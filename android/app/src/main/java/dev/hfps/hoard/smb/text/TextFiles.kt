package dev.hfps.hoard.smb.text

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.PipelinedFileReader
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.smb.SmbAccess
import dev.hfps.hoard.smb.SmbBrowser
import dev.hfps.hoard.smb.SmbEntry
import dev.hfps.hoard.smb.SmbErrors
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.SmbSession
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.viewer.RemoteFiles
import java.io.ByteArrayOutputStream
import java.util.EnumSet
import java.util.UUID

/** What `readTextFile` resolves with. */
data class TextRead(
    val text: String,
    val encoding: String,
    val size: Long,
    val modifiedAt: String?,
    val truncated: Boolean,
    /** The account's access to the file (share and file level combined); null when unknown. */
    val access: SmbAccess? = null,
)

/**
 * `readTextFile` / `writeTextFile`: small text files as strings for the built-in editor, the one
 * deliberate exception to "bytes never cross the bridge" (ADR 0001), bounded by `maxBytes`.
 */
object TextFiles {

    const val DEFAULT_MAX_BYTES = 5 * 1024 * 1024
    const val HARD_LIMIT = 16 * 1024 * 1024

    fun read(session: SmbSession, share: String, path: String, maxBytes: Int, truncate: Boolean): TextRead {
        val clean = SmbPaths.normalize(path)
        if (clean.isEmpty()) smbFail(ErrorCode.UNKNOWN, "Cannot read a folder as text")
        val limit = maxBytes.coerceIn(1, HARD_LIMIT)
        val (file, mask) = RemoteFiles.openReadWithAccess(session, share, clean)
        val access = SmbAccess.combine(SmbBrowser.shareAccessOrNull(session, share), SmbAccess.fromMask(mask))
        file.use { remote ->
            val info = RemoteFiles.info(remote)
            if (info.isDirectory) smbFail(ErrorCode.UNKNOWN, "Cannot read a folder as text")
            if (info.size > limit && !truncate) {
                smbFail(ErrorCode.TOO_LARGE, "${SmbPaths.name(clean)} is ${info.size} bytes (limit $limit)", mapOf("size" to info.size))
            }
            val truncated = info.size > limit
            // One byte past the cut when truncating, so the cut can tell whether it split a character.
            val length = if (truncated) limit + 1L else info.size
            val out = ByteArrayOutputStream(length.toInt())
            val reader = PipelinedFileReader(remote, 0, length)
            while (reader.position < length) {
                val chunk = reader.next() ?: break
                out.write(chunk)
            }
            val bytes = out.toByteArray()
            val decoded = (if (truncated) TextCodec.decodePrefix(bytes, limit) else TextCodec.decode(bytes))
                ?: smbFail(ErrorCode.NOT_TEXT, "${SmbPaths.name(clean)} is not a text file")
            return TextRead(decoded.text, decoded.encoding, info.size, IsoTime.fromSmbMillis(info.modifiedMillis), truncated, access)
        }
    }

    /**
     * Save [text]. `create` writes a new file (FILE_CREATE, `ALREADY_EXISTS` when taken).
     * `overwrite` checks [expectedModifiedAt] (`CONFLICT`), writes a dot-named temporary file in the
     * same folder and renames it over the target, so a dropped connection never leaves half a file.
     * Shares that refuse new files (or a target held open without delete sharing) fall back to
     * overwriting in place. The entry is read after every handle is closed: servers update the
     * last write time on close, and that value is the next `expectedModifiedAt`.
     */
    fun write(
        session: SmbSession,
        shareName: String,
        path: String,
        text: String,
        encoding: String,
        mode: String,
        expectedModifiedAt: String?,
    ): SmbEntry {
        val clean = SmbPaths.normalize(path)
        if (clean.isEmpty()) smbFail(ErrorCode.UNKNOWN, "A file path is required")
        val name = SmbPaths.requireValidName(SmbPaths.name(clean), "file name")
        val bytes = TextCodec.encode(text, encoding)
        val share = session.share(shareName)
        val target = SmbPaths.toSmb(clean)

        if (mode == "create") {
            writeFile(share, target, bytes, SMB2CreateDisposition.FILE_CREATE)
            return SmbBrowser.stat(session, shareName, clean)
        }

        checkUnchanged(share, target, expectedModifiedAt)
        val temp = SmbPaths.toSmb(SmbPaths.join(SmbPaths.parent(clean), ".$name.hoard-${UUID.randomUUID().toString().take(8)}.tmp"))
        val renamed = try {
            share.openFile(
                temp,
                EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
                // Not FILE_ATTRIBUTE_HIDDEN: attributes travel with the rename, and the saved
                // document would turn hidden. The leading dot hides the temp file in listings.
                null,
                EnumSet.noneOf(SMB2ShareAccess::class.java),
                SMB2CreateDisposition.FILE_CREATE,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { file ->
                if (bytes.isNotEmpty()) file.write(bytes, 0)
                // A last look right before replacing: narrows the window for a concurrent save.
                checkUnchanged(share, target, expectedModifiedAt)
                try {
                    file.rename(target, true)
                    true
                } catch (e: Exception) {
                    if (SmbErrors.map(e).code != ErrorCode.ACCESS_DENIED) throw e
                    file.deleteOnClose()
                    false
                }
            }
        } catch (e: HoardSmbException) {
            throw e
        } catch (e: Exception) {
            // No right to create files here (a drop box, a share that only allows edits): fall
            // back to overwriting in place. Anything else (disk full, connection lost) is real.
            if (SmbErrors.map(e).code != ErrorCode.ACCESS_DENIED) {
                runCatching { share.rm(temp) }
                throw e
            }
            false
        }
        if (!renamed) writeFile(share, target, bytes, SMB2CreateDisposition.FILE_OVERWRITE_IF)
        return SmbBrowser.stat(session, shareName, clean)
    }

    /** `CONFLICT` unless the target's last write time is [expected] (skipped when null). */
    private fun checkUnchanged(share: DiskShare, target: String, expected: String?) {
        if (expected == null) return
        val current = try {
            IsoTime.fromSmbMillis(share.getFileInformation(target).basicInformation.lastWriteTime?.toEpochMillis())
        } catch (e: Exception) {
            if (SmbErrors.map(e).code == ErrorCode.NOT_FOUND) {
                throw HoardSmbException(ErrorCode.CONFLICT, "The file was deleted on the server", data = mapOf("modifiedAt" to null))
            }
            throw e
        }
        if (!sameInstant(current, expected)) {
            throw HoardSmbException(
                ErrorCode.CONFLICT,
                "The file changed on the server since it was opened",
                data = mapOf("modifiedAt" to current),
            )
        }
    }

    /** Compares ISO timestamps by instant (JS may hand back the same moment written differently). */
    fun sameInstant(a: String?, b: String?): Boolean {
        if (a == b) return true
        val left = IsoTime.parse(a) ?: return false
        val right = IsoTime.parse(b) ?: return false
        return left == right
    }

    private fun writeFile(share: DiskShare, smbPath: String, bytes: ByteArray, disposition: SMB2CreateDisposition) {
        share.openFile(
            smbPath,
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.FILE_READ_ATTRIBUTES),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            disposition,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { file ->
            if (bytes.isNotEmpty()) file.write(bytes, 0)
        }
    }
}
