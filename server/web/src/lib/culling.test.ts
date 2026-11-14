import { describe, expect, it } from "vitest"
import { actionForKey, apply, changesFor, collapse, decisionOf, fieldRevs, withRevs, type Decision } from "./culling"
import type { Row } from "./types"

const row = (over: Partial<Row> = {}): Row => ({ id: "a", t: 0, k: "photo", n: "A.JPG", ...over })
const none: Decision = { flag: null, rating: 0 }

describe("keys (Hoard's culling keys, ADR 0008)", () => {
  it("maps P, X, U, 0-5 and nothing else", () => {
    expect(actionForKey("p")).toEqual({ kind: "flag", flag: "pick", mode: "toggle" })
    expect(actionForKey("P")).toEqual({ kind: "flag", flag: "pick", mode: "toggle" })
    expect(actionForKey("x")).toEqual({ kind: "flag", flag: "reject", mode: "toggle" })
    expect(actionForKey("u")).toEqual({ kind: "unflag" })
    expect(actionForKey("0")).toEqual({ kind: "rate", stars: 0, mode: "set" })
    expect(actionForKey("3")).toEqual({ kind: "rate", stars: 3, mode: "toggle" })
    for (const k of ["6", "z", "Escape", "ArrowLeft", " "]) expect(actionForKey(k)).toBeNull()
  })
})

describe("apply", () => {
  it("toggles pick and reject; U clears the flag and keeps the stars", () => {
    const pick = apply(none, { kind: "flag", flag: "pick", mode: "toggle" })
    expect(pick).toEqual({ flag: "pick", rating: 0 })
    expect(apply(pick, { kind: "flag", flag: "pick", mode: "toggle" })).toEqual(none)
    expect(apply(pick, { kind: "flag", flag: "reject", mode: "toggle" })).toEqual({ flag: "reject", rating: 0 })
    expect(apply({ flag: "reject", rating: 4 }, { kind: "unflag" })).toEqual({ flag: null, rating: 4 })
    expect(apply(pick, { kind: "flag", flag: "pick", mode: "set" })).toEqual(pick)
  })

  it("a reject keeps its stars (ADR 0016)", () => {
    expect(apply({ flag: null, rating: 4 }, { kind: "flag", flag: "reject", mode: "toggle" })).toEqual({ flag: "reject", rating: 4 })
  })

  it("rating a rejected shot makes it a pick; the same number again clears; 0 clears", () => {
    expect(apply({ flag: "reject", rating: 0 }, { kind: "rate", stars: 3, mode: "toggle" })).toEqual({ flag: "pick", rating: 3 })
    expect(apply({ flag: null, rating: 3 }, { kind: "rate", stars: 3, mode: "toggle" })).toEqual({ flag: null, rating: 0 })
    expect(apply({ flag: "pick", rating: 3 }, { kind: "rate", stars: 0, mode: "set" })).toEqual({ flag: "pick", rating: 0 })
    expect(apply({ flag: "reject", rating: 2 }, { kind: "rate", stars: 0, mode: "set" })).toEqual({ flag: "reject", rating: 0 })
    expect(apply(none, { kind: "rate", stars: 9, mode: "set" })).toEqual({ flag: null, rating: 5 })
  })
})

describe("changes for the server", () => {
  it("one change per field that changed, with the field's revision as baseRev", () => {
    const r = row({ x: "reject", r: 2, cr: "17.21.0" })
    const before = decisionOf(r)
    const after = apply(before, { kind: "rate", stars: 4, mode: "toggle" })
    expect(changesFor(r, before, after, 1000)).toEqual([
      { itemId: "a", field: "rating", value: 4, baseRev: 17, modifiedAt: 1000 },
      { itemId: "a", field: "flag", value: "pick", baseRev: 21, modifiedAt: 1000 },
    ])
    expect(changesFor(r, before, before, 1000)).toEqual([])
    expect(changesFor(row(), none, { flag: null, rating: 0 }, 1)).toEqual([])
    expect(changesFor(row(), { flag: "pick", rating: 0 }, none, 5)).toEqual([
      { itemId: "a", field: "flag", value: "none", baseRev: 0, modifiedAt: 5 },
    ])
  })

  it("reads and updates field revisions", () => {
    expect(fieldRevs(row())).toEqual({ rating: 0, flag: 0 })
    expect(fieldRevs(row({ cr: "3.0.9" }))).toEqual({ rating: 3, flag: 0 })
    expect(withRevs(row({ cr: "3.0.9" }), { flag: 12 }).cr).toBe("3.12.9")
    expect(withRevs(row(), { rating: 4 }).cr).toBe("4.0.0")
  })

  it("collapses pending changes per item and field to the last value, keeping the first baseRev and order of the last", () => {
    const q = collapse([
      { itemId: "a", field: "flag", value: "pick", baseRev: 5, modifiedAt: 1 },
      { itemId: "b", field: "flag", value: "reject", baseRev: 2, modifiedAt: 2 },
      { itemId: "a", field: "flag", value: "none", baseRev: 6, modifiedAt: 3 },
      { itemId: "a", field: "rating", value: 2, baseRev: 1, modifiedAt: 4 },
    ])
    expect(q).toEqual([
      { itemId: "b", field: "flag", value: "reject", baseRev: 2, modifiedAt: 2 },
      { itemId: "a", field: "flag", value: "none", baseRev: 5, modifiedAt: 3 },
      { itemId: "a", field: "rating", value: 2, baseRev: 1, modifiedAt: 4 },
    ])
  })
})
