package dev.hfps.hoard.server.compress

import dev.hfps.hoard.server.Fingerprint
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.api.CompressItem
import dev.hfps.hoard.server.db.CompressPhoneEntity

/** A phone replace's journal states (`compress_phone.state`, ADR 0025 "Phone replace flow (Hoard)", "Steps"). */
object PhoneState {
    /** The target is being downloaded into the part file (resumed at `part_offset`). */
    const val DOWNLOADING = "downloading"
    /** The part is complete: its SHA-256, size and media facts are checked. */
    const val VERIFYING = "verifying"
    /** The part is the target: the phone file is checked, consent asked if needed, then written. */
    const val VERIFIED = "verified"
    /** The part is being copied over the phone file: redone from the part after a kill, never rolled back. */
    const val WRITING = "writing"
    /** The phone file is the target (read back), Hoard's index and backup rows know it: the route is reported next. */
    const val WRITTEN = "written"
    /** The server knows (or a local repair is done): settled. */
    const val REPORTED = "reported"
    /** The item is given up: `abandon` is sent next ([CompressPhoneEntity.abandonReason]). */
    const val ABANDON = "abandon"
    /** Given up and told: settled. */
    const val ABANDONED = "abandoned"

    /** Rows whose phone file is settled (free up may take its shot again). */
    val SETTLED = setOf(REPORTED, ABANDONED)

    /** Rows that may be dropped when the server no longer lists their item: nothing was written yet. */
    val BEFORE_WRITE = setOf(DOWNLOADING, VERIFYING, VERIFIED)
}

/** Why a row waits or what it gave up on (`compress_phone.error`, `abandon_reason`). */
object PhoneError {
    /** Waiting for the owner's consent (`createWriteRequest`): "Waiting for your permission". */
    const val PERMISSION = "permission"
    /** The phone file is not the one the server compressed (another size, time or content). */
    const val CHANGED = "changed"
    /** The phone file is gone (freed or deleted meanwhile). */
    const val GONE = "gone"
    /** The downloaded or written bytes did not match. */
    const val VERIFY = "verify"
    /** `DATE_TAKEN` changed with the write and could not be written back (noted, not a failure). */
    const val DATE_TAKEN = "date_taken"
    /** The phone has no room for the part file, or for the write (a restore grows the file in place): tried again later. */
    const val SPACE = "space"
    /** The in-place write failed (an I/O error: a volume removed, access revoked meanwhile): tried again later. */
    const val WRITE = "write"
    /**
     * The phone file's MediaStore row is unreadable (the query failed) or, for a write already started, missing: no
     * write without a known row (Amendment (A2), "Review of #81"); tried again later.
     */
    const val MEDIA = "media"
    /** The owner chose to keep the original ("Keep originals"). */
    const val REFUSED = "refused"
    /** The server or network failed; tried again later. */
    const val OFFLINE = "offline"
}

/** The report a row sends once written (`compress_phone.report`). */
object PhoneReport {
    const val REPLACED = "replaced"
    const val RESTORED = "restored"
    /** A local repair (the source written back after a failed write): nothing to report. */
    const val NONE = "none"
}

/** The next step of a row ([CompressPlan.resume]). */
sealed interface PhoneStep {
    data object Download : PhoneStep
    data object Verify : PhoneStep
    /** Check the phone file, then (with consent when needed) write. */
    data object Check : PhoneStep
    data object Write : PhoneStep
    data object Report : PhoneStep
    data object Abandon : PhoneStep
    /** Waits until [at] (a back-off). */
    data class Wait(val at: Long) : PhoneStep
    data object Done : PhoneStep
}

/** What [CompressPlan.reconcile] decided: rows to store (new, or a new cycle over a settled one) and rows to drop. */
data class PhoneReconcile(val put: List<CompressPhoneEntity>, val drop: List<CompressPhoneEntity>)

/**
 * What the phone file is now, before a write ([CompressPlan.check]). [mediaPath]: the path of its MediaStore row (null:
 * no row); [mediaReadable]: false when the MediaStore query failed (nothing is known about the row).
 */
data class PhoneFileNow(
    val exists: Boolean,
    val size: Long,
    val modified: Long,
    val fingerprint: String?,
    val mediaPath: String?,
    val mediaReadable: Boolean = true,
)

/** A check's verdict. */
sealed interface PhoneCheck {
    /** The source is in place, unchanged: write. */
    data object Ok : PhoneCheck
    /** The target is in place already (a write done before a lost answer): read it back, then report. */
    data object AlreadyTarget : PhoneCheck
    data class Abandon(val reason: String) : PhoneCheck
    /** Nothing known about the MediaStore row (the query failed): checked again later, never written meanwhile. */
    data object Wait : PhoneCheck
}

/**
 * The phone replace's decisions, pure and JVM-tested (ADR 0025 "Modules and data"): which server items become journal
 * rows, the next step of a row (also after a process was killed in any state, in both directions), what a failed
 * download, check or read-back leads to, and when a row is dropped.
 */
object CompressPlan {
    /** A download whose bytes fail twice is abandoned; a write whose read-back fails twice too. */
    const val MAX_VERIFY_FAILURES = 2
    const val MAX_REWRITES = 2
    /** `createWriteRequest` takes this many rows per system dialog. */
    const val MAX_CONSENT = 500
    /** The server accepts a reported mtime within 2 s of the original's (FAT keeps even seconds); so does the check. */
    const val MTIME_SLACK_MS = 2_000L
    /** Settled rows are forgotten after this long. */
    const val KEEP_SETTLED_MS = 30L * 86_400_000L

    /** Network back-off: 1 min, doubling, at most 6 hours. */
    fun backoffMs(attempts: Int): Long = minOf(60_000L shl (attempts - 1).coerceIn(0, 20), 6 * 3_600_000L)

    /**
     * The step a row is at, which is also where a process killed in that state resumes: a download goes on from its
     * offset, a check or verification runs again (nothing was written), a write is **redone** from the part (the phone
     * no longer has the source once it started), a written row reports, an abandon is sent.
     */
    fun resume(row: CompressPhoneEntity, now: Long): PhoneStep = when (row.state) {
        PhoneState.DOWNLOADING -> row.nextAttemptAt?.takeIf { it > now }?.let { PhoneStep.Wait(it) } ?: PhoneStep.Download
        PhoneState.VERIFYING -> PhoneStep.Verify
        // A check or write that waits (no room, a failed write, an unreadable MediaStore) backs off; never a hot loop.
        PhoneState.VERIFIED -> row.nextAttemptAt?.takeIf { it > now }?.let { PhoneStep.Wait(it) } ?: PhoneStep.Check
        PhoneState.WRITING -> row.nextAttemptAt?.takeIf { it > now }?.let { PhoneStep.Wait(it) } ?: PhoneStep.Write
        PhoneState.WRITTEN -> when {
            // A local repair settles without a call ([CompressEngine]'s report step).
            row.report == PhoneReport.NONE -> PhoneStep.Report
            row.nextAttemptAt != null && row.nextAttemptAt > now -> PhoneStep.Wait(row.nextAttemptAt)
            else -> PhoneStep.Report
        }
        PhoneState.ABANDON -> row.nextAttemptAt?.takeIf { it > now }?.let { PhoneStep.Wait(it) } ?: PhoneStep.Abandon
        else -> PhoneStep.Done
    }

    /**
     * Server items of this phone waiting for it (`GET /v1/compress/items?mine=true&state=ready,replacing`) against the
     * journal: a `ready` item is a `compress` row (the output over the original), a `replacing` one a `restore` row (or
     * `compress` for a restore again). A settled row of the same item starts a new cycle (its check sees a target
     * already in place and only reports again); an unsettled one of the other direction is replaced unless its write
     * started (it is finished first). [complete]: every page was read, so rows the server no longer lists that wrote
     * nothing yet are dropped (cancelled there, or settled by a backup report). [absolute] maps a volume and path to
     * the phone's file (null: not a volume of this phone).
     */
    fun reconcile(
        items: List<CompressItem>,
        rows: List<CompressPhoneEntity>,
        complete: Boolean,
        now: Long,
        absolute: (volume: String, path: String) -> String?,
    ): PhoneReconcile {
        val byId = rows.associateBy { it.id }
        val put = ArrayList<CompressPhoneEntity>()
        val drop = ArrayList<CompressPhoneEntity>()
        val listed = HashSet<String>()
        for (item in items) {
            val fresh = rowOf(item, now, absolute) ?: continue
            listed += item.id
            val old = byId[item.id]
            when {
                old == null -> put += fresh
                old.state == PhoneState.ABANDONED && old.direction == fresh.direction -> Unit // told already; the server settles it
                old.state in PhoneState.SETTLED -> put += fresh
                old.direction == fresh.direction -> Unit
                old.writeStarted -> Unit // finish the write first; the next pass sees the new direction
                else -> {
                    drop += old
                    put += fresh
                }
            }
        }
        if (complete) {
            for (old in rows) {
                if (old.id in listed) continue
                if (old.state in PhoneState.BEFORE_WRITE && !old.writeStarted) drop += old
                if (old.state in PhoneState.SETTLED && now - old.updatedAt > KEEP_SETTLED_MS) drop += old
            }
        }
        return PhoneReconcile(put, drop.distinctBy { it.id })
    }

    /** A journal row for a server item (null when it names no phone file this phone has, or lacks a hash). */
    fun rowOf(item: CompressItem, now: Long, absolute: (String, String) -> String?): CompressPhoneEntity? {
        val volume = item.volume ?: return null
        val path = item.path ?: return null
        val file = absolute(volume, path) ?: return null
        val direction = when (item.state) {
            Compress.STATE_READY -> Compress.DIRECTION_COMPRESS
            Compress.STATE_REPLACING -> item.direction ?: Compress.DIRECTION_RESTORE
            else -> return null
        }
        val outFp = item.outFingerprint ?: return null
        val outSha = item.outSha256 ?: return null
        val outSize = item.outSize ?: return null
        if (!Fingerprint.isValid(outFp) || !Fingerprint.isValid(item.fingerprint)) return null
        val compress = direction == Compress.DIRECTION_COMPRESS
        val originalSha = item.sha256
        if (!compress && originalSha == null) return null
        return CompressPhoneEntity(
            id = item.id,
            jobId = item.jobId,
            itemId = item.itemId,
            rootId = item.rootId.orEmpty(),
            volume = volume,
            path = path,
            absolute = file,
            direction = direction,
            sourceFingerprint = if (compress) item.fingerprint else outFp,
            sourceSha256 = if (compress) originalSha else outSha,
            sourceSize = if (compress) item.size else outSize,
            targetFingerprint = if (compress) outFp else item.fingerprint,
            targetSha256 = if (compress) outSha else originalSha!!,
            targetSize = if (compress) outSize else item.size,
            originalMtime = item.modifiedAt,
            state = PhoneState.DOWNLOADING,
            report = if (compress) PhoneReport.REPLACED else PhoneReport.RESTORED,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Just before a write: the phone file must still be the source (its size, its mtime within [MTIME_SLACK_MS] of
     * the one the server knows, its `v1:` fingerprint), and its MediaStore row must exist and still name that path: a
     * file MediaStore does not know is never written (the consent rule needs a row, Amendment (A2) "Review of #81"),
     * and an unreadable MediaStore waits. A file that is already the target was written by an earlier run whose answer
     * was lost.
     */
    fun check(row: CompressPhoneEntity, now: PhoneFileNow): PhoneCheck {
        if (!now.exists) return PhoneCheck.Abandon(PhoneError.GONE)
        if (now.size == row.targetSize && now.fingerprint == row.targetFingerprint) return PhoneCheck.AlreadyTarget
        if (!now.mediaReadable) return PhoneCheck.Wait
        if (now.mediaPath == null || now.mediaPath != row.absolute) return PhoneCheck.Abandon(PhoneError.CHANGED)
        if (now.size != row.sourceSize || now.fingerprint != row.sourceFingerprint) return PhoneCheck.Abandon(PhoneError.CHANGED)
        val known = row.originalMtime
        if (known != null && kotlin.math.abs(now.modified - known) > MTIME_SLACK_MS) return PhoneCheck.Abandon(PhoneError.CHANGED)
        return PhoneCheck.Ok
    }

    /**
     * A part that failed verification: downloaded once more from the start; a second failure abandons the item
     * (`verify`), unless the write already started (the phone file is neither version: downloaded again, later).
     */
    fun verifyFailed(row: CompressPhoneEntity, now: Long): CompressPhoneEntity {
        val failures = row.verifyFailures + 1
        return when {
            row.writeStarted -> row.copy(state = PhoneState.DOWNLOADING, offset = 0, etag = null, verifyFailures = failures, attempts = row.attempts + 1,
                nextAttemptAt = now + backoffMs(row.attempts + 1), error = PhoneError.VERIFY, updatedAt = now)
            failures >= MAX_VERIFY_FAILURES -> abandon(row.copy(verifyFailures = failures), PhoneError.VERIFY, now)
            else -> row.copy(state = PhoneState.DOWNLOADING, offset = 0, etag = null, verifyFailures = failures, error = PhoneError.VERIFY, updatedAt = now)
        }
    }

    /**
     * A write whose read-back did not match: written once more from the part; a second failure abandons the item
     * (`verify`) and then repairs the phone file from the server ([repair]). A repair (nothing to abandon: the phone
     * file is neither version) keeps trying, but from its [MAX_REWRITES]th failure on only after a back-off.
     */
    fun readBackFailed(row: CompressPhoneEntity, now: Long): CompressPhoneEntity {
        val rewrites = row.rewrites + 1
        return when {
            rewrites < MAX_REWRITES -> row.copy(state = PhoneState.WRITING, rewrites = rewrites, error = PhoneError.VERIFY, updatedAt = now)
            row.report != PhoneReport.NONE -> abandon(row.copy(rewrites = rewrites), PhoneError.VERIFY, now)
            else -> retryLater(row.copy(state = PhoneState.WRITING, rewrites = rewrites), PhoneError.VERIFY, now)
        }
    }

    /** The row stays in its state with [error] and tries again after the back-off ([backoffMs], at most 6 hours). */
    fun retryLater(row: CompressPhoneEntity, error: String, now: Long): CompressPhoneEntity =
        row.copy(error = error, attempts = row.attempts + 1, nextAttemptAt = now + backoffMs(row.attempts + 1), updatedAt = now)

    /** Room a write in place needs beyond what the file holds: it is truncated, then grows to the target size. */
    const val WRITE_MARGIN = 64L shl 20

    /**
     * Whether a write of [row] fits now: the file at [current] bytes becomes [CompressPhoneEntity.targetSize], so a
     * restore (the original over the smaller output) needs `target − current` more, plus [WRITE_MARGIN]. [free]
     * unknown (null) does not block. Checked before the truncation, so a short phone leaves the file untouched.
     */
    fun writeFits(row: CompressPhoneEntity, current: Long, free: Long?): Boolean =
        free == null || free >= row.targetSize - current.coerceAtLeast(0) + WRITE_MARGIN

    fun abandon(row: CompressPhoneEntity, reason: String, now: Long): CompressPhoneEntity =
        row.copy(state = PhoneState.ABANDON, abandonReason = reason, error = reason, nextAttemptAt = null, updatedAt = now)

    /**
     * After an abandon was sent: settled, unless the phone file was already written (a failed read-back): then the
     * source is downloaded again (by its fingerprint, from the server's store) and written back as a local repair.
     */
    fun afterAbandon(row: CompressPhoneEntity, now: Long): CompressPhoneEntity {
        if (!row.writeStarted || row.sourceSha256 == null) return row.copy(state = PhoneState.ABANDONED, nextAttemptAt = null, updatedAt = now)
        return repair(row, now)
    }

    /** The source written back over a phone file a failed write left broken: nothing is reported for it. */
    fun repair(row: CompressPhoneEntity, now: Long): CompressPhoneEntity = row.copy(
        sourceFingerprint = row.targetFingerprint,
        sourceSha256 = row.targetSha256,
        sourceSize = row.targetSize,
        targetFingerprint = row.sourceFingerprint,
        targetSha256 = row.sourceSha256!!,
        targetSize = row.sourceSize,
        state = PhoneState.DOWNLOADING,
        offset = 0,
        etag = null,
        verifyFailures = 0,
        rewrites = 0,
        report = PhoneReport.NONE,
        nextAttemptAt = null,
        updatedAt = now,
    )

    /** Whether a row's target is downloaded from the output route (else from `GET /v1/originals/{fingerprint}`). */
    fun fromOutputRoute(row: CompressPhoneEntity): Boolean = row.direction == Compress.DIRECTION_COMPRESS && row.report == PhoneReport.REPLACED

    /** Rows that need the owner's consent, in system requests of at most [MAX_CONSENT]. */
    fun consentBatches(rows: List<CompressPhoneEntity>, max: Int = MAX_CONSENT): List<List<CompressPhoneEntity>> = rows.chunked(max.coerceAtLeast(1))
}

/**
 * Settings › Compress videos' "This phone" (ADR 0025 A2): rows waiting for the owner's consent, rows in progress, and
 * the recent settled ones (replaced, restored, given up), newest first.
 */
data class PhoneOverview(
    val waiting: List<CompressPhoneEntity>,
    val active: List<CompressPhoneEntity>,
    val recent: List<CompressPhoneEntity>,
) {
    val isEmpty: Boolean get() = waiting.isEmpty() && active.isEmpty() && recent.isEmpty()

    /** Bytes the phone saved with the replaces reported so far (original − output). */
    val saved: Long get() = recent.filter { it.state == PhoneState.REPORTED && it.report == PhoneReport.REPLACED }.sumOf { (it.sourceSize - it.targetSize).coerceAtLeast(0) }

    companion object {
        const val RECENT = 20

        fun of(rows: List<CompressPhoneEntity>): PhoneOverview {
            val waiting = rows.filter { it.state == PhoneState.VERIFIED && it.error == PhoneError.PERMISSION && !it.writeStarted }
            val active = rows.filter { it.state !in PhoneState.SETTLED && it !in waiting }
            val recent = rows.filter { it.state in PhoneState.SETTLED }.sortedByDescending { it.updatedAt }.take(RECENT)
            return PhoneOverview(waiting, active, recent)
        }
    }
}
