import { expect, test } from "@playwright/test"
import { execFileSync } from "node:child_process"
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs"
import path from "node:path"
import { LIB, PASSWORD, readState, SERVER } from "./server"

// Full resolution in the browser (ADR 0018 "Full resolution", opt-in: HOARD_WEB_ZOOM_BENCH=1): the viewer's 100 %
// zoom of a 3:2 photo whose full-resolution answer is a 24, 45 or 60 MP JPEG (synthetic: ffmpeg testsrc2 → vips,
// q90), served in place of /web/items/{id}/full. Measures the time from Z to the swap and the browser's memory (RSS of
// the renderer and GPU processes) at fit, at 100 % with the preview, with the full image, after panning across it and
// after moving to the next photo. Writes docs/testing/web-gallery/zoom-measurements.json.

test.skip(!process.env.HOARD_WEB_ZOOM_BENCH, "HOARD_WEB_ZOOM_BENCH not set")

const SIZES = [
  { mp: 24, w: 6000, h: 4000 },
  { mp: 45, w: 8256, h: 5504 },
  { mp: 60, w: 9504, h: 6336 },
]

function jpeg(w: number, h: number): string {
  const dir = path.join(LIB, "zoom-bench")
  mkdirSync(dir, { recursive: true })
  const out = path.join(dir, `${w}x${h}.jpg`)
  if (existsSync(out)) return out
  const ppm = path.join(dir, "src.ppm")
  execFileSync("ffmpeg", ["-nostdin", "-loglevel", "error", "-y", "-f", "lavfi", "-i", `testsrc2=s=${w}x${h}`, "-frames:v", "1", "-c:v", "ppm", ppm])
  execFileSync("vips", ["copy", ppm, `${out}[Q=90]`], { env: { ...process.env, VIPS_WARNING: "0" } })
  return out
}

test("full resolution: swap time and browser memory at 24, 45 and 60 MP", async ({ browser }) => {
  test.setTimeout(300_000)
  const state = readState()
  const cdp = await browser.newBrowserCDPSession()
  const rss = async () => {
    const { processInfo } = (await cdp.send("SystemInfo.getProcessInfo")) as { processInfo: { type: string; id: number }[] }
    let kb = 0
    for (const p of processInfo) {
      if (p.type !== "renderer" && p.type !== "GPU") continue
      try {
        kb += Number(/VmRSS:\s+(\d+)/.exec(readFileSync(`/proc/${p.id}/status`, "utf8"))?.[1] ?? 0)
      } catch {
        // gone
      }
    }
    return Math.round(kb / 1024)
  }
  const pair = state.items.find((i) => i.name === "DSC01000.ARW")!
  const results: Record<string, number>[] = []
  let cookies: Awaited<ReturnType<typeof browser.newContext>> extends { cookies(): Promise<infer C> } ? C : never = []
  for (const s of SIZES) {
    const body = readFileSync(jpeg(s.w, s.h))
    // A fresh context (a fresh renderer) per size.
    const context = await browser.newContext({ baseURL: state.base, viewport: { width: 1440, height: 900 } })
    const page = await context.newPage()
    await page.addInitScript(() => localStorage.setItem("hoard-web.language", "en"))
    await page.addInitScript(() => localStorage.setItem("hoard-web.info", "0"))
    if (cookies.length) await context.addCookies(cookies)
    else {
      expect((await page.request.post("/web/session", { data: { password: PASSWORD }, headers: { "X-Hoard-Web": "1" } })).ok()).toBe(true)
      cookies = await context.cookies()
    }
    const gc = async () => {
      const c = await context.newCDPSession(page)
      await c.send("HeapProfiler.collectGarbage")
      await c.detach()
    }
    await page.route("**/web/items/*/full", (route) =>
      route.fulfill({ status: 200, body, headers: { "Content-Type": "image/jpeg", "X-Hoard-Full": "original" } })
    )
    await page.goto(`/items/${pair.id}?q=DSC0100`)
    const img = page.getByTestId("zoom-image")
    await expect(page.getByTestId("layer")).toHaveText("Preview", { timeout: 15_000 })
    await page.waitForTimeout(500)
    const fit = await rss()
    const t0 = Date.now()
    await page.keyboard.press("z")
    await expect(img).toHaveAttribute("data-full", "shown", { timeout: 60_000 })
    const swapMs = Date.now() - t0
    await page.waitForTimeout(500)
    const full = await rss()
    // Pan across the whole photo (a drag from corner to corner, in steps).
    const box = (await img.boundingBox())!
    await page.mouse.move(box.x + box.width - 20, box.y + box.height - 20)
    await page.mouse.down()
    const t1 = Date.now()
    for (let i = 1; i <= 30; i++) await page.mouse.move(box.x + box.width - 20 - (i * (box.width - 40)) / 30, box.y + box.height - 20 - (i * (box.height - 40)) / 30)
    await page.mouse.up()
    const panMs = Date.now() - t1
    await page.waitForTimeout(500)
    const panned = await rss()
    // The neighbouring photo (←, inside the viewer): the full image is unmounted and its blob URL revoked.
    await page.keyboard.press("Escape") // leaves zoom
    await page.keyboard.press("ArrowLeft")
    await expect(page.getByTestId("viewer").getByText("9 of 10")).toBeVisible()
    await expect(page.getByTestId("layer")).toHaveText("Preview", { timeout: 15_000 })
    await gc()
    await page.waitForTimeout(3000)
    const after = await rss()
    await context.close()
    results.push({ megapixels: s.mp, width: s.w, height: s.h, jpegMB: +(body.length / 1e6).toFixed(1), swapMs, panMs, rssFitMB: fit, rssFullMB: full, rssPannedMB: panned, rssNextPhotoMB: after })
  }
  console.table(results)

  // A sitting: 60 MP, zoom to 100 % 8 times, going to the neighbour and back between (each visit mounts the photo
  // afresh: a new download, a new blob); memory must level off, not grow per visit.
  const series: number[] = []
  {
    const body = readFileSync(jpeg(9504, 6336))
    const context = await browser.newContext({ baseURL: state.base, viewport: { width: 1440, height: 900 } })
    await context.addCookies(cookies)
    const page = await context.newPage()
    await page.addInitScript(() => localStorage.setItem("hoard-web.language", "en"))
    await page.addInitScript(() => localStorage.setItem("hoard-web.info", "0"))
    await page.route("**/web/items/*/full", (route) =>
      route.fulfill({ status: 200, body, headers: { "Content-Type": "image/jpeg", "X-Hoard-Full": "original" } })
    )
    await page.goto(`/items/${pair.id}?q=DSC0100`)
    const img = page.getByTestId("zoom-image")
    series.push(await rss())
    for (let i = 0; i < Number(process.env.HOARD_WEB_ZOOM_VISITS ?? 8); i++) {
      await expect(page.getByTestId("layer")).not.toHaveText("Thumbnail", { timeout: 15_000 })
      if (!process.env.HOARD_WEB_ZOOM_BASELINE) {
        await page.keyboard.press("z")
        await expect(img).toHaveAttribute("data-full", "shown", { timeout: 60_000 })
        await page.waitForTimeout(300)
        await page.keyboard.press("Escape")
      }
      await page.keyboard.press("ArrowLeft")
      await expect(page.getByTestId("viewer").getByText("9 of 10")).toBeVisible()
      await page.keyboard.press("ArrowRight")
      await expect(page.getByTestId("viewer").getByText("10 of 10")).toBeVisible()
      await page.waitForTimeout(700)
      series.push(await rss())
    }
    await context.close()
  }
  console.log("60 MP series, RSS MB after each photo:", series.join(" "))

  // The alternative, measured the same way: 512 px tiles of the full-resolution level (vips dzsave), only the tiles in
  // view mounted, panned corner to corner at 100 %.
  const tiles: Record<string, number>[] = []
  for (const s of SIZES) {
    const dir = path.join(LIB, "zoom-bench", `tiles-${s.w}`)
    if (!existsSync(dir)) {
      execFileSync("vips", ["dzsave", jpeg(s.w, s.h), dir, "--tile-size", "512", "--overlap", "0", "--depth", "one", "--suffix", ".jpg[Q=90]"], {
        env: { ...process.env, VIPS_WARNING: "0" },
      })
    }
    const level = path.join(`${dir}_files`, "0")
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    const page = await context.newPage()
    let tileBytes = 0
    let tileRequests = 0
    await page.route("http://bench.test/**", (route) => {
      const u = new URL(route.request().url())
      if (u.pathname === "/") {
        return route.fulfill({
          contentType: "text/html",
          body: `<!doctype html><body style="margin:0;overflow:hidden;background:#111"><div id="v" style="position:absolute;inset:0;overflow:hidden"><div id="i" style="position:absolute;width:${s.w}px;height:${s.h}px"></div></div><script>
const T=512,W=${s.w},H=${s.h},v=document.getElementById("v"),im=document.getElementById("i");let x=0,y=0;const on=new Map();
function draw(){im.style.transform="translate("+x+"px,"+y+"px)";const c0=Math.floor(-x/T),r0=Math.floor(-y/T),c1=Math.floor((-x+innerWidth)/T),r1=Math.floor((-y+innerHeight)/T);const want=new Set();
for(let r=Math.max(r0,0);r<=Math.min(r1,Math.ceil(H/T)-1);r++)for(let c=Math.max(c0,0);c<=Math.min(c1,Math.ceil(W/T)-1);c++){const k=c+"_"+r;want.add(k);if(!on.has(k)){const e=new Image();e.src="/t/"+k+".jpg";e.style.cssText="position:absolute;left:"+c*T+"px;top:"+r*T+"px";im.appendChild(e);on.set(k,e)}}
for(const[k,e]of on)if(!want.has(k)){e.removeAttribute("src");e.remove();on.delete(k)}}
window.pan=(nx,ny)=>{x=nx;y=ny;draw()};draw();</script>`,
        })
      }
      const f = path.join(level, path.basename(u.pathname))
      const body = readFileSync(f)
      tileBytes += body.length
      tileRequests++
      return route.fulfill({ contentType: "image/jpeg", body })
    })
    await page.goto("http://bench.test/")
    await page.waitForTimeout(500)
    const first = await rss()
    const t1 = Date.now()
    for (let i = 0; i <= 30; i++) {
      await page.evaluate(([nx, ny]) => (window as unknown as { pan: (x: number, y: number) => void }).pan(nx, ny), [
        (-(s.w - 1440) * i) / 30,
        (-(s.h - 900) * i) / 30,
      ])
      await page.waitForTimeout(16)
    }
    const panMs = Date.now() - t1
    await page.waitForTimeout(500)
    tiles.push({ megapixels: s.mp, tileRequests, tileMB: +(tileBytes / 1e6).toFixed(1), panMs, rssFirstViewMB: first, rssPannedMB: await rss() })
    await context.close()
  }
  console.table(tiles)
  writeFileSync(path.resolve(SERVER, "..", "docs", "testing", "web-gallery", "zoom-measurements.json"), JSON.stringify({ singleImage: results, singleImageSeries60mp: series, tiles }, null, 2) + "\n")
})
