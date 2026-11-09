package dev.hfps.hoard.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SrvSvcTest {

    @Test
    fun `bind PDU carries srvsvc v3 and NDR v2`() {
        val pdu = SrvSvc.encodeBind(callId = 1)
        assertEquals(72, pdu.size)
        assertEquals(72, (pdu[8].toInt() and 0xFF) or ((pdu[9].toInt() and 0xFF) shl 8)) // frag length
        assertEquals(11, pdu[2].toInt()) // bind
        // srvsvc UUID 4b324fc8-1670-01d3-1278-5a47bf6ee188 in NDR byte order.
        val expected = byteArrayOf(
            0xc8.toByte(), 0x4f, 0x32, 0x4b, 0x70, 0x16, 0xd3.toByte(), 0x01,
            0x12, 0x78, 0x5a, 0x47, 0xbf.toByte(), 0x6e, 0xe1.toByte(), 0x88.toByte(),
        )
        assertArrayEquals(expected, pdu.copyOfRange(32, 48))
    }

    @Test
    fun `share enum response is decoded`() {
        // Stub of a NetrShareEnum level-1 response with three shares, as a server marshals it.
        val out = SrvSvc.Le()
        out.u32(1).u32(1) // level, switch
        out.u32(0x20000) // container pointer
        out.u32(3).u32(0x20004) // EntriesRead, Buffer pointer
        out.u32(3) // conformant array max count
        out.u32(0x20008).u32(0).u32(0x2000c) // media: disk
        out.u32(0x20010).u32(0x80000003L).u32(0x20014) // IPC$: special IPC
        out.u32(0x20018).u32(0x80000000L).u32(0) // ADMIN$: special disk, no remark
        out.conformantString("media").conformantString("Media files")
        out.conformantString("IPC$").conformantString("IPC Service")
        out.conformantString("ADMIN$")
        out.u32(3) // TotalEntries
        out.u32(0x2001c).u32(0) // ResumeHandle
        out.u32(0) // WERROR_OK
        val shares = SrvSvc.decodeShareEnum(out.bytes())
        assertEquals(listOf("media", "IPC$", "ADMIN$"), shares.map { it.name })
        assertEquals("Media files", shares[0].remark)
        assertTrue(shares[0].isDisk)
        assertFalse(shares[0].isHidden)
        assertFalse(shares[1].isDisk)
        assertTrue(shares[2].isDisk)
        assertTrue(shares[2].isHidden)
        assertEquals(null, shares[2].remark)
    }

    @Test
    fun `access denied WERROR is ACCESS_DENIED`() {
        val out = SrvSvc.Le()
        out.u32(1).u32(1).u32(0x20000).u32(0).u32(0) // empty container
        out.u32(0).u32(0) // total, null resume handle
        out.u32(5) // WERROR_ACCESS_DENIED
        try {
            SrvSvc.decodeShareEnum(out.bytes())
            throw AssertionError("expected ACCESS_DENIED")
        } catch (e: HoardSmbException) {
            assertEquals(ErrorCode.ACCESS_DENIED, e.code)
        }
    }

    @Test
    fun `share enum level 502 is requested and decoded with each security descriptor`() {
        val request = SrvSvc.encodeShareEnum("mac", 502)
        // Level and union switch are the first two of the eight trailing u32 fields.
        val levelOffset = request.size - 32
        assertEquals(502, (request[levelOffset].toInt() and 0xFF) or ((request[levelOffset + 1].toInt() and 0xFF) shl 8))
        assertEquals(502, (request[levelOffset + 4].toInt() and 0xFF) or ((request[levelOffset + 5].toInt() and 0xFF) shl 8))

        // Everyone read only (macOS "Macintosh HD", read-only: 1).
        val sd = SrvSvc.Le()
            .u8(1).u8(0).u16(0x8014).u32(0).u32(0).u32(0).u32(20) // header, DACL at 20
            .u8(2).u8(0).u16(8 + 20).u16(1).u16(0) // ACL: one ACE
            .u8(0).u8(0).u16(20).u32(0x00120089L) // allow, read
            .u8(1).u8(1).raw(byteArrayOf(0, 0, 0, 0, 0, 1)).u32(0) // S-1-1-0
            .bytes()
        val out = SrvSvc.Le()
        out.u32(502).u32(502) // level, switch
        out.u32(0x20000) // container pointer
        out.u32(3).u32(0x20004) // EntriesRead, Buffer pointer
        out.u32(3) // max count
        // netname*, type, remark*, permissions, max_uses, current_uses, path*, passwd*, reserved, sd*
        out.u32(0x20008).u32(0x80000003L).u32(0x2000c).u32(0).u32(0xFFFFFFFFL).u32(1).u32(0x20010).u32(0).u32(0).u32(0) // IPC$
        out.u32(0x20014).u32(0).u32(0x20018).u32(0).u32(0xFFFFFFFFL).u32(0).u32(0x2001c).u32(0).u32(sd.size.toLong()).u32(0x20020)
        out.u32(0x20024).u32(0).u32(0).u32(0).u32(0xFFFFFFFFL).u32(0).u32(0x20028).u32(0).u32(0).u32(0) // Samba: no descriptor
        out.conformantString("IPC$").conformantString("IPC Service").conformantString("")
        out.conformantString("Macintosh HD").conformantString("").conformantString("C:\\")
        out.u32(sd.size.toLong()).raw(sd).align4()
        out.conformantString("rw").conformantString("C:\\srv\\rw")
        out.u32(3) // TotalEntries
        out.u32(0x2002c).u32(0) // ResumeHandle
        out.u32(0) // WERROR_OK
        val shares = SrvSvc.decodeShareEnum(out.bytes())
        assertEquals(listOf("IPC$", "Macintosh HD", "rw"), shares.map { it.name })
        assertEquals("IPC Service", shares[0].remark)
        assertEquals(null, shares[1].remark)
        assertEquals(SmbAccess.READ, shares[1].everyoneAccess)
        assertEquals(null, shares[2].everyoneAccess)
        assertTrue(shares[1].isDisk)
        assertFalse(shares[0].isDisk)
    }
}
