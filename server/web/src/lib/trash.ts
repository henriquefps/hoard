import { api, ApiError } from "./api"
import type { TrashEntry } from "./compress"

/**
 * The trash from the web gallery (ADR 0023 Amendment (0.14.0)): Move to trash on a selection (in batches of 500, so
 * Select all of thousands stays responsive), and the Trash view's units: a shot's files in one day folder of a NAS
 * folder (the .hoard-trash layout Hoard writes, so Hoard's Trash lists the same files), or the server trash of an item
 * that is not on the NAS (phone backups, uploads).
 */

export const TRASH_BATCH = 500

/** One trashed shot's unit: what Restore and Delete permanently act on. */
export interface TrashUnit {
  key: string
  source: "nas" | "server"
  day: string
  folderId?: string
  folderName?: string
  dir: string
  device?: string
  files: number
  size: number
  held?: boolean
  purgeAfter?: number
  byServer?: boolean
}

export interface MoveAnswer {
  moved: string[]
  skipped: Record<string, string>
  failed: Record<string, string>
  counts: Record<string, number>
  warnings?: { onlyCopy: string[]; edits: string[] }
  nasShots: number
  nasFiles: number
  serverItems: number
  size: number
  nasBlocked?: string
}

/** What a dry run of a whole selection says (the batches summed). */
export interface MovePlan {
  shots: number
  nasShots: number
  nasFiles: number
  serverItems: number
  size: number
  skipped: Record<string, number>
  onlyCopy: string[]
  edits: string[]
  nasBlocked?: string
}

export const batches = <T>(list: T[], size = TRASH_BATCH): T[][] => {
  const out: T[][] = []
  for (let i = 0; i < list.length; i += size) out.push(list.slice(i, i + size))
  return out
}

/** Sums the dry runs of each batch of a selection (shots: the caller's count of what is not skipped). */
export function mergePlans(answers: MoveAnswer[]): MovePlan {
  const p: MovePlan = { shots: 0, nasShots: 0, nasFiles: 0, serverItems: 0, size: 0, skipped: {}, onlyCopy: [], edits: [] }
  for (const a of answers) {
    p.nasShots += a.nasShots
    p.nasFiles += a.nasFiles
    p.serverItems += a.serverItems
    p.size += a.size
    for (const why of Object.values(a.skipped)) p.skipped[why] = (p.skipped[why] ?? 0) + 1
    p.onlyCopy.push(...(a.warnings?.onlyCopy ?? []))
    p.edits.push(...(a.warnings?.edits ?? []))
    p.nasBlocked ||= a.nasBlocked || undefined
  }
  return p
}

/** A dry run over the whole selection, batch after batch; onProgress gets the fraction done. */
export async function planMove(ids: string[], onProgress?: (f: number) => void): Promise<MovePlan> {
  const parts = batches(ids)
  const answers: MoveAnswer[] = []
  for (const [i, b] of parts.entries()) {
    answers.push(await api<MoveAnswer>("/web/trash/move", { method: "POST", json: { itemIds: b, dryRun: true } }))
    onProgress?.((i + 1) / parts.length)
  }
  const plan = mergePlans(answers)
  plan.shots = ids.length - Object.values(plan.skipped).reduce((s, n) => s + n, 0)
  return plan
}

export interface MoveResult {
  moved: string[]
  skipped: Record<string, number>
  failed: Record<string, number>
  /** The write mount's problem when the server refused (nothing moved from then on). */
  refused?: string
}

/** Moves a selection to the trash, batch after batch; a refusal (no usable write mount) stops before the next batch. */
export async function moveToTrash(ids: string[], onProgress?: (f: number) => void): Promise<MoveResult> {
  const out: MoveResult = { moved: [], skipped: {}, failed: {} }
  const parts = batches(ids)
  for (const [i, b] of parts.entries()) {
    try {
      const a = await api<MoveAnswer>("/web/trash/move", { method: "POST", json: { itemIds: b } })
      out.moved.push(...a.moved)
      for (const why of Object.values(a.skipped)) out.skipped[why] = (out.skipped[why] ?? 0) + 1
      for (const why of Object.values(a.failed)) out.failed[why] = (out.failed[why] ?? 0) + 1
    } catch (e) {
      if (e instanceof ApiError && e.status === 503) {
        out.refused = e.code === "unavailable" ? e.message : e.code
        break
      }
      throw e
    }
    onProgress?.((i + 1) / parts.length)
  }
  return out
}

export interface UnitRef {
  itemId: string
  key: string
}

export interface ActionAnswer {
  done: UnitRef[]
  refused: Record<string, string>
  names?: Record<string, string>
  keptBy?: string[]
  size: number
}

async function unitAction(path: string, units: UnitRef[]): Promise<ActionAnswer> {
  const out: ActionAnswer = { done: [], refused: {}, names: {}, keptBy: [], size: 0 }
  for (const b of batches(units)) {
    const a = await api<ActionAnswer>(path, { method: "POST", json: { units: b } })
    out.done.push(...a.done)
    Object.assign(out.refused, a.refused)
    Object.assign(out.names!, a.names ?? {})
    for (const k of a.keptBy ?? []) if (!out.keptBy!.includes(k)) out.keptBy!.push(k)
    out.size += a.size
  }
  return out
}

export const restoreUnits = (units: UnitRef[]) => unitAction("/web/trash/restore", units)
export const deleteUnits = (units: UnitRef[]) => unitAction("/web/trash/delete", units)

/** Empty trash: the page's count and size must still be the trash's; the server answers 409 with the current ones. */
export async function emptyTrash(count: number, size: number): Promise<ActionAnswer | { count: number; size: number }> {
  try {
    return await api<ActionAnswer>("/web/trash/empty", { method: "POST", json: { confirm: { count, size } } })
  } catch (e) {
    if (e instanceof ApiError && e.status === 409) {
      const fresh = await fetchEmptyFigures()
      if (fresh) return fresh
    }
    throw e
  }
}

async function fetchEmptyFigures(): Promise<{ count: number; size: number } | null> {
  const res = await fetch("/web/trash/empty", {
    method: "POST",
    headers: { "X-Hoard-Web": "1", "Content-Type": "application/json" },
    body: JSON.stringify({ confirm: { count: -1, size: -1 } }),
    credentials: "same-origin",
  })
  if (res.status !== 409) return null
  const body = (await res.json()) as { error?: { details?: { count?: number; size?: number } } }
  const d = body.error?.details
  return d && typeof d.count === "number" && typeof d.size === "number" ? { count: d.count, size: d.size } : null
}

/** A group of the Trash view: one day, one place (a NAS folder's directory, or a phone's backup in the server trash). */
export interface TrashGroup {
  key: string
  day: string
  source: "nas" | "server"
  place: string
  folderName?: string
  device?: string
  dir: string
  shots: TrashEntry[]
  units: UnitRef[]
  size: number
  held: number
}

/** The units of an entry, as GET /web/trash lists them (older servers: none). */
export const unitsOf = (e: TrashEntry): TrashUnit[] => e.units ?? []

/**
 * Groups the trash by day (newest first), then by place (folder or phone, then directory): a shot with files in two
 * day folders is in both groups, each with its own unit.
 */
export function groupTrash(entries: TrashEntry[]): TrashGroup[] {
  const byKey = new Map<string, TrashGroup>()
  for (const e of entries) {
    for (const u of unitsOf(e)) {
      const place = u.source === "server" ? `server|${u.device ?? ""}|${u.dir}` : `nas|${u.folderId ?? ""}|${u.dir}`
      const key = `${u.day}|${place}`
      let g = byKey.get(key)
      if (!g) {
        g = { key, day: u.day, source: u.source, place, folderName: u.folderName, device: u.device, dir: u.dir, shots: [], units: [], size: 0, held: 0 }
        byKey.set(key, g)
      }
      if (!g.shots.includes(e)) g.shots.push(e)
      if (u.held) g.held++
      else g.units.push({ itemId: e.id, key: u.key })
      g.size += u.size
    }
  }
  return [...byKey.values()].sort((a, b) => (a.day === b.day ? a.place.localeCompare(b.place) : a.day < b.day ? 1 : -1))
}

/** Everything Empty takes: every unit not held, the number of shots and their size (what the server confirms). */
export function emptyFigures(entries: TrashEntry[]): { count: number; size: number } {
  let count = 0
  let size = 0
  for (const e of entries) {
    const us = unitsOf(e).filter((u) => !u.held)
    if (us.length) count++
    size += us.reduce((s, u) => s + u.size, 0)
  }
  return { count, size }
}

/** The units of a set of entries (a selection), held ones left out. */
export const unitsOfEntries = (entries: TrashEntry[]): UnitRef[] =>
  entries.flatMap((e) => unitsOf(e).filter((u) => !u.held).map((u) => ({ itemId: e.id, key: u.key })))
