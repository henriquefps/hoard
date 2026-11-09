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
    { label: machine.name, link: { to: "/browse/$machineId", params: { machineId: machine.id } } },
    { label: t("machines.edit") },
  ]
}

/**
 * Machines › <Machine> [› <share> [› folder › folder]]. The trail is the path: every folder level
 * links to itself, the current folder is the last crumb.
 */
export function browseTrail(
  t: TFunction,
  machine: { id: string; name: string | undefined },
  share?: string,
  path = ""
): Crumb[] {
  const params = { machineId: machine.id }
  const trail: Crumb[] = [machinesCrumb(t)]
  const segments = path.split("/").filter(Boolean)
  if (!share) return [...trail, { label: machine.name }]
  trail.push({ label: machine.name, link: { to: "/browse/$machineId", params } })
  const levels = [
    { label: share, path: "" },
    ...segments.map((s, i) => ({ label: s, path: segments.slice(0, i + 1).join("/") })),
  ]
  levels.forEach((level, index) => {
    const current = index === levels.length - 1
    trail.push({
      label: level.label,
      link: current
        ? undefined
        : { to: "/browse/$machineId", params, search: { share, path: level.path || undefined } },
    })
  })
  return trail
}
