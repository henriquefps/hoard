package dev.hfps.hoard.smb.transfer

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.smbFail
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * What [ServerTransferFs] needs of hoard-server (ADR 0024 A2): `GET /v1/originals/{fingerprint}` by range. Implemented
 * over the server client in `:native` (`:core:smb` does not know it). Failures the engine must tell apart are thrown
 * as [HoardSmbException] (`NOT_FOUND` for a `404`, `AUTH_FAILED` for a revoked key); a network failure is any
 * [IOException] (retried).
 */
interface ServerOriginals {
    /** `HEAD /v1/originals/{fingerprint}`: the size, or null when the server does not hold it (`404`). */
    fun size(fingerprint: String): Long?

    /** The original from [offset] to its end (`Range: bytes=<offset>-`). */
    fun open(fingerprint: String, offset: Long): InputStream
}

/**
 * hoard-server's originals as a read-only transfer source ([Endpoint.Server], ADR 0024 A2 "Save to this device", ADR
 * 0007: no second copy path): paths are `<fingerprint>/<file name>`, so a job's items keep the file's own name at the
 * destination. A read that ends before the size the server announced is a dropped connection (retried and resumed at
 * the byte written, as SMB is), never a short file. [modified] gives a file's modified time (the phone's, from the
 * item's location) so the copy keeps it.
 */
class ServerTransferFs(
    override val label: String,
    private val originals: ServerOriginals,
    private val modified: (fingerprint: String) -> Long? = { null },
) : TransferFs {
    override val isNetwork: Boolean get() = true

    /** Sizes asked this job (one `HEAD` per original). */
    private val sizes = HashMap<String, Long?>()

    private fun split(path: String): Pair<String, String> {
        val clean = path.trim('/')
        val fingerprint = clean.substringBefore('/')
        val name = clean.substringAfter('/', "")
        return fingerprint to name
    }

    private fun sizeOf(fingerprint: String): Long? = synchronized(sizes) { sizes[fingerprint] } ?: originals.size(fingerprint).also { synchronized(sizes) { sizes[fingerprint] = it } }

    override fun stat(path: String): FileStat? {
        val (fingerprint, name) = split(path)
        if (fingerprint.isEmpty()) return FileStat(label, true, 0, null)
        val size = sizeOf(fingerprint) ?: return null
        if (name.isEmpty()) return FileStat(fingerprint, true, 0, null)
        if (name.contains('/')) return null
        return FileStat(name, false, size, modified(fingerprint))
    }

    /** Nothing is listed: a job names each file it copies. */
    override fun list(path: String): List<FileStat> = emptyList()

    override fun openRead(path: String, offset: Long): ReadStream {
        val (fingerprint, name) = split(path)
        if (fingerprint.isEmpty() || name.isEmpty()) smbFail(ErrorCode.NOT_FOUND, "$path is not a file")
        val size = sizeOf(fingerprint) ?: smbFail(ErrorCode.NOT_FOUND, "$name is no longer on the server")
        if (offset >= size) return EmptyRead
        val input = originals.open(fingerprint, offset)
        return RangeRead(input, size - offset)
    }

    private object EmptyRead : ReadStream {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun close() {}
    }

    /** Reads [remaining] bytes; an end before them is a dropped connection, not the end of the file. */
    private class RangeRead(private val input: InputStream, private var remaining: Long) : ReadStream {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val n = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (n < 0) throw IOException("The server closed the connection with $remaining bytes left")
            remaining -= n
            return n
        }

        override fun close() {
            runCatching { input.close() }
        }
    }

    private fun readOnly(): Nothing = smbFail(ErrorCode.ACCESS_DENIED, "The photo server is read-only here")

    override fun mkdir(path: String) = readOnly()
    override fun openWrite(path: String, offset: Long): WriteStream = readOnly()
    override fun delete(path: String) = readOnly()
    override fun rename(from: String, to: String, replace: Boolean): Boolean = readOnly()
    override fun canWrite(path: String): Boolean = false

    /** A new attempt asks the sizes again (the original may have gone meanwhile). */
    override fun reconnect(addressIndex: Int) = synchronized(sizes) { sizes.clear() }

    override fun mapError(error: Throwable): HoardSmbException = when (error) {
        is HoardSmbException -> error
        is SocketTimeoutException -> HoardSmbException(ErrorCode.TIMEOUT, error.message ?: "The photo server did not answer", error)
        is InterruptedIOException -> HoardSmbException(ErrorCode.TIMEOUT, error.message ?: "The photo server did not answer", error)
        is IOException -> HoardSmbException(ErrorCode.UNREACHABLE, error.message ?: "The photo server cannot be reached", error)
        else -> HoardSmbException(ErrorCode.UNKNOWN, error.message ?: error.javaClass.simpleName, error)
    }
}
