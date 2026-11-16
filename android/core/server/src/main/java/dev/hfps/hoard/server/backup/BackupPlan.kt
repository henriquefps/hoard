package dev.hfps.hoard.server.backup

import dev.hfps.hoard.server.Fingerprint
import dev.hfps.hoard.server.api.BackupRemoval
import dev.hfps.hoard.server.api.BackupReportEntry
import dev.hfps.hoard.server.api.BackupReportResult
import dev.hfps.hoard.server.db.BackupFileEntity

/** The kinds of files a root covers (ADR 0024 "`GET /v1/backup/roots`" `types`). */
object BackupKind {
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val RAW = "raw"
    const val SIDECAR = "sidecar"

    /** What a root declares: `image` and `video`, plus `raw` and `sidecar` only while Hoard has All files access. */
    fun typesFor(allFilesAccess: Boolean): List<String> = if (allFilesAccess) listOf(IMAGE, VIDEO, RAW, SIDECAR) else listOf(IMAGE, VIDEO)

    fun encode(types: List<String>): String = types.joinToString(",")

    fun decode(types: String): List<String> = types.split(',').filter { it.isNotBlank() }
}

/**
 * A backed-up file's state (`backup_files.state`), what Settings › Backup and the Gallery's badge show. `paused` and
 * `failed` carry a reason in `error`; `refused` the server's reason (not tried again until the file changes).
 */
object BackupState {
    /** No fingerprint yet. */
    const val WAITING = "waiting"
    /** To report, or to upload. */
    const val QUEUED = "queued"
    const val HASHING = "hashing"
    const val UPLOADING = "uploading"
    /** Backed up: the bytes are in the server's store. */
    const val STORED = "stored"
    /** Already on the server: an indexed NAS file has the same content (no upload). */
    const val NAS = "nas"
    /** Backed up, and the server's own re-hash matched (`verified: yes`). */
    const val VERIFIED = "verified"
    /** Waits for a rule: `error` = [REASON_WIFI] or [REASON_CHARGING]. */
    const val PAUSED = "paused"
    /** `error` = [REASON_UNREADABLE], [REASON_SERVER_ERROR], [REASON_SERVER_FULL], [REASON_TOO_LARGE]; tried again later. */
    const val FAILED = "failed"
    /** The bytes sent did not match the hash (`checksum_mismatch`): identified and hashed again on the next run. */
    const val CHANGED = "changed"
    /** The server refused the entry (`error` = its reason): not reported again until the file changes. */
    const val REFUSED = "refused"

    val DONE = setOf(STORED, NAS, VERIFIED)

    const val REASON_WIFI = "wifi"
    const val REASON_CHARGING = "charging"
    const val REASON_UNREADABLE = "unreadable"
    const val REASON_SERVER_ERROR = "server_error"
    const val REASON_SERVER_FULL = "server_full"
    const val REASON_TOO_LARGE = "too_large"
}

/** Extensions the server takes as phone backup entries (ADR 0014's, plus PNG, WebP and GIF stills for mirrors). */
object BackupExtensions {
    val RAW = setOf("arw", "srf", "sr2", "cr2", "cr3", "nef", "nrw", "raf", "dng", "orf", "rw2", "pef", "srw")
    val STILL = setOf("jpg", "jpeg", "jpe", "jfif", "heic", "heif", "hif", "png", "webp", "gif")
    val VIDEO = setOf("mp4", "m4v", "mov", "3gp", "3g2", "mts", "m2ts", "mkv", "webm", "avi")
    const val SIDECAR = "xmp"

    fun of(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else name.substring(dot + 1).lowercase()
    }
}

/**
 * A file of a backed-up folder as the phone has it now. [path] is relative to its volume (what is reported), [absolute]
 * where it is on the phone, [mediaId] its `device_media` row (null for a file found by listing). [fingerprint] and
 * [sha256] are given only when valid for this [size] and [modified].
 */
data class PhoneFile(
    val path: String,
    val absolute: String,
    val mediaId: Long?,
    val kind: String,
    val size: Long,
    val modified: Long,
    val taken: Long,
    val fingerprint: String? = null,
    val sha256: String? = null,
)

/**
 * What a root holds now. [complete]: the volume is mounted and the listing finished, so a file not in it left the
 * phone (ADR 0024: removals are reported only after a complete listing that is not empty). [unsupported]: files of the
 * folder whose format the server does not take (shown, never reported).
 */
data class Listing(val files: List<PhoneFile>, val complete: Boolean, val unsupported: Int = 0)

/** Settings › Backup › Rules (owner's decision 8): photos on unmetered networks (on), while charging (off); videos only while charging (on). */
data class BackupRules(
    val photosUnmetered: Boolean = true,
    val photosCharging: Boolean = false,
    val videosCharging: Boolean = true,
)

/** The phone now: on an unmetered network, charging. */
data class Conditions(val unmetered: Boolean, val charging: Boolean)

/** What a merge of a listing into a root's rows found. */
data class Merge(
    /** New and changed rows to store. */
    val rows: List<BackupFileEntity>,
    /** Uploads of content a file no longer has (it changed): abandoned on the server. */
    val abandoned: List<String>,
)

/** One folder's numbers for Settings › Backup. */
data class FolderStatus(
    val files: Int = 0,
    val bytes: Long = 0,
    /** In the server's store (stored or verified). */
    val backedUp: Int = 0,
    val backedUpBytes: Long = 0,
    /** Already on the server (a NAS file has the content). */
    val onServer: Int = 0,
    val waitingWifi: Int = 0,
    val waitingCharging: Int = 0,
    /** To identify, report or upload. */
    val queued: Int = 0,
    val failed: Int = 0,
    val refused: Int = 0,
) {
    val done: Int get() = backedUp + onServer
}

/**
 * The phone backup's decisions, pure and JVM-tested (ADR 0024 "Modules and data"): which files of a root are new,
 * changed or gone, what to report, what to upload and in which order, under which rule a file waits, and each file's
 * next state from the server's answer.
 */
object BackupPlan {
    /** Upload backoff after a failure: 1 min, doubling, at most 6 hours. */
    fun backoffMs(attempts: Int): Long = minOf(60_000L shl (attempts - 1).coerceIn(0, 20), 6 * 3_600_000L)

    /**
     * The kind of a phone file (null: not backed up): a MediaStore video row with a video extension is `video`; a
     * MediaStore image row with a still or RAW extension `image` (a DNG Android lists is an image); a file found by
     * listing a root is `raw` (a RAW MediaStore did not list) or `sidecar` (`.xmp`).
     */
    fun kindOf(name: String, mediaStore: Boolean, video: Boolean): String? {
        val ext = BackupExtensions.of(name)
        return when {
            mediaStore && video -> BackupKind.VIDEO.takeIf { ext in BackupExtensions.VIDEO }
            mediaStore -> BackupKind.IMAGE.takeIf { ext in BackupExtensions.STILL || ext in BackupExtensions.RAW }
            ext in BackupExtensions.RAW -> BackupKind.RAW
            ext == BackupExtensions.SIDECAR -> BackupKind.SIDECAR
            else -> null
        }
    }

    /** The rule a file of [kind] waits for now, or null when it may go: `wifi` before `charging`. */
    fun pause(kind: String, rules: BackupRules, now: Conditions): String? = when {
        rules.photosUnmetered && !now.unmetered -> BackupState.REASON_WIFI
        rules.photosCharging && !now.charging -> BackupState.REASON_CHARGING
        kind == BackupKind.VIDEO && rules.videosCharging && !now.charging -> BackupState.REASON_CHARGING
        else -> null
    }

    /** The key of what a row would report: reported again when it differs from [BackupFileEntity.reported]. */
    fun reportKey(row: BackupFileEntity): String = "${row.fingerprint}|${row.size}|${row.modified}"

    /** A fingerprint valid for the row's size and mtime. */
    fun identified(row: BackupFileEntity): Boolean =
        Fingerprint.isValid(row.fingerprint) && row.fpSize == row.size && row.fpModified == row.modified && row.state != BackupState.CHANGED

    /** A SHA-256 valid for the row's size and mtime. */
    fun hashed(row: BackupFileEntity): Boolean = row.sha256 != null && row.shaSize == row.size && row.shaModified == row.modified

    /**
     * Brings a root's rows to what the phone has ([listing]): new files get a row; a file whose size, mtime or place
     * changed starts again (its server state, upload and hash are dropped, a running upload abandoned); identities the
     * index learned are copied in. Files gone from the listing keep their rows until [removals] reports them.
     */
    fun merge(rootId: String, listing: Listing, stored: List<BackupFileEntity>, now: Long): Merge {
        val byPath = stored.associateBy { it.path }
        val rows = ArrayList<BackupFileEntity>()
        val abandoned = ArrayList<String>()
        for (f in listing.files) {
            val old = byPath[f.path]
            if (old == null) {
                rows += fresh(rootId, f, now)
                continue
            }
            if (old.size != f.size || old.modified != f.modified || old.kind != f.kind) {
                old.uploadId?.let { abandoned += it }
                rows += fresh(rootId, f, now)
                continue
            }
            var row = old
            if (old.absolute != f.absolute || old.mediaId != f.mediaId || old.taken != f.taken) {
                row = row.copy(absolute = f.absolute, mediaId = f.mediaId, taken = f.taken)
            }
            // The index's identity, unless the server found the bytes differ from it (`changed`: made again here).
            if (old.state != BackupState.CHANGED && !identified(row) && f.fingerprint != null) {
                row = row.copy(fingerprint = f.fingerprint, fpSize = f.size, fpModified = f.modified)
            }
            if (old.state != BackupState.CHANGED && !hashed(row) && f.sha256 != null) {
                row = row.copy(sha256 = f.sha256, shaSize = f.size, shaModified = f.modified)
            }
            if (row.state == BackupState.WAITING && identified(row)) row = row.copy(state = BackupState.QUEUED)
            if (row != old) rows += row.copy(updatedAt = now)
        }
        // Uploads of files that left the phone are abandoned with their removal.
        val present = listing.files.mapTo(HashSet()) { it.path }
        for (old in stored) if (old.path !in present) old.uploadId?.let { abandoned += it }
        return Merge(rows, abandoned)
    }

    /**
     * Files of [rows] (a root's rows once merged and identified) that left the phone: only after a complete listing
     * that is not empty; `moved` when the content is at another path the phone has, else `deleted`.
     */
    fun removals(listing: Listing, rows: List<BackupFileEntity>): List<BackupRemoval> {
        if (!listing.complete || listing.files.isEmpty()) return emptyList()
        val present = listing.files.mapTo(HashSet()) { it.path }
        val contentAt = HashSet<String>()
        for (f in listing.files) f.fingerprint?.let { contentAt += it }
        for (row in rows) if (row.path in present && identified(row)) contentAt += row.fingerprint!!
        return rows.filter { it.path !in present }.map { old ->
            val moved = Fingerprint.isValid(old.fingerprint) && old.fingerprint in contentAt
            BackupRemoval(old.path, if (moved) REASON_MOVED else REASON_DELETED)
        }
    }

    private fun fresh(rootId: String, f: PhoneFile, now: Long): BackupFileEntity {
        val known = f.fingerprint != null && Fingerprint.isValid(f.fingerprint)
        return BackupFileEntity(
            rootId = rootId,
            path = f.path,
            absolute = f.absolute,
            mediaId = f.mediaId,
            kind = f.kind,
            size = f.size,
            modified = f.modified,
            taken = f.taken,
            fingerprint = f.fingerprint.takeIf { known },
            fpSize = f.size.takeIf { known },
            fpModified = f.modified.takeIf { known },
            sha256 = f.sha256,
            shaSize = f.size.takeIf { f.sha256 != null },
            shaModified = f.modified.takeIf { f.sha256 != null },
            state = if (known) BackupState.QUEUED else BackupState.WAITING,
            updatedAt = now,
        )
    }

    /** Rows to report now: identified, and never reported as they are (new, changed, or uploaded since). */
    fun toReport(rows: List<BackupFileEntity>): List<BackupFileEntity> =
        rows.filter { identified(it) && it.reported != reportKey(it) }.sortedByDescending { it.taken }

    fun entry(row: BackupFileEntity): BackupReportEntry =
        BackupReportEntry(row.path, row.fingerprint!!, row.size, row.modified, row.sha256.takeIf { hashed(row) })

    /** A row's next state from the server's answer to its report. */
    fun reported(row: BackupFileEntity, r: BackupReportResult, now: Long): BackupFileEntity {
        val verified = if (r.verified == "yes") now else row.verifiedAt
        val base = row.copy(reported = reportKey(row), serverState = r.state, itemId = r.itemId ?: row.itemId, verifiedAt = verified, updatedAt = now)
        return when (r.state) {
            "stored" -> base.copy(state = if (r.verified == "yes") BackupState.VERIFIED else BackupState.STORED, error = null, uploadId = null, uploadOffset = 0, attempts = 0, nextAttemptAt = null)
            "nas" -> base.copy(state = if (r.verified == "yes") BackupState.VERIFIED else BackupState.NAS, error = null, uploadId = null, uploadOffset = 0, attempts = 0, nextAttemptAt = null)
            "in_progress" -> base.copy(state = BackupState.QUEUED, error = null, nextAttemptAt = now + (r.retryAfter?.takeIf { it > 0 } ?: DEFAULT_RETRY_S) * 1000)
            "refused" -> base.copy(state = BackupState.REFUSED, error = r.reason ?: "refused", uploadId = null, uploadOffset = 0)
            // `missing`, `differs` (upload it), or a state this client does not know: queued for the upload phase.
            else -> base.copy(state = if (row.state == BackupState.FAILED || row.state == BackupState.PAUSED) row.state else BackupState.QUEUED)
        }
    }

    /** A row the server refused as a whole batch (`422`, older servers): kept out of reports until the file changes. */
    fun refused(row: BackupFileEntity, reason: String, now: Long): BackupFileEntity =
        row.copy(reported = reportKey(row), state = BackupState.REFUSED, error = reason, serverState = "refused", updatedAt = now)

    /**
     * What to upload now, newest first: the server answered `missing`, `differs` or `in_progress` for exactly this
     * file, it is not refused, and its next attempt is due.
     */
    fun uploads(rows: List<BackupFileEntity>, now: Long): List<BackupFileEntity> = rows.filter { row ->
        row.serverState in UPLOAD_STATES &&
            row.state !in BackupState.DONE &&
            row.state != BackupState.REFUSED &&
            identified(row) &&
            row.reported == reportKey(row) &&
            (row.nextAttemptAt ?: 0L) <= now
    }.sortedByDescending { it.taken }

    /** After a failed upload: `failed` with [reason], tried again after the backoff. */
    fun failed(row: BackupFileEntity, reason: String, now: Long): BackupFileEntity {
        val attempts = row.attempts + 1
        return row.copy(state = BackupState.FAILED, error = reason, attempts = attempts, nextAttemptAt = now + backoffMs(attempts), updatedAt = now)
    }

    /** `checksum_mismatch`: the file is identified and hashed again on the next run, its upload starts over. */
    fun changed(row: BackupFileEntity, now: Long): BackupFileEntity = row.copy(
        state = BackupState.CHANGED,
        sha256 = null,
        shaSize = null,
        shaModified = null,
        uploadId = null,
        uploadOffset = 0,
        error = null,
        updatedAt = now,
    )

    /** The upload finished: reported again (the server then answers `stored`). */
    fun uploaded(row: BackupFileEntity, now: Long): BackupFileEntity =
        row.copy(state = BackupState.QUEUED, reported = null, uploadId = null, uploadOffset = 0, attempts = 0, nextAttemptAt = null, error = null, updatedAt = now)

    /** A folder's numbers from its rows' counts (state, reason, files, bytes). */
    fun status(counts: List<Count>): FolderStatus {
        var s = FolderStatus()
        for (c in counts) {
            s = s.copy(files = s.files + c.files, bytes = s.bytes + c.bytes)
            s = when (c.state) {
                BackupState.STORED, BackupState.VERIFIED -> s.copy(backedUp = s.backedUp + c.files, backedUpBytes = s.backedUpBytes + c.bytes)
                BackupState.NAS -> s.copy(onServer = s.onServer + c.files)
                BackupState.PAUSED -> if (c.error == BackupState.REASON_CHARGING) s.copy(waitingCharging = s.waitingCharging + c.files) else s.copy(waitingWifi = s.waitingWifi + c.files)
                BackupState.FAILED -> s.copy(failed = s.failed + c.files)
                BackupState.REFUSED -> s.copy(refused = s.refused + c.files)
                else -> s.copy(queued = s.queued + c.files)
            }
        }
        return s
    }

    data class Count(val state: String, val error: String?, val files: Int, val bytes: Long)

    const val REASON_DELETED = "deleted"
    const val REASON_MOVED = "moved"
    const val DEFAULT_RETRY_S = 300L
    private val UPLOAD_STATES = setOf("missing", "differs", "in_progress")
}
