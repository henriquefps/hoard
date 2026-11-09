package dev.hfps.hoard.image.format

import dev.hfps.hoard.image.source.BlockCachedSource
import dev.hfps.hoard.image.source.RandomAccessSource
import dev.hfps.hoard.image.source.readRange
import dev.hfps.hoard.image.tiff.Ifd
import dev.hfps.hoard.image.tiff.JpegMarkers
import dev.hfps.hoard.image.tiff.Tags
import dev.hfps.hoard.image.tiff.TiffReader

/** A JPEG stored inside another file (RAW preview, EXIF thumbnail). Positions are absolute. */
data class EmbeddedImage(
    val offset: Long,
    val length: Long,
    val width: Int,
    val height: Int,
    /** Where it was found, for logs and the ADR ("IFD0 JPEGInterchangeFormat", "CR3 PRVW", …). */
    val origin: String,
) {
    val pixels: Long get() = width.toLong() * height
}

/** What one read of a file's header tells: type, EXIF essentials and embedded JPEGs (largest first). */
data class Inspection(
    val type: ImageType,
    val metadata: PhotoMetadata,
    val previews: List<EmbeddedImage>,
) {
    val largestPreview: EmbeddedImage? get() = previews.firstOrNull()

    /** The smallest preview whose longest side is at least [minLongSide], else the largest. */
    fun previewFor(minLongSide: Int): EmbeddedImage? =
        previews.filter { maxOf(it.width, it.height) >= minLongSide }.minByOrNull { it.pixels } ?: largestPreview
}

/**
 * Reads file type, EXIF and embedded previews from the header ranges only:
 *
 * - TIFF-based RAWs (ARW, NEF, DNG, CR2, PEF, SRW, ORF, RW2): IFD0 chain + SubIFDs + EXIF IFD;
 *   previews from JPEGInterchangeFormat, single-strip JPEG IFDs, Panasonic JpgFromRaw and the
 *   Olympus maker note.
 * - CR3 (ISO BMFF): CMT1/CMT2 TIFFs, THMB, the PRVW uuid box and the full-size JPEG track.
 * - RAF: the JPEG at the header's offset (and its EXIF).
 * - JPEG: APP1 EXIF + XMP rating + SOF size; the EXIF thumbnail as a preview.
 *
 * Wraps the source in a [BlockCachedSource], so a header costs a few block reads.
 */
object ImageInspector {

    /** First bytes fetched in one read: covers IFD0/EXIF of every camera seen so far (ZV-E10: 116 KB). */
    const val HEAD_PREFETCH = 128 * 1024

    fun inspect(source: RandomAccessSource, name: String): Inspection {
        val cached = source as? BlockCachedSource ?: BlockCachedSource(source)
        cached.prefetch(0, minOf(HEAD_PREFETCH.toLong(), cached.size).toInt())
        val head = cached.readRange(0, ImageFormats.MAGIC_BYTES)
        val type = ImageFormats.classify(name, head)
        val (metadata, previews) = when {
            type.kind == ImageKind.JPEG -> jpeg(cached)
            type.raw == RawFormat.CR3 -> cr3(cached)
            type.raw == RawFormat.RAF -> raf(cached)
            type.isRaw -> tiffRaw(cached)
            else -> PhotoMetadata() to emptyList()
        }
        val sorted = previews
            .filter { it.width > 0 && it.height > 0 }
            .distinctBy { it.offset }
            .sortedByDescending { it.pixels }
        return Inspection(type, metadata, sorted)
    }

    // --- JPEG --------------------------------------------------------------------------------

    private fun jpeg(source: RandomAccessSource): Pair<PhotoMetadata, List<EmbeddedImage>> {
        val header = JpegMarkers.read(source, 0) ?: return PhotoMetadata() to emptyList()
        var metadata = PhotoMetadata(width = header.width.takeIf { it > 0 }, height = header.height.takeIf { it > 0 })
        val previews = ArrayList<EmbeddedImage>()
        if (header.exifTiffOffset >= 0) {
            runCatching {
                val tiff = TiffReader.open(source, header.exifTiffOffset)
                val chain = tiff.chain()
                val collector = ExifCollector(tiff)
                chain.firstOrNull()?.let { collector.ifd0(it) }
                // IFD1 = EXIF thumbnail.
                chain.getOrNull(1)?.let { thumbnail(tiff, source, it, "EXIF IFD1 thumbnail")?.let(previews::add) }
                metadata = collector.result().orElse(metadata)
            }
        }
        if (header.xmpOffset >= 0) xmpRating(source, header.xmpOffset, header.xmpLength)?.let { metadata = metadata.copy(rating = metadata.rating ?: it) }
        return metadata to previews
    }

    // --- TIFF-based RAW ----------------------------------------------------------------------

    private fun tiffRaw(source: RandomAccessSource): Pair<PhotoMetadata, List<EmbeddedImage>> {
        val tiff = runCatching { TiffReader.open(source, 0) }.getOrNull() ?: return PhotoMetadata() to emptyList()
        val collector = ExifCollector(tiff)
        val previews = ArrayList<EmbeddedImage>()
        val seen = HashSet<Long>()
        val chain = tiff.chain()
        chain.firstOrNull()?.let { collector.ifd0(it) }

        fun visit(ifd: Ifd, label: String, depth: Int) {
            if (!seen.add(ifd.offset)) return
            thumbnail(tiff, source, ifd, "$label JPEGInterchangeFormat")?.let(previews::add)
            stripJpeg(tiff, source, ifd, "$label strip")?.let(previews::add)
            ifd[Tags.PANASONIC_JPG_FROM_RAW]?.takeIf { it.type == TiffReader.TYPE_UNDEFINED && it.count > 4 }?.let { e ->
                embedded(source, e.valueOffset, e.count, "$label JpgFromRaw")?.let(previews::add)
            }
            if (depth < 2) {
                tiff.ints(ifd[Tags.SUB_IFDS], 8).forEachIndexed { i, off -> tiff.ifd(off)?.let { visit(it, "$label SubIFD$i", depth + 1) } }
            }
        }
        chain.forEachIndexed { i, ifd -> visit(ifd, "IFD$i", 0) }

        chain.firstOrNull()?.let { ifd0 ->
            tiff.int(ifd0[Tags.EXIF_IFD])?.let { tiff.ifd(it) }?.let { exif ->
                collector.exif(exif)
                olympusPreview(tiff, source, exif)?.let(previews::add)
            }
        }
        return collector.result() to previews
    }

    /** JPEGInterchangeFormat / JPEGInterchangeFormatLength. */
    private fun thumbnail(tiff: TiffReader, source: RandomAccessSource, ifd: Ifd, origin: String): EmbeddedImage? {
        val offset = tiff.int(ifd[Tags.JPEG_OFFSET]) ?: return null
        val length = tiff.int(ifd[Tags.JPEG_LENGTH]) ?: return null
        return embedded(source, tiff.base + offset, length, origin)
    }

    /** A single-strip IFD holding a baseline JPEG (CR2 IFD0, DNG/NEF preview SubIFDs). Lossless-JPEG RAW data is rejected by its SOF. */
    private fun stripJpeg(tiff: TiffReader, source: RandomAccessSource, ifd: Ifd, origin: String): EmbeddedImage? {
        val compression = tiff.int(ifd[Tags.COMPRESSION]) ?: return null
        if (compression != 6L && compression != 7L) return null
        val offsets = ifd[Tags.STRIP_OFFSETS] ?: return null
        val counts = ifd[Tags.STRIP_BYTE_COUNTS] ?: return null
        if (offsets.count != 1L || counts.count != 1L) return null
        return embedded(source, tiff.base + (tiff.int(offsets) ?: return null), tiff.int(counts) ?: return null, origin)
    }

    /** Olympus maker note ("OLYMPUS\0II\3\0"): CameraSettings PreviewImageStart/Length, relative to the note. */
    private fun olympusPreview(tiff: TiffReader, source: RandomAccessSource, exif: Ifd): EmbeddedImage? {
        val note = exif[Tags.MAKER_NOTE] ?: return null
        val sig = source.readRange(note.valueOffset, 12)
        if (sig.size < 12 || String(sig, 0, 8, Charsets.ISO_8859_1) != "OLYMPUS\u0000") return null
        val little = sig[8] == 'I'.code.toByte()
        val reader = TiffReader(source, note.valueOffset, little, 12)
        val root = reader.ifd(12) ?: return null
        val settingsOffset = reader.int(root[Tags.OLYMPUS_CAMERA_SETTINGS]) ?: return null
        val settings = reader.ifd(settingsOffset) ?: return null
        val start = reader.int(settings[Tags.OLYMPUS_PREVIEW_START]) ?: return null
        val length = reader.int(settings[Tags.OLYMPUS_PREVIEW_LENGTH]) ?: return null
        return embedded(source, note.valueOffset + start, length, "Olympus maker note preview")
    }

    // --- RAF ---------------------------------------------------------------------------------

    private fun raf(source: RandomAccessSource): Pair<PhotoMetadata, List<EmbeddedImage>> {
        val header = source.readRange(84, 8)
        if (header.size < 8) return PhotoMetadata() to emptyList()
        val offset = be32(header, 0)
        val length = be32(header, 4)
        val preview = embedded(source, offset, length, "RAF JPEG") ?: return PhotoMetadata() to emptyList()
        val (metadata, _) = jpegAt(source, offset, length)
        return metadata to listOf(preview)
    }

    private fun jpegAt(source: RandomAccessSource, offset: Long, length: Long): Pair<PhotoMetadata, List<EmbeddedImage>> {
        val header = JpegMarkers.read(source, offset, offset + length) ?: return PhotoMetadata() to emptyList()
        var metadata = PhotoMetadata()
        if (header.exifTiffOffset >= 0) {
            runCatching {
                val tiff = TiffReader.open(source, header.exifTiffOffset)
                val collector = ExifCollector(tiff)
                tiff.chain().firstOrNull()?.let { collector.ifd0(it) }
                metadata = collector.result()
            }
        }
        return metadata to emptyList()
    }

    // --- CR3 ---------------------------------------------------------------------------------

    private val UUID_CANON = hex("85c0b687820f11e08111f4ce462b6a48")
    private val UUID_PREVIEW = hex("eaf42b5e1c984b88b9fbb7dc406e4d16")

    private fun cr3(source: RandomAccessSource): Pair<PhotoMetadata, List<EmbeddedImage>> {
        val previews = ArrayList<EmbeddedImage>()
        var metadata = PhotoMetadata()
        val tracks = ArrayList<LongArray>() // [chunk offset, sample size]

        fun tiffBox(contentStart: Long, exif: Boolean) {
            runCatching {
                val tiff = TiffReader.open(source, contentStart)
                val collector = ExifCollector(tiff)
                tiff.ifd(tiff.firstIfd)?.let { if (exif) collector.exif(it) else collector.ifd0(it) }
                metadata = metadata.orElse(collector.result())
            }
        }

        Boxes.walk(source, 0, source.size) { top ->
            when (top.type) {
                "moov" -> Boxes.walk(source, top.contentStart, top.end) { box ->
                    when (box.type) {
                        "uuid" -> if (source.readRange(box.contentStart, 16).contentEquals(UUID_CANON)) {
                            Boxes.walk(source, box.contentStart + 16, box.end) { child ->
                                when (child.type) {
                                    "CMT1" -> tiffBox(child.contentStart, exif = false)
                                    "CMT2" -> tiffBox(child.contentStart, exif = true)
                                    "THMB" -> {
                                        val x = source.readRange(child.contentStart, 16)
                                        if (x.size == 16) embedded(source, child.contentStart + 16, child.end - child.contentStart - 16, "CR3 THMB")?.let(previews::add)
                                    }
                                }
                            }
                        }
                        "trak" -> track(source, box)?.let(tracks::add)
                    }
                }
                "uuid" -> {
                    if (source.readRange(top.contentStart, 16).contentEquals(UUID_PREVIEW)) {
                        val x = source.readRange(top.contentStart + 16, 32)
                        if (x.size == 32 && String(x, 12, 4, Charsets.ISO_8859_1) == "PRVW") {
                            val start = top.contentStart + 16 + 32
                            embedded(source, start, top.end - start, "CR3 PRVW")?.let(previews::add)
                        }
                    }
                }
            }
        }
        // Track 1 of a CR3 is the full-size JPEG; keep any track whose sample is a displayable JPEG.
        tracks.forEachIndexed { i, (offset, size) -> embedded(source, offset, size, "CR3 track ${i + 1}")?.let(previews::add) }
        return metadata to previews
    }

    /** First chunk offset and first sample size of a `trak` (stco/co64 + stsz). */
    private fun track(source: RandomAccessSource, trak: Boxes.Box): LongArray? {
        var offset = -1L
        var size = -1L
        fun descend(box: Boxes.Box) {
            Boxes.walk(source, box.contentStart, box.end) { child ->
                when (child.type) {
                    "mdia", "minf", "stbl" -> descend(child)
                    "stsz" -> {
                        val b = source.readRange(child.contentStart, 16)
                        if (b.size >= 12) {
                            val fixed = be32(b, 4)
                            size = if (fixed != 0L) fixed else if (b.size >= 16) be32(b, 12) else -1
                        }
                    }
                    "co64" -> {
                        val b = source.readRange(child.contentStart, 16)
                        if (b.size == 16 && be32(b, 4) > 0) offset = (be32(b, 8) shl 32) or be32(b, 12)
                    }
                    "stco" -> {
                        val b = source.readRange(child.contentStart, 12)
                        if (b.size == 12 && be32(b, 4) > 0) offset = be32(b, 8)
                    }
                }
            }
        }
        descend(trak)
        return if (offset > 0 && size > 0) longArrayOf(offset, size) else null
    }

    // --- Shared ------------------------------------------------------------------------------

    /** A displayable JPEG at [offset] (SOF0/1/2, inside the file), with its frame size. */
    private fun embedded(source: RandomAccessSource, offset: Long, length: Long, origin: String): EmbeddedImage? {
        if (offset <= 0 || length < 128 || offset + length > source.size) return null
        val header = JpegMarkers.read(source, offset, offset + length) ?: return null
        if (!header.isDisplayable) return null
        return EmbeddedImage(offset, length, header.width, header.height, origin)
    }

    private val ratingAttr = Regex("""xmp:Rating\s*=\s*["'](-?\d)["']""")
    private val ratingElem = Regex("""<xmp:Rating>\s*(-?\d)\s*</xmp:Rating>""")

    private fun xmpRating(source: RandomAccessSource, offset: Long, length: Int): Int? {
        val text = String(source.readRange(offset, minOf(length, 64 * 1024)), Charsets.UTF_8)
        val m = ratingAttr.find(text) ?: ratingElem.find(text) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun be32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Collects [PhotoMetadata] from IFD0 and EXIF IFDs of one TIFF. */
internal class ExifCollector(private val tiff: TiffReader) {
    private var m = PhotoMetadata()
    private var exifSeen = false

    fun ifd0(ifd: Ifd) {
        m = m.copy(
            make = m.make ?: tiff.string(ifd[Tags.MAKE]),
            model = m.model ?: tiff.string(ifd[Tags.MODEL]),
            orientation = tiff.int(ifd[Tags.ORIENTATION])?.toInt()?.takeIf { it in 1..8 } ?: m.orientation,
            captureTime = m.captureTime ?: tiff.string(ifd[Tags.DATE_TIME]),
            rating = m.rating ?: tiff.int(ifd[Tags.RATING])?.toInt()?.let { if (it > 32767) it - 65536 else it }?.takeIf { it in -1..5 },
        )
        // Some TIFF-based RAWs put the EXIF IFD pointer in IFD0 (all of them, really): follow it once.
        if (!exifSeen) tiff.int(ifd[Tags.EXIF_IFD])?.let { tiff.ifd(it) }?.let { exif(it) }
    }

    fun exif(ifd: Ifd) {
        exifSeen = true
        val iso = tiff.int(ifd[Tags.ISO])?.takeIf { it in 1..65534 }
            ?: tiff.int(ifd[Tags.ISO_SPEED])?.takeIf { it > 0 }
            ?: tiff.int(ifd[Tags.RECOMMENDED_EXPOSURE_INDEX])?.takeIf { it > 0 }
            ?: tiff.int(ifd[Tags.ISO])
        val lens = tiff.string(ifd[Tags.LENS_MODEL])
        m = m.copy(
            // DateTimeOriginal wins over IFD0 DateTime (which an editor may have changed).
            captureTime = tiff.string(ifd[Tags.DATE_TIME_ORIGINAL]) ?: m.captureTime,
            subSecond = tiff.string(ifd[Tags.SUB_SEC_TIME_ORIGINAL]) ?: m.subSecond,
            offsetTime = tiff.string(ifd[Tags.OFFSET_TIME_ORIGINAL]) ?: m.offsetTime,
            exposureTime = tiff.rational(ifd[Tags.EXPOSURE_TIME])?.takeIf { it.denominator != 0L } ?: m.exposureTime,
            fNumber = tiff.rational(ifd[Tags.F_NUMBER])?.value?.takeIf { it > 0 } ?: m.fNumber,
            iso = iso?.toInt() ?: m.iso,
            focalLength = tiff.rational(ifd[Tags.FOCAL_LENGTH])?.value?.takeIf { it > 0 } ?: m.focalLength,
            focalLength35 = tiff.int(ifd[Tags.FOCAL_LENGTH_35])?.toInt()?.takeIf { it > 0 } ?: m.focalLength35,
            lens = lens ?: m.lens,
            width = tiff.int(ifd[Tags.PIXEL_X])?.toInt()?.takeIf { it > 0 } ?: m.width,
            height = tiff.int(ifd[Tags.PIXEL_Y])?.toInt()?.takeIf { it > 0 } ?: m.height,
        )
    }

    fun result(): PhotoMetadata = m
}

/** ISO BMFF box walking (CR3). */
internal object Boxes {
    data class Box(val type: String, val start: Long, val contentStart: Long, val end: Long)

    fun walk(source: RandomAccessSource, start: Long, end: Long, maxBoxes: Int = 512, visit: (Box) -> Unit) {
        var p = start
        var n = 0
        while (p + 8 <= end && n++ < maxBoxes) {
            val h = source.readRange(p, 16)
            if (h.size < 8) return
            var size = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) {
                if (h.size < 16) return
                size = (8 until 16).fold(0L) { acc, i -> (acc shl 8) or (h[i].toLong() and 0xFF) }
                header = 16
            } else if (size == 0L) {
                size = end - p
            }
            if (size < header || p + size > end) return
            visit(Box(type, p, p + header, p + size))
            p += size
        }
    }
}
