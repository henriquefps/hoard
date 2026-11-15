package dev.hfps.hoard.server.preview

import dev.hfps.hoard.image.source.RandomAccessSource
import dev.hfps.hoard.server.api.PreviewResult
import dev.hfps.hoard.server.api.ServerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Which preview of an item (ADR 0015): the grid's thumb (WebP, ≤ 512 px) or the viewer's screen (JPEG, ≤ 2560 px). */
enum class PreviewSize(val wire: String, val format: String, val ext: String) {
    Thumb("thumb", "webp", "webp"),
    Screen("screen", "jpeg", "jpg"),
}

/**
 * The bounded on-device cache of server previews (ADR 0021): files under [dir], dropped oldest-used first beyond
 * [maxBytes]. A preview the server is still making (202) is asked again after its `Retry-After`, a few times.
 * [edit] = `primary` or a version id shows Reveal's edit (ADR 0020); [version] goes into the file name, so a changed
 * edit or item is fetched again.
 */
class PreviewCache(private val dir: File, @Volatile var maxBytes: Long, private val api: suspend () -> ServerApi?) {
    /**
     * Called, off the main thread, with every complete original thumb this cache hands out (downloaded now or already
     * cached; never an edit's, never a partial file): Hoard 2.2.2 keeps them in the offline thumbnails. A failure here
     * never fails the preview.
     */
    @Volatile var onOriginalThumb: (suspend (itemId: String, file: File) -> Unit)? = null

    val locks = KeyedLocks()
    private val used = AtomicLong(-1)

    fun file(itemId: String, size: PreviewSize, edit: String?, version: String): File =
        File(dir, "${itemId.take(2)}/$itemId-${size.wire}-${edit ?: "o"}-${version.filter { it.isLetterOrDigit() }.take(24)}.${size.ext}")

    /** The cached file, without a request (null when not cached). */
    fun cached(itemId: String, size: PreviewSize, edit: String?, version: String): File? =
        file(itemId, size, edit, version).takeIf { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }

    /** The preview file, downloaded when needed; null when the server has none (or is not reachable). */
    suspend fun get(itemId: String, size: PreviewSize, edit: String? = null, version: String = "0", waitPending: Int = 6): File? {
        val file = fetch(itemId, size, edit, version, waitPending)
        val hook = onOriginalThumb
        if (file != null && hook != null && size == PreviewSize.Thumb && edit == null) {
            try {
                withContext(Dispatchers.IO) { hook(itemId, file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The offline copy is a convenience.
            }
        }
        return file
    }

    private suspend fun fetch(itemId: String, size: PreviewSize, edit: String?, version: String, waitPending: Int): File? {
        cached(itemId, size, edit, version)?.let { return it }
        val target = file(itemId, size, edit, version)
        // One download per preview at a time (a refcounted lock); [ServerApi.downloadPreview] writes a unique temporary
        // file and renames it, so even two processes never share a half-written file.
        return locks.withLock(target.name) {
            cached(itemId, size, edit, version) ?: withContext(Dispatchers.IO) {
                val server = api() ?: return@withContext null
                var tries = 0
                while (true) {
                    when (val r = server.downloadPreview(server.previewPath(itemId, size.wire, size.format, edit = edit), target)) {
                        is PreviewResult.Ready -> {
                            added(r.file.length())
                            return@withContext r.file
                        }
                        is PreviewResult.Pending -> {
                            if (++tries > waitPending) return@withContext null
                            delay(r.retryAfterSeconds.coerceIn(1, 15) * 1000)
                        }
                        is PreviewResult.Unavailable -> return@withContext null
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        }
    }

    fun bytes(): Long {
        val v = used.get()
        if (v >= 0) return v
        val total = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        used.set(total)
        return total
    }

    fun clear() {
        dir.deleteRecursively()
        used.set(0)
    }

    private fun added(n: Long) {
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
 * An original on the server read by HTTP range (`GET /v1/originals/{fingerprint}`, ADR 0013), for the image core:
 * 100 % tiles, RAW headers, embedded previews. Reads go through a small block cache so a parser's many small reads
 * become a few range requests.
 */
class HttpRangeSource(
    private val api: ServerApi,
    private val fingerprint: String,
    override val size: Long,
    private val blockSize: Int = 256 * 1024,
    private val maxBlocks: Int = 24,
) : RandomAccessSource {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > maxBlocks
    }

    /** Requests made (for tests and logs). */
    var requests = 0
        private set

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size || position < 0) return -1
        if (length == 0) return 0
        val index = position / blockSize
        val block = block(index)
        val inBlock = (position - index * blockSize).toInt()
        val n = minOf(length, block.size - inBlock)
        if (n <= 0) return -1
        System.arraycopy(block, inBlock, buffer, offset, n)
        return n
    }

    private fun block(index: Long): ByteArray {
        blocks[index]?.let { return it }
        val start = index * blockSize
        val want = minOf(blockSize.toLong(), size - start).toInt()
        val bytes = ByteArray(want)
        requests++
        val (got, _) = api.readOriginal(fingerprint, start, want, bytes)
        val block = if (got == want) bytes else bytes.copyOf(got)
        blocks[index] = block
        return block
    }
}
