import { describe, expect, it } from "vitest"
import { columnsFor, layout, monthAt, yearsOf } from "./layout"
import type { Row } from "./types"

const at = (iso: string, id: string, l?: string): Row => ({ id, t: Date.parse(iso), l: l ? Date.parse(l) : undefined, k: "photo", n: id })

describe("grid layout", () => {
  it("a header per day, rows of N cells, months for the jump bar", () => {
    const rows = [
      at("2024-05-03T10:00:00Z", "a"),
      at("2024-05-03T09:00:00Z", "b"),
      at("2024-05-03T08:00:00Z", "c"),
      at("2024-05-01T23:30:00Z", "d", "2024-05-02T02:30:00Z"), // wall clock May 2
      at("2024-04-30T08:00:00Z", "e"),
      at("2023-12-01T08:00:00Z", "f"),
    ]
    const g = layout(rows, 2)
    expect(g.entries.map((e) => (e.type === "header" ? `H${e.day}:${e.count}` : `${e.start}-${e.end}`))).toEqual([
      "H2024-05-03:3",
      "0-2",
      "2-3",
      "H2024-05-02:1",
      "3-4",
      "H2024-04-30:1",
      "4-5",
      "H2023-12-01:1",
      "5-6",
    ])
    expect(g.months.map((m) => `${m.key}@${m.entry}x${m.count}`)).toEqual(["2024-05@0x4", "2024-04@5x1", "2023-12@7x1"])
    expect([...g.entryOf]).toEqual([1, 1, 2, 4, 6, 8])
    expect(yearsOf(g.months).map((y) => `${y.year}:${y.count}:${y.months.length}`)).toEqual(["2024:5:2", "2023:1:1"])
    expect(monthAt(g.months, 0)?.key).toBe("2024-05")
    expect(monthAt(g.months, 6)?.key).toBe("2024-04")
    expect(monthAt(g.months, 99)?.key).toBe("2023-12")
  })
  it("empty library", () => {
    const g = layout([], 4)
    expect(g.entries).toEqual([])
    expect(monthAt(g.months, 0)).toBeNull()
  })
  it("20,000 items lay out quickly", () => {
    const rows: Row[] = []
    for (let i = 0; i < 20000; i++) rows.push({ id: String(i), t: Date.UTC(2024, 0, 1, 23) - i * 3_600_000, k: "photo", n: "x" })
    const t0 = performance.now()
    const g = layout(rows, 8)
    expect(performance.now() - t0).toBeLessThan(500)
    expect(g.entries.filter((e) => e.type === "header")).toHaveLength(Math.ceil(20000 / 24))
  })
  it("columns for a width", () => {
    expect(columnsFor(1200, 180, 4)).toBe(7)
    expect(columnsFor(300, 180, 4)).toBe(2)
    expect(columnsFor(0, 180, 4)).toBe(1)
  })
})
