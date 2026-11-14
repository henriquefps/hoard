/** One item of the library index (GET /web/library, ADR 0018 "Library index"). Short keys as served. */
export interface Row {
  id: string
  /** sortTime (ADR 0014), Unix ms. */
  t: number
  /** The camera's wall clock read as UTC (ms) when it differs from t: dates are shown from it. */
  l?: number
  k: Kind
  w?: number
  h?: number
  /** The main file's name. */
  n: string
  /** NAS folder id ("" or absent: uploaded by a device). */
  fo?: string
  /** Directory inside the folder. */
  d?: string
  cam?: string
  lens?: string
  raw?: boolean
  /** Video duration, ms. */
  dur?: number
  /** Rating 1..5 (absent = 0). */
  r?: number
  x?: Flag
  lb?: string
  /** Edited in Reveal. */
  e?: boolean
  /** Image preview key when one is on disk. */
  v?: string
  /** Culling field revisions "rating.flag.label". */
  cr?: string
  size?: number
  /** Reveal's edited previews (ADR 0020), the primary first. */
  ed?: Edit[]
}

/** One of Reveal's edits of an item (ADR 0020): a version's rendering uploaded by Reveal. */
export interface Edit {
  /** Reveal's version id. */
  id: string
  /** Label ("Copy 1"; absent for the main version). */
  l?: string
  /** Primary: the grid shows it for the item. */
  p?: boolean
  /** A virtual copy. */
  c?: boolean
  /** Its preview key when one is on disk. */
  v?: string
  w?: number
  h?: number
}

export type Kind = "photo" | "pair" | "video" | "motion"
export type Flag = "pick" | "reject"

export interface LibraryAnswer {
  cursor: string
  full: boolean
  rows: Row[]
  removed: string[]
  folders: Record<string, string>
  total: number
}

/** The wall-clock time a row is shown and grouped by (ms, read as UTC). */
export const wallClock = (row: Row) => row.l ?? row.t
