package dev.hfps.hoard.smb.transfer

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.hfps.hoard.smb.CredentialStore
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.smbFail
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.CopyOnWriteArraySet

/** Contract `TransferProgressEvent`. */
data class TransferProgress(val job: TransferJob, val bytesPerSecond: Long?)

/** What `startTransfer` asks for. */
data class StartTransferRequest(
    val transferId: String,
    val op: String,
    val sources: List<TransferSource>,
    val destination: Endpoint,
    val destPath: String,
    val conflict: String,
    val resume: Boolean,
    /** Source path → XMP sidecar content (ADR 0009). */
    val sidecars: Map<String, String> = emptyMap(),
)

/**
 * Process-wide owner of transfer jobs (ADR 0007): accepts them (`startTransfer`), answers
 * conflicts, cancels, answers `getTransfers` / `getTransferItems`, and fans progress out to the
 * plugin. The bytes move in [TransferWorker] → [TransferRunner].
 */
class TransferManager private constructor(context: Context) {

    val appContext: Context = context.applicationContext
    val store = TransferStore(appContext)
    val credentials = CredentialStore(appContext)
    val endpoints by lazy { TransferEndpoints(appContext, credentials) }

    /** At most two jobs move bytes at the same time; the others wait in `queued`. */
    val slots = Semaphore(MAX_CONCURRENT)

    private val listeners = CopyOnWriteArraySet<(TransferProgress) -> Unit>()

    fun addListener(listener: (TransferProgress) -> Unit) = listeners.add(listener)
    fun removeListener(listener: (TransferProgress) -> Unit) = listeners.remove(listener)

    fun emit(job: TransferJob, bytesPerSecond: Long? = null) {
        val event = TransferProgress(job, bytesPerSecond)
        listeners.forEach { runCatching { it(event) } }
    }

    /** Validate, persist and enqueue a job (or, with `resume`, run a known job's unfinished items again). */
    @Synchronized
    fun start(request: StartTransferRequest): TransferJob {
        val existing = store.get(request.transferId)
        if (existing != null && !existing.isTerminal) smbFail(ErrorCode.UNKNOWN, "Transfer ${request.transferId} is already running")
        if (request.op != "copy" && request.op != "move") smbFail(ErrorCode.UNKNOWN, "op must be copy or move")
        if (request.conflict !in Conflicts.ALL) smbFail(ErrorCode.UNKNOWN, "conflict must be ask, rename, overwrite or skip")
        if (request.destination is Endpoint.Incoming) smbFail(ErrorCode.UNKNOWN, "Shared files are not a destination")
        if (request.sources.isEmpty()) smbFail(ErrorCode.UNKNOWN, "No sources")
        if (request.sidecars.isNotEmpty() && request.op != "copy") smbFail(ErrorCode.UNKNOWN, "Sidecars are written by copies only")
        if (request.sidecars.size > MAX_SIDECARS || request.sidecars.values.any { it.length > MAX_SIDECAR_CHARS }) {
            smbFail(ErrorCode.UNKNOWN, "At most $MAX_SIDECARS sidecars of $MAX_SIDECAR_CHARS characters")
        }
        val destPath = SmbPaths.normalize(request.destPath)
        for (source in request.sources) {
            val path = SmbPaths.normalize(source.path)
            if (source.endpoint !is Endpoint.Incoming && path.isEmpty()) smbFail(ErrorCode.UNKNOWN, "A location's root cannot be copied as a whole")
            if (source.endpoint.key == request.destination.key && TransferPaths.isSameOrInside(path, destPath)) {
                smbFail(ErrorCode.UNKNOWN, "${SmbPaths.name(path)} cannot go into itself")
            }
        }

        if (existing != null && request.resume) {
            synchronized(existing) {
                existing.items.forEach {
                    if (it.state == TransferStates.FAILED || it.state == TransferStates.CANCELLED) {
                        it.state = TransferStates.QUEUED
                        it.errorCode = null
                        it.errorMessage = null
                    }
                }
            }
            existing.state = TransferStates.QUEUED
            existing.errorCode = null
            existing.errorMessage = null
            existing.finishedAt = null
            existing.acknowledged = false
            existing.spaceChecked = false
            store.put(existing)
            emit(existing)
            enqueue(existing, ExistingWorkPolicy.REPLACE)
            return existing
        }

        val job = TransferJob(
            transferId = request.transferId,
            op = request.op,
            sources = request.sources.map { it.copy(path = if (it.endpoint is Endpoint.Incoming) it.path else SmbPaths.normalize(it.path)) },
            destination = request.destination,
            destPath = destPath,
            conflict = request.conflict,
            startedAt = IsoTime.now(),
            resumeExisting = request.resume,
            sidecars = request.sidecars.mapKeys { SmbPaths.normalize(it.key) },
        )
        store.put(job)
        emit(job)
        enqueue(job, ExistingWorkPolicy.REPLACE)
        return job
    }

    /** Answers for a job waiting in `conflict`; it is queued again. */
    @Synchronized
    fun resolveConflicts(transferId: String, all: String?, perItem: Map<Int, String>) {
        val job = store.get(transferId) ?: smbFail(ErrorCode.NOT_FOUND, "No transfer $transferId")
        if (all != null && all !in Conflicts.POLICIES) smbFail(ErrorCode.UNKNOWN, "all must be rename, overwrite or skip")
        if (perItem.values.any { it !in Conflicts.POLICIES }) smbFail(ErrorCode.UNKNOWN, "Invalid policy")
        synchronized(job) {
            job.items.forEach { item ->
                if (item.conflict != Conflicts.ASK || item.isFinished) return@forEach
                item.conflict = perItem[item.index] ?: all ?: return@forEach
            }
        }
        if (job.state != TransferStates.CONFLICT || job.counts().conflicts > 0) {
            store.save(job)
            return
        }
        job.state = TransferStates.QUEUED
        store.save(job)
        emit(job)
        enqueue(job, ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(job: TransferJob, policy: ExistingWorkPolicy) {
        val network = job.sources.any { it.endpoint.isNetwork } || job.destination.isNetwork
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(workDataOf(TransferWorker.KEY_TRANSFER_ID to job.transferId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .apply { if (network) setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()) }
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(workName(job.transferId), policy, request)
    }

    /** Cancel a queued/running job: state `cancelled`, unfinished items cancelled. Idempotent. */
    fun cancel(transferId: String) {
        val job = synchronized(this) {
            val current = store.get(transferId) ?: return
            if (current.isTerminal) return
            synchronized(current) {
                current.items.forEach { if (!it.isFinished && !it.superseded) it.state = TransferStates.CANCELLED }
            }
            current.state = TransferStates.CANCELLED
            current.errorCode = ErrorCode.CANCELLED
            current.errorMessage = "Cancelled"
            current.finishedAt = IsoTime.now()
            store.save(current)
            current
        }
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(transferId))
        TransferNotifications.cancelAll(appContext, transferId)
        emit(job)
    }

    /** Jobs the UI has not acknowledged (running ones, and finished ones it has not stored yet). */
    fun reported(): List<TransferJob> = store.all().filter { !it.acknowledged || !it.isTerminal }

    fun acknowledge(transferIds: Collection<String>) {
        transferIds.forEach { id ->
            val job = store.get(id) ?: return@forEach
            if (job.isTerminal && !job.acknowledged) {
                job.acknowledged = true
                store.save(job)
            }
        }
    }

    /**
     * After a process restart: make sure every unfinished job has a worker (KEEP leaves a pending
     * or running one alone). Jobs waiting for a conflict answer stay put. Runs once per process.
     */
    @Synchronized
    fun reconcile() {
        if (reconciled) return
        reconciled = true
        store.all().filter { !it.isTerminal && it.state != TransferStates.CONFLICT }.forEach { enqueue(it, ExistingWorkPolicy.KEEP) }
    }

    private var reconciled = false

    companion object {
        const val MAX_CONCURRENT = 2
        /** One XMP per copied shot; a 5,000-shot card is more than anyone picks. */
        const val MAX_SIDECARS = 5000
        const val MAX_SIDECAR_CHARS = 16 * 1024
        const val WORK_TAG = "hoard-transfer"

        fun workName(transferId: String) = "hoard-transfer-$transferId"

        @Volatile private var instance: TransferManager? = null

        fun get(context: Context): TransferManager =
            instance ?: synchronized(this) { instance ?: TransferManager(context).also { instance = it } }
    }
}
