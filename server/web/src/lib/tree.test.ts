import { describe, expect, it } from "vitest"
import { buildTree } from "./tree"

describe("folder tree", () => {
  it("nests directories and adds up counts", () => {
    const root = buildTree([
      { path: "", items: 2 },
      { path: "2024/05/trip", items: 3 },
      { path: "2024/05", items: 1 },
      { path: "2024/10", items: 4 },
      { path: "2023", items: 5 },
    ])
    expect(root.total).toBe(15)
    expect(root.own).toBe(2)
    expect(root.children.map((c) => `${c.name}:${c.total}`)).toEqual(["2023:5", "2024:8"])
    const y2024 = root.children[1]
    expect(y2024.own).toBe(0)
    expect(y2024.children.map((c) => `${c.path}:${c.own}/${c.total}`)).toEqual(["2024/05:1/4", "2024/10:4/4"])
    expect(y2024.children[0].children[0]).toMatchObject({ name: "trip", path: "2024/05/trip", own: 3, total: 3 })
  })
  it("sorts names naturally", () => {
    const root = buildTree([{ path: "10", items: 1 }, { path: "9", items: 1 }, { path: "b", items: 1 }, { path: "A", items: 1 }])
    expect(root.children.map((c) => c.name)).toEqual(["9", "10", "A", "b"])
  })
})
