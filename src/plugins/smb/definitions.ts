/**
 * The HoardSmb plugin contract: everything the app asks the native side to do.
 *
 * Three implementations must stay in lockstep (see CLAUDE.md, "plugin contract rule"):
 *  - this file (the types the UI is written against),
 *  - `web.ts` (the in-memory mock used by `bun run dev` and the tests),
 *  - `android/app/src/main/java/dev/hfps/hoard/smb/HoardSmbPlugin.kt` (smbj + SAF).
 *
 * Ground rules (ADR 0001 / 0002 / 0003):
 *  - File bytes never cross the JS bridge. Transfers stream SMB → SAF natively; thumbnails and opened
 *    files come back as cache-file URLs, never as base64; streams and served pages are URIs/URLs
 *    another app (or a native WebView) reads natively.
 *  - Passwords are write-only from JS: `setCredentials` stores them in the Android Keystore and no
 *    method ever returns one. `connect` and `startDownload` read them natively by `machineId`.
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
  /** The destination (SAF tree or cache) ran out of space. */
  "NO_SPACE",
  /** Cancelled by `cancelTransfer`, by the user in a system picker, or by a newer call. */
  "CANCELLED",
  /** The persisted SAF permission for a destination folder was revoked or the folder is gone. */
  "PERMISSION_REVOKED",
  /** No app on the device can open this file type (`openFile`). */
  "NO_APP",
  /** Anything else; `message` has the details. Also returned by the not-yet-implemented native stub. */
  "UNKNOWN",
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
}

/** A disk share on a server (IPC$/printer shares are filtered out natively). */
export interface SmbShare {
  /** Share name used in every later call (`media`, `homes`, `Public`). */
  name: string
  /** Server-provided remark, if any. */
  comment?: string
  /** Hidden administrative share (name ends with `$`). */
  isHidden: boolean
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

export interface PathOptions extends SessionRef {
  share: string
  /** Path inside the share (see {@link SmbEntry.path}); `""` is the share root. */
  path: string
}

export interface ListResult {
  /** Folder contents, unsorted (the UI sorts). `.` and `..` are never included. */
  entries: SmbEntry[]
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

/** A folder on the phone the user granted with the Storage Access Framework (persisted). */
export interface DestinationFolder {
  /** The `content://…/tree/…` URI; pass it as `destTreeUri`. */
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

/** What to do when `destName` already exists in the destination folder. */
export type ConflictPolicy = "rename" | "overwrite" | "skip"

/** Lifecycle of a native transfer job. */
export type TransferState =
  /** Accepted, waiting for a worker slot or for the network. */
  | "queued"
  /** Bytes are flowing. */
  | "running"
  /** Lost the connection; native is retrying (same address, then the next in the list). */
  | "retrying"
  | "completed"
  | "failed"
  | "cancelled"
  /** `conflict: "skip"` and the destination already existed: nothing was written. */
  | "skipped"

export const TERMINAL_TRANSFER_STATES: readonly TransferState[] = [
  "completed",
  "failed",
  "cancelled",
  "skipped",
]

export interface StartDownloadOptions {
  /** `transfers.id` from the app database: the UI creates the row first, then starts the job. */
  transferId: string
  machineId: string
  /**
   * Addresses to try, in priority order (the currently reachable one first). Native retries a
   * dropped connection on the same address, then fails over to the next, resuming at `bytesDone`.
   */
  addresses: SmbAddress[]
  /** Pinned GUID; every address is checked against it before credentials are sent. */
  expectedServerGuid: string
  share: string
  /** File path inside the share. Folders are a later addition (`isDirectory` rejects `UNKNOWN` for now). */
  remotePath: string
  /** SAF tree from `pickDestinationFolder` / `listDestinationFolders`. */
  destTreeUri: string
  /** File name to create in the tree; default the remote file name. */
  destName?: string
  conflict: ConflictPolicy
  /**
   * Resume a job the app knows was interrupted (from `getTransfers` or the DB): native appends from
   * this offset if the partial destination file still has exactly this many bytes, else restarts at 0.
   */
  resumeFromBytes?: number
}

export interface StartDownloadResult {
  transferId: string
  /** Final file name in the destination (differs from `destName` after `conflict: "rename"`). */
  destName: string
  /** The created document's URI. */
  destUri: string
}

export interface TransferRef {
  transferId: string
}

/** A native job as the background service knows it. */
export interface NativeTransfer {
  transferId: string
  machineId: string
  share: string
  remotePath: string
  destTreeUri: string
  destName: string
  destUri?: string
  totalBytes: number
  bytesDone: number
  state: TransferState
  /** Set when `state` is `failed` (or while `retrying`, the last error). */
  error?: { code: HoardSmbErrorCode; message: string }
  /** Address currently used (or last used). */
  activeHost?: string
  startedAt: IsoDateTime
  finishedAt?: IsoDateTime
}

export interface GetTransfersResult {
  /** Jobs native still knows about: running, queued, and finished ones not yet acknowledged. */
  transfers: NativeTransfer[]
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
 * Where a stream or a served page reads from. Like `startDownload`, native opens its **own**
 * connection (not a `sessionId`): it outlives the UI's sessions and the WebView, and reconnects /
 * fails over across `addresses` while another app plays, re-checking the GUID before credentials.
 */
export interface StreamSourceOptions {
  machineId: string
  /** Priority order, the route in use first. */
  addresses: SmbAddress[]
  /** Pinned GUID, checked on every (re)connect. */
  expectedServerGuid: string
  share: string
  /** File path inside the share. */
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

// ---------------------------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------------------------

/** Emitted on every state change and at most every 250 ms (and every 1% or 1 MiB) while running. */
export interface TransferProgressEvent {
  transferId: string
  bytesDone: number
  totalBytes: number
  state: TransferState
  error?: { code: HoardSmbErrorCode; message: string }
  /** Host the bytes are coming from right now (changes on failover). */
  activeHost?: string
  /** Rolling average over the last few seconds, bytes per second. */
  bytesPerSecond?: number
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

  // --- Browsing -----------------------------------------------------------------------------

  /** Disk shares visible to the account. */
  listShares(options: SessionRef): Promise<ListSharesResult>

  /** Contents of a folder. Rejects `NOT_FOUND` / `ACCESS_DENIED`. */
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

  // --- Transfers (foreground service, survive screen off and app kill) ---------------------

  /**
   * Queue a download SMB → SAF in the native transfer service (with an ongoing notification).
   * Resolves once the job is accepted and the destination file is created (so `destName` is
   * final); progress then comes through `transferProgress`. Rejects synchronously-known failures
   * (`PERMISSION_REVOKED`, `NO_SPACE` when the size is known up front, `ALREADY_EXISTS` is never
   * thrown: `conflict` decides).
   */
  startDownload(options: StartDownloadOptions): Promise<StartDownloadResult>

  /** Cancel a queued/running job; it ends in `cancelled` and its partial file is deleted. Idempotent. */
  cancelTransfer(options: TransferRef): Promise<void>

  /**
   * Every job native knows (also after the app was killed and restarted), so the UI can reconcile
   * the `transfers` table with reality.
   */
  getTransfers(): Promise<GetTransfersResult>

  /** Forget finished jobs (`completed`/`failed`/`cancelled`/`skipped`) once the DB has them. */
  acknowledgeTransfers(options: { transferIds: string[] }): Promise<void>

  // --- Viewing ------------------------------------------------------------------------------

  /**
   * Download the file to the app cache and open it with ACTION_VIEW (chooser if several apps
   * match, always with `chooser: true`). Rejects `NO_APP` when nothing can open it, `CANCELLED` if
   * superseded. For small files; large ones and media go through `streamFile`.
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

  // --- Events -------------------------------------------------------------------------------

  addListener(
    eventName: "transferProgress",
    listener: TransferProgressListener
  ): Promise<PluginListenerHandle>

  addListener(
    eventName: "networkChanged",
    listener: NetworkChangedListener
  ): Promise<PluginListenerHandle>

  /** Remove every listener this plugin registered. */
  removeAllListeners(): Promise<void>
}
