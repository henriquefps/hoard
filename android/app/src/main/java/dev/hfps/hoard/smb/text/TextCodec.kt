package dev.hfps.hoard.smb.text

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.smbFail
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Text encoding detection and encoding for `readTextFile` / `writeTextFile`. The same rules as
 * `src/lib/text/codec.ts` (keep them in step; both have tests):
 *
 *  1. A byte order mark wins: EF BB BF → `utf-8-bom`, FF FE → `utf-16le`, FE FF → `utf-16be`.
 *  2. A NUL byte without a BOM → binary (null).
 *  3. Valid UTF-8 → `utf-8` (pure ASCII included).
 *  4. Anything else → `windows-1252`, lossless: the five bytes Windows leaves undefined map to the
 *     C1 controls, so every byte decodes to one character and encodes back to the same byte.
 */
object TextCodec {

    const val UTF8 = "utf-8"
    const val UTF8_BOM = "utf-8-bom"
    const val UTF16LE = "utf-16le"
    const val UTF16BE = "utf-16be"
    const val CP1252 = "windows-1252"
    val ENCODINGS = setOf(UTF8, UTF8_BOM, UTF16LE, UTF16BE, CP1252)

    data class Decoded(val text: String, val encoding: String)

    private val CP1252_HIGH = intArrayOf(
        0x20ac, 0x81, 0x201a, 0x0192, 0x201e, 0x2026, 0x2020, 0x2021, 0x02c6, 0x2030, 0x0160, 0x2039,
        0x0152, 0x8d, 0x017d, 0x8f, 0x90, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x2013, 0x2014, 0x02dc,
        0x2122, 0x0161, 0x203a, 0x0153, 0x9d, 0x017e, 0x0178,
    )
    private val CP1252_REVERSE: Map<Int, Int> = CP1252_HIGH.withIndex().associate { (i, code) -> code to 0x80 + i }

    private fun ByteArray.at(i: Int) = if (i < size) this[i].toInt() and 0xff else -1

    /** Detect and decode; null when the bytes look binary. */
    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.at(0) == 0xef && bytes.at(1) == 0xbb && bytes.at(2) == 0xbf) {
            return Decoded(utf8Lenient(bytes, 3), UTF8_BOM)
        }
        if (bytes.at(0) == 0xff && bytes.at(1) == 0xfe) return Decoded(utf16(bytes, 2, littleEndian = true), UTF16LE)
        if (bytes.at(0) == 0xfe && bytes.at(1) == 0xff) return Decoded(utf16(bytes, 2, littleEndian = false), UTF16BE)
        if (bytes.any { it.toInt() == 0 }) return null
        utf8Strict(bytes)?.let { return Decoded(it, UTF8) }
        return Decoded(cp1252(bytes), CP1252)
    }

    /**
     * Decode the first [length] bytes of a longer file: the cut moves back to a character boundary
     * first, so a split UTF-8 sequence does not make valid UTF-8 look like windows-1252.
     */
    fun decodePrefix(bytes: ByteArray, length: Int): Decoded? {
        if (bytes.size <= length) return decode(bytes)
        val family = when {
            bytes.at(0) == 0xff && bytes.at(1) == 0xfe -> UTF16LE
            bytes.at(0) == 0xfe && bytes.at(1) == 0xff -> UTF16BE
            else -> UTF8
        }
        return decode(bytes.copyOf(cutAtCharBoundary(bytes, length, family)))
    }

    /** The largest end ≤ [max] that does not split a character of [encoding]. */
    fun cutAtCharBoundary(bytes: ByteArray, max: Int, encoding: String): Int {
        if (bytes.size <= max) return bytes.size
        var end = max
        when (encoding) {
            UTF8, UTF8_BOM -> {
                while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
            }
            UTF16LE, UTF16BE -> {
                end -= end % 2
                if (end >= 2) {
                    val high = if (encoding == UTF16LE) bytes.at(end - 1) else bytes.at(end - 2)
                    if (high in 0xd8..0xdb) end -= 2
                }
            }
        }
        return end
    }

    /** Encode for writing; rejects `UNKNOWN` when windows-1252 cannot hold a character. */
    fun encode(text: String, encoding: String): ByteArray = when (encoding) {
        UTF8 -> text.toByteArray(Charsets.UTF_8)
        UTF8_BOM -> byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + text.toByteArray(Charsets.UTF_8)
        UTF16LE, UTF16BE -> {
            val le = encoding == UTF16LE
            val out = ByteArray(2 + text.length * 2)
            out[0] = if (le) 0xff.toByte() else 0xfe.toByte()
            out[1] = if (le) 0xfe.toByte() else 0xff.toByte()
            text.forEachIndexed { i, char ->
                val unit = char.code
                out[2 + i * 2] = (if (le) unit and 0xff else unit shr 8).toByte()
                out[3 + i * 2] = (if (le) unit shr 8 else unit and 0xff).toByte()
            }
            out
        }
        CP1252 -> ByteArray(text.length) { i ->
            val code = text[i].code
            val byte = if (code < 0x80 || code in 0xa0..0xff) code else CP1252_REVERSE[code]
            (byte ?: smbFail(ErrorCode.UNKNOWN, "windows-1252 cannot hold the character '${text[i]}'")).toByte()
        }
        else -> smbFail(ErrorCode.UNKNOWN, "Unknown text encoding: $encoding")
    }

    private fun utf8Strict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun utf8Lenient(bytes: ByteArray, offset: Int) = String(bytes, offset, bytes.size - offset, Charsets.UTF_8)

    /** Manual, so lone surrogates survive like they do in JS (no replacement character). */
    private fun utf16(bytes: ByteArray, offset: Int, littleEndian: Boolean): String {
        val units = (bytes.size - offset) / 2
        val chars = CharArray(units) { i ->
            val a = bytes[offset + i * 2].toInt() and 0xff
            val b = bytes[offset + i * 2 + 1].toInt() and 0xff
            (if (littleEndian) a or (b shl 8) else (a shl 8) or b).toChar()
        }
        return String(chars)
    }

    private fun cp1252(bytes: ByteArray): String {
        val chars = CharArray(bytes.size) { i ->
            val b = bytes[i].toInt() and 0xff
            (if (b in 0x80..0x9f) CP1252_HIGH[b - 0x80] else b).toChar()
        }
        return String(chars)
    }
}
