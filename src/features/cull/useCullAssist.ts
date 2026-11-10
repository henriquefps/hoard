import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useDb } from "@/db/DbProvider"
import { type CullAnalysisInput, listCullAnalyses, saveCullAnalyses } from "@/db/queries"
import { useSetting } from "@/features/settings/settings"
import { HoardSmb } from "@/plugins/smb"
import type { CullAnalysis, CullShot } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"
import {
  ANALYSIS_VERSION,
  type AssistSettings,
  analysedFile,
  bestInBursts,
  burstMembers,
  DEFAULT_ASSIST,
  flagsOf,
  groupBursts,
  type ShotFlag,
} from "./assist"
import type { BurstPosition } from "./model"
import type { CullFolder } from "./useCullFolder"

/** Shots per `cullAnalyze` call: small, so the order follows the user (the shot on screen first). */
const BATCH = 3
/** Attempts of a batch that fails as a whole (an SMB hiccup) before its shots count as failed. */
const RETRIES = 3

export interface AnalysisProgress {
  /** Shots with an analysis (cached or measured now). */
  done: number
  total: number
  /** Shots that could not be analysed (unreadable, no image). */
  failed: number
  /** Native work is under way. */
  running: boolean
}

export interface CullAssist {
  settings: AssistSettings
  analyses: ReadonlyMap<string, CullAnalysis>
  flags: ReadonlyMap<string, ShotFlag[]>
  /** Bursts by capture time and similarity (time only until shots are analysed). */
  bursts: ReadonlyMap<string, BurstPosition>
  /** Burst id → its shot ids. */
  members: ReadonlyMap<string, string[]>
  /** Burst id → its best frame. */
  best: ReadonlyMap<string, string>
  /** Shot ids that are the best frame of their burst. */
  bestShots: ReadonlySet<string>
  progress: AnalysisProgress
  /** Faces can be detected on this device (true until native says otherwise). */
  facesAvailable: boolean
  /** Analyse around this shot next (the one on screen); null = sweep from the top. */
  focus: (shotId: string | null) => void
}

/** Is a cached row still about this file, measured by this version? */
function rowMatches(
  shot: CullShot,
  row: { path: string; size: number; modified_at: string | null; version: number }
) {
  const file = analysedFile(shot)
  return (
    row.path === file.path &&
    row.size === file.size &&
    (row.modified_at ?? null) === (file.modifiedAt ?? null) &&
    row.version === ANALYSIS_VERSION
  )
}

/**
 * Assisted culling for an open folder (ADR 0009): cached analyses from the database, then the
 * rest measured natively in small batches (the shot on screen and its neighbours first, then a
 * sweep of the folder), saved as they come. Never blocks culling: native queues the work behind
 * the viewer's own images, and the grid and viewer work with whatever is known so far.
 */
export function useCullAssist(folder: CullFolder): CullAssist {
  const db = useDb()
  const [blur] = useSetting("cullBlurSensitivity")
  const [eyes] = useSetting("cullEyesSensitivity")
  const [gap] = useSetting("cullBurstGapMs")
  const settings = useMemo<AssistSettings>(
    () => ({
      blur: blur ?? DEFAULT_ASSIST.blur,
      eyes: eyes ?? DEFAULT_ASSIST.eyes,
      burstGapMs: gap ?? DEFAULT_ASSIST.burstGapMs,
    }),
    [blur, eyes, gap]
  )
  const [analyses, setAnalyses] = useState<Map<string, CullAnalysis>>(new Map())
  const [failed, setFailed] = useState<Set<string>>(new Set())
  const [running, setRunning] = useState(false)
  const [cacheLoaded, setCacheLoaded] = useState(false)
  const [facesAvailable, setFacesAvailable] = useState(true)
  /** Bumped to restart the analysis loop (a folder reload). */
  const [kick, setKick] = useState(0)

  const shotsRef = useRef(folder.shots)
  useEffect(() => {
    shotsRef.current = folder.shots
  })
  const analysesRef = useRef(analyses)
  const failedRef = useRef(failed)
  const focusRef = useRef<{ id: string | null; urgent: boolean }>({ id: null, urgent: false })
  const folderRef = useRef(folder)
  useEffect(() => {
    folderRef.current = folder
  })

  const ready = folder.status === "ready"
  const keyString = `${folder.key.locationId}|${folder.key.share}|${folder.key.path}`

  // 1. Cached analyses of this folder.
  useEffect(() => {
    if (!ready) return
    let cancelled = false
    void listCullAnalyses(db, folder.key)
      .then((rows) => {
        if (cancelled) return
        const byPath = new Map(rows.map((r) => [r.path, r]))
        const cached = new Map<string, CullAnalysis>()
        for (const shot of shotsRef.current) {
          const row = byPath.get(analysedFile(shot).path)
          if (!row || !rowMatches(shot, row)) continue
          try {
            cached.set(shot.id, JSON.parse(row.analysis) as CullAnalysis)
          } catch {
            // A row that does not parse is measured again.
          }
        }
        analysesRef.current = cached
        setAnalyses(cached)
        // A (re)load also gives failed shots another chance.
        failedRef.current = new Set()
        setFailed(new Set())
        setKick((n) => n + 1)
      })
      .catch(() => {})
      .finally(() => !cancelled && setCacheLoaded(true))
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [db, keyString, ready])

  // 2. The rest, natively, nearest to the focus first.
  useEffect(() => {
    if (!ready || !cacheLoaded) return
    let cancelled = false
    const run = async () => {
      let attempts = 0
      setRunning(true)
      try {
        while (!cancelled) {
          const shots = shotsRef.current
          const pending = shots
            .map((s, index) => ({ s, index }))
            .filter(({ s }) => !analysesRef.current.has(s.id) && !failedRef.current.has(s.id))
          if (pending.length === 0) break
          const focusIndex = focusRef.current.id
            ? Math.max(
                0,
                shots.findIndex((s) => s.id === focusRef.current.id)
              )
            : 0
          // Ahead of the focus first (people move forward), then behind it.
          const distance = (index: number) =>
            index >= focusIndex ? index - focusIndex : (focusIndex - index) * 1.5 + 0.5
          const batch = pending
            .sort((a, b) => distance(a.index) - distance(b.index))
            .slice(0, BATCH)
            .map(({ s }) => s)
          const urgent = focusRef.current.urgent && batch[0].id === focusRef.current.id
          try {
            const result = await folderRef.current.call((ref) =>
              HoardSmb.cullAnalyze({ ...ref, shotIds: batch.map((s) => s.id), urgent })
            )
            if (cancelled) return
            attempts = 0
            setFacesAvailable(result.faces)
            const measured = new Map(analysesRef.current)
            const failedNow = new Set(failedRef.current)
            const rows: CullAnalysisInput[] = []
            for (const item of result.results) {
              const shot = batch.find((s) => s.id === item.id)
              if (!shot) continue
              if (item.analysis) {
                measured.set(item.id, item.analysis)
                const file = analysedFile(shot)
                rows.push({
                  path: file.path,
                  size: file.size,
                  modifiedAt: file.modifiedAt ?? null,
                  version: item.analysis.version,
                  analysis: JSON.stringify(item.analysis),
                })
              } else failedNow.add(item.id)
            }
            analysesRef.current = measured
            failedRef.current = failedNow
            setAnalyses(measured)
            setFailed(failedNow)
            const key = folderRef.current.key
            void folderRef.current.write(() => saveCullAnalyses(db, key, rows)).catch(() => {})
          } catch (error) {
            if (cancelled || smbErrorCode(error) === "CANCELLED") return
            attempts++
            if (attempts >= RETRIES) {
              const failedNow = new Set(failedRef.current)
              batch.forEach((s) => failedNow.add(s.id))
              failedRef.current = failedNow
              setFailed(failedNow)
              attempts = 0
            } else {
              await new Promise((r) => setTimeout(r, 1500 * attempts))
            }
          }
        }
      } finally {
        if (!cancelled) setRunning(false)
      }
    }
    void run()
    return () => {
      cancelled = true
      setRunning(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, cacheLoaded, keyString, kick, folder.shots.length])

  const focus = useCallback((shotId: string | null) => {
    focusRef.current = { id: shotId, urgent: shotId !== null }
  }, [])

  const flags = useMemo(() => {
    const out = new Map<string, ShotFlag[]>()
    for (const [id, a] of analyses) out.set(id, flagsOf(a, settings))
    return out
  }, [analyses, settings])

  const bursts = useMemo(
    () =>
      folder.metadataDone
        ? groupBursts(folder.shots, folder.metadata, analyses, settings.burstGapMs)
        : new Map<string, BurstPosition>(),
    [folder.metadataDone, folder.shots, folder.metadata, analyses, settings.burstGapMs]
  )
  const members = useMemo(() => burstMembers(folder.shots, bursts), [folder.shots, bursts])
  const best = useMemo(
    () => bestInBursts(folder.shots, bursts, analyses, settings),
    [folder.shots, bursts, analyses, settings]
  )
  const bestShots = useMemo(() => new Set(best.values()), [best])

  const progress = useMemo<AnalysisProgress>(() => {
    let done = 0
    let failedCount = 0
    for (const shot of folder.shots) {
      if (analyses.has(shot.id)) done++
      else if (failed.has(shot.id)) failedCount++
    }
    return { done, total: folder.shots.length, failed: failedCount, running }
  }, [folder.shots, analyses, failed, running])

  return {
    settings,
    analyses,
    flags,
    bursts,
    members,
    best,
    bestShots,
    progress,
    facesAvailable,
    focus,
  }
}
