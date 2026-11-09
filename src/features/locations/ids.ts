import type { LocalLocationRef } from "@/plugins/smb/definitions"

/**
 * Location ids in routes and favorites (`/browse/$locationId`, `favorites.location_id`), ADR 0005:
 *  - `device`: the phone's shared storage;
 *  - `vol-<uuid>`: a removable volume (`vol-1A2B-3C4D`);
 *  - anything else: an SMB machine id (UUIDs from `crypto.randomUUID()`, never `device` or `vol-…`),
 *    so routes and favorites saved before 1.3.0 keep working unchanged.
 */
export type ParsedLocationId =
  { kind: "device" } | { kind: "volume"; uuid: string } | { kind: "smb"; machineId: string }

export const DEVICE_LOCATION_ID = "device"
const VOLUME_PREFIX = "vol-"

export const volumeLocationId = (uuid: string) => `${VOLUME_PREFIX}${uuid}`

export function parseLocationId(id: string): ParsedLocationId {
  if (id === DEVICE_LOCATION_ID) return { kind: "device" }
  if (id.startsWith(VOLUME_PREFIX) && id.length > VOLUME_PREFIX.length) {
    return { kind: "volume", uuid: id.slice(VOLUME_PREFIX.length) }
  }
  return { kind: "smb", machineId: id }
}

export const isLocalLocationId = (id: string) => parseLocationId(id).kind !== "smb"

/** The plugin ref of a local location id; undefined for machines. */
export function localRefOf(id: string): LocalLocationRef | undefined {
  const parsed = parseLocationId(id)
  if (parsed.kind === "device") return { kind: "device" }
  if (parsed.kind === "volume") return { kind: "volume", uuid: parsed.uuid }
  return undefined
}

/** The location id of a volume (`device` for the primary storage). */
export const locationIdOfVolume = (volume: { primary: boolean; uuid: string | null }) =>
  volume.primary || !volume.uuid ? DEVICE_LOCATION_ID : volumeLocationId(volume.uuid)

/** Absolute path shown for "Copy path" on local storage (`/storage/emulated/0/DCIM/Camera`). */
export function localDisplayPath(root: string | undefined, path: string) {
  const base = (root ?? "").replace(/\/+$/, "")
  return path ? `${base}/${path}` : base || "/"
}

/** Used share of a volume in [0, 1], or undefined when Android did not report sizes. */
export function usedFraction(volume: { totalBytes?: number; freeBytes?: number }) {
  const { totalBytes: total, freeBytes: free } = volume
  if (!total || free === undefined || total <= 0) return undefined
  return Math.min(1, Math.max(0, (total - free) / total))
}

/**
 * What changed between two volume lists (removable volumes only): a uuid that disappeared is
 * `removed`, a new one or one whose state moved reports its new state. The UI derives attach /
 * detach notices from this rather than from the event's own `change`, which depends on which
 * Android callback fired (and may name only the last of several changes).
 */
export function diffVolumes(
  previous: readonly { uuid: string | null; state: string }[] | undefined,
  next: readonly { uuid: string | null; state: string }[]
): { uuid: string; state: string }[] {
  const before = new Map(
    (previous ?? []).filter((v) => v.uuid).map((v) => [v.uuid!, v.state] as const)
  )
  const changes: { uuid: string; state: string }[] = []
  for (const volume of next) {
    if (!volume.uuid) continue
    if (before.get(volume.uuid) !== volume.state)
      changes.push({ uuid: volume.uuid, state: volume.state })
    before.delete(volume.uuid)
  }
  for (const uuid of before.keys()) changes.push({ uuid, state: "removed" })
  return changes
}
