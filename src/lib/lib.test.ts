import { describe, expect, it, vi } from "vitest"
import type { SmbEntry } from "@/plugins/smb/definitions"
import { etaSeconds, formatBytes, formatDuration, formatRelativeDate, formatSpeed } from "./format"
import {
  fileKind,
  hasThumbnail,
  isValidName,
  joinPath,
  openStrategy,
  parentPath,
  STREAM_FROM_BYTES,
  uncPath,
  viewEntries,
} from "./files"
import { TaskQueue } from "./taskQueue"

describe("format", () => {
  it("formats sizes in 1024 steps with the locale's decimal separator", () => {
    expect(formatBytes(0, "en")).toBe("0 B")
    expect(formatBytes(1023, "en")).toBe("1,023 B")
    expect(formatBytes(1536, "en")).toBe("1.5 KB")
    expect(formatBytes(8.5 * 1024 ** 3, "pt-BR")).toBe("8,5 GB")
    expect(formatBytes(1.4 * 1024 ** 4, "en")).toBe("1.4 TB")
    expect(formatBytes(150 * 1024 ** 2, "en")).toBe("150 MB")
    expect(formatBytes(Number.NaN, "en")).toBe("—")
    expect(formatSpeed(48 * 1024 ** 2, "en")).toBe("48 MB/s")
  })

  it("formats durations compactly", () => {
    expect(formatDuration(0)).toBe("0 s")
    expect(formatDuration(44.2)).toBe("45 s")
    expect(formatDuration(185)).toBe("3 min")
    expect(formatDuration(3840)).toBe("1 h 4 min")
    expect(formatDuration(7200)).toBe("2 h")
    expect(formatDuration(5 * 86400)).toBe("5 d")
    expect(etaSeconds(50, 150, 10)).toBe(10)
    expect(etaSeconds(50, 150, 0)).toBeUndefined()
  })

  it("formats relative dates without time", () => {
    const now = new Date(2026, 10, 8, 15, 0)
    expect(formatRelativeDate(new Date(2026, 10, 8, 1).toISOString(), "en", now)).toBe("today")
    expect(formatRelativeDate(new Date(2026, 10, 7, 23).toISOString(), "en", now)).toBe("yesterday")
    expect(formatRelativeDate(new Date(2026, 10, 5).toISOString(), "pt-BR", now)).toBe("há 3 dias")
    expect(formatRelativeDate(new Date(2026, 9, 24).toISOString(), "en", now)).toBe("Oct 24")
    expect(formatRelativeDate(new Date(2024, 9, 24).toISOString(), "en", now)).toBe("Oct 24, 2024")
    expect(formatRelativeDate(null, "en", now)).toBe("—")
  })
})

const entry = (name: string, extra: Partial<SmbEntry> = {}): SmbEntry => ({
  name,
  path: name,
  isDirectory: false,
  size: 0,
  modifiedAt: null,
  isHidden: name.startsWith("."),
  ...extra,
})

describe("files", () => {
  it("detects kinds and thumbnails from the extension", () => {
    expect(fileKind(entry("IMG_0001.JPG"))).toBe("image")
    expect(fileKind(entry("movie.mkv"))).toBe("video")
    expect(fileKind(entry("a.tar.zst"))).toBe("archive")
    expect(fileKind(entry("README"))).toBe("other")
    expect(fileKind(entry("Photos", { isDirectory: true }))).toBe("folder")
    expect(hasThumbnail(entry("cover.jpg"))).toBe(true)
    expect(hasThumbnail(entry("cover.jpg", { isDirectory: true }))).toBe(false)
    expect(hasThumbnail(entry("Spirited Away.mkv"))).toBe(true)
    expect(hasThumbnail(entry("notes.md"))).toBe(false)
  })

  it("picks how a tap opens a file", () => {
    expect(openStrategy(entry("index.html", { size: 4000 }))).toBe("web")
    expect(openStrategy(entry("Page.HTM", { size: 4000 }))).toBe("web")
    expect(openStrategy(entry("index.html", { size: 4000 }), true)).toBe("cache")
    expect(openStrategy(entry("movie.mkv", { size: 1000 }))).toBe("stream")
    expect(openStrategy(entry("song.flac", { size: 1000 }))).toBe("stream")
    expect(openStrategy(entry("movie.mp4", { size: 1000 }), true)).toBe("stream")
    expect(openStrategy(entry("scan.pdf", { size: 1000 }))).toBe("cache")
    expect(openStrategy(entry("disk.iso", { size: STREAM_FROM_BYTES }))).toBe("stream")
    expect(openStrategy(entry("disk.iso", { size: STREAM_FROM_BYTES - 1 }))).toBe("cache")
  })

  const listing = [
    entry("b.txt", { size: 10, modifiedAt: "2026-01-02T00:00:00Z" }),
    entry("Zeta", { isDirectory: true }),
    entry("file10.jpg", { size: 30, modifiedAt: "2026-01-03T00:00:00Z" }),
    entry("file2.jpg", { size: 20, modifiedAt: "2026-01-01T00:00:00Z" }),
    entry("alpha", { isDirectory: true }),
    entry(".hidden"),
    entry("Évora.mp4", { size: 5 }),
  ]
  const view = (options: Partial<Parameters<typeof viewEntries>[1]>) =>
    viewEntries(
      listing,
      {
        filter: "all",
        query: "",
        showHidden: false,
        sort: { key: "name", direction: "asc" },
        ...options,
      },
      "en"
    ).map((e) => e.name)

  it("sorts folders first, naturally by name, and hides hidden files", () => {
    expect(view({})).toEqual(["alpha", "Zeta", "b.txt", "Évora.mp4", "file2.jpg", "file10.jpg"])
    expect(view({ showHidden: true })).toContain(".hidden")
  })

  it("sorts by size and date in both directions", () => {
    expect(view({ sort: { key: "size", direction: "desc" } }).slice(2)).toEqual([
      "file10.jpg",
      "file2.jpg",
      "b.txt",
      "Évora.mp4",
    ])
    expect(view({ sort: { key: "modified", direction: "asc" } }).slice(2, 5)).toEqual([
      "Évora.mp4",
      "file2.jpg",
      "b.txt",
    ])
    expect(view({ sort: { key: "name", direction: "desc" }, foldersFirst: false })[0]).toBe("Zeta")
  })

  it("filters by kind and searches accent-insensitively", () => {
    expect(view({ filter: "images" })).toEqual(["file2.jpg", "file10.jpg"])
    expect(view({ filter: "folders" })).toEqual(["alpha", "Zeta"])
    expect(view({ query: "evo" })).toEqual(["Évora.mp4"])
    expect(view({ query: "FILE1" })).toEqual(["file10.jpg"])
  })

  it("handles paths and names", () => {
    expect(joinPath("", "a")).toBe("a")
    expect(joinPath("a/b", "c")).toBe("a/b/c")
    expect(parentPath("a/b/c")).toBe("a/b")
    expect(parentPath("a")).toBe("")
    expect(parentPath("")).toBeNull()
    expect(uncPath("192.168.1.155", "media", "Movies/a.mkv")).toBe(
      "\\\\192.168.1.155\\media\\Movies\\a.mkv"
    )
    expect(isValidName("Nova pasta")).toBe(true)
    expect(isValidName("a/b")).toBe(false)
    expect(isValidName("  ")).toBe(false)
    expect(isValidName("..")).toBe(false)
  })
})

describe("TaskQueue", () => {
  const deferred = () => {
    let resolve!: (v: string) => void
    const promise = new Promise<string>((r) => (resolve = r))
    return { promise, resolve }
  }

  it("limits concurrency, caches results and shares in-flight loads", async () => {
    const queue = new TaskQueue<string>(2)
    const jobs = [deferred(), deferred(), deferred()]
    const loaders = jobs.map((job) => vi.fn(() => job.promise))
    const a = queue.get("a", loaders[0])
    const again = queue.get("a", loaders[0])
    void queue.get("b", loaders[1])
    const c = queue.get("c", loaders[2])
    expect(queue.active).toBe(2)
    expect(queue.queued).toBe(1)
    jobs[0].resolve("A")
    expect(await a).toBe("A")
    expect(await again).toBe("A")
    expect(loaders[0]).toHaveBeenCalledTimes(1)
    await Promise.resolve()
    expect(loaders[2]).toHaveBeenCalled()
    jobs[2].resolve("C")
    expect(await c).toBe("C")
    expect(queue.peek("a")).toBe("A")
    expect(await queue.get("a", loaders[0])).toBe("A")
  })

  it("drops a waiting job when its signal aborts, and caches failures", async () => {
    const queue = new TaskQueue<string>(1)
    const first = deferred()
    void queue.get("a", () => first.promise)
    const controller = new AbortController()
    const loader = vi.fn(async () => "B")
    const b = queue.get("b", loader, controller.signal)
    controller.abort()
    await expect(b).rejects.toThrow("Aborted")
    first.resolve("A")
    await Promise.resolve()
    expect(loader).not.toHaveBeenCalled()

    const failing = vi.fn(async () => {
      throw new Error("nope")
    })
    await expect(queue.get("x", failing)).rejects.toThrow("nope")
    await expect(queue.get("x", failing)).rejects.toThrow("nope")
    expect(failing).toHaveBeenCalledTimes(1)
    expect(queue.hasFailed("x")).toBe(true)
  })
})

describe("moveItem (reorder)", () => {
  it("moves up, down and clamps at the ends", async () => {
    const { moveItem } = await import("@/components/SortableList")
    expect(moveItem(["a", "b", "c"], 2, 0)).toEqual(["c", "a", "b"])
    expect(moveItem(["a", "b", "c"], 0, 1)).toEqual(["b", "a", "c"])
    expect(moveItem(["a", "b", "c"], 0, 9)).toEqual(["b", "c", "a"])
  })
})
