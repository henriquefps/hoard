package dev.hfps.hoard.smb.handoff

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import com.hierynomus.smbj.share.DiskShare
import dev.hfps.hoard.smb.CredentialStore
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.SmbErrors
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.stream.SmbSource
import dev.hfps.hoard.smb.stream.StreamEntry
import dev.hfps.hoard.smb.stream.StreamKind
import dev.hfps.hoard.smb.stream.StreamProvider
import dev.hfps.hoard.smb.stream.StreamReader
import dev.hfps.hoard.smb.stream.StreamRegistry
import dev.hfps.hoard.smb.stream.StreamSource
import dev.hfps.hoard.smb.transfer.PartNames
import dev.hfps.hoard.smb.transfer.SmbShareFs
import dev.hfps.hoard.smb.transfer.TransferPaths
import dev.hfps.hoard.smb.viewer.RemoteFileInfo
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves the stable hand-off links of "Edit in Reveal" (ADR 0010 "Persistable hand-offs") inside `StreamProvider`:
 * `content://<app>.stream/h/<id>/<tag>%2F<name>`, an id of [HandoffStore]. Works in a process the system started
 * only for the provider (Reveal reopening a photo after a restart): the machine's addresses and pinned GUID come from
 * the store's mirror, the login from the Keystore ([CredentialStore]), and the route is found natively
 * ([NativeRoute]: last good address first, another server's GUID skipped).
 *
 * Reading is a seekable proxy descriptor, as for every stream. A sidecar link may also be written ([HandoffAccess]):
 * the bytes stay in memory and go to the share in one atomic write (`.<name>.hoardpart`, then a rename) when the
 * descriptor is synced or closed, only when they are well-formed XMP. Photos are never written.
 *
 * Every proxied open takes a short lease in [StreamRegistry] ([LEASE_IDLE_MS] after the last read), which keeps the
 * streaming service (and so the process, un-frozen) up while Reveal reads, then lets it stop.
 *
 * Bursts (ADR 0010 "Bursts of opens"): a client may open hundreds of links at once. Opens are gated ([MAX_OPENING]
 * doing network work at once, a bounded queue, then `busy:`), small files are served from a private copy
 * ([HandoffCache]: a plain descriptor, no appfuse), proxy descriptors are capped and read with a timeout
 * ([HandoffProxy]), an unreachable machine fails the rest of the burst at once ([ConnectBackoff]), the connection
 * is shared and kept a little after the last use ([Sources]), and the log has one line per burst ([HandoffBurst]).
 */
object HandoffServer {
    private const val TAG = "HoardHandoff"
    private const val STORE_FILE = "reveal-handoffs.json"
    private const val CACHE_DIR = "handoff"

    /** A lease (and the service) ends this long after Reveal last read through a link. */
    const val LEASE_IDLE_MS = 2 * 60_000L

    /** Opens doing network work (stat, a small copy, opening a descriptor) at once; the rest wait or are refused. */
    const val MAX_OPENING = 4
    const val OPEN_QUEUE = 16
    const val OPEN_WAIT_MS = 3_000L

    /** After a machine could not be reached, opens of its share fail at once for this long. */
    const val BACKOFF_MS = 10_000L

    /** A burst ends after this long without opens; its summary is logged then. */
    const val BURST_QUIET_MS = 2_000L

    private val BACKOFF_CODES = setOf(ErrorCode.UNREACHABLE, ErrorCode.TIMEOUT, ErrorCode.GUID_MISMATCH, ErrorCode.AUTH_FAILED)

    @Volatile private var app: Context? = null
    private lateinit var store: HandoffStore
    private lateinit var credentials: CredentialStore
    private lateinit var cache: HandoffCache

    private val opening = HandoffLimiter(MAX_OPENING, OPEN_QUEUE, OPEN_WAIT_MS, "hand-off opens in progress")
    private val backoff = ConnectBackoff(BACKOFF_MS)
    private val burst = HandoffBurst(BURST_QUIET_MS)
    private val summaryScheduled = AtomicBoolean()
    private val summaries = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hoard-handoff-log").apply { isDaemon = true } }

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        val appContext = context.applicationContext
        store = HandoffStore(File(appContext.noBackupFilesDir, STORE_FILE))
        credentials = CredentialStore(appContext)
        cache = HandoffCache(File(appContext.cacheDir, CACHE_DIR))
        StreamRegistry.init(appContext)
        app = appContext
    }

    fun store(context: Context): HandoffStore {
        init(context)
        return store
    }

    private fun context(): Context = app ?: throw FileNotFoundException("Hoard is starting")

    /** The link's URI: `content://<app>.stream/h/<id>/<tag>%2F<name>`. */
    fun uriOf(packageName: String, id: String, tag: String, name: String): Uri =
        Uri.Builder().scheme("content").authority(StreamProvider.authority(packageName))
            .appendPath(HandoffUris.PREFIX).appendPath(id).appendPath(HandoffUris.lastSegment(tag, name)).build()

    /** The SMB link of [uri], or null when it is not (or no longer) one. */
    fun item(uri: Uri): HandoffItem? {
        context()
        return store.get(HandoffUris.idOf(uri.pathSegments))?.takeIf { it.kind == HandoffKind.SMB }
    }

    /** Name, size, mtime and type from the store: no network (Reveal lists before it reads). */
    fun query(uri: Uri, projection: Array<out String>?): Cursor? {
        val item = item(uri) ?: return null
        val known = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val columns = projection?.filter { it in known }?.toTypedArray() ?: known
        return MatrixCursor(columns, 1).apply {
            addRow(
                columns.map {
                    when (it) {
                        OpenableColumns.DISPLAY_NAME -> item.name
                        OpenableColumns.SIZE -> item.size
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> item.modifiedMillis
                        else -> item.mimeType
                    }
                },
            )
        }
    }

    /**
     * Opens a link for [mode]. Unknown link, unreachable share, missing file, refused login: [FileNotFoundException]
     * with what went wrong (Reveal treats the photo as unreadable and falls back to its preview). Too many opens at
     * once: [FileNotFoundException] whose message starts with `busy:` ([HandoffBusy]; Reveal retries those). A write
     * on a photo: [SecurityException]. Runs on a binder thread (never the main thread): at most [MAX_OPENING] opens
     * do network work at once, the others wait at most [OPEN_WAIT_MS] (ADR 0010 "Bursts of opens").
     */
    fun open(uri: Uri, mode: String, caller: String?): ParcelFileDescriptor {
        val item = item(uri) ?: throw FileNotFoundException("This photo is no longer shared with Reveal")
        val access = try {
            HandoffAccess.of(mode, item.sidecar)
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException(e.message)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) throw FileNotFoundException("Streaming needs Android 8")
        val debug = Log.isLoggable(TAG, Log.DEBUG)
        val t0 = System.nanoTime()
        burst.begin()
        scheduleSummary()
        var outcome = OpenOutcome.FAILED
        try {
            // Network work (stat, a small copy) under the gate; a proxy descriptor is opened after it, under its own
            // limit, so cheap opens are not stuck behind the system's (slow, serialised) appfuse opens.
            val opened = opening.acquire().use { slot ->
                if (debug) Log.d(TAG, "open ${item.name} ($mode) for $caller: gate after ${(System.nanoTime() - t0) / 1_000_000} ms")
                openGated(item, access).also { if (debug) Log.d(TAG, "open ${item.name}: ${it.outcome} after ${(System.nanoTime() - t0) / 1_000_000} ms") }
            }
            outcome = opened.outcome
            return opened.descriptor ?: opened.proxy!!().also { if (debug) Log.d(TAG, "open ${item.name}: descriptor after ${(System.nanoTime() - t0) / 1_000_000} ms") }
        } catch (e: FileNotFoundException) {
            if (HandoffBusy.isBusy(e)) outcome = OpenOutcome.REFUSED
            throw e
        } finally {
            burst.end(outcome)
        }
    }

    /** What [openGated] decided: a descriptor already, or a proxy descriptor to open outside the gate. */
    private class Opened(val outcome: OpenOutcome, val descriptor: ParcelFileDescriptor? = null, val proxy: (() -> ParcelFileDescriptor)? = null)

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openGated(item: HandoffItem, access: HandoffAccess): Opened {
        val machine = store.machine(item.machineId)
            ?: throw FileNotFoundException("${item.name}: its machine is no longer in Hoard")
        if (machine.serverGuid == null) throw FileNotFoundException("${item.name}: the machine is not pinned yet")
        if (!credentials.has(machine.id)) throw FileNotFoundException("${item.name}: no login stored for the machine")
        val key = Sources.keyOf(machine.id, item.share.orEmpty())
        backoff.blocked(key)?.let { why -> throw FileNotFoundException("${item.name}: $why") }
        val source = Sources.acquire(machine, item.share.orEmpty(), credentials)
        try {
            val info = try {
                source.stat(item.path)
            } catch (e: Throwable) {
                throw unreadable(item, SmbErrors.map(e), key)
            }
            backoff.succeeded(key)
            source.smb.activeAddress?.let { store.setLastGood(machine.id, it) }
            if (info?.isDirectory == true) throw FileNotFoundException("${item.name} is a folder now")
            store.touch(item.id)
            if (access != HandoffAccess.READ) return Opened(OpenOutcome.WRITE, descriptor = openWrite(item, machine, source, info, access))
            info ?: throw FileNotFoundException("${item.name} is no longer on the share")
            store.updateFile(item.id, info.size, info.modifiedMillis)
            // Small files: a private copy and a plain descriptor (no appfuse); a slow copy falls back to a proxy.
            if (cache.fits(info.size)) {
                val copy = cache.cached(item.id, info.size, info.modifiedMillis) ?: try {
                    cache.fetch(item.id, info.size, info.modifiedMillis) { source.reader(item.path, info.size, AtomicLong()) }
                } catch (e: Throwable) {
                    throw unreadable(item, SmbErrors.map(e), null)
                }
                if (copy != null) return Opened(OpenOutcome.CACHED, descriptor = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY))
            }
            // The lease holds its own reference to the connection, so it outlives this handle.
            val entry = lease(item, machine)
            return Opened(OpenOutcome.PROXIED) {
                HandoffProxy.openRead(context(), entry, { entry.source.reader(item.path, info.size, entry.fetched) }, info.size)
            }
        } finally {
            source.close()
        }
    }

    /** The link's lease: its connection reference and the service while Reveal reads (sizes go per descriptor). */
    private fun lease(item: HandoffItem, machine: HandoffMachine): StreamEntry =
        StreamRegistry.lease(leaseKey(item.id)) {
            StreamEntry(
                leaseKey(item.id), StreamKind.FILE, Sources.acquire(machine, item.share.orEmpty(), credentials),
                item.path, item.name, item.size, item.mimeType, http = false, idleMs = LEASE_IDLE_MS,
            )
        }

    private fun leaseKey(id: String) = "h-$id"

    private fun unreadable(item: HandoffItem, error: HoardSmbException, backoffKey: String?): FileNotFoundException {
        val why = when (error.code) {
            ErrorCode.UNREACHABLE, ErrorCode.TIMEOUT -> "the machine is not reachable"
            ErrorCode.AUTH_FAILED -> "the machine refused the login"
            ErrorCode.GUID_MISMATCH -> "no address reaches the pinned server"
            ErrorCode.ACCESS_DENIED -> "access to the share was denied"
            ErrorCode.NOT_FOUND -> "the share or the file is gone"
            else -> error.message ?: error.code.name
        }
        val reason = "$why (${error.code})"
        // The machine itself failed: the next opens of the burst fail at once instead of connecting again.
        if (backoffKey != null && error.code in BACKOFF_CODES) backoff.failed(backoffKey, reason)
        Log.w(TAG, "${item.name} not readable: ${error.code}")
        return FileNotFoundException("${item.name}: $reason")
    }

    // --- Bursts ---------------------------------------------------------------------------------------------------

    private fun scheduleSummary() {
        if (summaryScheduled.getAndSet(true)) return
        summaries.schedule(::summarize, BURST_QUIET_MS, TimeUnit.MILLISECONDS)
    }

    private fun summarize() {
        val line = burst.flush()
        if (line == null) {
            summaries.schedule(::summarize, BURST_QUIET_MS / 2, TimeUnit.MILLISECONDS)
            return
        }
        summaryScheduled.set(false)
        if (burst.pending() > 0) scheduleSummary()
        val proxies = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ", ${HandoffProxy.stats()}" else ""
        Log.i(TAG, "$line$proxies")
        // Last-use times of the burst, written once (not once per open).
        runCatching { store.flush() }
    }

    // --- Sidecar writes -------------------------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openWrite(item: HandoffItem, machine: HandoffMachine, source: Sources.Handle, info: RemoteFileInfo?, access: HandoffAccess): ParcelFileDescriptor {
        val initial = if (access == HandoffAccess.WRITE_TRUNCATE || info == null) {
            ByteArray(0)
        } else {
            if (info.size > SidecarBuffer.MAX_BYTES) throw FileNotFoundException("${item.name} is too large to edit")
            try {
                readAll(source, item.path, info.size)
            } catch (e: Throwable) {
                throw unreadable(item, SmbErrors.map(e), null)
            }
        }
        val buffer = SidecarBuffer(initial, append = access == HandoffAccess.APPEND)
        val entry = lease(item, machine)
        val writer = Sources.acquire(machine, item.share.orEmpty(), credentials)
        return HandoffProxy.openWrite(context(), entry, { slot -> SidecarCallback(item, buffer, writer, entry, slot) }) { writer.close() }
    }

    private fun readAll(source: StreamSource, path: String, size: Long): ByteArray {
        val out = ByteArray(size.toInt())
        source.reader(path, size, AtomicLong()).use { reader ->
            var done = 0
            while (done < out.size) {
                val n = reader.read(done.toLong(), out.size - done, out, done)
                if (n <= 0) break
                done += n
            }
            return if (done == out.size) out else out.copyOf(done)
        }
    }

    /** Writes [bytes] as the sidecar at [item]'s path: a partial file renamed over it (atomic on the share). */
    private fun commit(item: HandoffItem, source: Sources.Handle, bytes: ByteArray) {
        val fs = object : SmbShareFs(item.share.orEmpty()) {
            override fun share(): DiskShare = source.smb.share()
        }
        val folder = SmbPaths.parent(item.path)
        val part = TransferPaths.join(folder, PartNames.of(item.name))
        try {
            val writer = fs.openWrite(part, 0)
            try {
                writer.write(bytes, 0, bytes.size)
                writer.finish()
            } finally {
                runCatching { writer.close() }
            }
            if (!fs.rename(part, item.path, true)) throw HoardSmbException(ErrorCode.UNKNOWN, "Could not name ${item.name}")
        } catch (e: Throwable) {
            runCatching { fs.delete(part) }
            throw e
        }
        val stat = runCatching { fs.stat(item.path) }.getOrNull()
        store.updateFile(item.id, stat?.size ?: bytes.size.toLong(), stat?.modifiedMillis)
        store.flush()
        Log.i(TAG, "${item.name} written by Reveal (${bytes.size} bytes)")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private class SidecarCallback(
        private val item: HandoffItem,
        private val buffer: SidecarBuffer,
        private val source: Sources.Handle,
        private val entry: StreamEntry,
        private val slot: HandoffLimiter.Slot,
    ) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = buffer.size.toLong()

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            entry.touch()
            return buffer.read(offset, size, data)
        }

        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
            entry.touch()
            if (!buffer.write(offset, size, data)) throw ErrnoException("onWrite", OsConstants.EFBIG)
            return size
        }

        override fun onFsync() {
            if (!flush()) throw ErrnoException("onFsync", OsConstants.EIO)
        }

        override fun onRelease() {
            try {
                flush()
            } finally {
                source.close()
                entry.openFds.decrementAndGet()
                entry.touch()
                slot.close()
            }
        }

        /** True when nothing is pending afterwards. */
        private fun flush(): Boolean {
            if (!buffer.dirty) return true
            val bytes = buffer.commitBytes()
            if (bytes == null) {
                Log.w(TAG, "${item.name}: not well-formed XMP, the sidecar was left as it was")
                buffer.discard()
                return false
            }
            return try {
                commit(item, source, bytes)
                true
            } catch (e: Throwable) {
                Log.w(TAG, "${item.name}: sidecar not written: ${SmbErrors.map(e).code}")
                false
            }
        }
    }

    // --- Settings: count, revoke, prune ---------------------------------------------------------------------------

    fun count(context: Context): Int = store(context).count()

    /**
     * "Revoke all": every grant on Hoard's hand-off links (one prefix revocation, persisted grants included) and on
     * the FileProvider URIs handed to Reveal, then the store. SAF documents belong to Android's storage provider: Hoard
     * cannot revoke those (the user removes them in Reveal). Returns the number of links forgotten.
     */
    fun revokeAll(context: Context): Int {
        val items = store(context).clear()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val prefix = Uri.Builder().scheme("content").authority(StreamProvider.authority(context.packageName))
            .appendPath(HandoffUris.PREFIX).build()
        runCatching { context.revokeUriPermission(prefix, flags) }
        items.filter { it.kind == HandoffKind.LOCAL }.forEach { runCatching { context.revokeUriPermission(Uri.parse(it.uri), flags) } }
        StreamRegistry.removeLeases("h-")
        cache.clear()
        Log.i(TAG, "Revoked ${items.size} hand-off links")
        return items.size
    }

    /** Forgets SMB links unused for 180 days (revoking their grants) and caps the LOCAL records. */
    fun prune(context: Context) {
        val gone = store(context).prune()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        gone.filter { it.kind == HandoffKind.SMB }.forEach { item ->
            runCatching { context.revokeUriPermission(Uri.parse(item.uri), flags) }
        }
        if (gone.isNotEmpty()) Log.i(TAG, "Pruned ${gone.size} hand-off links")
    }

    // --- Connections ----------------------------------------------------------------------------------------------

    /**
     * One SMB connection per machine + share for every open, lease and write of the links (a batch Reveal reads file
     * after file reuses it); reference-counted, closed [LINGER_MS] after the last [Handle] (a burst of cached opens,
     * which hold no lease, does not reconnect per open).
     */
    internal object Sources {
        const val LINGER_MS = 60_000L

        private class Pooled(val source: SmbSource) {
            var refs = 0
            var closing: ScheduledFuture<*>? = null
        }

        private val closer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hoard-handoff-linger").apply { isDaemon = true } }

        fun keyOf(machineId: String, share: String) = "$machineId|${share.lowercase()}"

        private val pool = HashMap<String, Pooled>()

        class Handle internal constructor(private val key: String, val smb: SmbSource) : StreamSource {
            @Volatile private var closed = false
            override val label: String get() = smb.label
            override fun stat(path: String): RemoteFileInfo? = smb.stat(path)
            override fun list(path: String): List<Pair<String, Boolean>> = throw SecurityException("Hand-off links never list folders")
            override fun reader(path: String, size: Long, fetched: AtomicLong): StreamReader = smb.reader(path, size, fetched)
            override fun close() {
                if (closed) return
                closed = true
                release(key)
            }
        }

        @Synchronized
        fun acquire(machine: HandoffMachine, share: String, credentials: CredentialStore): Handle {
            val key = keyOf(machine.id, share)
            val pooled = pool.getOrPut(key) {
                Pooled(
                    SmbSource(
                        machine.id,
                        NativeRoute.order(machine.addresses, machine.lastGood).ifEmpty { throw FileNotFoundException("The machine has no address") },
                        machine.serverGuid,
                        share,
                        credentials::get,
                        fatal = NativeRoute.FINAL,
                    ),
                )
            }
            pooled.closing?.cancel(false)
            pooled.closing = null
            pooled.refs++
            return Handle(key, pooled.source)
        }

        @Synchronized
        private fun release(key: String) {
            val pooled = pool[key] ?: return
            if (--pooled.refs > 0) return
            pooled.closing = closer.schedule({ expire(key, pooled) }, LINGER_MS, TimeUnit.MILLISECONDS)
        }

        @Synchronized
        private fun expire(key: String, pooled: Pooled) {
            if (pooled.refs > 0 || pool[key] !== pooled) return
            pool.remove(key)
            SmbSource.closeLater(pooled.source)
        }
    }
}
