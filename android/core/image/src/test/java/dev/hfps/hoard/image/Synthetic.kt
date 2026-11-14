package dev.hfps.hoard.image

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builders for synthetic camera files: TIFF structures (ARW/NEF/DNG/CR2/ORF/RW2 are TIFF inside),
 * JPEG headers, CR3 boxes. Nothing here comes from a real camera file.
 */
object Synthetic {

    /** A JPEG header: SOI, optional APP1 segments, a SOF with [width] x [height], SOS, filler, EOI. */
    fun jpeg(width: Int, height: Int, sof: Int = 0xC0, app1: List<ByteArray> = emptyList(), bodyBytes: Int = 256): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // APP0 JFIF
        segment(out, 0xE0, "JFIF\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0))
        app1.forEach { segment(out, 0xE1, it) }
        // DQT (dummy 65 bytes)
        segment(out, 0xDB, ByteArray(65))
        val frame = byteArrayOf(8, (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), 3,
            1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1)
        segment(out, sof, frame)
        segment(out, 0xDA, byteArrayOf(3, 1, 0, 2, 0x11, 3, 0x11, 0, 0x3F, 0))
        out.write(ByteArray(bodyBytes) { 0x55 })
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    private fun segment(out: ByteArrayOutputStream, marker: Int, data: ByteArray) {
        out.write(0xFF)
        out.write(marker)
        val len = data.size + 2
        out.write(len shr 8)
        out.write(len and 0xFF)
        out.write(data)
    }

    fun exifApp1(tiff: ByteArray) = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff

    fun xmpApp1(rating: Int) = ("http://ns.adobe.com/xap/1.0/\u0000" +
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description xmp:Rating="$rating"/></rdf:RDF></x:xmpmeta>""").toByteArray(Charsets.ISO_8859_1)

    // --- TIFF ------------------------------------------------------------------------------------

    sealed class Value {
        data class Short(val values: IntArray) : Value()
        data class Long(val values: LongArray) : Value()
        data class Ascii(val text: String) : Value()
        data class Rational(val num: kotlin.Long, val den: kotlin.Long) : Value()
        data class Undefined(val bytes: ByteArray) : Value()
        /** Pointer to a child IFD (LONG); resolved at layout. */
        data class Child(val ifd: Ifd, val type: Int = 4) : Value()
        data class Children(val ifds: List<Ifd>) : Value()
        /** Offset of a blob (LONG); resolved at layout. */
        data class BlobOffset(val blob: Blob) : Value()
        /** Undefined entry whose data is a blob placed anywhere (e.g. RW2 JpgFromRaw, maker note). */
        data class BlobData(val blob: Blob) : Value()
    }

    class Blob(val bytes: ByteArray) { var offset = -1 }

    class Ifd {
        val entries = sortedMapOf<Int, Value>()
        var next: Ifd? = null
        fun short(tag: Int, vararg v: Int) = apply { entries[tag] = Value.Short(v) }
        fun long(tag: Int, vararg v: kotlin.Long) = apply { entries[tag] = Value.Long(v) }
        fun ascii(tag: Int, s: String) = apply { entries[tag] = Value.Ascii(s) }
        fun rational(tag: Int, n: kotlin.Long, d: kotlin.Long) = apply { entries[tag] = Value.Rational(n, d) }
        fun child(tag: Int, ifd: Ifd) = apply { entries[tag] = Value.Child(ifd) }
        fun children(tag: Int, vararg ifds: Ifd) = apply { entries[tag] = Value.Children(ifds.toList()) }
        fun blobOffset(tag: Int, blob: Blob) = apply { entries[tag] = Value.BlobOffset(blob) }
        fun blobLength(tag: Int, blob: Blob) = apply { entries[tag] = Value.Long(longArrayOf(blob.bytes.size.toLong())) }
        fun blobData(tag: Int, blob: Blob) = apply { entries[tag] = Value.BlobData(blob) }
        /** JPEGInterchangeFormat + length. */
        fun jpeg(blob: Blob) = blobOffset(0x201, blob).blobLength(0x202, blob)
        /** Single strip (compression [compression]). */
        fun strip(blob: Blob, compression: Int) = short(0x103, compression).blobOffset(0x111, blob).blobLength(0x117, blob)
    }

    /**
     * Lays out a little- (or big-) endian TIFF: header, IFDs (with out-of-line values), then blobs;
     * [padTo] appends zeros (to mimic a 25 MB RAW) and [magic] allows ORF/RW2 variants.
     */
    fun tiff(root: Ifd, blobs: List<Blob>, littleEndian: Boolean = true, magic: Int = 42, padTo: Int = 0, prefix: ByteArray = ByteArray(0)): ByteArray {
        val order = if (littleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        // Collect IFDs in order (depth-first) to assign offsets.
        val ifds = ArrayList<Ifd>()
        fun collect(ifd: Ifd) {
            if (ifds.any { it === ifd }) return
            ifds += ifd
            ifd.entries.values.forEach { v ->
                when (v) {
                    is Value.Child -> collect(v.ifd)
                    is Value.Children -> v.ifds.forEach(::collect)
                    else -> Unit
                }
            }
            ifd.next?.let(::collect)
        }
        collect(root)
        fun valueSize(v: Value): Int = when (v) {
            is Value.Short -> v.values.size * 2
            is Value.Long -> v.values.size * 4
            is Value.Ascii -> v.text.length + 1
            is Value.Rational -> 8
            is Value.Undefined -> v.bytes.size
            is Value.Child -> 4
            is Value.Children -> v.ifds.size * 4
            is Value.BlobOffset -> 4
            is Value.BlobData -> 0 // points at the blob itself
        }
        // Layout: header(8) | each IFD: 2 + 12n + 4 + its out-of-line data | blobs
        val ifdOffset = HashMap<Ifd, Int>()
        var p = 8
        for (ifd in ifds) {
            ifdOffset[ifd] = p
            p += 2 + 12 * ifd.entries.size + 4
            ifd.entries.values.forEach { val s = valueSize(it); if (s > 4) p += s + (s and 1) }
        }
        for (b in blobs) { b.offset = p; p += b.bytes.size }
        val total = maxOf(p, padTo)
        val buf = ByteBuffer.allocate(total).order(order)
        buf.put(if (littleEndian) byteArrayOf(0x49, 0x49) else byteArrayOf(0x4D, 0x4D))
        buf.putShort(magic.toShort())
        buf.putInt(ifdOffset[root]!!)
        for (ifd in ifds) {
            val start = ifdOffset[ifd]!!
            var extra = start + 2 + 12 * ifd.entries.size + 4
            buf.position(start)
            buf.putShort(ifd.entries.size.toShort())
            for ((tag, v) in ifd.entries) {
                val type = when (v) {
                    is Value.Short -> 3
                    is Value.Long, is Value.BlobOffset, is Value.Children -> 4
                    is Value.Child -> v.type
                    is Value.Ascii -> 2
                    is Value.Rational -> 5
                    is Value.Undefined, is Value.BlobData -> 7
                }
                val count = when (v) {
                    is Value.Short -> v.values.size
                    is Value.Long -> v.values.size
                    is Value.Ascii -> v.text.length + 1
                    is Value.Rational -> 1
                    is Value.Undefined -> v.bytes.size
                    is Value.Child -> 1
                    is Value.Children -> v.ifds.size
                    is Value.BlobOffset -> 1
                    is Value.BlobData -> v.blob.bytes.size
                }
                buf.putShort(tag.toShort()); buf.putShort(type.toShort()); buf.putInt(count)
                val data = ByteBuffer.allocate(maxOf(4, valueSize(v))).order(order)
                when (v) {
                    is Value.Short -> v.values.forEach { data.putShort(it.toShort()) }
                    is Value.Long -> v.values.forEach { data.putInt(it.toInt()) }
                    is Value.Ascii -> { data.put(v.text.toByteArray(Charsets.ISO_8859_1)); data.put(0) }
                    is Value.Rational -> { data.putInt(v.num.toInt()); data.putInt(v.den.toInt()) }
                    is Value.Undefined -> data.put(v.bytes)
                    is Value.Child -> data.putInt(ifdOffset[v.ifd]!!)
                    is Value.Children -> v.ifds.forEach { data.putInt(ifdOffset[it]!!) }
                    is Value.BlobOffset -> data.putInt(v.blob.offset)
                    is Value.BlobData -> Unit
                }
                when {
                    v is Value.BlobData -> buf.putInt(v.blob.offset)
                    valueSize(v) <= 4 -> buf.put(data.array(), 0, 4)
                    else -> {
                        buf.putInt(extra)
                        val here = buf.position()
                        buf.position(extra); buf.put(data.array(), 0, valueSize(v)); buf.position(here)
                        extra += valueSize(v) + (valueSize(v) and 1)
                    }
                }
            }
            buf.putInt(ifd.next?.let { ifdOffset[it]!! } ?: 0)
        }
        for (b in blobs) { buf.position(b.offset); buf.put(b.bytes) }
        return prefix + buf.array()
    }

    // --- CR3 (ISO BMFF) ---------------------------------------------------------------------------

    fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { a, b -> a + b }
        return ByteBuffer.allocate(8).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).array() + body
    }

    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun be32(v: Long) = ByteBuffer.allocate(4).putInt(v.toInt()).array()
    fun be64(v: Long) = ByteBuffer.allocate(8).putLong(v).array()
}
