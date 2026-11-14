import { describe, expect, it } from "vitest"
import { EMPTY_LIBRARY, mergeLibrary, patchRow } from "./library"
import type { LibraryAnswer, Row } from "./types"

const r = (id: string, t: number, over: Partial<Row> = {}): Row => ({ id, t, k: "photo", n: id, ...over })
const ans = (over: Partial<LibraryAnswer>): LibraryAnswer => ({ cursor: "b:1", full: false, rows: [], removed: [], folders: {}, total: 0, ...over })

describe("library merge", () => {
  it("a full answer replaces everything, newest first", () => {
    const lib = mergeLibrary(EMPTY_LIBRARY, ans({ full: true, rows: [r("a", 1), r("c", 3), r("b", 2)], cursor: "b:3" }))
    expect(lib.rows.map((x) => x.id)).toEqual(["c", "b", "a"])
    expect(lib.byId.get("b")?.t).toBe(2)
    expect(lib.cursor).toBe("b:3")
  })
  it("a delta changes, adds and removes rows; an empty delta keeps the object", () => {
    let lib = mergeLibrary(EMPTY_LIBRARY, ans({ full: true, rows: [r("a", 1), r("b", 2)], cursor: "b:2" }))
    const same = mergeLibrary(lib, ans({ cursor: "b:2" }))
    expect(same).toBe(lib)
    lib = mergeLibrary(lib, ans({ rows: [r("a", 1, { x: "pick" }), r("z", 5)], removed: ["b"], cursor: "b:4" }))
    expect(lib.rows.map((x) => x.id)).toEqual(["z", "a"])
    expect(lib.byId.get("a")?.x).toBe("pick")
    expect(lib.byId.has("b")).toBe(false)
  })
  it("patches one row in place", () => {
    const lib = mergeLibrary(EMPTY_LIBRARY, ans({ full: true, rows: [r("a", 1), r("b", 2)] }))
    const next = patchRow(lib, "a", (x) => ({ ...x, r: 3 }))
    expect(next.rows[1].r).toBe(3)
    expect(next.byId.get("a")?.r).toBe(3)
    expect(lib.byId.get("a")?.r).toBeUndefined()
    expect(patchRow(lib, "nope", (x) => x)).toBe(lib)
  })
})
