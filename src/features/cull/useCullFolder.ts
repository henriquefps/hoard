import { App } from "@capacitor/app"
import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { useDb } from "@/db/DbProvider"
import {
  forgetCullShots,
  getCullSession,
  listCullDecisions,
  listCullSidecars,
  saveCullDecisions,
  saveCullSidecars,
  touchCullSession,
} from "@/db/queries"
import type { CullFolderKey, CullSessionRecord } from "@/db/types"
import type { BrowseLocation } from "@/features/locations/useBrowseLocation"
import { HoardSmb } from "@/plugins/smb"
import { useSetting } from "@/features/settings/settings"
import type {
  CullMetadata,
  CullRef,
  CullShot,
  CullSidecarRead,
  CullWriteSidecarsResult,
  SmbAccess,
} from "@/plugins/smb/definitions"
import { smbErrorCode } from "@/plugins/smb/definitions"
import {
  applyChange,
  changeOf,
  type CullDecision,
  type DecisionChange,
  pushUndo,
  sortByCapture,
  UNMARKED,
} from "./model"
import {
  importAfter,
  importChanges,
  importedDecision,
  type SidecarState,
  sidecarStateOf,
  wireOf,
} from "./xmp"

/** Metadata is read in batches of this many shots (two small reads per RAW natively). */
const METADATA_BATCH = 60

/** Sidecars are asked for in batches of this many shots (one listing, then only the newer ones are read). */
const SIDECAR_BATCH = 2000

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
  /**
   * Decisions stay in Hoard only: "Save ratings next to photos" is on but the folder is read-only (its listing says
   * so, or a write was refused this session). ADR 0011.
   */
  sidecarsReadOnly: boolean
}

/**
 * A folder opened for culling (ADR 0008): its shots (paired natively), their EXIF, the decisions
 * saved in the database, undo, and the culling session's times. Decisions are applied at once and
 * saved in the background; a failed save shows in `onSaveError`.
 *
 * XMP sidecars (ADR 0011): on open (and when the app comes back to the front) the shots' sidecars newer than what
 * Hoard last wrote or decided are read and imported as decisions; every decision is written into the shot's sidecar
 * natively (coalesced, flushed when the app leaves). Sidecars that could not be written are counted in
 * `onSidecarError`; a refused write makes the folder read-only for the session.
 */
export function useCullFolder(
  location: BrowseLocation,
  path: string,
  onSaveError: (error: unknown) => void,
  onSidecarError: (count: number) => void = () => {}
): CullFolder {
  const db = useDb()
  const saveXmp = useSetting("cullSaveXmp")[0] ?? true
  const hiddenXmp = useSetting("cullXmpHidden")[0] ?? false
  const xmpRef = useRef({ save: saveXmp, hidden: hiddenXmp })
  useEffect(() => {
    xmpRef.current = { save: saveXmp, hidden: hiddenXmp }
  })
  const [sidecarsRefused, setSidecarsRefused] = useState(false)
  /** What Hoard knows about each shot's sidecar (the `cull_sidecars` rows, kept current in memory). */
  const sidecarStates = useRef(new Map<string, SidecarState>())
  /** Shots decided since the folder opened (the open-time import leaves them alone). */
  const touched = useRef(new Set<string>())
  /** Sidecar writes not answered yet, per shot (a resume-time import leaves them alone). */
  const writing = useRef(new Map<string, number>())
  const onSidecarErrorRef = useRef(onSidecarError)
  useEffect(() => {
    onSidecarErrorRef.current = onSidecarError
  })
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
  const sidecarsReadOnly = state.access === "read" || sidecarsRefused
  const readOnlyRef = useRef(sidecarsReadOnly)
  useEffect(() => {
    readOnlyRef.current = sidecarsReadOnly
  })

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

  /**
   * Imports sidecars newer than Hoard's last write or decision (ADR 0011 "Precedence") as decisions. At open, shots
   * decided meanwhile are left alone; any time, shots whose own write is still on its way.
   */
  const applyImports = useCallback(
    (found: readonly CullSidecarRead[], atOpen: boolean) => {
      const next = new Map(decisionsRef.current)
      const changed: { shotId: string; flag: CullDecision["flag"]; rating: number }[] = []
      const rows: { shotId: string; sidecarMtime: number; synced: boolean }[] = []
      for (const item of found) {
        if (!item.fields || item.modifiedMillis === null) continue
        if (writing.current.has(item.id) || (atOpen && touched.current.has(item.id))) continue
        const current = next.get(item.id) ?? UNMARKED
        const imported = importedDecision(current, item.fields, item.hasValues ?? false)
        if (!imported) continue
        const state = sidecarStates.current.get(item.id)
        sidecarStates.current.set(item.id, {
          decidedAt: state?.decidedAt ?? null,
          sidecarMtime: item.modifiedMillis,
          synced: true,
        })
        rows.push({ shotId: item.id, sidecarMtime: item.modifiedMillis, synced: true })
        if (!importChanges(current, imported)) continue
        if (!imported.flag && imported.rating === 0) next.delete(item.id)
        else next.set(item.id, imported)
        changed.push({ shotId: item.id, ...imported })
      }
      if (changed.length) {
        decisionsRef.current = next
        setDecisions(next)
      }
      if (changed.length || rows.length) {
        void serial(async () => {
          await saveCullDecisions(db, key, changed)
          await saveCullSidecars(db, key, rows)
        }).catch((error: unknown) => onSaveErrorRef.current(error))
      }
      return changed.length
    },
    [db, key, serial]
  )

  /** Reads the sidecars of `ids` that may win over Hoard (`refresh`: list the folder again first). */
  const readSidecars = useCallback(
    async (ids: readonly string[], refresh: boolean, isCancelled: () => boolean) => {
      const atOpen = !refresh
      for (let start = 0; start < ids.length && !isCancelled(); start += SIDECAR_BATCH) {
        const shots = ids.slice(start, start + SIDECAR_BATCH).map((id) => ({
          id,
          after: importAfter(
            sidecarStates.current.get(id) ?? { decidedAt: null, sidecarMtime: null, synced: false }
          ),
        }))
        const result = await call((ref) =>
          HoardSmb.cullReadSidecars({
            ...ref,
            shots,
            ...(refresh && start === 0 ? { refresh } : {}),
          })
        )
        if (isCancelled()) return
        applyImports(result.shots, atOpen)
      }
    },
    [call, applyImports]
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
        // Sidecars (ADR 0011): what each shot's sidecar must be newer than, then import the newer ones.
        const sidecarRows = await listCullSidecars(db, key)
        if (cancelled) return
        const rowsById = new Map(sidecarRows.map((r) => [r.shot_id, r]))
        const decidedAt = new Map(rows.map((r) => [r.shot_id, r.updated_at]))
        sidecarStates.current = new Map(
          opened.shots.map((s) => [s.id, sidecarStateOf(rowsById.get(s.id), decidedAt.get(s.id))])
        )
        touched.current = new Set()
        writing.current = new Map()
        setSidecarsRefused(false)
        try {
          await readSidecars(
            opened.shots.map((s) => s.id),
            false,
            () => cancelled
          )
        } catch {
          // Unreadable sidecars leave Hoard's decisions as they are.
        }
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

  /** Records native's answer to a sidecar write: the mtime of what it wrote, the read-only folder, the failures. */
  const onSidecarsWritten = useCallback(
    (result: CullWriteSidecarsResult) => {
      const rows: { shotId: string; sidecarMtime: number; synced: boolean }[] = []
      let failed = 0
      for (const r of result.results) {
        const left = (writing.current.get(r.shotId) ?? 1) - 1
        if (left > 0) writing.current.set(r.shotId, left)
        else writing.current.delete(r.shotId)
        if (r.status === "malformed" || r.status === "failed") failed++
        if (r.status !== "written" || r.modifiedMillis === undefined) continue
        // Only when the sidecar now holds the shot's latest decision (a newer write may be on its way).
        const current = wireOf(decisionsRef.current.get(r.shotId) ?? UNMARKED)
        if (current.rating !== r.fields.rating || current.flag !== r.fields.flag) continue
        const state = sidecarStates.current.get(r.shotId)
        sidecarStates.current.set(r.shotId, {
          decidedAt: state?.decidedAt ?? null,
          sidecarMtime: r.modifiedMillis,
          synced: true,
        })
        rows.push({ shotId: r.shotId, sidecarMtime: r.modifiedMillis, synced: true })
      }
      if (result.readOnly) setSidecarsRefused(true)
      if (failed > 0) onSidecarErrorRef.current(failed)
      if (rows.length) void serial(() => saveCullSidecars(db, key, rows)).catch(() => {})
    },
    [db, key, serial]
  )

  const persist = useCallback(
    (change: DecisionChange, undo: boolean) => {
      const values = undo ? change.before : change.after
      const at = Date.now()
      for (const shotId of change.shotIds) {
        touched.current.add(shotId)
        const state = sidecarStates.current.get(shotId)
        sidecarStates.current.set(shotId, {
          decidedAt: at,
          sidecarMtime: state?.sidecarMtime ?? null,
          synced: false,
        })
      }
      void serial(async () => {
        await saveCullDecisions(
          db,
          key,
          change.shotIds.map((shotId, i) => ({
            shotId,
            flag: values[i].flag,
            rating: values[i].rating,
          }))
        )
        await saveCullSidecars(
          db,
          key,
          change.shotIds.map((shotId) => ({ shotId, decidedAt: at, synced: false }))
        )
      }).catch((error: unknown) => onSaveErrorRef.current(error))
      // The sidecars next to the photos (ADR 0011): native coalesces and writes them.
      const xmp = xmpRef.current
      if (!xmp.save || readOnlyRef.current) return
      const items = change.shotIds.map((shotId, i) => ({ shotId, ...wireOf(values[i]) }))
      for (const { shotId } of items)
        writing.current.set(shotId, (writing.current.get(shotId) ?? 0) + 1)
      void call((ref) => HoardSmb.cullWriteSidecars({ ...ref, items, hidden: xmp.hidden })).then(
        onSidecarsWritten,
        () => {
          for (const { shotId } of items) writing.current.delete(shotId)
          onSidecarErrorRef.current(items.length)
        }
      )
    },
    [db, key, serial, call, onSidecarsWritten]
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

  // Back from another app (Reveal, a file manager): sidecars changed there are read again (ADR 0011).
  const shotIdsRef = useRef<string[]>([])
  useEffect(() => {
    shotIdsRef.current = state.shots.map((s) => s.id)
  })
  useEffect(() => {
    if (state.status !== "ready") return
    let cancelled = false
    const refresh = () => {
      void readSidecars(shotIdsRef.current, true, () => cancelled).catch(() => {})
    }
    const handle = App.addListener("resume", refresh)
    return () => {
      cancelled = true
      void handle.then((h) => h.remove()).catch(() => {})
    }
  }, [state.status, readSidecars])

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
    sidecarsReadOnly: saveXmp && sidecarsReadOnly,
    reload: () => {
      setState({ status: "loading", shots: [], libRaw: true })
      setMetadata(new Map())
      setMetadataDone(false)
      setUndoStack([])
      setAttempt((n) => n + 1)
    },
  }
}
