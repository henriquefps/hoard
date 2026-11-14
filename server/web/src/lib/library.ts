import type { LibraryAnswer, Row } from "./types"

/** The browser's copy of the index: rows newest first, by id. */
export interface Library {
  cursor: string
  rows: Row[]
  byId: Map<string, Row>
  folders: Record<string, string>
}

export const EMPTY_LIBRARY: Library = { cursor: "", rows: [], byId: new Map(), folders: {} }

export const compareRows = (a: Row, b: Row) => b.t - a.t || (a.id < b.id ? 1 : a.id > b.id ? -1 : 0)

/**
 * Applies an answer of GET /web/library: the whole index (full) or the rows changed and removed since the cursor.
 * Returns the same object when nothing changed, so React skips the render.
 */
export function mergeLibrary(lib: Library, ans: LibraryAnswer): Library {
  if (ans.full) {
    const rows = [...ans.rows].sort(compareRows)
    return { cursor: ans.cursor, rows, byId: new Map(rows.map((r) => [r.id, r])), folders: ans.folders }
  }
  if (ans.rows.length === 0 && ans.removed.length === 0) {
    return ans.cursor === lib.cursor ? lib : { ...lib, cursor: ans.cursor }
  }
  const byId = new Map(lib.byId)
  for (const id of ans.removed) byId.delete(id)
  for (const r of ans.rows) byId.set(r.id, r)
  const rows = [...byId.values()].sort(compareRows)
  return { cursor: ans.cursor, rows, byId, folders: { ...lib.folders, ...ans.folders } }
}

/** Replaces one row in place (an optimistic culling change); the order does not change. */
export function patchRow(lib: Library, id: string, patch: (r: Row) => Row): Library {
  const old = lib.byId.get(id)
  if (!old) return lib
  const next = patch(old)
  const byId = new Map(lib.byId)
  byId.set(id, next)
  return { ...lib, byId, rows: lib.rows.map((r) => (r.id === id ? next : r)) }
}
