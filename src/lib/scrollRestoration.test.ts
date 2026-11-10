import { describe, expect, it } from "vitest"
import { clampScroll, isReachable, scrollKey, scrollSelector } from "./scrollRestoration"

const location = (href: string, key?: string) =>
  ({ href, state: key ? { __TSR_key: key } : {} }) as Parameters<typeof scrollKey>[0]

describe("scrollKey", () => {
  it("is the history entry, so the same folder opened twice is two positions", () => {
    expect(scrollKey(location("/browse/device?path=DCIM", "a1"))).toBe("a1")
    expect(scrollKey(location("/browse/device?path=DCIM", "b2"))).toBe("b2")
  })
  it("falls back to the href before the entry has a key", () => {
    expect(scrollKey(location("/settings"))).toBe("/settings")
  })
})

describe("clampScroll", () => {
  it("keeps an offset the content can show", () => {
    expect(clampScroll(500, 2000, 600)).toBe(500)
  })
  it("clamps to the bottom when the list got shorter", () => {
    expect(clampScroll(5000, 2000, 600)).toBe(1400)
  })
  it("never goes negative, and a list shorter than the screen stays at the top", () => {
    expect(clampScroll(-10, 2000, 600)).toBe(0)
    expect(clampScroll(300, 400, 600)).toBe(0)
  })
})

describe("isReachable", () => {
  it("waits while the content is still shorter than the saved offset", () => {
    expect(isReachable(1200, 800, 600)).toBe(false)
    expect(isReachable(1200, 1800, 600)).toBe(true)
    expect(isReachable(0, 100, 600)).toBe(true)
  })
})

describe("scrollSelector", () => {
  it("matches the attribute the containers carry", () => {
    expect(scrollSelector("shell")).toBe('[data-scroll-restoration-id="shell"]')
  })
})
