import { expect, test } from "@playwright/test"
import { existsSync, writeFileSync } from "node:fs"
import path from "node:path"
import { PASSWORD, readState, SERVER, statePath } from "./server"

// Measurements with the large synthetic library (HOARD_WEB_BENCH=1, HOARD_WEB_BENCH_N, default 20,000; ADR 0018
// "Measurements"). Writes docs/testing/web-gallery/measurements.json.

test.skip(!existsSync(statePath("bench")), "HOARD_WEB_BENCH not set")

test("20k items: first paint, index, scrolling, memory", async ({ browser }) => {
  test.setTimeout(240_000)
  const bench = readState("bench")
  const context = await browser.newContext({ baseURL: bench.base, viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  await page.addInitScript(() => localStorage.setItem("hoard-web.language", "en"))
  expect((await page.request.post("/web/session", { data: { password: PASSWORD }, headers: { "X-Hoard-Web": "1" } })).ok()).toBe(true)
  const cdp = await context.newCDPSession(page)
  await cdp.send("Performance.enable")
  const metrics = async () => {
    const { metrics } = await cdp.send("Performance.getMetrics")
    const m = Object.fromEntries(metrics.map((x) => [x.name, x.value]))
    return { jsHeapMB: +(m.JSHeapUsedSize / 1e6).toFixed(1), domNodes: m.Nodes }
  }

  const runs: Record<string, number>[] = []
  for (const cold of [true, false]) {
    // cold: the server's first index request after start (it builds the index); warm: a reload.
    await page.goto("/")
    await page.waitForFunction(() => {
      const img = document.querySelector<HTMLImageElement>('[data-cell="0"] img')
      return !!img && img.complete && img.naturalWidth > 0
    }, undefined, { timeout: 120_000 })
    const r = await page.evaluate(() => {
      const nav = performance.getEntriesByType("navigation")[0] as PerformanceNavigationTiming
      const fcp = performance.getEntriesByName("first-contentful-paint")[0]
      const lib = performance.getEntriesByType("resource").find((e) => e.name.includes("/web/library")) as PerformanceResourceTiming
      const firstThumb = performance
        .getEntriesByType("resource")
        .filter((e) => e.name.includes("/preview?size=thumb"))
        .map((e) => (e as PerformanceResourceTiming).responseEnd)
        .sort((a, b) => a - b)[0]
      return {
        domContentLoadedMs: Math.round(nav.domContentLoadedEventEnd),
        firstContentfulPaintMs: Math.round(fcp?.startTime ?? -1),
        libraryRequestMs: Math.round(lib.duration),
        libraryTransferKB: Math.round(lib.transferSize / 1024),
        libraryDecodedKB: Math.round(lib.decodedBodySize / 1024),
        firstThumbnailMs: Math.round(firstThumb ?? -1),
        gridReadyMs: Math.round(performance.now()),
      }
    })
    runs.push({ cold: cold ? 1 : 0, ...r })
  }
  await expect(page.getByTestId("count")).toHaveText(/20,000 items|\d+ items/)
  const afterLoad = await metrics()

  // Scrolling: 6 s at 3,000 px/s through the grid (about 70 rows of thumbnails a second), frame times from rAF.
  const scroll = await page.evaluate(async () => {
    const el = document.querySelector<HTMLElement>('[data-testid="grid"]')!
    const frames: number[] = []
    let last = performance.now()
    const start = last
    await new Promise<void>((done) => {
      const step = (now: number) => {
        frames.push(now - last)
        last = now
        el.scrollTop += 50
        if (now - start < 6000) requestAnimationFrame(step)
        else done()
      }
      requestAnimationFrame(step)
    })
    frames.shift()
    const sorted = [...frames].sort((a, b) => a - b)
    const p = (q: number) => +sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))].toFixed(1)
    return {
      frames: frames.length,
      fps: +(frames.length / 6).toFixed(1),
      medianFrameMs: p(0.5),
      p95FrameMs: p(0.95),
      p99FrameMs: p(0.99),
      framesOver50ms: frames.filter((f) => f > 50).length,
      scrolledPx: el.scrollTop,
      renderedCells: document.querySelectorAll("[data-cell]").length,
    }
  })
  const afterScroll = await metrics()

  // Jump bar to the oldest year, and a search over every row.
  const jump = await page.evaluate(async () => {
    const buttons = [...document.querySelectorAll<HTMLButtonElement>('nav[aria-label="Jump to a month"] button')]
    const t0 = performance.now()
    buttons[buttons.length - 1].click()
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
    return { jumpMs: Math.round(performance.now() - t0) }
  })
  const t0 = Date.now()
  await page.getByRole("searchbox", { name: "Search" }).fill("IMG_0199")
  await expect(page.getByTestId("count")).toHaveText(/^\d+ of /)
  const searchMs = Date.now() - t0

  const out = {
    date: new Date().toISOString(),
    items: bench.items.length,
    viewport: "1440x900",
    browser: `Chromium ${browser.version()} headless`,
    loads: runs,
    scroll,
    memory: { afterLoad, afterScroll },
    jump,
    searchMs,
  }
  console.log(JSON.stringify(out, null, 2))
  writeFileSync(path.resolve(SERVER, "..", "docs", "testing", "web-gallery", "measurements.json"), JSON.stringify(out, null, 2) + "\n")
  await context.close()
})
