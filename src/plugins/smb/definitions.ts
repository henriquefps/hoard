/**
 * The HoardSmb plugin contract: everything the app asks the native side to do. Despite the name
 * it covers every **location** files live in (ADR 0005): SMB shares, the phone's shared storage
 * and removable volumes (USB drives, SD cards). File operations take a {@link LocationRef}; the
 * SMB-only methods (probe, discover, credentials, connect, listShares) take a machine or session.
 *
 * Three implementations must stay in lockstep (see CLAUDE.md, "plugin contract rule"):
 *  - this file (the types the UI is written against),
 *  - `web.ts` (the in-memory mock used by `bun run dev` and the tests),
 *  - `android/app/src/main/java/dev/hfps/hoard/smb/HoardSmbPlugin.kt` (smbj + SAF).
 *
 * Ground rules (ADR 0001 / 0002 / 0003):
 *  - File bytes never cross the JS bridge. Transfers stream between locations natively; thumbnails and opened
 *    files come back as cache-file URLs, never as base64; streams and served pages are URIs/URLs
 *    another app (or a native WebView) reads natively. The one exception is small text files
 *    (`readTextFile` / `writeTextFile`, bounded by `maxBytes`) for the built-in text editor.
 *  - Passwords are write-only from JS: `setCredentials` stores them in the Android Keystore and no
 *    method ever returns one. `connect`, `startTransfer` and streams read them natively by `machineId`.
 *  - A machine is pinned by its SMB server GUID. Once the app knows it, every call that connects
 *    passes `expectedServerGuid`; the native side compares it right after NEGOTIATE (before
 *    SESSION_SETUP, so the password is never sent to a different server) and fails with
 *    `GUID_MISMATCH`.
 *  - Every rejection carries one of {@link HoardSmbErrorCode} in `error.code`
 *    (Capacitor's `call.reject(message, code)`), so the UI can branch without parsing messages.
 */

import type { PluginListenerHandle } from "@capacitor/core"

// ---------------------------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------------------------

/** Every error code a HoardSmb call can reject with (found in `error.code`). */
export const HOARD_SMB_ERROR_CODES = [
  /** TCP connect to the host/port failed (refused, no route, DNS failure). */
  "UNREACHABLE",
  /** The operation did not finish within its `timeoutMs` (or the native default). */
  "TIMEOUT",
  /** The server rejected the username/password/domain, or no credentials are stored for the machine. */
  "AUTH_FAILED",
  /** The server answered with a different GUID than `expectedServerGuid`: not the saved machine. */
  "GUID_MISMATCH",
  /** Authenticated, but the account may not read/write this share or path. */
  "ACCESS_DENIED",
  /** Share, path, session or transfer does not exist (also: `sessionId` expired). */
  "NOT_FOUND",
  /** Already exists (createFolder/rename onto an existing name, conflict = "skip" in a sync call). */
  "ALREADY_EXISTS",
  /** The destination ran out of space (`data.needed` / `data.free` from the pre-transfer check). */
  "NO_SPACE",
  /** Cancelled by `cancelTransfer`, by the user in a system picker, or by a newer call. */
  "CANCELLED",
  /** The persisted SAF permission for a destination folder was revoked or the folder is gone. */
  "PERMISSION_REVOKED",
  /** No app on the device can open this file type (`openFile`). */
  "NO_APP",
  /** Anything else; `message` has the details. Also returned by the not-yet-implemented native stub. */
  "UNKNOWN",
  /** `readTextFile`: the file is bigger than `maxBytes` (`data.size` has its size); retry with `truncate`. */
  "TOO_LARGE",
  /** `readTextFile`: the file looks binary (NUL bytes without a UTF-16 byte order mark). */
  "NOT_TEXT",
  /**
   * `writeTextFile`: the remote file changed (or was deleted) since it was read: its modified time
   * is not `expectedModifiedAt`. `data.modifiedAt` has the current one (null when it is gone).
   */
  "CONFLICT",
  /**
   * Device or volume location: Hoard may not read the phone's files. All files access
   * (`MANAGE_EXTERNAL_STORAGE`, or the storage permission before Android 11) was not granted, and
   * for a volume no SAF grant exists either. `requestStorageAccess` / `grantVolumeAccess` fix it.
   */
  "STORAGE_PERMISSION",
  /**
   * Volume location: the volume is not mounted (unplugged, ejected, still being checked, or a
   * filesystem Android cannot read). `data.state` has its {@link VolumeState} when known.
   */
  "UNAVAILABLE",
] as const

export type HoardSmbErrorCode = (typeof HOARD_SMB_ERROR_CODES)[number]

/** Shape of a rejected HoardSmb call (a Capacitor `CapacitorException`). */
export interface HoardSmbError extends Error {
  code: HoardSmbErrorCode
  /** Extra fields for some codes, e.g. `{ serverGuid }` on `GUID_MISMATCH`. */
  data?: Record<string, unknown>
}

/** Narrow an unknown thrown value to a HoardSmb error. */
export function isHoardSmbError(error: unknown): error is HoardSmbError {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    HOARD_SMB_ERROR_CODES.includes((error as { code: unknown }).code as HoardSmbErrorCode)
  )
}

/** The code of any thrown value; `UNKNOWN` when it is not a HoardSmb error. */
export function smbErrorCode(error: unknown): HoardSmbErrorCode {
  return isHoardSmbError(error) ? error.code : "UNKNOWN"
}

// ---------------------------------------------------------------------------------------------
// Shared types
// ---------------------------------------------------------------------------------------------

/** A host and port to reach a machine on. Port defaults to 445 everywhere. */
export interface SmbAddress {
  /** IPv4/IPv6 literal or a DNS / MagicDNS / mDNS name (`192.168.1.155`, `100.101.2.3`, `cachyos-fps`). */
  host: string
  /** TCP port; default 445. */
  port?: number
}

/** ISO-8601 timestamp string (UTC, e.g. `2026-11-08T21:14:03.120Z`). */
export type IsoDateTime = string

/** SMB dialect negotiated for a session. */
export type SmbDialect = "SMB_2_0_2" | "SMB_2_1" | "SMB_3_0" | "SMB_3_0_2" | "SMB_3_1_1"

/** An entry inside a share folder. */
export interface SmbEntry {
  /** File or folder name (the last path segment). */
  name: string
  /** Path inside the share, `/`-separated, no leading slash: `Photos/2026/IMG_0001.jpg`. Root is `""`. */
  path: string
  isDirectory: boolean
  /** Size in bytes; 0 for folders. */
  size: number
  /** Last write time, or null when the server does not report it. */
  modifiedAt: IsoDateTime | null
  /** Hidden attribute set, or the name starts with `.`. */
  isHidden: boolean
  /**
   * Device and volume locations only: a folder Android keeps to itself (`Android/data`,
   * `Android/obb` on Android 11+). Listed so the tree looks like it does in other file managers,
   * but listing it rejects `ACCESS_DENIED` with `data.restricted: true`. Absent elsewhere.
   */
  restricted?: boolean
}

/** Same shape for every location; the name is historical (SMB came first). */
export type FileEntry = SmbEntry

// ---------------------------------------------------------------------------------------------
// Locations (ADR 0005)
// ---------------------------------------------------------------------------------------------

/**
 * Where a file operation happens. Paths are always `/`-separated, without a leading slash,
 * relative to the location's root (`""` is the root):
 *  - `smb`: a share on a connected session (path inside the share);
 *  - `device`: the phone's shared storage, `/storage/emulated/0` (path below it: `DCIM/Camera`);
 *  - `volume`: a removable volume (USB OTG drive, SD card) by its {@link StorageVolumeInfo.uuid}
 *    (path below the volume root). Natively read by path with all files access, else through the
 *    volume's SAF tree when `grantVolumeAccess` granted one.
 */
export type LocationRef =
  | { kind: "smb"; sessionId: string; share: string }
  | { kind: "device" }
  | { kind: "volume"; uuid: string }

/** The kinds of {@link LocationRef}. */
export type LocationKind = LocationRef["kind"]

/** A local location (no network, no session). */
export type LocalLocationRef = Extract<LocationRef, { kind: "device" | "volume" }>

/** Mount state of a storage volume (Android's `Environment.MEDIA_*`, simplified). */
export type VolumeState =
  /** Readable and writable. */
  | "mounted"
  /** Readable only (write-protect switch, a filesystem Android mounts read-only such as NTFS on some phones). */
  | "mountedReadOnly"
  /** Attached and being checked; becomes `mounted` shortly. */
  | "checking"
  /** Being ejected from Settings. */
  | "ejecting"
  /** Known but not mounted (ejected, unplugged, not formatted, unsupported filesystem). */
  | "unmounted"
  /** A filesystem Android cannot mount (exFAT on old phones, ext4, APFS…). */
  | "unmountable"

/** One storage volume: the phone's own storage (`primary`) or a removable one. */
export interface StorageVolumeInfo {
  /**
   * Stable id across plug-ins: the filesystem UUID (`1A2B-3C4D` for FAT/exFAT). `null` for the
   * primary (internal) storage, which is the `device` location.
   */
  uuid: string | null
  /** System label ("Internal storage", "SanDisk USB drive", "SD card"). */
  label: string
  primary: boolean
  removable: boolean
  /** Best guess from Android's own description and the USB devices attached; `internal` for the primary. */
  kind: "internal" | "usb" | "sd" | "other"
  state: VolumeState
  /** Root directory (`/storage/1A2B-3C4D`) when Android exposes it. */
  path?: string
  /** Capacity and free space, when the volume is mounted and readable. */
  totalBytes?: number
  freeBytes?: number
  /** Filesystem type (`vfat`, `exfat`, `sdcardfs`…) when `/proc/mounts` says. */
  fileSystem?: string
  /**
   * How Hoard reads it now: `path` (all files access, plain file paths), `saf` (Storage Access
   * Framework folder grants on it, from `grantVolumeAccess`), `none` (no access yet).
   */
  access: "path" | "saf" | "none"
}

export interface ListVolumesResult {
  /** The primary storage first, then removable volumes (mounted or not) in the order Android lists them. */
  volumes: StorageVolumeInfo[]
}

/** Whether Hoard may read and write the phone's shared storage by path. */
export interface StorageAccessResult {
  /**
   * All files access is on (`MANAGE_EXTERNAL_STORAGE` on Android 11+; the storage runtime
   * permission before). Device and volume locations need it, or a SAF grant per volume.
   */
  granted: boolean
  /** `allFiles` (Android 11+, a Settings switch) or `runtime` (Android 10 and older, a dialog). */
  mechanism: "allFiles" | "runtime"
}

export interface VolumeRef {
  /** {@link StorageVolumeInfo.uuid} of a removable volume. */
  uuid: string
}

export interface OpenStorageSettingsOptions {
  /** Open the page for this volume when Android has one (else the storage overview). */
  uuid?: string
}

/**
 * What the connected account may do in a share, folder or file, as the server itself answered it
 * (never a guess from names or from a failed write):
 *  - share level: the TREE_CONNECT response's `MaximalAccess`;
 *  - folder / file level: the SMB2_CREATE_QUERY_MAXIMAL_ACCESS ("MxAc") create context on the open
 *    Hoard already does to list the folder or read the file, so per-folder ACLs and filesystem
 *    permissions count. A share-level `read` always wins.
 *
 * `readWrite` when the mask grants FILE_WRITE_DATA/ADD_FILE, FILE_APPEND_DATA/ADD_SUBDIRECTORY,
 * FILE_DELETE_CHILD, GENERIC_WRITE or GENERIC_ALL; `read` otherwise. Absent (undefined) when the
 * server gave no usable answer, which the UI treats as writable. It is a hint, not a permission:
 * writes still fail with `ACCESS_DENIED` when the server refuses them (access can change).
 */
export type SmbAccess = "read" | "readWrite"

/** A disk share on a server (IPC$/printer shares are filtered out natively). */
export interface SmbShare {
  /** Share name used in every later call (`media`, `homes`, `Public`). */
  name: string
  /** Server-provided remark, if any. */
  comment?: string
  /** Hidden administrative share (name ends with `$`). */
  isHidden: boolean
  /**
   * The account's access to the share root (TREE_CONNECT MaximalAccess). Undefined when the share
   * could not be connected in time or the server did not say: `listShares` never fails for it.
   */
  access?: SmbAccess
}

// ---------------------------------------------------------------------------------------------
// Options and results, grouped by area
// ---------------------------------------------------------------------------------------------

export interface ProbeOptions extends SmbAddress {
  /** Give up after this long; default 1500 ms. */
  timeoutMs?: number
}

/**
 * Result of a TCP connect to `host:port`. Never rejects for an unreachable host: `reachable: false`
 * is a normal answer. It only rejects (`UNKNOWN`) for invalid input.
 */
export interface ProbeResult {
  reachable: boolean
  /** Time to complete the TCP handshake, when reachable. */
  latencyMs?: number
  /** Why it is not reachable: `UNREACHABLE` (refused / no route / DNS) or `TIMEOUT`. */
  reason?: "UNREACHABLE" | "TIMEOUT"
}

export interface DiscoverOptions {
  /** How long to browse `_smb._tcp` with NSD; default 3000 ms. */
  timeoutMs?: number
}

/** An SMB server announced on the local network (NSD / mDNS `_smb._tcp`). */
export interface DiscoveredService {
  /** Service instance name, usually the machine's hostname (`cachyos-fps`). */
  name: string
  /** Resolved address (IPv4 preferred). */
  host: string
  port: number
}

export interface DiscoverResult {
  services: DiscoveredService[]
}

export interface SetCredentialsOptions {
  /** `machines.id` from the app database. */
  machineId: string
  username: string
  /** Stored in the Android Keystore; never readable from JS again. */
  password: string
  /** Windows domain / workgroup; omit for none. */
  domain?: string
}

export interface MachineRef {
  machineId: string
}

export interface HasCredentialsResult {
  /** True when a password is stored for the machine. */
  value: boolean
}

export interface ConnectOptions extends SmbAddress {
  /** Credentials are looked up natively by this id. Rejects `AUTH_FAILED` if none are stored. */
  machineId: string
  /** The GUID saved on first connect. Omit only on the very first connect (or after the user re-pins). */
  expectedServerGuid?: string | null
  /** Default 5000 ms (TCP + negotiate + session setup). */
  timeoutMs?: number
}

export interface ConnectResult {
  /** Opaque handle for later calls; valid until `disconnect` or until the native side drops it (then `NOT_FOUND`). */
  sessionId: string
  /** The server GUID from NEGOTIATE, lowercase UUID string. Save it on the machine on first connect. */
  serverGuid: string
  /** NetBIOS / DNS name the server reports, when available. */
  serverName?: string
  dialect: SmbDialect
}

export interface SessionRef {
  sessionId: string
}

export interface ListSharesResult {
  shares: SmbShare[]
}

/** Every file operation: a location and a path inside it. */
export interface PathOptions {
  location: LocationRef
  /** Path inside the location (see {@link SmbEntry.path} and {@link LocationRef}); `""` is its root. */
  path: string
}

export interface ListResult {
  /** Folder contents, unsorted (the UI sorts). `.` and `..` are never included. */
  entries: SmbEntry[]
  /**
   * The account's access to this folder (share and folder level combined); undefined when unknown.
   * Local locations: `read` on a read-only mounted volume or a folder Hoard may not write.
   */
  access?: SmbAccess
}

export interface StatResult {
  entry: SmbEntry
}

export interface CreateFolderOptions extends PathOptions {
  /** Name of the new folder inside `path`. Rejects `ALREADY_EXISTS` if taken. */
  name: string
}

export interface RenameOptions extends PathOptions {
  /** New name in the same folder (not a path). Rejects `ALREADY_EXISTS` if taken. */
  newName: string
}

export interface DeleteOptions extends PathOptions {
  /** Required to delete a non-empty folder; otherwise a non-empty folder rejects `ACCESS_DENIED`. */
  recursive?: boolean
}

/** A folder on the phone the user granted with the Storage Access Framework (persisted): a {@link TreeLocation}. */
export interface DestinationFolder {
  /** The `content://…/tree/…` URI; a transfer destination as `{ kind: "tree", uri }`. */
  uri: string
  /** Display name of the folder (`Download`, `Movies/Hoard`). */
  name: string
}

export interface ListDestinationFoldersResult {
  /** Every tree URI with a persisted permission that is still valid. */
  folders: DestinationFolder[]
}

export interface ReleaseDestinationFolderOptions {
  uri: string
}

export interface OpenFileOptions extends PathOptions {
  /** Overrides the MIME type guessed from the extension. */
  mimeType?: string
  /** Always show the system "Open with" chooser, even when the user set a default app. */
  chooser?: boolean
}

export interface ThumbnailOptions extends PathOptions {
  /** Longest side in px of the generated thumbnail (e.g. 96 for a row, 320 for a grid). */
  maxSize: number
}

export interface ThumbnailResult {
  /**
   * URL usable as `<img src>` (native: a cached JPEG through `Capacitor.convertFileSrc`; web mock:
   * a data/blob URL). Cached by machine + share + path + size + modifiedAt.
   */
  src: string
  width: number
  height: number
}

// --- Streaming and the local web server ---------------------------------------------------------

/**
 * An SMB share as a stream source or transfer endpoint. Like a transfer, native opens its **own** connection (not
 * a `sessionId`): it outlives the UI's sessions and the WebView, and reconnects / fails over
 * across `addresses` while another app plays, re-checking the GUID before credentials.
 */
export interface SmbStreamLocation {
  kind: "smb"
  machineId: string
  /** Priority order, the route in use first. */
  addresses: SmbAddress[]
  /** Pinned GUID, checked on every (re)connect. */
  expectedServerGuid: string
  share: string
}

/**
 * Where a stream or a served page reads from: an SMB share (own connection) or a local location
 * (the file itself: no stream token, no foreground service for `streamFile`).
 */
export type StreamLocation = SmbStreamLocation | LocalLocationRef

export interface StreamSourceOptions {
  location: StreamLocation
  /** File path inside the location. */
  path: string
}

/**
 * How another app reaches a stream:
 *  - `content`: `content://dev.hfps.hoard.stream/<token>/<name>`, a seekable proxy file descriptor
 *    (Android 8+) with a one-off read grant for the chosen app. The default: works with every
 *    player (VLC, MX Player, mpv…) and non-media apps, nothing listens on a port.
 *  - `http`: `http://127.0.0.1:<port>/<token>/<name>` with Range support, from the local web
 *    server. For players that only take URLs. Native also uses it below Android 8.
 */
export type StreamTransport = "content" | "http"

export interface StreamFileOptions extends StreamSourceOptions {
  /** Overrides the MIME type guessed from the extension. */
  mimeType?: string
  /** Always show the system "Open with" chooser. */
  chooser?: boolean
  /** Default `content`. */
  transport?: StreamTransport
}

export interface StreamFileResult {
  /** The URI handed to the other app (unguessable token; ends after inactivity). */
  uri: string
  /** File size in bytes. */
  size: number
  /** MIME type sent with the intent. */
  mimeType: string
}

export interface OpenWebPageOptions extends StreamSourceOptions {
  /**
   * `viewer` (default): Hoard's full-screen native web viewer (its toolbar has "Open in browser").
   * `browser`: hand the URL straight to the default browser.
   */
  target?: "viewer" | "browser"
}

export interface OpenWebPageResult {
  /** `http://127.0.0.1:<port>/<token>/<file name>`: the page's folder (and below) is served. */
  url: string
}

// --- Transfers (ADR 0007: any location → any location) ---------------------------------------

/**
 * What happens when a file already exists at the destination:
 *  - `rename` keeps both (`photo (1).jpg`), `overwrite` replaces it (only after the new copy is
 *    complete and verified), `skip` leaves it and counts the item as skipped;
 *  - `ask` (start options only): the job scans, then stops in state `conflict` and reports how many
 *    names clash; the UI asks once ("apply to all") and answers with `resolveTransferConflicts`.
 * Folders that already exist are always merged; the policy applies to the files inside.
 */
export type ConflictPolicy = "rename" | "overwrite" | "skip"
export type TransferConflict = ConflictPolicy | "ask"

/** Lifecycle of a native transfer job (and of each of its items, except `scanning`/`conflict`). */
export type TransferState =
  /** Accepted, waiting for a worker slot or for the network. */
  | "queued"
  /** Listing source folders: totals (files, bytes) grow until the scan ends. */
  | "scanning"
  /** `conflict: "ask"` found names that exist: waiting for `resolveTransferConflicts`. */
  | "conflict"
  /** Bytes are flowing. */
  | "running"
  /** Lost the connection (or the drive); native is retrying (same address, then the next in the list). */
  | "retrying"
  | "completed"
  /** At least one item failed (the others may have completed: `itemsDone`). */
  | "failed"
  | "cancelled"
  /** Every item was skipped (`conflict: "skip"` and all names existed): nothing was written. */
  | "skipped"

export const TERMINAL_TRANSFER_STATES: readonly TransferState[] = [
  "completed",
  "failed",
  "cancelled",
  "skipped",
]

/** `copy` leaves the sources; `move` deletes each source only after its copy was verified. */
export type TransferOp = "copy" | "move"

/**
 * A folder on the phone granted with the Storage Access Framework (`pickDestinationFolder`): a
 * destination for phones without all files access. Paths inside it are relative to the tree root.
 */
export interface TreeLocation {
  kind: "tree"
  /** The `content://…/tree/…` URI from `pickDestinationFolder` / `listDestinationFolders`. */
  uri: string
}

/**
 * Files another app shared to Hoard (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`), read natively through
 * the ContentResolver. Source only; a source `path` is the item's `index` as a string.
 */
export interface IncomingLocation {
  kind: "incoming"
  shareId: string
}

/**
 * Where a transfer reads or writes. Like streams, an SMB endpoint is **not** a session: native
 * opens its own connection over `addresses` (failover in order, GUID checked before credentials),
 * so jobs survive the UI's sessions, the WebView and a process restart.
 */
export type TransferLocation = SmbStreamLocation | LocalLocationRef | TreeLocation

export type TransferSourceLocation = TransferLocation | IncomingLocation

export interface TransferSource {
  location: TransferSourceLocation
  /** A file or a folder (copied recursively, with a scan step first). */
  path: string
}

export interface TransferDestination {
  location: TransferLocation
  /** The folder the sources land in (`""` = the location's root). It must exist. */
  path: string
}

export interface StartTransferOptions {
  /** `transfers.id` from the app database: the UI records the row first, then starts the job. */
  transferId: string
  op: TransferOp
  /** Files and folders, all from one location in practice (any mix is accepted). */
  sources: TransferSource[]
  destination: TransferDestination
  conflict: TransferConflict
  /**
   * Run a job again under the same id (Retry): items already completed or skipped stay so; failed
   * and cancelled ones run again, resuming partial files where the bytes on disk still match. When
   * native no longer has the job, it scans again and treats an existing destination file of the
   * same size as already copied.
   */
  resume?: boolean
  /**
   * Copies only: small XMP files written **at the destination** next to copied files (ADR 0009),
   * as `<destination stem>.xmp` once the file at `sourcePath` is copied (a conflict rename carries
   * over: `DSC1 (1).ARW` → `DSC1 (1).xmp`; a skipped file gets none). Best effort: a sidecar that
   * cannot be written does not fail the item. At most 5,000, 16 KB each.
   */
  sidecars?: TransferSidecar[]
}

export interface TransferSidecar {
  /** A source file of the job (as in `sources[].path`). */
  sourcePath: string
  /** The XMP document (UTF-8). */
  content: string
}

export interface StartTransferResult {
  transferId: string
}

export interface TransferRef {
  transferId: string
}

/** One file (or folder to create) of a job. */
export interface TransferItem {
  /** Stable index inside the job. */
  index: number
  /** Path inside the source location (incoming: the item index). */
  sourcePath: string
  /** Path inside the destination location, after conflict renames. */
  destPath: string
  isDirectory: boolean
  size: number
  bytesDone: number
  state: Exclude<TransferState, "scanning" | "conflict">
  /** The destination name already existed (and what was decided, once decided). */
  conflict?: ConflictPolicy | "ask"
  error?: { code: HoardSmbErrorCode; message: string }
}

export interface GetTransferItemsOptions extends TransferRef {
  /** Only items in these states (e.g. `["failed"]`); default all. */
  states?: TransferItem["state"][]
  /** Only items still waiting for a conflict answer. */
  conflictsOnly?: boolean
  offset?: number
  /** Default 200, at most 1000. */
  limit?: number
}

export interface GetTransferItemsResult {
  items: TransferItem[]
  /** Matching items in total (for paging). */
  total: number
}

export interface ResolveTransferConflictsOptions extends TransferRef {
  /** Answer for every item still asking. */
  all?: ConflictPolicy
  /** Per-item answers (`index` → policy); `all` covers the rest. */
  items?: { index: number; policy: ConflictPolicy }[]
}

/** A native job as the background service knows it. */
export interface NativeTransfer {
  transferId: string
  op: TransferOp
  state: TransferState
  /** Bytes of every file to write (grows while `scanning`). */
  totalBytes: number
  bytesDone: number
  /** Files (folders are not counted). */
  itemsTotal: number
  itemsDone: number
  itemsFailed: number
  itemsSkipped: number
  /** Names still waiting for an answer in state `conflict`. */
  conflicts?: number
  /** The file being copied now. */
  currentName?: string
  /** Set when `state` is `failed` (or while `retrying`, the last error). */
  error?: { code: HoardSmbErrorCode; message: string }
  /** Address currently used (or last used), on SMB endpoints. */
  activeHost?: string
  startedAt: IsoDateTime
  finishedAt?: IsoDateTime
}

export interface GetTransfersResult {
  /** Jobs native still reports: running, queued, and finished ones not yet acknowledged. */
  transfers: NativeTransfer[]
}

// --- Share in and out --------------------------------------------------------------------------

export interface ShareFilesOptions {
  /** Where the files are (SMB: own connection, like `streamFile`). */
  location: StreamLocation
  /** Files (not folders) in that location. */
  paths: string[]
}

/** One file another app shared to Hoard. */
export interface SharedFile {
  /** Its `path` in an {@link IncomingLocation}. */
  index: number
  name: string
  /** -1 when the other app did not say. */
  size: number
  mimeType?: string
}

/** What another app shared (one `ACTION_SEND` / `ACTION_SEND_MULTIPLE`). */
export interface SharedFiles {
  shareId: string
  files: SharedFile[]
  /** Text shared without a file (a link, a note), when any. Not saved by transfers. */
  text?: string
  /**
   * Items Hoard refused to read (ADR 0007, "Share in"): anything that is not `content://`
   * (`file://` paths) or that one of Hoard's own providers serves. They are not in `files`.
   */
  rejected?: number
  receivedAt: IsoDateTime
}

export interface GetSharedFilesResult {
  /** The latest share not yet cleared, or null. */
  shared: SharedFiles | null
}

// --- Text files (the built-in viewer and editor) ---------------------------------------------

/**
 * How a text file's bytes were decoded, and how `writeTextFile` encodes them again:
 *  - `utf-8`: valid UTF-8 without a byte order mark (also every pure ASCII file),
 *  - `utf-8-bom`: UTF-8 starting with EF BB BF (kept on save),
 *  - `utf-16le` / `utf-16be`: only detected from their byte order mark (kept on save),
 *  - `windows-1252`: the fallback for anything that is not valid UTF-8. It is ISO-8859-1 with
 *    printable characters in 0x80–0x9F (what browsers decode for "iso-8859-1"); the five bytes
 *    Windows leaves undefined map to the C1 controls, so every byte round-trips unchanged.
 */
export type TextEncoding = "utf-8" | "utf-8-bom" | "utf-16le" | "utf-16be" | "windows-1252"

export const TEXT_ENCODINGS: readonly TextEncoding[] = [
  "utf-8",
  "utf-8-bom",
  "utf-16le",
  "utf-16be",
  "windows-1252",
]

/** Default `maxBytes` of `readTextFile`: larger files reject `TOO_LARGE` unless `truncate`. */
export const TEXT_FILE_MAX_BYTES = 5 * 1024 * 1024

/** Natively `maxBytes` is clamped to this: text crosses the bridge as one JSON string. */
export const TEXT_FILE_HARD_LIMIT = 16 * 1024 * 1024

export interface ReadTextFileOptions extends PathOptions {
  /** Refuse (`TOO_LARGE`) files bigger than this; default {@link TEXT_FILE_MAX_BYTES}. */
  maxBytes?: number
  /**
   * Instead of rejecting a file bigger than `maxBytes`, return its first `maxBytes` bytes
   * (cut at a character boundary) with `truncated: true`. The UI shows that read-only.
   */
  truncate?: boolean
}

export interface ReadTextFileResult {
  /** The decoded text, line endings untouched (`\r\n` stays `\r\n`), without the byte order mark. */
  text: string
  encoding: TextEncoding
  /** Size of the whole remote file in bytes (also when `truncated`). */
  size: number
  /**
   * The remote last write time when it was read; pass it back as `expectedModifiedAt` to save.
   * Null when the server does not report one (then conflicts cannot be detected).
   */
  modifiedAt: IsoDateTime | null
  /** Only the first `maxBytes` bytes were read (`truncate: true` on a bigger file). */
  truncated: boolean
  /** The account's access to the file (share and file level combined); undefined when unknown. */
  access?: SmbAccess
}

export interface WriteTextFileOptions extends PathOptions {
  text: string
  /** Encode as this; default `utf-8`. Rejects `UNKNOWN` if `windows-1252` cannot hold a character. */
  encoding?: TextEncoding
  /**
   * `overwrite` (default) replaces the file at `path` (creating it if missing); `create` makes a
   * new file and rejects `ALREADY_EXISTS` if the name is taken (new file, save as copy).
   */
  mode?: "overwrite" | "create"
  /**
   * With `overwrite`: the `modifiedAt` the text was read at. If the remote file now has another
   * modified time, or is gone, nothing is written and the call rejects `CONFLICT`. Omit (or null)
   * to overwrite unconditionally ("Overwrite" in the conflict dialog).
   */
  expectedModifiedAt?: IsoDateTime | null
}

export interface WriteTextFileResult {
  /** The file after the write: its new `modifiedAt` is the next `expectedModifiedAt`. */
  entry: SmbEntry
}

// --- Culling (ADR 0008) ----------------------------------------------------------------------

/** What a shot is made of: RAW + camera JPEG (one shot), RAW only, or the camera JPEG/HEIF only. */
export type CullShotKind = "pair" | "raw" | "jpeg"

/** One file of a shot, as listed. */
export interface CullFile {
  name: string
  /** Path inside the location (as in {@link SmbEntry.path}). */
  path: string
  size: number
  modifiedAt: IsoDateTime | null
}

/**
 * One shot of a culling folder. Files are paired natively by case-insensitive stem
 * (`DSC07668.ARW` + `dsc07668.jpg`); a pair is one shot and decisions apply to both files.
 */
export interface CullShot {
  /** The lowercase stem: unique in the folder and stable across visits (decisions are keyed by it). */
  id: string
  /** The stem as written (`DSC07668`). */
  stem: string
  kind: CullShotKind
  raw?: CullFile
  /** The camera's rendering (JPEG preferred over HEIF). */
  rendered?: CullFile
  /** More files with the same stem (a HEIF next to the JPEG, a second RAW): they go with the shot. */
  others: CullFile[]
}

export type CullOpenOptions = PathOptions

export interface CullOpenResult {
  /** Handle for the other cull calls; valid until `cullClose` or until the app restarts (then `NOT_FOUND`). */
  cullId: string
  /** Every shot in the folder, natural name order (`DSC9` < `DSC10`); the UI re-sorts by capture time. */
  shots: CullShot[]
  /** LibRaw is available (RAW-only shots get a real 100% render; false on 32-bit ABIs). */
  libRaw: boolean
  /** The folder's access as `list` reports it ("Delete rejects" is off on `read`). */
  access?: SmbAccess
}

/**
 * Every call after `cullOpen` passes the handle and the location again: for SMB the session can
 * change between calls (a reconnect over another route), and native reads through the current one.
 */
export interface CullRef {
  cullId: string
  location: LocationRef
}

/** EXIF essentials of a shot (from the RAW's header when there is one, else the JPEG's). */
export interface CullMetadata {
  /** Camera clock, no zone: `2026-08-07T15:04:05.733` (sub-seconds when the camera wrote them). */
  captureTime?: string
  /** `captureTime` read as UTC, in ms: sortable and comparable within one camera clock. */
  captureMillis?: number
  /** `-03:00` when the camera wrote OffsetTimeOriginal. */
  offset?: string
  make?: string
  model?: string
  lens?: string
  /** `1/250`, `0.5`, `2` (seconds). */
  exposure?: string
  fNumber?: number
  iso?: number
  /** Millimetres. */
  focalLength?: number
  /** 35 mm equivalent. */
  focalLength35?: number
  /** Upright full-image size (EXIF orientation applied). */
  width?: number
  height?: number
  /** EXIF orientation 1..8. */
  orientation: number
  /** Rating written by the camera or an editor (0..5, -1 = rejected), when present. Informational. */
  rating?: number
}

export interface CullMetadataOptions extends CullRef {
  /** Up to 500 per call; native reads a few headers at once (two small reads per RAW). */
  shotIds: string[]
}

export interface CullMetadataResult {
  /** One entry per requested id, in order; a file that cannot be read has `error` instead. */
  shots: {
    id: string
    metadata?: CullMetadata
    error?: { code: HoardSmbErrorCode; message: string }
  }[]
}

/** Where a screen image came from (ADR 0006 plans). */
export type CullScreenSource = "rawPreview" | "rendered" | "rawHalf"

export interface CullImageOptions extends CullRef {
  shotId: string
  /** Device pixels of the screen's long side (native keeps at least 90% of it when the source allows). */
  longSide: number
  /**
   * `fast`: the first step of the plan (the RAW's embedded preview, ~0.5 MB read); `final`: the
   * best screen image (camera JPEG, or LibRaw's half-size render for a RAW-only shot whose preview
   * is small). Equal when the plan has one step.
   */
  quality: "fast" | "final"
  /**
   * A shot next to the one on screen (preloaded by the viewer): queued like a prefetch instead of
   * as urgent work, and joined with the prefetch of the same image. Default false.
   */
  background?: boolean
}

export interface CullImageResult {
  /** A cached upright JPEG for `<img src>` (native: a file URL; never bytes over the bridge). */
  src: string
  width: number
  height: number
  source: CullScreenSource
  /** This is the plan's final quality (no better screen image will come). */
  final: boolean
}

export interface CullThumbnailOptions extends CullRef {
  shotId: string
  /** Longest side in px (grid cells: ~320-480). */
  maxSize: number
}

export interface CullPrefetchOptions extends CullRef {
  /** The shots in the order the viewer pages through them (sorted and filtered as shown). */
  shotIds: string[]
  /** Index in `shotIds` of the shot on screen. */
  current: number
  longSide: number
  /** Shots ahead and behind whose final screen image to prepare (defaults 2 and 1). */
  ahead?: number
  behind?: number
  /** Shots ahead whose fast image (the RAW's preview) to prepare; default 6 (cheap, ~0.5 MB each). */
  fastAhead?: number
  /** Also prepare the 100% source of this many next shots (stage the JPEG, render a RAW-only shot); default 0. */
  zoomAhead?: number
}

/** Where 100% pixels come from: the camera JPEG, a full-size embedded JPEG, or a LibRaw render. */
export type CullZoomSource = "rendered" | "rawPreview" | "rawFull"

export interface CullShotOptions extends CullRef {
  shotId: string
}

export interface CullZoomResult {
  /** Upright size of the 100% image (tile coordinates are in this space). */
  width: number
  height: number
  source: CullZoomSource
}

export interface CullTileOptions extends CullShotOptions {
  /** Upright rectangle in the 100% image. */
  x: number
  y: number
  width: number
  height: number
  /** 1 = 100%, 2 = 50%, … (powers of two up to 16); the tile is about width/sample px wide. */
  sample: number
}

export interface CullTileResult {
  src: string
  width: number
  height: number
}

export interface CullSharpnessOptions extends CullRef {
  /** Up to 50 per call. Compare scores within a burst or scene, never across shoots (ADR 0006). */
  shotIds: string[]
}

export interface CullSharpnessScore {
  id: string
  /** Variance of the Laplacian over the whole frame. */
  global?: number
  /** The sharpest tile of a 4×4 grid (a sharp face on a soft background). */
  best?: number
  /** Index of that tile, row-major. */
  bestTile?: number
  error?: { code: HoardSmbErrorCode; message: string }
}

export interface CullSharpnessResult {
  scores: CullSharpnessScore[]
}

export interface CullCloseOptions {
  cullId: string
}

// --- Assisted culling (ADR 0009) -------------------------------------------------------------

export interface CullAnalyzeOptions extends CullRef {
  /** Up to 20, analysed one after another in this order (the app sends small batches, nearest first). */
  shotIds: string[]
  /** The shot on screen: queued right after the viewer's own prefetch instead of after the grid's thumbnails. */
  urgent?: boolean
}

/** A face found in the analysis image (box normalised to 0..1 of the upright image). */
export interface CullFace {
  x: number
  y: number
  width: number
  height: number
  /** ML Kit's probabilities, 0..1; absent when it could not tell (a profile, sunglasses). */
  leftEyeOpen?: number
  rightEyeOpen?: number
  smiling?: number
  /** Big enough to matter: at least 5% of the long side and 40% of the largest face's width. */
  prominent: boolean
  /** Variance of the Laplacian inside the box. */
  sharpness: number
  /** Crété-Roffet blur inside the box, 0 (sharp) .. 1 (flat). */
  blur: number
}

/**
 * What native measured on one shot (ADR 0009 has every formula). Cached by the app per file
 * (size, modified time) and `version`; scores compare within a folder, measured at one scale.
 */
export interface CullAnalysis {
  /** Measurement version: a result with an older version is recomputed. */
  version: number
  /** The image measured: the RAW's embedded preview, or the camera JPEG (JPEG-only shots). */
  source: "rawPreview" | "rendered"
  /** Analysis image size (long side 1536 unless the source is smaller). */
  width: number
  height: number
  /** Time it took natively, ms (decode + measurements + faces). */
  millis: number
  sharpness: {
    /** Variance of the 4-neighbour Laplacian of the luma, whole frame. */
    global: number
    /** The same on a 4 × 4 grid, row-major. */
    tiles: number[]
    bestTile: number
    /** Laplacian variance of the subject: the largest prominent face, else the sharpest tile. */
    subject: number
    subjectKind: "face" | "tile"
    /** Crété-Roffet blur of the subject region, 0..1: what the "blurry" flag compares. */
    subjectBlur: number
  }
  /** Faces were looked for (false: no detector on this device; eyes unknown). */
  facesDetected: boolean
  /** Largest first. */
  faces: CullFace[]
  exposure: {
    /** 64 bins of luma (bin = Y / 4), fractions of the pixels. */
    histogram: number[]
    /** Mean luma, 0..1. */
    mean: number
    /** Share of pixels with luma ≥ 250. */
    highlights: number
    /** Share of pixels with luma ≤ 5. */
    shadows: number
  }
  /** 64-bit difference hash, 16 hex digits. */
  hash: string
  /** Mean colours of a 4 × 4 grid: 48 bytes as 96 hex digits. */
  colors: string
}

export interface CullAnalyzeResult {
  /** One entry per requested id, in order. */
  results: {
    id: string
    analysis?: CullAnalysis
    error?: { code: HoardSmbErrorCode; message: string }
  }[]
  /** Face detection works on this device. */
  faces: boolean
}

// ---------------------------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------------------------

/**
 * Emitted on every state change, on every finished item, and while running at most every 250 ms
 * (when the job moved 1% or 1 MiB, or anything after 1 s). The same shape as {@link NativeTransfer}.
 */
export interface TransferProgressEvent extends Omit<
  NativeTransfer,
  "op" | "startedAt" | "finishedAt"
> {
  /** Rolling average over the last few seconds, bytes per second. */
  bytesPerSecond?: number
}

/**
 * A volume was attached, mounted, ejected or removed (USB OTG drive plugged in, SD card pulled).
 * `volumes` is the full list after the change (same as `listVolumes`).
 */
export interface VolumesChangedEvent {
  volumes: StorageVolumeInfo[]
  /** The volume that changed and how, when native knows (a plain refresh has none). */
  change?: { uuid: string | null; state: VolumeState | "removed" }
}

/** The device's default network changed (Wi-Fi joined/left, VPN up/down). Recheck routes. */
export interface NetworkChangedEvent {
  /** Kind of the new default network, `none` when offline. */
  transport: "wifi" | "cellular" | "ethernet" | "vpn" | "none" | "other"
  /** Whether a VPN (Tailscale) is up, as far as Android reports it. */
  vpn: boolean
}

export type TransferProgressListener = (event: TransferProgressEvent) => void
export type NetworkChangedListener = (event: NetworkChangedEvent) => void
export type VolumesChangedListener = (event: VolumesChangedEvent) => void
export type FilesSharedListener = (event: SharedFiles) => void

// ---------------------------------------------------------------------------------------------
// The plugin
// ---------------------------------------------------------------------------------------------

export interface HoardSmbPlugin {
  // --- Reachability and discovery ---------------------------------------------------------

  /**
   * Native TCP connect to `host:port` (no SMB traffic, no credentials). The route picker calls it
   * for every address of a machine in parallel. Resolves `reachable: false` instead of rejecting.
   */
  probe(options: ProbeOptions): Promise<ProbeResult>

  /** Browse the local network for `_smb._tcp` services (NSD). Used to prefill a new machine. */
  discover(options?: DiscoverOptions): Promise<DiscoverResult>

  // --- Credentials (Android Keystore) ------------------------------------------------------

  /** Store (or replace) the machine's credentials. The password never comes back to JS. */
  setCredentials(options: SetCredentialsOptions): Promise<void>

  /** Whether credentials are stored for the machine. */
  hasCredentials(options: MachineRef): Promise<HasCredentialsResult>

  /** Forget the machine's credentials (also call it when the machine is deleted). Idempotent. */
  deleteCredentials(options: MachineRef): Promise<void>

  // --- Sessions -----------------------------------------------------------------------------

  /**
   * Open an authenticated SMB session on one address. Order natively: TCP → NEGOTIATE → compare
   * the server GUID with `expectedServerGuid` (mismatch rejects `GUID_MISMATCH` with
   * `data.serverGuid`, before any credential is sent) → SESSION_SETUP with the stored credentials.
   * Rejects `UNREACHABLE`, `TIMEOUT`, `AUTH_FAILED`, `GUID_MISMATCH`.
   */
  connect(options: ConnectOptions): Promise<ConnectResult>

  /** Close a session. Idempotent: an unknown `sessionId` resolves. */
  disconnect(options: SessionRef): Promise<void>

  // --- Local storage (ADR 0005) ------------------------------------------------------------

  /** Whether all files access is granted (device and volume locations need it, or a SAF grant). */
  getStorageAccess(): Promise<StorageAccessResult>

  /**
   * Ask for all files access: Android 11+ opens Hoard's "All files access" page in Settings
   * (`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`) and resolves when the user comes back;
   * older versions show the storage permission dialog. Resolves with the new state (never
   * rejects for a refusal: `granted: false`).
   */
  requestStorageAccess(): Promise<StorageAccessResult>

  /** The phone's storage volumes: the primary (internal) one first, then removable ones. */
  listVolumes(): Promise<ListVolumesResult>

  /**
   * SAF fallback for a volume Hoard cannot read by path (no all files access): opens the system
   * folder picker on the volume (`StorageVolume.createOpenDocumentTreeIntent`, Android 10+) and
   * keeps a persisted grant on the folder the user picks. Android 11+ refuses a volume's root in
   * that picker, so the grant is usually a folder (`DCIM`); call it again to add more. Browsing
   * then shows the levels above granted folders as folders leading to them; anything outside every
   * grant rejects `STORAGE_PERMISSION`. Resolves with the volume's info (`access: "saf"`); rejects
   * `CANCELLED` when the user backs out, `ACCESS_DENIED` for a folder on another volume,
   * `UNAVAILABLE` when it is not mounted, `NOT_FOUND` for an unknown uuid.
   */
  grantVolumeAccess(options: VolumeRef): Promise<StorageVolumeInfo>

  /**
   * Open the system storage settings (the volume's page when possible). Apps cannot eject a volume
   * on modern Android; this is where the user does it safely.
   */
  openStorageSettings(options?: OpenStorageSettingsOptions): Promise<void>

  // --- Browsing (any location) ----------------------------------------------------------------

  /**
   * Disk shares visible to the account, each with its `access` when known (one TREE_CONNECT per
   * share, a few at a time, bounded to a few seconds in total; a share that fails or is slow comes
   * back without `access`, never failing the list).
   */
  listShares(options: SessionRef): Promise<ListSharesResult>

  /**
   * Contents of a folder in any location. Rejects `NOT_FOUND` / `ACCESS_DENIED` (`data.restricted`
   * for Android/data and Android/obb) / `STORAGE_PERMISSION` / `UNAVAILABLE`.
   */
  list(options: PathOptions): Promise<ListResult>

  /** One entry's metadata. Rejects `NOT_FOUND`. */
  stat(options: PathOptions): Promise<StatResult>

  /** Create a folder named `name` inside `path`. Resolves with the new entry. */
  createFolder(options: CreateFolderOptions): Promise<StatResult>

  /** Rename the entry at `path` within its folder. Resolves with the renamed entry. */
  rename(options: RenameOptions): Promise<StatResult>

  /** Delete the file or folder at `path`. */
  delete(options: DeleteOptions): Promise<void>

  // --- Destinations (Storage Access Framework) ---------------------------------------------

  /**
   * Open the system folder picker (ACTION_OPEN_DOCUMENT_TREE) and take a persisted read/write
   * permission on the chosen tree. Rejects `CANCELLED` if the user backs out.
   */
  pickDestinationFolder(): Promise<DestinationFolder>

  /** Folders with a persisted permission (stale ones are dropped natively). */
  listDestinationFolders(): Promise<ListDestinationFoldersResult>

  /** Release a persisted permission. Idempotent. */
  releaseDestinationFolder(options: ReleaseDestinationFolderOptions): Promise<void>

  // --- Transfers (foreground service, survive screen off and app kill; ADR 0007) ------------

  /**
   * Queue a copy or move from any locations to a folder in any location, in the native transfer
   * service (WorkManager, `dataSync` foreground service, ongoing notification). Resolves once the
   * job is persisted and queued; the worker then scans folders (`scanning`), checks free space
   * (`NO_SPACE` with `data.needed` / `data.free`), asks about conflicts when `conflict: "ask"`
   * (`conflict`), and copies item by item with progress through `transferProgress`. Each file is
   * written under a temporary name, its size verified, its modified time kept where the
   * destination allows it, then published under its name; a move deletes the source only after
   * that. A move inside one location (same share, same volume) is a rename instead, never a copy.
   * Rejects `UNKNOWN` for invalid input (no sources, a folder into itself, a running id).
   */
  startTransfer(options: StartTransferOptions): Promise<StartTransferResult>

  /** Answer the conflicts of a job in state `conflict`; it continues. Idempotent. */
  resolveTransferConflicts(options: ResolveTransferConflictsOptions): Promise<void>

  /**
   * Cancel a queued/running job: unfinished items end `cancelled`, finished ones stay. The file in
   * progress keeps its hidden partial file (`.<name>.hoardpart`) so Retry continues it. Idempotent.
   */
  cancelTransfer(options: TransferRef): Promise<void>

  /**
   * Every job native reports (also after the app was killed and restarted), so the UI can
   * reconcile the `transfers` table with reality.
   */
  getTransfers(): Promise<GetTransfersResult>

  /**
   * The items of a job (per-file states and errors), while native still has it: running jobs and
   * the last finished ones (kept for Retry even after `acknowledgeTransfers`). `NOT_FOUND` when gone.
   */
  getTransferItems(options: GetTransferItemsOptions): Promise<GetTransferItemsResult>

  /** Stop reporting finished jobs in `getTransfers` once the DB has them (items stay for Retry). */
  acknowledgeTransfers(options: { transferIds: string[] }): Promise<void>

  // --- Share in and out -------------------------------------------------------------------------

  /**
   * Hand files to another app with the system share sheet (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`
   * for several). Local files are shared in place (FileProvider or SAF URI); SMB files up to 16 MiB
   * are cached first (the other app may read them later, in the background), larger ones stream
   * through the proxy file descriptor provider like `streamFile`. Rejects `NO_APP`, `NOT_FOUND`.
   */
  shareFiles(options: ShareFilesOptions): Promise<void>

  /** What another app shared to Hoard and the UI has not handled yet (also the launch intent). */
  getSharedFiles(): Promise<GetSharedFilesResult>

  /** Forget a share once handled (saved or dismissed). Running transfers keep reading it. */
  clearSharedFiles(options: { shareId: string }): Promise<void>

  // --- Viewing ------------------------------------------------------------------------------

  /**
   * Open the file with ACTION_VIEW (chooser if several apps match, always with `chooser: true`).
   * SMB: downloads to the app cache first (for small files; large ones and media go through
   * `streamFile`). Local locations: the file itself is shared (FileProvider or its SAF document),
   * nothing is copied. Rejects `NO_APP` when nothing can open it, `CANCELLED` if superseded.
   */
  openFile(options: OpenFileOptions): Promise<void>

  /**
   * Hand a remote file to another app **without downloading it first**: the app reads (and seeks)
   * it through a stream URI served from SMB on demand, with read-ahead. Native connects, checks the
   * file, registers a token and fires ACTION_VIEW (or the chooser); a foreground service with a
   * "Streaming" notification keeps Hoard serving while the other app is in front. Tokens end after
   * 15 min unused (never while the app has the file open) or with `stopStreams`. Rejects `NO_APP`,
   * `NOT_FOUND`, `UNREACHABLE`, `AUTH_FAILED`, `GUID_MISMATCH`, `CANCELLED` if superseded.
   */
  streamFile(options: StreamFileOptions): Promise<StreamFileResult>

  /**
   * Open an `.html`/`.htm` file with its folder served by Hoard's local web server (HTTP/1.1 on
   * 127.0.0.1 only, random port, per-page random token in every URL; subfolders allowed, nothing
   * above the page's folder), so relative CSS, JS and images load. Shown in the native web viewer
   * or the browser (`target`). The server stops when its last page expires (30 min unused, never
   * while the viewer shows it). Rejects like `streamFile`.
   */
  openWebPage(options: OpenWebPageOptions): Promise<OpenWebPageResult>

  /** End every stream and served page now (open players get read errors). Idempotent. */
  stopStreams(): Promise<void>

  /**
   * Thumbnail for an image or a video file (natively a frame read with MediaMetadataRetriever, the
   * video is never downloaded whole). Rejects `UNKNOWN` for other types.
   */
  thumbnail(options: ThumbnailOptions): Promise<ThumbnailResult>

  // --- Text files ---------------------------------------------------------------------------

  /**
   * Read a small text file into a string for the built-in viewer and editor: the one deliberate
   * exception to "bytes never cross the bridge" (ADR 0001), bounded by `maxBytes`. The encoding
   * is detected natively (UTF-8 / UTF-16 byte order marks, valid UTF-8, else windows-1252).
   * Rejects `TOO_LARGE` (with `data.size`), `NOT_TEXT`, `NOT_FOUND`, `ACCESS_DENIED`.
   */
  readTextFile(options: ReadTextFileOptions): Promise<ReadTextFileResult>

  /**
   * Save text to `path`. Natively it is written to a hidden temporary file in the same folder and
   * renamed over the target only when complete, so a dropped connection never leaves half a file;
   * shares that refuse new files fall back to overwriting in place. Rejects `CONFLICT` (see
   * `expectedModifiedAt`), `ALREADY_EXISTS` (`mode: "create"`), `ACCESS_DENIED`, `NO_SPACE`.
   */
  writeTextFile(options: WriteTextFileOptions): Promise<WriteTextFileResult>

  // --- Culling (ADR 0008) --------------------------------------------------------------------
  // Images come back as cached JPEG files (URLs for `<img>`); the bytes never cross the bridge.

  /**
   * Open a folder of any location for culling: lists it and pairs RAW and JPEG files into shots.
   * Rejects like `list`.
   */
  cullOpen(options: CullOpenOptions): Promise<CullOpenResult>

  /** EXIF essentials of some shots (capture time with sub-seconds, exposure, lens…). */
  cullMetadata(options: CullMetadataOptions): Promise<CullMetadataResult>

  /**
   * A screen-sized upright image of a shot. Ask `fast` first for an instant picture (the embedded
   * preview), then `final`. Rejects `CANCELLED` when the view closed, `NOT_FOUND` for an unknown
   * `cullId` or shot (or an SMB session that dropped: reconnect and retry).
   */
  cullImage(options: CullImageOptions): Promise<CullImageResult>

  /** Grid thumbnail of a shot (the RAW's preview, or the JPEG's EXIF thumbnail on a remote JPEG-only shot). */
  cullThumbnail(options: CullThumbnailOptions): Promise<ThumbnailResult>

  /**
   * Keep the screen images around `current` prepared (ahead first), cancel what left the window.
   * Resolves at once; the work runs in the background.
   */
  cullPrefetch(options: CullPrefetchOptions): Promise<void>

  /**
   * Prepare the 100% image of a shot and return its size: stages the camera JPEG, or renders a
   * RAW-only shot with LibRaw (about a second the first time, then cached on disk).
   */
  cullZoom(options: CullShotOptions): Promise<CullZoomResult>

  /** One region of the 100% image at a sample size, as a cached JPEG (calls `cullZoom`'s work if needed). */
  cullTile(options: CullTileOptions): Promise<CullTileResult>

  /** Focus scores of some shots (computed on their preview), for choosing within a burst. */
  cullSharpness(options: CullSharpnessOptions): Promise<CullSharpnessResult>

  /**
   * Assisted culling (ADR 0009): sharpness, faces and eyes, exposure and a similarity signature of
   * a few shots, measured natively on the RAW's preview or the camera JPEG. Rejects `CANCELLED`
   * when the folder closes mid-batch; a shot that cannot be read has `error` instead.
   */
  cullAnalyze(options: CullAnalyzeOptions): Promise<CullAnalyzeResult>

  /** Forget a culling handle and stop its background work. Idempotent. */
  cullClose(options: CullCloseOptions): Promise<void>

  // --- Events -------------------------------------------------------------------------------

  addListener(
    eventName: "transferProgress",
    listener: TransferProgressListener
  ): Promise<PluginListenerHandle>

  addListener(
    eventName: "networkChanged",
    listener: NetworkChangedListener
  ): Promise<PluginListenerHandle>

  addListener(
    eventName: "volumesChanged",
    listener: VolumesChangedListener
  ): Promise<PluginListenerHandle>

  /** Another app shared files to Hoard (Hoard was open or brought to the front). */
  addListener(
    eventName: "filesShared",
    listener: FilesSharedListener
  ): Promise<PluginListenerHandle>

  /** Remove every listener this plugin registered. */
  removeAllListeners(): Promise<void>
}
