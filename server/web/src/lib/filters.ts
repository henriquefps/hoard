import type { Kind, Row } from "./types"
import { wallClock } from "./types"

/**
 * Gallery filters (ADR 0018 "Gallery"), applied in the browser over the library index. Every filter is AND-ed; an
 * empty list means "any". Dates are calendar days of the camera's wall clock, inclusive.
 */
export type FlagFilter = "pick" | "reject" | "none"

export const UPLOADS = "uploads"

export interface Filters {
  kinds: Kind[]
  cameras: string[] // "" = no camera recorded
  minRating: number
  flags: FlagFilter[]
  /** A NAS folder id (or UPLOADS) and a directory inside it ("" = the whole folder). */
  folder: { id: string; dir: string } | null
  from: string | null // YYYY-MM-DD
  to: string | null
  text: string
  edited: boolean
}

export const NO_FILTERS: Filters = Object.freeze({
  kinds: [],
  cameras: [],
  minRating: 0,
  flags: [],
  folder: null,
  from: null,
  to: null,
  text: "",
  edited: false,
}) as Filters

export const KINDS: Kind[] = ["photo", "pair", "video", "motion"]
export const FLAGS: FlagFilter[] = ["pick", "reject", "none"]

const DAY = 86_400_000

/** The UTC midnight of a YYYY-MM-DD day, or null when malformed. */
export function dayStart(day: string | null): number | null {
  if (!day || !/^\d{4}-\d{2}-\d{2}$/.test(day)) return null
  const t = Date.parse(day + "T00:00:00Z")
  return Number.isNaN(t) ? null : t
}

/** Lower-cased words of a search ("dsc 2024/05" → ["dsc", "2024/05"]). */
export const searchTerms = (text: string) => text.toLowerCase().split(/\s+/).filter(Boolean)

/** Whether every term is in the file name, the folder's name or the directory (semantic search comes later). */
export function matchesText(row: Row, terms: string[], folders: Record<string, string>): boolean {
  if (terms.length === 0) return true
  const hay = `${row.n} ${row.fo ? (folders[row.fo] ?? "") : ""} ${row.d ?? ""}`.toLowerCase()
  return terms.every((t) => hay.includes(t))
}

export function activeCount(f: Filters): number {
  return (
    (f.kinds.length ? 1 : 0) +
    (f.cameras.length ? 1 : 0) +
    (f.minRating ? 1 : 0) +
    (f.flags.length ? 1 : 0) +
    (f.folder ? 1 : 0) +
    (f.from || f.to ? 1 : 0) +
    (f.edited ? 1 : 0)
  )
}

export function matches(row: Row, f: Filters, terms: string[], folders: Record<string, string>): boolean {
  if (f.kinds.length && !f.kinds.includes(row.k)) return false
  if (f.cameras.length && !f.cameras.includes(row.cam ?? "")) return false
  if (f.minRating && (row.r ?? 0) < f.minRating) return false
  if (f.flags.length && !f.flags.includes(row.x ?? "none")) return false
  if (f.edited && !row.e) return false
  if (f.folder) {
    if (f.folder.id === UPLOADS) {
      if (row.fo) return false
    } else {
      if (row.fo !== f.folder.id) return false
      const dir = row.d ?? ""
      if (f.folder.dir && dir !== f.folder.dir && !dir.startsWith(f.folder.dir + "/")) return false
    }
  }
  const from = dayStart(f.from)
  const to = dayStart(f.to)
  if (from !== null || to !== null) {
    const t = wallClock(row)
    if (from !== null && t < from) return false
    if (to !== null && t >= to + DAY) return false
  }
  return matchesText(row, terms, folders)
}

export function applyFilters(rows: readonly Row[], f: Filters, folders: Record<string, string>): Row[] {
  const terms = searchTerms(f.text)
  if (activeCount(f) === 0 && terms.length === 0) return rows as Row[]
  return rows.filter((r) => matches(r, f, terms, folders))
}

/** Cameras in the library with their counts, most used first ("" = unknown). */
export function cameraFacets(rows: readonly Row[]): { camera: string; count: number }[] {
  const counts = new Map<string, number>()
  for (const r of rows) counts.set(r.cam ?? "", (counts.get(r.cam ?? "") ?? 0) + 1)
  return [...counts.entries()]
    .map(([camera, count]) => ({ camera, count }))
    .sort((a, b) => b.count - a.count || a.camera.localeCompare(b.camera))
}

// --- URL form: filters live in the query string, so a view can be bookmarked and Back works. ---

export function filtersToSearch(f: Filters): URLSearchParams {
  const p = new URLSearchParams()
  if (f.text) p.set("q", f.text)
  if (f.kinds.length) p.set("kind", f.kinds.join(","))
  if (f.cameras.length) p.set("camera", JSON.stringify(f.cameras))
  if (f.minRating) p.set("stars", String(f.minRating))
  if (f.flags.length) p.set("flag", f.flags.join(","))
  if (f.folder) p.set("folder", f.folder.dir ? `${f.folder.id}:${f.folder.dir}` : f.folder.id)
  if (f.from) p.set("from", f.from)
  if (f.to) p.set("to", f.to)
  if (f.edited) p.set("edited", "1")
  return p
}

export function filtersFromSearch(p: URLSearchParams): Filters {
  const list = <T extends string>(v: string | null, allowed: readonly T[]) =>
    (v ?? "").split(",").filter((x): x is T => (allowed as readonly string[]).includes(x))
  let cameras: string[] = []
  try {
    const parsed: unknown = JSON.parse(p.get("camera") ?? "[]")
    if (Array.isArray(parsed)) cameras = parsed.filter((c): c is string => typeof c === "string")
  } catch {
    cameras = []
  }
  const stars = Number(p.get("stars"))
  const folder = p.get("folder")
  let parsedFolder: Filters["folder"] = null
  if (folder) {
    const i = folder.indexOf(":")
    parsedFolder = i < 0 ? { id: folder, dir: "" } : { id: folder.slice(0, i), dir: folder.slice(i + 1) }
  }
  return {
    text: p.get("q") ?? "",
    kinds: list(p.get("kind"), KINDS),
    cameras,
    minRating: Number.isInteger(stars) && stars >= 1 && stars <= 5 ? stars : 0,
    flags: list(p.get("flag"), FLAGS),
    folder: parsedFolder,
    from: dayStart(p.get("from")) !== null ? p.get("from") : null,
    to: dayStart(p.get("to")) !== null ? p.get("to") : null,
    edited: p.get("edited") === "1",
  }
}
