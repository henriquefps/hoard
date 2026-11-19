package dev.hfps.hoard.app.compress

import android.util.Log
import dev.hfps.hoard.app.gallery.GalleryEntry
import dev.hfps.hoard.app.gallery.GalleryRepository
import dev.hfps.hoard.app.gallery.Kind
import dev.hfps.hoard.app.gallery.Where
import dev.hfps.hoard.server.api.BackupRoots
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.api.CompressEstimate
import dev.hfps.hoard.server.api.CompressEstimateRequest
import dev.hfps.hoard.server.api.CompressFolderSelect
import dev.hfps.hoard.server.api.CompressInfo
import dev.hfps.hoard.server.api.CompressJob
import dev.hfps.hoard.server.api.CompressJobCreate
import dev.hfps.hoard.server.api.CompressJobPatch
import dev.hfps.hoard.server.api.CompressRootSelect
import dev.hfps.hoard.server.api.CompressSelect
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerException
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.compress.CompressGate
import dev.hfps.hoard.server.compress.CompressOffer
import dev.hfps.hoard.server.compress.CompressOverview
import dev.hfps.hoard.server.compress.EstimateSummary
import dev.hfps.hoard.server.paths.PathMapping
import dev.hfps.hoard.server.paths.SharePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** One part of a compression: a target and what to take there (a Gallery selection may hold NAS and backup videos). */
data class CompressPart(val target: String, val select: CompressSelect, val videos: Int = 0)

/** The Gallery's badges (ADR 0025 "UI"): an output whose original is in the trash, a video in an unfinished job. */
enum class CompressBadge { Compressed, Compressing }

/** A folder Files can compress (a share folder a stored mapping covers, ADR 0022), as the server names it. */
data class CompressFolder(val folderId: String, val path: String, val label: String) {
    val select: CompressSelect get() = CompressSelect(folders = listOf(CompressFolderSelect(folderId, path, recursive = true)))
}

/**
 * Video compression from Hoard (ADR 0025, phase A1, Hoard 2.7.0): library (`nas`) and backup jobs on hoard-server. Every
 * action is offered only when the server lists `compress` and the target is usable ([offer]); the work itself is the
 * server's (no bytes move through the phone). The phone's own replace is phase A2.
 */
class CompressService(private val gallery: GalleryRepository, private val scope: CoroutineScope, private val mappings: suspend () -> List<PathMapping>) {
    /** `GET /v1/server`'s `compress` object (the last sync's, or [refresh]'s). */
    val info: StateFlow<CompressInfo?> get() = gallery.sync.compress

    private val features: Flow<List<String>> = gallery.account.map { it?.features?.split(',')?.filter { f -> f.isNotEmpty() }.orEmpty() }

    /** What a NAS (library) action shows. */
    val nasOffer: StateFlow<CompressOffer> = offerFlow(Compress.TARGET_NAS)

    /** What a backup action (server-only phone videos) shows. */
    val backupOffer: StateFlow<CompressOffer> = offerFlow(Compress.TARGET_BACKUP)

    /** Settings › Compress videos is listed (the feature, or a server that still reports compression). */
    val settingsVisible: StateFlow<Boolean> = combine(features, info) { f, i -> CompressGate.settingsVisible(f, i) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private fun offerFlow(target: String): StateFlow<CompressOffer> =
        combine(features, info) { f, i -> CompressGate.offer(f, i, target) }.stateIn(scope, SharingStarted.Eagerly, CompressOffer.Hidden)

    private val compressing = MutableStateFlow<Set<String>>(emptySet())

    /** Item id → badge: outputs whose original is still in the trash, and videos in an unfinished job. */
    val badges: StateFlow<Map<String, CompressBadge>> = combine(
        gallery.db.items().observeCompressedJson().map { rows ->
            rows.mapNotNull { r -> runCatching { ServerJson.decodeFromString(Item.serializer(), r.json) }.getOrNull()?.takeIf { it.compressed?.originalInTrash == true }?.id }.toSet()
        }.flowOn(Dispatchers.Default),
        compressing,
    ) { compressed, running ->
        val out = HashMap<String, CompressBadge>()
        compressed.forEach { out[it] = CompressBadge.Compressed }
        running.forEach { out[it] = CompressBadge.Compressing }
        out
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private suspend fun api(): ServerApi? = gallery.api()

    private suspend fun <T> io(block: suspend (ServerApi) -> T): T = withContext(Dispatchers.IO) {
        val api = api() ?: throw ServerException(0, "not_paired", "Not paired with a photo server")
        block(api)
    }

    /** Reads `GET /v1/server` now (the screens open with the current targets); quiet on failure. */
    suspend fun refresh() {
        runCatching { io { api -> api.server() }.also { gallery.sync.noteServerInfo(it) } }.onFailure { if (it is CancellationException) throw it }
        refreshCompressing()
    }

    /** The original items of unfinished jobs (the "Compressing" badge). */
    suspend fun refreshCompressing() {
        if (CompressGate.settingsVisible(features.first(), info.value).not()) {
            compressing.value = emptySet()
            return
        }
        runCatching {
            val ids = HashSet<String>()
            io { api ->
                var cursor: String? = null
                var pages = 0
                do {
                    val page = api.compressItems(states = Compress.UNFINISHED.joinToString(","), cursor = cursor, limit = 500)
                    page.items.forEach { ids += it.itemId }
                    cursor = page.cursor
                    pages++
                } while (page.hasMore && !cursor.isNullOrEmpty() && pages < MAX_PAGES)
            }
            compressing.value = ids
        }.onFailure { if (it is CancellationException) throw it }
    }

    // --- Selections ------------------------------------------------------------------------------------------------

    /**
     * The parts of a Gallery selection: videos with a NAS file go to the library (`nas`); videos only in a phone backup
     * and no longer on that phone go to `backup`. Phone-only videos (A2) and uploads are left out ([left]).
     */
    suspend fun partsOf(entries: List<GalleryEntry>): Pair<List<CompressPart>, Int> = withContext(Dispatchers.IO) {
        val videos = entries.filter { it.kind == Kind.Video && it.itemId != null }
        val nas = ArrayList<String>()
        val backup = ArrayList<String>()
        for (e in videos) {
            val row = gallery.db.items().get(e.itemId!!) ?: continue
            val item = runCatching { ServerJson.decodeFromString(Item.serializer(), row.json) }.getOrNull() ?: continue
            when (CompressTargets.of(item)) {
                Compress.TARGET_NAS -> nas += item.id
                Compress.TARGET_BACKUP -> backup += item.id
            }
        }
        val parts = listOfNotNull(
            nas.takeIf { it.isNotEmpty() }?.let { CompressPart(Compress.TARGET_NAS, CompressSelect(itemIds = it), it.size) },
            backup.takeIf { it.isNotEmpty() }?.let { CompressPart(Compress.TARGET_BACKUP, CompressSelect(itemIds = it), it.size) },
        )
        parts to (entries.count { it.kind == Kind.Video } - nas.size - backup.size)
    }

    /** A share folder's NAS folder (a stored mapping of the paired server covers it), or null. */
    suspend fun folderOf(machineId: String, share: String, path: String): CompressFolder? = withContext(Dispatchers.IO) {
        val serverId = gallery.db.account().get()?.serverId ?: return@withContext null
        val m = runCatching { mappings() }.getOrDefault(emptyList())
            .filter { it.serverId == serverId && it.sameShare(machineId, share) && it.covers(path) }
            .maxByOrNull { SharePaths.segments(it.smbPrefix).size } ?: return@withContext null
        val serverPath = m.serverPathOf(path) ?: return@withContext null
        CompressFolder(m.folderId, serverPath, listOfNotNull(m.folderName, serverPath.ifEmpty { null }).joinToString("/"))
    }

    /** A Gallery › Devices place (a phone, a volume, a folder) as backup roots (`target: "backup"`). */
    suspend fun backupPartOf(where: Where): CompressPart? = withContext(Dispatchers.IO) {
        val json = gallery.db.account().get()?.backupRoots ?: return@withContext null
        val roots = runCatching { ServerJson.decodeFromString(BackupRoots.serializer(), json).roots }.getOrDefault(emptyList())
        val select = CompressTargets.backupRoots(roots, where) ?: return@withContext null
        CompressPart(Compress.TARGET_BACKUP, CompressSelect(roots = select))
    }

    // --- Estimate and jobs -----------------------------------------------------------------------------------------

    /**
     * The estimate of [parts] with [profile], every page read; pending probes are asked again after `Retry-After` (at
     * most [MAX_WAITS] times), each pass reported through [onUpdate].
     */
    suspend fun estimate(parts: List<CompressPart>, profile: String, onUpdate: (EstimateSummary) -> Unit = {}): EstimateSummary {
        var waits = 0
        while (true) {
            val summaries = parts.map { part -> EstimateSummary.of(pages(part, profile)) }
            val total = CompressTargets.merge(summaries)
            onUpdate(total)
            if (total.pending == 0 || waits >= MAX_WAITS) return total
            waits++
            delay(PENDING_WAIT_MS)
        }
    }

    private suspend fun pages(part: CompressPart, profile: String): List<CompressEstimate> = io { api ->
        val out = ArrayList<CompressEstimate>()
        var cursor: String? = null
        do {
            val page = api.compressEstimate(CompressEstimateRequest(part.target, part.select, profile, cursor))
            out += page
            cursor = page.next
        } while (!cursor.isNullOrEmpty() && out.size < MAX_PAGES)
        out
    }

    /** Starts one job per part (each with its own idempotency key); `auto` turns a folder's switch on. */
    suspend fun start(parts: List<CompressPart>, profile: String, auto: Boolean = false, name: String? = null): List<CompressJob> {
        val jobs = parts.map { part ->
            io { api -> api.createCompressJob(CompressJobCreate(part.target, part.select, profile, UUID.randomUUID().toString(), auto.takeIf { it }, name?.take(200))) }
        }
        Log.i(TAG, "started ${jobs.size} job(s): ${jobs.joinToString { "${it.target} ${it.queued ?: 0} queued" }}")
        scope.launch { refreshCompressing() }
        return jobs
    }

    /** Every job (newest first, a few pages): Settings › Compress videos. */
    suspend fun overview(): CompressOverview = io { api ->
        val jobs = ArrayList<CompressJob>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = api.compressJobs(cursor = cursor, limit = 100)
            jobs += page.jobs
            cursor = page.cursor
            pages++
        } while (page.hasMore && !cursor.isNullOrEmpty() && pages < MAX_PAGES)
        CompressOverview.of(jobs)
    }

    /** The folder's standing job ("Compress automatically"), or null when it is off. */
    suspend fun automaticJob(folder: CompressFolder): CompressJob? = io { api ->
        api.compressJobs(auto = true, limit = 100).jobs.firstOrNull { it.state != CompressJob.STATE_CANCELLED && CompressTargets.sameFolder(it, folder) }
    }

    suspend fun pause(job: CompressJob, paused: Boolean): CompressJob = io { api -> api.patchCompressJob(job.id, CompressJobPatch(paused = paused)) }

    /** Turns a folder's "Compress automatically" off: its queued videos are cancelled, replaced ones stay replaced. */
    suspend fun autoOff(job: CompressJob): CompressJob = io { api -> api.patchCompressJob(job.id, CompressJobPatch(auto = false)) }

    suspend fun cancel(job: CompressJob) {
        io { api -> api.compressCancel(dev.hfps.hoard.server.api.CompressBatch(jobId = job.id)) }
        refreshCompressing()
    }

    companion object {
        private const val TAG = "HoardCompress"
        const val MAX_PAGES = 40
        const val MAX_WAITS = 15
        const val PENDING_WAIT_MS = 2_000L
    }
}
