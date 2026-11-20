package dev.hfps.hoard.server.compress

import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerException
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/** What a download came to. */
sealed interface DownloadOutcome {
    /** The part holds [size] bytes (its content is checked by the caller). */
    data object Done : DownloadOutcome

    /** The server no longer serves it (`404`: the item was cancelled there, or the original purged). */
    data object NotFound : DownloadOutcome

    /** The run was asked to stop; the offset is saved, the next run resumes. */
    data object Stopped : DownloadOutcome
}

/**
 * Range downloads with resume (ADR 0025 "Modules and data", after [dev.hfps.hoard.server.backup.BackupUploader]): the
 * part file is appended from the saved offset (`Range: bytes=<offset>-`), with `If-Range` on the ETag it was started
 * under, so a server whose file changed sends the whole file again (`200`: the part starts over). The offset is saved
 * through `save` after each synced chunk; a body that ends before its length is a dropped connection (the bytes that
 * arrived are kept, the download goes on after a back-off). `404` is [DownloadOutcome.NotFound]; a refused key and
 * other errors are thrown.
 */
class CompressDownloader(
    private val api: ServerApi,
    private val chunk: Long = CHUNK,
    private val maxFailures: Int = MAX_FAILURES,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Downloads [path] (a ranged route) into [part] until it holds [size] bytes. [offset] and [etag] are what an
     * earlier run saved; [save] stores the new ones after each chunk; [progress] gets the bytes held so far.
     */
    suspend fun download(
        path: String,
        part: File,
        size: Long,
        offset: Long,
        etag: String?,
        save: suspend (offset: Long, etag: String?) -> Unit,
        progress: (Long) -> Unit = {},
        stopped: () -> Boolean = { false },
    ): DownloadOutcome {
        part.parentFile?.mkdirs()
        var off = settle(part, offset)
        var tag = etag.takeIf { off > 0 }
        if (off != offset) save(off, tag)
        var failures = 0
        var restarts = 0
        while (true) {
            if (off == size && size > 0) return DownloadOutcome.Done
            if (off > size) {
                // More bytes than the file has: not this file's; start over.
                off = restart(part)
                tag = null
                save(off, tag)
                if (++restarts > MAX_RESTARTS) throw IOException("the download does not match its size")
                continue
            }
            if (stopped()) return DownloadOutcome.Stopped
            try {
                api.download(path, off, tag).use { d ->
                    when (d.status) {
                        416 -> {
                            // Nothing after our offset: complete when the server's size is ours, else start over.
                            if (d.total == off && off == size) return DownloadOutcome.Done
                            off = restart(part)
                            tag = null
                            save(off, tag)
                            if (++restarts > MAX_RESTARTS) throw IOException("the server refuses the range")
                            return@use
                        }
                        200 -> {
                            // The whole file: the range was ignored, or If-Range found another version.
                            if (off > 0) off = restart(part)
                        }
                        206 -> if (d.start != off) {
                            off = restart(part)
                            tag = null
                            save(off, tag)
                            if (++restarts > MAX_RESTARTS) throw IOException("the server answered another range")
                            return@use
                        }
                    }
                    tag = d.etag ?: tag
                    val expected = if (d.length >= 0) d.length else size - off
                    var received = 0L
                    FileOutputStream(part, true).use { out ->
                        val buffer = ByteArray(BUFFER)
                        var sinceSync = 0L
                        while (received < expected) {
                            if (stopped()) {
                                out.flush()
                                out.fd.sync()
                                save(off, tag)
                                return DownloadOutcome.Stopped
                            }
                            val n = d.stream.read(buffer, 0, minOf(buffer.size.toLong(), expected - received).toInt())
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            received += n
                            off += n
                            sinceSync += n
                            if (sinceSync >= chunk) {
                                out.flush()
                                out.fd.sync()
                                save(off, tag)
                                progress(off)
                                sinceSync = 0
                            }
                        }
                        out.flush()
                        out.fd.sync()
                    }
                    save(off, tag)
                    progress(off)
                    if (received < expected) throw ShortBodyException(received, expected)
                    failures = 0
                }
            } catch (e: ServerException) {
                if (e.status == 404) return DownloadOutcome.NotFound
                if (e.unauthorized) throw e
                if (e.status == 429 || e.status >= 500) {
                    if (++failures > maxFailures) throw e
                    sleep(((e.retryAfterSeconds ?: 2L) * 1000).coerceAtMost(30_000))
                    continue
                }
                throw e
            } catch (e: IOException) {
                // A dropped connection (or a short body): what arrived is kept and synced; go on from there.
                if (++failures > maxFailures) throw e
                off = settle(part, off)
                save(off, tag)
                sleep(1000L * failures)
            }
        }
    }

    /** The bytes really in [part], at most [saved] (a write after the last saved offset is dropped). */
    private fun settle(part: File, saved: Long): Long {
        if (!part.exists()) return 0
        val length = part.length()
        if (length > saved) RandomAccessFile(part, "rw").use { it.setLength(saved) }
        return minOf(length, saved)
    }

    private fun restart(part: File): Long {
        if (part.exists()) RandomAccessFile(part, "rw").use { it.setLength(0) }
        return 0
    }

    /** The body ended before its `Content-Length`: a dropped connection. */
    class ShortBodyException(received: Long, expected: Long) : IOException("the body ended after $received of $expected bytes")

    companion object {
        const val CHUNK = 4L shl 20
        const val MAX_FAILURES = 5
        private const val MAX_RESTARTS = 2
        private const val BUFFER = 64 * 1024
    }
}
