package dev.hfps.hoard.xmp

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.transfer.MemoryFs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Export XMP" (ADR 0011 amendment "2.2.0: export on demand"): the shared writer's rules on demand. */
class XmpExportTest {
    private val date = "2026-11-15T00:00:00.000Z"

    @Test
    fun `existing sidecars are merged by Hoard's naming rules, missing ones are created`() {
        val fs = MemoryFs("share")
        fs.file("d/a.ARW", ByteArray(10))
        fs.file("d/a.JPG", ByteArray(10))
        fs.file("d/a.ARW.xmp", LIGHTROOM) // darktable's name: found by Hoard's rules
        fs.file("d/b.ARW", ByteArray(10))
        val result = XmpExport.export(
            fs, "d",
            listOf(
                XmpExport.Shot("a", listOf("a.ARW", "a.JPG"), XmpFields(5, XmpFlag.PICK)),
                XmpExport.Shot("b", listOf("b.ARW"), XmpFields(0, XmpFlag.REJECT)),
            ),
            hidden = false, creatorTool = "Hoard test", date = date,
        )
        assertEquals(2, result.written)
        assertFalse(result.readOnly)
        val merged = XmpReader.read(fs.bytes("d/a.ARW.xmp")!!)
        assertEquals(XmpFields(5, XmpFlag.PICK), merged.fields)
        // Only the culling fields change: the rest of the other tool's document stays.
        assertTrue(String(fs.bytes("d/a.ARW.xmp")!!).contains("crs:Exposure2012"))
        assertEquals(XmpFields(0, XmpFlag.REJECT), XmpReader.read(fs.bytes("d/b.xmp")!!).fields)
        assertNull(fs.bytes("d/a.xmp"))
    }

    @Test
    fun `new sidecars may be hidden`() {
        val fs = MemoryFs("share")
        fs.file("a.ARW", ByteArray(1))
        val result = XmpExport.export(fs, "", listOf(XmpExport.Shot("a", listOf("a.ARW"), XmpFields(3, XmpFlag.NONE))), hidden = true, date = date)
        assertEquals(1, result.written)
        assertEquals(XmpFields(3, XmpFlag.NONE), XmpReader.read(fs.bytes(".a.xmp")!!).fields)
        assertEquals(".a.xmp", result.names["a"]?.first)
    }

    @Test
    fun `a malformed sidecar is left byte for byte`() {
        val fs = MemoryFs("share")
        fs.file("a.ARW", ByteArray(1))
        fs.file("a.xmp", "not xml".toByteArray())
        fs.file("b.ARW", ByteArray(1))
        val result = XmpExport.export(
            fs, "",
            listOf(XmpExport.Shot("a", listOf("a.ARW"), XmpFields(5, XmpFlag.PICK)), XmpExport.Shot("b", listOf("b.ARW"), XmpFields(1, XmpFlag.NONE))),
            hidden = false, date = date,
        )
        assertEquals(1, result.malformed)
        assertEquals(1, result.written)
        assertArrayEquals("not xml".toByteArray(), fs.bytes("a.xmp"))
    }

    @Test
    fun `a read-only folder stops at the first refusal and says so`() {
        val fs = MemoryFs("share").apply {
            file("a.ARW", ByteArray(1))
            file("b.ARW", ByteArray(1))
            writeFailure = HoardSmbException(ErrorCode.ACCESS_DENIED, "read-only")
        }
        val result = XmpExport.export(
            fs, "",
            listOf(XmpExport.Shot("a", listOf("a.ARW"), XmpFields(5, XmpFlag.PICK)), XmpExport.Shot("b", listOf("b.ARW"), XmpFields(1, XmpFlag.NONE))),
            hidden = false, date = date,
        )
        assertTrue(result.readOnly)
        assertEquals(0, result.written)
        assertNull(fs.bytes("a.xmp"))
    }

    @Test
    fun `nothing to export writes nothing`() {
        val fs = MemoryFs("share")
        assertEquals(XmpExport.Result(), XmpExport.export(fs, "", emptyList(), hidden = false))
    }

    companion object {
        val LIGHTROOM = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" xmp:Rating="2" crs:Exposure2012="+0.50"/></rdf:RDF></x:xmpmeta>""".toByteArray()
    }
}
