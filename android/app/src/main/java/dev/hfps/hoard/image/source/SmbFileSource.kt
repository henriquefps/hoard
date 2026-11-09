package dev.hfps.hoard.image.source

import com.hierynomus.smbj.share.File as SmbFile

/**
 * An open smbj file. Every [read] is one SMB READ (one round trip), so wrap it in a
 * [BlockCachedSource] before parsing headers. [ownsFile] closes the handle with the source.
 */
class SmbFileSource(
    private val file: SmbFile,
    override val size: Long = file.fileInformation.standardInformation.endOfFile,
    private val ownsFile: Boolean = false,
) : RandomAccessSource {

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        if (length == 0) return 0
        val wanted = minOf(length.toLong(), size - position, MAX_READ.toLong()).toInt()
        val n = file.read(buffer, position, offset, wanted)
        return if (n <= 0) -1 else n
    }

    override fun close() {
        if (ownsFile) file.close()
    }

    private companion object {
        // Below the smallest MaxReadSize servers negotiate (64 KiB on old Samba is rare; 1 MiB typical).
        const val MAX_READ = 1 shl 20
    }
}
