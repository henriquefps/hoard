package dev.hfps.hoard.server.backup

import android.util.Log
import dev.hfps.hoard.server.Fingerprint
import dev.hfps.hoard.server.api.BackupRemoval
import dev.hfps.hoard.server.api.BackupReport
import dev.hfps.hoard.server.api.BackupRoot
import dev.hfps.hoard.server.api.BackupRootCreate
import dev.hfps.hoard.server.api.BackupRootPatch
import dev.hfps.hoard.server.api.BackupRootStats
import dev.hfps.hoard.server.api.Features
import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerException
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.db.BackupFileEntity
import dev.hfps.hoard.server.db.BackupRootEntity
import dev.hfps.hoard.server.db.GalleryDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** The phone side of the backup, given by the app: its folders' files, its rules and conditions, its index. */
interface BackupSource {
    /** The `types` roots declare now: `image`, `video`, plus `raw` and `sidecar` with All files access. */
    fun types(): List<String>

    /** The files [root] holds now (MediaStore, plus a listing for RAWs and sidecars when [types] has them). */
    suspend fun list(root: BackupRootEntity, types: Set<String>): Listing

    /** An identity the backup made for a `device_media` row (the index keeps it). */
    suspend fun identified(mediaId: Long, fingerprint: String, sha256: String?, size: Long, modified: Long) {}

    /** A SHA-256 the backup made for a `device_media` row. */
    suspend fun hashed(mediaId: Long, sha256: String, size: Long, modified: Long) {}

    fun conditions(): Conditions

    suspend fun rules(): BackupRules

    fun file(absolute: String): File = File(absolute)
}

/** Where a run stands (Settings › Backup's progress line and the foreground notification). */
data class BackupProgress(
    val running: Boolean = false,
    /** `listing`, `reporting`, `hashing`, `uploading`. */
    val phase: String? = null,
    val name: String? = null,
    val sent: Long = 0,
    val size: Long = 0,
    val uploaded: Int = 0,
    val toUpload: Int = 0,
    val bytesSent: Long = 0,
)

enum class BackupOutcome { NotPaired, Unsupported, Unauthorized, Offline, NotConfigured, Done, Stopped, Busy }

data class BackupRunResult(
    val outcome: BackupOutcome,
    val uploaded: Int = 0,
    val bytes: Long = 0,
    /** Videos wait for the phone to charge (the worker asks for a run with that constraint). */
    val waitingForCharging: Boolean = false,
    /** Videos the server holds since this pass (stored or found on its NAS): a compressed folder's new videos (ADR 0025). */
    val videosStored: Int = 0,
)

/** What turning a folder's switch on came to. */
sealed interface AddRootResult {
    data class Added(val root: BackupRootEntity) : AddRootResult
    /** The folder is inside a root of this phone already ([rootId]). */
    data class Covered(val rootId: String) : AddRootResult
    /** The server has no backup folder chosen (`503 storage_not_configured`). */
    data object NotConfigured : AddRootResult
    /** Not paired, or a server without the `backup` feature. */
    data object Unsupported : AddRootResult
    data object Offline : AddRootResult
    data class Failed(val message: String) : AddRootResult
}

/** A folder as hoard-server names it (ADR 0024 "Path on the phone"): `primary` or the volume UUID, and the path in it. */
object BackupPaths {
    /** `device` → `primary`; `vol-<uuid>` → the UUID upper-case; null for any other location. */
    fun volumeOf(locationId: String): String? = when {
        locationId == "device" -> "primary"
        locationId.startsWith("vol-") -> locationId.removePrefix("vol-").uppercase().takeIf { it.isNotEmpty() }
        else -> null
    }

    /** A folder path as the server takes it: '/'-separated, no leading or trailing '/'. */
    fun clean(folder: String): String = folder.split('/').filter { it.isNotEmpty() }.joinToString("/")

    /** Whether [path] (relative to the volume) is [root] or inside it. */
    fun within(root: String, path: String): Boolean = root.isEmpty() || path == root || path.startsWith("$root/")
}

/**
 * The phone backup's runs (ADR 0024 "`BackupWorker`"): for each folder, list what the phone has, identify new files
 * (the `v1:` fingerprint, and the SHA-256 of small files in the same read), report new and changed files in batches of
 * `maxReportEntries`, report removals after complete listings only, then hash and upload what the server is missing,
 * newest first, under the rules; finally report the uploaded files again. Runs only inside the worker, never at
 * start-up. One run at a time per process.
 */
class BackupEngine(
    private val db: GalleryDatabase,
    private val apiOf: suspend () -> ServerApi?,
    private val source: BackupSource,
    private val now: () -> Long = System::currentTimeMillis,
    /** Throttle of identity reads and hashing while not charging (bytes per second; null = none, for tests). */
    private val throttle: Long? = Hasher.THROTTLE_BYTES_PER_SECOND,
) {
    private val dao = db.backup()
    private val mutex = Mutex()
    private val _progress = MutableStateFlow(BackupProgress())
    val progress: StateFlow<BackupProgress> = _progress

    /** Whether the paired server has the phone backup (`features` has `backup`). */
    suspend fun available(): Boolean = db.account().get()?.has(Features.BACKUP) == true

    // --- Folders ---------------------------------------------------------------------------------------------------

    /** Turns a folder's backup on: `POST /v1/backup/roots`, then the root is kept here (absorbed roots move into it). */
    suspend fun addRoot(locationId: String, folder: String): AddRootResult = withContext(Dispatchers.IO) {
        val volume = BackupPaths.volumeOf(locationId) ?: return@withContext AddRootResult.Failed("not a phone folder")
        val path = BackupPaths.clean(folder)
        val api = apiOf() ?: return@withContext AddRootResult.Unsupported
        if (!available()) return@withContext AddRootResult.Unsupported
        val types = source.types()
        val root = try {
            api.createBackupRoot(BackupRootCreate(volume, path, types))
        } catch (e: ServerException) {
            return@withContext when {
                e.status == 409 && e.reason == "covered" -> AddRootResult.Covered(e.detailString("rootId").orEmpty())
                e.code == "storage_not_configured" -> AddRootResult.NotConfigured
                else -> AddRootResult.Failed(e.code)
            }
        } catch (e: IOException) {
            return@withContext AddRootResult.Offline
        }
        val entity = BackupRootEntity(
            id = root.id,
            volume = root.volume,
            path = root.path,
            locationId = locationId,
            folder = path,
            types = BackupKind.encode(root.types.ifEmpty { types }),
            addedAt = now(),
        )
        for (absorbed in root.details?.absorbed.orEmpty()) {
            dao.moveFiles(absorbed, root.id)
            dao.deleteRootRow(absorbed)
        }
        // A root of this folder made before (another run of the app, an absorbed child) keeps its files.
        dao.root(root.id)?.let { old -> dao.putRoot(entity.copy(addedAt = old.addedAt, lastPassAt = old.lastPassAt, serverStats = old.serverStats)) } ?: dao.putRoot(entity)
        AddRootResult.Added(dao.root(root.id) ?: entity)
    }

    /**
     * Turns a folder's backup off: the root is stopped on the server (`DELETE`, its mirror stays: no device deletes a
     * backup) and forgotten here. Offline, it is forgotten here and stopped by the next call that reaches the server.
     */
    suspend fun removeRoot(id: String): Boolean = withContext(Dispatchers.IO) {
        val api = apiOf()
        val stopped = try {
            api?.stopBackupRoot(id)
            true
        } catch (e: ServerException) {
            e.status == 404
        } catch (e: IOException) {
            false
        }
        dao.deleteRoot(id)
        stopped
    }

    // --- A run -----------------------------------------------------------------------------------------------------

    /**
     * One backup pass. [stopped] is the worker's stop check (between files and chunks); [foreground] is called once
     * before the first upload (the worker becomes a foreground `dataSync` service).
     */
    suspend fun run(stopped: () -> Boolean = { false }, foreground: suspend (BackupProgress) -> Unit = {}): BackupRunResult = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext BackupRunResult(BackupOutcome.Busy)
        try {
            _progress.value = BackupProgress(running = true, phase = PHASE_LISTING)
            pass(stopped, foreground)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServerException) {
            Log.w(TAG, "backup: ${e.status} ${e.code}")
            BackupRunResult(
                when {
                    e.unauthorized -> BackupOutcome.Unauthorized
                    e.code == "storage_not_configured" -> BackupOutcome.NotConfigured
                    else -> BackupOutcome.Offline
                },
            )
        } catch (e: IOException) {
            Log.w(TAG, "backup: offline (${e.javaClass.simpleName})")
            BackupRunResult(BackupOutcome.Offline)
        } finally {
            _progress.value = BackupProgress()
            // Also when the worker was cancelled: no row stays `hashing` or `uploading`.
            withContext(kotlinx.coroutines.NonCancellable) { runCatching { dao.settleInterrupted() } }
            mutex.unlock()
        }
    }

    private suspend fun pass(stopped: () -> Boolean, foreground: suspend (BackupProgress) -> Unit): BackupRunResult {
        val account = db.account().get() ?: return BackupRunResult(BackupOutcome.NotPaired)
        val api = apiOf() ?: return BackupRunResult(BackupOutcome.NotPaired)
        val info = api.server()
        db.account().setServerInfo(info.features.joinToString(","), info.serverName ?: account.serverName, info.version ?: account.serverVersion)
        if (Features.BACKUP !in info.features) return BackupRunResult(BackupOutcome.Unsupported)
        val limits = info.backup ?: dev.hfps.hoard.server.api.BackupLimits()
        val batch = limits.maxReportEntries.coerceIn(1, 1000)
        dao.settleInterrupted()
        dao.deleteOrphans()
        val videosBefore = storedVideos()
        val types = source.types()
        val listings = HashMap<String, Listing>()
        val freedPaths = dao.freed().associateBy { it.path }
        // 1. Each folder: list, identify, report, report removals.
        for (initial in dao.roots()) {
            if (stopped()) return BackupRunResult(BackupOutcome.Stopped)
            if (initial.paused) continue
            val root = ensureTypes(api, initial, types) ?: continue
            _progress.value = _progress.value.copy(phase = PHASE_LISTING, name = root.path)
            val listing = source.list(root, types.toSet())
            listings[root.id] = listing
            val merge = BackupPlan.merge(root.id, listing, dao.files(root.id), now())
            if (merge.rows.isNotEmpty()) for (chunk in merge.rows.chunked(500)) dao.putFiles(chunk)
            for (id in merge.abandoned) runCatching { api.deleteUpload(id) }
            if (!identify(root, stopped)) return BackupRunResult(BackupOutcome.Stopped)
            _progress.value = _progress.value.copy(phase = PHASE_REPORTING)
            val live = report(api, root, batch) ?: continue
            // Files free up deleted whose report did not reach the server go first, as `freed` (ADR 0024 A3).
            val freed = BackupPlan.freedRemovals(dao.files(live.id), freedPaths) { source.file(it).exists() }
            if (freed.isNotEmpty()) reportRemovals(api, live, freed, batch)
            val removals = BackupPlan.removals(listing, dao.files(live.id))
            reportRemovals(api, live, removals, batch)
        }
        // 2. Uploads, newest first, under the rules.
        val upload = upload(api, info.limits.chunkSize ?: BackupUploader.DEFAULT_CHUNK, stopped, foreground)
        if (upload.outcome == BackupOutcome.Stopped) return upload
        // 3. What changed state (uploaded files) is reported again.
        for (root in dao.roots()) if (!root.paused) report(api, root, batch)
        if (upload.outcome == BackupOutcome.Done) {
            val stats = runCatching { api.backupRoots().roots.associateBy { it.id } }.getOrNull()
            val at = now()
            for (root in dao.roots()) {
                if (root.paused || root.id !in listings) continue
                dao.passed(root.id, at, stats?.get(root.id)?.stats?.let { ServerJson.encodeToString(BackupRootStats.serializer(), it) } ?: root.serverStats)
            }
        }
        return upload.copy(videosStored = (storedVideos() - videosBefore).size)
    }

    /**
     * Phone files a compression replace is writing in place (started, not read back): a backup pass during a long redo
     * must not identify, report or upload the half-written file as a new version (ADR 0025 Amendment (A2), "Review of
     * #81"). The replace's read-back puts the backup row right afterwards.
     */
    private suspend fun compressWriting(): Set<String> = db.compress().writingPaths().toHashSet()

    /** Videos of every root the server holds now, by root and path. */
    private suspend fun storedVideos(): Set<String> =
        dao.allFiles().filter { it.kind == BackupKind.VIDEO && it.state in BackupState.DONE }.mapTo(HashSet()) { "${it.rootId}|${it.path}" }

    /** The root's `types` follow All files access (ADR 0024: `PATCH` when it is granted or revoked); a root gone is made again. */
    private suspend fun ensureTypes(api: ServerApi, root: BackupRootEntity, types: List<String>): BackupRootEntity? {
        if (BackupKind.decode(root.types) == types) return root
        return try {
            api.patchBackupRoot(root.id, BackupRootPatch(types = types))
            dao.setTypes(root.id, BackupKind.encode(types))
            root.copy(types = BackupKind.encode(types))
        } catch (e: ServerException) {
            if (e.status == 404) recreate(api, root, types) else throw e
        }
    }

    /**
     * The server does not know the root (another phone claimed it, an admin removed it) or stopped it: made again with
     * `POST` (a stopped root is resumed with its id). Null when another root of this phone covers it now.
     */
    private suspend fun recreate(api: ServerApi, root: BackupRootEntity, types: List<String>): BackupRootEntity? {
        val made: BackupRoot = try {
            api.createBackupRoot(BackupRootCreate(root.volume, root.path, types))
        } catch (e: ServerException) {
            if (e.status == 409 && e.reason == "covered") {
                Log.i(TAG, "backup: a folder is covered by another root of this phone")
                dao.deleteRoot(root.id)
                return null
            }
            throw e
        }
        val entity = root.copy(id = made.id, types = BackupKind.encode(made.types.ifEmpty { types }))
        if (made.id != root.id) {
            dao.putRoot(entity)
            dao.moveFiles(root.id, made.id)
            dao.deleteRootRow(root.id)
        } else {
            dao.putRoot(entity)
        }
        return entity
    }

    /** Fingerprints (and small files' SHA-256) the rows still lack, newest first, in batches with yields. */
    private suspend fun identify(root: BackupRootEntity, stopped: () -> Boolean): Boolean {
        val writing = compressWriting()
        val todo = dao.files(root.id).filter { !BackupPlan.identified(it) && it.state != BackupState.REFUSED && it.absolute !in writing }.sortedByDescending { it.taken }
        if (todo.isEmpty()) return true
        _progress.value = _progress.value.copy(phase = PHASE_HASHING)
        val charging = source.conditions().charging
        for (batch in todo.chunked(IDENTIFY_BATCH)) {
            val out = ArrayList<BackupFileEntity>()
            for (row in batch) {
                if (stopped()) {
                    dao.putFiles(out)
                    return false
                }
                val file = source.file(row.absolute)
                if (!file.isFile || file.length() != row.size) continue // changed since listed: the next run sees it
                val started = System.nanoTime()
                val (fp, sha) = try {
                    Fingerprint.withSha(file)
                } catch (e: IOException) {
                    out += BackupPlan.failed(row, BackupState.REASON_UNREADABLE, now())
                    continue
                }
                val keepSha = sha ?: row.sha256.takeIf { BackupPlan.hashed(row) && row.state != BackupState.CHANGED }
                val wasChanged = row.state == BackupState.CHANGED
                out += row.copy(
                    fingerprint = fp,
                    fpSize = row.size,
                    fpModified = row.modified,
                    sha256 = keepSha,
                    shaSize = row.size.takeIf { keepSha != null },
                    shaModified = row.modified.takeIf { keepSha != null },
                    state = if (row.state == BackupState.WAITING || wasChanged || row.state == BackupState.FAILED) BackupState.QUEUED else row.state,
                    reported = if (wasChanged) null else row.reported,
                    error = null,
                    updatedAt = now(),
                )
                row.mediaId?.let { source.identified(it, fp, sha, row.size, row.modified) }
                pace(minOf(row.size, 2L shl 20), started, charging)
            }
            if (out.isNotEmpty()) dao.putFiles(out)
            yield()
        }
        return true
    }

    /** Sleeps what keeps reads under the throttle (not while charging). */
    private suspend fun pace(bytes: Long, startedNanos: Long, charging: Boolean) {
        val rate = throttle ?: return
        if (charging) return
        val due = bytes * 1000 / rate
        val took = (System.nanoTime() - startedNanos) / 1_000_000
        if (due > took) delay(due - took)
    }

    /**
     * Reports a root's rows that need it, in batches; per-entry refusals (`refused`) are kept per file. An older
     * server's `422` for one entry (`details.index`) refuses that entry and sends the batch again without it, so one
     * bad file never blocks a folder. Null when the root is gone (made again failed or covered).
     */
    private suspend fun report(api: ServerApi, initial: BackupRootEntity, batch: Int): BackupRootEntity? {
        var root = initial
        val writing = compressWriting()
        val due = BackupPlan.toReport(dao.files(root.id)).filter { it.absolute !in writing }
        for (part in due.chunked(batch)) {
            var pending = part
            while (pending.isNotEmpty()) {
                val response = try {
                    api.backupReport(BackupReport(root.id, pending.map(BackupPlan::entry)))
                } catch (e: ServerException) {
                    val index = e.detailLong("index")?.toInt()
                    when {
                        e.status == 422 && index != null && index in pending.indices && (e.detailString("field") ?: FIELD_ENTRIES) == FIELD_ENTRIES -> {
                            dao.putFiles(listOf(BackupPlan.refused(pending[index], REFUSED_INVALID, now())))
                            pending = pending.filterIndexed { i, _ -> i != index }
                            continue
                        }
                        (e.status == 409 && e.reason == "stopped") || e.status == 404 -> {
                            root = recreate(api, root, BackupKind.decode(root.types)) ?: return null
                            continue
                        }
                        else -> throw e
                    }
                }
                val at = now()
                val updated = response.results.mapNotNull { r -> pending.getOrNull(r.index)?.let { BackupPlan.reported(it, r, at) } }
                if (updated.isNotEmpty()) dao.putFiles(updated)
                pending = emptyList()
            }
        }
        return root
    }

    /** Removals of a complete listing, in batches; every answered path leaves the local rows (the server keeps it). */
    private suspend fun reportRemovals(api: ServerApi, root: BackupRootEntity, removals: List<BackupRemoval>, batch: Int) {
        for (part in removals.chunked(batch)) {
            var pending = part
            while (pending.isNotEmpty()) {
                try {
                    api.backupReport(BackupReport(root.id, removed = pending))
                } catch (e: ServerException) {
                    val index = e.detailLong("index")?.toInt()
                    if (e.status == 422 && index != null && index in pending.indices && e.detailString("field") == FIELD_REMOVED) {
                        dao.deleteFiles(root.id, listOf(pending[index].path))
                        pending = pending.filterIndexed { i, _ -> i != index }
                        continue
                    }
                    throw e
                }
                dao.deleteFiles(root.id, pending.map { it.path })
                pending = emptyList()
            }
        }
    }

    private suspend fun upload(api: ServerApi, chunkSize: Long, stopped: () -> Boolean, foreground: suspend (BackupProgress) -> Unit): BackupRunResult {
        val roots = dao.roots().filter { !it.paused }
        val writing = compressWriting()
        val candidates = roots.flatMap { BackupPlan.uploads(dao.files(it.id), now()) }.filter { it.absolute !in writing }.sortedByDescending { it.taken }
        if (candidates.isEmpty()) return BackupRunResult(BackupOutcome.Done)
        val rules = source.rules()
        val uploader = BackupUploader(api, chunkSize)
        var uploaded = 0
        var bytes = 0L
        var waitingCharging = false
        var foregrounded = false
        var errors = 0
        _progress.value = _progress.value.copy(phase = PHASE_UPLOADING, toUpload = candidates.size, uploaded = 0)
        for (candidate in candidates) {
            if (stopped()) return BackupRunResult(BackupOutcome.Stopped, uploaded, bytes, waitingCharging)
            var row = dao.file(candidate.rootId, candidate.path) ?: continue
            val conditions = source.conditions()
            val pause = BackupPlan.pause(row.kind, rules, conditions)
            if (pause != null) {
                if (pause == BackupState.REASON_CHARGING) waitingCharging = true
                if (row.state != BackupState.PAUSED || row.error != pause) dao.putFiles(listOf(row.copy(state = BackupState.PAUSED, error = pause, updatedAt = now())))
                continue
            }
            val file = source.file(row.absolute)
            if (!file.isFile || file.length() != row.size) continue // changed since listed: the next run sees it
            if (!file.canRead()) {
                dao.putFiles(listOf(BackupPlan.failed(row, BackupState.REASON_UNREADABLE, now())))
                continue
            }
            if (!foregrounded) {
                foregrounded = true
                runCatching { foreground(_progress.value) }
            }
            _progress.value = _progress.value.copy(name = file.name, sent = 0, size = row.size)
            try {
                if (!BackupPlan.hashed(row)) {
                    row = row.copy(state = BackupState.HASHING, updatedAt = now())
                    dao.putFiles(listOf(row))
                    _progress.value = _progress.value.copy(phase = PHASE_HASHING)
                    val sha = Hasher.sha256(file, if (conditions.charging) null else throttle, stopped)
                        ?: return BackupRunResult(BackupOutcome.Stopped, uploaded, bytes, waitingCharging)
                    row = row.copy(sha256 = sha, shaSize = row.size, shaModified = row.modified)
                    row.mediaId?.let { source.hashed(it, sha, row.size, row.modified) }
                }
                row = row.copy(state = BackupState.UPLOADING, updatedAt = now())
                dao.putFiles(listOf(row))
                _progress.value = _progress.value.copy(phase = PHASE_UPLOADING)
                val before = _progress.value.bytesSent
                val outcome = uploader.upload(
                    sha256 = row.sha256!!,
                    size = row.size,
                    fingerprint = row.fingerprint!!,
                    uploadId = row.uploadId,
                    source = { offset -> FileInputStream(file).also { it.channel.position(offset) } },
                    save = { id, offset ->
                        row = row.copy(uploadId = id, uploadOffset = offset, updatedAt = now())
                        dao.putFiles(listOf(row))
                    },
                    progress = { sent -> _progress.value = _progress.value.copy(sent = sent, bytesSent = before + sent) },
                    stopped = stopped,
                )
                when (outcome) {
                    UploadOutcome.Done -> {
                        dao.putFiles(listOf(BackupPlan.uploaded(row, now())))
                        uploaded++
                        bytes += row.size
                        _progress.value = _progress.value.copy(uploaded = uploaded)
                    }
                    is UploadOutcome.InProgress -> dao.putFiles(listOf(row.copy(state = BackupState.QUEUED, nextAttemptAt = now() + outcome.retryAfterSeconds * 1000, updatedAt = now())))
                    UploadOutcome.Changed -> dao.putFiles(listOf(BackupPlan.changed(row, now())))
                    UploadOutcome.Stopped -> {
                        dao.putFiles(listOf(row.copy(state = BackupState.QUEUED)))
                        return BackupRunResult(BackupOutcome.Stopped, uploaded, bytes, waitingCharging)
                    }
                }
                errors = 0
            } catch (e: ServerException) {
                if (e.unauthorized || e.code == "storage_not_configured") throw e
                val reason = when {
                    e.status == 413 || e.code == "too_large" -> BackupState.REASON_TOO_LARGE
                    e.status == 507 -> BackupState.REASON_SERVER_FULL
                    e.status == 429 -> null
                    else -> BackupState.REASON_SERVER_ERROR
                }
                if (reason == null) {
                    dao.putFiles(listOf(row.copy(state = BackupState.QUEUED, nextAttemptAt = now() + (e.retryAfterSeconds ?: 60) * 1000)))
                    return BackupRunResult(BackupOutcome.Done, uploaded, bytes, waitingCharging)
                }
                Log.w(TAG, "backup: upload refused ${e.status} ${e.code}")
                dao.putFiles(listOf(BackupPlan.failed(row, reason, now())))
                if (++errors >= MAX_ERRORS) return BackupRunResult(BackupOutcome.Offline, uploaded, bytes, waitingCharging)
            } catch (e: java.io.FileNotFoundException) {
                dao.putFiles(listOf(BackupPlan.failed(row, BackupState.REASON_UNREADABLE, now())))
            } catch (e: IOException) {
                // The network went away mid-upload: the offset is saved, the next run resumes.
                dao.putFiles(listOf(row.copy(state = BackupState.QUEUED)))
                throw e
            }
        }
        return BackupRunResult(BackupOutcome.Done, uploaded, bytes, waitingCharging)
    }

    companion object {
        const val TAG = "HoardBackup"
        const val PHASE_LISTING = "listing"
        const val PHASE_REPORTING = "reporting"
        const val PHASE_HASHING = "hashing"
        const val PHASE_UPLOADING = "uploading"
        private const val IDENTIFY_BATCH = 50
        private const val MAX_ERRORS = 3
        private const val FIELD_ENTRIES = "entries"
        private const val FIELD_REMOVED = "removed"
        /** The reason a file gets when an older server refused it as a whole batch (`422`). */
        const val REFUSED_INVALID = "invalid_request"
    }
}
