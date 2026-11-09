package dev.hfps.hoard.smb.text

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Same cases as `src/lib/text/text.test.ts` (the TS codec the web mock uses). */
class TextCodecTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `detects utf-8 with and without a byte order mark`() {
        assertEquals(TextCodec.Decoded("Olá, mundo ✓", "utf-8"), TextCodec.decode("Olá, mundo ✓".toByteArray()))
        assertEquals(TextCodec.Decoded("hi", "utf-8-bom"), TextCodec.decode(bytes(0xef, 0xbb, 0xbf, 0x68, 0x69)))
        assertEquals(TextCodec.Decoded("", "utf-8"), TextCodec.decode(ByteArray(0)))
    }

    @Test
    fun `detects utf-16 from the byte order mark`() {
        assertEquals(TextCodec.Decoded("h日", "utf-16le"), TextCodec.decode(bytes(0xff, 0xfe, 0x68, 0x00, 0xe5, 0x65)))
        assertEquals(TextCodec.Decoded("h日", "utf-16be"), TextCodec.decode(bytes(0xfe, 0xff, 0x00, 0x68, 0x65, 0xe5)))
    }

    @Test
    fun `falls back to windows-1252 losslessly`() {
        val decoded = TextCodec.decode(bytes(0x52, 0x65, 0x6c, 0x61, 0x74, 0xf3, 0x72, 0x69, 0x6f, 0x20, 0x80, 0x97, 0x81))!!
        assertEquals("windows-1252", decoded.encoding)
        assertEquals("Relatório €—\u0081", decoded.text)
        val all = ByteArray(255) { (it + 1).toByte() }
        assertArrayEquals(all, TextCodec.encode(TextCodec.decode(all)!!.text, "windows-1252"))
    }

    @Test
    fun `nul bytes without a bom are binary`() {
        assertNull(TextCodec.decode(bytes(0x7f, 0x45, 0x4c, 0x46, 0x00)))
    }

    @Test
    fun `round-trips every encoding and keeps CRLF`() {
        val text = "Line 1\r\nação ✓ 日本 😀\n"
        for (encoding in listOf("utf-8", "utf-8-bom", "utf-16le", "utf-16be")) {
            assertEquals(TextCodec.Decoded(text, encoding), TextCodec.decode(TextCodec.encode(text, encoding)))
        }
        assertEquals(TextCodec.Decoded("ação €", "windows-1252"), TextCodec.decode(TextCodec.encode("ação €", "windows-1252")))
    }

    @Test
    fun `refuses characters windows-1252 cannot hold`() {
        try {
            TextCodec.encode("✓", "windows-1252")
            fail("expected UNKNOWN")
        } catch (e: HoardSmbException) {
            assertEquals(ErrorCode.UNKNOWN, e.code)
        }
    }

    @Test
    fun `cuts a prefix at a character boundary`() {
        val text = "aé日😀".toByteArray() // 1 + 2 + 3 + 4 bytes
        assertEquals(TextCodec.Decoded("aé", "utf-8"), TextCodec.decodePrefix(text, 5))
        assertEquals(TextCodec.Decoded("aé日", "utf-8"), TextCodec.decodePrefix(text, 7))
        assertEquals(TextCodec.Decoded("aé日😀", "utf-8"), TextCodec.decodePrefix(text, 10))
        val utf16 = TextCodec.encode("a😀b", "utf-16le")
        assertEquals(4, TextCodec.cutAtCharBoundary(utf16, 7, "utf-16le"))
        assertEquals(TextCodec.Decoded("a😀", "utf-16le"), TextCodec.decodePrefix(utf16, 8))
    }

    @Test
    fun `compares modified times by instant`() {
        assertTrue(TextFiles.sameInstant("2026-11-08T21:14:03.120Z", "2026-11-08T21:14:03.120Z"))
        assertTrue(TextFiles.sameInstant(null, null))
        assertFalse(TextFiles.sameInstant("2026-11-08T21:14:03.120Z", "2026-11-08T21:14:03.121Z"))
        assertFalse(TextFiles.sameInstant(null, "2026-11-08T21:14:03.120Z"))
    }
}
