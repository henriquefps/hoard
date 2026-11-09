package dev.hfps.hoard.image.tiff

import dev.hfps.hoard.image.source.RandomAccessSource
import dev.hfps.hoard.image.source.readRange
import java.io.IOException

/** One IFD entry. [valueOffset] is absolute in the source (inline values point into the entry). */
data class TiffEntry(val tag: Int, val type: Int, val count: Long, val valueOffset: Long) {
    val byteLength: Long get() = count * TiffReader.typeSize(type)
}

/** One image file directory. */
data class Ifd(val offset: Long, val entries: Map<Int, TiffEntry>, val next: Long) {
    operator fun get(tag: Int): TiffEntry? = entries[tag]
    operator fun contains(tag: Int): Boolean = tag in entries
}

/**
 * Minimal, defensive TIFF/EXIF structure reader over a [RandomAccessSource]: reads only the IFDs and
 * values it is asked for. [base] is where the TIFF header sits (0 for TIFF-based RAWs, the APP1
 * payload in a JPEG, a box in CR3); every offset inside the TIFF is relative to it.
 *
 * Accepts the standard magic 42 and the RAW variants: Olympus "RO"/"RS" (ORF) and 0x55 (RW2).
 */
class TiffReader(
    private val source: RandomAccessSource,
    val base: Long,
    val littleEndian: Boolean,
    val firstIfd: Long,
) {

    /** Reads the IFD at [relativeOffset] (relative to [base]), or null when it is out of range/garbage. */
    fun ifd(relativeOffset: Long): Ifd? {
        if (relativeOffset < 8 || base + relativeOffset + 2 > source.size) return null
        val start = base + relativeOffset
        val countBytes = source.readRange(start, 2)
        if (countBytes.size < 2) return null
        val count = u16(countBytes, 0)
        if (count == 0 || count > MAX_ENTRIES) return null
        val body = source.readRange(start + 2, count * 12 + 4)
        if (body.size < count * 12) return null
        val entries = LinkedHashMap<Int, TiffEntry>(count)
        for (i in 0 until count) {
            val p = i * 12
            val tag = u16(body, p)
            val type = u16(body, p + 2)
            val n = u32(body, p + 4)
            val size = typeSize(type)
            if (size == 0) continue
            val total = n * size
            val valueOffset = if (total <= 4) start + 2 + p + 8 else base + u32(body, p + 8)
            if (total > 4 && valueOffset + total > source.size) continue
            entries.putIfAbsent(tag, TiffEntry(tag, type, n, valueOffset))
        }
        val next = if (body.size >= count * 12 + 4) u32(body, count * 12) else 0
        return Ifd(start, entries, next)
    }

    /** IFD0 and its `next` chain (IFD1 …), loop- and length-guarded. */
    fun chain(first: Long = firstIfd, max: Int = 8): List<Ifd> {
        val out = ArrayList<Ifd>()
        val seen = HashSet<Long>()
        var offset = first
        while (offset != 0L && out.size < max && seen.add(offset)) {
            val ifd = ifd(offset) ?: break
            out += ifd
            offset = ifd.next
        }
        return out
    }

    /** Unsigned integer value [index] of a BYTE/SHORT/LONG/SSHORT/SLONG/IFD entry. */
    fun int(entry: TiffEntry?, index: Int = 0): Long? {
        if (entry == null || index >= entry.count) return null
        val size = typeSize(entry.type)
        val bytes = source.readRange(entry.valueOffset + index * size, size)
        if (bytes.size < size) return null
        return when (entry.type) {
            TYPE_BYTE, TYPE_UNDEFINED -> (bytes[0].toLong() and 0xFF)
            TYPE_SBYTE -> bytes[0].toLong()
            TYPE_SHORT -> u16(bytes, 0).toLong()
            TYPE_SSHORT -> u16(bytes, 0).toShort().toLong()
            TYPE_LONG, TYPE_IFD -> u32(bytes, 0)
            TYPE_SLONG -> u32(bytes, 0).toInt().toLong()
            else -> null
        }
    }

    /** All integer values of an entry (capped). */
    fun ints(entry: TiffEntry?, max: Int = 64): LongArray {
        if (entry == null) return LongArray(0)
        val n = minOf(entry.count, max.toLong()).toInt()
        return LongArray(n) { int(entry, it) ?: 0 }
    }

    /** RATIONAL/SRATIONAL value [index] as numerator/denominator. */
    fun rational(entry: TiffEntry?, index: Int = 0): Rational? {
        if (entry == null || index >= entry.count) return null
        if (entry.type != TYPE_RATIONAL && entry.type != TYPE_SRATIONAL) {
            return int(entry, index)?.let { Rational(it, 1) }
        }
        val bytes = source.readRange(entry.valueOffset + index * 8L, 8)
        if (bytes.size < 8) return null
        return if (entry.type == TYPE_SRATIONAL) {
            Rational(u32(bytes, 0).toInt().toLong(), u32(bytes, 4).toInt().toLong())
        } else {
            Rational(u32(bytes, 0), u32(bytes, 4))
        }
    }

    /** ASCII value, trimmed of NULs and spaces; null when empty. */
    fun string(entry: TiffEntry?, max: Int = 256): String? {
        if (entry == null || (entry.type != TYPE_ASCII && entry.type != TYPE_UNDEFINED && entry.type != TYPE_BYTE)) return null
        val bytes = source.readRange(entry.valueOffset, minOf(entry.count, max.toLong()).toInt())
        val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
        return String(bytes, 0, end, Charsets.ISO_8859_1).trim().ifEmpty { null }
    }

    fun bytes(entry: TiffEntry?, max: Int = 4096): ByteArray =
        if (entry == null) ByteArray(0) else source.readRange(entry.valueOffset, minOf(entry.byteLength, max.toLong()).toInt())

    fun u16(b: ByteArray, p: Int): Int =
        if (littleEndian) (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
        else ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

    fun u32(b: ByteArray, p: Int): Long =
        if (littleEndian) (u16(b, p).toLong()) or (u16(b, p + 2).toLong() shl 16)
        else (u16(b, p).toLong() shl 16) or u16(b, p + 2).toLong()

    companion object {
        /** Reads the TIFF header at [base]. */
        fun open(source: RandomAccessSource, base: Long = 0): TiffReader {
            val header = source.readRange(base, 8)
            if (header.size < 8) throw TiffException("Too short for a TIFF header")
            val littleEndian = when {
                header[0] == 'I'.code.toByte() && header[1] == 'I'.code.toByte() -> true
                header[0] == 'M'.code.toByte() && header[1] == 'M'.code.toByte() -> false
                else -> throw TiffException("Not a TIFF byte order")
            }
            val probe = TiffReader(source, base, littleEndian, 0)
            val magic = probe.u16(header, 2)
            if (magic != 42 && magic != 0x4F52 && magic != 0x5352 && magic != 0x55) throw TiffException("Bad TIFF magic $magic")
            return TiffReader(source, base, littleEndian, probe.u32(header, 4))
        }

        const val TYPE_BYTE = 1
        const val TYPE_ASCII = 2
        const val TYPE_SHORT = 3
        const val TYPE_LONG = 4
        const val TYPE_RATIONAL = 5
        const val TYPE_SBYTE = 6
        const val TYPE_UNDEFINED = 7
        const val TYPE_SSHORT = 8
        const val TYPE_SLONG = 9
        const val TYPE_SRATIONAL = 10
        const val TYPE_FLOAT = 11
        const val TYPE_DOUBLE = 12
        const val TYPE_IFD = 13
        private const val MAX_ENTRIES = 1000

        fun typeSize(type: Int): Int = when (type) {
            TYPE_BYTE, TYPE_ASCII, TYPE_SBYTE, TYPE_UNDEFINED -> 1
            TYPE_SHORT, TYPE_SSHORT -> 2
            TYPE_LONG, TYPE_SLONG, TYPE_FLOAT, TYPE_IFD -> 4
            TYPE_RATIONAL, TYPE_SRATIONAL, TYPE_DOUBLE -> 8
            else -> 0
        }
    }
}

data class Rational(val numerator: Long, val denominator: Long) {
    val value: Double? get() = if (denominator == 0L) null else numerator.toDouble() / denominator
}

class TiffException(message: String) : IOException(message)

/** Tags the core reads. */
object Tags {
    const val NEW_SUBFILE_TYPE = 0x00FE
    const val IMAGE_WIDTH = 0x0100
    const val IMAGE_HEIGHT = 0x0101
    const val COMPRESSION = 0x0103
    const val MAKE = 0x010F
    const val MODEL = 0x0110
    const val STRIP_OFFSETS = 0x0111
    const val ORIENTATION = 0x0112
    const val STRIP_BYTE_COUNTS = 0x0117
    const val DATE_TIME = 0x0132
    const val SUB_IFDS = 0x014A
    const val JPEG_OFFSET = 0x0201
    const val JPEG_LENGTH = 0x0202
    const val PANASONIC_JPG_FROM_RAW = 0x002E
    const val RATING = 0x4746
    const val EXIF_IFD = 0x8769
    const val EXPOSURE_TIME = 0x829A
    const val F_NUMBER = 0x829D
    const val ISO = 0x8827
    const val RECOMMENDED_EXPOSURE_INDEX = 0x8832
    const val ISO_SPEED = 0x8833
    const val DATE_TIME_ORIGINAL = 0x9003
    const val OFFSET_TIME_ORIGINAL = 0x9011
    const val SUB_SEC_TIME_ORIGINAL = 0x9291
    const val MAKER_NOTE = 0x927C
    const val FOCAL_LENGTH = 0x920A
    const val PIXEL_X = 0xA002
    const val PIXEL_Y = 0xA003
    const val FOCAL_LENGTH_35 = 0xA405
    const val LENS_MAKE = 0xA433
    const val LENS_MODEL = 0xA434
    const val DNG_LENS_INFO = 0xC630
    const val DNG_UNIQUE_MODEL = 0xC614
    const val OLYMPUS_CAMERA_SETTINGS = 0x2020
    const val OLYMPUS_PREVIEW_START = 0x0101
    const val OLYMPUS_PREVIEW_LENGTH = 0x0102
}
