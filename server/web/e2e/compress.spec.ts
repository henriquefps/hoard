import { expect, test, type Cookie, type Page } from "@playwright/test"
import path from "node:path"
import { PASSWORD, readState, SERVER } from "./server"

// Video compression in the web gallery (ADR 0025 phase S3) against a real hoard-server built with the e2e tag and its
// fake encoder (HOARD_E2E_FAKE_COMPRESS=1: libx264 at 1 Mb/s, a passing verifier, a "detected" GPU), the library's
// folder as its own write mount, synthetic clips only (TestWriteWebCompressFixture): Videos/Drone DJI_0001, DJI_0002,
// DJI_HOLD_0003 (its trash link is made, then the fake linker answers EIO: the journal waits, the entry is held),
// OLD_0004 (an HEVC by libx265: "older compression"), Videos/Trips C0010 and C0011, two photos. Tests run in order on one
// server. A walk of a folder is asked at most once a minute (ADR 0023), so the steps that wait for one take a while.
// HOARD_WEB_SCREENSHOTS=1 writes docs/testing/web-compression/.

const state = readState("compress")
const SHOTS = process.env.HOARD_WEB_SCREENSHOTS
  ? path.resolve(SERVER, "..", "docs", "testing", "web-compression")
  : null
const PHONE = { width: 390, height: 844 }
const DESKTOP = { width: 1440, height: 900 }

async function shot(page: Page, name: string) {
  if (!SHOTS) return
  await page.waitForTimeout(400)
  await page.screenshot({ path: path.join(SHOTS, `${name}.png`) })
}

/** Light and dark, desktop and phone width, of what is on screen (re-opened by open() after each change of size). */
async function shots(page: Page, name: string, open: () => Promise<void>) {
  if (!SHOTS) return
  for (const theme of ["light", "dark"] as const) {
    for (const [size, vp] of [
      ["desktop", DESKTOP],
      ["phone", PHONE],
    ] as const) {
      await page.evaluate((t) => localStorage.setItem("hoard-web.theme", t), theme)
      await page.setViewportSize(vp)
      await open()
      await shot(page, `${name}-${size}-${theme}`)
    }
  }
  await page.evaluate(() => localStorage.setItem("hoard-web.theme", "light"))
  await page.setViewportSize(DESKTOP)
}

let session: Cookie[] | null = null

async function signIn(page: Page) {
  await page.addInitScript(() => {
    if (!localStorage.getItem("hoard-web.theme")) localStorage.setItem("hoard-web.theme", "light")
    localStorage.setItem("hoard-web.language", "en")
  })
  if (session) {
    await page.context().addCookies(session)
    return
  }
  const res = await page.request.post(state.base + "/web/session", {
    data: { password: PASSWORD },
    headers: { "X-Hoard-Web": "1" },
  })
  expect(res.ok()).toBeTruthy()
  session = await page.context().cookies()
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function web(page: Page, p: string): Promise<any> {
  const res = await page.request.get(state.base + p)
  expect(res.ok(), `${p}: ${res.status()}`).toBeTruthy()
  return res.json()
}

const go = async (page: Page, p: string) => {
  await page.goto(state.base + p)
}

/** Opens the ⋮ menu of a folder row in Folders (the tree expanded to it). */
async function folderMenu(page: Page, dir: string) {
  await go(page, "/folders")
  const tree = page.getByTestId("folders")
  await expect(tree).toBeVisible()
  const videos = tree.getByRole("treeitem").filter({ hasText: "Videos" }).first()
  if ((await videos.getAttribute("aria-expanded")) === "false")
    await tree.getByRole("button", { name: "Expand Videos" }).click()
  const row = tree.getByRole("treeitem").filter({ hasText: dir }).last()
  await row.getByTestId("folder-menu").first().click()
}

test.describe.configure({ mode: "serial" })

test("the server offers compression: the nav and the Compression view", async ({ page }) => {
  await signIn(page)
  const info = await web(page, "/web/compress")
  expect(info.compress.available).toBe(true)
  expect(info.compress.targets.nas.ok).toBe(true)
  await go(page, "/")
  await page.getByRole("link", { name: "Compression" }).click()
  await expect(page.getByTestId("compression")).toBeVisible()
  await expect(page.getByText("GPU: Fake GPU (web e2e)")).toBeVisible()
  await expect(page.getByTestId("compress-running")).toContainText("Nothing is being compressed.")
})

test("compress a folder: the estimate, skips and the trash note, then the job", async ({
  page,
}) => {
  test.setTimeout(120_000)
  await signIn(page)
  const open = async () => {
    await folderMenu(page, "Drone")
    await page.getByTestId("compress-folder").click()
    await expect(page.getByTestId("compress-summary")).toContainText("3 of 4 videos", {
      timeout: 30_000,
    })
  }
  await open()
  const dialog = page.getByTestId("compress-dialog")
  await expect(dialog).toContainText(
    "Library videos: the server replaces the files in the library."
  )
  await expect(dialog.getByTestId("compress-skips")).toHaveText(
    "1 already compressed by an older tool"
  )
  await expect(dialog).toContainText(
    "Originals go to the trash: restore them there until it is emptied (30 days)."
  )
  await expect(dialog.getByRole("radio", { name: /Same look/ })).toHaveAttribute(
    "aria-checked",
    "true"
  )
  await expect(dialog.getByRole("radio", { name: /AV1/ })).toHaveCount(0) // not offered on this server
  await shots(page, "estimate", open)
  await dialog.getByTestId("compress-start").click()
  await expect(page.getByText("3 videos queued")).toBeVisible()

  // The job: two replaced, the HOLD one waits after its trash link.
  await expect
    .poll(
      async () => {
        const items = (await web(page, "/web/compress/items")).items as {
          state: string
          name: string
        }[]
        return items.map((i) => `${i.name}:${i.state}`).sort()
      },
      { timeout: 60_000 }
    )
    .toEqual([
      "DJI_0001.MP4:replaced",
      "DJI_0002.MP4:replaced",
      "DJI_HOLD_0003.MP4:replacing",
      "OLD_0004.MP4:skipped",
    ])
})

test("the Compression view: progress, history, items with VMAF, the log and the metadata", async ({
  page,
}) => {
  await signIn(page)
  const open = async () => {
    await go(page, "/compression")
    await expect(page.getByTestId("job").first()).toBeVisible()
  }
  await open()
  const job = page.getByTestId("job").first()
  await expect(job).toContainText("Videos/Drone")
  await expect(job).toContainText("1 already compressed by an older tool")
  await expect(page.getByTestId("compress-stalled")).toContainText("1 compression waits")
  await job.getByTestId("job-open").click()
  const items = job.getByTestId("job-item")
  await expect(items).toHaveCount(4)
  const done = items.filter({ hasText: "DJI_0001.MP4" })
  await expect(done).toContainText("Compressed")
  await expect(done).toContainText("VMAF 95.4 (1 % low 92.1, full clip)")
  await expect(done).toContainText("q 28")
  await expect(items.filter({ hasText: "OLD_0004.MP4" })).toContainText(
    "already compressed by an older tool"
  )
  await shots(page, "compression", async () => {
    await open()
    await page.getByTestId("job").first().getByTestId("job-open").click()
    await expect(page.getByTestId("job-item")).toHaveCount(4)
  })

  // The log (the setup page's, now in the gallery too).
  await page
    .getByTestId("job-item")
    .filter({ hasText: "DJI_0001.MP4" })
    .getByTestId("item-log")
    .click()
  await expect(page.getByTestId("log-dialog")).toContainText("estimate:")
  await shot(page, "log-desktop-light")
  await page.keyboard.press("Escape")

  // The metadata sidecar: a download named after the video.
  const href = await page
    .getByTestId("job-item")
    .filter({ hasText: "DJI_0001.MP4" })
    .getByTestId("item-meta")
    .getAttribute("href")
  const res = await page.request.get(state.base + href!)
  expect(res.status()).toBe(200)
  expect(res.headers()["content-disposition"]).toContain("DJI_0001.metadata.json.gz")
  expect((await res.body()).subarray(0, 2)).toEqual(Buffer.from([0x1f, 0x8b]))
})

test("badges: Compressing on the held video, Compressed on the outputs after the walk", async ({
  page,
}) => {
  test.setTimeout(150_000)
  await signIn(page)
  await go(page, "/")
  await expect(page.getByTestId("compressing-badge")).toHaveCount(1, { timeout: 20_000 })
  // The walk (at most once a minute per folder) makes the outputs items with "compressed".
  await expect(page.getByTestId("compressed-badge")).toHaveCount(2, { timeout: 120_000 })
  await shots(page, "gallery-badges", async () => {
    await go(page, "/")
    await expect(page.getByTestId("compressed-badge")).toHaveCount(2)
    // The outputs' thumbnails are made on demand: wait until every one is decoded.
    await page
      .waitForFunction(
        () =>
          [...document.querySelectorAll("[data-cell] img")].every(
            (i) => (i as HTMLImageElement).naturalWidth > 0
          ),
        null,
        {
          timeout: 30_000,
        }
      )
      .catch(() => {})
  })
})

test("the trash: compressed originals with Restore and Delete permanently, the held entry untouched", async ({
  page,
}) => {
  test.setTimeout(200_000)
  await signIn(page)
  const open = async () => {
    await go(page, "/trash")
    await expect(page.getByTestId("trash")).toBeVisible()
    for (const g of await page.getByTestId("trash-group-toggle").all()) await g.click()
  }
  await open()
  const entry = (name: string) => page.locator(`[data-testid="trash-entry"][data-name="${name}"]`)
  await expect(entry("DJI_0001.MP4")).toContainText(
    /Original of a compressed video · saves [\d.]+ MB when emptied/
  )
  await expect(entry("DJI_0002.MP4")).toContainText("Original of a compressed video")
  const held = entry("DJI_HOLD_0003.MP4")
  await expect(held).toContainText("Held by a compression in progress")
  await expect(held.getByRole("checkbox")).toHaveCount(0) // nothing empties a held entry
  await shots(page, "trash", open)

  // Empty counts the compressed originals, never the held one.
  await page.getByTestId("trash-empty").click()
  await expect(page.getByTestId("confirm-dialog")).toContainText(/2 shots \([\d.]+ MB\) will be deleted for good/)
  await page.keyboard.press("Escape")

  // Restore DJI_0001: the original is back at its path; the output goes to the trash ("Compress again").
  await entry("DJI_0001.MP4").getByRole("checkbox").click()
  await page.getByTestId("trash-restore-selection").click()
  await expect(page.getByText("1 shot restored")).toBeVisible()
  await expect
    .poll(
      async () =>
        ((await web(page, "/web/compress/items")).items as { name: string; state: string }[]).find(
          (i) => i.name === "DJI_0001.MP4"
        )?.state,
      {
        timeout: 30_000,
      }
    )
    .toBe("restored")

  // Delete DJI_0002 permanently: the server's compression purges it; it leaves the list.
  await open()
  await entry("DJI_0002.MP4").getByRole("checkbox").click()
  await page.getByTestId("trash-delete-selection").click()
  await expect(page.getByTestId("confirm-dialog")).toContainText(/1 shot \([\d.]+ MB\) will be deleted for good/)
  await shot(page, "trash-purge-confirm-desktop-light")
  await page.getByTestId("confirm-action").click()
  await expect(page.getByText(/1 shot deleted/)).toBeVisible()
  await expect(entry("DJI_0002.MP4")).toHaveCount(0)
  await expect(held).toBeVisible() // still there, still held

  // After the next walk the restored output of DJI_0001 is the trashed side.
  await expect
    .poll(
      async () => {
        await open()
        return entry("DJI_0001.MP4").textContent()
      },
      { timeout: 150_000, intervals: [5_000] }
    )
    .toContain("Compressed video (the original was restored)")
})

test("the whole library's estimate: videos, sizes, skips and the NAS's room", async ({ page }) => {
  test.setTimeout(90_000)
  await signIn(page)
  const open = async () => {
    await go(page, "/compression")
    await page.getByTestId("library-estimate").click()
    await expect(page.getByTestId("library-summary")).toContainText(/of \d+ videos/, { timeout: 30_000 })
  }
  await open()
  await expect(page.getByTestId("compress-library").getByTestId("space-note")).toHaveAttribute("data-state", "fits")
  await shots(page, "library-estimate", open)
  // Not enough room (the answer as a full NAS gives it): "compress in parts or empty the trash first".
  await page.route("**/web/compress/estimate", async (route) => {
    const res = await route.fetch()
    const body = await res.json()
    body.space = { ...body.space, needed: 52e9, free: 39e9, trash: 20e9, fits: false, known: true }
    await route.fulfill({ response: res, json: body })
  })
  await open()
  const note = page.getByTestId("compress-library").getByTestId("space-note")
  await expect(note).toHaveAttribute("data-state", "short")
  await expect(note).toContainText("Needs about 52 GB free on the NAS while the originals stay in the trash; you have 39 GB.")
  await expect(note).toContainText("Empty the trash first (20 GB in it now), or compress in parts.")
  await shots(page, "library-estimate-short", open)
  await page.unroute("**/web/compress/estimate")
})

test("no_space is a wait, not a failure: the item says what it needs and links the trash", async ({ page }) => {
  await signIn(page)
  // An item waiting for room (as nas.go leaves it: ready, no_space), shown from the server's own answer.
  await page.route("**/web/compress/items?*", async (route) => {
    const res = await route.fetch()
    const body = await res.json()
    if (body.items?.length) {
      body.items[0] = { ...body.items[0], state: "ready", reason: "no_space", space: { where: "nas", needed: 412e6, free: 120e6 } }
    }
    await route.fulfill({ response: res, json: body })
  })
  const open = async () => {
    await go(page, "/compression")
    await page.getByTestId("job").first().getByTestId("job-open").click()
    await expect(page.getByTestId("item-no-space")).toBeVisible()
  }
  await open()
  const row = page.getByTestId("item-no-space")
  await expect(row).toContainText("Waiting for free space on the NAS: empty the trash or wait.")
  await expect(row).toContainText("needs 412 MB, 120 MB free")
  await expect(page.getByTestId("job-item").first()).toContainText("Waiting")
  await expect(page.getByTestId("job-item").first()).not.toContainText("not enough space")
  await shots(page, "no-space", open)
  await row.getByRole("link", { name: "Open the trash" }).click()
  await expect(page.getByTestId("trash")).toBeVisible()
  await page.unroute("**/web/compress/items?*")
})

test("compress a gallery selection: library videos only, photos left out", async ({ page }) => {
  await signIn(page)
  await go(page, "/")
  const cell = (name: string) => page.locator(`[data-cell][aria-label^="${name}"]`)
  await cell("C0011.MP4").click({ modifiers: ["ControlOrMeta"] })
  await cell("DSC09000.JPG").click({ modifiers: ["ControlOrMeta"] })
  await page.getByTestId("compress-selection").click()
  const dialog = page.getByTestId("compress-dialog")
  await expect(dialog).toContainText("Compress C0011.MP4")
  await expect(page.getByTestId("compress-summary")).toContainText("1 of 1 video", {
    timeout: 30_000,
  })
  await shot(page, "selection-desktop-light")
  await dialog.getByTestId("compress-start").click()
  await expect(page.getByText("1 video queued")).toBeVisible()
})

test("Compress automatically: turn it on for a folder, see it listed, turn it off", async ({
  page,
}) => {
  await signIn(page)
  const open = async () => {
    await folderMenu(page, "Trips")
    await page.getByTestId("compress-auto").click()
    await expect(page.getByTestId("compress-dialog")).toContainText(
      "New videos in this folder will be compressed"
    )
  }
  await open()
  const dialog = page.getByTestId("compress-dialog")
  await expect(dialog.getByTestId("compress-also")).toBeVisible({ timeout: 30_000 })
  await expect(dialog).toContainText("Also compress the 1 video already there")
  await shots(page, "auto", open)
  await dialog.getByTestId("compress-start").click()
  await expect(page.getByText("Compress automatically is on")).toBeVisible()
  const jobs = (await web(page, "/web/compress/jobs?auto=true")).jobs as {
    state: string
    createdBy: string
  }[]
  expect(jobs.filter((j) => j.state !== "cancelled")).toHaveLength(1)
  expect(jobs[0].createdBy).toBe("admin")

  await go(page, "/compression")
  const auto = page.getByTestId("compress-auto")
  await expect(auto).toContainText("Videos/Trips")
  await auto.getByTestId("auto-off").click()
  await expect(page.getByText("Compress automatically is off")).toBeVisible()
  await expect(auto).toContainText("No folder yet.")
})

test("Portuguese: the Compression view and the trash", async ({ page }) => {
  await signIn(page)
  await page.addInitScript(() => localStorage.setItem("hoard-web.language", "pt-BR"))
  await go(page, "/compression")
  await expect(page.getByRole("heading", { name: "Comprimir vídeos" })).toBeVisible()
  await shot(page, "compression-pt-BR-desktop-light")
  await go(page, "/trash")
  await expect(page.getByRole("heading", { name: "Lixeira" })).toBeVisible()
  for (const g of await page.getByTestId("trash-group-toggle").all()) await g.click()
  await expect(page.getByText("Retido por uma compressão em andamento").first()).toBeVisible()
  await shot(page, "trash-pt-BR-desktop-light")
})
