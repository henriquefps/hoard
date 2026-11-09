import { WebPlugin } from "@capacitor/core"
import type {
  ConnectOptions,
  ConnectResult,
  CreateFolderOptions,
  DeleteOptions,
  DestinationFolder,
  DiscoverOptions,
  DiscoverResult,
  GetTransfersResult,
  HasCredentialsResult,
  HoardSmbErrorCode,
  HoardSmbPlugin,
  ListDestinationFoldersResult,
  ListResult,
  ListSharesResult,
  ListVolumesResult,
  LocationRef,
  OpenStorageSettingsOptions,
  StorageAccessResult,
  StorageVolumeInfo,
  VolumeRef,
  VolumeState,
  MachineRef,
  OpenFileOptions,
  OpenWebPageOptions,
  OpenWebPageResult,
  PathOptions,
  ProbeOptions,
  ProbeResult,
  ReadTextFileOptions,
  ReadTextFileResult,
  ReleaseDestinationFolderOptions,
  RenameOptions,
  SessionRef,
  SetCredentialsOptions,
  SmbEntry,
  StartTransferOptions,
  StartTransferResult,
  GetTransferItemsOptions,
  GetTransferItemsResult,
  ResolveTransferConflictsOptions,
  ShareFilesOptions,
  GetSharedFilesResult,
  SharedFiles,
  TransferSourceLocation,
  StatResult,
  StreamFileOptions,
  StreamFileResult,
  StreamSourceOptions,
  ThumbnailOptions,
  ThumbnailResult,
  TransferRef,
  WriteTextFileOptions,
  WriteTextFileResult,
} from "./definitions"
import {
  smbErrorCode as smbErrorCodeOf,
  TEXT_FILE_HARD_LIMIT,
  TEXT_FILE_MAX_BYTES,
} from "./definitions"
import { detectAddressKind } from "@/lib/addressKind"
import { canEncode, decodeTextPrefix, encodeText } from "@/lib/text/codec"
import { isTextFile } from "@/lib/text/textFiles"
import { GENERATED_TEXT } from "./mockText"
import { MOCK_VOLUMES } from "./mockLocal"
import { type MockRoot, MockTransferEngine } from "./mockTransfers"
import {
  DEMO_PASSWORDS,
  MOCK_ROUTES,
  MOCK_SERVERS,
  type MockFile,
  type MockRoute,
  type MockServer,
  type MockServerKey,
  mockSpeedFor,
  UNKNOWN_ROUTE,
} from "./mockData"

/** A rejection shaped like Capacitor's `call.reject(message, code, data)`. */
export class HoardSmbWebError extends Error {
  readonly code: HoardSmbErrorCode
  readonly data?: Record<string, unknown>
  constructor(code: HoardSmbErrorCode, message: string, data?: Record<string, unknown>) {
    super(message)
    this.name = "HoardSmbError"
    this.code = code
    this.data = data
  }
}

const fail = (code: HoardSmbErrorCode, message: string, data?: Record<string, unknown>) =>
  new HoardSmbWebError(code, message, data)

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

/**
 * Mock-only network simulation (dev tools in Settings, never part of the contract): `home` is the
 * normal table, `away` makes every local address time out (left the house, Tailscale still up),
 * `offline` makes everything unreachable.
 */
export type MockNetwork = "home" | "away" | "offline"

let network: MockNetwork = "home"

const routeFor = (host: string): MockRoute => {
  const clean = host.trim().toLowerCase()
  const route = MOCK_ROUTES[clean] ?? UNKNOWN_ROUTE
  if (network === "offline") return { serverKey: null, latencyMs: 5, failure: "refused" }
  if (network === "away" && detectAddressKind(clean) === "local") {
    return { serverKey: null, latencyMs: 0, failure: "timeout" }
  }
  return route
}

const splitPath = (path: string) => path.split("/").filter(Boolean)

const joinPath = (folder: string, name: string) => (folder ? `${folder}/${name}` : name)

const extension = (name: string) => name.slice(name.lastIndexOf(".") + 1).toLowerCase()

/** What `thumbnail` accepts, like the native Thumbnailer: images and videos. */
const THUMBNAIL_EXTENSIONS = new Set([
  ...["jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"],
  ...["mp4", "m4v", "mov", "3gp", "mkv", "webm"],
])

/** MIME types the mock reports for streams (the native side has the full table). */
const MOCK_MIME_TYPES: Record<string, string> = {
  mp4: "video/mp4",
  m4v: "video/x-m4v",
  mkv: "video/x-matroska",
  webm: "video/webm",
  mov: "video/quicktime",
  mp3: "audio/mpeg",
  flac: "audio/flac",
  m4a: "audio/mp4",
  ogg: "audio/ogg",
  wav: "audio/wav",
  pdf: "application/pdf",
  zip: "application/zip",
  html: "text/html",
  htm: "text/html",
}

/** Types no app opens in the mock (`NO_APP`), like a phone without a matching viewer. */
const MOCK_NO_APP_EXTENSIONS = ["bin", "zst", "ds_store"]

const DESTINATIONS_KEY = "hoard.mock.destinations"
const STORAGE_ACCESS_KEY = "hoard.mock.storageAccess"

/** Android/data and Android/obb (Android 11+): listed, never openable (like native `LocalPaths`). */
const isRestrictedPath = (path: string) => {
  const [first, second] = path.split("/").filter(Boolean)
  return first?.toLowerCase() === "android" && ["data", "obb"].includes(second?.toLowerCase() ?? "")
}

interface MockVolume {
  info: (typeof MOCK_VOLUMES)[number]
  attached: boolean
  state: VolumeState
  root: MockFile[]
}

function readStorageGrant(): boolean {
  try {
    return localStorage.getItem(STORAGE_ACCESS_KEY) === "granted"
  } catch {
    return false
  }
}

interface MockSession {
  serverKey: MockServerKey
  latencyMs: number
}

function toEntry(node: MockFile, path: string): SmbEntry {
  return {
    name: node.name,
    path,
    isDirectory: Boolean(node.children),
    size: node.children ? 0 : (node.size ?? 0),
    modifiedAt: node.modifiedAt ?? null,
    isHidden: Boolean(node.hidden) || node.name.startsWith("."),
  }
}

/** Now, but always after `previous` (a save must change the modified time, even within 1 ms). */
function laterThan(previous: string | undefined): string {
  const now = Date.now()
  const before = previous ? Date.parse(previous) : 0
  return new Date(Math.max(now, before + 1)).toISOString()
}

/** Deterministic hue from a string, for fake thumbnails. */
function hueOf(text: string): number {
  let hash = 0
  for (const char of text) hash = (hash * 31 + char.charCodeAt(0)) | 0
  return Math.abs(hash) % 360
}

/**
 * In-memory implementation of {@link HoardSmbPlugin} for the browser (and unit tests). It simulates
 * the network (per-host latency, unreachable and timing-out hosts, a foreign server with another
 * GUID), credentials, browsing, SAF destinations, transfers with progress events and failover.
 * Mutations (createFolder, rename, delete) change the in-memory trees until the page reloads.
 */
export class HoardSmbWeb extends WebPlugin implements HoardSmbPlugin {
  /** Simulated delay multiplier; tests set 0 to run instantly. */
  latencyScale = 1

  private readonly credentials = new Map<
    string,
    { username: string; password: string; domain?: string }
  >(Object.entries(DEMO_PASSWORDS).map(([id, password]) => [id, { username: "demo", password }]))
  private readonly sessions = new Map<string, MockSession>()
  /** SAF folders picked with `pickDestinationFolder` (`tree` locations), by URI. */
  private readonly treeRoots = new Map<string, MockFile>()
  /** Files "shared from another app" (`simulateIncomingShare`). */
  private readonly incoming = new Map<string, SharedFiles & { cleared: boolean }>()
  private readonly transfers = new MockTransferEngine({
    rootOf: (location) => this.transferRoot(location),
    emit: (event) => this.notifyListeners("transferProgress", event),
    fail,
    tickMs: () => (this.latencyScale === 0 ? 0 : 250),
  })
  private readonly trees = structuredClone(MOCK_SERVERS) as Record<MockServerKey, MockServer>
  private nextId = 1
  /** All files access (mock: remembered in localStorage, toggled from Settings → Developer). */
  private storageGranted = readStorageGrant()
  /** Volumes with a SAF grant (`grantVolumeAccess`). */
  private readonly safGrants = new Set<string>()
  private readonly volumes: MockVolume[] = MOCK_VOLUMES.map((info) => ({
    info,
    attached: info.attachedByDefault,
    state: info.readOnly ? "mountedReadOnly" : "mounted",
    root: info.build(),
  }))

  /** Dev tool: switch the simulated network and emit `networkChanged` like Android would. */
  simulateNetwork(next: MockNetwork) {
    network = next
    this.notifyListeners("networkChanged", {
      transport: next === "offline" ? "none" : next === "away" ? "cellular" : "wifi",
      vpn: next === "away",
    })
  }

  get simulatedNetwork(): MockNetwork {
    return network
  }

  private wait(ms: number) {
    return this.latencyScale === 0 ? Promise.resolve() : sleep(ms * this.latencyScale)
  }

  // --- Local storage (mock) -------------------------------------------------------------------

  private volumeInfo(volume: MockVolume): StorageVolumeInfo {
    const { attachedByDefault: _a, readOnly: _r, build: _b, ...info } = volume.info
    const readable = volume.state === "mounted" || volume.state === "mountedReadOnly"
    const access: StorageVolumeInfo["access"] = !readable
      ? "none"
      : this.storageGranted
        ? "path"
        : info.uuid && this.safGrants.has(info.uuid)
          ? "saf"
          : "none"
    return {
      ...info,
      state: volume.state,
      access,
      ...(readable ? {} : { totalBytes: undefined, freeBytes: undefined }),
    }
  }

  private volumeList(): StorageVolumeInfo[] {
    return this.volumes.filter((v) => v.attached).map((v) => this.volumeInfo(v))
  }

  private emitVolumes(change?: { uuid: string | null; state: VolumeState | "removed" }) {
    this.notifyListeners("volumesChanged", {
      volumes: this.volumeList(),
      ...(change ? { change } : {}),
    })
  }

  /** Dev tool: plug in or pull out a mock volume (emits `volumesChanged` like Android would). */
  simulateVolume(uuid: string, attached: boolean) {
    const volume = this.volumes.find((v) => v.info.uuid === uuid)
    if (!volume || volume.attached === attached) return
    volume.attached = attached
    if (attached) {
      volume.state = "checking"
      this.emitVolumes({ uuid, state: "checking" })
      setTimeout(
        () => {
          volume.state = volume.info.readOnly ? "mountedReadOnly" : "mounted"
          this.emitVolumes({ uuid, state: volume.state })
        },
        this.latencyScale === 0 ? 0 : 700
      )
    } else {
      volume.state = "unmounted"
      this.emitVolumes({ uuid, state: "removed" })
    }
  }

  /** Dev tool: turn all files access on or off (as if changed in Android's Settings). */
  simulateStorageAccess(granted: boolean) {
    this.storageGranted = granted
    try {
      localStorage.setItem(STORAGE_ACCESS_KEY, granted ? "granted" : "denied")
    } catch {
      // Not persisted; fine for a mock.
    }
    this.emitVolumes()
  }

  /** Mock-only, for dev tools: which removable volumes are plugged in. */
  get simulatedVolumes(): { uuid: string; label: string; attached: boolean }[] {
    return this.volumes
      .filter((v) => v.info.uuid)
      .map((v) => ({ uuid: v.info.uuid!, label: v.info.label, attached: v.attached }))
  }

  async getStorageAccess(): Promise<StorageAccessResult> {
    return { granted: this.storageGranted, mechanism: "allFiles" }
  }

  async requestStorageAccess(): Promise<StorageAccessResult> {
    // The user goes to Settings, flips the switch and comes back.
    await this.wait(600)
    this.simulateStorageAccess(true)
    return this.getStorageAccess()
  }

  async listVolumes(): Promise<ListVolumesResult> {
    await this.wait(10)
    return { volumes: this.volumeList() }
  }

  async grantVolumeAccess({ uuid }: VolumeRef): Promise<StorageVolumeInfo> {
    const volume = this.volumes.find((v) => v.info.uuid === uuid && v.attached)
    if (!volume) throw fail("NOT_FOUND", `No volume ${uuid}`)
    if (volume.state !== "mounted" && volume.state !== "mountedReadOnly") {
      throw fail("UNAVAILABLE", `${volume.info.label} is not mounted`, { state: volume.state })
    }
    await this.wait(500)
    this.safGrants.add(uuid)
    this.emitVolumes()
    return this.volumeInfo(volume)
  }

  async openStorageSettings(_options?: OpenStorageSettingsOptions): Promise<void> {
    console.info("[HoardSmbWeb] would open Android's storage settings")
  }

  /** The tree of a local location, or why it cannot be read now. */
  private localRoot(location: Exclude<LocationRef, { kind: "smb" }>) {
    const volume =
      location.kind === "device"
        ? this.volumes.find((v) => v.info.primary)
        : this.volumes.find((v) => v.info.uuid === location.uuid)
    if (!volume?.attached)
      throw fail("UNAVAILABLE", "The volume is not connected", { state: "removed" })
    if (volume.state !== "mounted" && volume.state !== "mountedReadOnly") {
      throw fail("UNAVAILABLE", `${volume.info.label} is not mounted`, { state: volume.state })
    }
    const info = this.volumeInfo(volume)
    if (info.access === "none") {
      throw fail("STORAGE_PERMISSION", `Hoard has no access to ${volume.info.label}`)
    }
    return { volume, readOnly: volume.state === "mountedReadOnly" }
  }

  // --- Reachability and discovery -----------------------------------------------------------

  async probe({ host, port = 445, timeoutMs = 1500 }: ProbeOptions): Promise<ProbeResult> {
    if (!host.trim()) throw fail("UNKNOWN", "host is required")
    const route = routeFor(host)
    if (port !== 445) {
      await this.wait(Math.min(route.latencyMs || 30, timeoutMs))
      return { reachable: false, reason: "UNREACHABLE" }
    }
    if (route.failure === "timeout") {
      await this.wait(timeoutMs)
      return { reachable: false, reason: "TIMEOUT" }
    }
    if (route.failure === "refused") {
      await this.wait(Math.min(route.latencyMs, timeoutMs))
      return { reachable: false, reason: "UNREACHABLE" }
    }
    if (route.latencyMs > timeoutMs) {
      await this.wait(timeoutMs)
      return { reachable: false, reason: "TIMEOUT" }
    }
    const jitter = Math.round(route.latencyMs * 0.15 * Math.random())
    await this.wait(route.latencyMs + jitter)
    return { reachable: true, latencyMs: route.latencyMs + jitter }
  }

  async discover({ timeoutMs = 3000 }: DiscoverOptions = {}): Promise<DiscoverResult> {
    await this.wait(Math.min(timeoutMs, 1200))
    return {
      services: [
        { name: "cachyos-fps", host: "192.168.1.155", port: 445 },
        { name: "NAS-SALA", host: "192.168.1.20", port: 445 },
        { name: "SOMEONE-ELSES-NAS", host: "192.168.0.10", port: 445 },
      ],
    }
  }

  // --- Credentials --------------------------------------------------------------------------

  async setCredentials({
    machineId,
    username,
    password,
    domain,
  }: SetCredentialsOptions): Promise<void> {
    if (!machineId || !username) throw fail("UNKNOWN", "machineId and username are required")
    await this.wait(20)
    this.credentials.set(machineId, { username, password, domain })
  }

  async hasCredentials({ machineId }: MachineRef): Promise<HasCredentialsResult> {
    return { value: this.credentials.has(machineId) }
  }

  async deleteCredentials({ machineId }: MachineRef): Promise<void> {
    this.credentials.delete(machineId)
  }

  // --- Sessions -----------------------------------------------------------------------------

  /** TCP + negotiate + GUID check + auth, shared by connect and the transfer failover. */
  private async open(
    machineId: string,
    host: string,
    port: number,
    expectedServerGuid: string | null | undefined,
    timeoutMs: number
  ): Promise<{ serverKey: MockServerKey; route: MockRoute }> {
    const probe = await this.probe({ host, port, timeoutMs })
    if (!probe.reachable) {
      throw fail(probe.reason ?? "UNREACHABLE", `${host}:${port} did not answer`)
    }
    const route = routeFor(host)
    const serverKey = route.serverKey
    if (!serverKey) throw fail("UNREACHABLE", `${host}:${port} is not an SMB server`)
    const server = this.trees[serverKey]
    await this.wait(route.latencyMs) // NEGOTIATE
    if (expectedServerGuid && expectedServerGuid.toLowerCase() !== server.guid) {
      throw fail("GUID_MISMATCH", `${host} is a different server (${server.serverName})`, {
        serverGuid: server.guid,
      })
    }
    const credentials = this.credentials.get(machineId)
    await this.wait(route.latencyMs * 2) // SESSION_SETUP
    if (!credentials) throw fail("AUTH_FAILED", "No credentials stored for this machine")
    if (!server.passwords.includes(credentials.password)) {
      throw fail("AUTH_FAILED", "The server rejected the username or password")
    }
    return { serverKey, route }
  }

  async connect({
    machineId,
    host,
    port = 445,
    expectedServerGuid,
    timeoutMs = 5000,
  }: ConnectOptions): Promise<ConnectResult> {
    const { serverKey, route } = await this.open(
      machineId,
      host,
      port,
      expectedServerGuid,
      timeoutMs
    )
    const server = this.trees[serverKey]
    const sessionId = `mock-session-${this.nextId++}`
    this.sessions.set(sessionId, { serverKey, latencyMs: route.latencyMs })
    return {
      sessionId,
      serverGuid: server.guid,
      serverName: server.serverName,
      dialect: server.dialect,
    }
  }

  async disconnect({ sessionId }: SessionRef): Promise<void> {
    this.sessions.delete(sessionId)
  }

  // --- Browsing -----------------------------------------------------------------------------

  private session(sessionId: string): MockSession {
    const session = this.sessions.get(sessionId)
    if (!session) throw fail("NOT_FOUND", "Session expired or unknown")
    return session
  }

  private shareOf(session: MockSession, shareName: string) {
    const share = this.trees[session.serverKey].shares.find(
      (s) => s.name.toLowerCase() === shareName.toLowerCase()
    )
    if (!share) throw fail("NOT_FOUND", `Share ${shareName} not found`)
    if (share.denied) throw fail("ACCESS_DENIED", `No access to ${shareName}`)
    return share
  }

  /**
   * Resolve a path to its node (the share root is a synthetic folder). `readOnly` is true when the
   * share or any folder on the way (or the node itself) is read-only for the account.
   */
  private resolveSmb(sessionId: string, shareName: string, path: string) {
    const session = this.session(sessionId)
    const share = this.shareOf(session, shareName)
    return {
      ...HoardSmbWeb.walk(
        { name: share.name, children: share.root },
        path,
        Boolean(share.readOnly)
      ),
      latencyMs: session.latencyMs,
      local: false,
    }
  }

  /** Any location: an SMB share through its session, or a local tree (5 ms "latency"). */
  private resolve(location: LocationRef, path: string) {
    if (location.kind === "smb") return this.resolveSmb(location.sessionId, location.share, path)
    const { volume, readOnly } = this.localRoot(location)
    if (isRestrictedPath(path)) {
      throw fail("ACCESS_DENIED", "Android keeps this folder to its apps", { restricted: true })
    }
    return {
      ...HoardSmbWeb.walk({ name: volume.info.label, children: volume.root }, path, readOnly),
      latencyMs: 5,
      local: true,
    }
  }

  private static walk(root: MockFile, path: string, rootReadOnly: boolean) {
    let node = root
    let parent: MockFile | null = null
    let readOnly = rootReadOnly
    let parentReadOnly = readOnly
    for (const segment of splitPath(path)) {
      if (node.denied) throw fail("ACCESS_DENIED", `No access to ${node.name}`)
      const next = node.children?.find((child) => child.name === segment)
      if (!next) throw fail("NOT_FOUND", `${path} not found`)
      parent = node
      parentReadOnly = readOnly
      node = next
      readOnly ||= Boolean(next.readOnly)
    }
    return { node, parent, path: splitPath(path).join("/"), readOnly, parentReadOnly }
  }

  private static readOnlyError(name: string) {
    return fail("ACCESS_DENIED", `${name} is read-only for this account`)
  }

  async listShares({ sessionId }: SessionRef): Promise<ListSharesResult> {
    const session = this.session(sessionId)
    await this.wait(session.latencyMs * 2)
    return {
      shares: this.trees[session.serverKey].shares
        .filter((share) => share.name !== "IPC$" && !share.unlisted)
        .map((share) => ({
          name: share.name,
          comment: share.comment,
          isHidden: share.name.endsWith("$"),
          // Like the native side: denied shares cannot be tree-connected, so their access is unknown.
          ...(share.denied
            ? {}
            : { access: share.readOnly ? ("read" as const) : ("readWrite" as const) }),
        })),
    }
  }

  async list({ location, path }: PathOptions): Promise<ListResult> {
    const { latencyMs, node, path: clean, readOnly, local } = this.resolve(location, path)
    if (!node.children) throw fail("NOT_FOUND", `${path} is not a folder`)
    if (node.denied) throw fail("ACCESS_DENIED", `No access to ${node.name}`)
    await this.wait(latencyMs * 2 + Math.min(node.children.length, 400) / 4)
    return {
      entries: node.children.map((child) => {
        const entry = toEntry(child, joinPath(clean, child.name))
        return local && child.children && isRestrictedPath(entry.path)
          ? { ...entry, restricted: true }
          : entry
      }),
      access: readOnly ? "read" : "readWrite",
    }
  }

  async stat({ location, path }: PathOptions): Promise<StatResult> {
    const { latencyMs, node, path: clean } = this.resolve(location, path)
    await this.wait(latencyMs)
    return { entry: toEntry(node, clean) }
  }

  async createFolder({ location, path, name }: CreateFolderOptions): Promise<StatResult> {
    const { latencyMs, node, path: clean, readOnly } = this.resolve(location, path)
    if (!node.children) throw fail("NOT_FOUND", `${path} is not a folder`)
    if (readOnly) throw HoardSmbWeb.readOnlyError(node.name)
    if (node.children.some((child) => child.name === name)) {
      throw fail("ALREADY_EXISTS", `${name} already exists`)
    }
    await this.wait(latencyMs * 2)
    const created: MockFile = { name, children: [], modifiedAt: new Date().toISOString() }
    node.children.push(created)
    return { entry: toEntry(created, joinPath(clean, name)) }
  }

  async rename({ location, path, newName }: RenameOptions): Promise<StatResult> {
    const {
      latencyMs,
      node,
      parent,
      path: clean,
      readOnly,
      parentReadOnly,
    } = this.resolve(location, path)
    if (!parent) throw fail("ACCESS_DENIED", "Cannot rename the share root")
    if (readOnly || parentReadOnly) throw HoardSmbWeb.readOnlyError(node.name)
    if (parent.children?.some((child) => child.name === newName)) {
      throw fail("ALREADY_EXISTS", `${newName} already exists`)
    }
    await this.wait(latencyMs * 2)
    node.name = newName
    const folder = splitPath(clean).slice(0, -1).join("/")
    return { entry: toEntry(node, joinPath(folder, newName)) }
  }

  async delete({ location, path, recursive }: DeleteOptions): Promise<void> {
    const { latencyMs, node, parent, readOnly, parentReadOnly } = this.resolve(location, path)
    if (!parent?.children) throw fail("ACCESS_DENIED", "Cannot delete the share root")
    if (readOnly || parentReadOnly) throw HoardSmbWeb.readOnlyError(node.name)
    if (node.children?.length && !recursive)
      throw fail("ACCESS_DENIED", `${node.name} is not empty`)
    await this.wait(latencyMs * 2)
    parent.children.splice(parent.children.indexOf(node), 1)
  }

  // --- Destinations -------------------------------------------------------------------------

  private readDestinations(): DestinationFolder[] {
    try {
      const saved = JSON.parse(
        localStorage.getItem(DESTINATIONS_KEY) ?? "[]"
      ) as DestinationFolder[]
      return Array.isArray(saved) ? saved : []
    } catch {
      return []
    }
  }

  private writeDestinations(folders: DestinationFolder[]) {
    try {
      localStorage.setItem(DESTINATIONS_KEY, JSON.stringify(folders))
    } catch {
      // Not persisted in this browser; fine for a mock.
    }
  }

  async pickDestinationFolder(): Promise<DestinationFolder> {
    await this.wait(400)
    const existing = this.readDestinations()
    const candidates: DestinationFolder[] = [
      {
        uri: "content://com.android.externalstorage.documents/tree/primary%3ADownload",
        name: "Download",
      },
      {
        uri: "content://com.android.externalstorage.documents/tree/primary%3AMovies%2FHoard",
        name: "Movies/Hoard",
      },
      {
        uri: "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
        name: "Documents",
      },
    ]
    const folder = candidates.find((c) => !existing.some((e) => e.uri === c.uri)) ?? candidates[0]
    this.writeDestinations([...existing.filter((e) => e.uri !== folder.uri), folder])
    return folder
  }

  async listDestinationFolders(): Promise<ListDestinationFoldersResult> {
    return { folders: this.readDestinations() }
  }

  async releaseDestinationFolder({ uri }: ReleaseDestinationFolderOptions): Promise<void> {
    this.writeDestinations(this.readDestinations().filter((f) => f.uri !== uri))
  }

  // --- Transfers (ADR 0007) ------------------------------------------------------------------

  /** The tree a transfer reads or writes, like native endpoints (own connection for SMB). */
  private transferRoot(location: TransferSourceLocation): MockRoot {
    switch (location.kind) {
      case "smb": {
        const routes = location.addresses.map((a) => ({ host: a.host, route: routeFor(a.host) }))
        const live = routes.find((r) => r.route.serverKey && !r.route.failure)
        if (!live) throw fail("UNREACHABLE", "No address answered")
        const server = this.trees[live.route.serverKey!]
        if (location.expectedServerGuid.toLowerCase() !== server.guid) {
          throw fail("GUID_MISMATCH", `${live.host} is a different server`, {
            serverGuid: server.guid,
          })
        }
        const share = server.shares.find(
          (s) => s.name.toLowerCase() === location.share.toLowerCase()
        )
        if (!share) throw fail("NOT_FOUND", `Share ${location.share} not found`)
        if (share.denied) throw fail("ACCESS_DENIED", `No access to ${location.share}`)
        return {
          root: { name: share.name, children: share.root },
          readOnly: Boolean(share.readOnly),
          speed: mockSpeedFor(live.route.latencyMs),
          host: live.host,
          fallbackHosts: routes.filter((r) => r !== live).map((r) => r.host),
          key: `smb:${location.machineId}:${share.name.toLowerCase()}`,
        }
      }
      case "tree": {
        if (!this.readDestinations().some((f) => f.uri === location.uri)) {
          throw fail("PERMISSION_REVOKED", "The destination folder is no longer accessible")
        }
        let root = this.treeRoots.get(location.uri)
        if (!root) {
          root = {
            name: "tree",
            children: [{ name: "notes.md", size: 120, modifiedAt: new Date().toISOString() }],
          }
          this.treeRoots.set(location.uri, root)
        }
        return { root, readOnly: false, speed: 60 * 1024 * 1024, key: `tree:${location.uri}` }
      }
      case "incoming": {
        const shared = this.incoming.get(location.shareId)
        if (!shared) throw fail("NOT_FOUND", "The shared files are no longer available")
        return {
          root: {
            name: "incoming",
            children: shared.files.map((f) => ({
              name: f.name,
              size: Math.max(0, f.size),
              modifiedAt: shared.receivedAt,
            })),
          },
          readOnly: true,
          speed: 80 * 1024 * 1024,
          key: `incoming:${location.shareId}`,
        }
      }
      default: {
        const { volume, readOnly } = this.localRoot(location)
        return {
          root: { name: volume.info.label, children: volume.root },
          readOnly,
          speed: 80 * 1024 * 1024,
          freeBytes: volume.info.freeBytes,
          key: location.kind === "device" ? "device" : `volume:${location.uuid}`,
        }
      }
    }
  }

  async startTransfer(options: StartTransferOptions): Promise<StartTransferResult> {
    await this.wait(20)
    return this.transfers.start(options)
  }

  async resolveTransferConflicts(options: ResolveTransferConflictsOptions): Promise<void> {
    this.transfers.resolve(options)
  }

  async cancelTransfer({ transferId }: TransferRef): Promise<void> {
    this.transfers.cancel(transferId)
  }

  async getTransfers(): Promise<GetTransfersResult> {
    return { transfers: this.transfers.list() }
  }

  async getTransferItems(options: GetTransferItemsOptions): Promise<GetTransferItemsResult> {
    return this.transfers.items(options)
  }

  async acknowledgeTransfers({ transferIds }: { transferIds: string[] }): Promise<void> {
    this.transfers.acknowledge(transferIds)
  }

  // --- Share in and out ------------------------------------------------------------------------

  async shareFiles({ location, paths }: ShareFilesOptions): Promise<void> {
    if (paths.length === 0) throw fail("UNKNOWN", "paths is required")
    for (const path of paths) {
      const { node } = await this.streamSource({ location, path })
      if (node.children) throw fail("UNKNOWN", "Folders cannot be shared")
    }
    console.info(`[HoardSmbWeb] would open the share sheet with ${paths.length} file(s)`, paths)
  }

  /** Dev tool: pretend another app shared files to Hoard (emits `filesShared`). */
  simulateIncomingShare(files: { name: string; size: number; mimeType?: string }[]): SharedFiles {
    const shared: SharedFiles = {
      shareId: `mock-share-${this.nextId++}`,
      files: files.map((f, index) => ({ index, ...f })),
      receivedAt: new Date().toISOString(),
    }
    this.incoming.set(shared.shareId, { ...shared, cleared: false })
    this.notifyListeners("filesShared", shared)
    return shared
  }

  async getSharedFiles(): Promise<GetSharedFilesResult> {
    const latest = [...this.incoming.values()].reverse().find((s) => !s.cleared)
    if (!latest) return { shared: null }
    const { cleared: _cleared, ...shared } = latest
    return { shared }
  }

  async clearSharedFiles({ shareId }: { shareId: string }): Promise<void> {
    const shared = this.incoming.get(shareId)
    if (shared) shared.cleared = true
  }

  // --- Viewing ------------------------------------------------------------------------------

  async openFile({ location, path }: OpenFileOptions): Promise<void> {
    const { latencyMs, node, local } = this.resolve(location, path)
    if (node.children) throw fail("UNKNOWN", "Cannot open a folder")
    const ext = extension(node.name)
    if (MOCK_NO_APP_EXTENSIONS.includes(ext)) throw fail("NO_APP", `No app can open .${ext} files`)
    // SMB: simulates the download to cache at local speed (capped so the UI doesn't wait
    // forever). Local files open in place.
    if (!local) {
      await this.wait(
        Math.min(2500, latencyMs * 4 + ((node.size ?? 0) / mockSpeedFor(latencyMs)) * 1000)
      )
    }
    console.info(`[HoardSmbWeb] would open ${location.kind}:${path} with ACTION_VIEW`)
  }

  // --- Streaming and the local web server ----------------------------------------------------

  /** Live mock tokens (`stopStreams` clears them); mock-only, for tests and dev tools. */
  readonly streams = new Map<string, { kind: "file" | "folder"; source: string; path: string }>()

  /**
   * Like native: an SMB source opens its own connection over the addresses in order (GUID
   * checked), then the file; a local source reads the file itself.
   */
  private async streamSource({ location, path }: StreamSourceOptions) {
    if (location.kind !== "smb") return this.resolve(location, path)
    const { machineId, addresses, expectedServerGuid, share } = location
    if (addresses.length === 0) throw fail("UNKNOWN", "addresses is required")
    let lastError: unknown = fail("UNREACHABLE", "No address answered")
    for (const address of addresses) {
      try {
        const { serverKey, route } = await this.open(
          machineId,
          address.host,
          address.port ?? 445,
          expectedServerGuid,
          8000
        )
        const sessionId = `mock-stream-${this.nextId++}`
        this.sessions.set(sessionId, { serverKey, latencyMs: route.latencyMs })
        try {
          return this.resolveSmb(sessionId, share, path)
        } finally {
          this.sessions.delete(sessionId)
        }
      } catch (error) {
        const code = smbErrorCodeOf(error)
        if (code !== "UNREACHABLE" && code !== "TIMEOUT") throw error
        lastError = error
      }
    }
    throw lastError
  }

  async streamFile(options: StreamFileOptions): Promise<StreamFileResult> {
    const { node } = await this.streamSource(options)
    if (node.children) throw fail("UNKNOWN", "Cannot stream a folder")
    const ext = extension(node.name)
    if (MOCK_NO_APP_EXTENSIONS.includes(ext)) throw fail("NO_APP", `No app can open .${ext} files`)
    const mimeType = options.mimeType ?? MOCK_MIME_TYPES[ext] ?? "application/octet-stream"
    const { location } = options
    if (location.kind !== "smb") {
      // Local files are handed over in place (FileProvider), no stream token.
      const uri = `content://dev.hfps.hoard.fileprovider/device/${options.path.split("/").map(encodeURIComponent).join("/")}`
      console.info(`[HoardSmbWeb] would open ${options.path} in place as ${uri}`)
      return { uri, size: node.size ?? 0, mimeType }
    }
    const token = this.newToken()
    this.streams.set(token, { kind: "file", source: location.share, path: options.path })
    const uri =
      options.transport === "http"
        ? `http://127.0.0.1:38080/${token}/${encodeURIComponent(node.name)}`
        : `content://dev.hfps.hoard.stream/${token}/${encodeURIComponent(node.name)}`
    console.info(`[HoardSmbWeb] would stream ${location.share}/${options.path} as ${uri}`)
    return { uri, size: node.size ?? 0, mimeType }
  }

  async openWebPage(options: OpenWebPageOptions): Promise<OpenWebPageResult> {
    const { node } = await this.streamSource(options)
    if (node.children) throw fail("UNKNOWN", "Not a page")
    const token = this.newToken()
    const source = options.location.kind === "smb" ? options.location.share : options.location.kind
    this.streams.set(token, { kind: "folder", source, path: options.path })
    const url = `http://127.0.0.1:38080/${token}/${encodeURIComponent(node.name)}`
    console.info(`[HoardSmbWeb] would serve ${source}/${options.path} at ${url}`)
    return { url }
  }

  async stopStreams(): Promise<void> {
    this.streams.clear()
  }

  private newToken(): string {
    const bytes = new Uint8Array(32)
    crypto.getRandomValues(bytes)
    return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("")
  }

  // --- Text files ---------------------------------------------------------------------------

  /** Files someone else edits right after they are opened (the "conflict" demo), by node. */
  private readonly editedBySomeoneElse = new WeakSet<MockFile>()

  /** The node's bytes: real content, generated content, or a stand-in for files without any. */
  private contentOf(node: MockFile): Uint8Array {
    if (node.bytes) return node.bytes
    if (node.text !== undefined) return new TextEncoder().encode(node.text)
    if (node.generated) {
      node.bytes = new TextEncoder().encode(GENERATED_TEXT[node.generated]())
      node.size = node.bytes.length
      return node.bytes
    }
    // A listing-only file: text-like names get a short stand-in, anything else looks binary.
    if (!isTextFile(node.name)) return new Uint8Array([0, 1, 2, 3])
    return new TextEncoder().encode(`${node.name}\n\n(Sample content from the web mock.)\n`)
  }

  async readTextFile({
    location,
    path,
    maxBytes = TEXT_FILE_MAX_BYTES,
    truncate = false,
  }: ReadTextFileOptions): Promise<ReadTextFileResult> {
    const { latencyMs, node, readOnly } = this.resolve(location, path)
    if (node.children) throw fail("UNKNOWN", `${path} is a folder`)
    const limit = Math.max(1, Math.min(maxBytes, TEXT_FILE_HARD_LIMIT))
    // Files with content report their real size; listing-only ones their listed size.
    const hasContent = Boolean(node.bytes || node.text !== undefined || node.generated)
    const bytes = hasContent || (node.size ?? 0) <= limit ? this.contentOf(node) : null
    const size = bytes && hasContent ? bytes.length : (node.size ?? 0)
    if (size > limit && !truncate) {
      throw fail("TOO_LARGE", `${node.name} is ${size} bytes (limit ${limit})`, { size })
    }
    await this.wait(latencyMs * 2 + (Math.min(size, limit) / mockSpeedFor(latencyMs)) * 1000)
    const decoded = decodeTextPrefix(bytes ?? new Uint8Array(0), limit)
    if (!decoded) throw fail("NOT_TEXT", `${node.name} is not a text file`)
    const result: ReadTextFileResult = {
      text: decoded.text,
      encoding: decoded.encoding,
      size,
      modifiedAt: node.modifiedAt ?? null,
      truncated: size > limit,
      access: readOnly ? "read" : "readWrite",
    }
    if (node.name.includes("conflict") && !this.editedBySomeoneElse.has(node)) {
      // Simulates a colleague saving the file a few seconds after it was first opened: the next
      // save conflicts.
      this.editedBySomeoneElse.add(node)
      setTimeout(
        () => {
          node.text = `${new TextDecoder().decode(this.contentOf(node))}\nA line added by someone else.\n`
          node.bytes = undefined
          node.size = new TextEncoder().encode(node.text).length
          node.modifiedAt = laterThan(node.modifiedAt)
        },
        this.latencyScale === 0 ? 0 : 3000
      )
    }
    return result
  }

  async writeTextFile({
    location,
    path,
    text,
    encoding = "utf-8",
    mode = "overwrite",
    expectedModifiedAt,
  }: WriteTextFileOptions): Promise<WriteTextFileResult> {
    const segments = splitPath(path)
    const name = segments.at(-1) ?? ""
    if (!isValidMockName(name)) throw fail("UNKNOWN", `Invalid file name: '${name}'`)
    const folderPath = segments.slice(0, -1).join("/")
    const { latencyMs, node: folder, readOnly } = this.resolve(location, folderPath)
    if (!folder.children) throw fail("NOT_FOUND", `${folderPath} is not a folder`)
    if (folder.denied) throw fail("ACCESS_DENIED", `No access to ${folder.name}`)
    const existing = folder.children.find((child) => child.name === name)
    if (readOnly || existing?.readOnly)
      throw HoardSmbWeb.readOnlyError(existing?.name ?? folder.name)
    if (existing?.children) throw fail("ALREADY_EXISTS", `${name} is a folder`)
    if (mode === "create" && existing) throw fail("ALREADY_EXISTS", `${name} already exists`)
    if (mode === "overwrite" && expectedModifiedAt) {
      const current = existing?.modifiedAt ?? null
      if (current !== expectedModifiedAt) {
        throw fail("CONFLICT", `${name} changed on the server since it was opened`, {
          modifiedAt: existing ? current : null,
        })
      }
    }
    if (!canEncode(text, encoding)) {
      throw fail("UNKNOWN", `${encoding} cannot hold every character of the text`)
    }
    const bytes = encodeText(text, encoding)
    await this.wait(latencyMs * 3 + (bytes.length / mockSpeedFor(latencyMs)) * 1000)
    const target: MockFile = existing ?? { name }
    target.bytes = bytes
    target.text = undefined
    target.generated = undefined
    target.size = bytes.length
    target.modifiedAt = laterThan(existing?.modifiedAt)
    if (!existing) folder.children.push(target)
    return { entry: toEntry(target, segments.join("/")) }
  }

  async thumbnail({ location, path, maxSize }: ThumbnailOptions): Promise<ThumbnailResult> {
    const { latencyMs, node } = this.resolve(location, path)
    if (node.children || !THUMBNAIL_EXTENSIONS.has(extension(node.name))) {
      throw fail("UNKNOWN", "Thumbnails are only available for images and videos")
    }
    await this.wait(latencyMs * 3 + 40)
    const hue = hueOf(path)
    const landscape = hue % 3 !== 0
    const width = landscape ? maxSize : Math.round(maxSize * 0.75)
    const height = landscape ? Math.round(maxSize * 0.75) : maxSize
    const svg =
      `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">` +
      `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">` +
      `<stop offset="0" stop-color="hsl(${hue} 60% 62%)"/><stop offset="1" stop-color="hsl(${(hue + 50) % 360} 55% 38%)"/>` +
      `</linearGradient></defs><rect width="100%" height="100%" fill="url(#g)"/>` +
      `<circle cx="${width * 0.72}" cy="${height * 0.3}" r="${Math.min(width, height) * 0.12}" fill="hsl(${hue} 90% 88%)" opacity="0.85"/>` +
      `<path d="M0 ${height} L${width * 0.35} ${height * 0.5} L${width * 0.6} ${height * 0.78} L${width * 0.78} ${height * 0.6} L${width} ${height} Z" fill="hsl(${(hue + 120) % 360} 35% 25%)" opacity="0.7"/>` +
      `</svg>`
    return { src: `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`, width, height }
  }
}

/** Valid SMB file name (same rule as the native `SmbPaths.requireValidName`). */
function isValidMockName(name: string) {
  return (
    name.trim() === name &&
    name !== "" &&
    name !== "." &&
    name !== ".." &&
    !/[\\/:*?"<>|]/.test(name) &&
    ![...name].some((c) => c.charCodeAt(0) < 32) &&
    !name.endsWith(".")
  )
}
