import { describe, expect, it } from "vitest"
import { nextCollapse, visibleHeader } from "./collapse"
import { type Crumb, fitCrumbs, headerCrumbs } from "./breadcrumbs"

describe("nextCollapse (quick-return header)", () => {
  const H = 120
  it("hides the header as the content scrolls down, up to its height", () => {
    expect(nextCollapse(0, 0, 40, H)).toBe(40)
    expect(nextCollapse(40, 40, 100, H)).toBe(100)
    expect(nextCollapse(100, 100, 400, H)).toBe(H)
  })
  it("brings it back 1:1 when scrolling up, anywhere in the list", () => {
    expect(nextCollapse(H, 900, 870, H)).toBe(90)
    expect(nextCollapse(90, 870, 700, H)).toBe(0)
    expect(nextCollapse(0, 700, 650, H)).toBe(0)
  })
  it("hides it again from where it is after a return", () => {
    expect(nextCollapse(0, 650, 700, H)).toBe(50)
  })
  it("is fully expanded at the top, and never hidden by more than the scroll", () => {
    expect(nextCollapse(H, 500, 0, H)).toBe(0)
    expect(nextCollapse(H, 30, 20, H)).toBe(20)
  })
  it("ignores overscroll (negative scrollTop) and a jump (scroll restoration)", () => {
    expect(nextCollapse(0, 0, -30, H)).toBe(0)
    expect(nextCollapse(0, 0, 2400, H)).toBe(H)
  })
  it("visibleHeader is the header's bottom edge on screen", () => {
    expect(visibleHeader(H, 0, 0)).toBe(H)
    expect(visibleHeader(H, 500, H)).toBe(0)
    expect(visibleHeader(H, 500, 30)).toBe(90)
    expect(visibleHeader(H, 20, H)).toBe(100)
  })
})

describe("headerCrumbs", () => {
  const trail = (...labels: (string | undefined)[]): Crumb[] => labels.map((label) => ({ label }))
  it("drops the current page (the title shows it)", () => {
    expect(headerCrumbs(trail("Machines", "cachy", "raiz", "Images"), "Images")).toEqual(
      trail("Machines", "cachy", "raiz")
    )
  })
  it("drops the folder the title names under a sub-page (Cull)", () => {
    expect(headerCrumbs(trail("Machines", "cachy", "Park", "Cull"), "Park")).toEqual(
      trail("Machines", "cachy")
    )
  })
  it("keeps a parent that only shares the name with the current folder", () => {
    expect(headerCrumbs(trail("Home", "Photos", "Photos"), "Photos")).toEqual(
      trail("Home", "Photos")
    )
  })
  it("top-level and one-level pages have nothing or one crumb", () => {
    expect(headerCrumbs(trail("Settings"), "Settings")).toEqual([])
    expect(headerCrumbs(trail("Machines", "New machine"), "New machine")).toEqual(trail("Machines"))
  })
})

describe("fitCrumbs", () => {
  it("shows everything that fits", () => {
    expect(fitCrumbs([60, 80, 40], 500, 20, 30)).toBe(3)
  })
  it("keeps the tail and leaves room for the … menu", () => {
    // 40 + 20 + 80 = 140; + … (30 + 20) = 190 ≤ 200; adding 60 + 20 would overflow.
    expect(fitCrumbs([60, 80, 40], 200, 20, 30)).toBe(2)
    expect(fitCrumbs([60, 80, 40], 100, 20, 30)).toBe(1)
  })
  it("always shows at least the last crumb", () => {
    expect(fitCrumbs([300], 100, 20, 30)).toBe(1)
    expect(fitCrumbs([60, 300], 100, 20, 30)).toBe(1)
    expect(fitCrumbs([], 100, 20, 30)).toBe(0)
  })
  it("uses exactly the available width", () => {
    expect(fitCrumbs([50, 50], 120, 20, 30)).toBe(2)
    expect(fitCrumbs([50, 50], 119, 20, 30)).toBe(1)
  })
})
