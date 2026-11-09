import type { AddressKind } from "@/lib/addressKind"
import type {
  HoardSmbErrorCode,
  TransferConflict,
  TransferOp,
  TransferState,
} from "@/plugins/smb/definitions"

/** Row of `machines`. A machine owns its credentials (in the Keystore), shares and favorites. */
export interface MachineRecord {
  id: string
  name: string
  /** Phosphor icon id (kebab-case, e.g. `desktop-tower`), or null for the default. */
  icon: string | null
  /** Palette color id (e.g. `blue`), or null for the default. */
  color: string | null
  username: string
  domain: string | null
  /** SMB server GUID pinned on first successful connect (ADR 0002); null until then. */
  server_guid: string | null
  sort_order: number
  created_at: string
}

/** Row of `machine_addresses`: one way to reach a machine. */
export interface MachineAddressRecord {
  id: string
  machine_id: string
  host: string
  port: number
  kind: AddressKind
  label: string | null
  sort_order: number
}

export interface MachineWithAddresses extends MachineRecord {
  /** In priority order (`sort_order`). */
  addresses: MachineAddressRecord[]
}

/**
 * Row of `favorites`: a pinned folder in any location (v3, ADR 0005). `path` is relative to the
 * share (SMB) or the location's root (`""` = root).
 */
export interface FavoriteRecord {
  id: string
  /** The machine id (SMB), `device` or `vol-<uuid>` (same ids as `/browse/$locationId`). */
  location_id: string
  /** Set for SMB favorites (deleting the machine deletes them); null for local ones. */
  machine_id: string | null
  /** SMB share; `""` for local locations. */
  share: string
  path: string
  label: string
  sort_order: number
  created_at: string
}

/** Row of `manual_shares`: a share added by name because the server does not list it. */
export interface ManualShareRecord {
  machine_id: string
  /** Share name as the user typed it (unique per machine, case-insensitively). */
  name: string
  created_at: string
}

/**
 * Row of `transfers` (v4, ADR 0007): the app's record of a native job (same id). Locations use the
 * favorites' ids (machine id, `device`, `vol-<uuid>`) plus `tree` (a SAF folder in
 * `dest_tree_uri`) and `incoming` (files another app shared).
 */
export interface TransferRecord {
  id: string
  op: TransferOp
  source_location_id: string
  /** Set when the source is a machine (deleting it deletes the row). */
  source_machine_id: string | null
  source_share: string
  /** JSON array of the source paths (incoming: item indexes). */
  source_paths: string
  dest_location_id: string
  dest_machine_id: string | null
  dest_share: string
  /** The destination folder. */
  dest_path: string
  dest_tree_uri: string | null
  /** What the row shows: the single item's name, or the first source's. */
  name: string
  conflict: TransferConflict
  size: number
  bytes_done: number
  items_total: number
  items_done: number
  items_failed: number
  items_skipped: number
  state: TransferState
  error_code: HoardSmbErrorCode | null
  error: string | null
  created_at: string
  finished_at: string | null
}

/** A remembered destination folder ("Recent" in the destination picker). */
export interface RecentDestination {
  locationId: string
  share?: string
  path: string
  /** Shown name (folder name, share, drive label). */
  label: string
  /** For `tree` destinations. */
  treeUri?: string
}

/**
 * Known keys of `settings` (values are JSON strings):
 *  - `showHiddenFiles` boolean, `defaultSort` `{ key, direction }`, `defaultConflict` ConflictPolicy,
 *  - `lastDestination` (≤1.3, unused since 1.4), `recentDestinations` RecentDestination[] (the
 *    destination picker's "Recent", newest first), `probeTimeoutMs` number,
 *  - `onboarded` boolean (first-launch hint dismissed), `demoSeeded` / `worstCaseSeeded` (browser),
 *  - `storageIntroSeen` boolean (the all files access screen was shown once, ADR 0005).
 */
export type SettingKey =
  | "language"
  | "showHiddenFiles"
  | "defaultSort"
  | "defaultConflict"
  | "lastDestination"
  | "recentDestinations"
  | "probeTimeoutMs"
  | "onboarded"
  | "storageIntroSeen"
  | "demoSeeded"
  | "worstCaseSeeded"
