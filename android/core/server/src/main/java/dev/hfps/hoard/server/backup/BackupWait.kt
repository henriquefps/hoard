package dev.hfps.hoard.server.backup

import dev.hfps.hoard.server.api.ServerException
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.db.BackupFileEntity
import dev.hfps.hoard.server.db.FreedEntity
import kotlinx.serialization.Serializable

/**
 * The backup waits for room on the server (ADR 0024 Amendment (2.9.1)): hoard-server 0.14.0 answers an upload that
 * does not fit its backup volume `507 storage_full` with `details.free` and `details.needed`. A wait is pass-wide, not
 * a failure of the file: the pass stops at the first one with the rows left queued (no attempt counted), the next
 * passes skip uploads until [until] (Back up now tries at once), and the first upload that goes clears it.
 */
@Serializable
data class BackupWait(
    /** When the first `507` of this wait came (Unix ms). */
    val since: Long,
    /** Uploads are tried again from then on (Unix ms). */
    val until: Long,
    /** `507`s in a row (the back-off grows with them). */
    val repeats: Int = 1,
    /** The server's free bytes on its backup volume, as of the last `507` (null: not said). */
    val free: Long? = null,
    /** What the refused upload needed (null: not said). */
    val needed: Long? = null,
) {
    fun due(now: Long): Boolean = now >= until

    fun encode(): String = ServerJson.encodeToString(serializer(), this)

    companion object {
        /** The first wait: 30 minutes; it doubles with each `507` in a row, at most 6 hours. */
        const val FIRST_MS = 30 * 60_000L
        const val MAX_MS = 6 * 3_600_000L

        fun decode(json: String?): BackupWait? = json?.let { runCatching { ServerJson.decodeFromString(serializer(), it) }.getOrNull() }

        /** A `507` (hoard-server 0.14.0 `storage_full`; an older server's bare `507` too). */
        fun isFull(e: ServerException): Boolean = e.status == 507 || e.code == "storage_full" || e.reason == "storage_full"

        /** The back-off after [repeats] `507`s in a row, and at least the server's `Retry-After` (capped too). */
        fun backoffMs(repeats: Int, retryAfterSeconds: Long?): Long {
            val ours = minOf(FIRST_MS shl (repeats - 1).coerceIn(0, 10), MAX_MS)
            val theirs = ((retryAfterSeconds ?: 0L) * 1000).coerceIn(0, MAX_MS)
            return maxOf(ours, theirs)
        }

        /** The wait after a `507` at [now], following [previous] (null: the first). */
        fun after(previous: BackupWait?, e: ServerException, now: Long): BackupWait {
            val repeats = (previous?.repeats ?: 0) + 1
            return BackupWait(
                since = previous?.since ?: now,
                until = now + backoffMs(repeats, e.retryAfterSeconds),
                repeats = repeats,
                free = e.detailLong("free") ?: previous?.free,
                needed = e.detailLong("needed") ?: previous?.needed,
            )
        }
    }
}

/**
 * The backup starts over after the server lost this phone's backup (ADR 0024 Amendment (2.9.1)): the admin purged it
 * (hoard-server 0.14.0 `POST /admin/api/backup/{physicalId}/purge`), so `GET /v1/backup/roots` no longer lists the
 * phone's roots. Pure, JVM-tested.
 */
object BackupRestart {
    /** Local roots (not paused) whose id the server does not list: gone, made again with `POST`. */
    fun gone(local: List<dev.hfps.hoard.server.db.BackupRootEntity>, listed: Set<String>): List<dev.hfps.hoard.server.db.BackupRootEntity> =
        local.filter { !it.paused && it.id !in listed }

    /**
     * A row of a root made again: back to identified and not reported (`queued`; `waiting` without a valid
     * fingerprint; `changed` stays, it is identified again), fingerprint and SHA-256 kept, the server's answers and
     * the upload dropped, so the same pass reports it (`missing`) and uploads it again.
     */
    fun reset(row: BackupFileEntity, now: Long): BackupFileEntity = row.copy(
        state = when {
            row.state == BackupState.CHANGED -> BackupState.CHANGED
            BackupPlan.identified(row) -> BackupState.QUEUED
            else -> BackupState.WAITING
        },
        reported = null,
        serverState = null,
        itemId = null,
        verifiedAt = null,
        uploadId = null,
        uploadOffset = 0,
        attempts = 0,
        nextAttemptAt = null,
        error = null,
        updatedAt = now,
    )

    /** Free up's history of files under [folder] (absolute, no trailing '/'): the server has no copy of them any more. */
    fun freedUnder(freed: List<FreedEntity>, folder: String): List<FreedEntity> {
        val base = folder.trimEnd('/')
        return freed.filter { it.path == base || it.path.startsWith("$base/") }
    }

    /** Where a root is on the phone, from one of its rows (`absolute` ends with the volume-relative `path`), else null. */
    fun folderFromRows(rootPath: String, rows: List<BackupFileEntity>): String? {
        val row = rows.firstOrNull { it.absolute.endsWith("/" + it.path) } ?: return null
        val volume = row.absolute.removeSuffix("/" + row.path)
        return if (rootPath.isEmpty()) volume else "$volume/$rootPath"
    }
}
