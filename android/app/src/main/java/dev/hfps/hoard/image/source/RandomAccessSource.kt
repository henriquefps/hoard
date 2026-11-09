package dev.hfps.hoard.image.source

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * Random-access bytes of one file, wherever it lives (SMB, local storage, SAF, USB). The image core
 * only ever reads ranges, so a parser can look at a RAW's header and its embedded preview without
 * reading the 25 MB around them.
 *
 * Implementations must allow [read] from one thread at a time; the core never shares a source
 * between threads.
 */
interface RandomAccessSource : Closeable {
    /** Total length in bytes. */
    val size: Long

    /**
     * Reads up to [length] bytes at [position] into [buffer] at [offset]. Returns the number of bytes
     * read (may be short), or -1 at or past the end.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    override fun close() = Unit
}

/** Reads exactly [length] bytes at [position] (or throws [EOFException]). */
fun RandomAccessSource.readFully(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
    var done = 0
    while (done < length) {
        val n = read(position + done, buffer, offset + done, length - done)
        if (n <= 0) throw EOFException("Unexpected end at ${position + done} (size $size)")
        done += n
    }
}

/** Reads the range [position, position + length) clipped to the file into a new array. */
fun RandomAccessSource.readRange(position: Long, length: Int): ByteArray {
    if (position >= size || length <= 0) return ByteArray(0)
    val n = minOf(length.toLong(), size - position).toInt()
    return ByteArray(n).also { readFully(position, it) }
}

/** Copies the range [position, position + length) to [out] in chunks, checking [cancel] between them. */
fun RandomAccessSource.copyTo(out: OutputStream, position: Long = 0, length: Long = size - position, cancel: CancelToken = CancelToken.NONE, chunk: Int = 1 shl 20) {
    val buffer = ByteArray(chunk)
    var done = 0L
    while (done < length) {
        cancel.throwIfCancelled()
        val want = minOf(chunk.toLong(), length - done).toInt()
        val n = read(position + done, buffer, 0, want)
        if (n <= 0) throw EOFException("Unexpected end at ${position + done}")
        out.write(buffer, 0, n)
        done += n
    }
}

/** Copies the whole source to [target] through a temp file (atomic rename). */
fun RandomAccessSource.copyToFile(target: File, cancel: CancelToken = CancelToken.NONE) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, ".${target.name}.part")
    try {
        FileOutputStream(temp).use { copyTo(it, cancel = cancel) }
        if (!temp.renameTo(target)) throw IOException("Could not move ${temp.name} into place")
    } finally {
        temp.delete()
    }
}

/** A window [start, start + length) of another source, seen as a source of its own. */
class SliceSource(private val parent: RandomAccessSource, private val start: Long, length: Long) : RandomAccessSource {
    override val size: Long = length.coerceAtMost(parent.size - start).coerceAtLeast(0)

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        val n = minOf(length.toLong(), size - position).toInt()
        return parent.read(start + position, buffer, offset, n)
    }
}

/** An [InputStream] over [source], from [start] for [length] bytes (for decoders that take streams). */
class SourceInputStream(private val source: RandomAccessSource, start: Long = 0, length: Long = source.size - start) : InputStream() {
    private var position = start
    private val end = start + length
    private var mark = start

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position >= end) return -1
        val n = source.read(position, b, off, minOf(len.toLong(), end - position).toInt())
        if (n > 0) position += n
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = n.coerceIn(0, end - position)
        position += skipped
        return skipped
    }

    override fun available(): Int = (end - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    override fun markSupported() = true
    override fun mark(readlimit: Int) { mark = position }
    override fun reset() { position = mark }
}

/** A local file. */
class FileSource(file: File) : RandomAccessSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        raf.seek(position)
        return raf.read(buffer, offset, length)
    }

    override fun close() = raf.close()
}

/** Bytes in memory (tests, small staged previews). */
class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }
}

/** Counts what goes through [inner]: bytes and read calls (one call ~ one SMB round trip). */
class CountingSource(private val inner: RandomAccessSource) : RandomAccessSource {
    private val bytes = AtomicLong()
    private val calls = AtomicLong()

    val bytesRead: Long get() = bytes.get()
    val readCalls: Long get() = calls.get()
    override val size: Long get() = inner.size

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        calls.incrementAndGet()
        val n = inner.read(position, buffer, offset, length)
        if (n > 0) bytes.addAndGet(n.toLong())
        return n
    }

    fun reset() { bytes.set(0); calls.set(0) }
    override fun close() = inner.close()
}

/**
 * Caches aligned blocks of [inner] (a few, LRU), so a parser that hops around a header with many
 * small reads costs a few block-sized reads (SMB round trips) instead of hundreds.
 */
class BlockCachedSource(
    private val inner: RandomAccessSource,
    private val blockSize: Int = 64 * 1024,
    private val maxBlocks: Int = 16,
) : RandomAccessSource {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(maxBlocks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > maxBlocks
    }

    override val size: Long get() = inner.size

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        // Large reads (a whole embedded JPEG) bypass the cache.
        if (length >= blockSize * 2) return inner.read(position, buffer, offset, minOf(length.toLong(), size - position).toInt())
        val index = position / blockSize
        val block = blocks.getOrPut(index) { inner.readRange(index * blockSize, blockSize) }
        val within = (position - index * blockSize).toInt()
        if (within >= block.size) return -1
        val n = minOf(length, block.size - within)
        System.arraycopy(block, within, buffer, offset, n)
        return n
    }

    /** Preloads [length] bytes from [position] with one read (e.g. the first 128 KiB of a RAW). */
    fun prefetch(position: Long, length: Int) {
        val first = position / blockSize
        val last = (minOf(position + length, size) - 1) / blockSize
        if (last < first) return
        if ((first..last).all { blocks.containsKey(it) }) return
        val data = inner.readRange(first * blockSize, ((last - first + 1) * blockSize).toInt())
        for (i in first..last) {
            val from = ((i - first) * blockSize).toInt()
            if (from >= data.size) break
            blocks[i] = data.copyOfRange(from, minOf(from + blockSize, data.size))
        }
    }

    override fun close() = inner.close()
}

/** Cooperative cancellation for long work (staging, decoding, LibRaw). */
open class CancelToken {
    @Volatile var isCancelled: Boolean = false
        private set

    open fun cancel() { isCancelled = true }

    fun throwIfCancelled() { if (isCancelled) throw CancelledException() }

    companion object {
        val NONE: CancelToken = object : CancelToken() {
            override fun cancel() = Unit
        }
    }
}

class CancelledException : IOException("Cancelled")
