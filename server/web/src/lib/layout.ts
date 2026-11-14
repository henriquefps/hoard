import type { Row } from "./types"
import { wallClock } from "./types"

/**
 * The gallery grid as a virtual list (ADR 0018 "Gallery"): a header per capture day, then rows of `columns` square
 * cells. Days come from the camera's wall clock (row.l, else row.t), read as UTC.
 */
export type Entry =
  | { type: "header"; day: string; count: number; first: number }
  | { type: "cells"; day: string; start: number; end: number }

export interface Month {
  key: string // YYYY-MM
  year: number
  month: number // 1..12
  entry: number // index of the first entry of the month
  count: number
}

export interface GridLayout {
  entries: Entry[]
  months: Month[]
  /** Index of the entry that shows each item (by position in `rows`). */
  entryOf: Int32Array
}

export const dayKey = (row: Row) => new Date(wallClock(row)).toISOString().slice(0, 10)

export function layout(rows: readonly Row[], columns: number): GridLayout {
  const cols = Math.max(1, Math.floor(columns))
  const entries: Entry[] = []
  const months: Month[] = []
  const entryOf = new Int32Array(rows.length)
  let i = 0
  while (i < rows.length) {
    const day = dayKey(rows[i])
    let j = i + 1
    while (j < rows.length && dayKey(rows[j]) === day) j++
    const month = day.slice(0, 7)
    const last = months[months.length - 1]
    if (!last || last.key !== month) {
      months.push({ key: month, year: Number(day.slice(0, 4)), month: Number(day.slice(5, 7)), entry: entries.length, count: 0 })
    }
    // Items, not cells: an expanded stack repeats its item (Reveal's virtual copies, ADR 0020).
    let n = 0
    for (let k = i; k < j; k++) if (k === i || rows[k].id !== rows[k - 1].id) n++
    months[months.length - 1].count += n
    entries.push({ type: "header", day, count: n, first: i })
    for (let s = i; s < j; s += cols) {
      const end = Math.min(s + cols, j)
      for (let k = s; k < end; k++) entryOf[k] = entries.length
      entries.push({ type: "cells", day, start: s, end })
    }
    i = j
  }
  return { entries, months, entryOf }
}

/** Columns for a grid width: cells of about `target` px with `gap` px between them. */
export function columnsFor(width: number, target: number, gap: number): number {
  if (width <= 0) return 1
  return Math.max(2, Math.round((width + gap) / (target + gap)))
}

/** Years and their months, newest first (the jump bar). */
export function yearsOf(months: readonly Month[]): { year: number; months: Month[]; count: number }[] {
  const out: { year: number; months: Month[]; count: number }[] = []
  for (const m of months) {
    const last = out[out.length - 1]
    if (last && last.year === m.year) {
      last.months.push(m)
      last.count += m.count
    } else out.push({ year: m.year, months: [m], count: m.count })
  }
  return out
}

/** The month an entry belongs to (the last month starting at or before it). */
export function monthAt(months: readonly Month[], entry: number): Month | null {
  let lo = 0
  let hi = months.length - 1
  let found: Month | null = null
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (months[mid].entry <= entry) {
      found = months[mid]
      lo = mid + 1
    } else hi = mid - 1
  }
  return found
}
