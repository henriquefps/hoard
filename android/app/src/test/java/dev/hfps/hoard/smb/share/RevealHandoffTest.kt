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
        assertThrows(HoardSmbException::class.java) { RevealHandoff.plan(emptyList<Pair<String, String>>(), null, null, false) }
    }

    @Test
    fun `the plan keeps the order and sends exactly the given URIs`() {
        val files = listOf("a.ARW" to "content://x/a.ARW", "a.jpg" to "content://x/a.jpg", "b.jpg" to "content://x/b.jpg")
        val plan = RevealHandoff.plan(files, "  Sony  ", "a.jpg", persistable = false)
        assertEquals(listOf("content://x/a.ARW", "content://x/a.jpg", "content://x/b.jpg"), plan.items)
        assertEquals("Sony", plan.title)
        assertEquals("content://x/a.jpg", plan.initial)
    }

    @Test
    fun `read grant always, persistable only when asked, never write`() {
        val files = listOf("a.jpg" to "u")
        val plain = RevealHandoff.plan(files, null, null, persistable = false).flags
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, plain)
        val persisted = RevealHandoff.plan(files, null, null, persistable = true).flags
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, persisted)
        assertEquals(0, persisted and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun `title is trimmed to 120 characters and blank means none, an initial not sent is dropped`() {
        val files = listOf("a.jpg" to "u")
        assertEquals(120, RevealHandoff.plan(files, "x".repeat(300), null, false).title!!.length)
        assertNull(RevealHandoff.plan(files, "   ", null, false).title)
        assertNull(RevealHandoff.plan(files, null, "elsewhere.jpg", false).initial)
    }

    @Test
    fun `the contract's names`() {
        assertEquals("dev.hfps.reveal.action.DEVELOP", RevealHandoff.ACTION)
        assertEquals("dev.hfps.reveal.extra.TITLE", RevealHandoff.EXTRA_TITLE)
        assertEquals("dev.hfps.reveal.extra.INITIAL_URI", RevealHandoff.EXTRA_INITIAL_URI)
    }
}
