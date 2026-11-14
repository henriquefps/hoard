import { describe, expect, it } from "vitest"
import {
  cellEdit,
  expandStacks,
  initialEdit,
  isEdited,
  primaryEdit,
  stackOf,
  toggleEdit,
} from "./edits"
import { applyFilters, NO_FILTERS } from "./filters"
import type { Row } from "./types"

const row = (id: string, extra: Partial<Row> = {}): Row => ({
  id,
  t: 1,
  k: "photo",
  n: `${id}.JPG`,
  ...extra,
})
const main = { id: "v-main", p: true, v: "k1" }
const copy1 = { id: "v-c1", l: "Copy 1", c: true }
const copy2 = { id: "v-c2", l: "Copy 2", c: true }

describe("edits", () => {
  it("finds the primary and the stack", () => {
    const r = row("a", { ed: [main, copy1, copy2] })
    expect(primaryEdit(r)).toBe(main)
    expect(stackOf(r)).toEqual([copy1, copy2])
    // No primary: the cell shows the original, every edit is in the stack.
    const n = row("b", { ed: [copy1] })
    expect(primaryEdit(n)).toBeUndefined()
    expect(stackOf(n)).toEqual([copy1])
    expect(stackOf(row("c"))).toEqual([])
  })

  it("expands only the stacks asked for, right after their item", () => {
    const rows = [row("a", { ed: [main, copy1, copy2] }), row("b"), row("c", { ed: [copy1] })]
    expect(expandStacks(rows, new Set()).map((c) => c.key)).toEqual(["a", "b", "c"])
    const cells = expandStacks(rows, new Set(["a", "c"]))
    expect(cells.map((c) => c.key)).toEqual(["a", "a~v-c1", "a~v-c2", "b", "c", "c~v-c1"])
    expect(cellEdit(cells[0])).toBe(main)
    expect(cellEdit(cells[1])).toBe(copy1)
    expect(cells[1].row).toBe(rows[0]) // culling a copy acts on its item
    expect(cellEdit(cells[3])).toBeUndefined()
  })

  it("the Edited filter takes uploaded edits and Reveal's records", () => {
    const rows = [row("a", { ed: [copy1] }), row("b", { e: true }), row("c")]
    expect(isEdited(rows[0]) && isEdited(rows[1]) && !isEdited(rows[2])).toBe(true)
    expect(applyFilters(rows, { ...NO_FILTERS, edited: true }, {}).map((r) => r.id)).toEqual([
      "a",
      "b",
    ])
  })

  it("the viewer opens on the asked edit, else the primary; E toggles with the original", () => {
    const r = row("a", { ed: [main, copy1] })
    expect(initialEdit(r, "v-c1")).toBe(copy1)
    expect(initialEdit(r, null)).toBe(main)
    expect(initialEdit(r, "unknown")).toBe(main)
    expect(initialEdit(row("x"), null)).toBeUndefined()
    expect(toggleEdit(r, copy1, null)).toBeUndefined()
    expect(toggleEdit(r, undefined, "v-c1")).toBe(copy1)
    expect(toggleEdit(r, undefined, null)).toBe(main)
    expect(toggleEdit(row("y", { ed: [copy2] }), undefined, null)).toBe(copy2)
  })
})
