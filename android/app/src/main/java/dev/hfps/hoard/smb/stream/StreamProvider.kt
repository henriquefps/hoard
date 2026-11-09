package dev.hfps.hoard.smb.stream

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.FileNotFoundException
import java.io.IOException

/**
 * `content://<applicationId>.stream/<token>/<file name>`: a seekable, read-only view of a remote
 * file for the app the user picked (VLC, MX Player, a PDF reader…), without downloading it first.
 *
 * [openFile] returns a proxy file descriptor (`StorageManager.openProxyFileDescriptor`, Android 8+):
 * the kernel turns the other app's `read`/`pread`/`lseek` into [ProxyFileDescriptorCallback.onRead]
 * calls on a thread of ours, served by a [RemoteReader] (SMB READs with read-ahead). Each FD gets its
 * own thread and its own SMB file handle; [ProxyFileDescriptorCallback.onRelease] closes both.
 *
 * Not exported: other apps only get in through the one-off read grant on the intent, and the token
 * (256 random bits) ends after inactivity ([StreamExpiry]); unknown tokens are FileNotFound.
 */
class StreamProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let(StreamRegistry::init)
        return true
    }

    private fun entryFor(uri: Uri): StreamEntry? =
        StreamRegistry.get(uri.pathSegments.firstOrNull())?.takeIf { it.kind == StreamKind.FILE }

    override fun getType(uri: Uri): String? = entryFor(uri)?.mimeType

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val entry = entryFor(uri) ?: return null
        Log.d(TAG, "query ${projection?.joinToString()} by ${callingPackage}")
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) entry.name else entry.size })
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Streams are read-only")
        val entry = entryFor(uri) ?: throw FileNotFoundException("Unknown or expired stream")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) throw FileNotFoundException("Streaming needs Android 8")
        return openProxy(entry)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openProxy(entry: StreamEntry): ParcelFileDescriptor {
        val storage = context!!.getSystemService(StorageManager::class.java)
        val thread = HandlerThread("hoard-stream-fd").apply { start() }
        val reader = RemoteReader(entry.source, entry.path, entry.size, entry.fetched)
        entry.openFds.incrementAndGet()
        entry.touch()
        try {
            return storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                Callback(entry, reader, thread),
                Handler(thread.looper),
            )
        } catch (e: Throwable) {
            entry.openFds.decrementAndGet()
            reader.close()
            thread.quitSafely()
            throw FileNotFoundException("Could not open the stream: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private class Callback(
        private val entry: StreamEntry,
        private val reader: RemoteReader,
        private val thread: HandlerThread,
    ) : ProxyFileDescriptorCallback() {

        override fun onGetSize(): Long = entry.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            entry.touch()
            try {
                val n = reader.read(offset, size, data)
                entry.served.addAndGet(n.toLong())
                return n
            } catch (e: IOException) {
                Log.w(TAG, "Stream read of ${entry.name} @$offset failed: ${e.message}")
                throw ErrnoException("onRead", OsConstants.EIO)
            }
        }

        override fun onRelease() {
            reader.close()
            entry.openFds.decrementAndGet()
            entry.touch()
            Log.i(TAG, "FD released: ${entry.name} size=${entry.size} fetched=${entry.fetched.get()} served=${entry.served.get()}")
            thread.quitSafely()
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private const val TAG = "HoardStream"
        fun authority(packageName: String) = "$packageName.stream"
    }
}
