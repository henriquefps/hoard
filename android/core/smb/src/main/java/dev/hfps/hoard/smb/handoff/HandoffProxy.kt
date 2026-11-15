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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The proxy descriptors of hand-off links (ADR 0010 "Bursts of opens", "Idle reclaim"). Every proxy descriptor is an
 * appfuse file: opening one goes through `system_server` and vold (which holds its global lock while the kernel asks
 * us for the file's size), and every read is a kernel request answered on a looper of ours. A client opening hundreds
 * at once (Reveal's start-up) could pile up system_server's binder threads behind vold and trip the watchdog. So:
 *
 *  - a proxy descriptor holds its slot of [HandoffServer.reads] (at most [HandoffServer.MAX_READS] hand-off reads
 *    at once) from the open while it is read; after [IDLE_RELEASE_MS] without a callback it gives the slot back
 *    (parked) and its next read takes one again through the same limiter ([HandoffHold]); after [RETIRE_MS] it is
 *    closed from Hoard's side (its SMB reader and lease go; later reads fail). So a client that leaks descriptors
 *    holds the read slots for [IDLE_RELEASE_MS] at most;
 *  - `openProxyFileDescriptor` itself is called by one thread at a time ([openLock]): vold serialises them anyway;
 *  - [MAX_LIVE] loopers, started once and reused, one per live descriptor ([HandoffDescriptors]): a slow read on one
 *    descriptor never delays another's size or read, and a leaking client cannot make Hoard start threads;
 *  - reads run on a shared executor and a callback waits at most [READ_TIMEOUT_MS] ([TimedReader]), then answers
 *    `EIO`.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal object HandoffProxy {
    private const val TAG = "HoardHandoff"

    /** Longest a read callback waits for the network before answering EIO (vold's watchdog is 60 s). */
    const val READ_TIMEOUT_MS = 10_000L

    /**
     * A descriptor with no callback for this long gives its read slot back (ADR 0010 "Idle reclaim"). Longer than a
     * read may take ([READ_TIMEOUT_MS]) and than the pauses of a client reading a photo (decode steps, a thumbnail
     * then the full image); well inside a client's busy retry budget (about 30 s), so a client that leaks every
     * slot delays the others by this much at most.
     */
    const val IDLE_RELEASE_MS = 12_000L

    /** A descriptor with no callback for this long is closed from Hoard's side (remote SMB handles do not pile up). */
    const val RETIRE_MS = 10 * 60_000L

    /** Live descriptors with a looper of their own: the read slots plus as many parked ones. */
    const val MAX_LIVE = 2 * HandoffServer.MAX_READS

    /** How often idle descriptors are looked for while any is live. */
    const val REAP_EVERY_MS = 1_000L

    private val openLock = Any()
    private val loopers = arrayOfNulls<HandlerThread>(MAX_LIVE)
    internal val descriptors = HandoffDescriptors(HandoffServer.reads, MAX_LIVE, IDLE_RELEASE_MS, RETIRE_MS)
    private val proxyOpens = AtomicInteger()
    private val proxyOpenNanos = AtomicLong()
    private val proxyOpenMaxNanos = AtomicLong()
    private val reaper = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hoard-handoff-reap").apply { isDaemon = true } }
    private var reaping: ScheduledFuture<*>? = null

    /** For the burst summary: descriptors open now and the system's time per open since the last call. */
    fun stats(): String {
        val n = proxyOpens.getAndSet(0)
        val total = proxyOpenNanos.getAndSet(0)
        val max = proxyOpenMaxNanos.getAndSet(0)
        val avg = if (n > 0) total / n / 1_000_000 else 0
        return "${descriptors.live} descriptors open (${descriptors.parked} parked, ${descriptors.reacquiredTotal} reacquired, " +
            "${descriptors.retiredTotal} retired), appfuse open avg $avg ms max ${max / 1_000_000} ms"
    }

    private val reads = Executors.newCachedThreadPool { r -> Thread(r, "hoard-handoff-read").apply { isDaemon = true } }

    @Synchronized
    private fun handler(looper: Int): Handler {
        val thread = loopers[looper]?.takeIf { it.isAlive } ?: HandlerThread("hoard-handoff-fd-$looper").also {
            it.start()
            loopers[looper] = it
        }
        return Handler(thread.looper)
    }

    /** Looks for idle descriptors every [REAP_EVERY_MS] while any is live. */
    @Synchronized
    private fun startReaping() {
        if (reaping != null) return
        reaping = reaper.scheduleWithFixedDelay(::reap, REAP_EVERY_MS, REAP_EVERY_MS, TimeUnit.MILLISECONDS)
    }

    private fun reap() {
        val before = descriptors.retiredTotal
        runCatching { descriptors.reap() }.onFailure { Log.w(TAG, "Reap failed: ${it.message}") }
        val retired = descriptors.retiredTotal - before
        if (retired > 0) Log.i(TAG, "Closed $retired hand-off descriptors idle for ${RETIRE_MS / 60_000} min")
        synchronized(this) {
            if (descriptors.live == 0) {
                reaping?.cancel(false)
                reaping = null
            }
        }
    }

    /**
     * A read-only descriptor of [size] bytes over [reader] ([entry]'s lease). Takes [slot] (released when idle, when
     * the client closes the descriptor, or at once when the open fails).
     */
    fun openRead(context: Context, slot: HandoffLimiter.Slot, entry: StreamEntry, reader: () -> StreamReader, size: Long): ParcelFileDescriptor {
        val source = try {
            reader()
        } catch (e: Throwable) {
            slot.close()
            throw e
        }
        val timed = TimedReader(source, reads, READ_TIMEOUT_MS)
        return open(context, slot, entry, ParcelFileDescriptor.MODE_READ_ONLY, { timed.close() }) { hold -> ReadCallback(entry, size, timed, hold) }
    }

    /**
     * A read-write descriptor ([callback]: a sidecar in memory); takes [slot] as [openRead] does. [cleanup] runs once,
     * when the client closes the descriptor or Hoard retires it.
     */
    fun openWrite(context: Context, slot: HandoffLimiter.Slot, entry: StreamEntry, cleanup: () -> Unit, callback: (HandoffHold) -> ProxyFileDescriptorCallback): ParcelFileDescriptor =
        open(context, slot, entry, ParcelFileDescriptor.MODE_READ_WRITE, cleanup, callback)

    /** The client closed [hold]'s descriptor (its callback's `onRelease`). */
    fun released(hold: HandoffHold) {
        descriptors.released(hold)
    }

    private fun open(
        context: Context,
        slot: HandoffLimiter.Slot,
        entry: StreamEntry,
        mode: Int,
        cleanup: () -> Unit,
        callback: (HandoffHold) -> ProxyFileDescriptorCallback,
    ): ParcelFileDescriptor {
        entry.openFds.incrementAndGet()
        entry.touch()
        val hold = descriptors.register(slot) {
            try {
                cleanup()
            } finally {
                entry.openFds.decrementAndGet()
                entry.touch()
            }
        }
        startReaping()
        try {
            val storage = context.getSystemService(StorageManager::class.java)
            return synchronized(openLock) {
                val t0 = System.nanoTime()
                try {
                    storage.openProxyFileDescriptor(mode, callback(hold), handler(hold.looper))
                } finally {
                    val took = System.nanoTime() - t0
                    proxyOpens.incrementAndGet()
                    proxyOpenNanos.addAndGet(took)
                    proxyOpenMaxNanos.accumulateAndGet(took) { a, b -> maxOf(a, b) }
                }
            }
        } catch (e: Throwable) {
            descriptors.released(hold) // the slot back, cleanup once
            if (e is FileNotFoundException) throw e
            throw FileNotFoundException("Could not open ${entry.name}: ${e.message}")
        }
    }

    private class ReadCallback(
        private val entry: StreamEntry,
        private val size: Long,
        private val reader: TimedReader,
        private val hold: HandoffHold,
    ) : ProxyFileDescriptorCallback() {
        // The size is known: no network, so no slot (a parked descriptor answers it as it is).
        override fun onGetSize(): Long {
            hold.touch()
            return size
        }

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            entry.touch()
            try {
                val n = hold.use { reader.read(offset, size, data) }
                entry.served.addAndGet(n.toLong())
                return n
            } catch (e: IOException) {
                Log.w(TAG, "Read of ${entry.name} @$offset failed: ${e.message}")
                throw ErrnoException("onRead", OsConstants.EIO)
            }
        }

        override fun onRelease() {
            released(hold)
        }
    }
}
