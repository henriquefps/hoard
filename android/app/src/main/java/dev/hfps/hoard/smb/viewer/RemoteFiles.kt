package dev.hfps.hoard.smb.viewer

import android.os.StatFs
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File as SmbFile
import com.hierynomus.smbj.share.PipelinedFileReader
import com.hierynomus.smbj.share.HoardOpens
import dev.hfps.hoard.smb.Cancellation
import dev.hfps.hoard.smb.SmbAccess
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.SmbSession
import dev.hfps.hoard.smb.smbFail
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.EnumSet

/** Metadata of a remote file, used for cache keys. */
data class RemoteFileInfo(val size: Long, val modifiedMillis: Long?, val isDirectory: Boolean)

/** Shared helpers for openFile and thumbnail: open a remote file, download it, key caches. */
object RemoteFiles {

    fun openRead(session: SmbSession, share: String, path: String): SmbFile =
        session.share(share).openFile(
            SmbPaths.toSmb(path),
            EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )

    /** [openRead] with the file's maximal access ("MxAc"; null when the server does not answer). */
    fun openReadWithAccess(session: SmbSession, share: String, path: String): Pair<SmbFile, Long?> {
        val opened = HoardOpens.openWithMaximalAccess(
            session.share(share),
            SmbPaths.toSmb(path),
            EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        val file = opened.entry as? SmbFile ?: run {
            opened.entry.closeSilently()
            smbFail(ErrorCode.UNKNOWN, "Not a file: $path")
        }
        return file to SmbAccess.parseMaximalAccessResponse(opened.maximalAccessData)
    }

    fun info(file: SmbFile): RemoteFileInfo {
        val all = file.fileInformation
        return RemoteFileInfo(
            size = all.standardInformation.endOfFile,
            modifiedMillis = all.basicInformation.lastWriteTime?.toEpochMillis(),
            isDirectory = all.standardInformation.isDirectory,
        )
    }

    /**
     * Copy [length] bytes from the start of [remote] into [target] (via a temp file + rename, so a
     * half-written file is never mistaken for a cached one). Checks free space first.
     */
    fun download(remote: SmbFile, length: Long, target: File, cancellation: Cancellation = Cancellation.NONE) {
        target.parentFile?.mkdirs()
        val free = runCatching { StatFs(target.parentFile!!.path).availableBytes }.getOrNull()
        if (free != null && free < length + 16L * 1024 * 1024) {
            smbFail(ErrorCode.NO_SPACE, "Not enough free space to cache ${target.name}")
        }
        val temp = File(target.parentFile, ".${target.name}.part")
        try {
            FileOutputStream(temp).use { out ->
                val reader = PipelinedFileReader(remote, 0, length)
                while (reader.position < length) {
                    cancellation.check()
                    val chunk = reader.next() ?: break
                    out.write(chunk)
                }
                if (reader.position < length) throw IOException("The remote file ended early")
                out.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("Could not move ${temp.name} into place")
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    /** Read up to [length] bytes from the start of [remote] into memory. */
    fun head(remote: SmbFile, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = remote.read(buffer, filled.toLong(), filled, length - filled)
            if (read <= 0) break
            filled += read
        }
        return if (filled == length) buffer else buffer.copyOf(filled)
    }

    fun key(vararg parts: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it.toString().toByteArray(Charsets.UTF_8)); digest.update(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(40)
    }

    /**
     * Delete the least recently used entries of [dir] until it holds at most [maxBytes], never
     * touching [keep]. Entries are direct children (files or folders).
     */
    fun trim(dir: File, maxBytes: Long, keep: File? = null) {
        val entries = dir.listFiles()?.map { it to sizeOf(it) } ?: return
        var total = entries.sumOf { it.second }
        if (total <= maxBytes) return
        for ((entry, size) in entries.sortedBy { it.first.lastModified() }) {
            if (total <= maxBytes) break
            if (keep != null && (entry == keep || keep.path.startsWith(entry.path + File.separator))) continue
            if (entry.deleteRecursively()) total -= size
        }
    }

    private fun sizeOf(file: File): Long = if (file.isDirectory) file.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else file.length()
}
