import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useDb } from "@/db/DbProvider"
import {
  forgetCullShots,
  getCullSession,
  listCullDecisions,
  saveCullDecisions,
  touchCullSession,
} from "@/db/queries"
import type { CullFolderKey, CullSessionRecord } from "@/db/types"
import type { BrowseLocation } from "@/features/locations/useBrowseLocation"
import { HoardSmb } from "@/plugins/smb"
import type { CullMetadata, CullRef, CullShot, SmbAccess } from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"
import {
  applyChange,
  changeOf,
  type CullDecision,
  type DecisionChange,
  pushUndo,
  sortByCapture,
} from "./model"

/** Metadata is read in batches of this many shots (two small reads per RAW natively). */
const METADATA_BATCH = 60

export interface CullFolder {
  key: CullFolderKey
  status: "loading" | "ready" | "error"
  error?: unknown
  /** Every shot, in capture order once metadata is in (name order until then). */
  shots: CullShot[]
  metadata: ReadonlyMap<string, CullMetadata | undefined>
  /** Every shot's metadata was asked for (some may have failed). */
  metadataDone: boolean
  decisions: ReadonlyMap<string, CullDecision>
  access?: SmbAccess
  libRaw: boolean
  session: CullSessionRecord | null
  canUndo: boolean
  /** Run a cull call with the current handle (and SMB session); reopens once if the handle expired. */
  call: <T>(fn: (ref: CullRef) => Promise<T>) => Promise<T>
  /** Change some shots' decisions (one undo step). Returns the change, or null when nothing changed. */
  decide: (
    shotIds: readonly string[],
    next: (d: CullDecision, shotId: string) => CullDecision
  ) => DecisionChange | null
  undo: () => DecisionChange | null
  /** Shots that no longer exist (deleted rejects): dropped from the list and from the database. */
  removeShots: (shotIds: readonly string[]) => Promise<void>
  setLastShot: (shotId: string) => void
  /** Run a database write after the folder's other writes (the SQLite adapters cannot overlap transactions). */
  write: <T>(fn: () => Promise<T>) => Promise<T>
  reload: () => void
}

/**
 * A folder opened for culling (ADR 0008): its shots (paired natively), their EXIF, the decisions
 * saved in the database, undo, and the culling session's times. Decisions are applied at once and
 * saved in the background; a failed save shows in `onSaveError`.
 */
export function useCullFolder(
  location: BrowseLocation,
  path: string,
  onSaveError: (error: unknown) => void
): CullFolder {
  const db = useDb()
  const key = useMemo<CullFolderKey>(
    () => ({ locationId: location.id, share: location.share ?? "", path }),
    [location.id, location.share, path]
  )
  const [attempt, setAttempt] = useState(0)
  const [state, setState] = useState<{
    status: CullFolder["status"]
    error?: unknown
    shots: CullShot[]
    access?: SmbAccess
    libRaw: boolean
  }>({ status: "loading", shots: [], libRaw: true })
  const [metadata, setMetadata] = useState<Map<string, CullMetadata | undefined>>(new Map())
  const [metadataDone, setMetadataDone] = useState(false)
  const [decisions, setDecisions] = useState<Map<string, CullDecision>>(new Map())
  const [session, setSession] = useState<CullSessionRecord | null>(null)
  const [undoStack, setUndoStack] = useState<DecisionChange[]>([])

  const locationRef = useRef(location)
  useEffect(() => {
    locationRef.current = location
  })
  const decisionsRef = useRef(decisions)
  useEffect(() => {
    decisionsRef.current = decisions
  })
  // Database writes run one after another: the SQLite adapters treat a transaction that starts
  // while another is open as nested, so two quick decisions must not overlap.
  const writes = useRef<Promise<unknown>>(Promise.resolve())
  const serial = useCallback(<T>(write: () => Promise<T>): Promise<T> => {
    const next = writes.current.then(write, write)
    writes.current = next.catch(() => {})
    return next
  }, [])
  const handle = useRef<{ cullId: string } | null>(null)
  const reopening = useRef<Promise<string> | null>(null)
  const onSaveErrorRef = useRef(onSaveError)
  useEffect(() => {
    onSaveErrorRef.current = onSaveError
  })

  const open = useCallback(async () => {
    const result = await locationRef.current.run((loc) =>
      HoardSmb.cullOpen({ location: loc, path })
    )
    handle.current = { cullId: result.cullId }
    return result
  }, [path])

  const call = useCallback(
    async <T>(fn: (ref: CullRef) => Promise<T>): Promise<T> => {
      const run = (cullId: string) =>
        locationRef.current.run((loc) => fn({ cullId, location: loc }))
      const cullId =
        handle.current?.cullId ?? (await (reopening.current ??= open().then((r) => r.cullId)))
      try {
        return await run(cullId)
      } catch (error) {
        if (smbErrorCode(error) !== "NOT_FOUND") throw error
        // The handle may have expired (the app process restarted): open again, retry once.
        if (handle.current?.cullId === cullId) {
          handle.current = null
          reopening.current = open().then((r) => r.cullId)
        }
        const fresh = await (reopening.current ?? Promise.resolve(handle.current!.cullId))
        reopening.current = null
        return run(fresh)
      }
    },
    [open]
  )

  // Open: list + pair natively, then decisions and session from the database, then EXIF in batches.
  useEffect(() => {
    let cancelled = false
    let cullId: string | undefined
    // The page is keyed by location and folder, so state starts fresh; `reload` resets it itself.
    void (async () => {
      try {
        const opened = await open()
        cullId = opened.cullId
        const [rows, previous] = await Promise.all([
          listCullDecisions(db, key),
          getCullSession(db, key),
        ])
        if (cancelled) return
        setDecisions(new Map(rows.map((r) => [r.shot_id, { flag: r.flag, rating: r.rating }])))
        setSession(previous)
        setState({
          status: "ready",
          shots: opened.shots,
          access: opened.access,
          libRaw: opened.libRaw,
        })
        await serial(() => touchCullSession(db, key, { shotCount: opened.shots.length }))
        const ids = opened.shots.map((s) => s.id)
        for (let start = 0; start < ids.length && !cancelled; start += METADATA_BATCH) {
          const batch = ids.slice(start, start + METADATA_BATCH)
          try {
            const result = await call((ref) => HoardSmb.cullMetadata({ ...ref, shotIds: batch }))
            if (cancelled) return
            setMetadata((current) => {
              const next = new Map(current)
              for (const item of result.shots) next.set(item.id, item.metadata)
              return next
            })
          } catch {
            // A batch that fails leaves those shots without EXIF (name order, no overlay data).
            setMetadata((current) => {
              const next = new Map(current)
              for (const id of batch) if (!next.has(id)) next.set(id, undefined)
              return next
            })
          }
        }
        if (!cancelled) setMetadataDone(true)
      } catch (error) {
        if (!cancelled) setState({ status: "error", error, shots: [], libRaw: true })
      }
    })()
    return () => {
      cancelled = true
      if (cullId) void HoardSmb.cullClose({ cullId }).catch(() => {})
      handle.current = null
    }
    // `call` and `open` only change with the path, which `key` covers.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [db, key, attempt])

  const ordered = useMemo(
    () => (metadataDone ? sortByCapture(state.shots, metadata) : state.shots),
    [metadataDone, state.shots, metadata]
  )

  const persist = useCallback(
    (change: DecisionChange, undo: boolean) => {
      const values = undo ? change.before : change.after
      void serial(() =>
        saveCullDecisions(
          db,
          key,
          change.shotIds.map((shotId, i) => ({
            shotId,
            flag: values[i].flag,
            rating: values[i].rating,
          }))
        )
      ).catch((error: unknown) => onSaveErrorRef.current(error))
    },
    [db, key, serial]
  )

  const decide = useCallback(
    (shotIds: readonly string[], next: (d: CullDecision, shotId: string) => CullDecision) => {
      const change = changeOf(decisionsRef.current, shotIds, next)
      if (!change) return null
      const updated = applyChange(decisionsRef.current, change)
      decisionsRef.current = updated
      setDecisions(updated)
      setUndoStack((stack) => pushUndo(stack, change))
      persist(change, false)
      return change
    },
    [persist]
  )

  const undoStackRef = useRef(undoStack)
  useEffect(() => {
    undoStackRef.current = undoStack
  })
  const undo = useCallback(() => {
    const change = undoStackRef.current.at(-1)
    if (!change) return null
    const updated = applyChange(decisionsRef.current, change, true)
    decisionsRef.current = updated
    undoStackRef.current = undoStackRef.current.slice(0, -1)
    setDecisions(updated)
    setUndoStack(undoStackRef.current)
    persist(change, true)
    return change
  }, [persist])

  const removeShots = useCallback(
    async (shotIds: readonly string[]) => {
      const gone = new Set(shotIds)
      setState((current) => ({ ...current, shots: current.shots.filter((s) => !gone.has(s.id)) }))
      setDecisions((current) => {
        const next = new Map(current)
        for (const id of gone) next.delete(id)
        decisionsRef.current = next
        return next
      })
      setUndoStack((stack) => stack.filter((c) => !c.shotIds.some((id) => gone.has(id))))
      await serial(() => forgetCullShots(db, key, shotIds))
      await serial(() => touchCullSession(db, key, { shotCount: state.shots.length - gone.size }))
    },
    [db, key, serial, state.shots.length]
  )

  const lastShotTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const setLastShot = useCallback(
    (shotId: string) => {
      clearTimeout(lastShotTimer.current)
      lastShotTimer.current = setTimeout(() => {
        void serial(() => touchCullSession(db, key, { lastShotId: shotId })).catch(() => {})
      }, 800)
    },
    [db, key, serial]
  )
  useEffect(() => () => clearTimeout(lastShotTimer.current), [])

  return {
    key,
    status: state.status,
    error: state.error,
    shots: ordered,
    metadata,
    metadataDone,
    decisions,
    access: state.access,
    libRaw: state.libRaw,
    session,
    canUndo: undoStack.length > 0,
    call,
    decide,
    undo,
    removeShots,
    setLastShot,
    write: serial,
    reload: () => {
      setState({ status: "loading", shots: [], libRaw: true })
      setMetadata(new Map())
      setMetadataDone(false)
      setUndoStack([])
      setAttempt((n) => n + 1)
    },
  }
}
