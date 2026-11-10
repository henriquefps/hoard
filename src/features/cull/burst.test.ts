import { describe, expect, it } from "vitest"
import {
  burstCover,
  burstStatus,
  comparePartner,
  keepThisOne,
  stepOf,
  stepShot,
  viewerSteps,
} from "./burst"
import { applyChange, type BurstPosition, type CullDecision } from "./model"

const d = (flag: CullDecision["flag"], rating = 0): CullDecision => ({ flag, rating })
const decisions = (entries: Record<string, CullDecision>) => new Map(Object.entries(entries))
const IDS = ["a", "b", "c", "d", "e"]

describe("keep this one", () => {
  it("picks the frame and rejects the rest in one undo step", () => {
    const before = decisions({})
    const change = keepThisOne(before, IDS, "c")!
    expect(change.shotIds).toEqual(IDS)
    const after = applyChange(before, change)
    expect(after.get("c")?.flag).toBe("pick")
    expect(["a", "b", "d", "e"].map((id) => after.get(id)?.flag)).toEqual(Array(4).fill("reject"))
    // Undo restores exactly what was there.
    expect(applyChange(after, change, true)).toEqual(before)
  })

  it("never un-picks explicit keepers (picks, stars) and keeps the frame's own stars", () => {
    const before = decisions({ a: d("pick"), b: d(null, 3), c: d(null, 2), e: d("reject") })
    const after = applyChange(before, keepThisOne(before, IDS, "c")!)
    expect(after.get("a")).toEqual(d("pick"))
    expect(after.get("b")).toEqual(d(null, 3))
    expect(after.get("c")).toEqual(d("pick", 2))
    expect(after.get("d")).toEqual(d("reject"))
    expect(after.get("e")).toEqual(d("reject"))
  })

  it("changes only what differs; nothing when already resolved that way; not for a stranger", () => {
    const resolved = decisions({ a: d("reject"), b: d("pick"), c: d("reject") })
    expect(keepThisOne(resolved, ["a", "b", "c"], "b")).toBeNull()
    const change = keepThisOne(decisions({ a: d("reject") }), ["a", "b", "c"], "b")!
    expect(change.shotIds).toEqual(["b", "c"])
    // A rejected frame can be the one kept.
    expect(applyChange(resolved, keepThisOne(resolved, ["a", "b", "c"], "a")!).get("a")?.flag).toBe(
      "pick"
    )
    expect(keepThisOne(resolved, ["a", "b", "c"], "z")).toBeNull()
  })

  it("works for a burst of 2 and of 40", () => {
    const two = applyChange(new Map(), keepThisOne(new Map(), ["x", "y"], "y")!)
    expect([two.get("x")?.flag, two.get("y")?.flag]).toEqual(["reject", "pick"])
    const forty = Array.from({ length: 40 }, (_, i) => `f${i}`)
    const after = applyChange(new Map(), keepThisOne(new Map(), forty, "f17")!)
    expect(burstStatus(forty, after)).toEqual({ size: 40, kept: 1, decided: 40, resolved: true })
  })
})

describe("burst cover and status", () => {
  it("shows the user's pick, else the crowned best, else the first frame not rejected", () => {
    expect(burstCover(IDS, decisions({}), "c")).toBe("c")
    expect(burstCover(IDS, decisions({ d: d("pick") }), "c")).toBe("d")
    expect(burstCover(IDS, decisions({ b: d("pick"), d: d("pick") }), "c")).toBe("b")
    expect(burstCover(IDS, decisions({ c: d("reject") }), "c")).toBe("a")
    expect(burstCover(IDS, decisions({ a: d("reject"), c: d("reject") }), "c")).toBe("b")
    expect(burstCover(IDS, decisions({}), undefined)).toBe("a")
    expect(burstCover(IDS, decisions({}), "stranger")).toBe("a")
    const allRejected = decisions(Object.fromEntries(IDS.map((id) => [id, d("reject")])))
    expect(burstCover(IDS, allRejected, "c")).toBe("a")
  })

  it("is pending until every frame is decided", () => {
    expect(burstStatus(IDS, decisions({ a: d("pick") }))).toEqual({
      size: 5,
      kept: 1,
      decided: 1,
      resolved: false,
    })
    const done = decisions({
      a: d("pick"),
      b: d("reject"),
      c: d("reject"),
      d: d("pick"),
      e: d("reject"),
    })
    expect(burstStatus(IDS, done)).toMatchObject({ kept: 2, resolved: true })
    // Stars alone do not decide a frame.
    expect(burstStatus(["a"], decisions({ a: d(null, 4) })).resolved).toBe(false)
    expect(burstStatus([], decisions({})).resolved).toBe(false)
  })
})

describe("viewer steps", () => {
  const order = ["s1", "a", "b", "c", "s2", "x", "y"]
  const bursts = new Map<string, BurstPosition>([
    ["a", { burst: "a", index: 0, size: 3 }],
    ["b", { burst: "a", index: 1, size: 3 }],
    ["c", { burst: "a", index: 2, size: 3 }],
    ["x", { burst: "x", index: 0, size: 2 }],
    ["y", { burst: "x", index: 1, size: 2 }],
  ])
  const members = new Map([
    ["a", ["a", "b", "c"]],
    ["x", ["x", "y"]],
  ])

  it("counts a burst as one step in the folder", () => {
    const steps = viewerSteps(order, bursts, members, true)
    expect(steps).toEqual([
      { kind: "shot", shotId: "s1" },
      { kind: "burst", burst: "a", shotIds: ["a", "b", "c"] },
      { kind: "shot", shotId: "s2" },
      { kind: "burst", burst: "x", shotIds: ["x", "y"] },
    ])
    expect(stepOf(steps, "b")).toBe(1)
    expect(stepOf(steps, "s2")).toBe(2)
    expect(stepOf(steps, "nope")).toBe(-1)
  })

  it("shows individual frames when a filter hides part of a burst, or without collapse", () => {
    // Filter "Rejected": only b and y are in the order.
    expect(viewerSteps(["b", "y"], bursts, members, true)).toEqual([
      { kind: "shot", shotId: "b" },
      { kind: "shot", shotId: "y" },
    ])
    expect(viewerSteps(order, bursts, members, false)).toHaveLength(7)
  })

  it("a burst step shows its cover, following the decisions", () => {
    const step = viewerSteps(order, bursts, members, true)[1]
    const best = new Map([["a", "b"]])
    expect(stepShot(step, decisions({}), best)).toBe("b")
    expect(stepShot(step, decisions({ c: d("pick") }), best)).toBe("c")
    expect(stepShot({ kind: "shot", shotId: "s1" }, decisions({}), best)).toBe("s1")
  })
})

describe("quick compare partner", () => {
  it("compares with the suggested best, or the runner-up when on the best, else the next frame", () => {
    expect(comparePartner("c", IDS, ["b", "c", "a"])).toBe("b")
    expect(comparePartner("b", IDS, ["b", "c", "a"])).toBe("c")
    expect(comparePartner("b", IDS, ["b"])).toBe("c")
    expect(comparePartner("e", IDS, [])).toBe("d")
    expect(comparePartner("a", ["a"], [])).toBeUndefined()
  })
})
