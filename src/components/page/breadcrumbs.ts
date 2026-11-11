import type { LinkOptions } from "@tanstack/react-router"
import type { TFunction } from "i18next"

// Breadcrumb trails for pages below the top level (hfps-app-ui rule: one function per page family,
// never built inline). The last crumb is the current page; every other crumb links to its level.
// A crumb whose name is still loading has no label (the header shows a skeleton).

export interface Crumb {
  /** The level's name; undefined while it loads. */
  label: string | undefined
  /** Where the level lives; absent only on the current page. */
  link?: LinkOptions | undefined
}

/** The level the back button goes to: the crumb before the current page. */
export const parentOf = (trail: readonly Crumb[]): Crumb | undefined =>
  trail.length > 1 ? trail[trail.length - 2] : undefined

/**
 * The crumbs the header shows above the title (1.9.1): the ancestors only. The current page is the
 * title, so it is not repeated as the last crumb; when the title names the level just above (the
 * culled folder under "Cull"), that level is dropped too: the back button goes there.
 */
export function headerCrumbs(trail: readonly Crumb[], title: string): Crumb[] {
  const ancestors = trail.slice(0, -1)
  const last = ancestors.at(-1)
  if (last && last.label !== undefined && last.label === title && trail.at(-1)?.label !== title) {
    return ancestors.slice(0, -1)
  }
  return ancestors
}

/**
 * How many crumbs of the tail fit on one line (the earlier ones go behind a "…" menu). `widths` are
 * the crumbs' natural widths (already capped by their max width), `separator` the space one "›"
 * takes, `more` the "…" button's. At least one crumb always shows (it truncates if it must).
 */
export function fitCrumbs(
  widths: readonly number[],
  available: number,
  separator: number,
  more: number
): number {
  const n = widths.length
  if (n === 0) return 0
  let used = 0
  for (let k = 1; k <= n; k++) {
    used += widths[n - k] + (k > 1 ? separator : 0)
    const overflow = k < n ? more + separator : 0
    if (used + overflow > available) return Math.max(1, k - 1)
  }
  return n
}

const machinesCrumb = (t: TFunction): Crumb => ({
  label: t("nav.machines"),
  link: { to: "/machines" },
})

/** Machines › New machine */
export const newMachineTrail = (t: TFunction): Crumb[] => [
  machinesCrumb(t),
  { label: t("machines.new") },
]

/** Machines › <Machine> › Edit */
export function editMachineTrail(
  t: TFunction,
  machine: { id: string; name: string | undefined }
): Crumb[] {
  return [
    machinesCrumb(t),
    {
      label: machine.name,
      link: { to: "/browse/$locationId", params: { locationId: machine.id } },
    },
    { label: t("machines.edit") },
  ]
}

/** What a browse trail needs to know about the location (`BrowseLocation` fits). */
export interface TrailLocation {
  /** Machine id, `device` or `vol-<uuid>`. */
  id: string
  kind: "smb" | "device" | "volume"
  /** Machine name, "Internal storage", the drive's label; undefined while loading. */
  name: string | undefined
}

/**
 * SMB: Machines › <Machine> [› <share> [› folder › folder]]. Local storage (ADR 0005): Home ›
 * <Internal storage | drive> [› folder › folder]. The trail is the path: every folder level links
 * to itself, the current folder is the last crumb.
 */
export function browseTrail(
  t: TFunction,
  location: TrailLocation,
  share?: string,
  path = ""
): Crumb[] {
  const params = { locationId: location.id }
  const segments = path.split("/").filter(Boolean)
  const folderLevels = segments.map((s, i) => ({
    label: s,
    path: segments.slice(0, i + 1).join("/"),
  }))
  if (location.kind !== "smb") {
    const levels = [{ label: location.name, path: "" }, ...folderLevels]
    return [
      { label: t("nav.home"), link: { to: "/" } },
      ...levels.map((level, index): Crumb => ({
        label: level.label,
        link:
          index === levels.length - 1
            ? undefined
            : { to: "/browse/$locationId", params, search: { path: level.path || undefined } },
      })),
    ]
  }
  const trail: Crumb[] = [machinesCrumb(t)]
  if (!share) return [...trail, { label: location.name }]
  trail.push({ label: location.name, link: { to: "/browse/$locationId", params } })
  const levels = [{ label: share, path: "" }, ...folderLevels]
  levels.forEach((level, index) => {
    const current = index === levels.length - 1
    trail.push({
      label: level.label,
      link: current
        ? undefined
        : { to: "/browse/$locationId", params, search: { share, path: level.path || undefined } },
    })
  })
  return trail
}

/** <folder trail> › <file>: the folder trail (every level a link), then the file. */
export function viewTrail(
  t: TFunction,
  location: TrailLocation,
  share: string | undefined,
  path: string
): Crumb[] {
  const segments = path.split("/").filter(Boolean)
  const folderPath = segments.slice(0, -1).join("/")
  const folder = browseTrail(t, location, share, folderPath)
  const current = folder[folder.length - 1]
  current.link = {
    to: "/browse/$locationId",
    params: { locationId: location.id },
    search: { share, path: folderPath || undefined },
  }
  return [...folder, { label: segments.at(-1) }]
}

/** <folder trail> › Cull: the culled folder links back to the browser (ADR 0008). */
export function cullTrail(
  t: TFunction,
  location: TrailLocation,
  share: string | undefined,
  path: string
): Crumb[] {
  const folder = browseTrail(t, location, share, path)
  const current = folder[folder.length - 1]
  current.link = {
    to: "/browse/$locationId",
    params: { locationId: location.id },
    search: { share, path: path || undefined },
  }
  return [...folder, { label: t("cull.title") }]
}
