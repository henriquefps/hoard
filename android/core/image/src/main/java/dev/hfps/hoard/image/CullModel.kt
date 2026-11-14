package dev.hfps.hoard.image

import dev.hfps.hoard.image.format.ListedFile
import dev.hfps.hoard.image.format.PhotoMetadata
import dev.hfps.hoard.image.format.ShotPairing

/** A file of a culling folder as listed: name, path inside the location, size, modified time. */
data class CullFileRef(val name: String, val path: String, val size: Long, val modifiedAt: String?, val modifiedMillis: Long)

/** One shot of a culling folder: RAW, camera JPEG/HEIF, or both (a pair is one shot). */
data class CullShotRef(
    /** Lowercase stem: unique in the folder, stable across visits (decisions are keyed by it). */
    val id: String,
    val stem: String,
    val raw: CullFileRef?,
    val rendered: CullFileRef?,
    val others: List<CullFileRef>,
) {
    /** `pair`, `raw` (RAW only) or `jpeg` (camera rendering only, JPEG or HEIF). */
    val kind: String get() = when {
        raw != null && rendered != null -> "pair"
        raw != null -> "raw"
        else -> "jpeg"
    }
}

/** Pure culling-folder logic (JVM-tested). */
object CullModel {

    /** Pairs a folder listing into shots ([ShotPairing]), keeping each file's path and times. */
    fun shots(files: List<CullFileRef>): List<CullShotRef> {
        val byName = files.associateBy { it.name }
        return ShotPairing.pair(files.map { ListedFile(it.name, it.size, it.modifiedMillis) }).map { shot ->
            CullShotRef(
                id = shot.stem.lowercase(),
                stem = shot.stem,
                raw = shot.raw?.let { byName[it.name] },
                rendered = shot.rendered?.let { byName[it.name] },
                others = shot.others.mapNotNull { byName[it.name] },
            )
        }
    }

    /** `YYYY-MM-DDTHH:MM:SS(.mmm)` from EXIF's `YYYY:MM:DD HH:MM:SS` + sub-seconds (camera clock, no zone). */
    fun captureTime(metadata: PhotoMetadata): String? {
        val raw = metadata.captureTime ?: return null
        val m = Regex("""(\d{4})[:\-](\d{2})[:\-](\d{2})[ T](\d{2}):(\d{2}):(\d{2})""").find(raw) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        if (y == "0000" || mo == "00" || d == "00") return null
        val sub = metadata.subSecond?.trim()?.takeWhile { it.isDigit() }.orEmpty()
        val fraction = if (sub.isEmpty()) "" else "." + (sub + "00").take(3)
        return "$y-$mo-${d}T$h:$mi:$s$fraction"
    }

    /** Upright full-image size from EXIF (width/height swap for orientations 5..8). */
    fun uprightSize(metadata: PhotoMetadata): Pair<Int, Int>? {
        val w = metadata.width ?: return null
        val h = metadata.height ?: return null
        if (w <= 0 || h <= 0) return null
        return if (metadata.isTransposed) h to w else w to h
    }
}
