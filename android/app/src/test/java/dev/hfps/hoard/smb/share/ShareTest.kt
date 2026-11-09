package dev.hfps.hoard.smb.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareTest {
    @Test
    fun `names from other apps are safe for FAT, exFAT and SMB`() {
        assertEquals("IMG_0001.jpg", SharedNames.clean("IMG_0001.jpg", 0))
        assertEquals("a_b_c.txt", SharedNames.clean("a:b?c.txt", 0))
        assertEquals("photo.jpg", SharedNames.clean("/storage/emulated/0/DCIM/photo.jpg", 0))
        assertEquals("shared-3", SharedNames.clean(null, 2))
        assertEquals("shared-1", SharedNames.clean("..", 0))
        assertEquals("notes", SharedNames.clean("notes. ", 0))
        assertEquals(200, SharedNames.clean("x".repeat(400), 0).length)
        assertEquals("Família — praia.jpg", SharedNames.clean("Família — praia.jpg", 0))
    }

    @Test
    fun `the share sheet gets the most specific common type`() {
        assertEquals("image/jpeg", ShareOut.commonType(listOf("image/jpeg", "image/jpeg")))
        assertEquals("image/*", ShareOut.commonType(listOf("image/jpeg", "image/png")))
        assertEquals("*/*", ShareOut.commonType(listOf("image/jpeg", "application/pdf")))
    }

    private val own = "dev.hfps.hoard"

    @Test
    fun `other apps' content URIs are accepted`() {
        assertNull(SharedUris.check("content", "media", own, "com.android.providers.media.module"))
        assertNull(SharedUris.check("content", "com.android.externalstorage.documents", own, "com.android.externalstorage"))
        assertNull(SharedUris.check("CONTENT", "com.whatsapp.provider.media", own, "com.whatsapp"))
        // Cross-user form (`0@media`).
        assertNull(SharedUris.check("content", "0@media", own, "com.android.providers.media.module"))
        // An authority Android does not resolve (provider gone): the open fails later, it is not ours.
        assertNull(SharedUris.check("content", "com.example.files", own, null))
    }

    @Test
    fun `file paths and anything but content are refused`() {
        assertEquals(SharedUris.Refusal.NOT_CONTENT, SharedUris.check("file", null, own, null))
        assertEquals(SharedUris.Refusal.NOT_CONTENT, SharedUris.check("file", "", own, null))
        assertEquals(SharedUris.Refusal.NOT_CONTENT, SharedUris.check("http", "example.com", own, null))
        assertEquals(SharedUris.Refusal.NOT_CONTENT, SharedUris.check(null, "media", own, null))
        assertEquals(SharedUris.Refusal.NO_AUTHORITY, SharedUris.check("content", "", own, null))
        assertEquals(SharedUris.Refusal.NO_AUTHORITY, SharedUris.check("content", null, own, null))
    }

    @Test
    fun `Hoard's own providers are refused`() {
        assertEquals(SharedUris.Refusal.OWN_PROVIDER, SharedUris.check("content", "dev.hfps.hoard.fileprovider", own, own))
        assertEquals(SharedUris.Refusal.OWN_PROVIDER, SharedUris.check("content", "dev.hfps.hoard.stream", own, null))
        assertEquals(SharedUris.Refusal.OWN_PROVIDER, SharedUris.check("content", "DEV.HFPS.HOARD.FileProvider", own, null))
        assertEquals(SharedUris.Refusal.OWN_PROVIDER, SharedUris.check("content", "0@dev.hfps.hoard.fileprovider", own, null))
        assertEquals(SharedUris.Refusal.OWN_PROVIDER, SharedUris.check("content", "dev.hfps.hoard", own, null))
        // A provider of Hoard's under an unrelated name (androidx startup, a library's provider).
        assertEquals(SharedUris.Refusal.OWN_PROVIDER, SharedUris.check("content", "some.library.provider", own, own))
        // Another app whose id merely starts with the same letters is not Hoard.
        assertNull(SharedUris.check("content", "dev.hfps.hoardother.files", own, "dev.hfps.hoardother"))
    }
}
