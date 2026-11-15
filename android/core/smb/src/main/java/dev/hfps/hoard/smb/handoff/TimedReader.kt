package dev.hfps.hoard.smb.handoff

import dev.hfps.hoard.smb.stream.StreamReader
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Bounds how long a proxy descriptor's callback waits for the network (pure, JVM-tested): each read runs on a shared
 * [executor] and the callback waits at most [timeoutMs] for it, then fails ([IOException], which the callback turns
 * into `EIO`) instead of blocking the kernel's request indefinitely. The read that timed out keeps going in the
 * background; the next read waits for it first (the underlying reader is not thread-safe), within the same bound.
 *
 * The reader's own read-ahead ([dev.hfps.hoard.smb.stream.RemoteReader]: up to four 1 MiB SMB reads in flight on
 * sequential reads) makes most callbacks a memory copy.
 */
class TimedReader(
    private val reader: StreamReader,
    private val executor: ExecutorService,
    private val timeoutMs: Long,
) : AutoCloseable {
    private var inFlight: Future<*>? = null
    private var buffer = ByteArray(0)
    @Volatile private var closed = false

    /** Up to [length] bytes at [offset] into [out]; [IOException] on failure or after [timeoutMs]. */
    @Synchronized
    fun read(offset: Long, length: Int, out: ByteArray): Int {
        if (closed) throw IOException("closed")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        inFlight?.let { previous ->
            await(previous, deadline)
            inFlight = null
        }
        // The background read fills its own buffer: a late result must never land in the kernel's array.
        if (buffer.size < length) buffer = ByteArray(length)
        val target = buffer
        val task = executor.submit<Int> { reader.read(offset, length, target, 0) }
        inFlight = task
        val n = await(task, deadline)
        inFlight = null
        System.arraycopy(target, 0, out, 0, n)
        return n
    }

    private fun <T> await(task: Future<T>, deadline: Long): T {
        val left = deadline - System.nanoTime()
        return try {
            task.get(maxOf(left, 0L), TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            throw IOException("read took longer than $timeoutMs ms", e)
        } catch (e: ExecutionException) {
            val cause = e.cause
            throw cause as? IOException ?: IOException(cause?.message ?: "read failed", cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        }
    }

    /** Closes the reader once any read still running has ended (never blocks the caller). */
    override fun close() {
        if (closed) return
        closed = true
        val pending = synchronized(this) { inFlight }
        executor.execute {
            runCatching { pending?.get(timeoutMs * 4, TimeUnit.MILLISECONDS) }
            runCatching { reader.close() }
        }
    }
}
