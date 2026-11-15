package dev.hfps.hoard.app.transfers

import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.data.TransferDao
import dev.hfps.hoard.data.TransferEntity
import dev.hfps.hoard.smb.transfer.Endpoint
import dev.hfps.hoard.smb.transfer.StartTransferRequest
import dev.hfps.hoard.smb.transfer.TransferJob
import dev.hfps.hoard.smb.transfer.TransferProgress
import dev.hfps.hoard.smb.transfer.TransferSource
import dev.hfps.hoard.smb.transfer.TransferStates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.time.Instant
import java.util.UUID

/** A job's live view: the latest native event (`TransferProgressEvent`). */
data class LiveTransfer(
    val state: String,
    val bytesDone: Long = 0,
    val totalBytes: Long = 0,
    val itemsTotal: Int = 0,
    val itemsDone: Int = 0,
    val itemsFailed: Int = 0,
    val itemsSkipped: Int = 0,
    val conflicts: Int = 0,
    val currentName: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val activeHost: String? = null,
    val bytesPerSecond: Long? = null,
) {
    val isTerminal: Boolean get() = TransferStates.isTerminal(state)

    companion object {
        /** What `HoardSmbPlugin.jobFields` reports for a job. */
        fun of(job: TransferJob, bytesPerSecond: Long? = null): LiveTransfer {
            val counts = job.counts()
            val showsError = job.state == TransferStates.FAILED || job.state == TransferStates.RETRYING || job.state == TransferStates.CANCELLED
            return LiveTransfer(
                state = job.state,
                bytesDone = job.bytesDone(),
                totalBytes = job.totalBytes(),
                itemsTotal = counts.total,
                itemsDone = counts.done,
                itemsFailed = counts.failed,
                itemsSkipped = counts.skipped,
                conflicts = counts.conflicts,
                currentName = job.currentName,
                errorCode = job.errorCode?.takeIf { showsError }?.name,
                errorMessage = job.errorCode?.takeIf { showsError }?.let { job.errorMessage ?: it.name },
                activeHost = job.activeHost,
                bytesPerSecond = bytesPerSecond?.takeIf { job.state == TransferStates.RUNNING },
            )
        }
    }
}

/** The native transfer engine as the service needs it ([dev.hfps.hoard.smb.transfer.TransferManager] in the app). */
interface NativeTransfers {
    fun start(request: StartTransferRequest)
    fun cancel(transferId: String)
    fun resolveConflicts(transferId: String, all: String?, perItem: Map<Int, String>)
    fun reported(): List<TransferJob>
    fun acknowledge(transferIds: Collection<String>)
    fun addListener(listener: (TransferProgress) -> Unit)
    fun removeListener(listener: (TransferProgress) -> Unit)
    fun job(transferId: String): TransferJob?
}

/** A transfers row with the live view merged in (src/features/transfers/TransfersProvider.tsx `TransferView`). */
data class TransferView(
    val row: TransferEntity,
    val state: String,
    val live: LiveTransfer?,
    val bytesDone: Long,
    val total: Long,
    val itemsTotal: Int,
    val itemsDone: Int,
    val itemsFailed: Int,
    val itemsSkipped: Int,
    val conflicts: Int,
    val errorCode: String?,
) {
    val active: Boolean get() = !TransferStates.isTerminal(state)

    companion object {
        fun of(row: TransferEntity, live: LiveTransfer?): TransferView {
            // The row wins once it is terminal (the database is the history); the live view otherwise.
            val useLive = live != null && !TransferStates.isTerminal(row.state)
            val l = live.takeIf { useLive }
            return TransferView(
                row = row,
                state = l?.state ?: row.state,
                live = live,
                bytesDone = l?.bytesDone ?: row.bytesDone,
                total = (l?.totalBytes ?: row.size).takeIf { it > 0 } ?: row.size,
                itemsTotal = (l?.itemsTotal ?: row.itemsTotal).takeIf { it > 0 } ?: row.itemsTotal,
                itemsDone = l?.itemsDone ?: row.itemsDone,
                itemsFailed = l?.itemsFailed ?: row.itemsFailed,
                itemsSkipped = l?.itemsSkipped ?: row.itemsSkipped,
                conflicts = l?.conflicts ?: 0,
                errorCode = l?.errorCode ?: row.errorCode,
            )
        }
    }
}

/** The message of rows native no longer reports (the app or phone stopped meanwhile). */
const val INTERRUPTED = "Interrupted"

/**
 * Keeps the `transfers` table in step with the native transfer engine (ADR 0003, ADR 0007), a port of
 * src/features/transfers/service.ts, and is the one entry point for moving bytes between locations:
 *  - [transfer] records a row, then starts the native job with the same id;
 *  - progress events update an in-memory live view at full rate and the database at most once a second per job,
 *    plus every state change; finished jobs are acknowledged natively once the database has them;
 *  - [reconcile] (start and resume) applies what native reports and fails rows it no longer knows, so they can be
 *    retried ([retry] runs the failed items again, resuming partial files).
 *
 * Confined to [scope]'s dispatcher (main); native events are posted there.
 */
class TransferService(
    private val scope: CoroutineScope,
    private val native: NativeTransfers,
    private val dao: TransferDao,
    /** Turns a place into a native endpoint; SMB gets the route that is current right now. */
    private val resolve: suspend (TransferPlace) -> Endpoint,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _live = MutableStateFlow<Map<String, LiveTransfer>>(emptyMap())
    val live: StateFlow<Map<String, LiveTransfer>> = _live.asStateFlow()

    private val lastWrite = HashMap<String, Long>()
    private val pendingWrite = HashMap<String, Job>()
    private var started = false
    private val sessionStart = now()

    private val listener: (TransferProgress) -> Unit = { event ->
        val view = LiveTransfer.of(event.job, event.bytesPerSecond)
        val id = event.job.transferId
        scope.launch { onProgress(id, view) }
    }

    /** Rows with their live view, newest first. */
    val transfers: Flow<List<TransferView>> = combine(dao.observeAll(), live) { rows, live ->
        rows.map { TransferView.of(it, live[it.id]) }
    }

    /** Listen to progress and reconcile once. Idempotent. */
    fun start() {
        if (started) return
        started = true
        native.addListener(listener)
        scope.launch { runCatching { reconcile() } }
    }

    fun stop() {
        native.removeListener(listener)
        started = false
    }

    internal suspend fun onProgress(id: String, event: LiveTransfer) {
        val previous = _live.value[id]
        val merged = event.copy(
            bytesPerSecond = event.bytesPerSecond ?: if (event.state == TransferStates.RUNNING) previous?.bytesPerSecond else null,
            activeHost = event.activeHost ?: previous?.activeHost,
        )
        _live.update { it + (id to merged) }
        val stateChanged = previous?.state != event.state
        val due = now() - (lastWrite[id] ?: 0L) >= DB_WRITE_INTERVAL_MS
        if (stateChanged || due || event.isTerminal) {
            write(id, merged)
        } else if (pendingWrite[id] == null) {
            // Make sure the last progress of a burst lands in the database too.
            pendingWrite[id] = scope.launch {
                delay(DB_WRITE_INTERVAL_MS)
                pendingWrite.remove(id)
                _live.value[id]?.let { write(id, it) }
            }
        }
    }

    private suspend fun write(id: String, event: LiveTransfer) {
        pendingWrite.remove(id)?.cancel()
        lastWrite[id] = now()
        apply(id, event)
        if (event.isTerminal) runCatching { native.acknowledge(listOf(id)) }
    }

    private suspend fun apply(id: String, event: LiveTransfer) {
        dao.applyProgress(
            id = id,
            state = event.state,
            bytesDone = event.bytesDone,
            totalBytes = event.totalBytes,
            itemsTotal = event.itemsTotal,
            itemsDone = event.itemsDone,
            itemsFailed = event.itemsFailed,
            itemsSkipped = event.itemsSkipped,
            errorCode = event.errorCode,
            error = event.errorMessage,
            finished = event.isTerminal,
            now = Instant.ofEpochMilli(now()).toString(),
        )
    }

    /** Apply what native knows; fail rows native lost (app killed before it persisted, data cleared). */
    suspend fun reconcile() {
        val jobs = native.reported()
        val rows = dao.all().associateBy { it.id }
        val acknowledged = ArrayList<String>()
        for (job in jobs) {
            if (job.transferId !in rows) {
                if (job.isTerminal) acknowledged += job.transferId
                continue
            }
            val event = LiveTransfer.of(job)
            apply(job.transferId, event)
            _live.update { it + (job.transferId to event) }
            if (job.isTerminal) acknowledged += job.transferId
        }
        val known = jobs.map { it.transferId }.toSet()
        for (row in rows.values) {
            if (row.id in known || TransferStates.isTerminal(row.state)) continue
            // Started in this app session (native may not have it yet): leave it.
            val created = runCatching { Instant.parse(row.createdAt).toEpochMilli() }.getOrDefault(0L)
            if (created >= sessionStart) continue
            apply(row.id, rowEvent(row).copy(state = TransferStates.FAILED, errorCode = "UNKNOWN", errorMessage = INTERRUPTED))
            _live.update { it - row.id }
        }
        if (acknowledged.isNotEmpty()) runCatching { native.acknowledge(acknowledged) }
    }

    /** Record the row, then start the native job (same id). Throws (and fails the row) when it cannot start. */
    suspend fun transfer(request: TransferRequest): String {
        val id = UUID.randomUUID().toString()
        val source = request.source
        val destination = request.destination
        dao.insert(
            TransferEntity(
                id = id,
                op = request.op,
                sourceLocationId = source.locationId,
                sourceMachineId = source.locationId.takeIf(::isMachine),
                sourceShare = if (source.locationId == INCOMING_LOCATION_ID) source.shareId.orEmpty() else source.share.orEmpty(),
                sourcePaths = JSONArray(request.paths).toString(),
                destLocationId = destination.place.locationId,
                destMachineId = destination.place.locationId.takeIf(::isMachine),
                destShare = destination.place.share.orEmpty(),
                destPath = destination.path,
                destTreeUri = destination.place.treeUri,
                name = request.name,
                conflict = request.conflict,
                size = request.size,
                bytesDone = 0,
                itemsTotal = request.itemsTotal,
                itemsDone = 0,
                itemsFailed = 0,
                itemsSkipped = 0,
                state = TransferStates.QUEUED,
                errorCode = null,
                error = null,
                createdAt = Instant.ofEpochMilli(now()).toString(),
                finishedAt = null,
            ),
        )
        run(id, request.op, source, request.paths, destination, request.conflict, resume = false, request.sidecars, request.hiddenSidecars)
        return id
    }

    private suspend fun run(
        id: String,
        op: String,
        source: TransferPlace,
        paths: List<String>,
        destination: FolderPlace,
        conflict: String,
        resume: Boolean,
        sidecars: Map<String, dev.hfps.hoard.xmp.XmpFields> = emptyMap(),
        hiddenSidecars: Boolean = false,
    ) {
        _live.update { it + (id to (it[id] ?: LiveTransfer(TransferStates.QUEUED)).copy(state = TransferStates.QUEUED, errorCode = null, errorMessage = null)) }
        try {
            val from = resolve(source)
            val to = resolve(destination.place)
            native.start(
                StartTransferRequest(
                    transferId = id,
                    op = op,
                    sources = paths.map { TransferSource(from, it) },
                    destination = to,
                    destPath = destination.path,
                    conflict = conflict,
                    resume = resume,
                    sidecars = if (op == "copy") sidecars else emptyMap(),
                    hiddenSidecars = hiddenSidecars,
                ),
            )
        } catch (e: Throwable) {
            markFailed(id, e)
            throw e
        }
    }

    private suspend fun markFailed(id: String, error: Throwable) {
        _live.update { it - id }
        val row = dao.get(id) ?: return
        apply(id, rowEvent(row).copy(state = TransferStates.FAILED, errorCode = errorCodeOf(error).name, errorMessage = error.message ?: error.toString()))
    }

    /**
     * Run a failed or cancelled job again under the same id: completed items stay, the others run again, partial
     * files resume where the bytes still match (ADR 0007).
     */
    suspend fun retry(row: TransferEntity) {
        apply(row.id, rowEvent(row).copy(state = TransferStates.QUEUED))
        run(row.id, row.op, placeOf(row, source = true), sourcePaths(row), FolderPlace(placeOf(row, source = false), row.destPath), row.conflict, resume = true)
    }

    fun cancel(id: String) = native.cancel(id)

    /** Answer a job waiting in `conflict` ("apply to all", or per item). */
    fun resolveConflicts(id: String, all: String?, items: Map<Int, String> = emptyMap()) = native.resolveConflicts(id, all, items)

    /** The job's items (conflicts, failures), or null when native no longer has them. */
    fun job(id: String): TransferJob? = native.job(id)

    suspend fun remove(id: String) = dao.delete(id)

    suspend fun clearFinished(): Int = dao.clearFinished()

    companion object {
        const val DB_WRITE_INTERVAL_MS = 1000L

        fun sourcePaths(row: TransferEntity): List<String> = runCatching {
            val array = JSONArray(row.sourcePaths)
            (0 until array.length()).mapNotNull { array.opt(it) as? String }
        }.getOrDefault(emptyList())

        fun placeOf(row: TransferEntity, source: Boolean): TransferPlace = if (source) {
            TransferPlace(
                locationId = row.sourceLocationId,
                share = row.sourceShare.ifEmpty { null },
                shareId = row.sourceShare.ifEmpty { null }.takeIf { row.sourceLocationId == INCOMING_LOCATION_ID },
            )
        } else {
            TransferPlace(row.destLocationId, row.destShare.ifEmpty { null }, row.destTreeUri)
        }

        private fun rowEvent(row: TransferEntity) = LiveTransfer(
            state = row.state,
            bytesDone = row.bytesDone,
            totalBytes = row.size,
            itemsTotal = row.itemsTotal,
            itemsDone = row.itemsDone,
            itemsFailed = row.itemsFailed,
            itemsSkipped = row.itemsSkipped,
        )
    }
}

/** The top-bar indicator (TransfersIndicator.tsx): jobs running and their overall progress, or a recent failure. */
data class TransfersIndicator(val count: Int = 0, val percent: Double = 0.0, val failedRecently: Boolean = false) {
    val active: Boolean get() = count > 0

    companion object {
        /** A failure counts for this long after it finished (Hoard 1's `isRecent`, 30 minutes). */
        const val RECENT_MS = 30 * 60_000L

        fun of(views: List<TransferView>, now: Long = System.currentTimeMillis()): TransfersIndicator {
            val active = views.filter { it.active }
            val total = active.sumOf { it.total }
            val done = active.sumOf { it.bytesDone }
            val failed = views.any { v ->
                v.state == TransferStates.FAILED && v.row.finishedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }?.let { now - it < RECENT_MS } == true
            }
            return TransfersIndicator(active.size, if (total > 0) done * 100.0 / total else 0.0, failed)
        }
    }
}
