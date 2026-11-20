package dev.hfps.hoard.server.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Video compression on the phone (ADR 0025 "Phone replace flow (Hoard)", phase A2, `gallery.db` 6): the jobs Hoard
 * started for this phone and the redo journal of each phone file being replaced (or restored) in place.
 */

/** A compression job this phone started (any target), and its last status as the server answered it (JSON). */
@Entity(tableName = "compress_jobs")
data class CompressJobEntity(
    /** The server's job id. */
    @PrimaryKey val id: String,
    val target: String,
    val profile: String,
    @ColumnInfo(defaultValue = "0") val auto: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** The job's last `GET /v1/compress/jobs/{id}` answer, raw (null before the first read). */
    val status: String? = null,
)

/**
 * One phone file a compression replaces in place ([direction] `compress`: the output over the original) or puts back
 * (`restore`: the original over the output), as a redo journal: each [state] is written before its step runs, so a
 * process killed anywhere resumes at that step ([dev.hfps.hoard.server.compress.CompressPlan.resume]). The source is
 * the file the phone has before the write, the target what it has after.
 */
@Entity(tableName = "compress_phone", indices = [Index("state"), Index("absolute")])
data class CompressPhoneEntity(
    /** The server's compression item id (`compress_items.id`). */
    @PrimaryKey val id: String,
    @ColumnInfo(name = "job_id") val jobId: String = "",
    /** The gallery item the compression was queued for (the original's). */
    @ColumnInfo(name = "item_id") val itemId: String = "",
    /** The `device_media` row of the phone file (videos above `DeviceIndexer.VIDEO_ID_BASE`); null when not indexed. */
    @ColumnInfo(name = "media_id") val mediaId: Long? = null,
    @ColumnInfo(name = "root_id") val rootId: String = "",
    /** `primary` or the volume UUID, and the path on it (as the backup reports it). */
    val volume: String,
    val path: String,
    /** Where the file is on the phone. */
    val absolute: String,
    /** `compress` or `restore`. */
    val direction: String,
    @ColumnInfo(name = "source_fingerprint") val sourceFingerprint: String,
    @ColumnInfo(name = "source_sha256") val sourceSha256: String?,
    @ColumnInfo(name = "source_size") val sourceSize: Long,
    @ColumnInfo(name = "target_fingerprint") val targetFingerprint: String,
    @ColumnInfo(name = "target_sha256") val targetSha256: String,
    @ColumnInfo(name = "target_size") val targetSize: Long,
    /** The phone file's mtime before the write (ms), set back after it; the server's mirror mtime until checked. */
    @ColumnInfo(name = "original_mtime") val originalMtime: Long? = null,
    /** MediaStore `DATE_TAKEN` before the write (ms). */
    @ColumnInfo(name = "date_taken") val dateTaken: Long? = null,
    val state: String,
    /** The part file (app-specific storage) the target is downloaded into. */
    val part: String? = null,
    /** Bytes of [part] downloaded and synced. */
    @ColumnInfo(name = "part_offset", defaultValue = "0") val offset: Long = 0,
    /** The `ETag` the part was downloaded under (`If-Range` when resuming). */
    val etag: String? = null,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long? = null,
    /** Why the row waits or what it gave up on (`permission`, `changed`, `gone`, `verify`, `date_taken`, …). */
    val error: String? = null,
    /** Downloads whose bytes failed the check (a second failure abandons the item). */
    @ColumnInfo(name = "verify_failures", defaultValue = "0") val verifyFailures: Int = 0,
    /** Writes whose read-back failed (a second failure abandons the item). */
    @ColumnInfo(defaultValue = "0") val rewrites: Int = 0,
    /** The first byte of the phone file was written: the source is gone from the phone, never checked again. */
    @ColumnInfo(name = "write_started", defaultValue = "0") val writeStarted: Boolean = false,
    /** `replaced` / `restored`: the route reported once written; `none`: a local repair, nothing to report. */
    val report: String,
    /** The reason an `abandon` sends (state `abandon`). */
    @ColumnInfo(name = "abandon_reason") val abandonReason: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
)

/** Rows of the journal by state (Settings › Compress videos' phone section). */
data class CompressPhoneCount(val state: String, val error: String?, val files: Int, val bytes: Long)

@Dao
interface CompressDao {
    @Query("SELECT * FROM compress_jobs ORDER BY created_at DESC")
    suspend fun jobs(): List<CompressJobEntity>

    @Upsert
    suspend fun putJob(job: CompressJobEntity)

    @Query("UPDATE compress_jobs SET status = :status WHERE id = :id")
    suspend fun setJobStatus(id: String, status: String?)

    @Query("SELECT * FROM compress_phone ORDER BY created_at")
    suspend fun phone(): List<CompressPhoneEntity>

    @Query("SELECT * FROM compress_phone ORDER BY created_at")
    fun observePhone(): Flow<List<CompressPhoneEntity>>

    @Query("SELECT * FROM compress_phone WHERE id = :id")
    suspend fun phone(id: String): CompressPhoneEntity?

    @Upsert
    suspend fun putPhone(row: CompressPhoneEntity)

    @Query("DELETE FROM compress_phone WHERE id = :id")
    suspend fun deletePhone(id: String)

    /** Phone paths with a replace that has not settled (free up leaves their shots on the phone, ADR 0025). */
    @Query("SELECT absolute FROM compress_phone WHERE state NOT IN ('reported', 'abandoned')")
    suspend fun unsettledPaths(): List<String>

    /**
     * Phone paths a replace has started writing and not finished (the file is neither version until it is read back):
     * the backup neither identifies, reports nor uploads them (Amendment (A2), "Review of #81").
     */
    @Query("SELECT absolute FROM compress_phone WHERE write_started = 1 AND state NOT IN ('written', 'reported', 'abandoned')")
    suspend fun writingPaths(): List<String>

    @Query("SELECT state, error, COUNT(*) AS files, COALESCE(SUM(target_size), 0) AS bytes FROM compress_phone GROUP BY state, error")
    fun observeCounts(): Flow<List<CompressPhoneCount>>

    @Query("DELETE FROM compress_phone")
    suspend fun clearPhone()

    /** Rows that wrote nothing yet (unpairing: the server they came from is forgotten). */
    @Query("DELETE FROM compress_phone WHERE write_started = 0")
    suspend fun clearUnstarted()

    @Query("DELETE FROM compress_jobs")
    suspend fun clearJobs()
}
