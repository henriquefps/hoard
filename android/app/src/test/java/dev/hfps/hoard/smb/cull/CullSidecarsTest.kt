package dev.hfps.hoard.smb.cull

import dev.hfps.hoard.image.CullFileRef
import dev.hfps.hoard.image.CullShotRef
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.location.Location
import dev.hfps.hoard.smb.transfer.MemoryFs
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpFlag
import dev.hfps.hoard.xmp.XmpReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CullSidecarsTest {
    private fun file(name: String) = CullFileRef(name, "DCIM/$name", 10, null, 0)
    private fun shot(stem: String, vararg names: String): CullShotRef {
        val raw = names.firstOrNull { it.endsWith(".ARW") }?.let(::file)
        val rendered = names.firstOrNull { it.endsWith(".JPG") }?.let(::file)
        return CullShotRef(stem.lowercase(), stem, raw, rendered, emptyList())
    }

    private val shots = listOf(shot("DSC1", "DSC1.ARW", "DSC1.JPG"), shot("DSC2", "DSC2.JPG"), shot("DSC3", "DSC3.ARW"), shot("DSC4", "DSC4.ARW"))
    private val byId = shots.associateBy { it.id }

    private fun xmp(rating: Int, extra: String = "") =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">""" +
            """<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="$rating" $extra/></rdf:RDF></x:xmpmeta>"""

    private fun listed(fs: MemoryFs) = fs.list("DCIM").map { ListedName(it.name, it.modifiedMillis) }

    @Test
    fun `reading returns each shot's chosen sidecar newer than its threshold, malformed ones flagged`() {
        val fs = MemoryFs("phone")
        fs.file("DCIM/DSC1.ARW", ByteArray(10))
        fs.file("DCIM/DSC1.xmp", xmp(4).toByteArray(), modified = 5_000)
        fs.file("DCIM/.DSC1.xmp", xmp(1).toByteArray(), modified = 9_000)
        fs.file("DCIM/DSC2.JPG.xmp", xmp(-1).toByteArray(), modified = 5_000)
        fs.file("DCIM/DSC3.xmp", "<x:xmpmeta".toByteArray(), modified = 5_000)
        fs.file("DCIM/DSC4.xmp", xmp(2).toByteArray(), modified = 5_000)
        val found = CullSidecars.read(fs, "DCIM", shots, listed(fs), mapOf("dsc1" to 4_000L, "dsc2" to null, "dsc4" to 5_000L))
        assertEquals(listOf("dsc1", "dsc2", "dsc3"), found.map { it.shotId })
        assertEquals("DSC1.xmp", found[0].name)
        assertEquals(XmpFields(4), found[0].read!!.fields)
        assertEquals(XmpFields(0, XmpFlag.REJECT), found[1].read!!.fields)
        assertNull(found[2].read)
        assertEquals("malformed", found[2].error)
    }

    @Test
    fun `writing merges, creates, skips malformed byte for byte, and keeps one sidecar per shot`() {
        val fs = MemoryFs("phone")
        fs.dirs("DCIM")
        fs.file("DCIM/dsc1.ARW.xmp", xmp(1, """xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:Exposure2012="+0.50"""").toByteArray())
        val malformed = "<x:xmpmeta><broken".toByteArray()
        fs.file("DCIM/DSC3.xmp", malformed)
        val batch = CullSidecars.write(
            fs, "DCIM", byId,
            listOf("dsc1" to XmpFields(5, XmpFlag.PICK), "dsc2" to XmpFields(0, XmpFlag.REJECT), "dsc3" to XmpFields(2), "dsc4" to XmpFields(3)),
            hidden = true, readOnly = false, date = "2026-11-11T00:00:00.000Z",
        )
        assertEquals(listOf("written", "written", "malformed", "written"), batch.results.map { it.status })
        assertEquals(listOf("dsc1.ARW.xmp", ".DSC2.xmp", "DSC3.xmp", ".DSC4.xmp"), batch.results.map { it.name })
        val merged = fs.bytes("DCIM/dsc1.ARW.xmp")!!
        assertEquals(XmpFields(5, XmpFlag.PICK), XmpReader.read(merged).fields)
        assertTrue(merged.toString(Charsets.UTF_8).contains("crs:Exposure2012=\"+0.50\""))
        assertNull(fs.bytes("DCIM/DSC1.xmp"))
        assertNull(fs.bytes("DCIM/.DSC1.xmp"))
        assertEquals(XmpFields(0, XmpFlag.REJECT), XmpReader.read(fs.bytes("DCIM/.DSC2.xmp")!!).fields)
        assertArrayEquals(malformed, fs.bytes("DCIM/DSC3.xmp"))
        assertTrue(fs.nodes.keys.none { it.contains("hoardpart") })
        assertEquals(1_000L, batch.results[0].modifiedMillis)
    }

    @Test
    fun `the first refused write makes the folder read-only and nothing more is attempted`() {
        val fs = MemoryFs("share")
        fs.dirs("DCIM")
        fs.writeFailure = HoardSmbException(ErrorCode.ACCESS_DENIED, "read-only share")
        val batch = CullSidecars.write(fs, "DCIM", byId, listOf("dsc1" to XmpFields(1), "dsc2" to XmpFields(2)), hidden = false, readOnly = false)
        assertTrue(batch.readOnly)
        assertEquals(listOf("readOnly", "readOnly"), batch.results.map { it.status })
        fs.writeFailure = null
        val later = CullSidecars.write(fs, "DCIM", byId, listOf("dsc1" to XmpFields(1)), hidden = false, readOnly = true)
        assertEquals("readOnly", later.results.single().status)
        assertTrue(fs.nodes.keys.none { it.endsWith(".xmp") })
        // Another I/O failure is a failure, not read-only.
        fs.writeFailure = HoardSmbException(ErrorCode.UNAVAILABLE, "gone")
        val failed = CullSidecars.write(fs, "DCIM", byId, listOf("dsc1" to XmpFields(1)), hidden = false, readOnly = false)
        assertEquals("failed", failed.results.single().status)
        assertEquals(false, failed.readOnly)
    }

    @Test
    fun `the writer coalesces rapid decisions per shot and flushes on demand`() {
        val fs = MemoryFs("phone")
        fs.dirs("DCIM")
        var flushes = 0
        val folder = CullFolder("c1", "DCIM", "device", shots, Location.Device)
        val writer = CullSidecarWriter({ flushes++; fs }, debounceMs = 60_000, maxDelayMs = 120_000, log = {})
        val first = writer.enqueue(folder, listOf("dsc1" to XmpFields(1)), hidden = false)
        val second = writer.enqueue(folder, listOf("dsc1" to XmpFields(4, XmpFlag.PICK), "dsc2" to XmpFields(2)), hidden = false)
        // Nothing yet: the debounce is a minute. Leaving the app writes at once.
        assertNull(fs.bytes("DCIM/DSC1.xmp"))
        writer.flushAll()
        val a = first.get(5, TimeUnit.SECONDS)
        val b = second.get(5, TimeUnit.SECONDS)
        assertEquals(1, flushes)
        // The earlier call learns what was finally written for its shot.
        assertEquals(XmpFields(4, XmpFlag.PICK), a.single().fields)
        assertEquals(listOf("written", "written"), b.map { it.status })
        assertEquals(XmpFields(4, XmpFlag.PICK), XmpReader.read(fs.bytes("DCIM/DSC1.xmp")!!).fields)
        // Closing the folder writes what is pending too.
        val third = writer.enqueue(folder, listOf("dsc3" to XmpFields(5)), hidden = false)
        writer.close("c1")
        assertEquals("written", third.get(5, TimeUnit.SECONDS).single().status)
    }
}
