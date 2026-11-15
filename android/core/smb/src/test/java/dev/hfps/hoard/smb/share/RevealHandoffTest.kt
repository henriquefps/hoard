package dev.hfps.hoard.smb.share

import android.content.Intent
import dev.hfps.hoard.smb.HoardSmbException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.transfer.MemoryFs
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpFlag
import dev.hfps.hoard.xmp.XmpReader

class RevealHandoffTest {

    @Test
    fun `a pair travels together, RAW first, at the place of its first file`() {
        val order = RevealHandoff.order(
            listOf("DCIM/b.JPG", "DCIM/a.ARW", "DCIM/B.arw", "DCIM/a.jpg", "DCIM/c.heic", "DCIM/c.JPG"),
        )
        assertEquals(listOf("DCIM/B.arw", "DCIM/b.JPG", "DCIM/a.ARW", "DCIM/a.jpg", "DCIM/c.JPG", "DCIM/c.heic"), order)
    }

    @Test
    fun `the same stem in two folders stays two photos, duplicates and non-photos are dropped`() {
        val order = RevealHandoff.order(listOf("x/IMG_1.jpg", "y/IMG_1.ARW", "x/IMG_1.jpg", "x/notes.txt", "x/IMG_1.xmp"))
        assertEquals(listOf("x/IMG_1.jpg", "y/IMG_1.ARW"), order)
    }

    @Test
    fun `nothing to send is refused`() {
        assertThrows(HoardSmbException::class.java) { RevealHandoff.order(listOf("a.txt", "b.xmp")) }
        assertThrows(HoardSmbException::class.java) { RevealHandoff.plan(emptyList<Triple<String, String, Boolean>>(), null, null) }
        // Sidecars alone are not a hand-off.
        assertThrows(HoardSmbException::class.java) { RevealHandoff.plan(listOf(Triple("a.xmp", "u", true)), null, null) }
    }

    @Test
    fun `the plan keeps the order and sends exactly the given URIs`() {
        val files = listOf(
            Triple("a.ARW", "content://x/a.ARW", false),
            Triple("a.jpg", "content://x/a.jpg", false),
            Triple("a.xmp", "content://x/a.xmp", true),
            Triple("b.jpg", "content://x/b.jpg", false),
        )
        val plan = RevealHandoff.plan(files, "  Sony  ", "a.jpg")
        assertEquals(listOf("content://x/a.ARW", "content://x/a.jpg", "content://x/a.xmp", "content://x/b.jpg"), plan.items)
        assertEquals("Sony", plan.title)
        assertEquals("content://x/a.jpg", plan.initial)
        assertEquals(listOf("content://x/a.xmp"), plan.writable)
    }

    @Test
    fun `every item is read and persistable, the intent never grants write, only sidecars get it`() {
        val plan = RevealHandoff.plan(listOf(Triple("a.jpg", "u", false), Triple("a.xmp", "x", true)), null, null)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, plan.flags)
        assertEquals(0, plan.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(listOf("x"), plan.writable)
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            RevealHandoff.SIDECAR_GRANT,
        )
        // A sidecar is never the photo Reveal opens first.
        assertNull(RevealHandoff.plan(listOf(Triple("a.jpg", "u", false), Triple("a.xmp", "x", true)), null, "a.xmp").initial)
    }

    @Test
    fun `title is trimmed to 120 characters and blank means none, an initial not sent is dropped`() {
        val files = listOf(Triple("a.jpg", "u", false))
        assertEquals(120, RevealHandoff.plan(files, "x".repeat(300), null).title!!.length)
        assertNull(RevealHandoff.plan(files, "   ", null).title)
        assertNull(RevealHandoff.plan(files, null, "elsewhere.jpg").initial)
    }

    @Test
    fun `the release Reveal wins over the debug one, never another app`() {
        assertEquals("dev.hfps.reveal", RevealPackages.choose(listOf("dev.hfps.reveal.debug", "dev.hfps.reveal")))
        assertEquals("dev.hfps.reveal", RevealPackages.choose(listOf("dev.hfps.reveal")))
        assertEquals("dev.hfps.reveal.debug", RevealPackages.choose(listOf("com.example.editor", "dev.hfps.reveal.debug")))
        assertNull(RevealPackages.choose(listOf("com.example.editor")))
        assertNull(RevealPackages.choose(emptyList()))
    }

    @Test
    fun `each shot's sidecar follows its files`() {
        val ordered = listOf("d/a.ARW", "d/a.jpg", "d/b.jpg", "e/a.ARW")
        val out = RevealHandoff.assemble(
            ordered,
            ordered.associateWith { "u:$it" },
            mapOf("d/a.ARW" to ("d/a.xmp" to "u:d/a.xmp"), "e/a.ARW" to ("e/.a.xmp" to "u:e/.a.xmp")),
        )
        assertEquals(
            listOf("d/a.ARW", "d/a.jpg", "d/a.xmp", "d/b.jpg", "e/a.ARW", "e/.a.xmp"),
            out.map { it.first },
        )
        assertEquals(listOf(false, false, true, false, false, true), out.map { it.third })
    }

    @Test
    fun `existing writable sidecars are sent, missing ones are created with the shot's decision`() {
        val fs = MemoryFs("share")
        fs.file("d/a.ARW", ByteArray(10))
        fs.file("d/a.JPG", ByteArray(10))
        fs.file("d/a.ARW.xmp", XMP) // darktable's name: found by Hoard's rules
        fs.file("d/b.ARW", ByteArray(10))
        fs.file("d/c.JPG", ByteArray(10))
        val found = RevealHandoff.writableSidecars(
            fs,
            listOf("d/a.ARW", "d/a.JPG", "d/b.ARW", "d/c.JPG"),
            hidden = false,
            culling = mapOf("d/b.ARW" to RevealHandoff.Culling(4, "pick")),
            date = "2026-11-12T00:00:00.000Z",
        )
        assertEquals(mapOf("d/a.ARW" to "d/a.ARW.xmp", "d/b.ARW" to "d/b.xmp", "d/c.JPG" to "d/c.xmp"), found)
        assertArrayEquals(XMP, fs.bytes("d/a.ARW.xmp")) // existing ones are not touched
        val created = XmpReader.read(fs.bytes("d/b.xmp")!!)
        assertEquals(XmpFields(4, XmpFlag.PICK), created.fields)
        assertEquals(XmpFields(0, XmpFlag.NONE), XmpReader.read(fs.bytes("d/c.xmp")!!).fields)
    }

    @Test
    fun `new sidecars may be hidden`() {
        val fs = MemoryFs("share")
        fs.file("a.ARW", ByteArray(1))
        assertEquals(mapOf("a.ARW" to ".a.xmp"), RevealHandoff.writableSidecars(fs, listOf("a.ARW"), hidden = true, culling = emptyMap()))
    }

    @Test
    fun `a read-only folder sends no sidecar, nor does a malformed or read-only one`() {
        val readOnly = MemoryFs("share").apply {
            file("d/a.ARW", ByteArray(1))
            file("d/b.ARW", ByteArray(1))
            writeFailure = HoardSmbException(ErrorCode.ACCESS_DENIED, "read-only")
        }
        assertEquals(emptyMap<String, String>(), RevealHandoff.writableSidecars(readOnly, listOf("d/a.ARW", "d/b.ARW"), false, emptyMap()))
        assertNull(readOnly.bytes("d/a.xmp"))

        val malformed = MemoryFs("share").apply {
            file("a.ARW", ByteArray(1))
            file("a.xmp", "not xml".toByteArray())
        }
        assertEquals(emptyMap<String, String>(), RevealHandoff.writableSidecars(malformed, listOf("a.ARW"), false, emptyMap()))
        assertArrayEquals("not xml".toByteArray(), malformed.bytes("a.xmp"))

        val locked = MemoryFs("share").apply {
            file("a.ARW", ByteArray(1))
            file("a.xmp", XMP)
            writable = false
        }
        assertEquals(emptyMap<String, String>(), RevealHandoff.writableSidecars(locked, listOf("a.ARW"), false, emptyMap()))
    }

    @Test
    fun `the contract's names`() {
        assertEquals("dev.hfps.reveal.action.DEVELOP", RevealHandoff.ACTION)
        assertEquals("dev.hfps.reveal.extra.TITLE", RevealHandoff.EXTRA_TITLE)
        assertEquals("dev.hfps.reveal.extra.INITIAL_URI", RevealHandoff.EXTRA_INITIAL_URI)
    }

    @Test
    fun `culling values are keyed by each sent URI, on the RAW and the JPEG, only for shots with a decision`() {
        val files = listOf(
            "DCIM/a.ARW" to "content://x/a.ARW",
            "DCIM/a.jpg" to "content://x/a.jpg",
            "DCIM/b.jpg" to "content://x/b.jpg",
            "DCIM/c.ARW" to "content://x/c.ARW",
        )
        val values = RevealHandoff.cullingValues(
            files,
            mapOf(
                "DCIM/a.ARW" to RevealHandoff.Culling(4, "pick"),
                "/DCIM/a.jpg" to RevealHandoff.Culling(4, "pick"),
                // A reject sends its stars too (Reveal keeps them in its database).
                "DCIM/c.ARW" to RevealHandoff.Culling(3, "REJECT"),
                // Not among the files sent: dropped.
                "DCIM/z.ARW" to RevealHandoff.Culling(1, "pick"),
            ),
        )
        assertEquals(listOf("content://x/a.ARW", "content://x/a.jpg", "content://x/c.ARW"), values.keys.toList())
        assertEquals(RevealHandoff.Culling(4, "pick"), values["content://x/a.ARW"])
        assertEquals(RevealHandoff.Culling(3, "reject"), values["content://x/c.ARW"])
        assertNull(values["content://x/b.jpg"])
    }

    @Test
    fun `culling values are clamped and unknown flags dropped`() {
        val files = listOf("a.ARW" to "u1", "b.ARW" to "u2")
        val values = RevealHandoff.cullingValues(files, mapOf("a.ARW" to RevealHandoff.Culling(9, "none"), "b.ARW" to RevealHandoff.Culling(2, "maybe")))
        assertEquals(mapOf("u1" to RevealHandoff.Culling(5, "none")), values)
        assertEquals("dev.hfps.reveal.extra.CULLING", RevealHandoff.EXTRA_CULLING)
        assertEquals("dev.hfps.reveal.extra.CULLING_VERSION", RevealHandoff.EXTRA_CULLING_VERSION)
        assertEquals(2, RevealHandoff.CULLING_VERSION) // version 2: per-field times and devices (Reveal ADR 0011 amendment W3/H)
    }

    // --- Server references (Reveal ADR 0011 amendment W3/H, Hoard ADR 0010 "Server items") -------------------------

    private fun ref(n: Int, pair: Boolean = false) = RevealHandoff.ServerItem(
        itemId = "item-$n",
        fingerprint = "v1:" + "%064x".format(n),
        fingerprints = if (pair) listOf("v1:" + "%064x".format(n), "v1:" + "%064x".format(n + 100000)) else listOf("v1:" + "%064x".format(n)),
        name = "DSC%05d.ARW".format(n),
    )

    @Test
    fun serverItemsGoInIntentsOfFiveHundredInOrder() {
        val items = (1..1201).map { ref(it) } + ref(5)
        val batches = RevealHandoff.serverBatches(items)
        assertEquals(listOf(500, 500, 201), batches.map { it.size })
        assertEquals("item-1", batches[0].first().itemId)
        assertEquals("item-501", batches[1].first().itemId)
        assertEquals("item-1201", batches[2].last().itemId)
    }

    @Test
    fun aServerItemCarriesItsIdFingerprintsAndNameButNoCulling() {
        val single = RevealHandoff.serverItemFields(ref(7))
        assertEquals(listOf("itemId", "fingerprint", "name"), single.keys.toList())
        val pair = RevealHandoff.serverItemFields(ref(8, pair = true))
        assertEquals(listOf("itemId", "fingerprint", "fingerprints", "name"), pair.keys.toList())
        assertEquals(2, (pair["fingerprints"] as Array<*>).size)
        val unknown = RevealHandoff.serverItemFields(RevealHandoff.ServerItem("i", null, emptyList(), "x".repeat(400)))
        assertEquals(listOf("itemId", "name"), unknown.keys.toList())
        assertEquals(255, (unknown["name"] as String).length)
        assertThrows(IllegalArgumentException::class.java) { RevealHandoff.serverItemFields(RevealHandoff.ServerItem("", null, emptyList(), "a")) }
        listOf("rating", "flag", "label").forEach { key -> assert(key !in pair.keys) }
    }

    @Test
    fun fiveHundredReferencesStayFarUnderTheBinderLimit() {
        // Every key and value of a full batch of pairs, as UTF-16 in a Parcel (2 bytes a char, plus a header each).
        val bytes = RevealHandoff.serverBatches((1..500).map { ref(it, pair = true) }).first().sumOf { item ->
            RevealHandoff.serverItemFields(item).entries.sumOf { (k, v) ->
                val value = if (v is Array<*>) v.sumOf { (it as String).length * 2 + 8 } else (v as String).length * 2 + 8
                k.length * 2 + 8 + value + 16
            }
        }
        // About 320 KB for 500 pairs: a third of the 1 MB Binder buffer.
        assert(bytes < 400_000) { "batch of 500 is $bytes bytes" }
        assertEquals(90000L, RevealHandoff.MIN_SERVER_REF_VERSION_CODE)
    }

    @Test
    fun cullingVersionTwoAddsPerFieldTimesAndDevices() {
        assertEquals(2, RevealHandoff.CULLING_VERSION)
        val v1 = RevealHandoff.cullingFields(RevealHandoff.Culling(3, "pick"))
        assertEquals(listOf("rating", "flag"), v1.keys.toList())
        val v2 = RevealHandoff.cullingFields(RevealHandoff.Culling(3, "pick", "Red", 10L, 11L, 12L, "dev", "dev", null))
        assertEquals(listOf("rating", "flag", "label", "ratingModifiedAt", "flagModifiedAt", "labelModifiedAt", "ratingDevice", "flagDevice"), v2.keys.toList())
        assertEquals(11L, v2["flagModifiedAt"])
        // An older Reveal (before 0.9.0) gets version 1: the times, no devices.
        assertEquals(1, RevealHandoff.cullingVersionFor(70100))
        assertEquals(2, RevealHandoff.cullingVersionFor(90000))
        val older = RevealHandoff.cullingFields(RevealHandoff.Culling(3, "pick", "Red", 10L, 11L, 12L, "dev", "dev", null), version = 1)
        assertEquals(listOf("rating", "flag", "label", "ratingModifiedAt", "flagModifiedAt", "labelModifiedAt"), older.keys.toList())
    }

    private companion object {
        val XMP = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="2"/></rdf:RDF></x:xmpmeta>""".toByteArray()
    }
}
