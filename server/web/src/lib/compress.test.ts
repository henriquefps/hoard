import { describe, expect, it } from "vitest"
import en from "@/i18n/locales/en.json"
import {
  type CompressInfo,
  failKey,
  type Job,
  jobFraction,
  jobTotals,
  mergeEstimates,
  offeredProfiles,
  pageWarnings,
  sameFolder,
  selectionParts,
  skipKey,
  skipList,
  splitJobs,
  targetGate,
  type TrashEntry,
  trashActions,
  trashSaves,
  unavailableKey,
} from "./compress"
import type { Row } from "./types"

const info = (over: Partial<CompressInfo> = {}): CompressInfo => ({
  available: true,
  reason: null,
  targets: { nas: { ok: true }, phone: { ok: true }, backup: { ok: true } },
  offerAv1: false,
  trashDays: 30,
  profiles: [
    { id: "hevc-cq28", codec: "hevc", q: 28, default: true },
    { id: "hevc-cq30", codec: "hevc", q: 30 },
    { id: "av1-cq34", codec: "av1", q: 34, optIn: true },
  ],
  ...over,
})

const row = (id: string, k: Row["k"], fo?: string): Row => ({ id, t: 0, k, n: `${id}.MP4`, fo })

const job = (over: Partial<Job>): Job => ({
  id: "j",
  target: "nas",
  name: "",
  profile: "hevc-cq28",
  select: {},
  auto: false,
  priority: 2,
  state: "active",
  paused: false,
  createdAt: 0,
  finishedAt: null,
  createdBy: "admin",
  counts: {},
  skipped: {},
  failed: {},
  bytes: { original: 0, output: 0, saved: 0, inTrash: 0 },
  progress: { done: 0, toDo: 0, etaMs: 0 },
  ...over,
})

describe("the gate (Hoard 2.7.0's rules)", () => {
  it("needs the target's ok and says the server's reason otherwise", () => {
    expect(targetGate(info(), "nas")).toEqual({ ok: true })
    expect(targetGate(info({ targets: { nas: { ok: false, reason: "no_write_mount" }, backup: { ok: true } } }), "nas")).toEqual({
      ok: false,
      reason: "no_write_mount",
    })
    expect(targetGate(null, "nas").ok).toBe(false)
  })
  it("reads a server without targets from the feature and nasWrite", () => {
    const old = info({ targets: undefined, nasWrite: false })
    expect(targetGate(old, "nas")).toEqual({ ok: false, reason: "no_write_mount" })
    expect(targetGate(old, "backup")).toEqual({ ok: true })
    expect(targetGate(info({ targets: undefined, available: false, reason: "no_gpu" }), "backup")).toEqual({ ok: false, reason: "no_gpu" })
  })
  it("offers AV1 only when the server does, and never for automatic folders", () => {
    expect(offeredProfiles(info(), false).map((p) => p.id)).toEqual(["hevc-cq28", "hevc-cq30"])
    expect(offeredProfiles(info({ offerAv1: true }), false).map((p) => p.id)).toEqual(["hevc-cq28", "hevc-cq30", "av1-cq34"])
    expect(offeredProfiles(info({ offerAv1: true }), true).map((p) => p.id)).toEqual(["hevc-cq28", "hevc-cq30"])
  })
})

describe("a selection's parts", () => {
  it("splits library videos from backups and leaves photos out", () => {
    const rows = [row("a", "video", "f1"), row("b", "video"), row("c", "photo", "f1"), row("d", "video", "f2")]
    const { parts, leftOut, videos } = selectionParts(rows, info())
    expect(parts).toEqual([
      { target: "nas", select: { itemIds: ["a", "d"] } },
      { target: "backup", select: { itemIds: ["b"] } },
    ])
    expect(leftOut).toBe(0)
    expect(videos).toBe(3)
  })
  it("counts the videos of a target that is not ok as left out", () => {
    const i = info({ targets: { nas: { ok: false, reason: "no_write_mount" }, backup: { ok: true } } })
    const { parts, leftOut } = selectionParts([row("a", "video", "f1"), row("b", "video")], i)
    expect(parts).toEqual([{ target: "backup", select: { itemIds: ["b"] } }])
    expect(leftOut).toBe(1)
  })
})

describe("estimates", () => {
  it("merges the parts' totals and keeps the encoded videos' warnings", () => {
    const a = { totals: { items: 4, encode: 3, skip: { older_compression: 1 }, size: 100, estSize: 20, estMs: 6000 }, pending: 0, warnings: ["hdr10plus"] }
    const b = { totals: { items: 2, encode: 1, skip: { older_compression: 1, not_in_target: 0 }, size: 50, estSize: 5, estMs: 1000 }, pending: 1, warnings: [] }
    expect(mergeEstimates([a, b])).toEqual({
      totals: { items: 6, encode: 4, skip: { older_compression: 2, not_in_target: 0 }, size: 150, estSize: 25, estMs: 7000 },
      pending: 1,
      warnings: ["hdr10plus"],
    })
    expect(
      pageWarnings([
        { itemId: "1", decision: "encode", warnings: ["per_frame_metadata"] },
        { itemId: "2", decision: "skip", reason: "efficient", warnings: ["hdr10plus"] },
      ])
    ).toEqual(["per_frame_metadata"])
  })
  it("lists skips by count, left-out videos apart", () => {
    expect(skipList({ efficient: 2, older_compression: 180, unreadable: 1, not_in_target: 4 })).toEqual([
      { reason: "older_compression", count: 180 },
      { reason: "efficient", count: 2 },
      { reason: "unreadable", count: 1 },
    ])
  })
  it("has a string for every reason", () => {
    const has = (key: string) => key.split(".").reduce<unknown>((o, k) => (o as Record<string, unknown>)?.[k], en) !== undefined
    for (const r of ["older_compression", "sync_artifact", "weird"]) expect(has(skipKey(r) + "_one")).toBe(true)
    for (const r of ["verify_quality", "no_space", "weird"]) expect(has(failKey(r))).toBe(true)
    for (const r of ["no_write_mount", "unstable_inode", null, "weird"]) expect(has(unavailableKey(r))).toBe(true)
  })
})

describe("jobs", () => {
  it("splits running, automatic folders and history", () => {
    const run = job({ id: "1", progress: { done: 1, toDo: 2, etaMs: 0 } })
    const done = job({ id: "2", state: "done" })
    const auto = job({ id: "3", auto: true, select: { folders: [{ folderId: "f", path: "V" }] } })
    const autoBusy = job({ id: "4", auto: true, progress: { done: 0, toDo: 1, etaMs: 0 } })
    const autoOff = job({ id: "5", auto: true, state: "cancelled" })
    const s = splitJobs([run, done, auto, autoBusy, autoOff])
    expect(s.running.map((j) => j.id)).toEqual(["1", "4"])
    expect(s.auto.map((j) => j.id)).toEqual(["3", "4"])
    expect(s.history.map((j) => j.id)).toEqual(["2", "5"])
    expect(sameFolder(auto.select, { folderId: "f", path: "V" })).toBe(true)
    expect(sameFolder(auto.select, { folderId: "f", path: "" })).toBe(false)
    expect(sameFolder({ roots: [{ rootId: "r", path: "" }] }, { rootId: "r", path: "" })).toBe(true)
  })
  it("adds up saved and in-trash bytes and counts the current item's percent", () => {
    const a = job({ bytes: { original: 10, output: 2, saved: 8, inTrash: 0 }, counts: { purged: 1 } })
    const b = job({ bytes: { original: 10, output: 2, saved: 0, inTrash: 10 }, counts: { replaced: 2 } })
    expect(jobTotals([a, b])).toEqual({ saved: 8, inTrash: 10, replaced: 3 })
    expect(jobFraction(job({ progress: { done: 1, toDo: 1, etaMs: 0, current: { id: "1", itemId: "i", state: "encoding", percent: 50 } } }))).toBe(0.75)
    expect(jobFraction(job({}))).toBe(1)
  })
})

describe("the trash", () => {
  const entry = (over: Partial<TrashEntry>): TrashEntry => ({ id: "i", kind: "video", name: "A.MP4", size: 100, places: [], ...over })
  it("restores and empties compressed originals through the server only, never a held entry", () => {
    const c = { id: "7", jobId: "j", state: "replaced", role: "original" as const, size: 100, outSize: 20, source: "nas" }
    expect(trashActions(entry({ compressedTo: "o", compression: c }))).toBe("compressed")
    expect(trashActions(entry({ compressedTo: "o", compression: c, held: true }))).toBe("held")
    expect(trashActions(entry({ held: true, live: true }))).toBe("held")
    expect(trashActions(entry({ compressedTo: "o", compression: { ...c, state: "replacing" } }))).toBe("busy")
    expect(trashActions(entry({}))).toBe("plain")
  })
  it("saves the trashed side's size", () => {
    const c = { id: "7", jobId: "j", state: "replaced", role: "original" as const, size: 100, outSize: 20, source: "nas" }
    expect(trashSaves(entry({ compression: c }))).toBe(100)
    expect(trashSaves(entry({ compression: { ...c, state: "restored", role: "output" } }))).toBe(20)
  })
})
