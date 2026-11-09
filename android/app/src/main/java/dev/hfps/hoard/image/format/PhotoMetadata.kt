package dev.hfps.hoard.image.format

import dev.hfps.hoard.image.tiff.Rational
import kotlin.math.roundToInt

/** EXIF essentials for the culling overlay, burst grouping and sorting. Every field is optional. */
data class PhotoMetadata(
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    /** "YYYY:MM:DD HH:MM:SS" as written by the camera (local time of the camera's clock). */
    val captureTime: String? = null,
    /** Sub-second digits ("733" = .733 s). */
    val subSecond: String? = null,
    /** "+HH:MM" / "-HH:MM" when the camera wrote it (EXIF 2.31 OffsetTimeOriginal). */
    val offsetTime: String? = null,
    val exposureTime: Rational? = null,
    val fNumber: Double? = null,
    val iso: Int? = null,
    val focalLength: Double? = null,
    val focalLength35: Int? = null,
    /** EXIF orientation 1..8 (1 = upright). */
    val orientation: Int = 1,
    /** 0..5 (-1 = rejected) from the TIFF Rating tag or XMP xmp:Rating, when present. */
    val rating: Int? = null,
    /** Pixel size of the full image (camera JPEG frame, or EXIF PixelX/YDimension for RAWs). */
    val width: Int? = null,
    val height: Int? = null,
) {
    /** Milliseconds of the capture time read as UTC (sortable, comparable within one camera clock), + sub-seconds. */
    val captureSortMillis: Long? get() = captureTime?.let { ExifTime.localMillis(it) }?.plus(ExifTime.subSecondMillis(subSecond))

    /** Real instant when the offset is known. */
    val captureEpochMillis: Long? get() {
        val local = captureSortMillis ?: return null
        val offset = offsetTime?.let { ExifTime.offsetMinutes(it) } ?: return null
        return local - offset * 60_000L
    }

    /** "1/250", "0.5", "2" seconds. */
    val exposureLabel: String? get() {
        val e = exposureTime ?: return null
        if (e.numerator <= 0 || e.denominator <= 0) return null
        val seconds = e.numerator.toDouble() / e.denominator
        return when {
            seconds >= 1 -> if (seconds == seconds.toLong().toDouble()) "${seconds.toLong()}" else "%.1f".format(seconds)
            e.numerator == 1L -> "1/${e.denominator}"
            else -> "1/${(1 / seconds).roundToInt()}"
        }
    }

    /** Swapped width/height for orientations 5..8. */
    val isTransposed: Boolean get() = orientation in 5..8

    /** Fills gaps in this from [other] (e.g. RAW header + embedded JPEG's EXIF). */
    fun orElse(other: PhotoMetadata): PhotoMetadata = PhotoMetadata(
        make = make ?: other.make,
        model = model ?: other.model,
        lens = lens ?: other.lens,
        captureTime = captureTime ?: other.captureTime,
        subSecond = subSecond ?: other.subSecond,
        offsetTime = offsetTime ?: other.offsetTime,
        exposureTime = exposureTime ?: other.exposureTime,
        fNumber = fNumber ?: other.fNumber,
        iso = iso ?: other.iso,
        focalLength = focalLength ?: other.focalLength,
        focalLength35 = focalLength35 ?: other.focalLength35,
        orientation = if (orientation != 1) orientation else other.orientation,
        rating = rating ?: other.rating,
        width = width ?: other.width,
        height = height ?: other.height,
    )
}

/** EXIF date arithmetic without java.time (minSdk 24 has no java.time without desugaring). */
object ExifTime {
    private val pattern = Regex("""(\d{4})[:\-](\d{2})[:\-](\d{2})[ T](\d{2}):(\d{2}):(\d{2})""")

    fun localMillis(value: String): Long? {
        val m = pattern.find(value) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        val year = y.toInt()
        val month = mo.toInt()
        val day = d.toInt()
        if (year == 0 || month !in 1..12 || day !in 1..31) return null
        return (daysFromCivil(year, month, day) * 86_400L + h.toInt() * 3600L + mi.toInt() * 60L + s.toInt()) * 1000L
    }

    fun subSecondMillis(value: String?): Long {
        val digits = value?.trim()?.takeWhile { it.isDigit() }.orEmpty()
        if (digits.isEmpty()) return 0
        return (digits + "00").take(3).toLong()
    }

    fun offsetMinutes(value: String): Int? {
        val m = Regex("""^([+-])(\d{2}):(\d{2})$""").find(value.trim()) ?: return null
        val minutes = m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
        return if (m.groupValues[1] == "-") -minutes else minutes
    }

    /** Days since 1970-01-01 (Howard Hinnant's algorithm). */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
}
