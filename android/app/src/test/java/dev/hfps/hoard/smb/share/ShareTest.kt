package dev.hfps.hoard.smb.share

import org.junit.Assert.assertEquals
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
}
