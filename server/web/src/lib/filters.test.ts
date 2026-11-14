import { describe, expect, it } from "vitest"
import {
  activeCount,
  applyFilters,
  cameraFacets,
  filtersFromSearch,
  filtersToSearch,
  NO_FILTERS,
  UPLOADS,
  type Filters,
} from "./filters"
import type { Row } from "./types"

const day = (d: string, h = 12) => Date.parse(`${d}T${String(h).padStart(2, "0")}:00:00Z`)
const rows: Row[] = [
  { id: "1", t: day("2024-05-03"), k: "pair", n: "DSC00001.ARW", fo: "f1", d: "2024/05/trip", cam: "SONY ILCE-7M4", raw: true, r: 4, x: "pick" },
  { id: "2", t: day("2024-05-02"), k: "photo", n: "DSC00002.JPG", fo: "f1", d: "2024/05", cam: "SONY ILCE-7M4", x: "reject", r: 2 },
  { id: "3", t: day("2024-05-01", 23), l: day("2024-05-02", 2), k: "video", n: "VID_1.MP4", fo: "f2", d: "", cam: "Pixel 8", dur: 12000 },
  { id: "4", t: day("2023-12-31"), k: "motion", n: "PXL_1.MP.jpg", cam: "Pixel 8", e: true },
  { id: "5", t: day("2023-01-01"), k: "photo", n: "scan.jpg", fo: "f2", d: "old/Scans" },
]
const folders = { f1: "photos", f2: "phone" }
const ids = (f: Partial<Filters>) => applyFilters(rows, { ...NO_FILTERS, ...f }, folders).map((r) => r.id)

describe("filters", () => {
  it("no filter returns the rows as they are", () => {
    expect(applyFilters(rows, NO_FILTERS, folders)).toBe(rows)
    expect(activeCount(NO_FILTERS)).toBe(0)
  })
  it("kind, camera, rating, flag, edited", () => {
    expect(ids({ kinds: ["video", "motion"] })).toEqual(["3", "4"])
    expect(ids({ cameras: ["Pixel 8"] })).toEqual(["3", "4"])
    expect(ids({ cameras: [""] })).toEqual(["5"])
    expect(ids({ minRating: 3 })).toEqual(["1"])
    expect(ids({ minRating: 2 })).toEqual(["1", "2"])
    expect(ids({ flags: ["pick"] })).toEqual(["1"])
    expect(ids({ flags: ["none"] })).toEqual(["3", "4", "5"])
    expect(ids({ flags: ["pick", "reject"] })).toEqual(["1", "2"])
    expect(ids({ edited: true })).toEqual(["4"])
  })
  it("folder and directory (sub-directories included), and the uploads", () => {
    expect(ids({ folder: { id: "f1", dir: "" } })).toEqual(["1", "2"])
    expect(ids({ folder: { id: "f1", dir: "2024/05" } })).toEqual(["1", "2"])
    expect(ids({ folder: { id: "f1", dir: "2024/05/trip" } })).toEqual(["1"])
    expect(ids({ folder: { id: "f1", dir: "2024/0" } })).toEqual([])
    expect(ids({ folder: { id: UPLOADS, dir: "" } })).toEqual(["4"])
  })
  it("date range by the camera's wall clock, both ends inclusive", () => {
    // Item 3's instant is May 1 23:00 UTC but its wall clock says May 2.
    expect(ids({ from: "2024-05-02", to: "2024-05-02" })).toEqual(["2", "3"])
    expect(ids({ from: "2024-01-01" })).toEqual(["1", "2", "3"])
    expect(ids({ to: "2023-12-31" })).toEqual(["4", "5"])
    expect(ids({ from: "nonsense" })).toEqual(["1", "2", "3", "4", "5"])
  })
  it("text search over the file name, folder name and directory; every word must match", () => {
    expect(ids({ text: "dsc" })).toEqual(["1", "2"])
    expect(ids({ text: "DSC trip" })).toEqual(["1"])
    expect(ids({ text: "phone" })).toEqual(["3", "5"])
    expect(ids({ text: "scans" })).toEqual(["5"])
    expect(ids({ text: "  " })).toEqual(["1", "2", "3", "4", "5"])
    expect(ids({ text: "zzz" })).toEqual([])
  })
  it("combines filters", () => {
    expect(ids({ cameras: ["SONY ILCE-7M4"], flags: ["reject"], text: "dsc" })).toEqual(["2"])
  })
  it("camera facets: most used first, unknown as an empty name", () => {
    expect(cameraFacets(rows)).toEqual([
      { camera: "Pixel 8", count: 2 },
      { camera: "SONY ILCE-7M4", count: 2 },
      { camera: "", count: 1 },
    ])
  })
  it("round-trips through the query string and ignores bad values", () => {
    const f: Filters = {
      kinds: ["pair", "video"],
      cameras: ["SONY ILCE-7M4", ""],
      minRating: 3,
      flags: ["none"],
      folder: { id: "f1", dir: "2024/05:x" },
      from: "2024-01-01",
      to: "2024-12-31",
      text: "dsc trip",
      edited: true,
    }
    expect(filtersFromSearch(filtersToSearch(f))).toEqual(f)
    expect(filtersToSearch(NO_FILTERS).toString()).toBe("")
    expect(filtersFromSearch(new URLSearchParams("kind=photo,bogus&stars=9&flag=x&from=2024-13-99&camera=%5B1%5D"))).toEqual({
      ...NO_FILTERS,
      kinds: ["photo"],
    })
    expect(filtersFromSearch(new URLSearchParams("camera=notjson"))).toEqual(NO_FILTERS)
  })
})
