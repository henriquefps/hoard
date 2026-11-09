package dev.hfps.hoard.smb.location

import android.net.Uri
import dev.hfps.hoard.smb.Cancellation
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.FolderListing
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.SmbBrowser
import dev.hfps.hoard.smb.SmbEntry
import dev.hfps.hoard.smb.SmbErrors
import dev.hfps.hoard.smb.SmbSession
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.text.TextCodec
import dev.hfps.hoard.smb.text.TextFiles
import dev.hfps.hoard.smb.text.TextRead
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException

/**
 * The file operations every location supports (ADR 0005). Blocking; called on an IO thread.
 * Implementations: [SmbFs] (a share on a session), [PathFs] (plain paths, all files access) and
 * [SafFs] (a volume's Storage Access Framework tree).
 */
interface HoardFs {
    fun list(path: String, cancellation: Cancellation = Cancellation.NONE): FolderListing
    fun stat(path: String): SmbEntry
    fun createFolder(path: String, name: String): SmbEntry
    fun rename(path: String, newName: String): SmbEntry
    fun delete(path: String, recursive: Boolean, cancellation: Cancellation = Cancellation.NONE)
    fun readText(path: String, maxBytes: Int, truncate: Boolean): TextRead
    fun writeText(path: String, text: String, encoding: String, mode: String, expectedModifiedAt: String?): SmbEntry
}

/** SMB: the existing smbj code, unchanged, behind the common interface. */
class SmbFs(private val session: SmbSession, private val share: String) : HoardFs {
    override fun list(path: String, cancellation: Cancellation) = SmbBrowser.listWithAccess(session, share, path)
    override fun stat(path: String) = SmbBrowser.stat(session, share, path)
    override fun createFolder(path: String, name: String) = SmbBrowser.createFolder(session, share, path, name)
    override fun rename(path: String, newName: String) = SmbBrowser.rename(session, share, path, newName)
    override fun delete(path: String, recursive: Boolean, cancellation: Cancellation) =
        SmbBrowser.delete(session, share, path, recursive)
    override fun readText(path: String, maxBytes: Int, truncate: Boolean) = TextFiles.read(session, share, path, maxBytes, truncate)
    override fun writeText(path: String, text: String, encoding: String, mode: String, expectedModifiedAt: String?) =
        TextFiles.write(session, share, path, text, encoding, mode, expectedModifiedAt)
}

/** A local file found by [LocalFs.file]: how to hand it to a decoder, a viewer or the web server. */
class LocalFile(
    val name: String,
    val size: Long,
    val modifiedMillis: Long?,
    val isDirectory: Boolean,
    /** The file by path ([PathFs]); null for SAF documents. */
    val file: File?,
    /** The SAF document ([SafFs]); null for path files. */
    val documentUri: Uri?,
)

/** A device or volume location. */
interface LocalFs : HoardFs {
    /** Stable id for cache keys (`device`, `volume:<uuid>`). */
    val cacheId: String

    /** Root name for directory indexes ("Internal storage", the volume label). */
    val label: String

    /** The file or folder at [path] (`NOT_FOUND` when missing). */
    fun file(path: String): LocalFile

    /** Names in folder [path] for the web server's index (`isDirectory` per entry). */
    fun names(path: String): List<Pair<String, Boolean>> =
        list(path).entries.map { it.name to it.isDirectory }

    /** Opens [file] for reading (path or SAF). */
    fun openInput(file: LocalFile): InputStream
}

/** Exceptions from java.io / java.nio / SAF providers → contract codes. */
object LocalErrors {
    fun map(error: Throwable, what: String? = null): HoardSmbException {
        if (error is HoardSmbException) return error
        val message = listOfNotNull(what, error.message).joinToString(": ").ifEmpty { error.javaClass.simpleName }
        val code = when {
            error is NoSuchFileException || error is FileNotFoundException && isMissing(error) -> ErrorCode.NOT_FOUND
            error is NotDirectoryException -> ErrorCode.NOT_FOUND
            error is FileAlreadyExistsException -> ErrorCode.ALREADY_EXISTS
            error is AccessDeniedException || error is SecurityException -> ErrorCode.ACCESS_DENIED
            error is FileNotFoundException && (error.message?.contains("EACCES") == true || error.message?.contains("EPERM") == true) ->
                ErrorCode.ACCESS_DENIED
            error is FileNotFoundException && error.message?.contains("EROFS") == true -> ErrorCode.ACCESS_DENIED
            SmbErrors.isNoSpace(error) -> ErrorCode.NO_SPACE
            error is FileNotFoundException -> ErrorCode.NOT_FOUND
            error is IOException && error.message?.contains("EIO") == true -> ErrorCode.UNAVAILABLE
            else -> ErrorCode.UNKNOWN
        }
        return HoardSmbException(code, message, error)
    }

    private fun isMissing(error: FileNotFoundException) = error.message?.contains("ENOENT") == true

    inline fun <T> guard(what: String? = null, block: () -> T): T = try {
        block()
    } catch (e: HoardSmbException) {
        throw e
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw map(e, what)
    }
}

/** Text reading shared by [PathFs] and [SafFs] (same rules as the SMB `TextFiles.read`). */
internal object LocalText {
    fun read(input: InputStream, size: Long, name: String, maxBytes: Int, truncate: Boolean): Pair<String, String> {
        val limit = maxBytes.coerceIn(1, TextFiles.HARD_LIMIT)
        if (size > limit && !truncate) {
            smbFail(ErrorCode.TOO_LARGE, "$name is $size bytes (limit $limit)", mapOf("size" to size))
        }
        val truncated = size > limit
        val length = if (truncated) limit + 1 else size.toInt()
        val bytes = input.use { readUpTo(it, length) }
        val decoded = (if (truncated) TextCodec.decodePrefix(bytes, limit) else TextCodec.decode(bytes))
            ?: smbFail(ErrorCode.NOT_TEXT, "$name is not a text file")
        return decoded.text to decoded.encoding
    }

    private fun readUpTo(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val n = input.read(buffer, filled, length - filled)
            if (n <= 0) break
            filled += n
        }
        return if (filled == length) buffer else buffer.copyOf(filled)
    }

    /** `CONFLICT` unless [currentMillis] is [expected] (skipped when null). */
    fun checkUnchanged(currentIso: String?, exists: Boolean, expected: String?) {
        if (expected == null) return
        if (!exists) {
            throw HoardSmbException(ErrorCode.CONFLICT, "The file was deleted", data = mapOf("modifiedAt" to null))
        }
        if (!TextFiles.sameInstant(currentIso, expected)) {
            throw HoardSmbException(ErrorCode.CONFLICT, "The file changed since it was opened", data = mapOf("modifiedAt" to currentIso))
        }
    }
}
