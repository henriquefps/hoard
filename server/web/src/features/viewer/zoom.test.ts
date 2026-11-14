import { describe, expect, it } from "vitest"
import { displayableOriginal, zoomTarget } from "./zoom"

describe("zoom", () => {
  it("keeps the point under the pointer", () => {
    const v = zoomTarget({ s: 0.25, x: 100, y: 50 }, 1, { x: 300, y: 250 })
    // Image point under (300, 250) at 0.25 was ((300-100)/0.25, (250-50)/0.25) = (800, 800).
    expect(v).toEqual({ s: 1, x: 300 - 800, y: 250 - 800 })
    expect((300 - v.x) / v.s).toBe(800)
  })
  it("picks the full-resolution file the browser can show", () => {
    const files = [
      { fingerprint: "raw", role: "raw", name: "DSC1.ARW", size: 25e6 },
      { fingerprint: "jpg", role: "rendered", name: "DSC1.JPG", size: 8e6 },
      { fingerprint: "xmp", role: "sidecar", name: "DSC1.xmp", size: 2e3 },
    ]
    expect(displayableOriginal("pair", "raw", files)?.fingerprint).toBe("jpg")
    expect(displayableOriginal("photo", "raw", files)).toBeNull()
    expect(displayableOriginal("photo", "jpg", files)?.fingerprint).toBe("jpg")
    expect(displayableOriginal("photo", "h", [{ fingerprint: "h", role: "rendered", name: "IMG.HEIC", size: 3e6 }])).toBeNull()
    expect(displayableOriginal("video", "v", [{ fingerprint: "v", role: "video", name: "a.jpg", size: 1 }])).toBeNull()
    expect(displayableOriginal("photo", "j", [{ fingerprint: "j", role: "rendered", name: "big.jpg", size: 2e8 }])).toBeNull()
  })
})
