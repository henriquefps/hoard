package dev.hfps.hoard.smb.handoff

import dev.hfps.hoard.smb.stream.StreamReader
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Small hand-off files (sidecars, small JPEGs: at most [maxFileBytes]) served from a private file copy instead of a
 * proxy descriptor (ADR 0010 "Bursts of opens"; pure apart from the folder, JVM-tested). A plain file descriptor
 * costs the system nothing: no appfuse mount, no vold, no thread of ours per read. The copy is keyed by the link's
 * id and the file's size and mtime as just stat'ed, so a changed file is fetched again; the folder is trimmed to
 * [maxTotalBytes], least recently used first.
 *
 * A copy that takes longer than [deadlineMs] is abandoned ([fetch] returns null) and the caller falls back to a proxy
 * descriptor: a slow link never holds a binder thread for long.
 */
class HandoffCache(
    private val dir: File,
    val maxFileBytes: Long = MAX_FILE_BYTES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val deadlineMs: Long = DEADLINE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val locks = ConcurrentHashMap<String, Any>()

    fun fits(size: Long) = size in 0..maxFileBytes

    /** The file for [id] at [size] / [modifiedMillis] when it is already copied (marked as just used), else null. */
    fun cached(id: String, size: Long, modifiedMillis: Long?): File? =
        fileOf(id, size, modifiedMillis).takeIf { it.isFile && it.length() == size }?.also { it.setLastModified(clock()) }

    /**
     * The copy for [id], fetched with [reader] when missing. Null when the copy is abandoned (past [deadlineMs]);
     * [IOException] when the read fails. Concurrent calls for one id fetch once.
     */
    fun fetch(id: String, size: Long, modifiedMillis: Long?, reader: () -> StreamReader): File? {
        require(fits(size))
        val lock = locks.computeIfAbsent(id) { Any() }
        synchronized(lock) {
            cached(id, size, modifiedMillis)?.let { return it }
            dir.mkdirs()
            val target = fileOf(id, size, modifiedMillis)
            val part = File(dir, ".${target.name}.part")
            val started = clock()
            var complete = false
            try {
                reader().use { source ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(CHUNK)
                        var offset = 0L
                        while (offset < size) {
                            if (clock() - started > deadlineMs) return null
                            val n = source.read(offset, minOf(CHUNK.toLong(), size - offset).toInt(), buffer)
                            if (n <= 0) throw IOException("ended at $offset of $size bytes")
                            out.write(buffer, 0, n)
                            offset += n
                        }
                    }
                }
                // Older copies of this link (another size / mtime) are stale now.
                dir.listFiles { f -> f.name.startsWith("$id-") && f.name != target.name }?.forEach { it.delete() }
                if (!part.renameTo(target)) throw IOException("could not keep the copy")
                target.setLastModified(clock())
                complete = true
            } finally {
                if (!complete) part.delete()
            }
            trim(keep = target)
            return target
        }
    }

    /** Forgets every copy ("Revoke all"). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Deletes the least recently used copies until the folder holds at most [maxTotalBytes]. */
    fun trim(keep: File? = null) {
        val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxTotalBytes) break
            if (file == keep) continue
            total -= file.length()
            file.delete()
        }
    }

    private fun fileOf(id: String, size: Long, modifiedMillis: Long?) = File(dir, "$id-$size-${modifiedMillis ?: 0}")

    companion object {
        /** Sidecars, small JPEGs and previews; RAW files go through a proxy descriptor. */
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
        const val DEADLINE_MS = 6_000L
        private const val CHUNK = 256 * 1024
    }
}
