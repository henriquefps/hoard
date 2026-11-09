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
  MachineRef,
  NativeTransfer,
  OpenFileOptions,
  PathOptions,
  ProbeOptions,
  ProbeResult,
  ReleaseDestinationFolderOptions,
  RenameOptions,
  SessionRef,
  SetCredentialsOptions,
  SmbEntry,
  StartDownloadOptions,
  StartDownloadResult,
  StatResult,
  ThumbnailOptions,
  ThumbnailResult,
  TransferProgressEvent,
  TransferRef,
} from "./definitions"
import { TERMINAL_TRANSFER_STATES } from "./definitions"
import { detectAddressKind } from "@/lib/addressKind"
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

const DESTINATIONS_KEY = "hoard.mock.destinations"

interface MockSession {
  serverKey: MockServerKey
  latencyMs: number
}

interface MockJob extends NativeTransfer {
  timer?: ReturnType<typeof setInterval>
  speed: number
  addressIndex: number
  addresses: { host: string; port?: number }[]
  /** For the "flaky" scenario: drop once at this byte count. */
  dropAt?: number
  /** For the "broken" scenario: fail for good at this byte count (no address left). */
  failAt?: number
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
  private readonly jobs = new Map<string, MockJob>()
  private readonly trees = structuredClone(MOCK_SERVERS) as Record<MockServerKey, MockServer>
  private nextId = 1

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

  /** Resolve a path to its node (the share root is a synthetic folder). */
  private resolve(sessionId: string, shareName: string, path: string) {
    const session = this.session(sessionId)
    const share = this.shareOf(session, shareName)
    let node: MockFile = { name: share.name, children: share.root }
    let parent: MockFile | null = null
    for (const segment of splitPath(path)) {
      if (node.denied) throw fail("ACCESS_DENIED", `No access to ${node.name}`)
      const next = node.children?.find((child) => child.name === segment)
      if (!next) throw fail("NOT_FOUND", `${path} not found`)
      parent = node
      node = next
    }
    return { session, node, parent, path: splitPath(path).join("/") }
  }

  async listShares({ sessionId }: SessionRef): Promise<ListSharesResult> {
    const session = this.session(sessionId)
    await this.wait(session.latencyMs * 2)
    return {
      shares: this.trees[session.serverKey].shares
        .filter((share) => share.name !== "IPC$")
        .map((share) => ({
          name: share.name,
          comment: share.comment,
          isHidden: share.name.endsWith("$"),
        })),
    }
  }

  async list({ sessionId, share, path }: PathOptions): Promise<ListResult> {
    const { session, node, path: clean } = this.resolve(sessionId, share, path)
    if (!node.children) throw fail("NOT_FOUND", `${path} is not a folder`)
    if (node.denied) throw fail("ACCESS_DENIED", `No access to ${node.name}`)
    await this.wait(session.latencyMs * 2 + Math.min(node.children.length, 400) / 4)
    return { entries: node.children.map((child) => toEntry(child, joinPath(clean, child.name))) }
  }

  async stat({ sessionId, share, path }: PathOptions): Promise<StatResult> {
    const { session, node, path: clean } = this.resolve(sessionId, share, path)
    await this.wait(session.latencyMs)
    return { entry: toEntry(node, clean) }
  }

  async createFolder({ sessionId, share, path, name }: CreateFolderOptions): Promise<StatResult> {
    const { session, node, path: clean } = this.resolve(sessionId, share, path)
    if (!node.children) throw fail("NOT_FOUND", `${path} is not a folder`)
    if (node.children.some((child) => child.name === name)) {
      throw fail("ALREADY_EXISTS", `${name} already exists`)
    }
    await this.wait(session.latencyMs * 2)
    const created: MockFile = { name, children: [], modifiedAt: new Date().toISOString() }
    node.children.push(created)
    return { entry: toEntry(created, joinPath(clean, name)) }
  }

  async rename({ sessionId, share, path, newName }: RenameOptions): Promise<StatResult> {
    const { session, node, parent, path: clean } = this.resolve(sessionId, share, path)
    if (!parent) throw fail("ACCESS_DENIED", "Cannot rename the share root")
    if (parent.children?.some((child) => child.name === newName)) {
      throw fail("ALREADY_EXISTS", `${newName} already exists`)
    }
    await this.wait(session.latencyMs * 2)
    node.name = newName
    const folder = splitPath(clean).slice(0, -1).join("/")
    return { entry: toEntry(node, joinPath(folder, newName)) }
  }

  async delete({ sessionId, share, path, recursive }: DeleteOptions): Promise<void> {
    const { session, node, parent } = this.resolve(sessionId, share, path)
    if (!parent?.children) throw fail("ACCESS_DENIED", "Cannot delete the share root")
    if (node.children?.length && !recursive)
      throw fail("ACCESS_DENIED", `${node.name} is not empty`)
    await this.wait(session.latencyMs * 2)
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

  // --- Transfers ----------------------------------------------------------------------------

  private emit(job: MockJob, bytesPerSecond?: number) {
    const event: TransferProgressEvent = {
      transferId: job.transferId,
      bytesDone: job.bytesDone,
      totalBytes: job.totalBytes,
      state: job.state,
      error: job.error,
      activeHost: job.activeHost,
      bytesPerSecond,
    }
    this.notifyListeners("transferProgress", event)
  }

  private finish(job: MockJob, state: MockJob["state"], error?: MockJob["error"]) {
    clearInterval(job.timer)
    job.timer = undefined
    job.state = state
    job.error = error
    job.finishedAt = new Date().toISOString()
    this.emit(job)
  }

  /** Connect through the job's address list starting at `addressIndex`; null when none works. */
  private async connectJob(job: MockJob): Promise<MockRoute | null> {
    for (; job.addressIndex < job.addresses.length; job.addressIndex++) {
      const address = job.addresses[job.addressIndex]
      try {
        const { route } = await this.open(
          job.machineId,
          address.host,
          address.port ?? 445,
          this.jobGuid.get(job.transferId),
          1500
        )
        job.activeHost = address.host
        return route
      } catch (error) {
        const code = (error as HoardSmbWebError).code
        // A wrong password is not an address problem: failing over would not help. A GUID mismatch
        // means this address is somebody else's server: skip it like an unreachable one.
        if (code === "AUTH_FAILED") {
          this.finish(job, "failed", { code, message: (error as Error).message })
          return null
        }
        job.error = { code, message: (error as Error).message }
      }
    }
    this.finish(job, "failed", job.error ?? { code: "UNREACHABLE", message: "No address answered" })
    return null
  }

  private readonly jobGuid = new Map<string, string>()

  private run(job: MockJob, route: MockRoute) {
    job.state = "running"
    job.speed = mockSpeedFor(route.latencyMs)
    this.emit(job)
    const tickMs = 250
    job.timer = setInterval(
      () => {
        if (job.state !== "running") return
        const step = Math.max(1, Math.round((job.speed * tickMs) / 1000))
        job.bytesDone = Math.min(job.totalBytes, job.bytesDone + step)
        if (job.failAt !== undefined && job.bytesDone >= job.failAt) {
          this.finish(job, "failed", {
            code: "UNREACHABLE",
            message: `Connection reset by ${job.activeHost}; no other address answered`,
          })
          return
        }
        if (job.dropAt !== undefined && job.bytesDone >= job.dropAt) {
          // Simulated Wi-Fi drop: retry, fail over to the next address, resume at the same offset.
          job.dropAt = undefined
          clearInterval(job.timer)
          job.state = "retrying"
          job.error = { code: "UNREACHABLE", message: `Lost connection to ${job.activeHost}` }
          this.emit(job)
          job.addressIndex++
          void this.wait(1500).then(async () => {
            if (job.state !== "retrying") return
            const next = await this.connectJob(job)
            if (next) {
              job.error = undefined
              this.run(job, next)
            }
          })
          return
        }
        if (job.bytesDone >= job.totalBytes) {
          this.finish(job, "completed")
          return
        }
        this.emit(job, job.speed)
      },
      this.latencyScale === 0 ? 0 : tickMs
    )
  }

  async startDownload(options: StartDownloadOptions): Promise<StartDownloadResult> {
    const { transferId, machineId, addresses, expectedServerGuid, share, remotePath, destTreeUri } =
      options
    if (
      this.jobs.has(transferId) &&
      !TERMINAL_TRANSFER_STATES.includes(this.jobs.get(transferId)!.state)
    ) {
      throw fail("UNKNOWN", `Transfer ${transferId} is already running`)
    }
    if (addresses.length === 0) throw fail("UNREACHABLE", "No addresses given")
    if (!this.readDestinations().some((f) => f.uri === destTreeUri)) {
      throw fail("PERMISSION_REVOKED", "The destination folder is no longer accessible")
    }
    const serverKey = addresses.map((a) => routeFor(a.host).serverKey).find(Boolean)
    if (!serverKey) throw fail("UNREACHABLE", "No address answered")
    const node = this.findNode(serverKey, share, remotePath)
    if (!node) throw fail("NOT_FOUND", `${remotePath} not found`)
    if (node.children) throw fail("UNKNOWN", "Folder downloads are not supported yet")

    const remoteName = splitPath(remotePath).at(-1) ?? remotePath
    const wanted = options.destName ?? remoteName
    const destName =
      options.conflict === "rename" && this.destinationHas(destTreeUri, wanted)
        ? withSuffix(wanted)
        : wanted
    const totalBytes = node.size ?? 0
    const resumeFrom = Math.min(options.resumeFromBytes ?? 0, totalBytes)
    const job: MockJob = {
      transferId,
      machineId,
      share,
      remotePath,
      destTreeUri,
      destName,
      destUri: `${destTreeUri}/document/${encodeURIComponent(destName)}`,
      totalBytes,
      bytesDone: resumeFrom,
      state: "queued",
      startedAt: new Date().toISOString(),
      speed: 0,
      addressIndex: 0,
      addresses,
      dropAt: remoteName.includes("flaky") ? Math.round(totalBytes * 0.4) : undefined,
      failAt:
        remoteName.includes("broken") && resumeFrom === 0
          ? Math.round(totalBytes * 0.6)
          : undefined,
    }
    this.jobs.set(transferId, job)
    this.jobGuid.set(transferId, expectedServerGuid)

    if (options.conflict === "skip" && this.destinationHas(destTreeUri, wanted)) {
      this.finish(job, "skipped")
      return { transferId, destName, destUri: job.destUri! }
    }

    this.emit(job)
    void this.connectJob(job).then(
      (route) => route && job.state === "queued" && this.run(job, route)
    )
    return { transferId, destName, destUri: job.destUri! }
  }

  /** The mock "destination" already holds anything a completed job wrote, plus `notes.md`. */
  private destinationHas(treeUri: string, name: string) {
    if (name === "notes.md") return true
    return [...this.jobs.values()].some(
      (j) => j.destTreeUri === treeUri && j.destName === name && j.state === "completed"
    )
  }

  private findNode(serverKey: MockServerKey, shareName: string, path: string): MockFile | null {
    const share = this.trees[serverKey].shares.find((s) => s.name === shareName)
    if (!share) return null
    let node: MockFile = { name: share.name, children: share.root }
    for (const segment of splitPath(path)) {
      const next = node.children?.find((child) => child.name === segment)
      if (!next) return null
      node = next
    }
    return node
  }

  async cancelTransfer({ transferId }: TransferRef): Promise<void> {
    const job = this.jobs.get(transferId)
    if (!job || TERMINAL_TRANSFER_STATES.includes(job.state)) return
    this.finish(job, "cancelled", { code: "CANCELLED", message: "Cancelled" })
  }

  async getTransfers(): Promise<GetTransfersResult> {
    return {
      transfers: [...this.jobs.values()].map(
        ({
          timer: _timer,
          speed: _speed,
          addressIndex: _i,
          addresses: _a,
          dropAt: _d,
          failAt: _f,
          ...rest
        }) => rest
      ),
    }
  }

  async acknowledgeTransfers({ transferIds }: { transferIds: string[] }): Promise<void> {
    for (const id of transferIds) {
      const job = this.jobs.get(id)
      if (job && TERMINAL_TRANSFER_STATES.includes(job.state)) {
        this.jobs.delete(id)
        this.jobGuid.delete(id)
      }
    }
  }

  // --- Viewing ------------------------------------------------------------------------------

  async openFile({ sessionId, share, path }: OpenFileOptions): Promise<void> {
    const { session, node } = this.resolve(sessionId, share, path)
    if (node.children) throw fail("UNKNOWN", "Cannot open a folder")
    const ext = extension(node.name)
    if (["bin", "zst", "ds_store"].includes(ext))
      throw fail("NO_APP", `No app can open .${ext} files`)
    // Simulates the download to cache at local speed (capped so the UI doesn't wait forever).
    await this.wait(
      Math.min(
        2500,
        session.latencyMs * 4 + ((node.size ?? 0) / mockSpeedFor(session.latencyMs)) * 1000
      )
    )
    console.info(`[HoardSmbWeb] would open ${share}/${path} with ACTION_VIEW`)
  }

  async thumbnail({ sessionId, share, path, maxSize }: ThumbnailOptions): Promise<ThumbnailResult> {
    const { session, node } = this.resolve(sessionId, share, path)
    if (node.children || !THUMBNAIL_EXTENSIONS.has(extension(node.name))) {
      throw fail("UNKNOWN", "Thumbnails are only available for images and videos")
    }
    await this.wait(session.latencyMs * 3 + 40)
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

/** `photo.jpg` → `photo (1).jpg`, like Android's DocumentsProvider does on a name clash. */
function withSuffix(name: string): string {
  const dot = name.lastIndexOf(".")
  return dot > 0 ? `${name.slice(0, dot)} (1)${name.slice(dot)}` : `${name} (1)`
}
