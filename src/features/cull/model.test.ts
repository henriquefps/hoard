import { describe, expect, it } from "vitest"
import { countPhotos, pairShots } from "@/lib/photos"
import type { CullMetadata, CullShot, SmbEntry } from "@/plugins/smb/definitions"
import {
  ALL_SHOTS,
  applyChange,
  changeOf,
  copyTargetPath,
  type CullDecision,
  filterShots,
  nextUnmarked,
  planCopy,
  planDeleteRejects,
  prefetchSlice,
  progressOf,
  pushUndo,
  sampleForScale,
  sortByCapture,
  TILE_SIZE,
  tilesFor,
  UNDO_LIMIT,
  withFlag,
  withRating,
} from "./model"

const entry = (name: string, size = 100, isDirectory = false): SmbEntry => ({
  name,
  path: `DCIM/${name}`,
  size,
  modifiedAt: null,
  isDirectory,
  isHidden: false,
})

describe("pairShots", () => {
  it("pairs RAW and JPEG by case-insensitive stem and keeps singles", () => {
    const shots = pairShots([
      entry("DSC07668.ARW", 25),
      entry("dsc07668.jpg", 6),
      entry("DSC07669.ARW"),
      entry("DSC07670.JPG"),
      entry("notes.txt"),
      entry("DSC07671", 0, true),
    ])
    expect(shots.map((s) => [s.id, s.kind])).toEqual([
      ["dsc07668", "pair"],
      ["dsc07669", "raw"],
      ["dsc07670", "jpeg"],
    ])
    expect(shots[0].stem).toBe("DSC07668")
    expect(shots[0].raw?.name).toBe("DSC07668.ARW")
    expect(shots[0].rendered?.name).toBe("dsc07668.jpg")
  })

  it("prefers JPEG over HEIF and keeps the extras with the shot", () => {
    const [shot] = pairShots([
      entry("IMG_1.HIF"),
      entry("IMG_1.JPG"),
      entry("IMG_1.ARW"),
      entry("IMG_1.DNG"),
    ])
    expect(shot.rendered?.name).toBe("IMG_1.JPG")
    expect(shot.raw?.name).toBe("IMG_1.ARW")
    expect(shot.others.map((f) => f.name).sort()).toEqual(["IMG_1.DNG", "IMG_1.HIF"])
  })

  it("orders naturally and ignores names that only look like extensions", () => {
    const shots = pairShots([entry("IMG_10.jpg"), entry("IMG_9.jpg"), entry(".jpg"), entry("x.")])
    expect(shots.map((s) => s.stem)).toEqual(["IMG_9", "IMG_10"])
  })

  it("counts photos for the browser's Cull action", () => {
    expect(countPhotos([entry("a.ARW"), entry("a.JPG"), entry("b.mp4"), entry("c", 0, true)])).toBe(
      2
    )
  })
})

const shot = (id: string, kind: CullShot["kind"] = "pair"): CullShot => ({
  id,
  stem: id.toUpperCase(),
  kind,
  ...(kind !== "jpeg"
    ? { raw: { name: `${id}.ARW`, path: `f/${id}.ARW`, size: 25, modifiedAt: null } }
    : {}),
  ...(kind !== "raw"
    ? { rendered: { name: `${id}.JPG`, path: `f/${id}.JPG`, size: 6, modifiedAt: null } }
    : {}),
  others: [],
})

const meta = (captureMillis?: number): CullMetadata => ({ orientation: 1, captureMillis })

describe("order and bursts", () => {
  it("sorts by capture time with sub-seconds, name for ties, unknown times last", () => {
    const shots = [shot("dsc3"), shot("dsc1"), shot("dsc2"), shot("dsc10"), shot("dsc4")]
    const metadata = new Map([
      ["dsc1", meta(2_000)],
      ["dsc2", meta(1_500)],
      ["dsc3", meta(1_500)],
      ["dsc10", meta(undefined)],
    ])
    expect(sortByCapture(shots, metadata).map((s) => s.id)).toEqual([
      "dsc2",
      "dsc3",
      "dsc1",
      "dsc4",
      "dsc10",
    ])
  })
})

describe("decisions, filters and progress", () => {
  const shots = ["a", "b", "c", "d", "e"].map((id) => shot(id))

  it("flags and stars, with rating a reject turning it into a pick", () => {
    expect(withFlag({ flag: null, rating: 3 }, "reject")).toEqual({ flag: "reject", rating: 3 })
    expect(withRating({ flag: "reject", rating: 0 }, 4)).toEqual({ flag: "pick", rating: 4 })
    expect(withRating({ flag: null, rating: 2 }, 0)).toEqual({ flag: null, rating: 0 })
    expect(withRating({ flag: null, rating: 0 }, 9)).toEqual({ flag: null, rating: 5 })
  })

  it("applies and undoes changes, dropping unmarked shots", () => {
    let decisions: Map<string, CullDecision> = new Map()
    const pick = changeOf(decisions, ["a", "b"], (d) => withFlag(d, "pick"))!
    decisions = applyChange(decisions, pick)
    expect(decisions.get("a")).toEqual({ flag: "pick", rating: 0 })
    const again = changeOf(decisions, ["a"], (d) => withFlag(d, "pick"))
    expect(again).toBeNull()
    const reject = changeOf(decisions, ["b", "c"], (d) => withFlag(d, "reject"))!
    decisions = applyChange(decisions, reject)
    expect(reject.shotIds).toEqual(["b", "c"])
    decisions = applyChange(decisions, reject, true)
    expect(decisions.get("b")).toEqual({ flag: "pick", rating: 0 })
    expect(decisions.has("c")).toBe(false)
  })

  it("keeps the undo stack bounded", () => {
    let stack = [] as ReturnType<typeof pushUndo>
    for (let i = 0; i < UNDO_LIMIT + 5; i++) {
      stack = pushUndo(stack, { shotIds: [String(i)], before: [], after: [] })
    }
    expect(stack).toHaveLength(UNDO_LIMIT)
    expect(stack[0].shotIds).toEqual(["5"])
  })

  it("filters by flag and stars and counts progress", () => {
    const decisions = new Map<string, CullDecision>([
      ["a", { flag: "pick", rating: 5 }],
      ["b", { flag: "pick", rating: 2 }],
      ["c", { flag: "reject", rating: 0 }],
      ["d", { flag: null, rating: 3 }],
    ])
    const ids = (flag: "all" | "picked" | "rejected" | "unmarked", minStars = 0) =>
      filterShots(shots, decisions, { flag, minStars }).map((s) => s.id)
    expect(ids("all")).toEqual(["a", "b", "c", "d", "e"])
    expect(ids("picked")).toEqual(["a", "b"])
    expect(ids("rejected")).toEqual(["c"])
    expect(ids("unmarked")).toEqual(["d", "e"])
    expect(ids("all", 3)).toEqual(["a", "d"])
    expect(ids("picked", 3)).toEqual(["a"])
    expect(filterShots(shots, decisions, ALL_SHOTS)).toHaveLength(5)
    expect(progressOf(shots, decisions)).toEqual({
      total: 5,
      decided: 3,
      picked: 2,
      rejected: 1,
      unmarked: 2,
      rated: 3,
    })
    expect(nextUnmarked(shots, decisions, 0)).toBe(3)
    expect(nextUnmarked(shots, decisions, 4)).toBe(3)
    const all = new Map(shots.map((s) => [s.id, { flag: "pick" as const, rating: 0 }]))
    expect(nextUnmarked(shots, all, 0)).toBe(-1)
  })
})

describe("copy picks and delete rejects", () => {
  const shots = [shot("p1"), shot("r1", "raw"), shot("j1", "jpeg"), shot("x1"), shot("u1")]
  shots[0].others.push({ name: "p1.HIF", path: "f/p1.HIF", size: 3, modifiedAt: null })
  const decisions = new Map<string, CullDecision>([
    ["p1", { flag: "pick", rating: 0 }],
    ["r1", { flag: "pick", rating: 3 }],
    ["j1", { flag: "pick", rating: 0 }],
    ["x1", { flag: "reject", rating: 0 }],
  ])

  it("copies every file of a pick with RAW + JPEG", () => {
    const plan = planCopy(shots, decisions, "both")
    expect(plan.items.map((i) => i.file.name)).toEqual([
      "p1.ARW",
      "p1.JPG",
      "p1.HIF",
      "r1.ARW",
      "j1.JPG",
    ])
    expect(plan.shots).toBe(3)
    expect(plan.fallbacks).toBe(0)
    expect(plan.bytes).toBe(25 + 6 + 3 + 25 + 6)
  })

  it("copies one kind, falling back to the only file a shot has", () => {
    const raw = planCopy(shots, decisions, "raw")
    expect(raw.items.map((i) => [i.file.name, i.fallback])).toEqual([
      ["p1.ARW", false],
      ["r1.ARW", false],
      ["j1.JPG", true],
    ])
    expect(raw.fallbacks).toBe(1)
    const jpeg = planCopy(shots, decisions, "jpeg")
    expect(jpeg.items.map((i) => i.file.name)).toEqual(["p1.JPG", "r1.ARW", "j1.JPG"])
    expect(jpeg.fallbacks).toBe(1)
  })

  it("names the destination folder", () => {
    expect(copyTargetPath("Pictures", "DCIM/100MSDCF", { files: "both", folder: "keep" })).toBe(
      "Pictures/100MSDCF"
    )
    expect(copyTargetPath("", "DCIM", { files: "both", folder: "keep" })).toBe("DCIM")
    expect(copyTargetPath("Pictures", "", { files: "both", folder: "keep" })).toBe("Pictures")
    expect(
      copyTargetPath("Fotos", "DCIM/100MSDCF", {
        files: "raw",
        folder: "new",
        subfolder: " Celine ",
      })
    ).toBe("Fotos/Celine")
    expect(copyTargetPath("Fotos", "DCIM", { files: "raw", folder: "none" })).toBe("Fotos")
  })

  it("deletes every file of each rejected shot", () => {
    const plan = planDeleteRejects(shots, decisions)
    expect(plan.shots).toEqual(["x1"])
    expect(plan.files.map((f) => f.name)).toEqual(["x1.ARW", "x1.JPG"])
    expect(plan.bytes).toBe(31)
  })
})

describe("prefetch window and tiles", () => {
  it("slices the order around the current shot", () => {
    const order = ["a", "b", "c", "d", "e", "f"]
    expect(prefetchSlice(order, 2, 3, 1)).toEqual({
      shotIds: ["b", "c", "d", "e", "f"],
      current: 1,
    })
    expect(prefetchSlice(order, 0, 3, 1)).toEqual({ shotIds: ["a", "b", "c", "d"], current: 0 })
    expect(prefetchSlice(order, 5, 3, 2)).toEqual({ shotIds: ["d", "e", "f"], current: 2 })
    expect(prefetchSlice([], 0, 3, 1)).toEqual({ shotIds: [], current: 0 })
    expect(prefetchSlice(order, 9, 3, 1)).toEqual({ shotIds: [], current: 0 })
  })

  it("picks the sample size for a zoom level", () => {
    expect(sampleForScale(1)).toBe(1)
    expect(sampleForScale(2)).toBe(1)
    expect(sampleForScale(0.6)).toBe(1)
    expect(sampleForScale(0.5)).toBe(2)
    expect(sampleForScale(0.3)).toBe(2)
    expect(sampleForScale(0.24)).toBe(4)
    expect(sampleForScale(0.001)).toBe(16)
    expect(sampleForScale(0)).toBe(16)
  })

  it("covers the visible area with grid-aligned tiles, center first", () => {
    const tiles = tilesFor({ x: 600, y: 300, width: 1000, height: 400 }, 6000, 4000, 1)
    expect(tiles.map((t) => t.key).sort()).toEqual(
      ["1:512:0", "1:1024:0", "1:1536:0", "1:512:512", "1:1024:512", "1:1536:512"].sort()
    )
    expect(tiles[0].key).toBe("1:1024:0")
    const edge = tilesFor({ x: 5900, y: 3900, width: 500, height: 500 }, 6000, 4000, 1)
    expect(edge).toEqual([
      {
        key: `1:${TILE_SIZE * 11}:${TILE_SIZE * 7}`,
        x: 5632,
        y: 3584,
        width: 368,
        height: 416,
        sample: 1,
      },
    ])
    expect(tilesFor({ x: 0, y: 0, width: 6000, height: 4000 }, 6000, 4000, 4)).toHaveLength(6)
  })
})
