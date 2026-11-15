package dev.hfps.hoard.smb.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a copy for Reveal goes (ADR 0010 amendment "2.2.0: local copies", `docs/testing/mediastore-raw.md`): Images
 * for every format Android's MIME map types as an image, Downloads (octet-stream, real name) for the rest (CR3).
 */
class MediaStoreTargetTest {
    // Android 16's MIME map for the formats of the risk check (CR3 has no entry).
    private val androidMap = mapOf(
        "arw" to "image/x-sony-arw", "nef" to "image/x-nikon-nef", "raf" to "image/x-fuji-raf", "dng" to "image/x-adobe-dng",
        "orf" to "image/x-olympus-orf", "rw2" to "image/x-panasonic-rw2", "jpg" to "image/jpeg", "heic" to "image/heic",
        "cr2" to "image/x-canon-cr2", "xmp" to "application/rdf+xml",
    )
    private fun target(name: String) = MediaStoreTarget.forFormat(name) { androidMap[it] }

    @Test
    fun `formats MediaStore Images accepted go there with their MIME type`() {
        for ((name, mime) in listOf(
            "DSC1.ARW" to "image/x-sony-arw", "DSC1.NEF" to "image/x-nikon-nef", "DSC1.RAF" to "image/x-fuji-raf",
            "DSC1.DNG" to "image/x-adobe-dng", "P1.ORF" to "image/x-olympus-orf", "P1.RW2" to "image/x-panasonic-rw2",
            "IMG.jpg" to "image/jpeg", "IMG.HEIC" to "image/heic",
        )) {
            assertEquals(name, MediaStoreTarget.Target(MediaStoreTarget.Collection.IMAGES, mime), target(name))
        }
    }

    @Test
    fun `CR3 and anything else Images refuses goes to Downloads as octet-stream`() {
        val downloads = MediaStoreTarget.Target(MediaStoreTarget.Collection.DOWNLOADS, "application/octet-stream")
        assertEquals(downloads, target("IMG_0001.CR3"))
        assertEquals(downloads, target("IMG_0001.cr3"))
        assertEquals(downloads, target("noextension"))
        assertEquals(downloads, target("shot.xmp"))
        assertEquals(downloads, MediaStoreTarget.forFormat("x.weird") { "image/*" })
    }

    @Test
    fun `relative paths and batch folders`() {
        assertEquals("Pictures/Hoard/Trip/", MediaStoreTarget.relativePath(MediaStoreTarget.Collection.IMAGES, "Trip"))
        assertEquals("Download/Hoard/Trip/", MediaStoreTarget.relativePath(MediaStoreTarget.Collection.DOWNLOADS, "Trip"))
        assertEquals("Pictures/Hoard/", MediaStoreTarget.relativePath(MediaStoreTarget.Collection.IMAGES, ""))
        assertEquals("2026-11-15", MediaStoreTarget.folderName(null, "2026-11-15"))
        assertEquals("2026-11-15", MediaStoreTarget.folderName("  ../  ", "2026-11-15"))
        assertEquals("Sony a6000 shoot", MediaStoreTarget.folderName("Sony/a6000:  shoot", "x"))
        assertEquals(80, MediaStoreTarget.folderName("y".repeat(300), "x").length)
    }
}
