package dev.hfps.hoard.image.source

import com.hierynomus.smbj.share.PipelinedFileReader
import java.io.EOFException
import java.io.OutputStream
import com.hierynomus.smbj.share.File as SmbFile

/**
 * An open smbj file. Every [read] is one SMB READ (one round trip), so wrap it in a
 * [BlockCachedSource] before parsing headers. [ownsFile] closes the handle with the source.
 * Whole-file copies (staging a camera JPEG or a RAW) keep 4 READs in flight ([PipelinedFileReader]).
 */
class SmbFileSource(
    private val file: SmbFile,
    override val size: Long = file.fileInformation.standardInformation.endOfFile,
    private val ownsFile: Boolean = false,
) : BulkCopySource {

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        if (length == 0) return 0
        val wanted = minOf(length.toLong(), size - position, MAX_READ.toLong()).toInt()
        val n = file.read(buffer, position, offset, wanted)
        return if (n <= 0) -1 else n
    }

    override fun copyAllTo(out: OutputStream, cancel: CancelToken) {
        val reader = PipelinedFileReader(file, 0, size)
        try {
            while (reader.position < size) {
                cancel.throwIfCancelled()
                val chunk = reader.next() ?: break
                out.write(chunk)
            }
        } finally {
            reader.abandon()
        }
        if (reader.position < size) throw EOFException("The remote file ended early")
    }

    override fun close() {
        if (ownsFile) file.close()
    }

    private companion object {
        // Below the smallest MaxReadSize servers negotiate (64 KiB on old Samba is rare; 1 MiB typical).
        const val MAX_READ = 1 shl 20
    }
}
