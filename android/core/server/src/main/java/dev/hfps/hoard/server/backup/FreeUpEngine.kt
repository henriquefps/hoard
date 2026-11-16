package dev.hfps.hoard.server.backup

import android.util.Log
import dev.hfps.hoard.server.api.BackupLimits
import dev.hfps.hoard.server.api.BackupRemoval
import dev.hfps.hoard.server.api.BackupReport
import dev.hfps.hoard.server.api.BackupVerifyEntry
import dev.hfps.hoard.server.api.BackupVerifyRequest
import dev.hfps.hoard.server.api.Features
import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerException
import dev.hfps.hoard.server.db.BackupFileEntity
import dev.hfps.hoard.server.db.FreedEntity
import dev.hfps.hoard.server.db.GalleryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The phone side of free up, given by the app: favourites, culling, files, conditions. */
interface FreeUpSource {
    /** Of these `device_media` ids, the ones MediaStore marks `IS_FAVORITE`. */
    suspend fun favourites(mediaIds: Collection<Long>): Set<Long>

    /** Pushes culling decisions still on the phone (ADR 0024: "pushed first"). */
    suspend fun pushCulling() {}

    /** Of these shot keys (`<location>/<dir>/<stem>`), the ones whose culling has still not reached the server. */
    suspend fun pendingCulling(keys: Set<String>): Set<String> = emptySet()

    /** A SHA-256 free up made for a `device_media` row (the index keeps it). */
    suspend fun hashed(mediaId: Long, sha256: String, size: Long, modified: Long) {}

    fun conditions(): Conditions

    fun file(absolute: String): File = File(absolute)
}

/** Why free up cannot be offered now. */
enum class FreeUpBlock { NotPaired, Unsupported, Offline, Unauthorized }

/** The preview's data: the candidates and the Reveal gate as of now. */
data class FreeUpPreview(val candidates: FreeUpCandidates, val gate: RevealGate, val block: FreeUpBlock? = null) {
    companion object {
        fun blocked(block: FreeUpBlock) = FreeUpPreview(FreeUpCandidates(emptyList(), emptyMap()), RevealGate.Off, block)
    }
}

/** Where a check stands (the screen's progress line). */
data class FreeUpProgress(
    /** `hashing`, `verifying` ("Checking on the server…"). */
    val phase: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    val name: String? = null,
)

/** The check just before deleting: what may go now, and what stays with its reason. */
data class FreeUpCheck(
    val go: List<FreeShot>,
    val kept: List<Pair<FreeShot, String>>,
    val gate: RevealGate,
    val answers: Map<String, VerifyAnswer>,
    val block: FreeUpBlock? = null,
) {
    /** "Kept for Reveal: 42". */
    val keptForReveal: Int get() = kept.count { it.second == FreeUpPlan.REASON_REVEAL || it.second == FreeUpPlan.REASON_REVEAL_STALE }
}

/** What a deletion freed (reported to the server, or kept for the next backup run to report). */
data class FreeUpDone(val files: Int, val bytes: Long, val shots: Int, val reported: Boolean)

/**
 * Free up space (ADR 0024, Hoard 2.6.0): the candidates and the Reveal gate for the preview, the check just before
 * the deletion (re-stat, hash what has no valid hash or changed its time, `POST /v1/backup/verify` until every answer is
 * final, at most [maxWaitMs]), and the report of what the system really deleted (`removed`, reason `freed`), written to
 * `freed` first so that a report that fails is made by the next backup run. The deletion itself (the system's consent)
 * is the app's.
 */
class FreeUpEngine(
    private val db: GalleryDatabase,
    private val apiOf: suspend () -> ServerApi?,
    private val source: FreeUpSource,
    private val now: () -> Long = System::currentTimeMillis,
    /** Throttle of hashing while not charging (bytes per second; null = none, for tests). */
    private val throttle: Long? = Hasher.THROTTLE_BYTES_PER_SECOND,
    /** How long a check waits for `pending` answers before it gives up on them. */
    private val maxWaitMs: Long = 10 * 60_000L,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val dao = db.backup()
    private val mutex = Mutex()
    private val _progress = MutableStateFlow(FreeUpProgress())
    val progress: StateFlow<FreeUpProgress> = _progress

    private suspend fun ready(): Pair<ServerApi?, FreeUpBlock?> {
        val account = db.account().get() ?: return null to FreeUpBlock.NotPaired
        val api = apiOf() ?: return null to FreeUpBlock.NotPaired
        if (!account.has(Features.BACKUP)) return null to FreeUpBlock.Unsupported
        return api to null
    }

    private suspend fun gate(api: ServerApi): RevealGate = FreeUpPlan.revealGate(api.device().physicalDevice?.devices.orEmpty(), now())

    private fun blockOf(e: Exception): FreeUpBlock = if (e is ServerException && e.unauthorized) FreeUpBlock.Unauthorized else FreeUpBlock.Offline

    /** The preview: culling pushed first, then the candidates (oldest first) and the gate from `GET /v1/device`. */
    suspend fun preview(rules: FreeUpRules): FreeUpPreview = withContext(Dispatchers.IO) {
        val (api, block) = ready()
        if (api == null) return@withContext FreeUpPreview.blocked(block!!)
        val gate = try {
            gate(api)
        } catch (e: ServerException) {
            return@withContext FreeUpPreview.blocked(blockOf(e))
        } catch (e: IOException) {
            return@withContext FreeUpPreview.blocked(FreeUpBlock.Offline)
        }
        runCatching { source.pushCulling() }.onFailure { Log.w(TAG, "free up: culling push failed (${it.javaClass.simpleName})") }
        val roots = dao.roots()
        val files = dao.allFiles()
        val shots = FreeUpPlan.shots(roots, files)
        val favourites = source.favourites(files.mapNotNull { it.mediaId })
        val pending = source.pendingCulling(shots.mapTo(HashSet()) { it.key })
        FreeUpPreview(FreeUpPlan.candidates(roots, files, rules, favourites, pending, now()), gate)
    }

    /**
     * The check just before the deletion: each file is stat'ed again (gone or a new size: the shot stays, `changed`;
     * a new time: hashed again, and the shot stays if its bytes changed), files without a valid hash are hashed, then
     * `POST /v1/backup/verify` for all of them in batches of `maxVerify`, asked again while `pending` (at the server's
     * `retryAfter`) for at most [maxWaitMs]. The gate is read again first.
     */
    suspend fun check(shots: List<FreeShot>, stopped: () -> Boolean = { false }): FreeUpCheck = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                checkLocked(shots, stopped)
            } finally {
                _progress.value = FreeUpProgress()
            }
        }
    }

    private suspend fun checkLocked(shots: List<FreeShot>, stopped: () -> Boolean): FreeUpCheck {
        val (api, block) = ready()
        if (api == null) return FreeUpCheck(emptyList(), emptyList(), RevealGate.Off, emptyMap(), block)
        val limits = try {
            api.server().backup ?: BackupLimits()
        } catch (e: Exception) {
            if (e !is IOException && e !is ServerException) throw e
            return FreeUpCheck(emptyList(), emptyList(), RevealGate.Off, emptyMap(), blockOf(e))
        }
        val gate = try {
            gate(api)
        } catch (e: Exception) {
            if (e !is IOException && e !is ServerException) throw e
            return FreeUpCheck(emptyList(), emptyList(), RevealGate.Off, emptyMap(), blockOf(e))
        }
        if (gate.blocksAll) return FreeUpCheck(emptyList(), shots.map { it to FreeUpPlan.REASON_REVEAL_STALE }, gate, emptyMap())
        val kept = ArrayList<Pair<FreeShot, String>>()
        // 1. Re-stat and hash.
        val current = ArrayList<FreeShot>()
        val toHash = shots.sumOf { s -> s.files.count { !BackupPlan.hashed(it) } }
        var hashed = 0
        for (shot in shots) {
            if (stopped()) return FreeUpCheck(emptyList(), kept, gate, emptyMap())
            var reason: String? = null
            val files = ArrayList<BackupFileEntity>()
            for (row in shot.files) {
                val file = source.file(row.absolute)
                if (!file.isFile || !file.canRead()) {
                    reason = FreeUpPlan.REASON_UNREADABLE
                    break
                }
                if (file.length() != row.size) {
                    reason = FreeUpPlan.REASON_CHANGED
                    break
                }
                val sameTime = file.lastModified() / 1000 == row.modified / 1000
                if (sameTime && BackupPlan.hashed(row)) {
                    files += row
                    continue
                }
                _progress.value = FreeUpProgress(PHASE_HASHING, hashed, toHash, file.name)
                val sha = try {
                    Hasher.sha256(file, if (source.conditions().charging) null else throttle, stopped)
                } catch (e: IOException) {
                    reason = FreeUpPlan.REASON_UNREADABLE
                    break
                } ?: return FreeUpCheck(emptyList(), kept, gate, emptyMap())
                hashed++
                if (BackupPlan.hashed(row) && sha != row.sha256) {
                    // Rewritten in place with the same size: the next backup run reports the new content.
                    reason = FreeUpPlan.REASON_CHANGED
                    break
                }
                if (!sameTime) {
                    // Only the time moved (the same bytes): verified with the hash, the row keeps what was reported.
                    files += row.copy(sha256 = sha)
                    continue
                }
                val updated = row.copy(sha256 = sha, shaSize = row.size, shaModified = row.modified, updatedAt = now())
                dao.putFiles(listOf(updated))
                row.mediaId?.let { source.hashed(it, sha, row.size, row.modified) }
                files += updated
            }
            if (reason != null) kept += shot to reason else current += shot.copy(files = files)
        }
        // 2. Verify until every answer is final (or the wait is over).
        val answers = HashMap<String, VerifyAnswer>()
        val started = now()
        val batch = limits.maxVerify.coerceIn(1, 500)
        var active: List<FreeShot> = current
        val go = ArrayList<FreeShot>()
        while (active.isNotEmpty()) {
            if (stopped()) break
            val ask = FreeUpPlan.toAsk(active, answers, now())
            _progress.value = FreeUpProgress(PHASE_VERIFYING, go.size, current.size)
            for (part in ask.chunked(batch)) {
                val response = try {
                    api.backupVerify(BackupVerifyRequest(part.map { BackupVerifyEntry(it.rootId, it.path, it.sha256!!, it.size) }))
                } catch (e: ServerException) {
                    if (e.unauthorized) return FreeUpCheck(go, kept, gate, answers, FreeUpBlock.Unauthorized)
                    if (e.status == 404) {
                        // A root the server no longer knows: its files stay until the backup made it again.
                        val roots = part.mapTo(HashSet()) { it.rootId }
                        for (s in active.filter { it.rootId in roots }) kept += s to "not_found"
                        active = active.filter { it.rootId !in roots }
                        continue
                    }
                    return FreeUpCheck(go, kept + active.map { it to "offline" }, gate, answers, FreeUpBlock.Offline)
                } catch (e: IOException) {
                    return FreeUpCheck(go, kept + active.map { it to "offline" }, gate, answers, FreeUpBlock.Offline)
                }
                val at = now()
                for (r in response.results) part.getOrNull(r.index)?.let { answers[FreeUpPlan.fileKey(it)] = FreeUpPlan.answer(r, at) }
            }
            val waiting = ArrayList<FreeShot>()
            for (s in active) {
                when (val c = FreeUpPlan.check(s, answers, gate, now())) {
                    ShotCheck.Go -> go += s
                    ShotCheck.Wait -> waiting += s
                    is ShotCheck.Keep -> kept += s to c.reason
                }
            }
            active = waiting
            if (active.isEmpty()) break
            if (now() - started >= maxWaitMs) {
                for (s in active) kept += s to "pending"
                break
            }
            _progress.value = FreeUpProgress(PHASE_VERIFYING, go.size, current.size)
            val next = active.flatMap { s -> s.files.mapNotNull { answers[FreeUpPlan.fileKey(it)]?.retryAt } }.minOrNull() ?: now()
            sleep((next - now()).coerceIn(1_000L, 60_000L))
        }
        Log.i(TAG, "free up check: ${go.size} shots verified, ${kept.size} kept")
        return FreeUpCheck(go.sortedBy { it.taken }, kept, gate, answers)
    }

    /**
     * Whether every file of [shots] still has a `yes` from the last 10 minutes (the deletion request is made just
     * after; else the screen checks again).
     */
    fun fresh(shots: List<FreeShot>, answers: Map<String, VerifyAnswer>): Boolean = shots.all { s ->
        s.files.all { f -> answers[FreeUpPlan.fileKey(f)]?.let { it.verified == "yes" && now() - it.at <= FreeUpPlan.VERIFY_FRESH_MS } == true }
    }

    /**
     * After a delete request: [settlement] says which media files the system deleted; the other files of whole shots
     * are deleted here ([deleteOther], true when the file is gone). Everything gone is written to `freed` first, then
     * reported (`removed`, reason `freed`) per root, and leaves `backup_files`. A report that fails is made by the
     * next backup run (it finds the paths in `freed`).
     */
    suspend fun freed(settlement: FreeUpSettlement, deleteOther: (BackupFileEntity) -> Boolean): FreeUpDone = withContext(Dispatchers.IO) {
        val gone = ArrayList(settlement.goneMedia)
        for (f in settlement.othersToDelete) if (runCatching { deleteOther(f) }.getOrDefault(false)) gone += f
        if (gone.isEmpty()) return@withContext FreeUpDone(0, 0, 0, reported = true)
        val at = now()
        dao.putFreed(gone.map { FreedEntity(it.absolute, it.itemId, it.fingerprint.orEmpty(), it.size, at) })
        val reported = report(gone)
        val shots = settlement.whole.size + settlement.partial.size
        Log.i(TAG, "freed ${gone.size} files of $shots shots, reported=$reported")
        FreeUpDone(gone.size, gone.sumOf { it.size }, shots, reported)
    }

    /** Reports [files] as freed, root by root; true when every batch was answered (the rows then leave `backup_files`). */
    suspend fun report(files: List<BackupFileEntity>): Boolean {
        val api = apiOf() ?: return false
        val batch = REPORT_BATCH
        var ok = true
        for ((rootId, rows) in files.groupBy { it.rootId }) {
            for (part in rows.chunked(batch)) {
                try {
                    api.backupReport(BackupReport(rootId, removed = part.map { BackupRemoval(it.path, BackupPlan.REASON_FREED) }))
                    dao.deleteFiles(rootId, part.map { it.path })
                } catch (e: ServerException) {
                    Log.w(TAG, "free up: report ${e.status} ${e.code}")
                    ok = false
                    // A root gone or stopped: its rows stay; the backup run makes the root again and reports them.
                } catch (e: IOException) {
                    ok = false
                }
            }
        }
        return ok
    }

    companion object {
        const val TAG = "HoardFreeUp"
        const val PHASE_HASHING = "hashing"
        const val PHASE_VERIFYING = "verifying"
        /** `maxReportEntries` of every hoard-server 0.12.0 (removals take as many). */
        private const val REPORT_BATCH = 1000
    }
}
