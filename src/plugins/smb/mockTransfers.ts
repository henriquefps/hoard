/**
 * The web mock's transfer engine (ADR 0007), the in-memory twin of the native `TransferRunner`:
 * scan → conflicts (`ask` stops in `conflict`) → free space → items in order → empty source
 * folders after a move. Files really appear in (and, for a move, leave) the mock trees, so the UI
 * can browse the result. Bytes "flow" on a timer at the route's simulated speed.
 *
 * Scenarios: a name containing "flaky" drops the connection at 40% and fails over to the next
 * address; "broken" fails for good at 60% (`UNREACHABLE`); a read-only destination rejects
 * `ACCESS_DENIED` per item; a destination volume with less free space than needed fails `NO_SPACE`.
 */
import type {
  ConflictPolicy,
  GetTransferItemsOptions,
  GetTransferItemsResult,
  HoardSmbErrorCode,
  NativeTransfer,
  ResolveTransferConflictsOptions,
  StartTransferOptions,
  TransferItem,
  TransferProgressEvent,
  TransferSourceLocation,
  TransferState,
} from "./definitions"
import { TERMINAL_TRANSFER_STATES } from "./definitions"
import type { MockFile } from "./mockData"
import { findSidecar, writeMockXmp } from "./mockXmp"

/** A location's tree as the engine sees it. */
export interface MockRoot {
  root: MockFile
  readOnly: boolean
  /** Simulated bytes per second. */
  speed: number
  /** Free bytes at the destination, when known. */
  freeBytes?: number
  /** The host bytes come from or go to (SMB). */
  host?: string
  /** Other addresses to fail over to (SMB). */
  fallbackHosts?: string[]
  /** Identity for "same place" (renames). */
  key: string
}

type Fail = (code: HoardSmbErrorCode, message: string, data?: Record<string, unknown>) => Error

interface MockItem extends TransferItem {
  kind: "file" | "folder" | "rename"
  source: number
  modifiedAt?: string
  superseded?: boolean
}

interface MockJob {
  transferId: string
  op: StartTransferOptions["op"]
  options: StartTransferOptions
  state: TransferState
  items: MockItem[]
  scanned: boolean
  acknowledged: boolean
  error?: { code: HoardSmbErrorCode; message: string }
  activeHost?: string
  currentName?: string
  startedAt: string
  finishedAt?: string
  timer?: ReturnType<typeof setTimeout>
  dropped: Set<number>
}

const split = (path: string) => path.split("/").filter(Boolean)
const join = (folder: string, name: string) => (folder ? `${folder}/${name}` : name)
const parentOf = (path: string) => split(path).slice(0, -1).join("/")
const nameOf = (path: string) => split(path).at(-1) ?? ""
const isTerminal = (state: TransferState) => TERMINAL_TRANSFER_STATES.includes(state)

/** `photo.jpg` → `photo (1).jpg` → `photo (2).jpg`, case-insensitively (same as native `ConflictNames`). */
export function firstFreeName(wanted: string, taken: Iterable<string>): string {
  const lower = new Set([...taken].map((n) => n.toLowerCase()))
  if (!lower.has(wanted.toLowerCase())) return wanted
  const dot = wanted.lastIndexOf(".")
  const [stem, ext] = dot > 0 ? [wanted.slice(0, dot), wanted.slice(dot)] : [wanted, ""]
  const match = /^(.*) \((\d+)\)$/.exec(stem)
  const base = match ? match[1] : stem
  let n = match ? Number(match[2]) + 1 : 1
  while (lower.has(`${base} (${n})${ext}`.toLowerCase())) n++
  return `${base} (${n})${ext}`
}

function walk(root: MockFile, path: string): MockFile | null {
  let node: MockFile = root
  for (const segment of split(path)) {
    const next = node.children?.find((c) => c.name === segment)
    if (!next) return null
    node = next
  }
  return node
}

function clone(node: MockFile): MockFile {
  return { ...node, children: node.children?.map(clone) }
}

export class MockTransferEngine {
  private readonly jobs = new Map<string, MockJob>()
  private readonly rootOf: (location: TransferSourceLocation) => MockRoot
  private readonly emit: (event: TransferProgressEvent) => void
  private readonly fail: Fail
  private readonly tickMs: () => number

  constructor(deps: {
    rootOf: (location: TransferSourceLocation) => MockRoot
    emit: (event: TransferProgressEvent) => void
    fail: Fail
    /** 250 in the browser, 0 in tests. */
    tickMs: () => number
  }) {
    this.rootOf = deps.rootOf
    this.emit = deps.emit
    this.fail = deps.fail
    this.tickMs = deps.tickMs
  }

  // --- Contract ----------------------------------------------------------------------------------

  start(options: StartTransferOptions): { transferId: string } {
    const existing = this.jobs.get(options.transferId)
    if (existing && !isTerminal(existing.state)) {
      throw this.fail("UNKNOWN", `Transfer ${options.transferId} is already running`)
    }
    if (!options.sources.length) throw this.fail("UNKNOWN", "No sources")
    if (options.sidecars?.length && options.op !== "copy") {
      throw this.fail("UNKNOWN", "Sidecars are written by copies only")
    }
    if (
      (options.sidecars?.length ?? 0) > 5000 ||
      options.sidecars?.some(
        (x) =>
          !x.sourcePath ||
          !(x.rating >= 0 && x.rating <= 5) ||
          !["pick", "reject", "none"].includes(x.flag)
      )
    ) {
      throw this.fail("UNKNOWN", "Invalid sidecars")
    }
    if ((options.destination.location as TransferSourceLocation).kind === "incoming") {
      throw this.fail("UNKNOWN", "Shared files are not a destination")
    }
    const dest = this.rootOf(options.destination.location)
    const destFolder = walk(dest.root, options.destination.path)
    if (!destFolder?.children) throw this.fail("NOT_FOUND", "The destination folder does not exist")
    for (const source of options.sources) {
      const root = this.rootOf(source.location)
      if (root.key === dest.key && isSameOrInside(source.path, options.destination.path)) {
        throw this.fail("UNKNOWN", `${nameOf(source.path)} cannot go into itself`)
      }
    }
    if (existing && options.resume) {
      for (const item of existing.items) {
        if (item.state === "failed" || item.state === "cancelled") {
          item.state = "queued"
          item.error = undefined
        }
      }
      existing.state = "queued"
      existing.error = undefined
      existing.finishedAt = undefined
      existing.acknowledged = false
      this.report(existing)
      this.schedule(existing)
      return { transferId: existing.transferId }
    }
    const job: MockJob = {
      transferId: options.transferId,
      op: options.op,
      options,
      state: "queued",
      items: [],
      scanned: false,
      acknowledged: false,
      startedAt: new Date().toISOString(),
      dropped: new Set(),
    }
    this.jobs.set(job.transferId, job)
    this.report(job)
    this.schedule(job)
    return { transferId: job.transferId }
  }

  resolve({ transferId, all, items }: ResolveTransferConflictsOptions) {
    const job = this.jobs.get(transferId)
    if (!job) throw this.fail("NOT_FOUND", `No transfer ${transferId}`)
    const answers = new Map(items?.map((i) => [i.index, i.policy]))
    for (const item of job.items) {
      if (item.conflict !== "ask" || isTerminal(item.state)) continue
      const answer = answers.get(item.index) ?? all
      if (answer) item.conflict = answer
    }
    if (job.state === "conflict" && !this.conflicts(job)) {
      job.state = "queued"
      this.report(job)
      this.schedule(job)
    }
  }

  cancel(transferId: string) {
    const job = this.jobs.get(transferId)
    if (!job || isTerminal(job.state)) return
    clearTimeout(job.timer)
    for (const item of job.items) if (!isTerminal(item.state)) item.state = "cancelled"
    job.error = { code: "CANCELLED", message: "Cancelled" }
    this.end(job, "cancelled")
  }

  list(): NativeTransfer[] {
    return [...this.jobs.values()]
      .filter((j) => !j.acknowledged || !isTerminal(j.state))
      .map((job) => ({
        ...this.fields(job),
        op: job.op,
        startedAt: job.startedAt,
        ...(job.finishedAt ? { finishedAt: job.finishedAt } : {}),
      }))
  }

  acknowledge(ids: string[]) {
    for (const id of ids) {
      const job = this.jobs.get(id)
      if (job && isTerminal(job.state)) job.acknowledged = true
    }
  }

  items(options: GetTransferItemsOptions): GetTransferItemsResult {
    const job = this.jobs.get(options.transferId)
    if (!job) throw this.fail("NOT_FOUND", "The transfer's details are gone")
    const matching = job.items.filter(
      (i) =>
        !i.superseded &&
        (i.kind !== "folder" || i.state === "failed") &&
        (!options.states || options.states.includes(i.state)) &&
        (!options.conflictsOnly || (i.conflict === "ask" && !isTerminal(i.state)))
    )
    const offset = options.offset ?? 0
    const limit = Math.min(1000, options.limit ?? 200)
    return {
      total: matching.length,
      items: matching
        .slice(offset, offset + limit)
        .map(({ kind: _k, source: _s, modifiedAt: _m, superseded: _x, ...item }) => item),
    }
  }

  // --- Engine ------------------------------------------------------------------------------------

  private schedule(job: MockJob, delay = this.tickMs()) {
    clearTimeout(job.timer)
    job.timer = setTimeout(() => this.step(job), delay)
  }

  private conflicts(job: MockJob) {
    return job.items.filter((i) => i.conflict === "ask" && !isTerminal(i.state)).length
  }

  private counts(job: MockJob) {
    const counted = job.items.filter((i) => i.kind !== "folder" && !i.superseded)
    return {
      itemsTotal: counted.length,
      itemsDone: counted.filter((i) => i.state === "completed").length,
      itemsFailed: counted.filter((i) => i.state === "failed" || i.state === "cancelled").length,
      itemsSkipped: counted.filter((i) => i.state === "skipped").length,
    }
  }

  private fields(job: MockJob): TransferProgressEvent {
    const files = job.items.filter(
      (i) => i.kind === "file" && !i.superseded && i.state !== "skipped"
    )
    const conflicts = this.conflicts(job)
    return {
      transferId: job.transferId,
      state: job.state,
      totalBytes: files.reduce((s, i) => s + i.size, 0),
      bytesDone: files.reduce(
        (s, i) => s + (i.state === "completed" ? i.size : i.state === "running" ? i.bytesDone : 0),
        0
      ),
      ...this.counts(job),
      ...(conflicts ? { conflicts } : {}),
      ...(job.currentName ? { currentName: job.currentName } : {}),
      ...(job.error &&
      (job.state === "failed" || job.state === "retrying" || job.state === "cancelled")
        ? { error: job.error }
        : {}),
      ...(job.activeHost ? { activeHost: job.activeHost } : {}),
    }
  }

  private report(job: MockJob, bytesPerSecond?: number) {
    this.emit({ ...this.fields(job), ...(bytesPerSecond ? { bytesPerSecond } : {}) })
  }

  private step(job: MockJob) {
    if (isTerminal(job.state) || job.state === "conflict") return
    try {
      if (!job.scanned) {
        job.state = "scanning"
        this.report(job)
        this.scan(job)
        job.scanned = true
        if (this.conflicts(job)) {
          job.state = "conflict"
          this.report(job)
          return
        }
        this.checkSpace(job)
      }
      if (job.state !== "running") {
        job.state = "running"
        job.error = undefined
        this.report(job)
      }
      this.advance(job)
    } catch (error) {
      const e = error as { code?: HoardSmbErrorCode; message?: string }
      this.stop(job, { code: e.code ?? "UNKNOWN", message: e.message ?? String(error) })
    }
  }

  private scan(job: MockJob) {
    const { destination, sources, op } = job.options
    const dest = this.rootOf(destination.location)
    job.items = []
    const add = (item: Omit<MockItem, "index" | "bytesDone" | "state"> & Partial<MockItem>) =>
      job.items.push({ index: job.items.length, bytesDone: 0, state: "queued", ...item })
    const destNames = new Map<string, Set<string>>()
    const exists = (folder: string, name: string) => {
      let names = destNames.get(folder)
      if (!names) {
        names = new Set(walk(dest.root, folder)?.children?.map((c) => c.name.toLowerCase()) ?? [])
        destNames.set(folder, names)
      }
      return names.has(name.toLowerCase())
    }
    const addFile = (source: number, from: string, to: string, node: MockFile) =>
      add({
        kind: "file",
        source,
        sourcePath: from,
        destPath: to,
        isDirectory: false,
        size: node.size ?? 0,
        modifiedAt: node.modifiedAt,
        ...(job.options.conflict === "ask" && exists(parentOf(to), nameOf(to))
          ? { conflict: "ask" as const }
          : {}),
      })
    const walkTree = (source: number, node: MockFile, from: string, to: string) => {
      add({ kind: "folder", source, sourcePath: from, destPath: to, isDirectory: true, size: 0 })
      for (const child of [...(node.children ?? [])].sort((a, b) => a.name.localeCompare(b.name))) {
        const childFrom = join(from, child.name)
        const childTo = join(to, child.name)
        if (child.children) walkTree(source, child, childFrom, childTo)
        else addFile(source, childFrom, childTo, child)
      }
    }
    sources.forEach((source, index) => {
      const root = this.rootOf(source.location)
      const node = walk(root.root, source.location.kind === "incoming" ? "" : source.path)
      const target =
        source.location.kind === "incoming" ? node?.children?.[Number(source.path)] : node
      const top = join(destination.path, target?.name ?? nameOf(source.path))
      if (!target) {
        add({
          kind: "file",
          source: index,
          sourcePath: source.path,
          destPath: top,
          isDirectory: false,
          size: 0,
          state: "failed",
          error: { code: "NOT_FOUND", message: `${source.path} does not exist` },
        })
        return
      }
      const same = root.key === dest.key
      if (same && op === "move" && parentOf(source.path) === destination.path) {
        add({
          kind: "rename",
          source: index,
          sourcePath: source.path,
          destPath: source.path,
          isDirectory: Boolean(target.children),
          size: target.size ?? 0,
          state: "skipped",
        })
        return
      }
      if (same && op === "move" && !exists(destination.path, target.name)) {
        add({
          kind: "rename",
          source: index,
          sourcePath: source.path,
          destPath: top,
          isDirectory: Boolean(target.children),
          size: target.size ?? 0,
        })
        return
      }
      if (target.children) walkTree(index, target, source.path, top)
      else
        addFile(index, source.location.kind === "incoming" ? source.path : source.path, top, target)
    })
  }

  private checkSpace(job: MockJob) {
    const dest = this.rootOf(job.options.destination.location)
    if (dest.freeBytes === undefined) return
    const needed = job.items
      .filter((i) => i.kind === "file" && !isTerminal(i.state))
      .reduce((s, i) => s + i.size - i.bytesDone, 0)
    if (needed > dest.freeBytes) {
      throw this.fail(
        "NO_SPACE",
        `Needs ${needed} bytes, ${dest.freeBytes} free at the destination`,
        {
          needed,
          free: dest.freeBytes,
        }
      )
    }
  }

  /** One tick: move `speed × tick` bytes through the items in order. */
  private advance(job: MockJob) {
    const tick = Math.max(this.tickMs(), 1)
    const dest = this.rootOf(job.options.destination.location)
    let budget = Math.max(1, Math.round((dest.speed * Math.max(tick, 250)) / 1000))
    let item = job.items.find((i) => !isTerminal(i.state) && !i.superseded)
    while (item && budget > 0) {
      job.currentName = nameOf(item.destPath)
      const source = this.rootOf(job.options.sources[item.source].location)
      job.activeHost ??= source.host ?? dest.host
      if (item.state === "queued") {
        item.state = "running"
        if (!this.begin(job, item, source, dest)) {
          item = job.items.find((i) => !isTerminal(i.state) && !i.superseded)
          continue
        }
      }
      if (item.kind === "file") {
        const step = Math.min(budget, item.size - item.bytesDone)
        const before = item.bytesDone
        item.bytesDone += step
        budget -= Math.max(step, 1)
        const name = item.sourcePath
        if (
          name.includes("broken") &&
          before < item.size * 0.6 &&
          item.bytesDone >= item.size * 0.6
        ) {
          throw this.fail(
            "UNREACHABLE",
            `Connection reset by ${job.activeHost}; no other address answered`
          )
        }
        if (
          name.includes("flaky") &&
          !job.dropped.has(item.index) &&
          item.bytesDone >= item.size * 0.4
        ) {
          job.dropped.add(item.index)
          job.state = "retrying"
          job.error = { code: "UNREACHABLE", message: `Lost connection to ${job.activeHost}` }
          const next = source.fallbackHosts?.[0] ?? dest.fallbackHosts?.[0]
          this.report(job)
          this.schedule(job, this.tickMs() === 0 ? 0 : 1500)
          if (next) job.activeHost = next
          return
        }
        if (item.bytesDone < item.size) break
      }
      this.finishItem(job, item, source, dest)
      item = job.items.find((i) => !isTerminal(i.state) && !i.superseded)
    }
    if (!item) {
      if (job.op === "move") this.cleanUpMoved(job)
      const counts = this.counts(job)
      const failed = job.items.find((i) => i.state === "failed")
      if (failed) job.error = job.error ?? failed.error
      this.end(
        job,
        counts.itemsFailed > 0
          ? "failed"
          : counts.itemsTotal > 0 && counts.itemsSkipped === counts.itemsTotal
            ? "skipped"
            : "completed"
      )
      return
    }
    this.report(job, dest.speed)
    this.schedule(job)
  }

  /** Conflicts and access for an item about to start; false when it already ended (skipped, failed). */
  private begin(job: MockJob, item: MockItem, source: MockRoot, dest: MockRoot): boolean {
    if (dest.readOnly) {
      item.state = "failed"
      item.error = { code: "ACCESS_DENIED", message: "The destination is read-only" }
      return false
    }
    if (item.kind !== "file") return true
    if (
      !walk(source.root, item.sourcePath) &&
      source.root.children &&
      !this.isIncoming(job, item)
    ) {
      item.state = "failed"
      item.error = { code: "NOT_FOUND", message: `${item.sourcePath} does not exist` }
      return false
    }
    const folder = walk(dest.root, parentOf(item.destPath))
    const existing = folder?.children?.find(
      (c) => c.name.toLowerCase() === nameOf(item.destPath).toLowerCase()
    )
    if (!existing) return true
    const policy: ConflictPolicy = existing.children
      ? "rename"
      : item.conflict && item.conflict !== "ask"
        ? item.conflict
        : job.options.conflict !== "ask"
          ? job.options.conflict
          : "rename"
    if (policy === "skip") {
      item.state = "skipped"
      return false
    }
    if (policy === "rename") {
      item.destPath = join(
        parentOf(item.destPath),
        firstFreeName(nameOf(item.destPath), folder?.children?.map((c) => c.name) ?? [])
      )
    }
    return true
  }

  private isIncoming(job: MockJob, item: MockItem) {
    return job.options.sources[item.source].location.kind === "incoming"
  }

  private finishItem(job: MockJob, item: MockItem, source: MockRoot, dest: MockRoot) {
    const node = this.isIncoming(job, item)
      ? source.root.children?.[Number(item.sourcePath)]
      : walk(source.root, item.sourcePath)
    const folder = walk(dest.root, parentOf(item.destPath))
    if (!folder?.children || (!node && item.kind !== "folder")) {
      item.state = "failed"
      item.error = { code: "NOT_FOUND", message: `${item.sourcePath} is gone` }
      return
    }
    const name = nameOf(item.destPath)
    if (item.kind === "folder") {
      if (!folder.children.some((c) => c.name === name && c.children)) {
        folder.children.push({ name, children: [], modifiedAt: new Date().toISOString() })
      }
    } else if (item.kind === "rename") {
      const parent = walk(source.root, parentOf(item.sourcePath))
      parent?.children?.splice(parent.children.indexOf(node!), 1)
      node!.name = name
      folder.children.push(node!)
    } else {
      const copy = clone(node!)
      copy.name = name
      copy.readOnly = undefined
      const at = folder.children.findIndex((c) => c.name.toLowerCase() === name.toLowerCase())
      if (at >= 0) folder.children.splice(at, 1, copy)
      else folder.children.push(copy)
      if (job.op === "move" && !this.isIncoming(job, item)) {
        const parent = walk(source.root, parentOf(item.sourcePath))
        parent?.children?.splice(parent.children.indexOf(node!), 1)
      }
      item.bytesDone = item.size
      // XMP sidecar (ADR 0011 "Copy picks"): merged into the shot's sidecar at the destination, else a new
      // `<destination stem>.xmp` (hidden on request); a malformed one is left alone.
      const sidecar =
        job.op === "copy"
          ? job.options.sidecars?.find((x) => x.sourcePath === item.sourcePath)
          : undefined
      if (sidecar) {
        const dot = name.lastIndexOf(".")
        const stem = dot > 0 ? name.slice(0, dot) : name
        const files = folder.children.filter((c) => !c.children)
        const existingName = findSidecar(
          stem,
          [name],
          files.map((c) => c.name)
        )
        const existing = existingName ? files.find((c) => c.name === existingName) : undefined
        const text = writeMockXmp(
          existing
            ? (existing.text ?? new TextDecoder().decode(existing.bytes ?? new Uint8Array()))
            : null,
          sidecar,
          `Hoard ${__APP_VERSION__}`,
          new Date().toISOString()
        )
        if (text !== null) {
          const file: MockFile = existing ?? {
            name: job.options.hiddenSidecars ? `.${stem}.xmp` : `${stem}.xmp`,
          }
          file.text = text
          file.bytes = undefined
          file.size = new TextEncoder().encode(text).length
          file.modifiedAt = new Date().toISOString()
          if (!existing) folder.children.push(file)
        }
      }
    }
    item.state = "completed"
    this.report(job)
  }

  private cleanUpMoved(job: MockJob) {
    const folders = job.items.filter((i) => i.kind === "folder").reverse()
    for (const folder of folders) {
      const root = this.rootOf(job.options.sources[folder.source].location)
      const node = walk(root.root, folder.sourcePath)
      if (node?.children?.length === 0) {
        const parent = walk(root.root, parentOf(folder.sourcePath))
        parent?.children?.splice(parent.children.indexOf(node), 1)
      }
    }
  }

  private stop(job: MockJob, error: { code: HoardSmbErrorCode; message: string }) {
    for (const item of job.items) {
      if (!isTerminal(item.state) && !item.superseded) {
        item.state = "failed"
        item.error = error
      }
    }
    job.error = error
    this.end(job, "failed")
  }

  private end(job: MockJob, state: TransferState) {
    clearTimeout(job.timer)
    job.timer = undefined
    job.state = state
    job.currentName = undefined
    job.finishedAt = new Date().toISOString()
    if (state !== "failed" && state !== "cancelled") job.error = undefined
    this.report(job)
  }
}

function isSameOrInside(parent: string, child: string) {
  const p = split(parent).join("/")
  const c = split(child).join("/")
  return p === "" || c === p || c.startsWith(`${p}/`)
}
