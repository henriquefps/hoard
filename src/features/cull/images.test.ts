import { describe, expect, it, vi } from "vitest"
import type { CullShot } from "@/plugins/smb/definitions"
import {
  loadThumbnail,
  peekThumbnail,
  reloadedSrc,
  reloadThumbnail,
  thumbKey,
  thumbnailFailed,
  thumbnails,
} from "./images"
import type { CullFolder } from "./useCullFolder"

const shot = (id: string): CullShot =>
  ({
    id,
    stem: id.toUpperCase(),
    kind: "pair",
    raw: { name: `${id}.ARW`, path: `s/${id}.ARW`, size: 10, modifiedAt: "2026-01-01T00:00:00Z" },
    rendered: { name: `${id}.JPG`, path: `s/${id}.JPG`, size: 5, modifiedAt: "2026-01-01T00:00:00Z" },
    others: [],
  }) as unknown as CullShot

/** A culling folder whose native thumbnail call always answers `src`. */
function fakeFolder(src: string) {
  const call = vi.fn(async () => ({ src, width: 320, height: 213 }))
  const folder = {
    key: { locationId: "m1", share: "photos", path: "shoot" },
    call,
  } as unknown as CullFolder
  return { folder, call }
}

describe("culling thumbnails (1.10.1)", () => {
  it("a broken cached URL is forgotten and asked again from native, with a fresh URL", async () => {
    const { folder, call } = fakeFolder("http://localhost/_capacitor_file_/cache/t.jpg")
    const s = shot("dsc00001")
    expect(await loadThumbnail(folder, s)).toBe("http://localhost/_capacitor_file_/cache/t.jpg")
    // Cached: a second tile (or a reopened grid) does not ask native.
    expect(await loadThumbnail(folder, s)).toBe("http://localhost/_capacitor_file_/cache/t.jpg")
    expect(call).toHaveBeenCalledTimes(1)
    // The <img> failed on it: reload once.
    const again = await reloadThumbnail(folder, s)
    expect(call).toHaveBeenCalledTimes(2)
    expect(again).toMatch(/^http:\/\/localhost\/_capacitor_file_\/cache\/t\.jpg\?r=\d+$/)
    expect(peekThumbnail(folder, s)).toBe(again)
  })

  it("a second failure is cached: no more native calls, no URL to show", async () => {
    const { folder, call } = fakeFolder("http://localhost/_capacitor_file_/cache/u.jpg")
    const s = shot("dsc00002")
    await loadThumbnail(folder, s)
    await reloadThumbnail(folder, s)
    thumbnailFailed(folder, s)
    expect(thumbnails.hasFailed(thumbKey(folder, s))).toBe(true)
    expect(peekThumbnail(folder, s)).toBeUndefined()
    await expect(loadThumbnail(folder, s)).rejects.toThrow()
    expect(call).toHaveBeenCalledTimes(2)
  })

  it("adds the cache-busting query after an existing one", () => {
    expect(reloadedSrc("a.jpg", 7)).toBe("a.jpg?r=7")
    expect(reloadedSrc("a.jpg?x=1", 7)).toBe("a.jpg?x=1&r=7")
  })
})
