import { describe, expect, it } from "vitest"
import { previewMagnified } from "./fullres"
import { zoomTarget } from "./zoom"

describe("zoom", () => {
  it("keeps the point under the pointer", () => {
    const v = zoomTarget({ s: 0.25, x: 100, y: 50 }, 1, { x: 300, y: 250 })
    // Image point under (300, 250) at 0.25 was ((300-100)/0.25, (250-50)/0.25) = (800, 800).
    expect(v).toEqual({ s: 1, x: 300 - 800, y: 250 - 800 })
    expect((300 - v.x) / v.s).toBe(800)
  })
  it("asks for full resolution only when the screen preview is magnified", () => {
    const photo = { width: 6000, height: 4000, previewWidth: 2560 }
    // Fit on a 1440 px wide viewer: not magnified, on a 1× or a 2× screen.
    expect(previewMagnified({ ...photo, scale: 0.2, dpr: 1 })).toBe(false)
    expect(previewMagnified({ ...photo, scale: 0.2, dpr: 2 })).toBe(false)
    // 100 %: one image pixel per device pixel.
    expect(previewMagnified({ ...photo, scale: 1, dpr: 1 })).toBe(true)
    expect(previewMagnified({ ...photo, scale: 0.5, dpr: 2 })).toBe(true)
    // Just past the preview's size (5 % slack), and a Sony RAW whose preview is its 1616 px embedded JPEG.
    expect(previewMagnified({ ...photo, scale: 2560 / 6000, dpr: 1 })).toBe(false)
    expect(previewMagnified({ ...photo, scale: 0.46, dpr: 1 })).toBe(true)
    expect(previewMagnified({ ...photo, previewWidth: 1616, scale: 0.3, dpr: 1 })).toBe(true)
    // A photo the preview already shows at full size, an unknown preview, a giant one.
    expect(previewMagnified({ width: 2400, height: 1600, previewWidth: 2400, scale: 1, dpr: 2 })).toBe(false)
    expect(previewMagnified({ ...photo, previewWidth: 0, scale: 1, dpr: 1 })).toBe(false)
    expect(previewMagnified({ width: 20000, height: 10000, previewWidth: 2560, scale: 1, dpr: 1 })).toBe(false)
  })
})
