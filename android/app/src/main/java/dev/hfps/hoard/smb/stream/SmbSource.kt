package dev.hfps.hoard.smb.stream

import android.util.Log
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.hierynomus.smbj.share.PipelinedFileReader
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.SmbConnector
import dev.hfps.hoard.smb.SmbCredentials
import dev.hfps.hoard.smb.SmbErrors
import dev.hfps.hoard.smb.SmbHandle
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.viewer.RemoteFileInfo
import dev.hfps.hoard.smb.viewer.RemoteFiles
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * What a stream token or a served folder reads from: an SMB share ([SmbSource], its own
 * connection) or a local location (`location.LocalStreamSource`). Thread-safe; one
 * [StreamReader] per file descriptor or HTTP response.
 */
interface StreamSource : Closeable {
    /** Name of the source's root for the directory index (the share, the volume). */
    val label: String

    /** Metadata of [path], or null when it does not exist. Folders included. */
    fun stat(path: String): RemoteFileInfo?

    /** Names in folder [path] (`isDirectory` per entry). */
    fun list(path: String): List<Pair<String, Boolean>>

    /** Random-access reads of the file at [path] ([size] bytes); [fetched] counts bytes read from storage. */
    fun reader(path: String, size: Long, fetched: AtomicLong): StreamReader
}

/** Random-access reads of one file. Not thread-safe: one per FD or per HTTP response. */
interface StreamReader : Closeable {
    /** Up to [length] bytes at [offset] into [out] at [outOffset]; fewer only at the end, 0 past it. */
    fun read(offset: Long, length: Int, out: ByteArray, outOffset: Int = 0): Int
}

/** One address of the machine, in priority order (the route in use first). */
data class StreamAddress(val host: String, val port: Int)

/**
 * The SMB side of a stream or a served folder: its **own** authenticated connection to one share,
 * independent of the UI's sessions (the UI may disconnect or the WebView may be gone while VLC
 * plays). When the connection drops it reconnects, moving on to the next address, re-checking the
 * pinned server GUID before credentials are sent (same rules as `connect` and transfers).
 *
 * Thread-safe: several readers (proxy FDs, HTTP requests) share it.
 */
class SmbSource(
    private val machineId: String,
    private val addresses: List<StreamAddress>,
    private val expectedServerGuid: String?,
    val share: String,
    private val credentials: (String) -> SmbCredentials?,
) : StreamSource {

    override val label: String get() = share

    override fun reader(path: String, size: Long, fetched: AtomicLong): StreamReader = RemoteReader(this, path, size, fetched)

    private var handle: SmbHandle? = null
    private var diskShare: DiskShare? = null
    private var addressIndex = 0
    @Volatile private var closed = false

    /** Bumped on every reconnect, so concurrent readers that failed together reconnect once. */
    @Volatile var generation = 0
        private set

    /** Host currently (or last) used. */
    val activeHost: String? get() = addresses.getOrNull(addressIndex)?.host

    init {
        require(addresses.isNotEmpty()) { "addresses must not be empty" }
    }

    /** The connected share, connecting (trying each address once, from the current one) if needed. */
    @Synchronized
    fun share(): DiskShare {
        if (closed) throw IOException("Stream closed")
        val current = diskShare
        if (current != null && current.isConnected && handle?.isAlive == true) return current
        drop()
        var last: Throwable? = null
        repeat(addresses.size) { step ->
            val index = (addressIndex + step) % addresses.size
            val address = addresses[index]
            try {
                val opened = runBlocking {
                    SmbConnector.open(address.host, address.port, expectedServerGuid, CONNECT_TIMEOUT_MS) { credentials(machineId) }
                }
                val connected = try {
                    opened.session.connectShare(share) as? DiskShare ?: smbFail(ErrorCode.NOT_FOUND, "$share is not a disk share")
                } catch (e: Throwable) {
                    closeLater(opened)
                    throw e
                }
                handle = opened
                diskShare = connected
                addressIndex = index
                return connected
            } catch (e: Throwable) {
                val mapped = SmbErrors.map(e)
                Log.w(TAG, "Stream connect via ${address.host}: ${mapped.code} ${mapped.message}")
                // The server answered and refused: another address reaches the same server, same answer.
                if (mapped.code in FATAL) throw mapped
                last = mapped
            }
        }
        throw last ?: HoardSmbException(ErrorCode.UNREACHABLE, "No address answered")
    }

    /**
     * A read failed on the connection of [seenGeneration]: drop it (once) so the next [share] call
     * reconnects, starting with the next address when this one is dead.
     */
    @Synchronized
    fun invalidate(seenGeneration: Int) {
        if (seenGeneration != generation) return
        generation++
        if (handle?.isAlive != true) addressIndex = (addressIndex + 1) % addresses.size
        drop()
    }

    /** Open [path] (share-relative) for reading, with metadata. */
    fun open(path: String): Pair<SmbFile, RemoteFileInfo> {
        val file = share().openFile(
            SmbPaths.toSmb(path),
            EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        return try {
            file to RemoteFiles.info(file)
        } catch (e: Throwable) {
            closeLater(file)
            throw e
        }
    }

    /** Metadata of [path], or null when it does not exist. Folders included. */
    override fun stat(path: String): RemoteFileInfo? = try {
        val info = share().getFileInformation(SmbPaths.toSmb(path))
        RemoteFileInfo(
            size = info.standardInformation.endOfFile,
            modifiedMillis = info.basicInformation.lastWriteTime?.toEpochMillis(),
            isDirectory = info.standardInformation.isDirectory,
        )
    } catch (e: Throwable) {
        if (SmbErrors.map(e).code == ErrorCode.NOT_FOUND) null else throw e
    }

    /** Names in folder [path] (`isDirectory` per entry), for the optional directory index. */
    override fun list(path: String): List<Pair<String, Boolean>> =
        share().list(SmbPaths.toSmb(path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map { it.fileName to ((it.fileAttributes and DIRECTORY_ATTRIBUTE) != 0L) }

    private fun drop() {
        diskShare = null
        handle?.let(::closeLater)
        handle = null
    }

    override fun close() {
        synchronized(this) {
            closed = true
            drop()
        }
    }

    companion object {
        private const val TAG = "HoardStream"
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val DIRECTORY_ATTRIBUTE = 0x10L
        internal val FATAL = setOf(ErrorCode.AUTH_FAILED, ErrorCode.GUID_MISMATCH, ErrorCode.ACCESS_DENIED, ErrorCode.NOT_FOUND)

        private val closer = Executors.newCachedThreadPool { runnable -> Thread(runnable, "hoard-stream-close").apply { isDaemon = true } }

        /** SMB2 CLOSE / LOGOFF can block on a dead link (request timeout); never on a reader thread. */
        fun closeLater(resource: Closeable) = closer.execute { runCatching { resource.close() } }
    }
}

/**
 * Random-access reads of one remote file for a proxy file descriptor or an HTTP response, with a
 * read-ahead pipeline: sequential reads (playback) keep up to [MAX_DEPTH] 1 MiB SMB READs in
 * flight; a seek starts a fresh pipeline at depth 1, so a lone small read near the end of the
 * file (an MP4 `moov` atom, a Matroska cue table) costs one round trip, not four.
 *
 * Failures (dropped connection, stalled route) reconnect through [source] and reopen the file,
 * up to [MAX_ATTEMPTS] times per read. Not thread-safe: one reader per FD / per response.
 */
class RemoteReader(
    private val source: SmbSource,
    private val path: String,
    val size: Long,
    /** Adds every byte fetched from the server (read-ahead included): the stream statistics. */
    private val fetched: AtomicLong = AtomicLong(),
) : StreamReader {

    private var file: SmbFile? = null
    private var generation = -1
    private var pipeline: PipelinedFileReader? = null

    /** The chunk last returned by the pipeline, and where it starts in the file. */
    private var chunk: ByteArray? = null
    private var chunkOffset = 0L
    private var sequentialChunks = 0

    @Volatile private var closed = false

    /**
     * Read up to [length] bytes at [offset] into [out] at [outOffset]. Returns the count (fewer only
     * at the end of the file; 0 at or past the end).
     */
    override fun read(offset: Long, length: Int, out: ByteArray, outOffset: Int): Int {
        if (offset >= size || length <= 0) return 0
        val wanted = minOf(length.toLong(), size - offset).toInt()
        var done = 0
        while (done < wanted) {
            val n = readSome(offset + done, wanted - done, out, outOffset + done)
            if (n <= 0) break
            done += n
        }
        return done
    }

    private fun readSome(offset: Long, length: Int, out: ByteArray, outOffset: Int): Int {
        var failures = 0
        while (true) {
            if (closed) throw IOException("Stream closed")
            try {
                return copyFromChunk(offset, length, out, outOffset) ?: run {
                    fetchUntil(offset)
                    copyFromChunk(offset, length, out, outOffset) ?: 0
                }
            } catch (e: Throwable) {
                if (closed) throw IOException("Stream closed", e)
                val mapped = SmbErrors.map(e)
                failures++
                Log.w(TAG, "Read $path @$offset failed (${mapped.code}: ${mapped.message}), attempt $failures")
                resetFile()
                if (mapped.code in SmbSource.FATAL || failures >= MAX_ATTEMPTS) {
                    throw IOException(mapped.message, mapped)
                }
                source.invalidate(generation)
                Thread.sleep(RETRY_DELAY_MS * failures)
            }
        }
    }

    private fun copyFromChunk(offset: Long, length: Int, out: ByteArray, outOffset: Int): Int? {
        val data = chunk ?: return null
        if (offset < chunkOffset || offset >= chunkOffset + data.size) return null
        val from = (offset - chunkOffset).toInt()
        val n = minOf(length, data.size - from)
        System.arraycopy(data, from, out, outOffset, n)
        return n
    }

    /** Advance (or restart) the pipeline until the current chunk covers [offset]. */
    private fun fetchUntil(offset: Long) {
        var reader = pipeline
        if (reader == null || offset < reader.position || offset - reader.position > SKIP_WINDOW) {
            reader?.abandon()
            reader = PipelinedFileReader(openFile(), offset, size, depth = 1, timeoutMs = STALL_TIMEOUT_MS)
            pipeline = reader
            sequentialChunks = 0
            chunk = null
        }
        while (true) {
            val start = reader.position
            val data = reader.next() ?: throw IOException("$path ended at $start of $size bytes")
            fetched.addAndGet(data.size.toLong())
            chunk = data
            chunkOffset = start
            if (++sequentialChunks >= 2) reader.depth = MAX_DEPTH
            if (offset < start + data.size) return
        }
    }

    private fun openFile(): SmbFile {
        file?.let { return it }
        val seen = source.generation
        val (opened, _) = source.open(path)
        file = opened
        generation = seen
        return opened
    }

    private fun resetFile() {
        pipeline?.abandon()
        pipeline = null
        chunk = null
        file?.let(SmbSource::closeLater)
        file = null
    }

    override fun close() {
        closed = true
        resetFile()
    }

    companion object {
        private const val TAG = "HoardStream"
        const val MAX_DEPTH = 4
        /** A forward jump this small is read through instead of restarting the pipeline. */
        private const val SKIP_WINDOW = 2L * 1024 * 1024
        private const val STALL_TIMEOUT_MS = 15_000L
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 500L
    }
}
