import { describe, expect, it } from "vitest"
import { spaceVerdict, type TrashEntry } from "./compress"
import { batches, emptyFigures, groupTrash, mergePlans, type MoveAnswer, unitsOfEntries } from "./trash"

const entry = (id: string, units: TrashEntry["units"], extra: Partial<TrashEntry> = {}): TrashEntry => ({
  id,
  kind: "photo",
  name: `${id}.JPG`,
  size: 10,
  places: [],
  units,
  ...extra,
})
const nas = (day: string, dir: string, size: number, held = false) => ({
  key: `nas:f1:.hoard-trash/${day}`,
  source: "nas" as const,
  day,
  folderId: "f1",
  folderName: "Imagens",
  dir,
  files: 2,
  size,
  held,
})

describe("trash", () => {
  it("groups by day, newest first, then by place; held units are counted, not acted on", () => {
    const list = [
      entry("a", [nas("2026-11-20", "2024/Trip", 100)]),
      entry("b", [nas("2026-11-26", "2024/Trip", 50)]),
      entry("c", [nas("2026-11-26", "2024/Trip", 70, true)]),
      entry("d", [{ key: "server", source: "server", day: "2026-11-26", dir: "DCIM/Camera", device: "Pixel 8", files: 1, size: 5 }]),
      entry("e", [nas("2026-11-26", "2024/Other", 1), nas("2026-11-20", "2024/Other", 2)]),
    ]
    const g = groupTrash(list)
    expect(g.map((x) => `${x.day} ${x.source} ${x.dir}`)).toEqual([
      "2026-11-26 nas 2024/Other",
      "2026-11-26 nas 2024/Trip",
      "2026-11-26 server DCIM/Camera",
      "2026-11-20 nas 2024/Other",
      "2026-11-20 nas 2024/Trip",
    ])
    const trip = g[1]
    expect(trip.shots.map((e) => e.id)).toEqual(["b", "c"])
    expect(trip.units).toEqual([{ itemId: "b", key: "nas:f1:.hoard-trash/2026-11-26" }])
    expect(trip.held).toBe(1)
    expect(trip.size).toBe(120)
    // Empty takes every unit not held: 4 shots (c is held), their sizes.
    expect(emptyFigures(list)).toEqual({ count: 4, size: 158 })
    expect(unitsOfEntries([list[2], list[4]])).toHaveLength(2)
  })

  it("sums the dry runs of a selection's batches", () => {
    const a: MoveAnswer = { moved: [], skipped: { x: "busy", y: "on_phone" }, failed: {}, counts: {}, warnings: { onlyCopy: ["p"], edits: [] }, nasShots: 3, nasFiles: 7, serverItems: 1, size: 100 }
    const b: MoveAnswer = { moved: [], skipped: { z: "busy" }, failed: {}, counts: {}, warnings: { onlyCopy: [], edits: ["q"] }, nasShots: 0, nasFiles: 0, serverItems: 2, size: 5, nasBlocked: "no_write_mount" }
    const p = mergePlans([a, b])
    expect(p).toMatchObject({ nasShots: 3, nasFiles: 7, serverItems: 3, size: 105, skipped: { busy: 2, on_phone: 1 }, onlyCopy: ["p"], edits: ["q"], nasBlocked: "no_write_mount" })
    expect(batches([...Array(1001).keys()]).map((x) => x.length)).toEqual([500, 500, 1])
  })

  it("says whether the outputs fit while the originals wait in the trash", () => {
    const s = { target: "nas" as const, needed: 50, free: 39, trash: 20, fits: false, known: true, disks: 1 }
    expect(spaceVerdict(s)).toEqual({ state: "short", short: 11, trashHelps: true })
    expect(spaceVerdict({ ...s, trash: 5 })).toEqual({ state: "short", short: 11, trashHelps: false })
    expect(spaceVerdict({ ...s, fits: true, free: 60 }).state).toBe("fits")
    expect(spaceVerdict({ ...s, known: false }).state).toBe("unknown")
    expect(spaceVerdict(undefined).state).toBe("fits")
  })
})
