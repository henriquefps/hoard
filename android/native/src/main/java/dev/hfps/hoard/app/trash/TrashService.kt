package dev.hfps.hoard.app.trash

import android.os.Build
import android.util.Log
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.files.BrowseLocation
import dev.hfps.hoard.app.files.FileService
import dev.hfps.hoard.app.files.LocationKind
import dev.hfps.hoard.app.transfers.DEVICE_LOCATION_ID
import dev.hfps.hoard.data.TrashDao
import dev.hfps.hoard.data.TrashEntryEntity
import dev.hfps.hoard.data.TrashStates
import dev.hfps.hoard.server.api.Features
import dev.hfps.hoard.server.api.NasWalkRequest
import dev.hfps.hoard.server.paths.SharePaths
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.smb.SmbEntry
import dev.hfps.hoard.smb.location.HoardFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which trash a location uses (ADR 0023 "Where the trash is, per location"): a `.hoard-trash` folder ([FOLDER]: SMB
 * shares, USB drives and SD cards, the phone below Android 11) or the phone's MediaStore trash ([MEDIA_STORE], phase
 * A3). Each kind is one [TrashBackend].
 */
enum class TrashKind {
    FOLDER,
    MEDIA_STORE,
    ;

    companion object {
        fun of(locationId: String, sdkInt: Int = Build.VERSION.SDK_INT): TrashKind =
            if (locationId == DEVICE_LOCATION_ID && sdkInt >= Build.VERSION_CODES.R) MEDIA_STORE else FOLDER
    }
}

/**
 * One kind of trash. [FolderTrashBackend] (A1) moves files into `.hoard-trash` folders through [TrashRunner]; the
 * MediaStore trash of the phone (A3: `IS_TRASHED`, `createTrashRequest` in batches of 500) plugs in as another
 * implementation with the same calls, writing the same `trash_entries` rows (`media_uri`, `expires_at`).
 */
interface TrashBackend {
    val kind: TrashKind

    suspend fun trash(plan: TrashPlan, cancelled: () -> Boolean, progress: (TrashProgress) -> Unit): TrashReport

    suspend fun restore(entries: List<TrashEntryEntity>): List<RestoreOutcome>

    suspend fun empty(entries: List<TrashEntryEntity>, cancelled: () -> Boolean): List<TrashEntryEntity>
}

/** The `.hoard-trash` folders, through [TrashRunner]. */
class FolderTrashBackend(val runner: TrashRunner) : TrashBackend {
    override val kind = TrashKind.FOLDER

    override suspend fun trash(plan: TrashPlan, cancelled: () -> Boolean, progress: (TrashProgress) -> Unit) = runner.trash(plan, cancelled, progress)

    override suspend fun restore(entries: List<TrashEntryEntity>) = runner.restore(entries)

    override suspend fun empty(entries: List<TrashEntryEntity>, cancelled: () -> Boolean) = runner.empty(entries, cancelled)
}

/** [TrashIndex] over `hoard.db`. */
class RoomTrashIndex(private val dao: TrashDao) : TrashIndex {
    override suspend fun put(rows: List<TrashEntryEntity>) = dao.put(rows)
    override suspend fun setState(ids: List<String>, state: String) = dao.setState(ids, state)
    override suspend fun delete(ids: List<String>) = dao.delete(ids)
    override suspend fun unsettled() = dao.unsettled()
    override suspend fun inRoot(place: TrashPlace, root: String) = dao.inRoot(place.locationId, place.share, root)
    override suspend fun touchRoot(place: TrashPlace, root: String, serverFolderId: String?, listedAt: Long?) =
        dao.touchRoot(place.locationId, place.share, root, serverFolderId, listedAt)
}

/**
 * The trash (ADR 0023), the app's side: plans a selection ([prepare]: whole shots with their sidecars, the trash root
 * from the ADR 0022 mappings), runs it ([run]: progress and Cancel, one run at a time), then asks the server to walk
 * the NAS folders it touched (`POST /v1/nas/walk`, only when the server lists `trash`). Restore, Empty and auto-empty
 * ([autoEmpty], from [TrashCleanupWorker]) go through the same rows; [resume] settles what a killed process left.
 *
 * Trashed shots are hidden locally at once ([hidden]), before the server's feed confirms. The screens (A2) and the
 * Gallery (A4) build on these calls; it is not a transfer: no bytes move (CLAUDE.md "one engine" is about bytes).
 */
class TrashService(private val container: AppContainer) {
    private val dao: TrashDao get() = container.database.trash()
    private val index = RoomTrashIndex(container.database.trash())

    val runner = TrashRunner(index, ::fsFor)
    private val folders = FolderTrashBackend(runner)

    /** The phone's MediaStore trash (Android 11+, phase A3). */
    private val media: MediaStoreTrashBackend? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStoreTrashBackend(index, AndroidMediaStoreOps(container.appContext)) else null

    fun backend(kind: TrashKind): TrashBackend? = when (kind) {
        TrashKind.FOLDER -> folders
        TrashKind.MEDIA_STORE -> media
    }

    private val running = Mutex()
    private val cancelRequested = AtomicBoolean(false)
    private val _progress = MutableStateFlow<TrashProgress?>(null)

    /** The current run's progress (null when none runs). */
    val progress: StateFlow<TrashProgress?> = _progress.asStateFlow()

    /** Every row of the index (the Trash screen). */
    fun observe(): Flow<List<TrashEntryEntity>> = dao.observeAll()

    /** Files trashed or being trashed: hidden from Files' badges and the Gallery before the server's feed says so. */
    val hidden: Flow<List<TrashEntryEntity>> = dao.observeAll().map { rows -> rows.filter { it.state != TrashStates.RESTORING } }

    /** The file access of a place, or null when its location is not known now (a removed machine, an absent drive). */
    private suspend fun fsFor(place: TrashPlace): TrashFs? {
        val location = locationOf(place) ?: return null
        return object : TrashFs {
            override suspend fun <T> use(block: (HoardFs) -> T): T = container.files.run(location) { fs, _ -> block(fs) }
        }
    }

    private suspend fun locationOf(place: TrashPlace): BrowseLocation? = withContext(Dispatchers.IO) {
        val machines = container.database.machines().all()
        val volumes = runCatching { container.volumeRegistry.list() }.getOrDefault(emptyList())
        FileService.resolve(place.locationId, place.share.ifEmpty { null }, machines, volumes, "", "")
    }

    /**
     * Plans trashing [selected] paths of [folder] in [location], whose current listing is [listing] (ADR 0023): whole
     * shots with their sidecars, plain files as they are; the trash root from the folder's mapping (learned now if
     * needed: the same two reads as opening the folder); refusals up front (inside a trash already, the server
     * unreachable for an unknown folder, the phone's MediaStore trash before A3).
     */
    suspend fun prepare(location: BrowseLocation, folder: String, listing: List<SmbEntry>, selected: Collection<String>, wholeShots: Boolean = true): TrashPlan {
        val place = TrashPlace(location.id, if (location.isSmb) location.share.orEmpty() else "")
        val files = listing.map { TrashFile(it.path, it.size, IsoTime.parse(it.modifiedAt), it.isDirectory) }
        val units = TrashPlanner.units(folder, files, selected, wholeShots)
        val account = runCatching { container.gallery.db.account().get() }.getOrNull()
        val serverHasTrash = account?.has(Features.TRASH) == true
        if (backend(TrashKind.of(location.id)) == null) {
            return TrashPlan(place, emptyList(), units.map { RefusedUnit(it, TrashRefusal.PHONE_TRASH_UNAVAILABLE) })
        }
        if (TrashKind.of(location.id) == TrashKind.MEDIA_STORE) {
            // MediaStore trashes files: a folder cannot go (the dialog offers today's delete for it).
            val (folders, filesOnly) = units.partition { u -> u.files.any { it.isDirectory } }
            val plan = TrashPlanner.plan(place, filesOnly, RootChoice.Root(""), serverHasTrash = false)
            return plan.copy(refused = plan.refused + folders.map { RefusedUnit(it, TrashRefusal.FOLDER_NOT_TRASHABLE) })
        }
        // Selected folders are walked once (bounded): what they hold decides both the refusals (a `.hoard-trash`
        // inside) and, on a share, the mapping when the folder on screen has no photos of its own.
        val dirs = units.flatMap { u -> u.files.filter { it.isDirectory }.map { it.path } }
        val walk = try {
            if (dirs.isEmpty()) DirWalk.EMPTY else container.files.run(location) { fs, cancel -> DirWalk.of(MappedHoardFs(fs), dirs) { cancel.check() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return TrashPlan(place, emptyList(), units.map { RefusedUnit(it, TrashRefusal.UNREACHABLE) })
        }
        val protectedPaths = ArrayList<String>()
        dao.roots().filter { it.locationId == place.locationId && it.share.equals(place.share, ignoreCase = true) }.forEach { protectedPaths += it.rootPath }
        val choiceFor: (TrashUnit) -> RootChoice
        var items: (String) -> String? = { null }
        if (location.kind == LocationKind.Smb) {
            val links = container.serverLinks
            val ref = links.ref(location, folder) ?: return TrashPlan(place, emptyList(), units.map { RefusedUnit(it, TrashRefusal.UNREACHABLE) })
            runCatching { links.paths.mappings() }.getOrDefault(emptyList())
                .filter { it.sameShare(ref.machineId, ref.share) }
                .forEach { protectedPaths += it.smbPrefix }
            val state = try {
                links.paths.mappingState(ref, links.filesOf(listing), links.reader(location)) {
                    dev.hfps.hoard.server.paths.SelectionMedia(walk.media, complete = walk.incomplete.isEmpty())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "mapping of ${ref.key} failed", e)
                dev.hfps.hoard.server.paths.MappingState.Unreachable
            }
            choiceFor = { unit -> TrashPlanner.smbRoot(unit.files.first().path, state) }
            links.paths.cached(ref)?.let { r -> items = { path -> r.itemOf(path) } }
        } else {
            val choice = try {
                TrashPlanner.localRoot(container.files.run(location) { fs, _ -> fs.moveRootOf(folder) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                RootChoice.Refused(TrashRefusal.UNREACHABLE)
            }
            choiceFor = { choice }
        }
        return TrashPlanner.plan(place, units, choiceFor, serverHasTrash, items, protectedPaths) { dir -> dir in walk.holdsTrash || dir in walk.incomplete }
    }

    /**
     * A bounded walk of the selected folders: the photos and videos under them ([media]), which of them hold a
     * `.hoard-trash` somewhere below ([holdsTrash]), and which could not be walked in full ([incomplete]: too deep,
     * too many entries, or unreadable), which the plan treats as holding one.
     */
    class DirWalk(val media: List<dev.hfps.hoard.server.paths.MappingDiscovery.SmbFile>, val holdsTrash: Set<String>, val incomplete: Set<String>) {
        companion object {
            val EMPTY = DirWalk(emptyList(), emptySet(), emptySet())
            const val MAX_ENTRIES = 20_000
            const val MAX_DEPTH = 16

            fun of(fs: HoardFs, dirs: List<String>, check: () -> Unit = {}): DirWalk {
                val media = ArrayList<dev.hfps.hoard.server.paths.MappingDiscovery.SmbFile>()
                val holds = HashSet<String>()
                val incomplete = HashSet<String>()
                var seen = 0
                for (dir in dirs) {
                    val stack = ArrayDeque(listOf(dir to 0))
                    while (stack.isNotEmpty()) {
                        val (at, depth) = stack.removeLast()
                        check()
                        val entries = try {
                            fs.list(at).entries
                        } catch (e: dev.hfps.hoard.smb.HoardSmbException) {
                            incomplete += dir
                            break
                        }
                        seen += entries.size
                        if (seen > MAX_ENTRIES) {
                            incomplete += dir
                            break
                        }
                        for (e in entries) {
                            if (e.isDirectory) {
                                if (e.name.equals(TrashPaths.DIR, ignoreCase = true)) {
                                    holds += dir
                                } else if (depth + 1 > MAX_DEPTH) {
                                    incomplete += dir
                                } else {
                                    stack.addLast(e.path to depth + 1)
                                }
                            } else if (TrashPlanner.isMedia(e.name) && media.size < MAX_MEDIA) {
                                media += dev.hfps.hoard.server.paths.MappingDiscovery.SmbFile(e.path, e.size, IsoTime.parse(e.modifiedAt) ?: 0L)
                            }
                        }
                        if (dir in holds) break
                    }
                }
                return DirWalk(media, holds, incomplete)
            }

            private const val MAX_MEDIA = 500
        }
    }

    /** [prepare] on a fresh listing of [folder] (the dialog's plan is never made from a stale one). */
    suspend fun prepareFolder(location: BrowseLocation, folder: String, selected: Collection<String>, wholeShots: Boolean = true): TrashPlan {
        val listing = container.files.run(location) { fs, cancel -> fs.list(folder, cancel) }.entries
        return prepare(location, folder, listing, selected, wholeShots)
    }

    private val _changes = MutableStateFlow(0)

    /** Bumped after every run, restore, Empty and auto-empty: open folders and culling grids refresh from it. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun changed() = _changes.update { it + 1 }

    private val _active = MutableStateFlow(false)

    /** A run is going (the foreground notification follows it). */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /**
     * Starts [plan] in the process scope (leaving the screen does not stop it), with the foreground notification when
     * it moves more than [NOTIFY_FILES] files; the result arrives on the returned [Deferred].
     */
    fun start(plan: TrashPlan): kotlinx.coroutines.Deferred<TrashReport> {
        _active.value = true
        if (plan.files > NOTIFY_FILES) TrashRunService.start(container.appContext)
        return container.scope.async {
            try {
                run(plan)
            } finally {
                _active.value = false
            }
        }
    }

    /**
     * The permanent delete the dialog offers for units the trash refused (ADR 0023 "When the trash is not possible"):
     * every file of each unit (folders recursively). Returns the units that went entirely.
     */
    suspend fun deletePermanently(location: BrowseLocation, units: List<TrashUnit>): List<TrashUnit> {
        val gone = ArrayList<TrashUnit>()
        for (unit in units) {
            val ok = unit.all.all { f ->
                try {
                    container.files.run(location) { fs, cancel -> fs.delete(f.path, f.isDirectory, cancel) }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: dev.hfps.hoard.smb.HoardSmbException) {
                    e.code == dev.hfps.hoard.smb.ErrorCode.NOT_FOUND
                } catch (e: Throwable) {
                    false
                }
            }
            if (ok) gone += unit
        }
        if (location.isSmb) units.map { it.folder }.distinct().forEach { container.serverLinks.invalidate(location, it) }
        changed()
        return gone
    }

    /** Asks the running [run] to stop after the unit it is moving (a unit is never left half-moved by Cancel). */
    fun cancel() = cancelRequested.set(true)

    /**
     * Runs [plan] (one run at a time), then: the server is asked to walk the NAS folders it touched (feature `trash`
     * only, ADR 0023 "Older servers"), and the touched SMB folders' cached server answers are dropped.
     */
    suspend fun run(plan: TrashPlan): TrashReport = running.withLock {
        cancelRequested.set(false)
        try {
            val backend = backend(TrashKind.of(plan.place.locationId))
            val report = backend?.trash(plan, { cancelRequested.get() }) { _progress.value = it }
                ?: TrashReport(plan.place, plan.units.map { TrashOutcome.Refused(it.unit, TrashRefusal.PHONE_TRASH_UNAVAILABLE) } + plan.refused.map { TrashOutcome.Refused(it.unit, it.reason) })
            val touched = report.entries
            touched.maxOfOrNull { it.trashedAt }?.let { newest ->
                val before = container.settingsStore.getJson(NEWEST_TRASHED)?.toLongOrNull() ?: 0L
                if (newest > before) container.settingsStore.putJson(NEWEST_TRASHED, newest.toString())
            }
            if (touched.isNotEmpty()) {
                invalidate(plan.place, touched)
                requestWalk(touched.mapNotNull { it.serverFolderId })
            }
            Log.i(TAG, "trash ${plan.place.locationId}: ${report.trashed.size} trashed, ${report.refused.size} refused, ${report.partial.size} partial, ${report.cancelled.size} cancelled")
            for (r in report.refused) Log.i(TAG, "refused ${r.unit.key}: ${r.reason} ${r.code ?: ""} ${r.message.orEmpty()}")
            for (p in report.partial) Log.i(TAG, "partial ${p.unit.key}: ${p.reason} failed ${p.failed} ${p.message.orEmpty()}")
            report
        } finally {
            _progress.value = null
            changed()
        }
    }

    private val recovered = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val recovering = AtomicBoolean(false)

    /**
     * Start-up recovery runs once before anything else that walks the trash (auto-empty can be the first caller, from
     * WorkManager, before the app's own start): later callers wait for it.
     */
    private suspend fun ensureRecovered() {
        if (recovering.compareAndSet(false, true)) {
            try {
                running.withLock { settle() }
            } finally {
                recovered.complete(Unit)
            }
        }
        recovered.await()
    }

    /** Settles rows a killed process left (app start). Places that cannot be reached now are tried at the next start. */
    suspend fun resume(): ResumeReport? {
        if (recovering.compareAndSet(false, true)) {
            try {
                return running.withLock { settle() }
            } finally {
                recovered.complete(Unit)
            }
        }
        recovered.await()
        return running.withLock { settle() }
    }

    private suspend fun settle(): ResumeReport? {
        val unsettled = dao.unsettled()
        if (unsettled.isEmpty()) return null
        media?.let { m -> unsettled.filter { it.mediaUri != null }.takeIf { it.isNotEmpty() }?.let { m.reconcile(it) } }
        return runner.resume().also { r ->
            Log.i(TAG, "resume: ${r.settled.size} settled, ${r.rolledBack.size} rolled back, ${r.dropped.size} dropped, ${r.unreachable.size} places unreachable")
            if (r.settled.isNotEmpty()) requestWalk(r.settled.mapNotNull { it.serverFolderId })
        }
    }

    /** Restores the rows [ids] (ADR 0023 "Restore"): exact original paths, a conflict refuses the unit. */
    suspend fun restore(ids: List<String>): List<RestoreOutcome> = running.withLock {
        val rows = wholeUnits(dao.byIds(ids))
        val out = ArrayList<RestoreOutcome>()
        for ((kind, inKind) in rows.groupBy { TrashKind.of(it.locationId) }) {
            val backend = backend(kind) ?: continue
            out += backend.restore(inKind)
        }
        val back = out.flatMap { o ->
            when (o) {
                is RestoreOutcome.Restored -> o.entries
                is RestoreOutcome.Partial -> o.restored
                else -> emptyList()
            }
        }
        if (back.isNotEmpty()) {
            back.groupBy { TrashPlace(it.locationId, it.share) }.forEach { (place, r) -> invalidate(place, r) }
            requestWalk(back.mapNotNull { it.serverFolderId })
        }
        changed()
        out
    }

    /** Empties the rows [ids] for good (after the dialog's confirmation). Returns the rows purged. */
    suspend fun empty(ids: List<String>): List<TrashEntryEntity> = running.withLock {
        cancelRequested.set(false)
        purge(wholeUnits(dao.byIds(ids)))
    }

    /**
     * [rows] completed to whole units (review of PR #58): every row of the same shot (or plain item) in the same day
     * folder of the same root, so restore and Empty never split a shot, whatever ids the caller passed.
     */
    private suspend fun wholeUnits(rows: List<TrashEntryEntity>): List<TrashEntryEntity> {
        val out = LinkedHashMap<String, TrashEntryEntity>()
        rows.forEach { out[it.id] = it }
        for ((key, inUnit) in rows.groupBy { listOf(it.locationId, it.share, it.trashRoot, it.dayFolder) }) {
            dao.units(key[0], key[1], key[2], key[3], inUnit.map { it.shotKey }.distinct()).forEach { out.putIfAbsent(it.id, it) }
        }
        return out.values.toList()
    }

    /** Empty trash: every root is listed first (what other devices trashed is emptied too), then everything goes. */
    suspend fun emptyAll(): List<TrashEntryEntity> = running.withLock {
        cancelRequested.set(false)
        refreshRoots()
        purge(dao.all())
    }

    private suspend fun purge(rows: List<TrashEntryEntity>): List<TrashEntryEntity> {
        val purged = ArrayList<TrashEntryEntity>()
        for ((kind, inKind) in rows.groupBy { TrashKind.of(it.locationId) }) {
            purged += backend(kind)?.empty(inKind) { cancelRequested.get() } ?: continue
        }
        afterPurge(purged)
        changed()
        return purged
    }

    /**
     * Lists every known root's `.hoard-trash` (the Trash screen's refresh): rows for what another device trashed.
     * Returns the places that could not be reached (their rows show greyed, "Not reachable").
     */
    suspend fun refresh(): Set<TrashPlace> = running.withLock { refreshRoots() }

    /** The roots, as the Trash screen lists them. */
    fun observeRoots() = dao.observeRoots()

    private suspend fun refreshRoots(): Set<TrashPlace> {
        // The phone's trash: rows Android purged or the user restored elsewhere are forgotten, expiry dates updated.
        media?.let { m -> withContext(Dispatchers.IO) { m.reconcile(dao.all().filter { it.mediaUri != null }) } }
        val unreachable = HashSet<TrashPlace>()
        for (root in roots()) {
            if (TrashKind.of(root.place.locationId) != TrashKind.FOLDER) continue
            if (root.place in unreachable) continue
            if (fsFor(root.place) == null) {
                unreachable += root.place
                continue
            }
            runCatching { runner.scan(root) }.onFailure {
                if (it is CancellationException) throw it
                unreachable += root.place
            }
        }
        return unreachable
    }

    /** Every root Hoard trashed into or found, and those of rows not recorded as roots. */
    private suspend fun roots(): List<TrashRunner.Root> {
        val listed = dao.roots().map { TrashRunner.Root(TrashPlace(it.locationId, it.share), it.rootPath, it.serverFolderId) }
        val fromRows = dao.all().map { TrashRunner.Root(TrashPlace(it.locationId, it.share), it.trashRoot, it.serverFolderId) }
        return (listed + fromRows).distinctBy { Triple(it.place.locationId, it.place.share.lowercase(), it.path.lowercase()) }
    }

    /**
     * Auto-empty (ADR 0023, [TrashCleanupWorker]): day folders older than the setting in every reachable root. Off
     * (0) does nothing. Returns how many day folders went.
     */
    suspend fun autoEmpty(): Int {
        ensureRecovered()
        return running.withLock { autoEmptyLocked() }
    }

    /** What auto-empty would delete now with [days] (Settings asks before a shorter period applies). Deletes nothing. */
    data class AutoEmptyPreview(val files: Int, val bytes: Long, val places: Int, val othersIncluded: Boolean) {
        val empty: Boolean get() = files == 0
    }

    suspend fun previewAutoEmpty(days: Int): AutoEmptyPreview {
        ensureRecovered()
        return running.withLock {
            val notBefore = maxOf(dao.newestOwnTrashedAt() ?: 0L, container.settingsStore.getJson(NEWEST_TRASHED)?.toLongOrNull() ?: 0L)
            val expired = runner.autoEmpty(roots().filter { TrashKind.of(it.place.locationId) == TrashKind.FOLDER }, days, notBefore, ::networkTime, dryRun = true)
            AutoEmptyPreview(expired.sumOf { it.files }, expired.sumOf { it.bytes }, expired.map { it.root.place }.distinct().size, expired.any { it.othersIncluded })
        }
    }

    private suspend fun autoEmptyLocked(): Int {
        val days = container.appSettings.trashAutoEmptyDaysNow()
        if (days <= 0) return 0
        // A clock that went back: nothing runs until it is past the newest trash time this device wrote.
        val notBefore = maxOf(dao.newestOwnTrashedAt() ?: 0L, container.settingsStore.getJson(NEWEST_TRASHED)?.toLongOrNull() ?: 0L)
        val expired = runner.autoEmpty(roots().filter { TrashKind.of(it.place.locationId) == TrashKind.FOLDER }, days, notBefore, ::networkTime)
        // The phone's trash: only Hoard's own rows (no consent can be shown here); Android expires the rest itself.
        val phonePurged = media?.let { m ->
            val nowMs = listOfNotNull(System.currentTimeMillis(), networkTime()).min()
            if (nowMs < notBefore) emptyList() else m.autoEmpty(dao.all().filter { it.mediaUri != null }, nowMs - days * 86_400_000L)
        }.orEmpty()
        if (phonePurged.isNotEmpty()) {
            afterPurge(phonePurged)
            changed()
        }
        afterPurge(expired.flatMap { it.purged })
        requestWalk(expired.mapNotNull { it.root.serverFolderId })
        if (expired.isNotEmpty()) changed()
        return expired.sumOf { it.dayFolders.size }
    }

    /** The network's time when Android has one (API 33+), a clock the phone's own setting cannot move. */
    private fun networkTime(): Long? = if (Build.VERSION.SDK_INT >= 33) {
        runCatching { android.os.SystemClock.currentNetworkTimeClock().millis() }.getOrNull()
    } else {
        null
    }

    /** Purged shots: their folder culling rows are forgotten (as "Delete rejects" did), and the server walks. */
    private suspend fun afterPurge(purged: List<TrashEntryEntity>) {
        if (purged.isEmpty()) return
        val cull = container.database.cull()
        for (row in purged.filter { TrashPlanner.isMedia(SharePaths.name(it.originalPath)) }.distinctBy { Triple(it.locationId, it.share, it.shotKey) }) {
            val folder = SharePaths.parent(row.originalPath)
            runCatching { cull.forgetShots(row.locationId, row.share, folder, listOf(SharePaths.name(row.shotKey))) }
        }
        requestWalk(purged.mapNotNull { it.serverFolderId })
    }

    /** Hoard changed these SMB folders: their cached server answers go (ADR 0022 "Cache"). */
    private suspend fun invalidate(place: TrashPlace, rows: List<TrashEntryEntity>) {
        if (!place.isSmb) return
        val links = runCatching { container.serverLinks }.getOrNull() ?: return
        for (folder in rows.map { SharePaths.parent(it.originalPath) }.distinct()) links.invalidate(place.locationId, place.share, folder)
    }

    /** `POST /v1/nas/walk` for [folderIds] (≤ 100 per call), only when the paired server lists `trash`. Best effort. */
    private suspend fun requestWalk(folderIds: List<String>) {
        val ids = folderIds.distinct()
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val account = container.gallery.db.account().get() ?: return@runCatching
                if (!account.has(Features.TRASH)) return@runCatching
                val api = container.gallery.api() ?: return@runCatching
                for (chunk in ids.chunked(NasWalkRequest.MAX_FOLDERS)) api.nasWalk(chunk)
            }.onFailure { if (it is CancellationException) throw it else Log.w(TAG, "walk request failed", it) }
        }
    }

    companion object {
        private const val TAG = "HoardTrash"

        /** Settings key: the newest trash time this device wrote (outlives Empty; auto-empty's clock guard). */
        const val NEWEST_TRASHED = "trashNewestTrashedAt"

        /** Runs that move more files than this show the foreground notification. */
        const val NOTIFY_FILES = 20
    }
}
