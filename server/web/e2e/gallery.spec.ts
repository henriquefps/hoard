import { chromium, expect, test, type Cookie, type Page } from "@playwright/test"
import { readFileSync } from "node:fs"
import http from "node:http"
import path from "node:path"
import { PASSWORD, readState, SERVER } from "./server"

// The web gallery against a real hoard-server with the synthetic library of TestWriteWebFixture (37 items: 4 RAW+JPG
// pairs, 27 JPEGs, a lone RAW with a full-size embedded JPEG, 3 videos, 1 motion photo, one XMP sidecar with 5 stars + pick). Tests run in order and share the
// server; each signs in afresh. HOARD_WEB_SCREENSHOTS=1 also writes the documentation screenshots.

const state = readState()
const SHOTS = process.env.HOARD_WEB_SCREENSHOTS ? path.resolve(SERVER, "..", "docs", "testing", "web-gallery") : null

async function shot(page: Page, name: string) {
  if (!SHOTS) return
  await page.waitForTimeout(400) // thumbnails decoded
  await page.screenshot({ path: path.join(SHOTS, `${name}.png`) })
}

// One session for the whole file: sign-in is rate limited to 5 a minute per address (ADR 0013 "Admin").
let session: Cookie[] | null = null

async function signIn(page: Page, theme: "light" | "dark" = "light") {
  await page.addInitScript((t) => localStorage.setItem("hoard-web.theme", t), theme)
  await page.addInitScript(() => localStorage.setItem("hoard-web.language", "en"))
  if (session) {
    await page.context().addCookies(session)
    return
  }
  const res = await page.request.post("/web/session", { data: { password: PASSWORD }, headers: { "X-Hoard-Web": "1" } })
  expect(res.ok()).toBeTruthy()
  session = await page.context().cookies()
}

const grid = (page: Page) => page.getByTestId("grid")
const count = (page: Page) => page.getByTestId("count")

// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function device(method: string, p: string, body?: unknown): Promise<any> {
  const res = await fetch(state.base + p, {
    method,
    headers: { Authorization: `Bearer ${state.deviceKey}`, "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) throw new Error(`${method} ${p}: ${res.status} ${await res.text()}`)
  return res.json()
}

test.describe.configure({ mode: "serial" })

test("sign-in: wrong password, then the gallery", async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("hoard-web.language", "en"))
  await page.goto("/")
  await expect(page.getByRole("heading", { name: "Hoard" })).toBeVisible()
  await shot(page, "sign-in")
  await page.getByLabel("Admin password").fill("not the password")
  await page.getByRole("button", { name: "Sign in" }).click()
  await expect(page.getByRole("alert")).toHaveText("Wrong password.")
  await page.getByLabel("Admin password").fill(PASSWORD)
  await page.getByRole("button", { name: "Sign in" }).click()
  await expect(count(page)).toHaveText("37 items")
  // Newest first: January 2025 on top; the jump bar lists the years.
  await expect(grid(page).getByRole("heading").first()).toContainText("January 15, 2025")
  await expect(page.getByRole("navigation", { name: "Jump to a month" }).getByRole("button", { name: "2023" })).toBeVisible()
  // The sidecar imported from the NAS shows as a pick with 5 stars.
  await expect(grid(page).getByRole("button", { name: /DSC01001\.JPG.*picked, 5 stars/ })).toBeVisible()
  await expect(page.getByTestId("https-hint")).toHaveCount(0) // 127.0.0.1 is this computer: downloads work
})

test("grid screenshots: light and dark, 1440 × 900 and 1024 × 768", async ({ page }) => {
  await signIn(page)
  await page.goto("/")
  await expect(count(page)).toHaveText("37 items")
  await shot(page, "grid-1440-light")
  await page.setViewportSize({ width: 1024, height: 768 })
  await shot(page, "grid-1024-light")
  await page.evaluate(() => {
    document.documentElement.classList.remove("light")
    document.documentElement.classList.add("dark")
  })
  await shot(page, "grid-1024-dark")
  await page.setViewportSize({ width: 1440, height: 900 })
  // The jump bar scrolls to 2023.
  await page.getByRole("navigation", { name: "Jump to a month" }).getByRole("button", { name: "2023" }).click()
  await expect(grid(page).getByRole("heading", { name: /December 24, 2023/ })).toBeVisible()
  await shot(page, "grid-1440-dark-jump")
  // The document never scrolls: header fixed, the card scrolls (hfps shell).
  const docScroll = await page.evaluate(() => document.scrollingElement!.scrollHeight <= window.innerHeight)
  expect(docScroll).toBe(true)
})

test("filters and search", async ({ page }) => {
  await signIn(page)
  await page.goto("/")
  await expect(count(page)).toHaveText("37 items")
  // Kind: videos.
  await page.getByRole("button", { name: "Kind", exact: true }).click()
  await page.getByRole("menuitemcheckbox", { name: "Video" }).click()
  await page.keyboard.press("Escape")
  await expect(count(page)).toHaveText("3 of 37")
  await expect(page).toHaveURL(/kind=video/)
  await page.getByRole("button", { name: "Clear filters", exact: true }).click()
  // Camera facets with counts.
  await page.getByRole("button", { name: "Camera", exact: true }).click()
  await expect(page.getByRole("menuitemcheckbox", { name: /Pixel 8/ })).toContainText("9")
  await shot(page, "filters-camera")
  await page.getByRole("menuitemcheckbox", { name: /Canon EOS R6/ }).click()
  await page.keyboard.press("Escape")
  await expect(count(page)).toHaveText("6 of 37")
  await page.getByRole("button", { name: "Clear filters", exact: true }).click()
  // Picked + rating.
  await page.getByRole("button", { name: "Picked", exact: true }).click()
  await expect(count(page)).toHaveText("1 of 37")
  await page.getByRole("button", { name: "Picked", exact: true }).click()
  // Text search: file names and folders; every word.
  await page.getByRole("searchbox", { name: "Search" }).fill("trip c0001")
  await expect(count(page)).toHaveText("1 of 37")
  await page.getByRole("searchbox", { name: "Search" }).fill("snow")
  await expect(count(page)).toHaveText("7 of 37")
  // Dates (the camera's calendar day).
  await page.getByRole("searchbox", { name: "Search" }).fill("")
  await page.getByRole("button", { name: "Dates", exact: true }).click()
  await page.getByLabel("From", { exact: true }).fill("2024-05-04")
  await page.getByLabel("To", { exact: true }).fill("2024-05-04")
  await page.keyboard.press("Escape")
  await expect(count(page)).toHaveText("6 of 37")
  await shot(page, "filters-dates")
  // Filters survive a reload (they live in the URL).
  await page.reload()
  await expect(count(page)).toHaveText("6 of 37")
  await page.getByRole("button", { name: "Clear filters", exact: true }).click()
  await expect(count(page)).toHaveText("37 items")
})

test("viewer: keys cull with Hoard's semantics, zoom, info, Esc", async ({ page }) => {
  await signIn(page)
  await page.goto("/?q=DSC0100")
  await expect(count(page)).toHaveText("10 of 37")
  await grid(page).getByRole("button", { name: /^DSC01009\.ARW/ }).click()
  const viewer = page.getByTestId("viewer")
  await expect(viewer).toBeVisible()
  await expect(viewer).toHaveAttribute("aria-label", "DSC01009.ARW")
  await expect(viewer.getByText("1 of 10")).toBeVisible()
  // Thumbnail, then the preview (its JPEG is 2400 px: the preview is already full size, nothing more is loaded).
  await expect(viewer.getByTestId("layer")).toHaveText("Preview", { timeout: 15_000 })
  await expect(viewer.getByText(/1\/\d+ s · f\/[\d.]+ · ISO \d+/)).toBeVisible()
  await shot(page, "viewer-1440-info")
  // Z: 100 % (one image pixel per device pixel at deviceScaleFactor 1).
  const img = page.getByTestId("zoom-image")
  const fit = Number(await img.getAttribute("data-scale"))
  await page.keyboard.press("z")
  await expect(img).toHaveAttribute("data-scale", "1.0000")
  expect(fit).toBeLessThan(0.5)
  await shot(page, "viewer-1440-zoom100")
  await page.keyboard.press("Escape") // leaves zoom first
  await expect(viewer).toBeVisible()
  await expect(img).toHaveAttribute("data-scale", fit.toFixed(4))
  // 3 stars, then P picks and advances to the next item (auto-advance).
  await page.keyboard.press("3")
  await expect(viewer.getByRole("button", { name: "3 stars" })).toHaveAttribute("aria-pressed", "true")
  await page.keyboard.press("p")
  await expect(viewer.getByText("2 of 10")).toBeVisible()
  // X rejects; X again un-rejects (a toggle); rating a rejected shot makes it a pick.
  await page.keyboard.press("x")
  await expect(viewer.getByText("3 of 10")).toBeVisible()
  await page.keyboard.press("ArrowLeft")
  await expect(viewer.getByRole("button", { name: "Reject", exact: true })).toHaveAttribute("aria-pressed", "true")
  await page.keyboard.press("4")
  await expect(viewer.getByRole("button", { name: "Pick", exact: true })).toHaveAttribute("aria-pressed", "true")
  await page.keyboard.press("u")
  await expect(viewer.getByRole("button", { name: "Pick", exact: true })).toHaveAttribute("aria-pressed", "false")
  await expect(viewer.getByRole("button", { name: "4 stars" })).toHaveAttribute("aria-pressed", "true") // U keeps stars
  await page.keyboard.press("0")
  await expect(viewer.getByRole("button", { name: "4 stars" })).toHaveAttribute("aria-pressed", "false")
  // Ctrl+Z undoes the last decision.
  await page.keyboard.press("Control+z")
  await expect(viewer.getByRole("button", { name: "4 stars" })).toHaveAttribute("aria-pressed", "true")
  // I hides the info panel; Esc closes the viewer and the grid shows the item.
  await page.keyboard.press("i")
  await expect(viewer.getByRole("complementary")).toHaveCount(0)
  await page.keyboard.press("i")
  await page.setViewportSize({ width: 1024, height: 768 })
  await shot(page, "viewer-1024")
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.keyboard.press("Escape")
  await expect(viewer).toHaveCount(0)
  await expect(grid(page).getByRole("button", { name: /^DSC01009\.ARW.*picked, 3 stars/ })).toBeVisible()
  // The server has the decisions, written by this session's "web:" writer (ADR 0016).
  await expect
    .poll(async () => {
      const it = state.items.find((i) => i.name === "DSC01009.ARW")!
      const rec = await device("GET", `/v1/culling/${it.id}`)
      return `${rec.flag?.value}/${rec.rating?.value}/${String(rec.flag?.deviceId).slice(0, 4)}`
    })
    .toBe("pick/3/web:")
})

test("zoom: 100 % swaps the preview for full resolution in place (a JPEG original, a lone RAW's render)", async ({ page }) => {
  await signIn(page)
  const fullRequests: string[] = []
  page.on("request", (r) => {
    if (/\/web\/items\/[^/]+\/full$/.test(new URL(r.url()).pathname)) fullRequests.push(r.url())
  })
  // Hold the full-resolution answer until the preview at 100 % has been looked at.
  let release!: () => void
  const held = new Promise<void>((r) => (release = r))
  await page.route("**/web/items/*/full", async (route) => {
    await held
    await route.continue()
  })
  // A 6000 × 4000 RAW+JPEG pair: the camera JPEG is the original.
  await page.goto("/?q=DSC01000")
  await grid(page).locator("[data-cell='0']").click()
  const viewer = page.getByTestId("viewer")
  const img = page.getByTestId("zoom-image")
  await expect(viewer.getByTestId("layer")).toHaveText("Preview", { timeout: 15_000 })
  // Fit to the screen: the preview is enough, nothing is asked.
  await page.waitForTimeout(300)
  expect(fullRequests).toHaveLength(0)
  await page.keyboard.press("i") // the info panel off: more of the photo on screen
  await page.keyboard.press("z")
  await expect(img).toHaveAttribute("data-scale", "1.0000")
  await expect.poll(() => fullRequests.length).toBe(1)
  await expect(img.getByRole("status")).toBeVisible() // progress while it comes
  const box = img.getByRole("img", { name: "DSC01000.ARW" })
  const before = await box.boundingBox()
  await shot(page, "zoom100-preview")
  release()
  await expect(img).toHaveAttribute("data-full", "shown", { timeout: 30_000 })
  await expect(viewer.getByTestId("layer")).toHaveText("Original")
  expect(await page.getByTestId("full-image").evaluate((i: HTMLImageElement) => [i.naturalWidth, i.naturalHeight])).toEqual([6000, 4000])
  // Swapped in place: same box, same scale.
  expect(await box.boundingBox()).toEqual(before)
  await expect(img).toHaveAttribute("data-scale", "1.0000")
  await expect(img.getByRole("status")).toHaveCount(0)
  await shot(page, "zoom100-original")
  await page.keyboard.press("Escape")
  await page.keyboard.press("Escape")

  // A lone RAW: the server makes its full-resolution still on demand (here from its full-size embedded JPEG).
  await page.goto("/?q=DSC03000")
  await expect(count(page)).toHaveText("1 of 37")
  await grid(page).locator("[data-cell='0']").click()
  await expect(viewer.getByTestId("layer")).toHaveText("Preview", { timeout: 15_000 })
  await page.keyboard.press("z")
  await expect(viewer.getByTestId("layer")).toHaveText("Original (rendered from the RAW)", { timeout: 60_000 })
  expect(await page.getByTestId("full-image").evaluate((i: HTMLImageElement) => [i.naturalWidth, i.naturalHeight])).toEqual([6000, 4000])
  await shot(page, "zoom100-raw")
  await page.keyboard.press("i")
})

test("video plays from the proxy; a motion photo plays its video", async ({ page }) => {
  await signIn(page)
  await page.goto("/?kind=video")
  await grid(page).getByRole("button", { name: /^C0001\.MP4/ }).click()
  const video = page.getByTestId("video")
  await expect(video).toBeVisible()
  await expect.poll(() => video.evaluate((v: HTMLVideoElement) => v.readyState), { timeout: 15_000 }).toBeGreaterThanOrEqual(2)
  await video.evaluate((v: HTMLVideoElement) => v.pause())
  await shot(page, "viewer-video")
  await page.goto("/?kind=motion")
  await grid(page).locator("[data-cell='0']").click()
  await page.getByRole("button", { name: "Play motion" }).click()
  await expect.poll(() => page.getByTestId("video").evaluate((v: HTMLVideoElement) => v.readyState), { timeout: 15_000 }).toBeGreaterThanOrEqual(2)
})

test("keyboard: the grid is one tab stop with arrow navigation; selection and culling from the grid", async ({ page }) => {
  await signIn(page)
  await page.goto("/?q=PXL_20231224_000")
  await expect(count(page)).toHaveText("8 of 37")
  await grid(page).locator("[data-cell='0']").focus()
  await page.keyboard.press("ArrowRight")
  await expect(grid(page).locator("[data-cell='1']")).toBeFocused()
  await page.keyboard.press("Space")
  await page.keyboard.press("ArrowRight")
  await page.keyboard.press("Shift+Space")
  await expect(page.getByRole("toolbar", { name: "Selection" })).toContainText("2 selected")
  await page.keyboard.press("x")
  await expect(grid(page).getByRole("button", { name: /rejected/ })).toHaveCount(2)
  await shot(page, "grid-selection")
  await page.keyboard.press("Control+z")
  await expect(grid(page).getByRole("button", { name: /rejected/ })).toHaveCount(0)
  await page.keyboard.press("Escape")
  await expect(page.getByRole("toolbar", { name: "Selection" })).toHaveCount(0)
})

test("downloads: one file, a pair as a zip, a selection as a zip", async ({ page }) => {
  await signIn(page)
  await page.goto("/?q=DSC01000")
  await grid(page).locator("[data-cell='0']").click()
  // A pair: both files in a zip.
  const [pairZip] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Download original" }).click()])
  expect(pairZip.suggestedFilename()).toBe("DSC01000.zip")
  const zip = readFileSync((await pairZip.path())!)
  expect(zip.subarray(0, 2).toString()).toBe("PK")
  expect(zip.includes(Buffer.from("DSC01000.ARW")) && zip.includes(Buffer.from("DSC01000.JPG"))).toBe(true)
  // One file from the info panel.
  const link = page.getByRole("link", { name: "Download DSC01000.JPG" })
  // The URL ends in the name too (a browser that names a download by its URL keeps it).
  await expect(link).toHaveAttribute("href", /\/DSC01000\.JPG\?download=1$/)
  await expect(link).toHaveAttribute("download", "DSC01000.JPG")
  const [one] = await Promise.all([page.waitForEvent("download"), link.click()])
  expect(one.suggestedFilename()).toBe("DSC01000.JPG")
  const res = await page.request.get((await link.getAttribute("href"))!)
  expect(res.headers()["content-disposition"]).toBe(`attachment; filename="DSC01000.JPG"; filename*=UTF-8''DSC01000.JPG`)
  await page.keyboard.press("Escape")
  // A selection: three items.
  await page.goto("/?q=snow")
  for (const i of [0, 1, 2]) await grid(page).locator(`[data-cell='${i}']`).click({ modifiers: ["Control"] })
  const [sel] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Download zip", exact: true }).click()])
  expect(sel.suggestedFilename()).toBe("Snow-3 photos.zip")
})

test("live: culling from a device and an edit in Reveal appear without a reload", async ({ page }) => {
  await signIn(page)
  await page.goto("/")
  await expect(count(page)).toHaveText("37 items")
  const target = state.items.find((i) => i.name === "IMG_0303.JPG")!
  await device("POST", "/v1/culling", { changes: [{ itemId: target.id, field: "rating", value: 2, baseRev: 0, modifiedAt: Date.now() }] })
  await expect(grid(page).getByRole("button", { name: /^IMG_0303\.JPG.*2 stars/ })).toBeVisible({ timeout: 10_000 })
  // Reveal edits a photo: a photo record and a version with a history step.
  const photoId = crypto.randomUUID()
  await device("POST", "/v1/changes", {
    changes: [
      { kind: "photo", id: photoId, baseRev: 0, modifiedAt: Date.now(), deleted: false, data: { fingerprint: target.mainFingerprint } },
      {
        kind: "version",
        id: crypto.randomUUID(),
        baseRev: 0,
        modifiedAt: Date.now(),
        deleted: false,
        data: { photoId, position: 0, name: null, recipe: { v: 1 }, history: [{ recipe: { v: 1 }, label: "Exposure" }], cursor: 0 },
      },
    ],
  })
  await expect(grid(page).getByRole("button", { name: /^IMG_0303\.JPG.*Edited in Reveal/ })).toBeVisible({ timeout: 10_000 })
  await page.getByRole("button", { name: "Edited", exact: true }).click()
  await expect(count(page)).toHaveText("1 of 37")
})

test("folders: the NAS tree with counts opens the gallery filtered", async ({ page }) => {
  await signIn(page)
  await page.goto("/folders")
  const folders = page.getByTestId("folders")
  await expect(folders.getByRole("button", { name: "nas" })).toBeVisible()
  await folders.getByRole("button", { name: "Expand 2024" }).click()
  await expect(folders.getByRole("treeitem", { name: /2024-05-03 Trip/ })).toContainText("13")
  await shot(page, "folders-1440-light")
  await page.setViewportSize({ width: 1024, height: 768 })
  await page.evaluate(() => document.documentElement.classList.add("dark"))
  await shot(page, "folders-1024-dark")
  await page.setViewportSize({ width: 1440, height: 900 })
  await folders.getByRole("button", { name: "2024-05-03 Trip" }).click()
  await expect(page).toHaveURL(/folder=/)
  await expect(count(page)).toHaveText("13 of 37")
  await expect(page.getByRole("button", { name: "Show every folder" })).toBeVisible()
})

test("signing out ends the session", async ({ page }) => {
  await signIn(page)
  await page.goto("/")
  await page.getByRole("button", { name: "Settings" }).click()
  await page.getByRole("menuitem", { name: "Sign out" }).click()
  await expect(page.getByLabel("Admin password")).toBeVisible()
  expect((await page.request.get("/web/library")).status()).toBe(401)
})

test("https: the gallery behind tailscale serve (TRUST_TAILSCALE_SERVE=1); plain HTTP from another computer shows the hint", async () => {
  test.setTimeout(90_000)
  // tailscale serve connects from loopback with X-Forwarded-For (the tailnet address), -Proto and -Host. Node's http
  // module sends these headers (and a Host) as tailscale serve does.
  const ts = readState("tailscale")
  const port = Number(new URL(ts.base).port)
  const req = (method: string, p: string, headers: Record<string, string>, body?: unknown) =>
    new Promise<{ status: number; headers: http.IncomingHttpHeaders; body: string }>((resolve, reject) => {
      const r = http.request({ host: "127.0.0.1", port, method, path: p, headers }, (res) => {
        let b = ""
        res.on("data", (c) => (b += c))
        res.on("end", () => resolve({ status: res.statusCode!, headers: res.headers, body: b }))
      })
      r.on("error", reject)
      if (body !== undefined) r.end(JSON.stringify(body))
      else r.end()
    })
  const serve = {
    Host: `127.0.0.1:${port}`,
    "X-Forwarded-For": "100.101.102.103",
    "X-Forwarded-Proto": "https",
    "X-Forwarded-Host": "cachyos-fps.tail1234.ts.net",
    "Tailscale-User-Login": "owner@example.com",
  }
  expect((await req("GET", "/web/session", serve)).status).toBe(200)
  expect((await req("GET", "/", serve)).status).toBe(200)
  // Funnel (public) requests and other proxies' headers: 404 like an unknown route.
  expect((await req("GET", "/web/session", { ...serve, "Tailscale-Funnel-Request": "?1" })).status).toBe(404)
  expect((await req("GET", "/web/session", { ...serve, "Cf-Connecting-Ip": "203.0.113.7" })).status).toBe(404)
  expect((await req("GET", "/web/session", { ...serve, "X-Forwarded-For": "203.0.113.7" })).status).toBe(404)
  // Sign-in over its HTTPS: the Origin is the ts.net name, the cookie is Secure.
  const login = await req(
    "POST",
    "/web/session",
    { ...serve, "X-Hoard-Web": "1", Origin: "https://cachyos-fps.tail1234.ts.net", "Content-Type": "application/json" },
    { password: PASSWORD }
  )
  expect(login.status).toBe(200)
  const cookie = String(login.headers["set-cookie"])
  expect(cookie).toMatch(/hoard_web=.*; Secure/)
  // The main server (no TRUST_TAILSCALE_SERVE) refuses the same request.
  const main = await fetch(state.base + "/web/session", { headers: { "X-Forwarded-For": "100.101.102.103" } })
  expect(main.status).toBe(404)

  // Opened over plain HTTP by a name that is not this computer, the gallery shows the hint (Chrome blocks downloads
  // there). The name resolves to 127.0.0.1 inside this browser only.
  const browser = await chromium.launch({ args: ["--host-resolver-rules=MAP gallery.test 127.0.0.1"] })
  try {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    const page = await ctx.newPage()
    await page.addInitScript(() => localStorage.setItem("hoard-web.language", "en"))
    const origin = `http://gallery.test:${new URL(state.base).port}`
    await page.goto(origin + "/")
    const status = await page.evaluate(
      (password) =>
        fetch("/web/session", {
          method: "POST",
          headers: { "X-Hoard-Web": "1", "Content-Type": "application/json" },
          body: JSON.stringify({ password }),
        }).then((r) => r.status),
      PASSWORD
    )
    expect(status).toBe(200)
    await page.reload()
    const hint = page.getByTestId("https-hint")
    await expect(hint).toContainText("HTTPS address")
    await shot(page, "https-hint")
    await hint.getByRole("button", { name: "Dismiss" }).click()
    await expect(hint).toHaveCount(0)
    await page.reload()
    await expect(page.getByTestId("grid")).toBeVisible()
    await expect(hint).toHaveCount(0)
  } finally {
    await browser.close()
  }
})
