package dev.hfps.hoard.image.tiff

import dev.hfps.hoard.image.source.RandomAccessSource
import dev.hfps.hoard.image.source.readRange

/** Segments of a JPEG header the core cares about (positions absolute in the source). */
data class JpegHeader(
    /** Frame size from SOF0/1/2 (baseline/extended/progressive: what Android decodes). 0 when absent. */
    val width: Int,
    val height: Int,
    /** SOF marker found (0xC0…0xCF), or 0. SOF3 = lossless (RAW data, not displayable). */
    val sofMarker: Int,
    /** TIFF header inside APP1 "Exif\0\0", or -1. */
    val exifTiffOffset: Long,
    /** XMP packet inside APP1, or null. */
    val xmpOffset: Long,
    val xmpLength: Int,
) {
    val isDisplayable: Boolean get() = sofMarker in DISPLAYABLE_SOF && width > 0 && height > 0

    companion object {
        val DISPLAYABLE_SOF = setOf(0xC0, 0xC1, 0xC2)
    }
}

/** Walks a JPEG's marker segments from [start] up to the first SOS, reading only marker headers. */
object JpegMarkers {

    private const val XMP_NS = "http://ns.adobe.com/xap/1.0/\u0000"

    fun startsWithSoi(source: RandomAccessSource, start: Long): Boolean {
        val b = source.readRange(start, 3)
        return b.size == 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
    }

    /** Null when [start] is not a JPEG. [end] bounds the walk (embedded JPEG length). */
    fun read(source: RandomAccessSource, start: Long, end: Long = source.size, maxSegments: Int = 128): JpegHeader? {
        if (!startsWithSoi(source, start)) return null
        var p = start + 2
        var width = 0
        var height = 0
        var sof = 0
        var exif = -1L
        var xmp = -1L
        var xmpLength = 0
        var segments = 0
        while (p + 4 <= end && segments++ < maxSegments) {
            val head = source.readRange(p, 4)
            if (head.size < 4) break
            if (head[0] != 0xFF.toByte()) break
            val marker = head[1].toInt() and 0xFF
            if (marker == 0xFF) { p += 1; continue } // fill byte
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { p += 2; continue }
            if (marker == 0xD9 || marker == 0xDA) break // EOI / SOS: header is over
            val length = ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
            if (length < 2) break
            val data = p + 4
            when {
                marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC && sof == 0 -> {
                    val frame = source.readRange(data, 5)
                    if (frame.size == 5) {
                        height = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
                        width = ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
                        sof = marker
                    }
                }
                marker == 0xE1 -> {
                    val id = source.readRange(data, minOf(length - 2, XMP_NS.length))
                    if (exif < 0 && id.size >= 6 && String(id, 0, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
                        exif = data + 6
                    } else if (xmp < 0 && id.size == XMP_NS.length && String(id, Charsets.ISO_8859_1) == XMP_NS) {
                        xmp = data + XMP_NS.length
                        xmpLength = length - 2 - XMP_NS.length
                    }
                }
            }
            p = data + length - 2
        }
        return JpegHeader(width, height, sof, exif, xmp, xmpLength)
    }
}
