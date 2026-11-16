package dev.hfps.hoard.server.backup

import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerException
import dev.hfps.hoard.server.api.UploadCreate
import kotlinx.coroutines.delay
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** What an upload came to. */
sealed interface UploadOutcome {
    /** The server holds the bytes (sent now, or `exists`). */
    data object Done : UploadOutcome

    /** Another device is sending the same bytes (`in_progress`, ADR 0024 `wait`): ask again after [retryAfterSeconds]. */
    data class InProgress(val retryAfterSeconds: Long) : UploadOutcome

    /** The bytes are not what was hashed (`checksum_mismatch`, or the file ended early): hash it again. */
    data object Changed : UploadOutcome

    /** The run was asked to stop; the upload id and offset are saved, the next run resumes. */
    data object Stopped : UploadOutcome
}

/** The file an upload reads, from an offset (opened per chunk, so nothing stays open between runs). */
fun interface UploadSource {
    fun open(offset: Long): InputStream
}

/** The file ended before its size: it changed since it was hashed. */
class FileChangedException : IOException("the file changed")

/**
 * Chunked, resumable uploads of a phone file (ADR 0024 "Modules and data", after Reveal's `BlobUploader`):
 * `POST /v1/uploads` with `wait: true` and `claim: false` answers `exists` (nothing to send), `in_progress` (another
 * device is sending it), `created` or `resume`; chunks of the server's `chunkSize` follow, the upload id and offset saved
 * through [save] after each, so a process killed mid-way resumes from the stored upload id (`HEAD` gives the server's
 * offset). A broken chunk asks `HEAD` where to go on; `checksum_mismatch` is [UploadOutcome.Changed]. A refused key
 * (`401`) and other errors are thrown.
 */
class BackupUploader(
    private val api: ServerApi,
    /** The server's chunk size (`GET /v1/server` `limits.chunkSize`). */
    private val chunkSize: Long = DEFAULT_CHUNK,
) {
    /**
     * Uploads [size] bytes whose SHA-256 is [sha256] and fingerprint [fingerprint], resuming [uploadId] at the server's
     * offset when it still knows it. [save] stores the upload id and offset (null, 0 = none); [progress] gets the bytes
     * sent so far.
     */
    suspend fun upload(
        sha256: String,
        size: Long,
        fingerprint: String,
        uploadId: String?,
        source: UploadSource,
        save: suspend (uploadId: String?, offset: Long) -> Unit,
        progress: (Long) -> Unit = {},
        stopped: () -> Boolean = { false },
    ): UploadOutcome {
        var id = uploadId
        var offset = 0L
        var chunk = chunkSize.coerceIn(MIN_CHUNK, DEFAULT_CHUNK)
        if (id != null) {
            val known = api.uploadOffset(id)
            if (known == null) {
                id = null
                save(null, 0)
            } else {
                offset = known
                save(id, offset)
            }
        }
        var restarts = 0
        var failures = 0
        while (true) {
            if (id == null) {
                val created = create(sha256, size, fingerprint) ?: return UploadOutcome.Changed
                when (created.status) {
                    STATUS_EXISTS -> {
                        save(null, 0)
                        return UploadOutcome.Done
                    }
                    STATUS_IN_PROGRESS -> return UploadOutcome.InProgress(created.retryAfter?.takeIf { it > 0 } ?: BackupPlan.DEFAULT_RETRY_S)
                }
                id = created.uploadId ?: throw IOException("no upload id")
                offset = created.offset
                created.chunkSize?.takeIf { it > 0 }?.let { chunk = it.coerceIn(MIN_CHUNK, DEFAULT_CHUNK) }
                save(id, offset)
            }
            if (offset >= size) {
                // Everything arrived but the completion was not seen: the next create answers `exists`.
                id = null
                if (++restarts > MAX_RESTARTS) throw IOException("the upload did not complete")
                continue
            }
            if (stopped()) return UploadOutcome.Stopped
            val length = minOf(chunk, size - offset).toInt()
            val start = offset
            val result = try {
                source.open(start).use { input -> api.patchUpload(id, start, length) { out -> copy(input, out, length) } }
            } catch (e: FileChangedException) {
                runCatching { api.deleteUpload(id) }
                save(null, 0)
                return UploadOutcome.Changed
            } catch (e: ServerException) {
                if (e.unauthorized) throw e
                when {
                    e.code == "checksum_mismatch" -> {
                        runCatching { api.deleteUpload(id) }
                        save(null, 0)
                        return UploadOutcome.Changed
                    }
                    e.status == 404 -> {
                        // Expired or swept meanwhile: start over with a new upload.
                        save(null, 0)
                        id = null
                        if (++restarts > MAX_RESTARTS) throw e
                        continue
                    }
                    e.detailLong("offset") != null -> {
                        offset = e.detailLong("offset")!!
                        // A smaller limit than announced: halve the chunks.
                        if (e.code == "too_large") chunk = (chunk / 2).coerceAtLeast(MIN_CHUNK)
                        if (++failures > MAX_FAILURES * 4) throw e
                    }
                    e.code == "upload_busy" || e.status == 429 || e.status >= 500 -> {
                        if (++failures > MAX_FAILURES) throw e
                        delay(((e.retryAfterSeconds ?: 2L) * 1000).coerceAtMost(30_000))
                        offset = api.uploadOffset(id) ?: run { id = null; 0L }
                    }
                    else -> throw e
                }
                save(id, offset)
                continue
            } catch (e: IOException) {
                // The connection broke mid-chunk: the bytes that arrived are kept; ask where to go on.
                if (++failures > MAX_FAILURES) throw e
                delay(1000L * failures)
                offset = api.uploadOffset(id) ?: run { id = null; 0L }
                save(id, offset)
                continue
            }
            offset = result.offset
            failures = 0
            if (result.complete) {
                save(null, 0)
                progress(size)
                return UploadOutcome.Done
            }
            save(id, offset)
            progress(offset)
        }
    }

    /** `POST /v1/uploads`; null on `checksum_mismatch` (the store has these bytes with another size or fingerprint). */
    private fun create(sha256: String, size: Long, fingerprint: String): dev.hfps.hoard.server.api.UploadState? {
        val request = UploadCreate(sha256 = sha256, size = size, kind = "original", fingerprint = fingerprint, wait = true, claim = false)
        return try {
            api.createUpload(request)
        } catch (e: ServerException) {
            when {
                e.code == "checksum_mismatch" -> null
                // An unfinished upload of these bytes with other details (an older run's): abandoned, then again.
                e.status == 422 && e.detailString("uploadId") != null -> {
                    api.deleteUpload(e.detailString("uploadId")!!)
                    api.createUpload(request)
                }
                else -> throw e
            }
        }
    }

    companion object {
        const val DEFAULT_CHUNK = 8L shl 20
        private const val MIN_CHUNK = 64L shl 10
        private const val MAX_FAILURES = 5
        private const val MAX_RESTARTS = 2
        private const val STATUS_EXISTS = "exists"
        private const val STATUS_IN_PROGRESS = "in_progress"
        private const val BUFFER = 64 * 1024

        private fun copy(input: InputStream, out: OutputStream, length: Int) {
            val buffer = ByteArray(BUFFER)
            var left = length
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size, left))
                if (n < 0) throw FileChangedException()
                out.write(buffer, 0, n)
                left -= n
            }
        }
    }
}
