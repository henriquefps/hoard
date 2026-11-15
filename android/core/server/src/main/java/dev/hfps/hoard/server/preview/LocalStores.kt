package dev.hfps.hoard.server.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import dev.hfps.hoard.server.api.ServerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * One mutex per key, dropped when its last user leaves (refcounted): every caller of a key waits on the same mutex,
 * however many come and go (a map entry removed while others still waited would let a newcomer make a second one).
 */
class KeyedLocks {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val entries = HashMap<String, Entry>()

    suspend fun <T> withLock(key: String, block: suspend () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }

    /** Keys held or waited on now (tests). */
    fun size(): Int = synchronized(entries) { entries.size }
}

/** A unique temporary file next to [target] (`<name>.<random>.part`), renamed over it when complete. */
internal fun partFileFor(target: File): File {
    target.parentFile?.mkdirs()
    return File.createTempFile(target.name + ".", ".part", target.parentFile)
}

/** A folder of files bounded by [maxBytes], dropped least recently used first (file mtime = last use). */
open class BoundedDir(val dir: File, @Volatile var maxBytes: Long) {
    private val used = AtomicLong(-1)

    /** Total size; walks the folder once (never at start-up: only when asked or when a file is added). */
    fun bytes(): Long {
        val v = used.get()
        if (v >= 0) return v
        val total = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        used.set(total)
        return total
    }

    fun count(): Int = dir.walkTopDown().count { it.isFile && !it.name.endsWith(".part") }

    fun clear() {
        dir.deleteRecursively()
        used.set(0)
    }

    protected fun touch(file: File): File = file.also { it.setLastModified(System.currentTimeMillis()) }

    protected fun added(n: Long) {
        if (used.get() < 0) bytes() else used.addAndGet(n)
        if (used.get() > maxBytes) trim()
    }

    /** Drops the least recently used files down to 90 % of [maxBytes]. */
    @Synchronized
    fun trim() {
        val files = dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.sortedBy { it.lastModified() }.toList()
        var total = files.sumOf { it.length() }
        val goal = maxBytes * 9 / 10
        for (f in files) {
            if (total <= goal) break
            val n = f.length()
            if (f.delete()) total -= n
        }
        used.set(total)
    }
}

/**
 * Originals of server items the owner opened (ADR 0021 "Kept originals"): downloaded whole in the background when a
 * shot is viewed, kept within a budget (Settings), least recently opened dropped first, so a shot opened again reads
 * from the phone (100 %, RAW decode, analysis) without the network.
 */
class OriginalCache(dir: File, maxBytes: Long, private val api: suspend () -> ServerApi?) : BoundedDir(dir, maxBytes) {
    val locks = KeyedLocks()

    fun file(fingerprint: String): File = File(dir, fingerprint.removePrefix("v1:").take(64) + ".bin")

    fun cached(fingerprint: String, size: Long): File? = file(fingerprint).takeIf { it.isFile && it.length() == size }?.let(::touch)

    /** Downloads the whole original (4 MiB ranges) unless kept already; null when it cannot (offline, too big). */
    suspend fun keep(fingerprint: String, size: Long): File? {
        if (size <= 0 || size > maxBytes / 4) return null
        cached(fingerprint, size)?.let { return it }
        // One download per original at a time (a refcounted lock), each through its own temporary file.
        return locks.withLock(fingerprint) {
            cached(fingerprint, size) ?: withContext(Dispatchers.IO) {
                val server = api() ?: return@withContext null
                val target = file(fingerprint)
                val part = runCatching { partFileFor(target) }.getOrElse { return@withContext null }
                runCatching {
                    part.outputStream().use { out ->
                        val buffer = ByteArray(CHUNK)
                        var done = 0L
                        while (done < size) {
                            val want = minOf(CHUNK.toLong(), size - done).toInt()
                            val (got, _) = server.readOriginal(fingerprint, done, want, buffer)
                            if (got <= 0) error("short read at $done")
                            out.write(buffer, 0, got)
                            done += got
                        }
                    }
                    check(part.length() == size && part.renameTo(target))
                    added(size)
                    target
                }.getOrElse {
                    part.delete()
                    null
                }
            }
        }
    }

    companion object {
        private const val CHUNK = 4 shl 20
    }
}

/**
 * Small thumbnails of the whole server library kept for offline browsing (ADR 0021 "Offline thumbnails"): the
 * server's 512 px thumb (ADR 0015) scaled on the phone to a 256 px short edge, WebP q60 (≈ 10–15 KB), one per item.
 * Filled by a background job on unmetered networks only; never read in bulk (a cell checks its own file).
 */
class OfflineThumbs(val dir: File) {
    fun file(itemId: String): File = File(dir, "${itemId.take(2)}/$itemId.webp")

    fun has(itemId: String): Boolean = file(itemId).isFile

    fun bytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun count(): Int = dir.walkTopDown().count { it.isFile && it.name.endsWith(".webp") }

    fun clear() = dir.deleteRecursively()

    /** Stores [source] (a decoded server thumb file) scaled down; false when it is not an image. */
    fun store(itemId: String, source: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(source.absolutePath, it) }
        if (bounds.outWidth <= 0) return false
        val decoded = BitmapFactory.decodeFile(source.absolutePath) ?: return false
        val short = minOf(decoded.width, decoded.height)
        val scale = if (short > EDGE) EDGE.toFloat() / short else 1f
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
        val target = file(itemId)
        target.parentFile?.mkdirs()
        val part = partFileFor(target)
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        part.outputStream().use { scaled.compress(format, QUALITY, it) }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return part.renameTo(target)
    }

    companion object {
        const val EDGE = 256
        const val QUALITY = 60
    }
}
