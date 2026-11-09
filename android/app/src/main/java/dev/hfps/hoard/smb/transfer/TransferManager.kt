package dev.hfps.hoard.smb.transfer

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.hfps.hoard.smb.CredentialStore
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.smb.SafDestinations
import dev.hfps.hoard.smb.SmbConnector
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.smbFail
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.CopyOnWriteArraySet

/** Contract `TransferProgressEvent`. */
data class TransferProgress(val job: TransferJob, val bytesPerSecond: Long?)

/** What `startDownload` asks for. */
data class StartDownloadRequest(
    val transferId: String,
    val machineId: String,
    val addresses: List<TransferAddress>,
    val expectedServerGuid: String,
    val share: String,
    val remotePath: String,
    val destTreeUri: String,
    val destName: String?,
    val conflict: String,
    val resumeFromBytes: Long,
)

/**
 * Process-wide owner of transfer jobs: accepts them (`startDownload`), cancels them, answers
 * `getTransfers`, and fans progress out to the plugin. The bytes move in [TransferWorker].
 */
class TransferManager private constructor(context: Context) {

    val appContext: Context = context.applicationContext
    val store = TransferStore(appContext)
    val destinations = SafDestinations(appContext)
    val credentials = CredentialStore(appContext)

    /** At most two downloads move bytes at the same time; the others wait in `queued`. */
    val slots = Semaphore(MAX_CONCURRENT)

    private val listeners = CopyOnWriteArraySet<(TransferProgress) -> Unit>()

    fun addListener(listener: (TransferProgress) -> Unit) = listeners.add(listener)
    fun removeListener(listener: (TransferProgress) -> Unit) = listeners.remove(listener)

    fun emit(job: TransferJob, bytesPerSecond: Long? = null) {
        val event = TransferProgress(job, bytesPerSecond)
        listeners.forEach { runCatching { it(event) } }
    }

    /**
     * Validate, resolve the destination name per [StartDownloadRequest.conflict], create (or reuse,
     * when resuming) the destination document, persist the job and enqueue its worker.
     */
    @Synchronized
    fun start(request: StartDownloadRequest): TransferJob {
        if (!DestinationRules.acceptsNew(store.get(request.transferId))) {
            smbFail(ErrorCode.UNKNOWN, "Transfer ${request.transferId} is already running")
        }
        if (request.addresses.isEmpty()) smbFail(ErrorCode.UNREACHABLE, "No addresses given")
        val guid = SmbConnector.normalizeGuid(request.expectedServerGuid)
            ?: smbFail(ErrorCode.UNKNOWN, "expectedServerGuid is required for transfers")
        val remotePath = SmbPaths.normalize(request.remotePath)
        if (remotePath.isEmpty()) smbFail(ErrorCode.UNKNOWN, "remotePath must be a file")
        val tree = destinations.requireWritableTree(request.destTreeUri)
        val wanted = SmbPaths.requireValidName(request.destName ?: SmbPaths.name(remotePath), "destName")
        val now = IsoTime.now()
        val base = TransferJob(
            transferId = request.transferId,
            machineId = request.machineId,
            addresses = request.addresses,
            expectedServerGuid = guid,
            share = request.share,
            remotePath = remotePath,
            destTreeUri = request.destTreeUri,
            destName = wanted,
            destUri = null,
            startedAt = now,
        )
        val decision = DestinationRules.plan(
            wanted, request.conflict, request.resumeFromBytes, destinations.children(tree),
            nameOf = { it.name }, sizeOf = { it.size },
        )
        return when (decision) {
            // Retry of an interrupted job: same document, append when the size matches, else from 0.
            is DestinationRules.Decision.Resume -> accept(
                base.copy(
                    destUri = decision.document.uri.toString(),
                    resumeFromBytes = decision.fromBytes,
                    bytesDone = decision.fromBytes,
                ),
            )
            is DestinationRules.Decision.Skip -> {
                val job = base.copy(destUri = decision.document.uri.toString(), state = TransferStates.SKIPPED, finishedAt = now)
                store.put(job)
                emit(job)
                job
            }
            is DestinationRules.Decision.Overwrite -> accept(base.copy(destUri = decision.document.uri.toString()))
            is DestinationRules.Decision.Create -> {
                val created = destinations.create(tree, decision.name)
                accept(base.copy(destName = created.name, destUri = created.uri.toString(), createdDocument = true))
            }
        }
    }

    private fun accept(job: TransferJob): TransferJob {
        store.put(job)
        emit(job)
        enqueue(job.transferId, ExistingWorkPolicy.REPLACE)
        return job
    }

    private fun enqueue(transferId: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(workDataOf(TransferWorker.KEY_TRANSFER_ID to transferId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(workName(transferId), policy, request)
    }

    /** Cancel a queued/running job: state `cancelled`, partial file deleted. Idempotent. */
    fun cancel(transferId: String) {
        val job = synchronized(this) {
            val current = store.get(transferId) ?: return
            if (current.isTerminal) return
            store.update(transferId) {
                it.copy(
                    state = TransferStates.CANCELLED,
                    errorCode = ErrorCode.CANCELLED,
                    errorMessage = "Cancelled",
                    finishedAt = IsoTime.now(),
                )
            } ?: return
        }
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(transferId))
        job.destUri?.let { destinations.delete(Uri.parse(it)) }
        TransferNotifications.cancelAll(appContext, transferId)
        emit(job)
    }

    fun all(): List<TransferJob> = store.all()

    fun acknowledge(transferIds: Collection<String>) {
        store.remove(transferIds, onlyTerminal = true)
    }

    /**
     * After a process restart: make sure every unfinished job still has a worker (KEEP leaves a
     * pending or running one alone). Runs once per process.
     */
    @Synchronized
    fun reconcile() {
        if (reconciled) return
        reconciled = true
        store.all().filter { !it.isTerminal }.forEach { enqueue(it.transferId, ExistingWorkPolicy.KEEP) }
    }

    private var reconciled = false

    /** Final state of a job (from the worker). Ignored if the job was cancelled meanwhile. */
    @Synchronized
    fun finish(transferId: String, state: String, error: HoardSmbException? = null): TransferJob? {
        val current = store.get(transferId) ?: return null
        if (current.isTerminal) return null
        return store.update(transferId) {
            it.copy(
                state = state,
                bytesDone = if (state == TransferStates.COMPLETED) it.totalBytes else it.bytesDone,
                errorCode = error?.code,
                errorMessage = error?.message,
                finishedAt = IsoTime.now(),
            )
        }
    }

    companion object {
        const val MAX_CONCURRENT = 2
        const val WORK_TAG = "hoard-transfer"

        fun workName(transferId: String) = "hoard-transfer-$transferId"

        @Volatile private var instance: TransferManager? = null

        fun get(context: Context): TransferManager =
            instance ?: synchronized(this) { instance ?: TransferManager(context).also { instance = it } }
    }
}
