import type { AddressKind } from "@/lib/addressKind"
import type { HoardSmbErrorCode, TransferState } from "@/plugins/smb/definitions"

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

/** Row of `favorites`: a pinned folder. `path` is relative to the share, `""` = share root. */
export interface FavoriteRecord {
  id: string
  machine_id: string
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

/** Row of `transfers`: the app's record of a native download job (same id). */
export interface TransferRecord {
  id: string
  machine_id: string
  share: string
  remote_path: string
  dest_tree_uri: string
  dest_name: string
  dest_uri: string | null
  size: number
  bytes_done: number
  state: TransferState
  error_code: HoardSmbErrorCode | null
  error: string | null
  created_at: string
  finished_at: string | null
}

/**
 * Known keys of `settings` (values are JSON strings):
 *  - `showHiddenFiles` boolean, `defaultSort` `{ key, direction }`, `defaultConflict` ConflictPolicy,
 *  - `lastDestination` the SAF tree URI used for the last download, `probeTimeoutMs` number,
 *  - `onboarded` boolean (first-launch hint dismissed), `demoSeeded` / `worstCaseSeeded` (browser).
 */
export type SettingKey =
  | "language"
  | "showHiddenFiles"
  | "defaultSort"
  | "defaultConflict"
  | "lastDestination"
  | "probeTimeoutMs"
  | "onboarded"
  | "demoSeeded"
  | "worstCaseSeeded"
