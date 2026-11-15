package dev.hfps.hoard.smb.share

import android.content.Intent
import dev.hfps.hoard.smb.HoardSmbException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

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
        assertThrows(HoardSmbException::class.java) { RevealHandoff.plan(emptyList<Pair<String, String>>(), null, null) }
    }

    @Test
    fun `the plan keeps the order and sends exactly the given URIs`() {
        val files = listOf(
            "a.ARW" to "content://media/external_primary/images/media/1",
            "a.jpg" to "content://media/external_primary/images/media/2",
            "b.CR3" to "content://media/external_primary/downloads/3",
        )
        val plan = RevealHandoff.plan(files, "  Sony  ", "a.jpg")
        assertEquals(files.map { it.second }, plan.items)
        assertEquals("Sony", plan.title)
        assertEquals("content://media/external_primary/images/media/2", plan.initial)
    }

    @Test
    fun `2_2_0 - every item gets a plain read grant, never persistable, never write`() {
        val plan = RevealHandoff.plan(listOf("a.jpg" to "u"), null, null)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, plan.flags)
        assertEquals(0, plan.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, plan.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, RevealHandoff.ITEM_GRANT)
    }

    @Test
    fun `title is trimmed to 120 characters and blank means none, an initial not sent is dropped`() {
        val files = listOf("a.jpg" to "u")
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
