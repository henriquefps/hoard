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

/** A culled folder: where (same keys as favorites) and the culling session's times (v5, ADR 0008). */
export interface CullFolderKey {
  /** Machine id (SMB), `device` or `vol-<uuid>`. */
  locationId: string
  /** SMB share; `""` for local locations. */
  share: string
  /** Folder inside the share or location root. */
  path: string
}

/** Row of `cull_sessions`: one culled folder (started, last visited, where the viewer was). */
export interface CullSessionRecord {
  location_id: string
  share: string
  path: string
  started_at: string
  last_visited_at: string
  /** Shot id (lowercase stem) last shown in the viewer. */
  last_shot_id: string | null
  shot_count: number
}

/** A culling flag; absent (null) = unmarked. */
export type CullFlag = "pick" | "reject"

/** Row of `cull_decisions`: a shot that is picked, rejected or rated (others have no row). */
export interface CullDecisionRecord {
  location_id: string
  share: string
  path: string
  /** Lowercase stem: a RAW + JPEG pair is one shot. */
  shot_id: string
  flag: CullFlag | null
  /** 0 (none) .. 5 stars. */
  rating: number
  updated_at: string
}

/**
 * Row of `cull_sidecars` (v7, ADR 0011): what the import rule needs about a shot's XMP sidecar. Times are epoch ms.
 */
export interface CullSidecarRecord {
  location_id: string
  share: string
  path: string
  shot_id: string
  /** Hoard's last decision on the shot (including unmarking it); null when only an import touched it. */
  decided_at: number | null
  /** The sidecar's mtime when Hoard last wrote or imported it. */
  sidecar_mtime: number | null
  /** 1: the sidecar held Hoard's latest decision at `sidecar_mtime`. */
  synced: number
}

/**
 * Row of `cull_analysis` (v6, ADR 0009): a shot's measurements, keyed by the analysed file (its
 * RAW, else its JPEG); valid while `size`, `modified_at` and `version` match the file and the app.
 */
export interface CullAnalysisRecord {
  location_id: string
  share: string
  /** The analysed file's path. */
  path: string
  /** Its folder (for listing a folder's rows). */
  folder: string
  size: number
  /** As listed (`CullFile.modifiedAt`); null when the location does not say. */
  modified_at: string | null
  version: number
  /** JSON of `CullAnalysis`. */
  analysis: string
  analysed_at: string
}

/** Row of `cull_destinations`: the last "Copy picks" destination for a source location. */
export interface CullDestinationRecord {
  source_location_id: string
  source_share: string
  location_id: string
  share: string
  path: string
  label: string | null
  /** JSON of the copy options last used (`CopyPicksOptions`). */
  options: string
  updated_at: string
}

/**
 * Known keys of `settings` (values are JSON strings):
 *  - `showHiddenFiles` boolean, `defaultSort` `{ key, direction }`, `defaultConflict` ConflictPolicy,
 *  - `lastDestination` (≤1.3, unused since 1.4), `recentDestinations` RecentDestination[] (the
 *    destination picker's "Recent", newest first), `probeTimeoutMs` number,
 *  - `onboarded` boolean (first-launch hint dismissed), `demoSeeded` / `worstCaseSeeded` (browser),
 *  - `storageIntroSeen` boolean (the all files access screen was shown once, ADR 0005),
 *  - `cullAutoAdvance` boolean (the culling viewer moves on after a pick or reject, ADR 0008),
 *  - `cullBlurSensitivity` / `cullEyesSensitivity` `"low" | "medium" | "high"`, `cullBurstGapMs`
 *    number (assisted culling, ADR 0009), `cullXmpSidecars` (1.6-1.8, dropped by migration v7),
 *  - `cullSaveXmp` boolean (decisions written to XMP sidecars next to the photos, and by "Copy picks"),
 *    `cullXmpHidden` boolean (new sidecars as `.<stem>.xmp`) (ADR 0011).
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
  | "cullAutoAdvance"
  | "cullBlurSensitivity"
  | "cullEyesSensitivity"
  | "cullBurstGapMs"
  | "cullXmpSidecars"
  | "cullSaveXmp"
  | "cullXmpHidden"
  | "cullSwipeDecide"
  | "revealStreamNoteSeen"
  | "demoSeeded"
  | "worstCaseSeeded"
