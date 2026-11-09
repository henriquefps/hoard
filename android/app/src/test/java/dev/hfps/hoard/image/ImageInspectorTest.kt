package dev.hfps.hoard.image

import dev.hfps.hoard.image.Synthetic.Blob
import dev.hfps.hoard.image.Synthetic.Ifd
import dev.hfps.hoard.image.format.ImageInspector
import dev.hfps.hoard.image.format.ImageKind
import dev.hfps.hoard.image.format.RawFormat
import dev.hfps.hoard.image.source.ByteArraySource
import dev.hfps.hoard.image.source.CountingSource
import dev.hfps.hoard.image.tiff.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ImageInspectorTest {

    /** EXIF IFD as a ZV-E10 writes it (values from the real camera's structure, not its files). */
    private fun sonyExif() = Ifd()
        .rational(0x829A, 1, 250)
        .rational(0x829D, 14, 10)
        .short(0x8827, 100)
        .ascii(0x9003, "2026:08:06 14:24:48")
        .ascii(0x9011, "-03:00")
        .ascii(0x9291, "733")
        .rational(0x920A, 300, 10)
        .short(0xA405, 45)
        .long(0xA002, 6000)
        .long(0xA003, 4000)
        .ascii(0xA434, "30mm F1.4 DC DN | Contemporary 016")

    /** Mimics a ZV-E10 ARW: IFD0 preview 1616x1080, SubIFD raw (Sony compression), IFD1 160x120. */
    private fun syntheticArw(padTo: Int = 0): Pair<ByteArray, Int> {
        val preview = Blob(Synthetic.jpeg(1616, 1080, bodyBytes = 4000))
        val thumb = Blob(Synthetic.jpeg(160, 120))
        val rawData = Blob(ByteArray(2048))
        val sub = Ifd().long(0xFE, 0).long(0x100, 6048).long(0x101, 4024).short(0x103, 32767).blobOffset(0x111, rawData).blobLength(0x117, rawData)
        val ifd1 = Ifd().short(0x103, 6).jpeg(thumb)
        val ifd0 = Ifd()
            .ascii(0x10F, "SONY").ascii(0x110, "ZV-E10").short(0x112, 8)
            .ascii(0x132, "2026:08:06 14:24:48")
            .child(0x14A, sub)
            .jpeg(preview)
            .child(0x8769, sonyExif())
        ifd0.next = ifd1
        val bytes = Synthetic.tiff(ifd0, listOf(thumb, preview, rawData), padTo = padTo)
        return bytes to preview.offset
    }

    @Test
    fun `ARW - largest embedded JPEG, EXIF, and only header ranges are read`() {
        val (bytes, previewOffset) = syntheticArw(padTo = 8 * 1024 * 1024)
        val counting = CountingSource(ByteArraySource(bytes))
        val inspection = ImageInspector.inspect(counting, "DSC07668.ARW")

        assertEquals(ImageKind.RAW, inspection.type.kind)
        assertEquals(RawFormat.ARW, inspection.type.raw)
        val preview = inspection.largestPreview!!
        assertEquals(1616 to 1080, preview.width to preview.height)
        assertEquals(previewOffset.toLong(), preview.offset)
        assertEquals(listOf(1616, 160), inspection.previews.map { it.width })

        val m = inspection.metadata
        assertEquals("SONY", m.make)
        assertEquals("ZV-E10", m.model)
        assertEquals(8, m.orientation)
        assertEquals("2026:08:06 14:24:48", m.captureTime)
        assertEquals("733", m.subSecond)
        assertEquals("1/250", m.exposureLabel)
        assertEquals(1.4, m.fNumber!!, 1e-9)
        assertEquals(100, m.iso)
        assertEquals(30.0, m.focalLength!!, 1e-9)
        assertEquals(45, m.focalLength35)
        assertEquals("30mm F1.4 DC DN | Contemporary 016", m.lens)
        assertEquals(6000 to 4000, m.width to m.height)
        // 2026-08-06 14:24:48.733 -03:00 = 17:24:48.733Z
        assertEquals(java.time.Instant.parse("2026-08-06T17:24:48.733Z").toEpochMilli(), m.captureEpochMillis)

        // Header prefetch (128 KiB) only: the 8 MB file is never read beyond it.
        assertTrue("read ${counting.bytesRead}", counting.bytesRead <= ImageInspector.HEAD_PREFETCH + 64 * 1024)
        assertTrue("calls ${counting.readCalls}", counting.readCalls <= 3)
    }

    @Test
    fun `lossless JPEG raw data is not a preview (CR2 IFD3, DNG main image)`() {
        val cr2Preview = Blob(Synthetic.jpeg(5472, 3648))
        val lossless = Blob(Synthetic.jpeg(1368, 3648, sof = 0xC3))
        val ifd3 = Ifd().strip(lossless, 6)
        val ifd0 = Ifd().ascii(0x10F, "Canon").ascii(0x110, "Canon EOS 80D").short(0x112, 1).strip(cr2Preview, 6)
        ifd0.next = ifd3
        val bytes = Synthetic.tiff(ifd0, listOf(cr2Preview, lossless))
        // CR2 = TIFF + "CR" at 8; place it in the header by prefixing? CR2 sets bytes 8..9 = "CR".
        val inspection = ImageInspector.inspect(ByteArraySource(bytes), "IMG_0001.CR2")
        assertEquals(RawFormat.CR2, inspection.type.raw)
        assertEquals(listOf(5472 to 3648), inspection.previews.map { it.width to it.height })
        assertEquals("Canon EOS 80D", inspection.metadata.model)
    }

    @Test
    fun `DNG preview in a SubIFD (compression 7, baseline) and NEF JpgFromRaw`() {
        val dngPreview = Blob(Synthetic.jpeg(1024, 768))
        val rawTiles = Blob(Synthetic.jpeg(4000, 3000, sof = 0xC3))
        val main = Ifd().long(0xFE, 0).strip(rawTiles, 7)
        val previewIfd = Ifd().long(0xFE, 1).strip(dngPreview, 7)
        val ifd0 = Ifd().ascii(0x10F, "Google").ascii(0x110, "Pixel 8").children(0x14A, main, previewIfd)
        val dng = ImageInspector.inspect(ByteArraySource(Synthetic.tiff(ifd0, listOf(dngPreview, rawTiles))), "PXL.dng")
        assertEquals(RawFormat.DNG, dng.type.raw)
        assertEquals(listOf(1024 to 768), dng.previews.map { it.width to it.height })

        val big = Blob(Synthetic.jpeg(6048, 4024))
        val small = Blob(Synthetic.jpeg(640, 424))
        val nefSub = Ifd().long(0xFE, 1).jpeg(big)
        val nef0 = Ifd().ascii(0x10F, "NIKON CORPORATION").ascii(0x110, "NIKON Z 6_2").jpeg(small).children(0x14A, nefSub)
        val nef = ImageInspector.inspect(ByteArraySource(Synthetic.tiff(nef0, listOf(big, small), littleEndian = false)), "DSC_0001.NEF")
        assertEquals(listOf(6048, 640), nef.previews.map { it.width })
        assertEquals(6048, nef.previewFor(3000)!!.width)
        assertEquals(640, nef.previewFor(500)!!.width)
    }

    @Test
    fun `RW2 JpgFromRaw (magic 0x55) and ORF maker note preview`() {
        val jpg = Blob(Synthetic.jpeg(1920, 1440))
        val rw2 = Ifd().ascii(0x10F, "Panasonic").ascii(0x110, "DC-G9").blobData(0x2E, jpg)
        val inspection = ImageInspector.inspect(ByteArraySource(Synthetic.tiff(rw2, listOf(jpg), magic = 0x55)), "P1000001.RW2")
        assertEquals(RawFormat.RW2, inspection.type.raw)
        assertEquals(listOf(1920 to 1440), inspection.previews.map { it.width to it.height })

        // Olympus maker note: "OLYMPUS\0II\3\0" + IFD at +12 whose offsets are relative to the note.
        val previewJpeg = Synthetic.jpeg(3200, 2400)
        val note = ByteBuffer.allocate(4096 + previewJpeg.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        note.put("OLYMPUS\u0000II".toByteArray(Charsets.ISO_8859_1)).putShort(3)
        // root IFD at 12: one entry 0x2020 -> sub IFD at 64
        note.position(12); note.putShort(1); note.putShort(0x2020); note.putShort(13); note.putInt(1); note.putInt(64); note.putInt(0)
        note.position(64); note.putShort(2)
        note.putShort(0x0101); note.putShort(4); note.putInt(1); note.putInt(4096)
        note.putShort(0x0102); note.putShort(4); note.putInt(1); note.putInt(previewJpeg.size)
        note.putInt(0)
        note.position(4096); note.put(previewJpeg)
        val noteBlob = Blob(note.array())
        val exif = Ifd().blobData(0x927C, noteBlob).ascii(0x9003, "2025:01:02 03:04:05")
        val orf0 = Ifd().ascii(0x10F, "OM Digital Solutions").ascii(0x110, "OM-1").child(0x8769, exif)
        val orf = ImageInspector.inspect(ByteArraySource(Synthetic.tiff(orf0, listOf(noteBlob), magic = 0x4F52)), "P1010001.ORF")
        assertEquals(RawFormat.ORF, orf.type.raw)
        assertEquals(listOf(3200 to 2400), orf.previews.map { it.width to it.height })
        assertEquals("2025:01:02 03:04:05", orf.metadata.captureTime)
    }

    @Test
    fun `RAF - header JPEG and its EXIF`() {
        val exifTiff = Synthetic.tiff(Ifd().ascii(0x10F, "FUJIFILM").ascii(0x110, "X-T5").short(0x112, 6)
            .child(0x8769, Ifd().ascii(0x9003, "2025:05:06 07:08:09").short(0x8827, 125)), emptyList())
        val jpeg = Synthetic.jpeg(1920, 1280, app1 = listOf(Synthetic.exifApp1(exifTiff)))
        val header = ByteArray(160)
        "FUJIFILMCCD-RAW 0201FF383501".toByteArray(Charsets.ISO_8859_1).copyInto(header)
        ByteBuffer.wrap(header).putInt(84, 160).putInt(88, jpeg.size)
        val inspection = ImageInspector.inspect(ByteArraySource(header + jpeg + ByteArray(10_000)), "DSCF0001.RAF")
        assertEquals(RawFormat.RAF, inspection.type.raw)
        assertEquals(listOf(1920 to 1280), inspection.previews.map { it.width to it.height })
        assertEquals("X-T5", inspection.metadata.model)
        assertEquals(6, inspection.metadata.orientation)
        assertEquals(125, inspection.metadata.iso)
    }

    @Test
    fun `CR3 - CMT1, CMT2, THMB, PRVW and the full-size JPEG track`() {
        val canon = Synthetic.hex("85c0b687820f11e08111f4ce462b6a48")
        val prvwUuid = Synthetic.hex("eaf42b5e1c984b88b9fbb7dc406e4d16")
        val cmt1 = Synthetic.tiff(Ifd().ascii(0x10F, "Canon").ascii(0x110, "Canon EOS R6m2").short(0x112, 1), emptyList())
        val cmt2 = Synthetic.tiff(Ifd().rational(0x829A, 1, 1000).rational(0x829D, 28, 10).short(0x8827, 400).ascii(0x9003, "2025:03:04 05:06:07"), emptyList())
        val thumb = Synthetic.jpeg(160, 120)
        val thmb = Synthetic.box("THMB", ByteArray(4), byteArrayOf(0, 0xA0.toByte(), 0, 0x78), ByteArray(8), thumb)
        val prvwJpeg = Synthetic.jpeg(1620, 1080)
        val prvwHeader = ByteArray(32).also { "PRVW".toByteArray().copyInto(it, 12) }
        val prvw = Synthetic.box("uuid", prvwUuid, ByteArray(0), prvwHeader, prvwJpeg)
        val full = Synthetic.jpeg(6000, 4000)
        val ftyp = Synthetic.box("ftyp", "crx ".toByteArray(), ByteArray(4))
        // mdat after moov+prvw: compute offsets with a placeholder track first.
        fun moov(fullOffset: Long) = Synthetic.box("moov",
            Synthetic.box("uuid", canon, Synthetic.box("CMT1", cmt1), Synthetic.box("CMT2", cmt2), thmb),
            Synthetic.box("trak", Synthetic.box("mdia", Synthetic.box("minf", Synthetic.box("stbl",
                Synthetic.box("stsz", ByteArray(4), Synthetic.be32(full.size.toLong()), Synthetic.be32(1)),
                Synthetic.box("co64", ByteArray(4), Synthetic.be32(1), Synthetic.be64(fullOffset)),
            )))),
        )
        val headerSize = ftyp.size + moov(0).size + prvw.size
        val mdat = Synthetic.box("mdat", full)
        val bytes = ftyp + moov(headerSize + 8L) + prvw + mdat
        val inspection = ImageInspector.inspect(ByteArraySource(bytes), "IMG_0001.CR3")
        assertEquals(RawFormat.CR3, inspection.type.raw)
        assertEquals(listOf(6000, 1620, 160), inspection.previews.map { it.width })
        assertEquals(listOf("CR3 track 1", "CR3 PRVW", "CR3 THMB"), inspection.previews.map { it.origin })
        assertEquals("Canon EOS R6m2", inspection.metadata.model)
        assertEquals(Rational(1, 1000), inspection.metadata.exposureTime)
        assertEquals(400, inspection.metadata.iso)
        assertEquals("2025:03:04 05:06:07", inspection.metadata.captureTime)
    }

    @Test
    fun `JPEG - EXIF, XMP rating, frame size, EXIF thumbnail`() {
        val thumb = Blob(Synthetic.jpeg(160, 120))
        val ifd1 = Ifd().short(0x103, 6).jpeg(thumb)
        val ifd0 = Ifd().ascii(0x10F, "SONY").ascii(0x110, "ZV-E10").short(0x112, 6).child(0x8769, sonyExif())
        ifd0.next = ifd1
        val exif = Synthetic.tiff(ifd0, listOf(thumb))
        val jpeg = Synthetic.jpeg(6000, 4000, app1 = listOf(Synthetic.exifApp1(exif), Synthetic.xmpApp1(4)))
        val inspection = ImageInspector.inspect(ByteArraySource(jpeg), "DSC07668.JPG")
        assertEquals(ImageKind.JPEG, inspection.type.kind)
        val m = inspection.metadata
        assertEquals(6, m.orientation)
        assertEquals(4, m.rating)
        assertEquals(6000 to 4000, m.width to m.height)
        assertEquals("1/250", m.exposureLabel)
        // EXIF thumbnail found inside the APP1 TIFF (offsets relative to the TIFF header).
        assertEquals(listOf(160 to 120), inspection.previews.map { it.width to it.height })
    }

    @Test
    fun `garbage, truncation and IFD loops never throw`() {
        val loop = Ifd().ascii(0x10F, "X")
        loop.next = loop
        val looped = Synthetic.tiff(loop, emptyList())
        listOf(
            ByteArray(0) to "a.arw",
            ByteArray(3) to "a.nef",
            ByteArray(10_000) { (it * 31).toByte() } to "a.cr2",
            looped to "a.arw",
            syntheticArw().first.copyOf(300) to "cut.arw",
            Synthetic.jpeg(10, 10).copyOf(20) to "cut.jpg",
            Synthetic.box("ftyp", "crx ".toByteArray()) + ByteArray(5) to "a.cr3",
        ).forEach { (bytes, name) ->
            val inspection = ImageInspector.inspect(ByteArraySource(bytes), name)
            assertTrue(name, inspection.previews.all { it.offset + it.length <= bytes.size })
        }
        assertNull(ImageInspector.inspect(ByteArraySource(ByteArray(4)), "a.arw").largestPreview)
    }
}
