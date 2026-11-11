import type { Db } from "@/db/db"
import { applyTransferProgress, createTransfer, transferSourcePaths } from "@/db/queries"
import type { TransferRecord } from "@/db/types"
import type {
  ConflictPolicy,
  HoardSmbPlugin,
  NativeTransfer,
  TransferConflict,
  TransferLocation,
  TransferOp,
  TransferProgressEvent,
  TransferSidecar,
  TransferSourceLocation,
  TransferState,
} from "@/plugins/smb/definitions"
import { smbErrorCode, TERMINAL_TRANSFER_STATES } from "@/plugins/smb/definitions"

/**
 * Keeps the `transfers` table in step with the native transfer service (ADR 0003, ADR 0007) and is
 * the one entry point for moving bytes between locations (copy, move, upload, download, "Save to…"
 * from another app, and phase 2b's "copy picks"):
 *  - `transfer` records a row, then starts the native job with the same id;
 *  - `transferProgress` events update an in-memory live view at full rate (progress bars, speed,
 *    active host, current file) and the database at most once a second per job, plus every state
 *    change; finished jobs are acknowledged natively once the database has them;
 *  - `reconcile` (app start and resume) applies `getTransfers()` and fails rows native no longer
 *    knows, so they can be retried (`retry` runs the failed items again, resuming partial files).
 */

export type SmbForTransfers = Pick<
  HoardSmbPlugin,
  | "startTransfer"
  | "resolveTransferConflicts"
  | "cancelTransfer"
  | "getTransfers"
  | "getTransferItems"
  | "acknowledgeTransfers"
  | "addListener"
>

/** A row's live view: the latest native event. */
export type LiveTransfer = Omit<TransferProgressEvent, "transferId">

/**
 * A place in app terms (ADR 0005 ids), turned into a native {@link TransferLocation} only when a job
 * starts, so SMB endpoints carry the route that is current then:
 *  - a machine id with `share`; `device`; `vol-<uuid>`;
 *  - `tree` with `treeUri` (a SAF folder from `pickDestinationFolder`);
 *  - `incoming` with `shareId` (files another app shared; source only).
 */
export interface TransferPlace {
  locationId: string
  share?: string
  treeUri?: string
  shareId?: string
}

export const TREE_LOCATION_ID = "tree"
/** The error message of rows native no longer reports (the app or phone stopped meanwhile). */
export const INTERRUPTED = "Interrupted"
export const INCOMING_LOCATION_ID = "incoming"

export interface TransferRequest {
  op: TransferOp
  source: TransferPlace
  /** Files and folders in the source (incoming: the shared items' indexes). */
  paths: string[]
  /** What the row shows: the single item's name, or the first one's. */
  name: string
  /** Bytes known up front (selected files); native reports the real total after its scan. */
  size?: number
  /** Files known up front (folders count once until the scan). */
  itemsTotal?: number
  /** The folder the sources land in. */
  destination: TransferPlace & { path: string }
  conflict: TransferConflict
  /** XMP sidecars to write at the destination next to copied files (ADR 0011, copies only). */
  sidecars?: TransferSidecar[]
  /** New sidecars are hidden (`.<stem>.xmp`). */
  hiddenSidecars?: boolean
}

/** Resolves a place to what native needs (the provider uses the route engine for machines). */
export type PlaceResolver = (place: TransferPlace) => Promise<TransferSourceLocation>

const isTerminal = (state: TransferState) => TERMINAL_TRANSFER_STATES.includes(state)
const DB_WRITE_INTERVAL_MS = 1000

/** The place of a row's source or destination. */
export function placeOf(row: TransferRecord, side: "source" | "dest"): TransferPlace {
  if (side === "source") {
    return {
      locationId: row.source_location_id,
      share: row.source_share || undefined,
      ...(row.source_location_id === INCOMING_LOCATION_ID
        ? { shareId: row.source_share || undefined }
        : {}),
    }
  }
  return {
    locationId: row.dest_location_id,
    share: row.dest_share || undefined,
    treeUri: row.dest_tree_uri ?? undefined,
  }
}

export class TransferService {
  private readonly live = new Map<string, LiveTransfer>()
  private readonly lastWrite = new Map<string, number>()
  private readonly pendingWrite = new Map<string, ReturnType<typeof setTimeout>>()
  private readonly listeners = new Set<() => void>()
  private version = 0
  private started: Promise<void> | null = null
  /** Rows created from now on belong to this app session; native may not have them yet. */
  private readonly sessionStart: number

  private readonly smb: SmbForTransfers
  private readonly db: Db
  private readonly resolve: PlaceResolver
  private readonly now: () => number

  constructor(smb: SmbForTransfers, db: Db, resolve: PlaceResolver, now: () => number = Date.now) {
    this.smb = smb
    this.db = db
    this.resolve = resolve
    this.now = now
    this.sessionStart = now()
  }

  // --- Store API --------------------------------------------------------------------------------

  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  getVersion = () => this.version

  getLive = (id: string): LiveTransfer | undefined => this.live.get(id)

  private emit() {
    this.version++
    this.listeners.forEach((listener) => listener())
  }

  // --- Lifecycle --------------------------------------------------------------------------------

  /** Listen to progress and reconcile once. Idempotent. */
  start(): Promise<void> {
    this.started ??= (async () => {
      await this.smb.addListener("transferProgress", (event) => void this.onProgress(event))
      await this.reconcile()
    })()
    return this.started
  }

  async onProgress(event: TransferProgressEvent): Promise<void> {
    const previous = this.live.get(event.transferId)
    const { transferId: _id, ...rest } = event
    this.live.set(event.transferId, {
      ...rest,
      bytesPerSecond:
        event.bytesPerSecond ?? (event.state === "running" ? previous?.bytesPerSecond : undefined),
      activeHost: event.activeHost ?? previous?.activeHost,
    })
    this.emit()
    const stateChanged = previous?.state !== event.state
    const due = this.now() - (this.lastWrite.get(event.transferId) ?? 0) >= DB_WRITE_INTERVAL_MS
    if (stateChanged || due || isTerminal(event.state)) {
      await this.write(event)
    } else if (!this.pendingWrite.has(event.transferId)) {
      // Make sure the last progress of a burst lands in the database too.
      this.pendingWrite.set(
        event.transferId,
        setTimeout(() => {
          this.pendingWrite.delete(event.transferId)
          const latest = this.live.get(event.transferId)
          if (latest) void this.write({ transferId: event.transferId, ...latest })
        }, DB_WRITE_INTERVAL_MS)
      )
    }
  }

  private async write(event: TransferProgressEvent) {
    clearTimeout(this.pendingWrite.get(event.transferId))
    this.pendingWrite.delete(event.transferId)
    this.lastWrite.set(event.transferId, this.now())
    await applyTransferProgress(this.db, event)
    if (isTerminal(event.state)) {
      await this.smb.acknowledgeTransfers({ transferIds: [event.transferId] }).catch(() => {})
    }
  }

  /** Apply what native knows; fail rows native lost (app killed before it persisted, data cleared). */
  async reconcile(): Promise<void> {
    const { transfers } = await this.smb.getTransfers()
    const rows = await this.db.query<TransferRecord>("SELECT * FROM transfers")
    const byId = new Map(rows.map((row) => [row.id, row]))
    const acknowledged: string[] = []
    await this.db.transaction(async () => {
      for (const job of transfers) {
        if (!byId.has(job.transferId)) {
          // A job the database never recorded (should not happen): forget it once finished.
          if (isTerminal(job.state)) acknowledged.push(job.transferId)
          continue
        }
        const event = toEvent(job)
        await applyTransferProgress(this.db, event)
        const { transferId: _id, ...live } = event
        this.live.set(job.transferId, live)
        if (isTerminal(job.state)) acknowledged.push(job.transferId)
      }
      const known = new Set(transfers.map((job) => job.transferId))
      for (const row of rows) {
        if (known.has(row.id) || isTerminal(row.state)) continue
        // Started in this session (native may not have it yet): leave it. Anything older that
        // native does not report was lost with the process.
        if (Date.parse(row.created_at) >= this.sessionStart) continue
        await applyTransferProgress(this.db, {
          ...rowEvent(row),
          state: "failed",
          error: { code: "UNKNOWN", message: INTERRUPTED },
        })
        this.live.delete(row.id)
      }
    })
    if (acknowledged.length) {
      await this.smb.acknowledgeTransfers({ transferIds: acknowledged }).catch(() => {})
    }
    this.emit()
  }

  // --- Actions ----------------------------------------------------------------------------------

  /** Record the row, then start the native job (same id). Rejects (and fails the row) when it cannot start. */
  async transfer(request: TransferRequest): Promise<string> {
    const { source, destination } = request
    const id = await createTransfer(this.db, {
      op: request.op,
      sourceLocationId: source.locationId,
      sourceMachineId: isMachine(source.locationId) ? source.locationId : null,
      sourceShare:
        source.locationId === INCOMING_LOCATION_ID ? (source.shareId ?? "") : (source.share ?? ""),
      sourcePaths: request.paths,
      destLocationId: destination.locationId,
      destMachineId: isMachine(destination.locationId) ? destination.locationId : null,
      destShare: destination.share ?? "",
      destPath: destination.path,
      destTreeUri: destination.treeUri ?? null,
      name: request.name,
      conflict: request.conflict,
      size: request.size ?? 0,
      itemsTotal: request.itemsTotal ?? request.paths.length,
    })
    await this.run(
      id,
      request.op,
      source,
      request.paths,
      destination,
      request.conflict,
      false,
      request.sidecars,
      request.hiddenSidecars
    )
    return id
  }

  private async run(
    id: string,
    op: TransferOp,
    source: TransferPlace,
    paths: string[],
    destination: TransferPlace & { path: string },
    conflict: TransferConflict,
    resume: boolean,
    sidecars?: TransferSidecar[],
    hiddenSidecars?: boolean
  ) {
    this.live.set(id, {
      ...(this.live.get(id) ?? emptyLive()),
      state: "queued",
      error: undefined,
    })
    this.emit()
    try {
      const [from, to] = await Promise.all([this.resolve(source), this.resolve(destination)])
      await this.smb.startTransfer({
        transferId: id,
        op,
        sources: paths.map((path) => ({ location: from, path })),
        destination: { location: to as TransferLocation, path: destination.path },
        conflict,
        resume,
        ...(sidecars?.length ? { sidecars, ...(hiddenSidecars ? { hiddenSidecars } : {}) } : {}),
      })
    } catch (error) {
      await this.markFailed(id, error)
      throw error
    }
  }

  /** Fail a row whose native job could not start. */
  private async markFailed(id: string, error: unknown) {
    const [row] = await this.db.query<TransferRecord>("SELECT * FROM transfers WHERE id = ?", [id])
    this.live.delete(id)
    this.emit()
    if (!row) return
    await applyTransferProgress(this.db, {
      ...rowEvent(row),
      state: "failed",
      error: {
        code: smbErrorCode(error),
        message: error instanceof Error ? error.message : String(error),
      },
    })
  }

  /**
   * Run a failed or cancelled job again under the same id: completed items stay, the others run
   * again, partial files resume where the bytes still match (ADR 0007). When native lost the job
   * it scans again and counts same-size files already at the destination as copied.
   */
  async retry(row: TransferRecord): Promise<void> {
    await applyTransferProgress(this.db, { ...rowEvent(row), state: "queued" })
    await this.run(
      row.id,
      row.op,
      placeOf(row, "source"),
      transferSourcePaths(row),
      { ...placeOf(row, "dest"), path: row.dest_path },
      row.conflict,
      true
    )
  }

  async cancel(id: string): Promise<void> {
    await this.smb.cancelTransfer({ transferId: id })
  }

  /** Answer a job waiting in `conflict` ("apply to all", or per item). */
  async resolveConflicts(
    id: string,
    answer: { all?: ConflictPolicy; items?: { index: number; policy: ConflictPolicy }[] }
  ): Promise<void> {
    await this.smb.resolveTransferConflicts({ transferId: id, ...answer })
  }

  items(...args: Parameters<SmbForTransfers["getTransferItems"]>) {
    return this.smb.getTransferItems(...args)
  }
}

/** Machine ids are anything that is not a local or special location id. */
export function isMachine(locationId: string) {
  return !(
    locationId === "device" ||
    locationId.startsWith("vol-") ||
    locationId === TREE_LOCATION_ID ||
    locationId === INCOMING_LOCATION_ID
  )
}

function emptyLive(): LiveTransfer {
  return {
    state: "queued",
    bytesDone: 0,
    totalBytes: 0,
    itemsTotal: 0,
    itemsDone: 0,
    itemsFailed: 0,
    itemsSkipped: 0,
  }
}

function rowEvent(row: TransferRecord): TransferProgressEvent {
  return {
    transferId: row.id,
    state: row.state,
    bytesDone: row.bytes_done,
    totalBytes: row.size,
    itemsTotal: row.items_total,
    itemsDone: row.items_done,
    itemsFailed: row.items_failed,
    itemsSkipped: row.items_skipped,
  }
}

function toEvent(job: NativeTransfer): TransferProgressEvent {
  const { op: _op, startedAt: _s, finishedAt: _f, ...event } = job
  return event
}
