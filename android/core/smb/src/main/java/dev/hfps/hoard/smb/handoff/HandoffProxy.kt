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

/**
 * The proxy descriptors of hand-off links (ADR 0010 "Bursts of opens"). Every proxy descriptor is an appfuse file:
 * opening one goes through `system_server` and vold (which holds its global lock while the kernel asks us for the
 * file's size), and every read is a kernel request answered on a looper of ours. A client opening hundreds at once
 * (Reveal's start-up) could pile up system_server's binder threads behind vold and trip the watchdog. So:
 *
 *  - at most [MAX_FDS] hand-off proxy descriptors are open at once ([slots]); beyond that an open waits at most
 *    [SLOT_WAIT_MS] in a queue of at most [SLOT_QUEUE], then fails with [HandoffBusy];
 *  - `openProxyFileDescriptor` itself is called by one thread at a time ([openLock]): vold serialises them anyway;
 *  - each slot has its own looper, started once and reused (not a thread per descriptor), so a slow read on one
 *    descriptor never delays another's size or read;
 *  - reads run on a shared executor and a callback waits at most [READ_TIMEOUT_MS] ([TimedReader]), then answers
 *    `EIO`.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal object HandoffProxy {
    private const val TAG = "HoardHandoff"

    /** Hand-off proxy descriptors open at once. */
    const val MAX_FDS = 6
    const val SLOT_QUEUE = 8
    const val SLOT_WAIT_MS = 2_000L

    /** Longest a read callback waits for the network before answering EIO (vold's watchdog is 60 s). */
    const val READ_TIMEOUT_MS = 10_000L

    val slots = HandoffLimiter(MAX_FDS, SLOT_QUEUE, SLOT_WAIT_MS, "hand-off reads in progress")
    private val openLock = Any()
    private val loopers = arrayOfNulls<HandlerThread>(MAX_FDS)
    private val reads = Executors.newCachedThreadPool { r -> Thread(r, "hoard-handoff-read").apply { isDaemon = true } }

    @Synchronized
    private fun handler(slot: Int): Handler {
        val thread = loopers[slot]?.takeIf { it.isAlive } ?: HandlerThread("hoard-handoff-fd-$slot").also {
            it.start()
            loopers[slot] = it
        }
        return Handler(thread.looper)
    }

    /** A read-only descriptor of [size] bytes over [reader] ([entry]'s lease); [HandoffBusy] when every slot is taken. */
    fun openRead(context: Context, entry: StreamEntry, reader: () -> StreamReader, size: Long): ParcelFileDescriptor {
        val slot = slots.acquire()
        val source = try {
            reader()
        } catch (e: Throwable) {
            slot.close()
            throw e
        }
        val timed = TimedReader(source, reads, READ_TIMEOUT_MS)
        return open(context, slot, entry, ParcelFileDescriptor.MODE_READ_ONLY, ReadCallback(entry, size, timed, slot)) { timed.close() }
    }

    /** A read-write descriptor ([callback]: a sidecar in memory); [HandoffBusy] when every slot is taken. */
    fun openWrite(context: Context, entry: StreamEntry, callback: (HandoffLimiter.Slot) -> ProxyFileDescriptorCallback, cleanup: () -> Unit): ParcelFileDescriptor {
        val slot = try {
            slots.acquire()
        } catch (e: Throwable) {
            cleanup()
            throw e
        }
        return open(context, slot, entry, ParcelFileDescriptor.MODE_READ_WRITE, callback(slot), cleanup)
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
            return synchronized(openLock) { storage.openProxyFileDescriptor(mode, callback, handler(slot.index)) }
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
            slot.close()
        }
    }
}
