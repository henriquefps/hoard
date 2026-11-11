package dev.hfps.hoard.smb.cull

import android.util.Log
import dev.hfps.hoard.image.CullShotRef
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.smb.transfer.TransferFs
import dev.hfps.hoard.smb.transfer.TransferPaths
import dev.hfps.hoard.xmp.SidecarNames
import dev.hfps.hoard.xmp.SidecarTool
import dev.hfps.hoard.xmp.SidecarWrite
import dev.hfps.hoard.xmp.Sidecars
import dev.hfps.hoard.xmp.XmpException
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpRead
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A file of a culling folder as listed: its name and mtime (ms). */
data class ListedName(val name: String, val modifiedMillis: Long?)

/** One shot's sidecar as read for culling (ADR 0011 "Reading"). [read] is null when it is not well-formed XMP. */
data class SidecarFound(val shotId: String, val name: String, val modifiedMillis: Long?, val read: XmpRead?, val error: String? = null)

/** The outcome of one shot's write, reported to the UI. */
data class SidecarResult(
    val shotId: String,
    /** `written`, `malformed`, `failed`, `readOnly` (not attempted: the folder refused a write this session). */
    val status: String,
    val fields: XmpFields,
    val name: String? = null,
    val modifiedMillis: Long? = null,
    val error: HoardSmbException? = null,
)

/**
 * Culling's sidecars (ADR 0011): reading the sidecars of a folder's shots, and writing each decision into the shot's
 * sidecar. Pure apart from the [TransferFs] given (JVM-tested with `MemoryFs`); [CullSidecarWriter] adds the
 * debounce, the serialization and the flush.
 */
object CullSidecars {
    /** The file names of a shot, main file first (the RAW, then the rendering, then the others). */
    fun fileNames(shot: CullShotRef): List<String> = listOfNotNull(shot.raw?.name, shot.rendered?.name) + shot.others.map { it.name }

    /**
     * The sidecars of [shots] among the folder's [listed] files whose mtime is later than [after] (shot id → ms;
     * absent or null = read whatever exists), read through [fs]. Shots without a sidecar, or with one that is not
     * newer, are left out. One listing (the caller's), then one small read per sidecar returned.
     */
    fun read(fs: TransferFs, folder: String, shots: List<CullShotRef>, listed: List<ListedName>, after: Map<String, Long?>): List<SidecarFound> {
        val byLower = HashMap<String, ListedName>()
        for (entry in listed) if (SidecarNames.isSidecar(entry.name)) byLower.putIfAbsent(entry.name.lowercase(Locale.ROOT), entry)
        return shots.mapNotNull { shot ->
            val name = SidecarNames.find(shot.stem, fileNames(shot)) { byLower[it]?.name } ?: return@mapNotNull null
            val entry = byLower.getValue(name.lowercase(Locale.ROOT))
            val threshold = after[shot.id]
            if (threshold != null && (entry.modifiedMillis ?: Long.MIN_VALUE) <= threshold) return@mapNotNull null
            try {
                SidecarFound(shot.id, name, entry.modifiedMillis, Sidecars.read(fs, TransferPaths.join(folder, name)))
            } catch (e: XmpException) {
                SidecarFound(shot.id, name, entry.modifiedMillis, null, "malformed")
            } catch (e: InterruptedException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                SidecarFound(shot.id, name, entry.modifiedMillis, null, fs.mapError(e).code.name)
            }
        }
    }

    /**
     * Writes [items] (shot id → fields) into their shots' sidecars in [folder], in order, with one listing of the
     * folder. [readOnly] says the folder already refused a write: nothing is attempted. The first `ACCESS_DENIED`
     * marks the folder read-only ([Batch.readOnly]) and the remaining items are not attempted either.
     */
    fun write(
        fs: TransferFs,
        folder: String,
        shots: Map<String, CullShotRef>,
        items: List<Pair<String, XmpFields>>,
        hidden: Boolean,
        readOnly: Boolean,
        date: String = IsoTime.now(),
    ): Batch {
        if (readOnly) return Batch(items.map { (id, f) -> SidecarResult(id, STATUS_READ_ONLY, f) }, true)
        val names = try {
            fs.list(folder).filter { !it.isDirectory && SidecarNames.isSidecar(it.name) }
                .associateTo(HashMap()) { it.name.lowercase(Locale.ROOT) to it.name }
        } catch (e: InterruptedException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            val error = fs.mapError(e)
            return Batch(items.map { (id, f) -> SidecarResult(id, STATUS_FAILED, f, error = error) }, false)
        }
        var deniedNow = false
        val results = items.map { (shotId, fields) ->
            val shot = shots[shotId]
            when {
                deniedNow -> SidecarResult(shotId, STATUS_READ_ONLY, fields)
                shot == null -> SidecarResult(shotId, STATUS_FAILED, fields, error = HoardSmbException(ErrorCode.NOT_FOUND, "No shot $shotId"))
                else -> {
                    val existing = SidecarNames.find(shot.stem, fileNames(shot)) { names[it] }
                    when (val written = Sidecars.write(fs, folder, shot.stem, existing, fields, hidden, SidecarTool.creatorTool, date)) {
                        is SidecarWrite.Written -> {
                            names[written.name.lowercase(Locale.ROOT)] = written.name
                            SidecarResult(shotId, STATUS_WRITTEN, fields, written.name, written.modifiedMillis)
                        }
                        is SidecarWrite.Malformed -> SidecarResult(shotId, STATUS_MALFORMED, fields, written.name)
                        is SidecarWrite.Failed -> {
                            if (written.error.code == ErrorCode.ACCESS_DENIED) {
                                deniedNow = true
                                SidecarResult(shotId, STATUS_READ_ONLY, fields, written.name, error = written.error)
                            } else {
                                SidecarResult(shotId, STATUS_FAILED, fields, written.name, error = written.error)
                            }
                        }
                    }
                }
            }
        }
        return Batch(results, deniedNow)
    }

    /** One flush: what happened to each item, and whether the folder turned out read-only. */
    data class Batch(val results: List<SidecarResult>, val readOnly: Boolean)

    const val STATUS_WRITTEN = "written"
    const val STATUS_MALFORMED = "malformed"
    const val STATUS_FAILED = "failed"
    const val STATUS_READ_ONLY = "readOnly"
}

/**
 * The culling sidecar writer (ADR 0011 "Writing"): every decision is queued here at once (the bridge call returns
 * when its write is done), writes are coalesced per shot over [debounceMs] (at most [maxDelayMs] after the first
 * pending one), run one at a time on one thread (so writes to one sidecar never overlap), and [flushAll] writes
 * whatever is pending without waiting (the app going to the background, the folder closing). A folder that refuses a
 * write (`ACCESS_DENIED`) is read-only for the rest of the session: later decisions are not attempted.
 */
class CullSidecarWriter(
    /** The folder's file operations now (its location can change between calls: an SMB reconnect). */
    private val fsFor: (CullFolder) -> TransferFs,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "HoardSidecars").apply { isDaemon = true }
    },
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private class Pending(var fields: XmpFields, val waiters: MutableList<CompletableFuture<SidecarResult>> = mutableListOf())

    private class FolderState(val folder: CullFolder) {
        val pending = LinkedHashMap<String, Pending>()
        var hidden = false
        var firstPendingAt = 0L
        var scheduled: ScheduledFuture<*>? = null
        @Volatile var readOnly = false
    }

    private val states = ConcurrentHashMap<String, FolderState>()

    /** The folder refused a write this session (the UI shows the read-only note). */
    fun isReadOnly(cullId: String): Boolean = states[cullId]?.readOnly == true

    /** Queues [items] (shot id → fields) of [folder]; completes with one result per item once written. */
    fun enqueue(folder: CullFolder, items: List<Pair<String, XmpFields>>, hidden: Boolean): CompletableFuture<List<SidecarResult>> {
        val state = states.computeIfAbsent(folder.id) { FolderState(folder) }
        val futures = synchronized(state) {
            state.hidden = hidden
            val now = System.nanoTime() / 1_000_000
            if (state.pending.isEmpty()) state.firstPendingAt = now
            val futures = items.map { (shotId, fields) ->
                val future = CompletableFuture<SidecarResult>()
                val pending = state.pending.getOrPut(shotId) { Pending(fields) }
                pending.fields = fields
                pending.waiters += future
                future
            }
            state.scheduled?.cancel(false)
            val delay = minOf(debounceMs, maxOf(0L, state.firstPendingAt + maxDelayMs - now))
            state.scheduled = executor.schedule({ flush(state) }, delay, TimeUnit.MILLISECONDS)
            futures
        }
        return CompletableFuture.allOf(*futures.toTypedArray()).thenApply { futures.map { it.join() } }
    }

    /** Writes everything pending now (all folders). Returns at once; the writes run on the writer's thread. */
    fun flushAll() {
        states.values.forEach { state ->
            synchronized(state) {
                if (state.pending.isEmpty()) return@forEach
                state.scheduled?.cancel(false)
                state.scheduled = executor.schedule({ flush(state) }, 0, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** The folder closed: write what is pending, then forget it. */
    fun close(cullId: String) {
        val state = states[cullId] ?: return
        synchronized(state) { state.scheduled?.cancel(false) }
        executor.execute {
            flush(state)
            synchronized(state) { if (state.pending.isEmpty()) states.remove(cullId, state) }
        }
    }

    private fun flush(state: FolderState) {
        val batch = synchronized(state) {
            val taken = state.pending.toList()
            state.pending.clear()
            state.scheduled = null
            taken
        }
        if (batch.isEmpty()) return
        val folder = state.folder
        val outcome = try {
            fsFor(folder).use { fs ->
                CullSidecars.write(fs, folder.path, folder.byId, batch.map { it.first to it.second.fields }, state.hidden, state.readOnly)
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            val error = e as? HoardSmbException ?: HoardSmbException(ErrorCode.UNKNOWN, e.message ?: e.javaClass.simpleName, e)
            CullSidecars.Batch(batch.map { SidecarResult(it.first, CullSidecars.STATUS_FAILED, it.second.fields, error = error) }, false)
        }
        if (outcome.readOnly && !state.readOnly) {
            state.readOnly = true
            log("${folder.path.ifEmpty { "/" }}: read-only, ratings stay in Hoard for this session")
        }
        outcome.results.forEachIndexed { i, result ->
            if (result.status == CullSidecars.STATUS_MALFORMED || result.status == CullSidecars.STATUS_FAILED) {
                log("${result.shotId}: sidecar ${result.name ?: ""} not written: ${result.status} ${result.error?.message ?: ""}")
            }
            batch[i].second.waiters.forEach { it.complete(result) }
        }
    }

    companion object {
        const val TAG = "HoardSidecars"
        const val DEBOUNCE_MS = 400L
        const val MAX_DELAY_MS = 2_000L
    }
}
