package dev.hfps.hoard.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SmbPathsTest {

    private fun assertCode(code: ErrorCode, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: HoardSmbException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun `normalize trims separators and dots`() {
        assertEquals("", SmbPaths.normalize(""))
        assertEquals("", SmbPaths.normalize("/"))
        assertEquals("Photos/2026", SmbPaths.normalize("/Photos//2026/"))
        assertEquals("Photos/2026", SmbPaths.normalize("Photos\\2026"))
        assertEquals("Photos/a", SmbPaths.normalize("./Photos/./a"))
        assertCode(ErrorCode.UNKNOWN) { SmbPaths.normalize("Photos/../secret") }
    }

    @Test
    fun `smb paths use backslashes`() {
        assertEquals("Photos\\2026\\IMG.jpg", SmbPaths.toSmb("Photos/2026/IMG.jpg"))
        assertEquals("", SmbPaths.toSmb(""))
    }

    @Test
    fun `join name parent`() {
        assertEquals("a.txt", SmbPaths.join("", "a.txt"))
        assertEquals("x/a.txt", SmbPaths.join("x/", "a.txt"))
        assertEquals("IMG.jpg", SmbPaths.name("Photos/IMG.jpg"))
        assertEquals("Photos", SmbPaths.parent("Photos/IMG.jpg"))
        assertEquals("", SmbPaths.parent("IMG.jpg"))
        assertEquals("", SmbPaths.parent(""))
    }

    @Test
    fun `names are validated`() {
        assertEquals("New folder", SmbPaths.requireValidName(" New folder "))
        for (bad in listOf("", ".", "..", "a/b", "a\\b", "a:b", "a*", "what?", "end.", "x|y")) {
            assertCode(ErrorCode.UNKNOWN) { SmbPaths.requireValidName(bad) }
        }
    }

    @Test
    fun extensions() {
        assertEquals("jpg", SmbPaths.extension("IMG.JPG"))
        assertEquals("gz", SmbPaths.extension("a.tar.gz"))
        assertEquals("", SmbPaths.extension(".bashrc"))
        assertEquals("", SmbPaths.extension("README"))
    }

    @Test
    fun `conflict names count up like Android`() {
        assertEquals("photo.jpg", ConflictNames.firstFree("photo.jpg", listOf("other.jpg")))
        assertEquals("photo (1).jpg", ConflictNames.firstFree("photo.jpg", listOf("photo.jpg")))
        assertEquals("photo (2).jpg", ConflictNames.firstFree("photo.jpg", listOf("PHOTO.JPG", "photo (1).jpg")))
        assertEquals("photo (2).jpg", ConflictNames.firstFree("photo (1).jpg", listOf("photo (1).jpg")))
        assertEquals("README (1)", ConflictNames.firstFree("README", listOf("README")))
        assertEquals(".bashrc (1)", ConflictNames.firstFree(".bashrc", listOf(".bashrc")))
        assertEquals("a.tar (1).gz", ConflictNames.firstFree("a.tar.gz", listOf("a.tar.gz")))
    }

    @Test
    fun `iso timestamps are UTC with millis`() {
        assertEquals("2026-11-08T21:14:03.120Z", IsoTime.format(1_794_172_443_120L))
        assertEquals(1_794_172_443_120L, IsoTime.parse("2026-11-08T21:14:03.120Z"))
        assertNull(IsoTime.fromSmbMillis(0))
        assertNull(IsoTime.fromSmbMillis(-11_644_473_600_000L)) // FILETIME 0
        assertEquals("2026-11-08T21:14:03.120Z", IsoTime.fromSmbMillis(1_794_172_443_120L))
    }

    @Test
    fun `addresses are validated`() {
        assertEquals("192.168.1.155" to 445, Net.requireValidAddress(" 192.168.1.155 ", null))
        assertEquals("fe80::1" to 4450, Net.requireValidAddress("[fe80::1]", 4450))
        assertCode(ErrorCode.UNKNOWN) { Net.requireValidAddress("", 445) }
        assertCode(ErrorCode.UNKNOWN) { Net.requireValidAddress("nas", 0) }
        assertCode(ErrorCode.UNKNOWN) { Net.requireValidAddress("nas box", 445) }
    }
}
