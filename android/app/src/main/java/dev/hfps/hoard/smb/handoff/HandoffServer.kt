package dev.hfps.hoard.smb.handoff

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
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
 * Every open takes a short lease in [StreamRegistry] ([LEASE_IDLE_MS] after the last read), which keeps the
 * streaming service (and so the process, un-frozen) up while Reveal reads, then lets it stop.
 */
object HandoffServer {
    private const val TAG = "HoardHandoff"
    private const val STORE_FILE = "reveal-handoffs.json"

    /** A lease (and the service) ends this long after Reveal last read through a link. */
    const val LEASE_IDLE_MS = 2 * 60_000L

    @Volatile private var app: Context? = null
    private lateinit var store: HandoffStore
    private lateinit var credentials: CredentialStore

    @Synchronized
    fun init(context: Context) {
        if (app != null) return
        val appContext = context.applicationContext
        store = HandoffStore(File(appContext.noBackupFilesDir, STORE_FILE))
        credentials = CredentialStore(appContext)
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
     * with what went wrong (Reveal treats the photo as unreadable and falls back to its preview). A write on a photo:
     * [SecurityException]. Runs on a binder thread (never the main thread); connecting may take a few seconds.
     */
    fun open(uri: Uri, mode: String, caller: String?): ParcelFileDescriptor {
        val item = item(uri) ?: throw FileNotFoundException("This photo is no longer shared with Reveal")
        val access = try {
            HandoffAccess.of(mode, item.sidecar)
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException(e.message)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) throw FileNotFoundException("Streaming needs Android 8")
        Log.i(TAG, "open ${item.name} ($mode) for $caller")
        val machine = store.machine(item.machineId)
            ?: throw FileNotFoundException("${item.name}: its machine is no longer in Hoard")
        if (machine.serverGuid == null) throw FileNotFoundException("${item.name}: the machine is not pinned yet")
        if (!credentials.has(machine.id)) throw FileNotFoundException("${item.name}: no login stored for the machine")
        val source = Sources.acquire(machine, item.share.orEmpty(), credentials)
        try {
            val info = try {
                source.stat(item.path)
            } catch (e: Throwable) {
                throw unreadable(item, SmbErrors.map(e))
            }
            source.smb.activeAddress?.let { store.setLastGood(machine.id, it) }
            if (info?.isDirectory == true) throw FileNotFoundException("${item.name} is a folder now")
            store.touch(item.id)
            return if (access == HandoffAccess.READ) {
                info ?: throw FileNotFoundException("${item.name} is no longer on the share")
                store.updateFile(item.id, info.size, info.modifiedMillis)
                StreamProvider.openProxy(context(), lease(item, machine), info.size)
            } else {
                openWrite(item, machine, source, info, access)
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

    private fun unreadable(item: HandoffItem, error: HoardSmbException): FileNotFoundException {
        val why = when (error.code) {
            ErrorCode.UNREACHABLE, ErrorCode.TIMEOUT -> "the machine is not reachable"
            ErrorCode.AUTH_FAILED -> "the machine refused the login"
            ErrorCode.GUID_MISMATCH -> "no address reaches the pinned server"
            ErrorCode.ACCESS_DENIED -> "access to the share was denied"
            ErrorCode.NOT_FOUND -> "the share or the file is gone"
            else -> error.message ?: error.code.name
        }
        Log.w(TAG, "${item.name} not readable: ${error.code}")
        return FileNotFoundException("${item.name}: $why (${error.code})")
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
                throw unreadable(item, SmbErrors.map(e))
            }
        }
        val buffer = SidecarBuffer(initial, append = access == HandoffAccess.APPEND)
        val entry = lease(item, machine)
        val writer = Sources.acquire(machine, item.share.orEmpty(), credentials)
        val thread = HandlerThread("hoard-handoff-xmp").apply { start() }
        entry.openFds.incrementAndGet()
        try {
            return context().getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_WRITE,
                SidecarCallback(item, buffer, writer, entry, thread),
                Handler(thread.looper),
            )
        } catch (e: Throwable) {
            entry.openFds.decrementAndGet()
            writer.close()
            thread.quitSafely()
            throw FileNotFoundException("Could not open ${item.name}: ${e.message}")
        }
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
        Log.i(TAG, "${item.name} written by Reveal (${bytes.size} bytes)")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private class SidecarCallback(
        private val item: HandoffItem,
        private val buffer: SidecarBuffer,
        private val source: Sources.Handle,
        private val entry: StreamEntry,
        private val thread: HandlerThread,
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
                thread.quitSafely()
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
     * One SMB connection per machine + share for every lease and write of the links (a batch Reveal reads file after
     * file reuses it); reference-counted, closed with the last [Handle].
     */
    internal object Sources {
        private class Pooled(val source: SmbSource) {
            var refs = 0
        }

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
            val key = "${machine.id}|${share.lowercase()}"
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
            pooled.refs++
            return Handle(key, pooled.source)
        }

        @Synchronized
        private fun release(key: String) {
            val pooled = pool[key] ?: return
            if (--pooled.refs > 0) return
            pool.remove(key)
            SmbSource.closeLater(pooled.source)
        }
    }
}
