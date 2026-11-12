package dev.hfps.hoard.smb.handoff

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.stream.StreamAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffLogicTest {
    private val lan = StreamAddress("192.168.1.2", 445)
    private val vpn = StreamAddress("100.64.0.2", 445)
    private val other = StreamAddress("10.0.0.9", 4450)

    @Test
    fun `the last good address goes first, then the priority order`() {
        assertEquals(listOf(lan, vpn, other), NativeRoute.order(listOf(lan, vpn, other), null))
        assertEquals(listOf(vpn, lan, other), NativeRoute.order(listOf(lan, vpn, other), vpn))
        assertEquals(listOf(lan, vpn), NativeRoute.order(listOf(lan, vpn, lan), StreamAddress("gone", 445)))
    }

    @Test
    fun `another server's GUID and dead addresses are skipped`() {
        val tried = mutableListOf<StreamAddress>()
        val (result, index) = NativeRoute.first(listOf(lan, vpn, other)) { address ->
            tried += address
            when (address) {
                lan -> throw HoardSmbException(ErrorCode.GUID_MISMATCH, "someone else")
                vpn -> throw HoardSmbException(ErrorCode.UNREACHABLE, "down")
                else -> "connected"
            }
        }
        assertEquals("connected", result)
        assertEquals(2, index)
        assertEquals(listOf(lan, vpn, other), tried)
    }

    @Test
    fun `a refused login stops at once, all dead throws the last error`() {
        val tried = mutableListOf<StreamAddress>()
        val auth = assertThrows(HoardSmbException::class.java) {
            NativeRoute.first(listOf(lan, vpn)) { tried += it; throw HoardSmbException(ErrorCode.AUTH_FAILED, "no") }
        }
        assertEquals(ErrorCode.AUTH_FAILED, auth.code)
        assertEquals(listOf(lan), tried)
        val dead = assertThrows(HoardSmbException::class.java) {
            NativeRoute.first(listOf(lan, vpn)) { throw HoardSmbException(if (it == lan) ErrorCode.TIMEOUT else ErrorCode.UNREACHABLE, "x") }
        }
        assertEquals(ErrorCode.UNREACHABLE, dead.code)
        assertEquals(ErrorCode.UNREACHABLE, assertThrows(HoardSmbException::class.java) { NativeRoute.first(emptyList<StreamAddress>()) { 1 } }.code)
    }

    @Test
    fun `a GUID mismatch is final for ordinary streams`() {
        val error = assertThrows(HoardSmbException::class.java) {
            NativeRoute.first(listOf(lan, vpn), dev.hfps.hoard.smb.stream.SmbSource.FATAL) {
                if (it == lan) throw HoardSmbException(ErrorCode.GUID_MISMATCH, "x") else "ok"
            }
        }
        assertEquals(ErrorCode.GUID_MISMATCH, error.code)
    }

    @Test
    fun `hand-off URIs are parsed by id, never by path`() {
        val id = "a".repeat(64)
        assertEquals(id, HandoffUris.idOf(listOf("h", id, "tag/DSC1.ARW")))
        assertNull(HandoffUris.idOf(listOf("h", id, "DSC1.ARW")))
        assertNull(HandoffUris.idOf(listOf("h", id, "tag/sub/DSC1.ARW")))
        assertNull(HandoffUris.idOf(listOf("h", "../../etc", "tag/x")))
        assertNull(HandoffUris.idOf(listOf("h", id)))
        assertNull(HandoffUris.idOf(listOf("x", id, "tag/x")))
        assertNull(HandoffUris.idOf(listOf(id, "x")))
        assertTrue(HandoffUris.isHandoff(listOf("h", "x")))
        assertFalse(HandoffUris.isHandoff(listOf(id, "x.jpg")))
        assertEquals("t/a.ARW", HandoffUris.lastSegment("t", "a.ARW"))
    }

    @Test
    fun `photos are read-only, sidecars may be written in any mode`() {
        assertEquals(HandoffAccess.READ, HandoffAccess.of("r", sidecar = false))
        for (mode in listOf("w", "wt", "rw", "rwt", "wa")) {
            assertThrows(SecurityException::class.java) { HandoffAccess.of(mode, sidecar = false) }
        }
        assertEquals(HandoffAccess.READ, HandoffAccess.of("r", sidecar = true))
        assertEquals(HandoffAccess.WRITE_TRUNCATE, HandoffAccess.of("w", sidecar = true))
        assertEquals(HandoffAccess.WRITE_TRUNCATE, HandoffAccess.of("wt", sidecar = true))
        assertEquals(HandoffAccess.WRITE_TRUNCATE, HandoffAccess.of("rwt", sidecar = true))
        assertEquals(HandoffAccess.READ_WRITE, HandoffAccess.of("rw", sidecar = true))
        assertEquals(HandoffAccess.APPEND, HandoffAccess.of("wa", sidecar = true))
        assertThrows(IllegalArgumentException::class.java) { HandoffAccess.of("x", sidecar = true) }
    }

    @Test
    fun `the sidecar buffer reads, writes, appends and only commits well-formed XMP`() {
        val buffer = SidecarBuffer(XMP, append = false)
        val out = ByteArray(XMP.size)
        assertEquals(XMP.size, buffer.read(0, XMP.size, out))
        assertArrayEquals(XMP, out)
        assertNull(buffer.commitBytes()) // nothing changed

        val rewritten = SidecarBuffer(ByteArray(0), append = false)
        assertTrue(rewritten.write(0, 10, XMP))
        assertTrue(rewritten.write(10, XMP.size - 10, XMP.copyOfRange(10, XMP.size)))
        assertArrayEquals(XMP, rewritten.commitBytes())
        assertFalse(rewritten.dirty)

        val garbage = SidecarBuffer(ByteArray(0), append = false)
        garbage.write(0, 3, "abc".toByteArray())
        assertNull(garbage.commitBytes())

        val append = SidecarBuffer("ab".toByteArray(), append = true)
        append.write(0, 2, "cd".toByteArray())
        val bytes = ByteArray(4)
        append.read(0, 4, bytes)
        assertEquals("abcd", String(bytes))

        val small = SidecarBuffer(ByteArray(0), append = false, max = 8)
        assertFalse(small.write(4, 5, ByteArray(5)))
        assertTrue(small.write(0, 8, ByteArray(8)))
    }

    private companion object {
        val XMP = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:Rating="3"/></rdf:RDF></x:xmpmeta>""".toByteArray()
    }
}
