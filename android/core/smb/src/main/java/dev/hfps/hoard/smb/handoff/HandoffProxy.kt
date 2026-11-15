package dev.hfps.hoard.smb.handoff

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import dev.hfps.hoard.smb.stream.StreamEntry
import dev.hfps.hoard.smb.stream.StreamReader
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The proxy descriptors of hand-off links (ADR 0010 "Bursts of opens"). Every proxy descriptor is an appfuse file:
 * opening one goes through `system_server` and vold (which holds its global lock while the kernel asks us for the
 * file's size), and every read is a kernel request answered on a looper of ours. A client opening hundreds at once
 * (Reveal's start-up) could pile up system_server's binder threads behind vold and trip the watchdog. So:
 *
 *  - a proxy descriptor holds its slot of [HandoffServer.reads] (at most [HandoffServer.MAX_READS] hand-off reads
 *    at once) from the open until the client closes it, so at most that many proxy descriptors are ever open;
 *  - `openProxyFileDescriptor` itself is called by one thread at a time ([openLock]): vold serialises them anyway;
 *  - each slot has its own looper, started once and reused (not a thread per descriptor), so a slow read on one
 *    descriptor never delays another's size or read;
 *  - reads run on a shared executor and a callback waits at most [READ_TIMEOUT_MS] ([TimedReader]), then answers
 *    `EIO`.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal object HandoffProxy {
    private const val TAG = "HoardHandoff"

    /** Longest a read callback waits for the network before answering EIO (vold's watchdog is 60 s). */
    const val READ_TIMEOUT_MS = 10_000L

    private val openLock = Any()
    private val loopers = arrayOfNulls<HandlerThread>(HandoffServer.MAX_READS)
    private val openNow = AtomicInteger()
    private val proxyOpens = AtomicInteger()
    private val proxyOpenNanos = AtomicLong()
    private val proxyOpenMaxNanos = AtomicLong()

    /** For the burst summary: descriptors open now and the system's time per open since the last call. */
    fun stats(): String {
        val n = proxyOpens.getAndSet(0)
        val total = proxyOpenNanos.getAndSet(0)
        val max = proxyOpenMaxNanos.getAndSet(0)
        val avg = if (n > 0) total / n / 1_000_000 else 0
        return "${openNow.get()} descriptors open, appfuse open avg $avg ms max ${max / 1_000_000} ms"
    }

    private val reads = Executors.newCachedThreadPool { r -> Thread(r, "hoard-handoff-read").apply { isDaemon = true } }

    @Synchronized
    private fun handler(slot: Int): Handler {
        val thread = loopers[slot]?.takeIf { it.isAlive } ?: HandlerThread("hoard-handoff-fd-$slot").also {
            it.start()
            loopers[slot] = it
        }
        return Handler(thread.looper)
    }

    /**
     * A read-only descriptor of [size] bytes over [reader] ([entry]'s lease). Takes [slot]: released when the client
     * closes the descriptor, or at once when the open fails.
     */
    fun openRead(context: Context, slot: HandoffLimiter.Slot, entry: StreamEntry, reader: () -> StreamReader, size: Long): ParcelFileDescriptor {
        val source = try {
            reader()
        } catch (e: Throwable) {
            slot.close()
            throw e
        }
        val timed = TimedReader(source, reads, READ_TIMEOUT_MS)
        return open(context, slot, entry, ParcelFileDescriptor.MODE_READ_ONLY, ReadCallback(entry, size, timed, slot)) { timed.close() }
    }

    /** A read-write descriptor ([callback]: a sidecar in memory); takes [slot] as [openRead] does. */
    fun openWrite(context: Context, slot: HandoffLimiter.Slot, entry: StreamEntry, callback: ProxyFileDescriptorCallback, cleanup: () -> Unit): ParcelFileDescriptor =
        open(context, slot, entry, ParcelFileDescriptor.MODE_READ_WRITE, callback, cleanup)

    /** A descriptor's slot goes back (from its callback's `onRelease`). */
    fun released(slot: HandoffLimiter.Slot) {
        openNow.decrementAndGet()
        slot.close()
    }

    private fun open(
        context: Context,
        slot: HandoffLimiter.Slot,
        entry: StreamEntry,
        mode: Int,
        callback: ProxyFileDescriptorCallback,
        cleanup: () -> Unit,
    ): ParcelFileDescriptor {
        entry.openFds.incrementAndGet()
        entry.touch()
        try {
            val storage = context.getSystemService(StorageManager::class.java)
            return synchronized(openLock) {
                val t0 = System.nanoTime()
                try {
                    storage.openProxyFileDescriptor(mode, callback, handler(slot.index)).also { openNow.incrementAndGet() }
                } finally {
                    val took = System.nanoTime() - t0
                    proxyOpens.incrementAndGet()
                    proxyOpenNanos.addAndGet(took)
                    proxyOpenMaxNanos.accumulateAndGet(took) { a, b -> maxOf(a, b) }
                }
            }
        } catch (e: Throwable) {
            entry.openFds.decrementAndGet()
            cleanup()
            slot.close()
            if (e is FileNotFoundException) throw e
            throw FileNotFoundException("Could not open ${entry.name}: ${e.message}")
        }
    }

    private class ReadCallback(
        private val entry: StreamEntry,
        private val size: Long,
        private val reader: TimedReader,
        private val slot: HandoffLimiter.Slot,
    ) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            entry.touch()
            try {
                val n = reader.read(offset, size, data)
                entry.served.addAndGet(n.toLong())
                return n
            } catch (e: IOException) {
                Log.w(TAG, "Read of ${entry.name} @$offset failed: ${e.message}")
                throw ErrnoException("onRead", OsConstants.EIO)
            }
        }

        override fun onRelease() {
            reader.close()
            entry.openFds.decrementAndGet()
            entry.touch()
            released(slot)
        }
    }
}
