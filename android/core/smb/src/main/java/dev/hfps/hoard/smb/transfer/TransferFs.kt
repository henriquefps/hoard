package dev.hfps.hoard.smb.transfer

import dev.hfps.hoard.smb.HoardSmbException
import java.io.Closeable

/** What a transfer needs to know about a file or folder. */
data class FileStat(val name: String, val isDirectory: Boolean, val size: Long, val modifiedMillis: Long?)

/** Sequential reader from an offset. */
interface ReadStream : Closeable {
    /** Up to [length] bytes into [buffer]; -1 at the end. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/** Sequential writer; [position] is the number of bytes known to be in the file (the resume point). */
interface WriteStream : Closeable {
    val position: Long
    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** Wait for every pending write and make the bytes durable (fsync where the endpoint can). */
    fun finish()
}

/**
 * One endpoint of a transfer (ADR 0007), with paths relative to its root (`""` = the root). Blocking;
 * called on the worker thread. Implementations map their own failures to [HoardSmbException]
 * ([mapError]) so the engine can tell a dropped route (retry) from a missing file (item fails) from
 * a full or unplugged destination (the job stops).
 */
interface TransferFs : Closeable {
    /** Shown in notifications and errors ("NAS · media", "USB drive"). */
    val label: String

    /** True when failures may be a dropped route the engine should retry (SMB). */
    val isNetwork: Boolean get() = false

    /** The address in use (SMB). */
    val activeHost: String? get() = null

    fun stat(path: String): FileStat?
    fun list(path: String): List<FileStat>

    /** Create the folder [path] (its parent exists). An existing folder is fine. */
    fun mkdir(path: String)

    fun openRead(path: String, offset: Long): ReadStream

    /** Open [path] for writing at [offset]: 0 truncates (or creates); >0 keeps exactly [offset] bytes and appends. */
    fun openWrite(path: String, offset: Long): WriteStream

    /** Delete a file or an empty folder. A missing one is fine. */
    fun delete(path: String)

    /**
     * Move [from] to [to] inside this endpoint (any folders), replacing an existing file at [to]
     * when [replace]. Returns false when the endpoint cannot do it as one cheap step (the caller
     * then copies). Rejects `ALREADY_EXISTS` when [to] exists and not [replace].
     */
    fun rename(from: String, to: String, replace: Boolean): Boolean

    /**
     * The existing file at [path] may be written (a sidecar handed to Reveal with a write grant, ADR 0010). Checked
     * without changing it; true when the endpoint cannot tell (a write then fails the usual way).
     */
    fun canWrite(path: String): Boolean = true

    /** Keep the source's modified time; false when the endpoint cannot. */
    fun setModified(path: String, millis: Long): Boolean = false

    /** Free bytes where [path] lives, null when unknown. */
    fun freeBytes(path: String): Long? = null

    /**
     * Copy [length] bytes of [from] (in this same endpoint) at [offset] into [to]'s writer position,
     * without the bytes crossing the phone (SMB server-side copy). Null when unsupported.
     */
    fun serverCopy(from: String, to: String, offset: Long, length: Long): Boolean = false

    /** Drop the connection so the next call reconnects (SMB; [addressIndex] is the one to use). */
    fun reconnect(addressIndex: Int) {}

    /** Number of addresses to fail over across (SMB), 1 elsewhere. */
    val addressCount: Int get() = 1

    fun mapError(error: Throwable): HoardSmbException

    override fun close() {}
}
