package dev.hfps.hoard.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareSecurityTest {

    private data class Ace(val type: Int, val mask: Long, val sid: String, val flags: Int = 0)

    private fun sidBytes(sid: String): ByteArray {
        val parts = sid.removePrefix("S-").split("-").map { it.toLong() }
        val out = SrvSvc.Le()
        out.u8(parts[0].toInt()).u8(parts.size - 2)
        val authority = parts[1]
        for (shift in 40 downTo 0 step 8) out.u8(((authority shr shift) and 0xFF).toInt())
        parts.drop(2).forEach { out.u32(it) }
        return out.bytes()
    }

    /** Self-relative descriptor shaped like the ones macOS sends (owner SYSTEM, group Administrators). */
    private fun sd(aces: List<Ace>?, control: Int = 0x8014): ByteArray {
        val owner = sidBytes("S-1-5-18")
        val group = sidBytes("S-1-5-32-544")
        val aceBytes = aces.orEmpty().map { ace ->
            val sid = sidBytes(ace.sid)
            SrvSvc.Le().u8(ace.type).u8(ace.flags).u16(8 + sid.size).u32(ace.mask).raw(sid).bytes()
        }
        val aclSize = 8 + aceBytes.sumOf { it.size }
        val daclOffset = if (aces == null) 0 else 20
        val ownerOffset = 20 + (if (aces == null) 0 else aclSize)
        val out = SrvSvc.Le()
        out.u8(1).u8(0).u16(control)
        out.u32(ownerOffset.toLong()).u32((ownerOffset + owner.size).toLong()).u32(0).u32(daclOffset.toLong())
        if (aces != null) {
            out.u8(2).u8(0).u16(aclSize).u16(aces.size).u16(0)
            aceBytes.forEach { out.raw(it) }
        }
        out.raw(owner).raw(group)
        return out.bytes()
    }

    private val nullSidDeny = Ace(1, 0x001F01FFL, "S-1-0-0")

    @Test
    fun `macOS read-only share grants Everyone read only`() {
        // NetrShareEnum level 502 of the owner's Mac, share "Macintosh HD" (read-only: 1).
        assertEquals(SmbAccess.READ, ShareSecurity.accessForEveryone(sd(listOf(nullSidDeny, Ace(0, 0x00120089L, "S-1-1-0")))))
    }

    @Test
    fun `macOS writable share grants Everyone full access`() {
        assertEquals(SmbAccess.READ_WRITE, ShareSecurity.accessForEveryone(sd(listOf(nullSidDeny, Ace(0, 0x001F01FFL, "S-1-1-0")))))
    }

    @Test
    fun `an ACE for another SID cannot be evaluated`() {
        // macOS home share: a single ACE for the user's own SID.
        val user = Ace(0, 0x001F01FFL, "S-1-5-21-2825015603-2718167110-579334000-2002")
        assertNull(ShareSecurity.accessForEveryone(sd(listOf(nullSidDeny, user))))
        // Windows: Everyone read plus Administrators full.
        assertNull(ShareSecurity.accessForEveryone(sd(listOf(Ace(0, 0x001200A9L, "S-1-1-0"), Ace(0, 0x001F01FFL, "S-1-5-32-544")))))
    }

    @Test
    fun `an Everyone deny of write rights before the allow wins`() {
        val aces = listOf(Ace(1, 0x00000046L, "S-1-1-0"), Ace(0, 0x001F01FFL, "S-1-1-0")) // add file, add subfolder, delete child
        assertEquals(SmbAccess.READ, ShareSecurity.accessForEveryone(sd(aces)))
        // A deny after a grant of the same right does not take it back (canonical order).
        val late = listOf(Ace(0, 0x001F01FFL, "S-1-1-0"), Ace(1, 0x00000046L, "S-1-1-0"))
        assertEquals(SmbAccess.READ_WRITE, ShareSecurity.accessForEveryone(sd(late)))
    }

    @Test
    fun `inherit-only ACEs are ignored`() {
        val aces = listOf(Ace(0, 0x00120089L, "S-1-1-0"), Ace(0, 0x001F01FFL, "S-1-5-32-544", flags = 0x08))
        assertEquals(SmbAccess.READ, ShareSecurity.accessForEveryone(sd(aces)))
    }

    @Test
    fun `missing, NULL or malformed descriptors are unknown`() {
        assertNull(ShareSecurity.accessForEveryone(null))
        assertNull(ShareSecurity.accessForEveryone(ByteArray(0)))
        assertNull(ShareSecurity.accessForEveryone(sd(null))) // NULL DACL: no restriction stated
        assertNull(ShareSecurity.accessForEveryone(sd(emptyList()))) // empty DACL: nothing granted
        val good = sd(listOf(Ace(0, 0x00120089L, "S-1-1-0")))
        assertNull(ShareSecurity.accessForEveryone(good.copyOf(good.size - 30))) // truncated ACE
    }
}
