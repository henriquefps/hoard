import { useSyncExternalStore } from "react"
import { api, ApiError } from "./api"
import {
  apply,
  changesFor,
  collapse,
  type CullAction,
  type Decision,
  decisionOf,
  type FieldChange,
  withDecision,
  withRevs,
} from "./culling"
import { EMPTY_LIBRARY, type Library, mergeLibrary, patchRow } from "./library"
import type { LibraryAnswer } from "./types"

/**
 * The library in the browser: loaded once, then kept current by polling GET /web/library with the cursor (culling made
 * in Hoard or Reveal appears without a reload, ADR 0018 "Live"), plus the culling writes made here: optimistic, queued
 * per field, pushed to POST /web/culling, undoable.
 */
export interface LibraryState {
  library: Library
  status: "loading" | "ready" | "error"
  error?: string
  /** Changes not yet confirmed by the server. */
  pending: number
}

let state: LibraryState = { library: EMPTY_LIBRARY, status: "loading", pending: 0 }
const listeners = new Set<() => void>()
const set = (next: Partial<LibraryState>) => {
  state = { ...state, ...next }
  listeners.forEach((l) => l())
}

export const useLibraryState = () =>
  useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => state
  )

export const getLibraryState = () => state

export const POLL_MS = 4000
let timer: number | undefined
let polling = false
let queue: FieldChange[] = []
let inflight: FieldChange[] = []

/** Keeps the values of fields that are still pending over what the server sent (ADR 0016 "Client rules" 3). */
function reapplyPending(lib: Library): Library {
  let out = lib
  for (const c of [...inflight, ...queue]) {
    out = patchRow(out, c.itemId, (r) => {
      const d = decisionOf(r)
      return withDecision(
        r,
        c.field === "rating"
          ? { ...d, rating: c.value as number }
          : { ...d, flag: c.value === "none" ? null : (c.value as Decision["flag"]) }
      )
    })
  }
  return out
}

export async function refreshLibrary(): Promise<void> {
  if (polling) return
  polling = true
  try {
    const ans = await api<LibraryAnswer>(`/web/library?cursor=${encodeURIComponent(state.library.cursor)}`)
    const merged = mergeLibrary(state.library, ans)
    const next = merged === state.library ? merged : reapplyPending(merged)
    if (next !== state.library || state.status !== "ready") set({ library: next, status: "ready", error: undefined })
  } catch (e) {
    if (state.status !== "ready") set({ status: "error", error: e instanceof Error ? e.message : String(e) })
  } finally {
    polling = false
  }
}

export function startPolling(): () => void {
  void refreshLibrary()
  const tick = () => {
    if (document.visibilityState === "visible") void refreshLibrary()
  }
  timer = window.setInterval(tick, POLL_MS)
  const onVisible = () => tick()
  document.addEventListener("visibilitychange", onVisible)
  return () => {
    window.clearInterval(timer)
    document.removeEventListener("visibilitychange", onVisible)
  }
}

// --- culling ---

interface UndoStep {
  ids: string[]
  before: Decision[]
}
const undoStack: UndoStep[] = []
export const UNDO_LIMIT = 200

/** Applies one action to items; with several, a toggle sets the flag unless every item has it (then clears it). */
export function decide(ids: string[], action: CullAction, record = true): number {
  const lib = state.library
  const rows = ids.map((id) => lib.byId.get(id)).filter((r) => r !== undefined)
  if (rows.length === 0) return 0
  let a = action
  if (rows.length > 1 && a.kind === "flag" && a.mode === "toggle") {
    const all = rows.every((r) => r.x === (a as { flag: string }).flag)
    a = { ...a, mode: all ? "toggle" : "set" }
  }
  if (rows.length > 1 && a.kind === "rate" && a.mode === "toggle") {
    const all = rows.every((r) => (r.r ?? 0) === (a as { stars: number }).stars)
    a = { ...a, mode: all ? "toggle" : "set" }
  }
  return setDecisions(
    rows.map((r) => r.id),
    rows.map((r) => apply(decisionOf(r), a)),
    record
  )
}

function setDecisions(ids: string[], afters: Decision[], record: boolean): number {
  let lib = state.library
  const now = Date.now()
  const step: UndoStep = { ids: [], before: [] }
  const changes: FieldChange[] = []
  ids.forEach((id, i) => {
    const row = lib.byId.get(id)
    if (!row) return
    const before = decisionOf(row)
    const c = changesFor(row, before, afters[i], now)
    if (c.length === 0) return
    changes.push(...c)
    step.ids.push(id)
    step.before.push(before)
    lib = patchRow(lib, id, (r) => withDecision(r, afters[i]))
  })
  if (changes.length === 0) return 0
  if (record) {
    undoStack.push(step)
    if (undoStack.length > UNDO_LIMIT) undoStack.shift()
  }
  queue.push(...changes)
  set({ library: lib, pending: queue.length + inflight.length })
  void flush()
  return step.ids.length
}

/** Undoes the last decision; returns the items it changed back (the viewer shows the first). */
export function undo(): string[] {
  const step = undoStack.pop()
  if (!step) return []
  setDecisions(step.ids, step.before, false)
  return step.ids
}

let retry: number | undefined

async function flush(): Promise<void> {
  if (inflight.length || queue.length === 0) return
  inflight = collapse(queue).slice(0, 500)
  const sent = new Set(inflight)
  const rest = collapse(queue).filter((c) => !sent.has(c))
  queue = rest
  try {
    const ans = await api<{
      results: {
        itemId: string
        field: "rating" | "flag" | "label"
        status: string
        winner?: string
        rev: number
      }[]
    }>("/web/culling", { method: "POST", json: { changes: inflight } })
    let lib = state.library
    let lost = false
    ans.results.forEach((r, i) => {
      // A change sent by fingerprint is not used here; results come back in order, item ids as sent (or followed).
      const id = inflight[i]?.itemId ?? r.itemId
      // "invalid" (an item that went away, ADR 0016): nothing was written; the next poll shows the server's state.
      if (r.status === "invalid") lost = true
      else lib = patchRow(lib, id, (row) => withRevs(row, { [r.field]: r.rev }))
      if (r.status === "conflict" && r.winner === "server") lost = true
    })
    inflight = []
    set({ library: lib, pending: queue.length })
    if (lost) void refreshLibrary()
  } catch (e) {
    if (e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 429) {
      // Refused (an item that went away): drop it and take the server's state.
      inflight = []
      set({ pending: queue.length, library: { ...state.library, cursor: "" } })
      void refreshLibrary()
    } else {
      queue = [...inflight, ...queue]
      inflight = []
      window.clearTimeout(retry)
      retry = window.setTimeout(() => void flush(), 5000)
      set({ pending: queue.length })
    }
    return
  }
  if (queue.length) void flush()
}

/** Download links: one file directly, several files (a pair, a selection) as a zip made by the server. */
export async function downloadZip(ids: string[]): Promise<{ name: string; files: number; bytes: number }> {
  const ans = await api<{ url: string; name: string; files: number; bytes: number }>("/web/downloads", {
    method: "POST",
    json: { items: ids },
  })
  startDownload(ans.url, ans.name)
  return ans
}

/** Starts a same-origin download with the browser's own download manager, named (the server names it too). */
export function startDownload(url: string, name = "") {
  const a = document.createElement("a")
  a.href = url
  a.rel = "noopener"
  a.download = name
  document.body.appendChild(a)
  a.click()
  a.remove()
}
