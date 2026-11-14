import type { Edit, Row } from "./types"

/**
 * Reveal's edited previews in the gallery (ADR 0020): a cell shows an item's primary edit; the item's other edits
 * (its virtual copies) form a stack that expands into cells of their own after it. Culling stays per item.
 */

/** The edit an item's cell shows: its primary, if any. */
export const primaryEdit = (row: Row): Edit | undefined => row.ed?.find((e) => e.p)

/** Edited in Reveal: an uploaded edit, or (before Reveal uploaded them) the index's "edited" flag from its records. */
export const isEdited = (row: Row) => !!row.e || !!row.ed?.length

/** The edits in the item's stack: every edit its own cell does not show. */
export const stackOf = (row: Row): Edit[] => {
  const shown = primaryEdit(row)
  return (row.ed ?? []).filter((e) => e !== shown)
}

/** A grid cell: an item, or one edit of its stack. */
export interface GridCell {
  row: Row
  /** A stack cell: the edit it shows. */
  edit?: Edit
  key: string
}

/** The grid's cells: every row, followed by the cells of its stack when it is expanded. */
export function expandStacks(rows: readonly Row[], expanded: ReadonlySet<string>): GridCell[] {
  const out: GridCell[] = []
  for (const row of rows) {
    out.push({ row, key: row.id })
    if (!expanded.has(row.id)) continue
    for (const edit of stackOf(row)) out.push({ row, edit, key: `${row.id}~${edit.id}` })
  }
  return out
}

/** The edit a cell shows (a stack cell's, else the item's primary). */
export const cellEdit = (c: GridCell): Edit | undefined => c.edit ?? primaryEdit(c.row)

/** The edit the viewer opens on: the one asked for (a stack cell), else the primary, else none (the original). */
export function initialEdit(row: Row | undefined, asked: string | null): Edit | undefined {
  if (!row?.ed?.length) return undefined
  return row.ed.find((e) => e.id === asked) ?? primaryEdit(row)
}

/** What E toggles to: from an edit to the original; from the original back to the last edit (else primary, first). */
export function toggleEdit(
  row: Row,
  current: Edit | undefined,
  last: string | null
): Edit | undefined {
  if (current) return undefined
  return row.ed?.find((e) => e.id === last) ?? primaryEdit(row) ?? row.ed?.[0]
}
