package dev.hfps.hoard.server.compress

import android.util.Log
import dev.hfps.hoard.server.Fingerprint
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.api.CompressItem
import dev.hfps.hoard.server.api.CompressPhoneReport
import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerException
import dev.hfps.hoard.server.backup.BackupPlan
import dev.hfps.hoard.server.backup.BackupState
import dev.hfps.hoard.server.backup.Hasher
import dev.hfps.hoard.server.db.CompressPhoneEntity
import dev.hfps.hoard.server.db.GalleryDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A MediaStore row as the replace reads it (ms times). */
data class PhoneMedia(
    val mediaId: Long,
    val path: String,
    val size: Long,
    /** `DATE_MODIFIED` in ms (seconds × 1000). */
    val modified: Long,
    val dateTaken: Long?,
    val durationMs: Long?,
    val favorite: Boolean,
    val owner: String?,
)

/** What a write did (beyond the bytes): whether `IS_PENDING` was set around it (never on an existing row, Amendment (A2)). */
data class PhoneWrite(val pending: Boolean)

/**
 * The phone side of a replace, given by the app ([dev.hfps.hoard.app.compress.PhoneCompress]): files, MediaStore, the
 * consent dialog, the in-place write, Hoard's own index. Everything that touches Android lives here; the decisions are
 * [CompressPlan]'s and the order [CompressEngine]'s.
 */
interface CompressSource {
    /** Android 11+ with All files access: the in-place write and the restored mtime need it. */
    fun supported(): Boolean

    /** The phone's file for a volume (`primary` or a UUID) and a path on it; null when it is not mounted here. */
    fun absolute(volume: String, path: String): String?

    /** Where an item's part is downloaded (app-specific storage on the file's volume, not scanned). */
    fun partFile(row: CompressPhoneEntity): File

    fun file(absolute: String): File = File(absolute)

    /** The MediaStore row of the file at [absolute] now (null: none). */
    suspend fun media(absolute: String): PhoneMedia?

    /**
     * Whether the part plays as the phone file does: duration within 0.1 s, the same video and audio tracks, the same
     * width and height (`MediaMetadataRetriever`, `MediaExtractor`). Null when it does; else what differs.
     */
    suspend fun compareMedia(part: File, phone: File): String?

    /** Whether writing [media] needs the owner's consent: not Hoard's file, and no Media management (`MANAGE_MEDIA`). */
    fun needsConsent(media: PhoneMedia): Boolean

    /** One system dialog (`createWriteRequest`) for [media]; true when the owner allowed it, false when refused or no screen. */
    suspend fun requestWrite(media: List<PhoneMedia>): Boolean

    /**
     * Copies [part] over the phone file in place (truncating, `fsync`), with `IS_PENDING` around it where MediaStore
     * allows it, sets the mtime back to [mtime] and has MediaStore scan the file again (before and after the mtime).
     */
    suspend fun write(row: CompressPhoneEntity, media: PhoneMedia?, part: File, mtime: Long): PhoneWrite

    /** Writes `DATE_TAKEN` back after a write changed it; false when MediaStore refused. */
    suspend fun setDateTaken(mediaId: Long, dateTaken: Long): Boolean

    /** The run's foreground notification (a long download). */
    suspend fun foreground(progress: CompressProgress) {}

    /** Rows wait for the owner's consent and no screen could ask: tell the owner (a notification). */
    fun consentWaiting(count: Int) {}
}

/** Where a run stands (Settings › Compress videos and the notification). */
data class CompressProgress(
    val running: Boolean = false,
    /** `listing`, `downloading`, `verifying`, `writing`, `reporting`. */
    val phase: String? = null,
    val name: String? = null,
    val done: Long = 0,
    val size: Long = 0,
)

enum class CompressOutcome { NotPaired, Unsupported, Unauthorized, Offline, Done, Stopped, Busy }

data class CompressRunResult(
    val outcome: CompressOutcome,
    /** Files written and reported this run. */
    val replaced: Int = 0,
    /** Rows waiting for the owner's consent. */
    val waitingConsent: Int = 0,
    /** The server still works on items of this phone (queued … verifying): a later run picks them up. */
    val serverPending: Boolean = false,
    /** Rows wait for a back-off (a failed download or report). */
    val retryAt: Long? = null,
)

/**
 * The phone's replace of compressed videos (ADR 0025 "Phone replace flow (Hoard)"): reads this phone's items waiting
 * for it, keeps their redo journal (`compress_phone`), downloads each target by range, verifies it, checks the phone
 * file, asks consent in batches when needed, writes in place, reads back, updates Hoard's index and backup rows (so the
 * backup neither hashes nor uploads the new file), and reports. One run at a time per process; runs from the
 * [CompressWorker], or from the screen for the consent step ([run] with `interactive`).
 */
class CompressEngine(
    private val db: GalleryDatabase,
    private val apiOf: suspend () -> ServerApi?,
    private val source: CompressSource,
    private val now: () -> Long = System::currentTimeMillis,
    private val downloaderOf: (ServerApi) -> CompressDownloader = { CompressDownloader(it) },
) {
    private val dao = db.compress()
    private val mutex = Mutex()
    private val _progress = MutableStateFlow(CompressProgress())
    val progress: StateFlow<CompressProgress> = _progress

    /**
     * One pass. [interactive]: a screen is up, so rows waiting for consent are asked (one dialog per batch of
     * [CompressPlan.MAX_CONSENT]); otherwise they stay `verified` ("Waiting for your permission").
     */
    suspend fun run(stopped: () -> Boolean = { false }, interactive: Boolean = false): CompressRunResult = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext CompressRunResult(CompressOutcome.Busy)
        try {
            _progress.value = CompressProgress(running = true, phase = PHASE_LISTING)
            pass(stopped, interactive)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServerException) {
            Log.w(TAG, "compress: ${e.status} ${e.code}")
            CompressRunResult(if (e.unauthorized) CompressOutcome.Unauthorized else CompressOutcome.Offline)
        } catch (e: IOException) {
            Log.w(TAG, "compress: offline (${e.javaClass.simpleName})")
            CompressRunResult(CompressOutcome.Offline)
        } finally {
            _progress.value = CompressProgress()
            mutex.unlock()
        }
    }

    /** "Keep originals": rows waiting for consent are given up (`refused`); the server cancels them. */
    suspend fun keepOriginals(ids: Collection<String>) = withContext(Dispatchers.IO) {
        for (id in ids) {
            val row = dao.phone(id) ?: continue
            if (row.state != PhoneState.VERIFIED && row.state != PhoneState.DOWNLOADING && row.state != PhoneState.VERIFYING) continue
            if (row.writeStarted) continue
            dao.putPhone(CompressPlan.abandon(row, PhoneError.REFUSED, now()))
        }
    }

    private suspend fun pass(stopped: () -> Boolean, interactive: Boolean): CompressRunResult {
        db.account().get() ?: return CompressRunResult(CompressOutcome.NotPaired)
        val api = apiOf() ?: return CompressRunResult(CompressOutcome.NotPaired)
        // A journal already started always finishes; new items only with what the replace needs.
        var serverPending = false
        if (source.supported()) {
            serverPending = listItems(api)
        }
        var replaced = 0
        var foregrounded = false
        val consent = ArrayList<Pair<CompressPhoneEntity, PhoneMedia>>()
        for (initial in dao.phone()) {
            var row = initial
            while (true) {
                if (stopped()) return result(CompressOutcome.Stopped, replaced, serverPending)
                val step = CompressPlan.resume(row, now())
                if (step == PhoneStep.Done || step is PhoneStep.Wait) break
                if (!source.supported() && row.state in PhoneState.BEFORE_WRITE) break
                if (step == PhoneStep.Download && !foregrounded) {
                    foregrounded = true
                    runCatching { source.foreground(_progress.value) }
                }
                val next = when (step) {
                    PhoneStep.Download -> download(api, row, stopped) ?: return result(CompressOutcome.Stopped, replaced, serverPending)
                    PhoneStep.Verify -> verify(row)
                    PhoneStep.Check -> when (val c = check(row)) {
                        is Checked.Write -> if (c.media != null && source.needsConsent(c.media)) {
                            consent += row to c.media
                            null
                        } else {
                            write(row.copy(originalMtime = c.mtime, dateTaken = c.media?.dateTaken ?: row.dateTaken), c.media)
                        }
                        is Checked.Next -> c.row
                    }
                    PhoneStep.Write -> write(row, source.media(row.absolute))
                    PhoneStep.Report -> report(api, row).also { if (it.state == PhoneState.REPORTED && row.report != PhoneReport.NONE) replaced++ }
                    PhoneStep.Abandon -> abandon(api, row)
                    else -> null
                } ?: break
                if (next == row) break
                row = next
                dao.putPhone(row)
            }
        }
        // Consent: one system dialog per batch while a screen is up; otherwise the rows wait.
        var waiting = 0
        if (consent.isNotEmpty()) {
            if (interactive) {
                for (batch in CompressPlan.consentBatches(consent.map { it.first }).map { b -> b.map { r -> consent.first { it.first.id == r.id } } }) {
                    if (stopped()) break
                    val allowed = source.requestWrite(batch.map { it.second })
                    Log.i(TAG, "compress: consent for ${batch.size} files: $allowed")
                    for ((r, media) in batch) {
                        if (!allowed) {
                            dao.putPhone(r.copy(error = PhoneError.PERMISSION, updatedAt = now()))
                            waiting++
                            continue
                        }
                        // Written under the grant at once, then read back and reported like any other.
                        var row = write(r.copy(originalMtime = r.originalMtime ?: source.file(r.absolute).lastModified(), dateTaken = media.dateTaken), media)
                        dao.putPhone(row)
                        while (row.state == PhoneState.WRITTEN || row.state == PhoneState.WRITING) {
                            val next = if (row.state == PhoneState.WRITING) write(row, source.media(row.absolute)) else report(api, row)
                            if (next == row) break
                            if (next.state == PhoneState.REPORTED) replaced++
                            row = next
                            dao.putPhone(row)
                        }
                    }
                }
            } else {
                for ((r, _) in consent) if (r.error != PhoneError.PERMISSION) dao.putPhone(r.copy(error = PhoneError.PERMISSION, updatedAt = now()))
                waiting = consent.size
                source.consentWaiting(waiting)
            }
        }
        return result(CompressOutcome.Done, replaced, serverPending, waiting)
    }

    private suspend fun result(outcome: CompressOutcome, replaced: Int, serverPending: Boolean, waiting: Int? = null): CompressRunResult {
        val rows = dao.phone()
        val retry = rows.mapNotNull { r -> (CompressPlan.resume(r, now()) as? PhoneStep.Wait)?.at }.minOrNull()
        val consent = waiting ?: rows.count { it.state == PhoneState.VERIFIED && it.error == PhoneError.PERMISSION }
        return CompressRunResult(outcome, replaced, consent, serverPending, retry)
    }

    /** Reads this phone's items waiting for it into the journal; true when the server still works on others of ours. */
    private suspend fun listItems(api: ServerApi): Boolean {
        val items = ArrayList<CompressItem>()
        var cursor: String? = null
        var pages = 0
        var complete = true
        try {
            do {
                val page = api.compressItems(states = "${Compress.STATE_READY},${Compress.STATE_REPLACING}", cursor = cursor, limit = 200, mine = true)
                items += page.items.filter { it.source == null || it.source == "blob" }
                cursor = page.cursor
                pages++
                if (page.hasMore && pages >= MAX_PAGES) complete = false
            } while (page.hasMore && !cursor.isNullOrEmpty() && pages < MAX_PAGES)
        } catch (e: ServerException) {
            if (e.unauthorized) throw e
            // A server without the phone routes (before 0.13.0): nothing to do.
            if (e.status == 404 || e.status == 422) return false
            throw e
        }
        // A restore again of an item replaced by the phone has state `replacing` and direction `compress`: the item's
        // `direction` says which file the phone writes. Items that are not the phone's to write are skipped by the plan.
        val plan = CompressPlan.reconcile(items, dao.phone(), complete, now(), source::absolute)
        for (row in plan.drop) {
            row.part?.let { File(it).delete() }
            dao.deletePhone(row.id)
        }
        for (row in plan.put) {
            dao.phone(row.id)?.part?.let { File(it).delete() }
            dao.putPhone(row.copy(mediaId = source.media(row.absolute)?.mediaId))
        }
        if (plan.put.isNotEmpty() || plan.drop.isNotEmpty()) Log.i(TAG, "compress: ${plan.put.size} new, ${plan.drop.size} dropped of ${items.size} waiting")
        return runCatching {
            api.compressItems(states = "queued,estimating,encoding,verifying", limit = 1, mine = true).items.isNotEmpty()
        }.getOrDefault(false)
    }

    // --- Steps ------------------------------------------------------------------------------------------------------

    /** Null when the run was asked to stop (the offset is saved). */
    private suspend fun download(api: ServerApi, initial: CompressPhoneEntity, stopped: () -> Boolean): CompressPhoneEntity? {
        var row = initial
        val target = source.file(row.absolute)
        // A target already at the path (a write done by a run whose answer was lost): read it back, no download.
        if (!row.writeStarted && row.offset == 0L && target.isFile && target.length() == row.targetSize) {
            val fp = runCatching { Fingerprint.of(target) }.getOrNull()
            if (fp == row.targetFingerprint) return readBack(row.copy(writeStarted = true), source.media(row.absolute))
        }
        val part = row.part?.let(::File) ?: source.partFile(row)
        if (row.part == null) {
            row = row.copy(part = part.absolutePath, updatedAt = now())
            dao.putPhone(row)
        }
        val free = runCatching { part.parentFile?.let { it.mkdirs(); it.usableSpace } }.getOrNull()
        if (free != null && free < row.targetSize - row.offset + SPACE_MARGIN) {
            return row.copy(error = PhoneError.SPACE, attempts = row.attempts + 1, nextAttemptAt = now() + CompressPlan.backoffMs(row.attempts + 1), updatedAt = now())
        }
        _progress.value = CompressProgress(true, PHASE_DOWNLOADING, target.name, row.offset, row.targetSize)
        val path = if (CompressPlan.fromOutputRoute(row)) api.compressOutputPath(row.id) else api.originalPath(row.targetFingerprint)
        val outcome = try {
            downloaderOf(api).download(
                path = path,
                part = part,
                size = row.targetSize,
                offset = row.offset,
                etag = row.etag,
                save = { off, tag ->
                    row = row.copy(offset = off, etag = tag, updatedAt = now())
                    dao.putPhone(row)
                },
                progress = { off -> _progress.value = _progress.value.copy(done = off) },
                stopped = stopped,
            )
        } catch (e: ServerException) {
            if (e.unauthorized) throw e
            Log.w(TAG, "compress: download refused ${e.status} ${e.code}")
            return row.copy(error = PhoneError.OFFLINE, attempts = row.attempts + 1, nextAttemptAt = now() + CompressPlan.backoffMs(row.attempts + 1), updatedAt = now())
        } catch (e: IOException) {
            Log.w(TAG, "compress: download broke (${e.javaClass.simpleName})")
            return row.copy(error = PhoneError.OFFLINE, attempts = row.attempts + 1, nextAttemptAt = now() + CompressPlan.backoffMs(row.attempts + 1), updatedAt = now())
        }
        return when (outcome) {
            DownloadOutcome.Done -> row.copy(state = PhoneState.VERIFYING, error = null, attempts = 0, nextAttemptAt = null, updatedAt = now())
            DownloadOutcome.Stopped -> null
            DownloadOutcome.NotFound -> {
                // Cancelled on the server (or the original purged): nothing written, the row goes. A write already
                // started keeps trying (the phone file is neither version until it is written).
                if (row.writeStarted) {
                    row.copy(error = PhoneError.OFFLINE, attempts = row.attempts + 1, nextAttemptAt = now() + CompressPlan.backoffMs(row.attempts + 1), updatedAt = now())
                } else {
                    part.delete()
                    row.copy(state = PhoneState.ABANDONED, error = "not_found", updatedAt = now())
                }
            }
        }
    }

    private suspend fun verify(row: CompressPhoneEntity): CompressPhoneEntity {
        val part = row.part?.let(::File)
        _progress.value = CompressProgress(true, PHASE_VERIFYING, File(row.absolute).name, 0, row.targetSize)
        val ok = part != null && part.isFile && part.length() == row.targetSize && runCatching { Hasher.sha256(part, null) }.getOrNull() == row.targetSha256
        // The media facts are compared with the phone file only while it is still the source.
        val media = if (ok && !row.writeStarted) source.compareMedia(part!!, source.file(row.absolute)).also { if (it != null) Log.w(TAG, "compress: the part differs from the phone file ($it)") } else null
        if (ok && media == null) return row.copy(state = PhoneState.VERIFIED, error = null, updatedAt = now())
        part?.delete()
        return CompressPlan.verifyFailed(row.copy(part = null), now())
    }

    private sealed interface Checked {
        data class Write(val media: PhoneMedia?, val mtime: Long) : Checked
        data class Next(val row: CompressPhoneEntity) : Checked
    }

    private suspend fun check(row: CompressPhoneEntity): Checked {
        if (row.writeStarted) return Checked.Next(row.copy(state = PhoneState.WRITING, updatedAt = now()))
        val file = source.file(row.absolute)
        val media = source.media(row.absolute)
        val nowFile = PhoneFileNow(
            exists = file.isFile,
            size = if (file.isFile) file.length() else -1,
            modified = if (file.isFile) file.lastModified() else 0,
            fingerprint = if (file.isFile) runCatching { Fingerprint.of(file) }.getOrNull() else null,
            mediaPath = media?.path,
        )
        return when (val c = CompressPlan.check(row, nowFile)) {
            PhoneCheck.Ok -> Checked.Write(media, nowFile.modified)
            PhoneCheck.AlreadyTarget -> Checked.Next(readBack(row.copy(writeStarted = true, originalMtime = row.originalMtime ?: nowFile.modified), media))
            is PhoneCheck.Abandon -> {
                Log.i(TAG, "compress: the phone file is ${c.reason}")
                row.part?.let { File(it).delete() }
                Checked.Next(CompressPlan.abandon(row.copy(part = null, offset = 0), c.reason, now()))
            }
        }
    }

    /** The write (and its redo after a kill): the journal says `writing` before the first byte. */
    private suspend fun write(initial: CompressPhoneEntity, media: PhoneMedia?): CompressPhoneEntity {
        val part = initial.part?.let(::File)
        if (part == null || !part.isFile || part.length() != initial.targetSize) {
            // The part went missing after the write started: downloaded again, then written.
            return initial.copy(state = PhoneState.DOWNLOADING, part = null, offset = 0, etag = null, writeStarted = true, updatedAt = now())
        }
        val mtime = initial.originalMtime ?: source.file(initial.absolute).lastModified()
        val row = initial.copy(state = PhoneState.WRITING, writeStarted = true, originalMtime = mtime, mediaId = media?.mediaId ?: initial.mediaId, updatedAt = now())
        dao.putPhone(row)
        _progress.value = CompressProgress(true, PHASE_WRITING, File(row.absolute).name, 0, row.targetSize)
        try {
            val w = source.write(row, media, part, mtime)
            if (!w.pending) Log.i(TAG, "compress: written without IS_PENDING")
        } catch (e: IOException) {
            Log.w(TAG, "compress: the write failed (${e.javaClass.simpleName})")
            return row.copy(error = PhoneError.OFFLINE, updatedAt = now())
        } catch (e: SecurityException) {
            Log.w(TAG, "compress: the write was refused", e)
            return row.copy(error = PhoneError.PERMISSION, updatedAt = now())
        }
        return readBack(row, source.media(row.absolute) ?: media)
    }

    /**
     * The read-back: the file's SHA-256 is the target's, MediaStore's row has its size, its duration and the
     * `DATE_TAKEN` it had (within a second; written back when it moved). Then Hoard's index and backup rows take the
     * new file, so the backup neither hashes nor uploads it.
     */
    private suspend fun readBack(row: CompressPhoneEntity, media: PhoneMedia?): CompressPhoneEntity {
        val file = source.file(row.absolute)
        val sha = if (file.isFile && file.length() == row.targetSize) runCatching { Hasher.sha256(file, null) }.getOrNull() else null
        if (sha != row.targetSha256) {
            Log.w(TAG, "compress: the read-back did not match")
            return CompressPlan.readBackFailed(row, now())
        }
        var error: String? = null
        val fresh = media?.let { source.media(row.absolute) } ?: media
        val taken = row.dateTaken
        // A rewritten file whose metadata MediaStore reads differently (no capture time, another one): written back.
        if (fresh != null && taken != null && (fresh.dateTaken == null || kotlin.math.abs(fresh.dateTaken - taken) > 1000)) {
            if (!source.setDateTaken(fresh.mediaId, taken)) error = PhoneError.DATE_TAKEN
        }
        if (fresh != null && fresh.size != row.targetSize) Log.w(TAG, "compress: MediaStore still has the old size")
        recordWritten(row, fresh, file)
        row.part?.let { File(it).delete() }
        return row.copy(state = PhoneState.WRITTEN, part = null, error = error, nextAttemptAt = null, attempts = 0, updatedAt = now())
    }

    /**
     * `device_media` and `backup_files` take the new file (its size, the MediaStore time, the target's fingerprint and
     * SHA-256): the backup reports it as it is and the server answers `stored` (the swap), nothing hashed or uploaded.
     */
    private suspend fun recordWritten(row: CompressPhoneEntity, media: PhoneMedia?, file: File) {
        val size = row.targetSize
        val modified = media?.modified ?: (file.lastModified() / 1000 * 1000)
        val mediaId = media?.mediaId ?: row.mediaId
        if (mediaId != null) db.device().replaced(mediaId, size, modified, row.targetFingerprint, row.targetSha256)
        val backup = db.backup()
        val rows = backup.filesAt(row.absolute)
        if (rows.isNotEmpty()) {
            backup.putFiles(
                rows.map {
                    it.copy(
                        size = size,
                        modified = modified,
                        mediaId = mediaId ?: it.mediaId,
                        fingerprint = row.targetFingerprint,
                        fpSize = size,
                        fpModified = modified,
                        sha256 = row.targetSha256,
                        shaSize = size,
                        shaModified = modified,
                        state = BackupState.STORED,
                        serverState = "stored",
                        reported = null,
                        uploadId = null,
                        uploadOffset = 0,
                        attempts = 0,
                        nextAttemptAt = null,
                        error = null,
                        updatedAt = now(),
                    )
                },
            )
        }
    }

    private suspend fun report(api: ServerApi, row: CompressPhoneEntity): CompressPhoneEntity {
        if (row.report == PhoneReport.NONE) return row.copy(state = PhoneState.REPORTED, updatedAt = now())
        _progress.value = CompressProgress(true, PHASE_REPORTING, File(row.absolute).name)
        val file = source.file(row.absolute)
        val restored = row.report == PhoneReport.RESTORED
        val body = CompressPhoneReport(row.targetFingerprint, row.targetSha256, row.targetSize, file.lastModified())
        return try {
            api.compressPhoneReport(row.id, restored, body)
            settledBackupRow(row)
            row.copy(state = PhoneState.REPORTED, nextAttemptAt = null, attempts = 0, updatedAt = now())
        } catch (e: ServerException) {
            if (e.unauthorized) throw e
            when {
                // Settled already (a backup report did it), or no longer the server's to settle: the file stays the
                // target and the backup's next report tells the server what is there.
                e.status == 409 || e.status == 404 -> {
                    Log.i(TAG, "compress: report answered ${e.status} ${e.reason ?: e.code}")
                    row.copy(state = PhoneState.REPORTED, error = row.error ?: (e.reason ?: e.code), updatedAt = now())
                }
                else -> row.copy(attempts = row.attempts + 1, nextAttemptAt = now() + CompressPlan.backoffMs(row.attempts + 1), updatedAt = now())
            }
        } catch (e: IOException) {
            row.copy(attempts = row.attempts + 1, nextAttemptAt = now() + CompressPlan.backoffMs(row.attempts + 1), updatedAt = now())
        }
    }

    /** After `/replaced` (or `/restored`): the backup row counts as reported as it is, so the next pass sends nothing for it. */
    private suspend fun settledBackupRow(row: CompressPhoneEntity) {
        val backup = db.backup()
        val rows = backup.filesAt(row.absolute).filter { it.fingerprint == row.targetFingerprint && it.size == row.targetSize }
        if (rows.isNotEmpty()) backup.putFiles(rows.map { it.copy(reported = BackupPlan.reportKey(it), updatedAt = now()) })
    }

    private suspend fun abandon(api: ServerApi, row: CompressPhoneEntity): CompressPhoneEntity {
        val reason = when (row.abandonReason) {
            PhoneError.GONE -> Compress.ABANDON_GONE
            PhoneError.CHANGED -> Compress.ABANDON_CHANGED
            PhoneError.REFUSED -> Compress.ABANDON_REFUSED
            else -> Compress.ABANDON_VERIFY
        }
        return try {
            api.compressAbandon(row.id, reason)
            CompressPlan.afterAbandon(row, now())
        } catch (e: ServerException) {
            if (e.unauthorized) throw e
            if (e.status == 409 || e.status == 404) CompressPlan.afterAbandon(row, now())
            else row.copy(attempts = row.attempts + 1, nextAttemptAt = now() + CompressPlan.backoffMs(row.attempts + 1), updatedAt = now())
        } catch (e: IOException) {
            row.copy(attempts = row.attempts + 1, nextAttemptAt = now() + CompressPlan.backoffMs(row.attempts + 1), updatedAt = now())
        }
    }

    companion object {
        const val TAG = "HoardCompress"
        const val PHASE_LISTING = "listing"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_VERIFYING = "verifying"
        const val PHASE_WRITING = "writing"
        const val PHASE_REPORTING = "reporting"
        private const val MAX_PAGES = 20
        /** Room kept free beyond the part (the phone must not fill up with a download). */
        const val SPACE_MARGIN = 512L shl 20
    }
}
