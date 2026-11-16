package dev.hfps.hoard.app.trash

import dev.hfps.hoard.data.TrashEntryEntity
import dev.hfps.hoard.data.TrashStates
import dev.hfps.hoard.server.paths.SharePaths
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.SmbErrors
import dev.hfps.hoard.smb.location.HoardFs
import dev.hfps.hoard.smb.location.LocalErrors
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** The trash rows the runner keeps (`trash_entries` / `trash_roots` of `hoard.db`; in memory in tests). */
interface TrashIndex {
    suspend fun put(rows: List<TrashEntryEntity>)
    suspend fun setState(ids: List<String>, state: String)
    suspend fun delete(ids: List<String>)
    suspend fun unsettled(): List<TrashEntryEntity>
    suspend fun inRoot(place: TrashPlace, root: String): List<TrashEntryEntity>
    suspend fun touchRoot(place: TrashPlace, root: String, serverFolderId: String?, listedAt: Long? = null)
}

/** File access to one place: [use] runs a block of blocking calls on its [HoardFs] (an IO thread, a session). */
interface TrashFs {
    suspend fun <T> use(block: (HoardFs) -> T): T
}

/** Progress of a trash run (A2's dialog and notification). */
data class TrashProgress(
    val unitsTotal: Int,
    val unitsDone: Int,
    val filesTotal: Int,
    val filesDone: Int,
    val bytesTotal: Long,
    val bytesDone: Long,
    val currentName: String? = null,
)

/** What happened to one unit of a run. */
sealed interface TrashOutcome {
    val unit: TrashUnit

    data class Trashed(override val unit: TrashUnit, val entries: List<TrashEntryEntity>) : TrashOutcome

    /** Nothing of the unit moved; it is as it was. */
    data class Refused(override val unit: TrashUnit, val reason: TrashRefusal, val code: ErrorCode? = null, val message: String? = null) : TrashOutcome

    /** Some files moved (they stay in the trash, restorable), [failed] did not. */
    data class Partial(override val unit: TrashUnit, val entries: List<TrashEntryEntity>, val failed: List<String>, val reason: TrashRefusal, val message: String? = null) : TrashOutcome

    /** Not started: the run was cancelled before it. */
    data class Cancelled(override val unit: TrashUnit) : TrashOutcome
}

data class TrashReport(val place: TrashPlace, val outcomes: List<TrashOutcome>) {
    val trashed: List<TrashOutcome.Trashed> get() = outcomes.filterIsInstance<TrashOutcome.Trashed>()
    val refused: List<TrashOutcome.Refused> get() = outcomes.filterIsInstance<TrashOutcome.Refused>()
    val partial: List<TrashOutcome.Partial> get() = outcomes.filterIsInstance<TrashOutcome.Partial>()
    val cancelled: List<TrashOutcome.Cancelled> get() = outcomes.filterIsInstance<TrashOutcome.Cancelled>()

    /** Every row now in the trash (trashed and half-moved units). */
    val entries: List<TrashEntryEntity> get() = trashed.flatMap { it.entries } + partial.flatMap { it.entries }
}

/** A restore of one unit (its rows grouped by place, root, day folder and shot). */
sealed interface RestoreOutcome {
    val entries: List<TrashEntryEntity>

    data class Restored(override val entries: List<TrashEntryEntity>) : RestoreOutcome

    /** A file exists at an original path ([paths]): nothing of the unit moved (ADR 0023: refused, never renamed). */
    data class Conflict(override val entries: List<TrashEntryEntity>, val paths: List<String>) : RestoreOutcome

    /** The trashed files are gone (emptied elsewhere): their rows are forgotten. */
    data class Missing(override val entries: List<TrashEntryEntity>) : RestoreOutcome

    /** [restored] went back, [failed] did not (they stay in the trash). */
    data class Partial(override val entries: List<TrashEntryEntity>, val restored: List<TrashEntryEntity>, val failed: List<TrashEntryEntity>, val code: ErrorCode?, val message: String?) : RestoreOutcome

    data class Failed(override val entries: List<TrashEntryEntity>, val code: ErrorCode?, val message: String?) : RestoreOutcome
}

/** What a resume after a kill did. */
data class ResumeReport(
    /** Rows settled as trashed (their move had happened, or was completed now). */
    val settled: List<TrashEntryEntity>,
    /** Rows of units that had not started: forgotten, the files are where they were. */
    val rolledBack: List<TrashEntryEntity>,
    /** Rows whose files were found nowhere, or that could not be completed. */
    val dropped: List<TrashEntryEntity>,
    /** Places that could not be reached: their rows wait for the next resume. */
    val unreachable: List<TrashPlace>,
)

/**
 * The trash engine (ADR 0023 "Moves"), over a place's [HoardFs] and the [TrashIndex]. No bytes move: every step is a
 * rename inside one location. Per unit (a shot or a plain item):
 * - its rows are written first (`moving`), then each file is moved (media files, then sidecars) and its row settled
 *   (`trashed`);
 * - a unit whose first file fails is left as it was (rows dropped, refused); a half-moved unit is reported and its
 *   moved files stay in the trash;
 * - Cancel is checked between units, never inside one.
 * A killed process leaves `moving` rows: [resume] settles them by a `stat` of both paths, completes a unit that had
 * started and rolls back one that had not.
 */
class TrashRunner(
    private val index: TrashIndex,
    fsFor: suspend (TrashPlace) -> TrashFs?,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Every place's file system with its failures mapped to contract codes ([MappedHoardFs]). */
    private val fsFor: suspend (TrashPlace) -> TrashFs? = { place ->
        fsFor(place)?.let { raw ->
            object : TrashFs {
                override suspend fun <T> use(block: (HoardFs) -> T): T = raw.use { hoard -> block(MappedHoardFs(hoard)) }
            }
        }
    }

    /** Runs [plan]. [cancelled] is checked between units; [progress] after each file. */
    suspend fun trash(plan: TrashPlan, cancelled: () -> Boolean = { false }, progress: (TrashProgress) -> Unit = {}): TrashReport {
        val place = plan.place
        val outcomes = ArrayList<TrashOutcome>()
        val fs = fsFor(place)
        val filesTotal = plan.files
        var filesDone = 0
        var bytesDone = 0L
        var unitsDone = 0
        fun report(name: String?) = progress(TrashProgress(plan.units.size, unitsDone, filesTotal, filesDone, plan.bytes, bytesDone, name))
        plan.refused.forEach { outcomes += TrashOutcome.Refused(it.unit, it.reason, message = it.message) }
        val runId = newId()
        val day = today()
        /** Roots that refused (read-only, another filesystem): the other units there are refused at once. */
        val failedRoots = HashMap<String, TrashOutcome.Refused>()
        var placeDown = false
        val readyRoots = HashSet<String>()
        val planned = HashSet<String>()
        report(null)
        for (p in plan.units) {
            if (cancelled()) {
                outcomes += TrashOutcome.Cancelled(p.unit)
                continue
            }
            val outcome = if (fs == null || placeDown) {
                TrashOutcome.Refused(p.unit, TrashRefusal.UNREACHABLE)
            } else {
                failedRoots[p.root]?.let { it.copy(unit = p.unit) } ?: trashUnit(fs, place, p, runId, day, readyRoots, planned) { file ->
                    filesDone++
                    bytesDone += file.size
                    report(file.name)
                }
            }
            if (outcome is TrashOutcome.Refused && outcome.reason in ROOT_REASONS) failedRoots[p.root] = outcome
            // The share or drive went away: every other unit would wait for its own timeout. Stop the batch.
            val gone = (outcome as? TrashOutcome.Refused)?.reason == TrashRefusal.UNREACHABLE || (outcome as? TrashOutcome.Partial)?.reason == TrashRefusal.UNREACHABLE
            if (gone) placeDown = true
            outcomes += outcome
            unitsDone++
            report(null)
        }
        return TrashReport(place, outcomes)
    }

    private suspend fun trashUnit(
        fs: TrashFs,
        place: TrashPlace,
        p: PlannedUnit,
        runId: String,
        day: LocalDate,
        readyRoots: MutableSet<String>,
        planned: MutableSet<String>,
        moved: (TrashFile) -> Unit,
    ): TrashOutcome {
        val unit = p.unit
        val files = unit.all
        // The day folder: the first where none of the unit's targets is taken (on the share, or by this run).
        val dayFolder = try {
            fs.use { hoard ->
                TrashPlanner.dayFolderFor(p.root, day, files.map { it.path }) { target -> target.lowercase() in planned || exists(hoard, target) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return refusal(unit, e)
        }
        val at = now()
        val rows = files.map { f ->
            TrashEntryEntity(
                id = newId(),
                runId = runId,
                locationId = place.locationId,
                share = place.share,
                trashRoot = p.root,
                dayFolder = dayFolder,
                originalPath = f.path,
                trashedPath = TrashPaths.trashedPath(p.root, dayFolder, f.path),
                shotKey = unit.key,
                isDirectory = f.isDirectory,
                size = f.size,
                modifiedMillis = f.modifiedMillis,
                trashedAt = at,
                serverItemId = p.serverItems[f.path],
                serverFolderId = p.serverFolderId,
                state = TrashStates.MOVING,
            )
        }
        rows.forEach { planned += it.trashedPath.lowercase() }
        index.put(rows)
        index.touchRoot(place, p.root, p.serverFolderId)
        // The trash root, the day folder and the directories under it.
        try {
            fs.use { hoard ->
                if (p.root !in readyRoots) {
                    ensureTrashDir(hoard, p.root, local = !place.isSmb)
                    readyRoots += p.root
                }
                for (dir in rows.map { SharePaths.parent(it.trashedPath) }.distinct()) mkdirs(hoard, dir)
            }
        } catch (e: CancellationException) {
            index.delete(rows.map { it.id })
            throw e
        } catch (e: Exception) {
            index.delete(rows.map { it.id })
            val code = codeOf(e)
            return if (code == ErrorCode.ACCESS_DENIED) {
                TrashOutcome.Refused(unit, TrashRefusal.ROOT_NOT_WRITABLE, code, e.message)
            } else {
                refusal(unit, e)
            }
        }
        val done = ArrayList<TrashEntryEntity>()
        for ((i, row) in rows.withIndex()) {
            try {
                fs.use { hoard -> hoard.move(row.originalPath, row.trashedPath) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val rest = rows.drop(i)
                index.delete(rest.map { it.id })
                if (done.isEmpty()) return refusal(unit, e)
                val (reason, _) = reasonOf(e)
                return TrashOutcome.Partial(unit, done, rest.map { it.originalPath }, reason, e.message)
            }
            index.setState(listOf(row.id), TrashStates.TRASHED)
            done += row.copy(state = TrashStates.TRASHED)
            moved(files[i])
        }
        return TrashOutcome.Trashed(unit, done)
    }

    /**
     * Settles the rows a killed run left (ADR 0023: "a `moving` row is settled by a `stat` of both paths"). A unit
     * with any file already in the trash is completed; one with none is rolled back (its rows forgotten). `restoring`
     * rows go back to `trashed` unless the file is back at its place; `purging` rows go back to `trashed` while the
     * file is still in the trash (Empty can run again).
     */
    suspend fun resume(): ResumeReport {
        val settled = ArrayList<TrashEntryEntity>()
        val rolledBack = ArrayList<TrashEntryEntity>()
        val dropped = ArrayList<TrashEntryEntity>()
        val unreachable = ArrayList<TrashPlace>()
        for ((place, rows) in index.unsettled().groupBy { TrashPlace(it.locationId, it.share) }) {
            val fs = fsFor(place)
            if (fs == null) {
                unreachable += place
                continue
            }
            try {
                for (unitRows in rows.groupBy { UnitKey.of(it) }.values) resumeUnit(fs, place, unitRows, settled, rolledBack, dropped)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unreachable += place
            }
        }
        return ResumeReport(settled, rolledBack, dropped, unreachable)
    }

    private suspend fun resumeUnit(
        fs: TrashFs,
        place: TrashPlace,
        rows: List<TrashEntryEntity>,
        settled: MutableList<TrashEntryEntity>,
        rolledBack: MutableList<TrashEntryEntity>,
        dropped: MutableList<TrashEntryEntity>,
    ) {
        val seen = fs.use { hoard -> rows.associateWith { exists(hoard, it.originalPath) to exists(hoard, it.trashedPath) } }
        // Restoring and purging rows: one by one.
        for (row in rows.filter { it.state == TrashStates.RESTORING || it.state == TrashStates.PURGING }) {
            val (original, trashed) = seen.getValue(row)
            if (trashed) {
                index.setState(listOf(row.id), TrashStates.TRASHED)
                settled += row.copy(state = TrashStates.TRASHED)
            } else {
                index.delete(listOf(row.id))
                if (row.state == TrashStates.RESTORING && original) rolledBack += row else dropped += row
            }
        }
        val moving = rows.filter { it.state == TrashStates.MOVING }
        if (moving.isEmpty()) return
        // The unit started when one of its files is in the trash (a settled row of the same unit, or a stat).
        val siblings = index.inRoot(place, moving.first().trashRoot).filter { UnitKey.of(it) == UnitKey.of(moving.first()) && it.state == TrashStates.TRASHED }
        val inTrash = moving.filter { seen.getValue(it).second }
        val started = siblings.isNotEmpty() || inTrash.isNotEmpty()
        for (row in moving) {
            val (original, trashed) = seen.getValue(row)
            when {
                trashed -> {
                    index.setState(listOf(row.id), TrashStates.TRASHED)
                    settled += row.copy(state = TrashStates.TRASHED)
                }
                !original -> {
                    index.delete(listOf(row.id))
                    dropped += row
                }
                !started -> {
                    index.delete(listOf(row.id))
                    rolledBack += row
                }
                else -> {
                    val ok = runCatching {
                        fs.use { hoard ->
                            mkdirs(hoard, SharePaths.parent(row.trashedPath))
                            hoard.move(row.originalPath, row.trashedPath)
                        }
                    }.onFailure { if (it is CancellationException) throw it }.isSuccess
                    if (ok) {
                        index.setState(listOf(row.id), TrashStates.TRASHED)
                        settled += row.copy(state = TrashStates.TRASHED)
                    } else {
                        index.delete(listOf(row.id))
                        dropped += row
                    }
                }
            }
        }
    }

    /**
     * Restores [entries] (ADR 0023 "Restore"): each unit back to its exact original paths, all its files together. A
     * file at an original path refuses the whole unit ([RestoreOutcome.Conflict], never renamed); missing directories
     * are created; empty folders left in the day folder are removed.
     */
    suspend fun restore(entries: List<TrashEntryEntity>): List<RestoreOutcome> {
        val out = ArrayList<RestoreOutcome>()
        for ((place, rows) in entries.filter { it.state == TrashStates.TRASHED }.groupBy { TrashPlace(it.locationId, it.share) }) {
            val fs = fsFor(place)
            for (unit in rows.groupBy { UnitKey.of(it) }.values) {
                out += if (fs == null) RestoreOutcome.Failed(unit, ErrorCode.UNREACHABLE, null) else restoreUnit(fs, unit)
            }
        }
        return out
    }

    private suspend fun restoreUnit(fs: TrashFs, rows: List<TrashEntryEntity>): RestoreOutcome {
        val state = try {
            fs.use { hoard -> rows.associateWith { exists(hoard, it.originalPath) to exists(hoard, it.trashedPath) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RestoreOutcome.Failed(rows, codeOf(e), e.message)
        }
        val gone = rows.filter { !state.getValue(it).second }
        val present = rows - gone.toSet()
        if (gone.isNotEmpty()) index.delete(gone.map { it.id })
        if (present.isEmpty()) return RestoreOutcome.Missing(rows)
        val taken = present.filter { state.getValue(it).first }
        if (taken.isNotEmpty()) return RestoreOutcome.Conflict(rows, taken.map { it.originalPath })
        index.setState(present.map { it.id }, TrashStates.RESTORING)
        val restored = ArrayList<TrashEntryEntity>()
        for ((i, row) in present.withIndex()) {
            try {
                fs.use { hoard ->
                    mkdirs(hoard, SharePaths.parent(row.originalPath))
                    hoard.move(row.trashedPath, row.originalPath)
                }
            } catch (e: CancellationException) {
                index.setState(present.drop(i).map { it.id }, TrashStates.TRASHED)
                throw e
            } catch (e: Exception) {
                val failed = present.drop(i)
                index.setState(failed.map { it.id }, TrashStates.TRASHED)
                cleanUp(fs, restored)
                return if (restored.isEmpty()) RestoreOutcome.Failed(rows, codeOf(e), e.message) else RestoreOutcome.Partial(rows, restored, failed, codeOf(e), e.message)
            }
            index.delete(listOf(row.id))
            restored += row
        }
        cleanUp(fs, restored)
        return RestoreOutcome.Restored(rows)
    }

    /**
     * Deletes [entries] for good (ADR 0023 "Empty"): the files, then the folders they leave empty in their day folder.
     * Returns the rows purged (the caller forgets their culling rows and asks the server for a walk); rows of a place
     * that cannot be reached, or whose delete failed, stay `trashed`.
     */
    suspend fun empty(entries: List<TrashEntryEntity>, cancelled: () -> Boolean = { false }): List<TrashEntryEntity> {
        val purged = ArrayList<TrashEntryEntity>()
        for ((place, rows) in entries.filter { it.state == TrashStates.TRASHED }.groupBy { TrashPlace(it.locationId, it.share) }) {
            val fs = fsFor(place) ?: continue
            val done = ArrayList<TrashEntryEntity>()
            for (row in rows) {
                if (cancelled()) break
                index.setState(listOf(row.id), TrashStates.PURGING)
                val ok = try {
                    fs.use { hoard -> deleteIfExists(hoard, requireTrashPath(row)) }
                    true
                } catch (e: CancellationException) {
                    index.setState(listOf(row.id), TrashStates.TRASHED)
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    index.delete(listOf(row.id))
                    done += row
                } else {
                    index.setState(listOf(row.id), TrashStates.TRASHED)
                }
            }
            cleanUp(fs, done)
            purged += done
        }
        return purged
    }

    /** A root found by listing or remembered: auto-empty and refresh visit each. */
    data class Root(val place: TrashPlace, val path: String, val serverFolderId: String? = null)

    /** What auto-empty did in one root. */
    data class Expired(val root: Root, val dayFolders: List<String>, val purged: List<TrashEntryEntity>, val files: Int = 0, val bytes: Long = 0) {
        /** Another device's day folders are in it (rows found by listing, or no row at all). */
        val othersIncluded: Boolean get() = dayFolders.any { day -> purged.none { it.dayFolder == day && it.runId != SCAN_RUN } }
    }

    /**
     * Auto-empty (ADR 0023): in every reachable root, the day folders older than [days] days are deleted (including
     * those another device made), with their rows. [days] ≤ 0 is off. The phone's clock and the day folders' names are
     * not trusted alone (review of PR #58):
     * - the whole run is skipped while now is before [notBefore] (the newest trash time this device has written: a
     *   clock that went back);
     * - a day folder goes only when its name says so **and** its mtime on the share or drive (the server's clock) is
     *   older than the setting too; a folder without an mtime goes only when it is this device's and every row of it
     *   was trashed before the limit. A clock that jumped ahead, or a day folder named by a clock that was behind,
     *   purges nothing.
     */
    suspend fun autoEmpty(roots: List<Root>, days: Int, notBefore: Long = 0, trustedNow: () -> Long? = { null }, dryRun: Boolean = false): List<Expired> {
        if (days <= 0) return emptyList()
        val phoneNow = now()
        if (phoneNow < notBefore) return emptyList()
        val out = ArrayList<Expired>()
        val today = today()
        for (root in roots) {
            val fs = fsFor(root.place) ?: continue
            try {
                val dir = TrashPaths.trashDir(root.path)
                val rows = index.inRoot(root.place, root.path)
                val byDay = rows.groupBy { it.dayFolder }
                var nowMs = phoneNow
                var files = 0
                var bytes = 0L
                val expired = fs.use { hoard ->
                    val entries = try {
                        hoard.list(dir).entries
                    } catch (e: HoardSmbException) {
                        if (e.code == ErrorCode.NOT_FOUND) return@use emptyList() else throw e
                    }
                    // The share's (or drive's) own clock: the mtime of a file written now. A phone clock that jumped
                    // ahead is then not believed; nor is it without that write (a read-only trash purges nothing).
                    val probe = clockOf(hoard, dir) ?: return@use emptyList()
                    nowMs = listOfNotNull(phoneNow, probe, trustedNow()).min()
                    val limitMs = nowMs - days * DAY_MS
                    val folders = entries.filter { it.isDirectory }.associateBy { it.name }
                    val referenceDay = java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    TrashPlanner.expired(folders.keys, minOf(today, referenceDay), days).filter { day ->
                        val mtime = dev.hfps.hoard.smb.IsoTime.parse(folders.getValue(day).modifiedAt)
                        val own = byDay[day].orEmpty().filter { it.runId != SCAN_RUN }
                        if (mtime != null) mtime <= limitMs else own.isNotEmpty() && own.all { it.trashedAt <= limitMs }
                    }.onEach { day ->
                        val path = requireTrashPath(root.path, SharePaths.join(dir, day))
                        walk(hoard, path).forEach { f ->
                            files++
                            bytes += f.size
                        }
                        if (!dryRun) deleteIfExists(hoard, path)
                    }
                }
                val purged = rows.filter { it.dayFolder in expired }
                if (!dryRun) {
                    if (purged.isNotEmpty()) index.delete(purged.map { it.id })
                    index.touchRoot(root.place, root.path, root.serverFolderId, nowMs)
                }
                if (expired.isNotEmpty()) out += Expired(root, expired, purged, files, bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
        }
        return out
    }

    /**
     * Lists a root's `.hoard-trash` and indexes what it holds that the index does not know (another device, a
     * reinstall: ADR 0023 "No manifest: the path is the manifest"), and forgets rows whose file is gone. Returns the
     * rows now indexed for that root.
     */
    suspend fun scan(root: Root): List<TrashEntryEntity> {
        val fs = fsFor(root.place) ?: return index.inRoot(root.place, root.path)
        val known = index.inRoot(root.place, root.path)
        // A trashed folder is one row: the walk stops at it and reports it as a folder (its files stay one unit).
        val folderRows = known.filter { it.isDirectory }.map { it.trashedPath.lowercase() }.toHashSet()
        val found = fs.use { hoard -> walk(hoard, TrashPaths.trashDir(root.path), folderRows) }
        val byTrashed = known.associateBy { it.trashedPath.lowercase() }
        val foundKeys = found.map { it.path.lowercase() }.toHashSet()
        val gone = known.filter { it.state == TrashStates.TRASHED && it.trashedPath.lowercase() !in foundKeys }
        if (gone.isNotEmpty()) index.delete(gone.map { it.id })
        val at = now()
        val added = found.filter { it.path.lowercase() !in byTrashed }.mapNotNull { f ->
            val parsed = TrashPaths.parse(f.path) ?: return@mapNotNull null
            if (!parsed.root.equals(root.path, ignoreCase = true)) return@mapNotNull null
            val name = SharePaths.name(parsed.originalPath)
            val folder = SharePaths.parent(parsed.originalPath)
            TrashEntryEntity(
                id = newId(),
                runId = SCAN_RUN,
                locationId = root.place.locationId,
                share = root.place.share,
                trashRoot = root.path,
                dayFolder = parsed.dayFolder,
                originalPath = parsed.originalPath,
                trashedPath = f.path,
                shotKey = if (TrashPlanner.isMedia(name) || dev.hfps.hoard.xmp.SidecarNames.isSidecar(name)) {
                    TrashPlanner.shotKey(folder, shotStem(name))
                } else {
                    parsed.originalPath.lowercase()
                },
                size = f.size,
                modifiedMillis = f.modifiedMillis,
                trashedAt = TrashPaths.dateOf(parsed.dayFolder)?.let { it.toEpochDay() * DAY_MS } ?: at,
                serverFolderId = root.serverFolderId,
                state = TrashStates.TRASHED,
            )
        }
        if (added.isNotEmpty()) index.put(added)
        index.touchRoot(root.place, root.path, root.serverFolderId, at)
        return index.inRoot(root.place, root.path)
    }

    /**
     * Files under [dir], recursively (folders are walked, not returned), except the folders in [stopAt] (lower-case
     * paths: trashed folders the index knows), which are returned as folders and not entered. Empty when it does not
     * exist.
     */
    private fun walk(fs: HoardFs, dir: String, stopAt: Set<String> = emptySet()): List<TrashFile> {
        val out = ArrayList<TrashFile>()
        val entries = try {
            fs.list(dir).entries
        } catch (e: HoardSmbException) {
            if (e.code == ErrorCode.NOT_FOUND) return out else throw e
        }
        for (e in entries) {
            if (e.isDirectory && e.path.lowercase() in stopAt) {
                out += TrashFile(e.path, 0, dev.hfps.hoard.smb.IsoTime.parse(e.modifiedAt), isDirectory = true)
            } else if (e.isDirectory) {
                out += walk(fs, e.path, stopAt)
            } else if (e.name != TrashPaths.NO_MEDIA && e.name != CLOCK_FILE) {
                out += TrashFile(e.path, e.size, dev.hfps.hoard.smb.IsoTime.parse(e.modifiedAt))
            }
        }
        return out
    }

    /** Removes the folders [rows] leave empty, from each file's folder up to (and with) its day folder. */
    private suspend fun cleanUp(fs: TrashFs, rows: List<TrashEntryEntity>) {
        if (rows.isEmpty()) return
        runCatching {
            fs.use { hoard ->
                val dirs = rows.flatMap { row ->
                    val day = SharePaths.join(TrashPaths.trashDir(row.trashRoot), row.dayFolder)
                    generateSequence(SharePaths.parent(row.trashedPath)) { p -> SharePaths.parent(p).takeIf { SharePaths.rest(it, day) != null } }
                        .takeWhile { SharePaths.rest(it, day) != null }
                        .toList()
                }.distinct().sortedByDescending { SharePaths.segments(it).size }
                for (dir in dirs) {
                    val empty = runCatching { hoard.list(dir).entries.isEmpty() }.getOrDefault(false)
                    if (empty) runCatching { hoard.delete(dir, false) }
                }
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** One unit's rows: the same place, root, day folder, shot and run. */
    private data class UnitKey(val locationId: String, val share: String, val root: String, val day: String, val shot: String, val run: String) {
        companion object {
            fun of(e: TrashEntryEntity) = UnitKey(e.locationId, e.share, e.trashRoot, e.dayFolder, e.shotKey, e.runId)
        }
    }

    private fun refusal(unit: TrashUnit, e: Exception): TrashOutcome.Refused {
        val (reason, code) = reasonOf(e)
        return TrashOutcome.Refused(unit, reason, code, e.message)
    }

    companion object {
        /** `run_id` of rows found by listing a `.hoard-trash` (not written by a run of this device). */
        const val SCAN_RUN = "scan"

        private const val DAY_MS = 86_400_000L

        /** Refusals that hold for the whole root: the other units there are refused without trying. */
        private val ROOT_REASONS = setOf(TrashRefusal.ROOT_NOT_WRITABLE, TrashRefusal.NOT_SAME_DEVICE, TrashRefusal.MOVE_UNSUPPORTED)

        fun codeOf(e: Throwable): ErrorCode? = (e as? HoardSmbException)?.code ?: runCatching { SmbErrors.map(e).code }.getOrNull()

        /** A failure → its refusal reason (and code). */
        fun reasonOf(e: Throwable): Pair<TrashRefusal, ErrorCode?> {
            val mapped = e as? HoardSmbException ?: SmbErrors.map(e)
            val reason = when {
                mapped.data[SmbErrors.REASON] == SmbErrors.NOT_SAME_DEVICE -> TrashRefusal.NOT_SAME_DEVICE
                mapped.data[SmbErrors.REASON] == LocalErrors.MOVE_UNSUPPORTED -> TrashRefusal.MOVE_UNSUPPORTED
                mapped.code == ErrorCode.NOT_FOUND -> TrashRefusal.NOT_FOUND
                mapped.code == ErrorCode.UNREACHABLE || mapped.code == ErrorCode.TIMEOUT || mapped.code == ErrorCode.UNAVAILABLE -> TrashRefusal.UNREACHABLE
                else -> TrashRefusal.FAILED
            }
            return reason to mapped.code
        }

        /** The stem a trashed file's shot is keyed by (`X.ARW.xmp` → `X`, `.X.xmp` → `X`). */
        fun shotStem(name: String): String {
            var n = name
            if (dev.hfps.hoard.xmp.SidecarNames.isSidecar(n)) {
                n = n.dropLast(dev.hfps.hoard.xmp.SidecarNames.SUFFIX.length).removePrefix(".")
            }
            return dev.hfps.hoard.image.format.ImageFormats.stem(n).ifEmpty { n }
        }

        fun exists(fs: HoardFs, path: String): Boolean = try {
            fs.stat(path)
            true
        } catch (e: HoardSmbException) {
            if (e.code == ErrorCode.NOT_FOUND) false else throw e
        }

        /** Names in [dir], or null when it does not exist. */
        fun listNames(fs: HoardFs, dir: String): List<String>? = try {
            fs.list(dir).entries.map { it.name }
        } catch (e: HoardSmbException) {
            if (e.code == ErrorCode.NOT_FOUND) null else throw e
        }

        /** Creates [dir] and its missing parents (`ALREADY_EXISTS` ignored). */
        fun mkdirs(fs: HoardFs, dir: String) {
            val segments = SharePaths.segments(dir)
            var at = ""
            for (name in segments) {
                val next = SharePaths.join(at, name)
                if (!exists(fs, next)) {
                    try {
                        fs.createFolder(at, name)
                    } catch (e: HoardSmbException) {
                        if (e.code != ErrorCode.ALREADY_EXISTS) throw e
                    }
                }
                at = next
            }
        }

        /** `<root>/.hoard-trash`, with a `.nomedia` on local storage (the media scanner skips the trash). */
        fun ensureTrashDir(fs: HoardFs, root: String, local: Boolean) {
            val dir = TrashPaths.trashDir(root)
            mkdirs(fs, dir)
            if (local) {
                val marker = SharePaths.join(dir, TrashPaths.NO_MEDIA)
                if (!exists(fs, marker)) {
                    try {
                        fs.writeText(marker, "", "utf-8", "create", null)
                    } catch (e: HoardSmbException) {
                        if (e.code != ErrorCode.ALREADY_EXISTS) throw e
                    }
                }
            }
        }

        /**
         * The row's trashed path, checked to be `<trash root>/.hoard-trash/<day folder>/…` of that very row before
         * anything is deleted recursively there (a corrupt row must never point a delete at the library).
         */
        fun requireTrashPath(row: TrashEntryEntity): String {
            val parsed = TrashPaths.parse(row.trashedPath)
            require(parsed != null && parsed.root.equals(row.trashRoot, ignoreCase = true) && parsed.dayFolder == row.dayFolder) {
                "Not a trash path: ${row.trashedPath}"
            }
            return row.trashedPath
        }

        /** The probe file auto-empty writes in each `.hoard-trash` to read the share's clock. */
        const val CLOCK_FILE = ".hoard-clock"

        /** The share's "now": the mtime of [CLOCK_FILE] written in [trashDir] (null when it cannot be written or read). */
        fun clockOf(fs: HoardFs, trashDir: String): Long? = runCatching {
            val written = fs.writeText(SharePaths.join(trashDir, CLOCK_FILE), "", "utf-8", "overwrite", null)
            dev.hfps.hoard.smb.IsoTime.parse(written.modifiedAt)
        }.getOrNull()

        /** A day folder [path] checked to be exactly `<root>/.hoard-trash/<day>`. */
        fun requireTrashPath(root: String, path: String): String {
            val day = SharePaths.name(path)
            require(TrashPaths.isDayFolder(day) && SharePaths.join(TrashPaths.trashDir(root), day).equals(SharePaths.normalize(path), ignoreCase = true)) {
                "Not a day folder: $path"
            }
            return path
        }

        fun deleteIfExists(fs: HoardFs, path: String) {
            try {
                fs.delete(path, true)
            } catch (e: HoardSmbException) {
                if (e.code != ErrorCode.NOT_FOUND) throw e
            }
        }
    }
}
