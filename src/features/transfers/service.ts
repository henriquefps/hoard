import type { Db } from "@/db/db"
import { applyTransferProgress, createTransfer, setTransferDestination } from "@/db/queries"
import type { TransferRecord } from "@/db/types"
import type {
  ConflictPolicy,
  HoardSmbErrorCode,
  HoardSmbPlugin,
  NativeTransfer,
  SmbAddress,
  TransferProgressEvent,
  TransferState,
} from "@/plugins/smb/definitions"
import { smbErrorCode, TERMINAL_TRANSFER_STATES } from "@/plugins/smb/definitions"

/**
 * Keeps the `transfers` table in step with the native transfer service (ADR 0003):
 *  - `transferProgress` events update an in-memory live view at full rate (progress bars, speed,
 *    active host) and the database at most once a second per job, plus every state change;
 *  - finished jobs are acknowledged natively once the database has them;
 *  - `reconcile` (app start and resume) applies `getTransfers()` and marks rows native no longer
 *    knows as failed, so they can be retried with `resumeFromBytes`.
 */

export type SmbForTransfers = Pick<
  HoardSmbPlugin,
  "startDownload" | "cancelTransfer" | "getTransfers" | "acknowledgeTransfers" | "addListener"
>

export interface LiveTransfer {
  bytesDone: number
  totalBytes: number
  state: TransferState
  bytesPerSecond?: number
  activeHost?: string
  error?: { code: HoardSmbErrorCode; message: string }
}

export interface DownloadFile {
  share: string
  /** Path of the file inside the share. */
  path: string
  name: string
  size: number
}

export interface DownloadRequest {
  machineId: string
  files: DownloadFile[]
  /** Priority order, the route in use first. */
  addresses: SmbAddress[]
  expectedServerGuid: string
  destTreeUri: string
  conflict: ConflictPolicy
}

export interface DownloadOutcome {
  started: number
  failed: { name: string; code: HoardSmbErrorCode }[]
}

const isTerminal = (state: TransferState) => TERMINAL_TRANSFER_STATES.includes(state)
const DB_WRITE_INTERVAL_MS = 1000

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
  private readonly now: () => number

  constructor(smb: SmbForTransfers, db: Db, now: () => number = Date.now) {
    this.smb = smb
    this.db = db
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
    this.live.set(event.transferId, {
      bytesDone: event.bytesDone,
      totalBytes: event.totalBytes,
      state: event.state,
      bytesPerSecond:
        event.bytesPerSecond ?? (event.state === "running" ? previous?.bytesPerSecond : undefined),
      activeHost: event.activeHost ?? previous?.activeHost,
      error: event.error,
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

  /** Apply what native knows; fail rows native lost (app killed, phone rebooted). */
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
        await applyTransferProgress(this.db, toEvent(job))
        if (job.destUri) {
          await setTransferDestination(this.db, job.transferId, job.destName, job.destUri)
        }
        this.live.set(job.transferId, {
          bytesDone: job.bytesDone,
          totalBytes: job.totalBytes,
          state: job.state,
          activeHost: job.activeHost,
          error: job.error,
        })
        if (isTerminal(job.state)) acknowledged.push(job.transferId)
      }
      const known = new Set(transfers.map((job) => job.transferId))
      for (const row of rows) {
        if (known.has(row.id) || isTerminal(row.state)) continue
        // Started in this session (native may not have it yet): leave it. Anything older that
        // native does not know was lost with the process (app killed, phone rebooted).
        if (Date.parse(row.created_at) >= this.sessionStart) continue
        await applyTransferProgress(this.db, {
          transferId: row.id,
          bytesDone: row.bytes_done,
          totalBytes: row.size,
          state: "failed",
          error: { code: "UNKNOWN", message: "Interrupted" },
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

  /** Record a row per file, then start each native job (the row exists first, same id). */
  async download(request: DownloadRequest): Promise<DownloadOutcome> {
    const outcome: DownloadOutcome = { started: 0, failed: [] }
    for (const file of request.files) {
      const id = await createTransfer(this.db, {
        machineId: request.machineId,
        share: file.share,
        remotePath: file.path,
        destTreeUri: request.destTreeUri,
        destName: file.name,
        size: file.size,
      })
      try {
        await this.startJob(id, request, file)
        outcome.started++
      } catch (error) {
        outcome.failed.push({ name: file.name, code: smbErrorCode(error) })
        await this.markFailed(id, file.size, error)
      }
    }
    return outcome
  }

  private async startJob(
    id: string,
    request: Omit<DownloadRequest, "files">,
    file: DownloadFile,
    resumeFromBytes?: number,
    destName?: string
  ) {
    this.live.set(id, { bytesDone: resumeFromBytes ?? 0, totalBytes: file.size, state: "queued" })
    this.emit()
    const result = await this.smb.startDownload({
      transferId: id,
      machineId: request.machineId,
      addresses: request.addresses,
      expectedServerGuid: request.expectedServerGuid,
      share: file.share,
      remotePath: file.path,
      destTreeUri: request.destTreeUri,
      destName: destName ?? file.name,
      conflict: request.conflict,
      resumeFromBytes,
    })
    await setTransferDestination(this.db, id, result.destName, result.destUri)
  }

  private async markFailed(id: string, size: number, error: unknown) {
    this.live.delete(id)
    this.emit()
    await applyTransferProgress(this.db, {
      transferId: id,
      bytesDone: 0,
      totalBytes: size,
      state: "failed",
      error: {
        code: smbErrorCode(error),
        message: error instanceof Error ? error.message : String(error),
      },
    })
  }

  /**
   * Start a failed or cancelled transfer again under the same id, resuming from the bytes already
   * written (native restarts at 0 if the partial file does not match). The destination file is the
   * transfer's own, so it is overwritten rather than renamed.
   */
  async retry(
    row: TransferRecord,
    route: { addresses: SmbAddress[]; expectedServerGuid: string }
  ): Promise<void> {
    await applyTransferProgress(this.db, {
      transferId: row.id,
      bytesDone: row.bytes_done,
      totalBytes: row.size,
      state: "queued",
    })
    try {
      await this.startJob(
        row.id,
        {
          machineId: row.machine_id,
          addresses: route.addresses,
          expectedServerGuid: route.expectedServerGuid,
          destTreeUri: row.dest_tree_uri,
          conflict: "overwrite",
        },
        {
          share: row.share,
          path: row.remote_path,
          name: row.dest_name,
          size: row.size,
        },
        row.state === "cancelled" ? 0 : row.bytes_done,
        row.dest_name
      )
    } catch (error) {
      await this.markFailed(row.id, row.size, error)
      throw error
    }
  }

  async cancel(id: string): Promise<void> {
    await this.smb.cancelTransfer({ transferId: id })
  }
}

function toEvent(job: NativeTransfer): TransferProgressEvent {
  return {
    transferId: job.transferId,
    bytesDone: job.bytesDone,
    totalBytes: job.totalBytes,
    state: job.state,
    error: job.error,
    activeHost: job.activeHost,
  }
}
