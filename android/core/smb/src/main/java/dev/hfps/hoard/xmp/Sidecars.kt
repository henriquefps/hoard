package dev.hfps.hoard.xmp

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.transfer.PartNames
import dev.hfps.hoard.smb.transfer.TransferFs
import dev.hfps.hoard.smb.transfer.TransferPaths
import java.io.ByteArrayOutputStream

/** What happened to one sidecar write (ADR 0011). */
sealed class SidecarWrite {
    /** Written as [name] (in the shot's folder); [modifiedMillis] is its mtime after the write, when known. */
    data class Written(val name: String, val modifiedMillis: Long?) : SidecarWrite()

    /** The existing sidecar [name] is not well-formed XMP: left byte for byte as it was. */
    data class Malformed(val name: String, val message: String) : SidecarWrite()

    /** Reading or writing failed ([error] carries the contract code; `ACCESS_DENIED` = the folder is read-only). */
    data class Failed(val name: String, val error: HoardSmbException) : SidecarWrite()
}

/**
 * Reading and writing a shot's sidecar through any [TransferFs] (the transfer engine's endpoints: SMB, paths, SAF):
 * the culling writer and reader (`smb/cull/CullSidecars`) and "Copy picks" at the destination (`TransferRunner`)
 * all go through here. Blocking; callers serialize writes to one sidecar. Pure apart from [fs] (JVM-tested with
 * `MemoryFs`).
 */
object Sidecars {
    /** A sidecar bigger than this is not read (Lightroom's are ~10-100 KB): treated as malformed, never rewritten. */
    const val MAX_BYTES = 4 * 1024 * 1024

    /** Reads [path] whole. `TOO_LARGE` beyond [MAX_BYTES]. */
    fun readBytes(fs: TransferFs, path: String): ByteArray {
        val out = ByteArrayOutputStream()
        fs.openRead(path, 0).use { stream ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = stream.read(buffer, 0, buffer.size)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) throw HoardSmbException(ErrorCode.TOO_LARGE, "${TransferPaths.name(path)} is larger than $MAX_BYTES bytes")
            }
        }
        return out.toByteArray()
    }

    /** The sidecar at [path] parsed; throws [XmpException] when it is not well-formed XMP. */
    fun read(fs: TransferFs, path: String): XmpRead = XmpReader.read(readBytes(fs, path))

    /**
     * Writes [fields] into the sidecar of a shot of [stem] in [folder]: merged into [existing] (the name the reader
     * picked, see [SidecarNames.find]) when there is one, else a new `<stem>.xmp` (`.<stem>.xmp` when [hidden]).
     * Atomic: the new bytes go to a partial file that is then renamed over the sidecar (the transfer engine's
     * `.<name>.hoardpart`). A malformed sidecar, or one that cannot be read, is never written.
     */
    fun write(
        fs: TransferFs,
        folder: String,
        stem: String,
        existing: String?,
        fields: XmpFields,
        hidden: Boolean,
        creatorTool: String,
        date: String,
    ): SidecarWrite {
        val name = SidecarNames.target(stem, existing, hidden)
        val path = TransferPaths.join(folder, name)
        val part = TransferPaths.join(folder, PartNames.of(name))
        return try {
            val current = if (existing != null) readBytes(fs, path) else null
            val bytes = try {
                XmpWriter.update(current, fields, creatorTool, date)
            } catch (e: XmpException) {
                return SidecarWrite.Malformed(name, e.message ?: "Not well-formed XMP")
            }
            val writer = fs.openWrite(part, 0)
            try {
                writer.write(bytes, 0, bytes.size)
                writer.finish()
            } finally {
                runCatching { writer.close() }
            }
            if (!fs.rename(part, path, true)) throw HoardSmbException(ErrorCode.UNKNOWN, "Could not name $name")
            SidecarWrite.Written(name, runCatching { fs.stat(path)?.modifiedMillis }.getOrNull())
        } catch (e: InterruptedException) {
            throw e
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            runCatching { fs.delete(part) }
            SidecarWrite.Failed(name, fs.mapError(e))
        }
    }

    private const val BUFFER = 64 * 1024
}

/** `xmp:CreatorTool` of the sidecars Hoard creates: `Hoard <version>` (the version is set when the plugin loads). */
object SidecarTool {
    @Volatile var version: String = ""

    val creatorTool: String get() = if (version.isBlank()) "Hoard" else "Hoard $version"
}
