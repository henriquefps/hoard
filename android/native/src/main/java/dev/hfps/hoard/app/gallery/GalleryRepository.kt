package dev.hfps.hoard.app.gallery

import android.content.Context
import android.os.Build
import android.util.Log
import dev.hfps.hoard.app.cull.Decision
import dev.hfps.hoard.app.cull.Flag
import dev.hfps.hoard.data.CullDao
import dev.hfps.hoard.server.KeystoreKeyVault
import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.db.CullingEntity
import dev.hfps.hoard.server.db.DevicePushedEntity
import dev.hfps.hoard.server.db.GalleryDatabase
import dev.hfps.hoard.server.db.PendingCullingEntity
import dev.hfps.hoard.server.preview.PreviewCache
import dev.hfps.hoard.server.sync.CullValues
import dev.hfps.hoard.server.sync.CullingRules
import dev.hfps.hoard.server.sync.DeviceInfo
import dev.hfps.hoard.server.sync.GallerySyncWorker
import dev.hfps.hoard.server.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The Gallery's data (ADR 0021): the server client and its cache, the device index, the merged entries, and where a
 * decision goes. Anything on the server is decided through the server's culling (ADR 0016, queued and pushed); a
 * phone-only shot is decided in its folder's rows (Hoard 1's `cull_decisions`, as culling that folder from the Files
 * tab does) and handed to the server once the shot reaches it.
 */
class GalleryRepository(
    private val context: Context,
    private val cullDao: CullDao,
    private val scope: CoroutineScope,
    appVersion: String,
    debug: Boolean,
) {
    val db: GalleryDatabase by lazy { GalleryDatabase.open(context) }
    val device = DeviceInfo(
        name = Build.MODEL ?: "Android",
        app = if (debug) "hoard-debug" else "hoard",
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        appVersion = appVersion,
    )
    val sync: SyncEngine by lazy { SyncEngine(db, KeystoreKeyVault(context), device) }
    val previews: PreviewCache by lazy {
        PreviewCache(File(context.cacheDir, "gallery-previews"), DEFAULT_CACHE_BYTES) { sync.api() }.also { cache ->
            // 2.2.2: every original thumb the grid or the filmstrip gets is kept offline too (unless the setting is off).
            cache.onOriginalThumb = { itemId, file -> if (offlineThumbsOn()) offlineThumbs.keep(itemId, file) }
        }
    }
    val deviceIndex: DeviceIndexer by lazy { DeviceIndexer(context, db.device(), scope) }
    /** Originals of server items the owner opened, kept within a budget (Settings › Photo server). */
    val originals: dev.hfps.hoard.server.preview.OriginalCache by lazy {
        dev.hfps.hoard.server.preview.OriginalCache(File(context.noBackupFilesDir, "gallery-originals"), DEFAULT_ORIGINALS_BYTES) { sync.api() }
    }
    /** Small thumbnails of the whole server library, for browsing offline. */
    val offlineThumbs: dev.hfps.hoard.server.preview.OfflineThumbs by lazy { dev.hfps.hoard.server.preview.OfflineThumbs(File(context.noBackupFilesDir, "gallery-thumbs")) }
    val thumbs: GalleryThumbs by lazy { GalleryThumbs(context, previews, deviceIndex, offlineThumbs) }
    val account get() = db.account().observe()

    private val deviceShots: Flow<List<DeviceShot>> = db.device().observeAll().map { DeviceShots.group(it) }

    private val serverFiles: Flow<Map<String, String>> = db.items().observeFiles().map { rows ->
        val map = HashMap<String, String>(rows.size * 2)
        // A file in two items (rare: the same JPEG in a pair and alone) maps to the first.
        for (r in rows) map.putIfAbsent(r.fingerprint, r.itemId)
        map
    }

    private val serverDecisions: Flow<Map<String, EntryDecision>> = combine(db.culling().observeAll(), db.culling().observePending()) { records, pending ->
        val pendingBy = pending.groupBy { it.itemId }
        val ids = records.map { it.itemId }.toHashSet() + pendingBy.keys
        val byId = records.associateBy { it.itemId }
        ids.associateWith { id -> CullingRules.effective(byId[id], pendingBy[id].orEmpty()).let { EntryDecision(it.rating, it.flag) } }
    }

    private val deviceDecisions: Flow<Map<String, EntryDecision>> = cullDao.observeLocal().map { rows ->
        rows.associate { "${it.locationId}/${it.path}/${it.shotId}" to EntryDecision(it.rating, it.flag) }
    }

    /** Phone files not backed up yet, by path (ADR 0024: the cells' backup badge). */
    private val backupStates: Flow<Map<String, String>> = db.backup().observeOpenStates().map { rows -> rows.associate { it.absolute to it.state } }

    /** Item id → its `mirror` locations (ADR 0024 A2). */
    private val mirrors: Flow<Map<String, List<dev.hfps.hoard.server.db.ServerItemMirrorEntity>>> = db.items().observeMirrors().map { rows -> rows.groupBy { it.itemId } }

    /** This phone's physical device (ADR 0017), for the merge of its own backed-up files. */
    private val physicalId: Flow<String?> = db.account().observe().map { it?.physicalDeviceId }.distinctUntilChanged()

    /** Every phone's backup roots as of the last sync (`GET /v1/backup/roots`, ADR 0024 A2). */
    val backupRoots: Flow<List<dev.hfps.hoard.server.api.BackupRoot>> = db.account().observe().map { it?.backupRoots }.distinctUntilChanged().map { json ->
        json?.let { runCatching { dev.hfps.hoard.server.api.ServerJson.decodeFromString(dev.hfps.hoard.server.api.BackupRoots.serializer(), it).roots }.getOrNull() }.orEmpty()
    }

    /** Every shot, newest first; null until first computed. */
    val entries: StateFlow<List<GalleryEntry>?> = combine(
        db.items().observeRows(),
        deviceShots,
        serverFiles,
        serverDecisions,
        combine(deviceDecisions, backupStates, mirrors, physicalId) { d, b, m, p -> Extra(d, b, m, p) },
    ) { server, shots, files, sd, extra ->
        GalleryModel.entries(GalleryInput(server, shots, files, sd, extra.decisions, backup = extra.backup, mirrors = extra.mirrors, physicalId = extra.physicalId))
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    private data class Extra(
        val decisions: Map<String, EntryDecision>,
        val backup: Map<String, String>,
        val mirrors: Map<String, List<dev.hfps.hoard.server.db.ServerItemMirrorEntity>>,
        val physicalId: String?,
    )

    /** Gallery › Devices (ADR 0024 A2): every phone with a backup, its volumes and folders. */
    val devices: StateFlow<List<BackupDevice>> = combine(backupRoots, entries.filterNotNull(), physicalId) { roots, list, mine ->
        DeviceTree.build(roots, list, mine)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Lazily, emptyList())

    /** Entry key → decision, for an open culling session ([dev.hfps.hoard.app.cull.CullStore.external]). */
    val decisions: Flow<Map<String, Decision>> = entries.filterNotNull().map { list ->
        list.filter { !it.decision.unmarked }.associate { it.key to Decision(Flag.of(it.decision.flag), it.decision.rating) }
    }.distinctUntilChanged()

    private var started = false

    /** Process start: the device index, the background sync, the promotion of decided phone shots. */
    fun start() {
        if (started) return
        started = true
        deviceIndex.onFingerprints = { scope.launch { promote() } }
        // A phone shot decided later in the Files tab's culling, once it is on the server: handed over too.
        // An unmark deletes the decision row; the time of the clear is written to the sidecar row just after it.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        scope.launch { kotlinx.coroutines.flow.merge(cullDao.observeLocal(), cullDao.observeLocalSidecarRows()).debounce(2000).collect { runCatching { promote() } } }
        // …and a phone shot that reached the server (a sync, a fingerprint) is handed over once matched.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        scope.launch { entries.filterNotNull().debounce(2000).collect { runCatching { promote() } } }
        // Start-up does no bulk file I/O (ADR 0021): one MediaStore query now, fingerprints later and paced.
        deviceIndex.start()
        scope.launch {
            if (db.account().get() != null) {
                GallerySyncWorker.schedule(context)
                GallerySyncWorker.soon(context, 2)
                scheduleOfflineThumbs()
            }
            settings?.getString(KEY_ORIGINALS_MB)?.toLongOrNull()?.let { originals.maxBytes = it shl 20 }
            settings?.getString(dev.hfps.hoard.app.ui.server.CACHE_LIMIT_KEY)?.toLongOrNull()?.let { previews.maxBytes = it shl 20 }
        }
    }

    /** Sync now (Settings, pull to refresh); then hands decided phone shots that reached the server over. */
    suspend fun syncNow(): Boolean {
        val started = android.os.SystemClock.elapsedRealtime()
        val ok = sync.sync()
        Log.i(TAG, "timing sync ok=$ok pulled=${sync.status.value.pulled} pushed=${sync.status.value.pushed} in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        promote()
        if (ok) scheduleOfflineThumbs()
        if (ok && db.culling().pending(1).isNotEmpty()) sync.sync()
        if (ok) runCatching { afterSync?.invoke() }
        return ok
    }

    /** Called after each successful sync (the phone replace looks for items of this phone, ADR 0025 A2). */
    @Volatile var afterSync: (suspend () -> Unit)? = null

    // --- Decisions -------------------------------------------------------------------------------------------------

    private val writes = Mutex()

    /**
     * Applies decisions to entries (by key): server culling for server items, the folder's rows for phone shots. Each
     * is dated [at], or [times] (by entry key) for a decision made earlier (a sidecar import's mtime, ADR 0016 rule 4).
     */
    suspend fun decide(changes: List<Pair<GalleryEntry, EntryDecision>>, at: Long = System.currentTimeMillis(), times: Map<String, Long> = emptyMap()) = writes.withLock {
        var server = false
        for ((entry, after) in changes) {
            val itemId = entry.itemId
            val decidedAt = times[entry.key] ?: at
            if (itemId != null) {
                val record = db.culling().get(itemId)
                val pending = db.culling().pendingFor(itemId)
                val before = CullingRules.effective(record, pending)
                sync.decide(itemId, before, before.copy(rating = after.rating, flag = after.flag), decidedAt)
                server = true
            }
            // The phone's copy keeps its folder's row too (culling that folder from the Files tab agrees).
            entry.device?.let { shot ->
                val stem = shot.stem.lowercase()
                cullDao.saveDecisions(shot.locationId, "", shot.dir, listOf(Triple(stem, after.flag, after.rating)), mapOf(stem to decidedAt))
                // Already queued for the server above: the row's write is not a new decision to hand over.
                if (itemId != null) db.device().putPushed(listOf(DevicePushedEntity(shot.key, itemId, System.currentTimeMillis() + 1000)))
            }
        }
        if (server) GallerySyncWorker.soon(context)
    }

    /**
     * Phone shots decided while they were only on the phone, now on the server: their decisions are queued once
     * (baseRev 0: the newer of the phone's and the server's wins), then later decisions go straight to the server. A
     * decision or an unmark made later in the Files tab's culling of that folder is handed over again, with its own
     * time ([Handover]).
     */
    suspend fun promote() = writes.withLock {
        val account = db.account().get() ?: return@withLock
        if (!account.has("culling")) return@withLock
        val list = entries.value ?: return@withLock
        val pushed = db.device().pushed().associateBy { it.shotKey }
        val rows = cullDao.localDecisions().associateBy { "${it.locationId}/${it.path}/${it.shotId}" }
        val sidecars = cullDao.localSidecarRows().associateBy { "${it.locationId}/${it.path}/${it.shotId}" }
        val now = System.currentTimeMillis()
        val done = ArrayList<DevicePushedEntity>()
        for (e in list) {
            val shot = e.device ?: continue
            val itemId = e.itemId ?: continue
            val step = Handover.step(itemId, rows[shot.key], sidecars[shot.key], pushed[shot.key], now) ?: continue
            step.promotion?.let { sync.promote(itemId, it.values, it.at, clears = it.clears) }
            done += DevicePushedEntity(shot.key, itemId, step.pushedAt)
        }
        if (done.isNotEmpty()) {
            db.device().putPushed(done)
            if (db.culling().pending(1).isNotEmpty()) GallerySyncWorker.soon(context)
            Log.i(TAG, "promoted ${done.size} phone shots now on the server")
        }
    }

    /**
     * Of these phone shot keys (`<location>/<dir>/<stem>`), the ones whose culling has not reached the server (ADR 0024
     * "Free up space": such shots stay on the phone): a decision on the phone whose shot has no server item yet or is
     * still to hand over, or a server item with queued changes. Called after [syncNow].
     */
    suspend fun pendingCulling(keys: Set<String>): Set<String> = writes.withLock {
        if (keys.isEmpty()) return@withLock emptySet()
        val byKey = entries.value.orEmpty().mapNotNull { e -> e.device?.let { it.key to e } }.toMap()
        val rows = cullDao.localDecisions().associateBy { "${it.locationId}/${it.path}/${it.shotId}" }
        val sidecars = cullDao.localSidecarRows().associateBy { "${it.locationId}/${it.path}/${it.shotId}" }
        val pushed = db.device().pushed().associateBy { it.shotKey }
        val now = System.currentTimeMillis()
        keys.filterTo(HashSet()) { key ->
            val itemId = byKey[key]?.itemId
            val row = rows[key]
            val decided = row != null && (row.flag != null || row.rating > 0)
            when {
                itemId == null -> decided
                Handover.step(itemId, row, sidecars[key], pushed[key], now)?.promotion != null -> true
                else -> db.culling().pendingFor(itemId).isNotEmpty()
            }
        }
    }

    // --- Server originals for hand-off links and the image core ---------------------------------------------------

    /** Whether the network now is unmetered (Wi-Fi, Ethernet): originals are kept while viewing only then. */
    fun unmetered(): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }.getOrDefault(false)

    /**
     * The offline thumbnails job (ADR 0021): every server item without one, newest first, in pages; the server is
     * asked to make the thumbs it has not made yet (warm), then each is fetched, scaled down and stored.
     */
    suspend fun prefetchThumbs(stopped: () -> Boolean): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val api = sync.api() ?: return@withContext true
        var offset = 0
        val missing = java.util.concurrent.atomic.AtomicInteger()
        val tmp = File(context.cacheDir, "offline-thumb-tmp").apply { mkdirs() }
        while (!stopped()) {
            val ids = db.items().idsNewestFirst(PAGE, offset)
            if (ids.isEmpty()) break
            offset += ids.size
            val need = ids.filter { !offlineThumbs.has(it) }
            if (need.isEmpty()) continue
            runCatching { api.warm(dev.hfps.hoard.server.api.WarmRequest(need, listOf("thumb"))) }
            missing.addAndGet(
                fetchEach(need, PARALLEL_THUMBS) { id ->
                    if (stopped()) return@fetchEach true
                    val file = File(tmp, "$id.webp")
                    try {
                        val r = runCatching { api.downloadPreview(api.previewPath(id, "thumb", "webp"), file) }.getOrNull()
                        if (r is dev.hfps.hoard.server.api.PreviewResult.Ready) {
                            runCatching { offlineThumbs.store(id, file) }
                            true
                        } else {
                            false
                        }
                    } finally {
                        file.delete()
                    }
                },
            )
        }
        Log.i(TAG, "offline thumbnails: ${offlineThumbs.count()} kept, ${missing.get()} not ready yet")
        missing.get() == 0 && !stopped()
    }

    suspend fun api(): ServerApi? = sync.api()

    /** Settings: keep thumbnails of the whole library (default on), only while charging (default off), originals budget. */
    var settings: dev.hfps.hoard.data.SettingsStore? = null

    suspend fun offlineThumbsOn(): Boolean = settings?.getString(KEY_OFFLINE_THUMBS) != "off"
    suspend fun chargingOnly(): Boolean = settings?.getString(KEY_THUMBS_CHARGING) == "on"

    suspend fun scheduleOfflineThumbs() {
        if (db.account().get() == null || !offlineThumbsOn()) {
            dev.hfps.hoard.server.sync.OfflineThumbsWorker.cancel(context)
            return
        }
        dev.hfps.hoard.server.sync.OfflineThumbsWorker.schedule(context, chargingOnly())
        dev.hfps.hoard.server.sync.OfflineThumbsWorker.soon(context, chargingOnly())
    }

    suspend fun serverId(): String? = db.account().get()?.serverId

    /** The cached server values + pending changes of an item. */
    suspend fun values(itemId: String): Pair<CullingEntity?, List<PendingCullingEntity>> = db.culling().get(itemId) to db.culling().pendingFor(itemId)

    companion object {
        const val TAG = "HoardGallery"
        const val DEFAULT_CACHE_BYTES = 1024L shl 20
        const val DEFAULT_ORIGINALS_BYTES = 2048L shl 20
        const val PAGE = 200
        const val PARALLEL_THUMBS = 4

        /** Runs [fetch] on every id, [parallel] at a time; returns how many answered false (counted atomically). */
        internal suspend fun fetchEach(ids: List<String>, parallel: Int, fetch: suspend (String) -> Boolean): Int {
            val missing = java.util.concurrent.atomic.AtomicInteger()
            kotlinx.coroutines.coroutineScope {
                val slots = kotlinx.coroutines.sync.Semaphore(parallel)
                for (id in ids) {
                    launch {
                        slots.acquire()
                        try {
                            if (!fetch(id)) missing.incrementAndGet()
                        } finally {
                            slots.release()
                        }
                    }
                }
            }
            return missing.get()
        }
        const val KEY_OFFLINE_THUMBS = "galleryOfflineThumbs"
        const val KEY_THUMBS_CHARGING = "galleryThumbsCharging"
        const val KEY_ORIGINALS_MB = "galleryOriginalsMb"
    }
}
