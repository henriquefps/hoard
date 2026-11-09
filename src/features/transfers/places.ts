import type { RecentDestination } from "@/db/types"
import type { TransferConflict, TransferOp } from "@/plugins/smb/definitions"
import type { TransferPlace, TransferRequest } from "./service"

/**
 * Pure rules for picking where files go (ADR 0007): the clipboard's "Paste here", the destination
 * picker's checks and its "Recent" list. Unit tested.
 */

/** A folder in any location (the place plus the folder's path). */
export type FolderPlace = TransferPlace & { path: string }

/** What is selected for a transfer: paths in one place, with what the UI already knows. */
export interface PickedEntry {
  path: string
  name: string
  isDirectory: boolean
  size: number
}

const clean = (path: string) => path.split("/").filter(Boolean).join("/")
const sameShare = (a?: string, b?: string) => (a ?? "").toLowerCase() === (b ?? "").toLowerCase()

/** The same location (and share): moves there are renames, and a folder must not go into itself. */
export function sameLocation(a: TransferPlace, b: TransferPlace) {
  return (
    a.locationId === b.locationId &&
    sameShare(a.share, b.share) &&
    (a.treeUri ?? "") === (b.treeUri ?? "")
  )
}

export function isSameOrInside(parent: string, child: string) {
  const p = clean(parent)
  const c = clean(child)
  return p === "" || c === p || c.startsWith(`${p}/`)
}

export const parentOf = (path: string) => clean(path).split("/").slice(0, -1).join("/")

/**
 * Why `entries` (from `source`) cannot go to `target`, or null when they can:
 *  - `intoItself`: a folder into itself or one of its subfolders;
 *  - `sameFolder`: a move into the folder the entries are already in (nothing would happen).
 * A copy into the same folder is allowed: it makes "name (1)" copies.
 */
export function pasteProblem(
  op: TransferOp,
  source: TransferPlace,
  entries: Pick<PickedEntry, "path" | "isDirectory">[],
  target: FolderPlace
): "intoItself" | "sameFolder" | null {
  if (!sameLocation(source, target)) return null
  if (entries.some((e) => e.isDirectory && isSameOrInside(e.path, target.path))) return "intoItself"
  if (op === "move" && entries.every((e) => parentOf(e.path) === clean(target.path))) {
    return "sameFolder"
  }
  return null
}

/** The transfer for `entries` of `source` → `destination`. */
export function transferRequest(
  op: TransferOp,
  source: TransferPlace,
  entries: PickedEntry[],
  destination: FolderPlace,
  conflict: TransferConflict
): TransferRequest {
  const files = entries.filter((e) => !e.isDirectory)
  return {
    op,
    source,
    paths: entries.map((e) => e.path),
    name: entries[0]?.name ?? "",
    size: files.reduce((sum, e) => sum + e.size, 0),
    itemsTotal: entries.length,
    destination,
    conflict,
  }
}

/** Put `destination` first in the recent list (no duplicates), keeping at most `max`. */
export function rememberDestination(
  list: readonly RecentDestination[],
  destination: RecentDestination,
  max: number
): RecentDestination[] {
  const same = (d: RecentDestination) =>
    d.locationId === destination.locationId &&
    sameShare(d.share, destination.share) &&
    clean(d.path) === clean(destination.path) &&
    (d.treeUri ?? "") === (destination.treeUri ?? "")
  return [destination, ...list.filter((d) => !same(d))].slice(0, max)
}

/** Recent destinations whose location still exists (machines deleted, drives never seen again). */
export function liveDestinations(
  list: readonly RecentDestination[],
  isKnown: (locationId: string) => boolean
): RecentDestination[] {
  return list.filter((d) => isKnown(d.locationId))
}
