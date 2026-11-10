import { describe, expect, it } from "vitest"
import type { CullAnalysis, CullFace, CullMetadata, CullShot } from "@/plugins/smb/definitions"
import {
  bestInBursts,
  colorDistance,
  comparePair,
  DEFAULT_ASSIST,
  flagsOf,
  gridItems,
  groupBursts,
  hammingDistance,
  matchesAssist,
  rankBurst,
  sharpnessScore,
  sortBySharpness,
  suggest,
  burstMembers,
} from "./assist"
import type { CullDecision } from "./model"

const shot = (stem: string): CullShot => ({
  id: stem.toLowerCase(),
  stem,
  kind: "pair",
  raw: { name: `${stem}.ARW`, path: `DCIM/${stem}.ARW`, size: 25e6, modifiedAt: null },
  rendered: { name: `${stem}.JPG`, path: `DCIM/${stem}.JPG`, size: 6e6, modifiedAt: null },
  others: [],
})

const HASH = "a161f1e1e3e36025"
const COLORS = "80".repeat(48)

/** Flip n low bits of the base hash. */
const hashWith = (n: number) => {
  const value = BigInt(`0x${HASH}`) ^ ((1n << BigInt(n)) - 1n)
  return value.toString(16).padStart(16, "0")
}

const face = (eyes: number, prominent = true): CullFace => ({
  x: 0.4,
  y: 0.3,
  width: 0.15,
  height: 0.2,
  leftEyeOpen: eyes,
  rightEyeOpen: eyes,
  prominent,
  sharpness: 100,
  blur: 0.3,
})

function analysis(over: {
  subject?: number
  blur?: number
  faces?: CullFace[]
  mean?: number
  highlights?: number
  shadows?: number
  hash?: string
  colors?: string
}): CullAnalysis {
  return {
    version: 1,
    source: "rawPreview",
    width: 1536,
    height: 1024,
    millis: 50,
    sharpness: {
      global: 50,
      tiles: Array(16).fill(10),
      bestTile: 5,
      subject: over.subject ?? 200,
      subjectKind: over.faces?.length ? "face" : "tile",
      subjectBlur: over.blur ?? 0.35,
    },
    facesDetected: true,
    faces: over.faces ?? [],
    exposure: {
      histogram: Array(64).fill(1 / 64),
      mean: over.mean ?? 0.45,
      highlights: over.highlights ?? 0.01,
      shadows: over.shadows ?? 0.001,
    },
    hash: over.hash ?? HASH,
    colors: over.colors ?? COLORS,
  }
}

const meta = (times: Record<string, number>) =>
  new Map<string, CullMetadata>(
    Object.entries(times).map(([id, t]) => [id, { captureMillis: t, orientation: 1 }])
  )

describe("flags", () => {
  it("blur by sensitivity, eyes on prominent faces only, exposure", () => {
    const soft = analysis({ blur: 0.62 })
    expect(flagsOf(soft, DEFAULT_ASSIST)).toEqual(["blurry"])
    expect(flagsOf(soft, { ...DEFAULT_ASSIST, blur: "low" })).toEqual([])
    expect(flagsOf(analysis({ blur: 0.55 }), { ...DEFAULT_ASSIST, blur: "high" })).toEqual([
      "blurry",
    ])
    expect(flagsOf(analysis({ faces: [face(0.1)] }), DEFAULT_ASSIST)).toEqual(["eyesClosed"])
    expect(flagsOf(analysis({ faces: [face(0.95), face(0.05, false)] }), DEFAULT_ASSIST)).toEqual(
      []
    )
    // One eye unknown (a profile): never "closed".
    expect(
      flagsOf(analysis({ faces: [{ ...face(0.05), rightEyeOpen: undefined }] }), DEFAULT_ASSIST)
    ).toEqual([])
    // Half-closed: only the high sensitivity flags it.
    expect(flagsOf(analysis({ faces: [face(0.4)] }), DEFAULT_ASSIST)).toEqual([])
    expect(flagsOf(analysis({ faces: [face(0.4)] }), { ...DEFAULT_ASSIST, eyes: "high" })).toEqual([
      "eyesClosed",
    ])
    expect(flagsOf(analysis({ highlights: 0.12 }), DEFAULT_ASSIST)).toEqual(["overexposed"])
    expect(flagsOf(analysis({ mean: 0.72 }), DEFAULT_ASSIST)).toEqual(["overexposed"])
    expect(flagsOf(analysis({ shadows: 0.09 }), DEFAULT_ASSIST)).toEqual(["underexposed"])
    expect(flagsOf(analysis({ mean: 0.18 }), DEFAULT_ASSIST)).toEqual(["underexposed"])
    expect(sharpnessScore(analysis({ blur: 0.35 }))).toBe(65)
  })

  it("filters by flag", () => {
    expect(matchesAssist(["blurry"], "blurry")).toBe(true)
    expect(matchesAssist(["blurry"], "eyesClosed")).toBe(false)
    expect(matchesAssist([], "clean")).toBe(true)
    expect(matchesAssist([], "flagged")).toBe(false)
    expect(matchesAssist(undefined, "clean")).toBe(false)
    expect(matchesAssist(undefined, "any")).toBe(true)
  })
})

describe("similarity", () => {
  it("hamming and colour distances", () => {
    expect(hammingDistance(HASH, HASH)).toBe(0)
    expect(hammingDistance(HASH, hashWith(7))).toBe(7)
    expect(hammingDistance("ffffffffffffffff", "0000000000000000")).toBe(64)
    expect(hammingDistance("zz", "00")).toBe(64)
    expect(colorDistance(COLORS, COLORS)).toBe(0)
    expect(colorDistance("00".repeat(48), "ff".repeat(48))).toBe(1)
    expect(colorDistance("00", "0000")).toBe(1)
  })
})

describe("burst grouping", () => {
  const shots = ["A", "B", "C", "D", "E"].map(shot)

  it("joins frames within the gap; no analysis never splits", () => {
    const bursts = groupBursts(
      shots,
      meta({ a: 0, b: 300, c: 600, d: 5000, e: 5400 }),
      new Map(),
      1000
    )
    expect(bursts.get("a")).toEqual({ burst: "a", index: 0, size: 3 })
    expect(bursts.get("c")).toEqual({ burst: "a", index: 2, size: 3 })
    expect(bursts.get("e")).toEqual({ burst: "d", index: 1, size: 2 })
  })

  it("splits a time run when the scene changes", () => {
    const analyses = new Map([
      ["a", analysis({})],
      ["b", analysis({ hash: hashWith(3) })],
      // Another scene 0.4 s later: very different hash and colours.
      ["c", analysis({ hash: "5e9e0e1e1c1c9fda", colors: "20".repeat(48) })],
      ["d", analysis({ hash: "5e9e0e1e1c1c9fdb", colors: "20".repeat(48) })],
    ])
    const bursts = groupBursts(
      shots.slice(0, 4),
      meta({ a: 0, b: 200, c: 600, d: 800 }),
      analyses,
      1000
    )
    expect(bursts.get("a")?.size).toBe(2)
    expect(bursts.get("c")).toEqual({ burst: "c", index: 0, size: 2 })
  })

  it("colours alone can split; a small hash change with the same light does not", () => {
    const analyses = new Map([
      ["a", analysis({})],
      ["b", analysis({ hash: hashWith(22), colors: "80".repeat(47) + "84" })],
      ["c", analysis({ colors: "f0".repeat(48) })],
    ])
    const bursts = groupBursts(shots.slice(0, 3), meta({ a: 0, b: 200, c: 400 }), analyses, 1000)
    // a→b: hamming 22 but colours 0.0003 apart: same scene (camera moved a little).
    expect(bursts.get("b")).toEqual({ burst: "a", index: 1, size: 2 })
    expect(bursts.has("c")).toBe(false)
  })

  it("joins near-identical frames a little over the gap, never far over it", () => {
    const analyses = new Map([
      ["a", analysis({})],
      ["b", analysis({ hash: hashWith(5) })],
      ["c", analysis({ hash: hashWith(6) })],
      ["d", analysis({ hash: hashWith(20) })],
    ])
    const times = meta({ a: 0, b: 3000, c: 10_000, d: 12_500 })
    const bursts = groupBursts(shots.slice(0, 4), times, analyses, 1000)
    expect(bursts.get("b")).toEqual({ burst: "a", index: 1, size: 2 })
    expect(bursts.has("c")).toBe(false) // 7 s after b: more than 4 gaps
    expect(bursts.has("d")).toBe(false) // 2.5 s after c but not near-identical
    // A wider gap setting widens both rules.
    expect(groupBursts(shots.slice(0, 4), times, analyses, 3000).get("d")?.size).toBe(4)
  })

  it("missing or backwards times never join; everything one burst", () => {
    const bursts = groupBursts(shots.slice(0, 3), meta({ a: 1000, b: 500 }), new Map(), 1000)
    expect(bursts.size).toBe(0)
    const all = Array.from({ length: 320 }, (_, i) => shot(`DSC${String(i).padStart(5, "0")}`))
    const times = new Map<string, CullMetadata>(
      all.map((s, i) => [s.id, { captureMillis: i * 100, orientation: 1 }])
    )
    const one = groupBursts(all, times, new Map(), 1000)
    expect(one.get(all[319].id)).toEqual({ burst: all[0].id, index: 319, size: 320 })
  })
})

describe("ranking and suggestions", () => {
  const shots = ["DSC1", "DSC2", "DSC3", "DSC4", "DSC5"].map(shot)
  const bursts = new Map([
    ["dsc1", { burst: "dsc1", index: 0, size: 3 }],
    ["dsc2", { burst: "dsc1", index: 1, size: 3 }],
    ["dsc3", { burst: "dsc1", index: 2, size: 3 }],
  ])
  const analyses = new Map([
    ["dsc1", analysis({ subject: 120, faces: [face(0.9)] })],
    ["dsc2", analysis({ subject: 300, faces: [face(0.05)] })], // sharpest, eyes closed
    ["dsc3", analysis({ subject: 280, faces: [face(0.95)] })],
    ["dsc4", analysis({ blur: 0.7 })], // single, blurry
    ["dsc5", analysis({})], // single, fine
  ])

  it("ranks by relative sharpness minus penalties, ties in capture order", () => {
    const ranked = rankBurst(["dsc1", "dsc2", "dsc3"], analyses, DEFAULT_ASSIST)
    expect(ranked.map((r) => r.shotId)).toEqual(["dsc3", "dsc1", "dsc2"])
    expect(ranked[0].relativeSharpness).toBeCloseTo(280 / 300)
    expect(ranked[2].flags).toEqual(["eyesClosed"])
    const tie = new Map([
      ["x", analysis({ subject: 100 })],
      ["y", analysis({ subject: 100 })],
    ])
    expect(rankBurst(["x", "y"], tie, DEFAULT_ASSIST)[0].shotId).toBe("x")
    expect(bestInBursts(shots, bursts, analyses, DEFAULT_ASSIST).get("dsc1")).toBe("dsc3")
  })

  it("keeps the best of a burst, rejects the rest with reasons and flagged singles", () => {
    const s = suggest(shots, bursts, analyses, new Map(), DEFAULT_ASSIST)
    expect(s.get("dsc3")).toEqual({
      shotId: "dsc3",
      action: "keep",
      reasons: [{ kind: "sharpest" }, { kind: "eyesOpen" }],
      burst: "dsc1",
    })
    expect(s.get("dsc1")).toMatchObject({
      action: "reject",
      reasons: [{ kind: "blurrierThan", than: "DSC3" }],
    })
    expect(s.get("dsc2")).toMatchObject({
      action: "reject",
      reasons: [{ kind: "flag", flag: "eyesClosed" }],
    })
    expect(s.get("dsc4")).toMatchObject({
      action: "reject",
      reasons: [{ kind: "flag", flag: "blurry" }],
    })
    expect(s.has("dsc5")).toBe(false)
  })

  it("never suggests over a decision, waits for a whole burst, keeps none when all are flagged", () => {
    const decisions = new Map<string, CullDecision>([["dsc3", { flag: "reject", rating: 0 }]])
    const s = suggest(shots, bursts, analyses, decisions, DEFAULT_ASSIST)
    expect(s.has("dsc3")).toBe(false)
    // The burst's best is still DSC3 (decided): the others are still compared with it.
    expect(s.get("dsc1")?.action).toBe("reject")
    // A burst with a frame not analysed yet gets nothing.
    const partial = new Map(analyses)
    partial.delete("dsc2")
    const p = suggest(shots, bursts, partial, new Map(), DEFAULT_ASSIST)
    expect(["dsc1", "dsc2", "dsc3"].some((id) => p.has(id))).toBe(false)
    // Every frame blurry: no keep, each rejected, the best one says why.
    const blurry = new Map(
      ["dsc1", "dsc2", "dsc3"].map((id, i) => [id, analysis({ blur: 0.7, subject: 100 + i })])
    )
    const b = suggest(shots.slice(0, 3), bursts, blurry, new Map(), DEFAULT_ASSIST)
    expect([...b.values()].every((x) => x.action === "reject")).toBe(true)
    expect(b.get("dsc3")?.reasons).toContainEqual({ kind: "allFlagged" })
  })

  it("compare opens on the burst's top two, else the next shot", () => {
    const members = burstMembers(shots, bursts)
    const order = shots.map((s) => s.id)
    expect(comparePair("dsc1", order, bursts, members, analyses, DEFAULT_ASSIST)).toEqual([
      "dsc3",
      "dsc1",
    ])
    expect(comparePair("dsc5", order, bursts, members, analyses, DEFAULT_ASSIST)).toEqual([
      "dsc5",
      "dsc4",
    ])
    expect(comparePair("dsc4", ["dsc4"], bursts, members, analyses, DEFAULT_ASSIST)).toBeNull()
  })
})

describe("order and grid stacks", () => {
  const shots = ["A", "B", "C", "D"].map(shot)
  it("sorts sharpest first, unanalysed last in their order", () => {
    const analyses = new Map([
      ["b", analysis({ blur: 0.5 })],
      ["d", analysis({ blur: 0.2 })],
    ])
    expect(sortBySharpness(shots, analyses).map((s) => s.id)).toEqual(["d", "b", "a", "c"])
  })

  it("collapses bursts into one cell on their best frame, expands on demand", () => {
    const bursts = new Map([
      ["b", { burst: "b", index: 0, size: 3 }],
      ["c", { burst: "b", index: 1, size: 3 }],
      ["d", { burst: "b", index: 2, size: 3 }],
    ])
    const best = new Map([["b", "c"]])
    expect(gridItems(shots, bursts, best, new Set(), true)).toEqual([
      { kind: "shot", shotId: "a", index: 0 },
      { kind: "stack", burst: "b", shotId: "c", index: 2, size: 3 },
    ])
    expect(gridItems(shots, bursts, best, new Set(["b"]), true)).toHaveLength(4)
    expect(gridItems(shots, bursts, best, new Set(), false)).toHaveLength(4)
    // A filter that leaves one frame of a burst shows it as a plain cell.
    expect(gridItems([shots[0], shots[1]], bursts, best, new Set(), true)).toEqual([
      { kind: "shot", shotId: "a", index: 0 },
      { kind: "shot", shotId: "b", index: 1 },
    ])
  })
})
