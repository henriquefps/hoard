import { expect, test, type Cookie, type Page } from "@playwright/test"
import { existsSync, rmSync, writeFileSync } from "node:fs"
import path from "node:path"
import { LIB, PASSWORD, readState, SERVER } from "./server"

// The trash from the web gallery (ADR 0023 Amendment (0.14.0)) against a real hoard-server whose library folder is its
// own write mount, with a backup root and a phone's backup of three photos freed on the phone (server-only items, the
// only copies). Synthetic files only (TestWriteWebTrashFixture): Trips/2025-06 DSC07000 (a RAW+JPEG pair with
// DSC07000.xmp, DSC07000.ARW.xmp and the hidden .DSC07000.xmp) and DSC07001…06, Home IMG_0100 and IMG_0101.
// Tests run in order on one server. HOARD_WEB_SCREENSHOTS=1 writes docs/testing/web-trash/.

const state = readState("trash")
const NAS = path.join(LIB, "trash", "mnt", "nas")
const SHOTS = process.env.HOARD_WEB_SCREENSHOTS ? path.resolve(SERVER, "..", "docs", "testing", "web-trash") : null
const PHONE = { width: 390, height: 844 }
const DESKTOP = { width: 1440, height: 900 }
const today = new Date().toLocaleDateString("en-CA") // yyyy-MM-dd, the server's local date (same machine)

async function shot(page: Page, name: string) {
  if (!SHOTS) return
  await page.waitForTimeout(400)
  await page.screenshot({ path: path.join(SHOTS, `${name}.png`) })
}

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
  const res = await page.request.post(state.base + "/web/session", { data: { password: PASSWORD }, headers: { "X-Hoard-Web": "1" } })
  expect(res.ok()).toBeTruthy()
  session = await page.context().cookies()
}

const go = (page: Page, p: string) => page.goto(state.base + p)
const cell = (page: Page, name: string) => page.locator(`[data-cell][aria-label^="${name}"]`)
const nas = (rel: string) => existsSync(path.join(NAS, rel))

test.describe.configure({ mode: "serial" })

test("reject shots, Select all of the Rejected filter, Move to trash: the NAS layout Hoard uses", async ({ page }) => {
  await signIn(page)
  await go(page, "/?q=DSC070")
  await expect(cell(page, "DSC07001.JPG")).toBeVisible({ timeout: 20_000 })
  for (const name of ["DSC07000.ARW", "DSC07001.JPG", "DSC07002.JPG"]) await cell(page, name).click({ modifiers: ["ControlOrMeta"] })
  await page.getByRole("toolbar").getByRole("button", { name: "Reject" }).click()
  await page.getByRole("button", { name: "Clear selection" }).click()

  const open = async () => {
    await go(page, "/?flag=reject")
    await expect(page.getByTestId("count")).toContainText("3 of")
    await page.getByTestId("filter-select-all").click()
    await page.getByTestId("trash-selection").click()
    await expect(page.getByTestId("trash-move-summary")).toHaveText(/3 shots · [\d.]+ (KB|kB|MB)/, { timeout: 20_000 })
  }
  await open()
  const dialog = page.getByTestId("trash-move-dialog")
  await expect(dialog).toContainText("3 on the NAS (6 files with their sidecars)")
  await expect(dialog.getByTestId("trash-move-only-copy")).toHaveCount(0)
  await shots(page, "move", open)
  await dialog.getByTestId("trash-move-confirm").click()
  await expect(page.getByText("3 moved to the trash")).toBeVisible()
  // Gone from the grid at once; on the NAS in today's .hoard-trash with every sidecar, the hidden one too.
  await expect(cell(page, "DSC07001.JPG")).toHaveCount(0)
  for (const f of ["DSC07000.ARW", "DSC07000.JPG", "DSC07000.xmp", "DSC07000.ARW.xmp", ".DSC07000.xmp", "DSC07001.JPG", "DSC07002.JPG"]) {
    expect(nas(`Trips/2025-06/${f}`), f).toBe(false)
    expect(nas(`.hoard-trash/${today}/Trips/2025-06/${f}`), f).toBe(true)
  }
})

test("a phone backup's photos (only copies) go to the server trash after a warning", async ({ page }) => {
  await signIn(page)
  await go(page, "/?q=PXL_20251101")
  await expect(cell(page, "PXL_20251101_180000.jpg")).toBeVisible({ timeout: 20_000 })
  await cell(page, "PXL_20251101_180000.jpg").click({ modifiers: ["ControlOrMeta"] })
  await page.getByTestId("trash-selection").click()
  const dialog = page.getByTestId("trash-move-dialog")
  await expect(dialog).toContainText("1 only on the server (phone backups, uploads)", { timeout: 20_000 })
  await expect(dialog.getByTestId("trash-move-only-copy")).toContainText("1 is the only copy anywhere")
  await shot(page, "move-only-copy-desktop-light")
  await dialog.getByTestId("trash-move-confirm").click()
  await expect(page.getByText("1 moved to the trash")).toBeVisible()
})

test("the Trash view: by day and place, with counts and sizes", async ({ page }) => {
  test.setTimeout(120_000)
  await signIn(page)
  const open = async () => {
    await go(page, "/trash")
    await expect(page.getByTestId("trash-group")).toHaveCount(2, { timeout: 60_000 }) // after the walk
  }
  await open()
  const day = page.locator(`[data-testid="trash-day"][data-day="${today}"]`)
  await expect(day).toContainText("4 shots")
  const nasGroup = page.locator('[data-testid="trash-group"][data-source="nas"]')
  await expect(nasGroup).toContainText("/ Trips/2025-06")
  await expect(nasGroup.getByTestId("trash-group-count")).toContainText("3 shots")
  const server = page.locator('[data-testid="trash-group"][data-source="server"]')
  await expect(server).toContainText("Server backup · E2E Pixel")
  await nasGroup.getByTestId("trash-group-toggle").click()
  await expect(nasGroup.getByTestId("trash-entry")).toHaveCount(3)
  await shots(page, "trash", async () => {
    await open()
    await page.locator('[data-testid="trash-group"][data-source="nas"]').getByTestId("trash-group-toggle").click()
  })
})

test("restore refuses a taken path, then puts the shot back", async ({ page }) => {
  await signIn(page)
  writeFileSync(path.join(NAS, "Trips/2025-06/DSC07001.JPG"), "a newer file someone put there")
  await go(page, "/trash")
  const nasGroup = page.locator('[data-testid="trash-group"][data-source="nas"]')
  await nasGroup.getByTestId("trash-group-toggle").click()
  await nasGroup.locator('[data-testid="trash-entry"][data-name="DSC07001.JPG"]').getByRole("checkbox").click()
  await page.getByTestId("trash-restore-selection").click()
  await expect(page.getByText("1 shot was not restored: another file is at Trips/2025-06/DSC07001.JPG")).toBeVisible()
  await shot(page, "restore-taken-desktop-light")
  rmSync(path.join(NAS, "Trips/2025-06/DSC07001.JPG"))
  await nasGroup.locator('[data-testid="trash-entry"][data-name="DSC07001.JPG"]').getByRole("checkbox").click()
  await page.getByTestId("trash-restore-selection").click()
  await expect(page.getByText("1 shot restored")).toBeVisible()
  expect(nas("Trips/2025-06/DSC07001.JPG")).toBe(true)
})

test("delete permanently, then Empty trash with its count and size", async ({ page }) => {
  test.setTimeout(120_000)
  await signIn(page)
  await go(page, "/trash")
  const server = page.locator('[data-testid="trash-group"][data-source="server"]')
  await server.getByTestId("trash-group-delete").click()
  await expect(page.getByTestId("confirm-dialog")).toContainText(/1 shot \([\d.]+ (KB|kB|MB)\) will be deleted for good/)
  await page.getByTestId("confirm-action").click()
  await expect(page.getByText(/1 shot deleted/)).toBeVisible()

  await expect(page.getByTestId("trash-empty")).toBeVisible({ timeout: 60_000 })
  await page.getByTestId("trash-empty").click()
  await expect(page.getByTestId("confirm-dialog")).toContainText(/2 shots \([\d.]+ (KB|kB|MB)\) will be deleted for good/)
  await shot(page, "empty-confirm-desktop-light")
  await page.getByTestId("confirm-action").click()
  await expect(page.getByText(/2 shots deleted/)).toBeVisible()
  expect(nas(`.hoard-trash/${today}/Trips/2025-06/DSC07000.ARW`)).toBe(false)
  expect(nas(`.hoard-trash/${today}/Trips/2025-06/.DSC07000.xmp`)).toBe(false)
  await expect(page.getByText("The trash is empty")).toBeVisible({ timeout: 60_000 })
})

test("Portuguese: Move to trash and the Trash view", async ({ page }) => {
  await signIn(page)
  await page.addInitScript(() => localStorage.setItem("hoard-web.language", "pt-BR"))
  await go(page, "/?q=IMG_0100")
  await expect(cell(page, "IMG_0100.JPG")).toBeVisible({ timeout: 20_000 })
  await cell(page, "IMG_0100.JPG").click({ modifiers: ["ControlOrMeta"] })
  await page.getByTestId("trash-selection").click()
  await expect(page.getByTestId("trash-move-summary")).toContainText("1 foto", { timeout: 20_000 })
  await shot(page, "move-pt-BR-desktop-light")
  await page.getByTestId("trash-move-confirm").click()
  await expect(page.getByText("1 movido para a lixeira")).toBeVisible()
  await go(page, "/trash")
  await expect(page.getByTestId("trash-group")).toHaveCount(1, { timeout: 60_000 }) // after the walk
  await expect(page.getByRole("heading", { name: "Lixeira" })).toBeVisible()
  await shot(page, "trash-pt-BR-desktop-light")
})
