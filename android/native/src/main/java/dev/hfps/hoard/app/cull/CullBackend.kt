package dev.hfps.hoard.app.cull

import android.util.Log
import dev.hfps.hoard.app.files.BrowseLocation
import dev.hfps.hoard.app.files.LocationKind
import dev.hfps.hoard.app.files.engineMachine
import dev.hfps.hoard.app.route.RouteEngine
import dev.hfps.hoard.app.smb.SmbGateway
import dev.hfps.hoard.image.ImageFileResult
import dev.hfps.hoard.image.ImageCore
import dev.hfps.hoard.image.ScreenFile
import dev.hfps.hoard.image.ZoomInfo
import dev.hfps.hoard.image.cache.PriorityScheduler
import dev.hfps.hoard.image.source.CancelledException
import dev.hfps.hoard.smb.Cancellation
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.location.LocalErrors
import dev.hfps.hoard.smb.SmbAccess
import dev.hfps.hoard.smb.SmbErrors
import dev.hfps.hoard.smb.cull.CullFolder
import dev.hfps.hoard.smb.cull.CullService
import dev.hfps.hoard.smb.cull.CullSidecars
import dev.hfps.hoard.smb.cull.ListedName
import dev.hfps.hoard.smb.cull.SidecarResult
import dev.hfps.hoard.smb.cull.SidecarScan
import dev.hfps.hoard.smb.cull.SidecarStamp
import dev.hfps.hoard.smb.location.Location
import dev.hfps.hoard.smb.location.VolumeRegistry
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.xmp.XmpFields
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A folder as culling opens it: its shots (paired natively), the folder's access, whether LibRaw is there. */
data class OpenedFolder(val shots: List<Shot>, val access: SmbAccess?, val libRaw: Boolean)

/** The answer to an analysis batch: one entry per id (null = could not be analysed), and whether faces work. */
data class AnalyzeResult(val results: Map<String, Analysis?>, val faces: Boolean)

/** Sidecar writes as native answered them: one result per item, and whether the folder refused a write. */
data class SidecarWrites(val results: List<SidecarResult>, val readOnly: Boolean)

/**
 * Everything culling asks of the native side for one folder (what the plugin's `cull*` methods did for Hoard 1). The
 * real one is [NativeCullBackend]; tests give a fake.
 */
interface CullBackend {
    suspend fun open(): OpenedFolder
    suspend fun metadata(shotIds: List<String>): Map<String, ShotMetadata?>
    /**
     * The sidecars of the shots in [seen] (shot id → the file last read, null = never read) that changed since that
     * read, and the shots with no sidecar; `refresh` lists the folder again first (ADR 0011 "First read of a sidecar").
     */
    suspend fun readSidecars(seen: Map<String, SidecarStamp?>, refresh: Boolean): SidecarScan
    suspend fun writeSidecars(items: List<Pair<String, XmpFields>>, hidden: Boolean): SidecarWrites
    suspend fun analyze(shotIds: List<String>, urgent: Boolean): AnalyzeResult
    suspend fun thumbnail(shotId: String, maxSize: Int): File
    suspend fun screen(shotId: String, longSide: Int, fast: Boolean, background: Boolean = false): ScreenFile
    /**
     * The ADR 0006 focus score (variance of the Laplacian per tile, best tile) of a shot not analysed yet, on its
     * 1600 px screen image (`cullSharpness`). Compare runs it while the analysis queue has not reached the pair.
     */
    suspend fun focusScore(shotId: String): Double? = null
    suspend fun zoomInfo(shotId: String): ZoomInfo
    suspend fun tile(shotId: String, tile: Tile): ImageFileResult
    fun prefetch(shotIds: List<String>, current: Int, longSide: Int)
    suspend fun delete(path: String)
    /** Pending sidecar writes go out now (the app left the screen; ADR 0011 "Flush"). */
    fun flush()
    fun close()

    /** Fuller metadata of one shot, read when the viewer reaches it (the Gallery reads EXIF lazily); null = none. */
    suspend fun details(shotId: String): ShotMetadata? = null

    /** Which image of a shot is shown now (the Gallery: Reveal's edit or the original, ADR 0020); part of cache keys. */
    fun imageVariant(shotId: String): String = ""
}

/**
 * [CullBackend] over :core:smb's [CullService] (one per process, in the container) and the route engine: SMB calls
 * run on the machine's current session (one reconnect on NOT_FOUND, as `withSession`), local ones on `HoardFs`. A
 * culling handle that expired (the process died) is opened again once, as `useCullFolder`'s `call` did.
 */
class NativeCullBackend(
    private val service: CullService,
    private val routes: RouteEngine,
    private val gateway: SmbGateway,
    private val volumes: VolumeRegistry,
    private val location: BrowseLocation,
    private val path: String,
    /** A file of the folder was deleted (ADR 0022: its cached server resolution is dropped). */
    private val onDeleted: (String) -> Unit = {},
) : CullBackend {
    @Volatile private var folder: CullFolder? = null
    private val opening = Mutex()

    private suspend fun <T> onLocation(block: suspend (Location) -> T): T = when (location.kind) {
        LocationKind.Smb -> {
            val machine = location.machine ?: smbFail(ErrorCode.NOT_FOUND, "Machine not found")
            val share = location.share ?: smbFail(ErrorCode.UNKNOWN, "No share")
            routes.withSession(machine.engineMachine()) { sessionId -> block(Location.Smb(sessionId, share)) }
        }
        else -> block(location.localRef!!)
    }

    private suspend fun openNow(loc: Location): CullFolder = withContext(Dispatchers.IO) {
        val listing = when (loc) {
            is Location.Smb -> gateway.sessions.use(loc.sessionId) { session -> dev.hfps.hoard.smb.location.SmbFs(session, loc.share).list(path, Cancellation.NONE) }
            is Location.Local -> volumes.fs(loc).list(path, Cancellation.NONE)
        }
        val opened = if (loc is Location.Smb) gateway.sessions.use(loc.sessionId) { service.open(loc, path, listing.entries) } else service.open(loc, path, listing.entries)
        access = listing.access
        opened
    }

    @Volatile private var access: SmbAccess? = null

    override suspend fun open(): OpenedFolder = mapped {
        val opened = onLocation { loc -> openNow(loc) }
        folder?.let { old -> if (old.id != opened.id) service.close(old.id) }
        folder = opened
        OpenedFolder(opened.shots, access, service.core.libRawAvailable)
    }

    /** Runs [block] with the open folder reached through the current location; reopens once on an expired handle. */
    private suspend fun <T> call(block: suspend (CullFolder) -> T): T = mapped {
        onLocation { loc ->
            val current = folder ?: opening.withLock { folder ?: openNow(loc).also { folder = it } }
            try {
                block(service.folder(current.id, loc))
            } catch (e: HoardSmbException) {
                if (e.code != ErrorCode.NOT_FOUND || !e.message.orEmpty().startsWith("Culling session expired")) throw e
                val fresh = opening.withLock { openNow(loc).also { folder = it } }
                block(service.folder(fresh.id, loc))
            }
        }
    }

    private suspend fun <T> await(folder: CullFolder, job: PriorityScheduler.Job<T>): T = try {
        job.future.awaitIt()
    } catch (e: CancellationException) {
        service.core.cancel(job)
        throw e
    } catch (e: Throwable) {
        val cause = generateSequence(e) { if (it is ExecutionException || it is CompletionException) it.cause else null }.last()
        if (cause is CancelledException) throw HoardSmbException(ErrorCode.CANCELLED, "Superseded", cause)
        val loc = folder.location
        if (loc is Location.Smb && cause !is HoardSmbException && SmbErrors.isSessionGone(cause)) {
            gateway.sessions.remove(loc.sessionId)
            throw HoardSmbException(ErrorCode.NOT_FOUND, "Session expired: ${SmbErrors.map(cause).message}", cause)
        }
        throw cause
    }

    override suspend fun metadata(shotIds: List<String>): Map<String, ShotMetadata?> = call { f ->
        withContext(Dispatchers.IO) {
            shotIds.associateWith { id -> runCatching { ShotMetadata.of(service.metadata(f, id)) }.getOrNull() }
        }
    }

    override suspend fun readSidecars(seen: Map<String, SidecarStamp?>, refresh: Boolean): SidecarScan = call { f ->
        withContext(Dispatchers.IO) {
            val shots = seen.keys.mapNotNull { f.byId[it] }
            val read = {
                service.fs(f).use { fs ->
                    val listed = if (refresh) {
                        fs.list(f.path).filter { !it.isDirectory }.map { ListedName(it.name, it.modifiedMillis, it.size) }.also { f.listed = it }
                    } else {
                        f.listed
                    }
                    CullSidecars.scan(fs, f.path, shots, listed, seen)
                }
            }
            val loc = f.location
            if (loc is Location.Smb) gateway.sessions.use(loc.sessionId) { read() } else LocalErrors.guard { read() }
        }
    }

    override suspend fun writeSidecars(items: List<Pair<String, XmpFields>>, hidden: Boolean): SidecarWrites = call { f ->
        if (items.isEmpty()) return@call SidecarWrites(emptyList(), service.sidecars.isReadOnly(f.id))
        val results = service.sidecars.enqueue(f, items, hidden).awaitIt()
        SidecarWrites(results, service.sidecars.isReadOnly(f.id))
    }

    override suspend fun analyze(shotIds: List<String>, urgent: Boolean): AnalyzeResult = call { f ->
        val results = LinkedHashMap<String, Analysis?>()
        for (id in shotIds) {
            results[id] = try {
                AnalysisJson.of(await(f, service.analyze(f, id, if (urgent) ANALYZE_URGENT_PRIORITY else ImageCore.ANALYSIS_PRIORITY)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val error = SmbErrors.map(e)
                if (error.code == ErrorCode.CANCELLED || error.code == ErrorCode.NOT_FOUND) throw error
                Log.w(CullService.TAG, "analysis $id failed: ${error.message}")
                null
            }
        }
        AnalyzeResult(results, service.facesAvailable)
    }

    override suspend fun thumbnail(shotId: String, maxSize: Int): File = call { f ->
        await(f, service.core.thumbnailFile(service.request(f, shotId), maxSize, priority = 50)).file
    }

    override suspend fun screen(shotId: String, longSide: Int, fast: Boolean, background: Boolean): ScreenFile = call { f ->
        await(f, service.core.screenFile(service.request(f, shotId), longSide, fast, priority = if (background) BACKGROUND_PRIORITY else 0))
    }

    override suspend fun zoomInfo(shotId: String): ZoomInfo = call { f -> await(f, service.core.zoomInfo(service.request(f, shotId))) }

    override suspend fun focusScore(shotId: String): Double? = call { f ->
        val request = service.request(f, shotId)
        val step = withContext(Dispatchers.IO) { service.core.screenPlan(request, SHARPNESS_SIDE).firstOrNull() } ?: return@call null
        val decoded = await(f, service.core.screen(request, SHARPNESS_SIDE, priority = FOCUS_PRIORITY, step = step))
        withContext(Dispatchers.Default) { service.core.sharpness(decoded.bitmap).best }
    }

    override suspend fun tile(shotId: String, tile: Tile): ImageFileResult = call { f ->
        await(f, service.core.tileFile(service.request(f, shotId), tile.x, tile.y, tile.width, tile.height, tile.sample))
    }

    override fun prefetch(shotIds: List<String>, current: Int, longSide: Int) {
        val f = folder ?: return
        runCatching {
            val requests = shotIds.map { service.request(f, it) }
            service.core.prefetchFiles(requests, current, longSide, ahead = 2, behind = 1, zoomAhead = 1, fastAhead = 6)
        }
    }

    override suspend fun delete(path: String) {
        mapped {
            onLocation { loc ->
                withContext(Dispatchers.IO) {
                    when (loc) {
                        is Location.Smb -> gateway.sessions.use(loc.sessionId) { dev.hfps.hoard.smb.location.SmbFs(it, loc.share).delete(path, false) }
                        is Location.Local -> volumes.fs(loc).delete(path, false)
                    }
                }
            }
        }
        onDeleted(path)
    }

    override fun flush() = service.sidecars.flushAll()

    override fun close() {
        folder?.let { service.close(it.id) }
        folder = null
    }

    private suspend fun <T> mapped(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HoardSmbException) {
        throw e
    } catch (e: dev.hfps.hoard.app.route.RouteException) {
        throw e
    } catch (e: Throwable) {
        throw SmbErrors.map(e)
    }

    companion object {
        /** Priority of an urgent analysis (the shot on screen): after its own prefetch window. */
        private const val ANALYZE_URGENT_PRIORITY = 30
        /** Priority of a neighbour's screen image: right after the prefetch window's fast images. */
        private const val BACKGROUND_PRIORITY = 2
        /** The focus score's image size and priority, as Hoard 1's `cullSharpness`. */
        private const val SHARPNESS_SIDE = 1600
        private const val FOCUS_PRIORITY = 20
    }
}

/** Waits for a future without blocking a thread; the future is not cancelled with the coroutine. */
suspend fun <T> CompletableFuture<T>.awaitIt(): T = suspendCancellableCoroutine { cont ->
    whenComplete { value, error ->
        if (error != null) {
            val cause = if (error is CompletionException || error is ExecutionException) error.cause ?: error else error
            cont.resumeWithException(cause)
        } else {
            cont.resume(value)
        }
    }
}
