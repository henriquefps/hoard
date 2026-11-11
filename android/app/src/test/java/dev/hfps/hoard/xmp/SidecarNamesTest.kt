package dev.hfps.hoard.xmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidecarNamesTest {
    private val pair = listOf("DSC1.ARW", "DSC1.JPG")

    @Test
    fun `candidates are the stem, each file, then their hidden forms`() {
        assertEquals(
            listOf("dsc1.xmp", "dsc1.arw.xmp", "dsc1.jpg.xmp", ".dsc1.xmp", ".dsc1.arw.xmp", ".dsc1.jpg.xmp"),
            SidecarNames.candidates("DSC1", pair),
        )
    }

    @Test
    fun `the normal stem name wins, then darktable's, then hidden ones, in any case`() {
        assertEquals("DSC1.xmp", SidecarNames.find("DSC1", pair, listOf(".DSC1.xmp", "DSC1.ARW.xmp", "DSC1.xmp", "DSC1.ARW")))
        assertEquals("dsc1.arw.XMP", SidecarNames.find("DSC1", pair, listOf(".DSC1.xmp", "dsc1.arw.XMP")))
        assertEquals(".DSC1.xmp", SidecarNames.find("DSC1", pair, listOf(".DSC1.xmp", "._DSC1.xmp", ".DSC1.JPG.xmp")))
        assertEquals("DSC1.JPG.xmp", SidecarNames.find("DSC1", pair, listOf("DSC1.JPG.xmp", ".DSC1.ARW.xmp")))
        assertNull(SidecarNames.find("DSC1", pair, listOf("DSC10.xmp", "DSC1.ARW", "._DSC1.xmp")))
    }

    @Test
    fun `the write target is the existing sidecar, else the stem, hidden on request`() {
        assertEquals("DSC1.ARW.xmp", SidecarNames.target("DSC1", "DSC1.ARW.xmp", hidden = true))
        assertEquals("DSC1.xmp", SidecarNames.target("DSC1", null, hidden = false))
        assertEquals(".DSC1.xmp", SidecarNames.target("DSC1", null, hidden = true))
    }

    @Test
    fun `AppleDouble files are not sidecars`() {
        assertFalse(SidecarNames.isSidecar("._DSC1.xmp"))
        assertTrue(SidecarNames.isSidecar(".DSC1.XMP"))
    }
}
