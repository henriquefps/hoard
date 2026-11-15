package dev.hfps.hoard.xmp

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.smb.transfer.TransferFs
import java.util.Locale

/**
 * "Export XMP" (ADR 0011, amendment "2.2.0: export on demand"): the only way Hoard writes sidecars since 2.2.0. Each
 * shot's culling fields go into its sidecar in [folder] of [fs] through the shared writer ([Sidecars.write]): merged
 * into the shot's existing sidecar (Hoard's naming rules, [SidecarNames]) or a new `<stem>.xmp` (`.<stem>.xmp` when
 * hidden). A malformed sidecar is left as it is; only the culling fields are written (rating, pick/reject, label).
 * Pure apart from [fs] (JVM-tested with `MemoryFs`).
 */
object XmpExport {
    /** One shot to export: its stem, its files' names (to find a `<file name>.xmp`), and the fields to write. */
    data class Shot(val stem: String, val fileNames: List<String>, val fields: XmpFields)

    data class Result(
        val written: Int = 0,
        /** Existing sidecars that are not well-formed XMP: left byte for byte. */
        val malformed: Int = 0,
        val failed: Int = 0,
        /** The folder refused a write (`ACCESS_DENIED`): nothing more was tried there; ask for another folder. */
        val readOnly: Boolean = false,
        /** Names written, in order (for the folder's culling state). */
        val names: Map<String, Pair<String, Long?>> = emptyMap(),
    ) {
        operator fun plus(other: Result) = Result(
            written + other.written, malformed + other.malformed, failed + other.failed, readOnly || other.readOnly, names + other.names,
        )
    }

    fun export(
        fs: TransferFs,
        folder: String,
        shots: List<Shot>,
        hidden: Boolean,
        creatorTool: String = SidecarTool.creatorTool,
        date: String = IsoTime.now(),
        isCancelled: () -> Boolean = { false },
    ): Result {
        if (shots.isEmpty()) return Result()
        val names = fs.list(folder).filter { !it.isDirectory && SidecarNames.isSidecar(it.name) }
            .associateTo(HashMap()) { it.name.lowercase(Locale.ROOT) to it.name }
        var written = 0
        var malformed = 0
        var failed = 0
        val out = LinkedHashMap<String, Pair<String, Long?>>()
        for (shot in shots) {
            if (isCancelled()) break
            val existing = SidecarNames.find(shot.stem, shot.fileNames) { names[it] }
            when (val result = Sidecars.write(fs, folder, shot.stem, existing, shot.fields, hidden, creatorTool, date)) {
                is SidecarWrite.Written -> {
                    written++
                    names[result.name.lowercase(Locale.ROOT)] = result.name
                    out[shot.stem] = result.name to result.modifiedMillis
                }
                is SidecarWrite.Malformed -> malformed++
                is SidecarWrite.Failed -> {
                    if (result.error.code == ErrorCode.ACCESS_DENIED || result.error.code == ErrorCode.PERMISSION_REVOKED) {
                        return Result(written, malformed, failed, readOnly = true, names = out)
                    }
                    failed++
                }
            }
        }
        return Result(written, malformed, failed, readOnly = false, names = out)
    }
}
