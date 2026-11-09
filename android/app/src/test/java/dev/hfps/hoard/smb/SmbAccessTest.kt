package dev.hfps.hoard.smb

import com.hierynomus.msdtyp.AccessMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.EnumSet

class SmbAccessTest {

    private fun decide(mask: Long?) = SmbAccess.fromMask(mask)

    @Test
    fun `full control and the usual writable answers are readWrite`() {
        assertEquals(SmbAccess.READ_WRITE, decide(0x001F01FFL)) // FILE_ALL_ACCESS (Samba, Windows owner)
        assertEquals(SmbAccess.READ_WRITE, decide(0x001301BFL)) // Windows "Modify"
        assertEquals(SmbAccess.READ_WRITE, decide(0x00000002L or 0x00000001L)) // add file + list
        assertEquals(SmbAccess.READ_WRITE, decide(0x00000004L or 0x00000001L)) // add subdirectory + list
        assertEquals(SmbAccess.READ_WRITE, decide(0x00000040L or 0x00000001L)) // delete child
        assertEquals(SmbAccess.READ_WRITE, decide(0x10000000L)) // GENERIC_ALL
        assertEquals(SmbAccess.READ_WRITE, decide(0x40000000L or 0x80000000L)) // GENERIC_WRITE | GENERIC_READ
    }

    @Test
    fun `read-only answers are read`() {
        assertEquals(SmbAccess.READ, decide(0x001200A9L)) // Windows "Read & execute"
        assertEquals(SmbAccess.READ, decide(0x00120089L)) // FILE_GENERIC_READ
        assertEquals(SmbAccess.READ, decide(0x80000000L)) // GENERIC_READ
        // Samba `read only = yes`: write bits stripped, DELETE and ownership rights kept.
        assertEquals(SmbAccess.READ, decide(0x001F01FFL and (0x2L or 0x4L or 0x10L or 0x100L or 0x40L).inv()))
        // DELETE / WRITE_DAC / WRITE_OWNER / attribute and EA writes alone do not make it writable.
        assertEquals(SmbAccess.READ, decide(0x000100A9L or 0x000C0000L or 0x110L))
    }

    @Test
    fun `no usable answer is unknown`() {
        assertNull(decide(null))
        assertNull(decide(0L))
        assertNull(decide(0x02000000L)) // MAXIMUM_ALLOWED alone is a request flag, not a grant
        assertNull(decide(0x00100000L)) // SYNCHRONIZE alone
    }

    @Test
    fun `enum sets from smbj convert with overlapping aliases`() {
        // smbj's AccessMask has aliases with the same bit (FILE_ADD_FILE = FILE_WRITE_DATA).
        val set = EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_DATA, AccessMask.FILE_ADD_FILE, AccessMask.FILE_WRITE_DATA)
        assertEquals(0x3L, SmbAccess.toLong(set))
        assertEquals(SmbAccess.READ_WRITE, SmbAccess.fromMask(set))
        assertEquals(SmbAccess.READ, SmbAccess.fromMask(EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.DELETE)))
        assertNull(SmbAccess.fromMask(null as Set<AccessMask>?))
    }

    @Test
    fun `share level read-only wins, otherwise the most specific answer`() {
        assertEquals(SmbAccess.READ, SmbAccess.combine(SmbAccess.READ, SmbAccess.READ_WRITE))
        assertEquals(SmbAccess.READ, SmbAccess.combine(SmbAccess.READ_WRITE, SmbAccess.READ))
        assertEquals(SmbAccess.READ, SmbAccess.combine(SmbAccess.READ, null))
        assertEquals(SmbAccess.READ, SmbAccess.combine(null, SmbAccess.READ))
        assertEquals(SmbAccess.READ_WRITE, SmbAccess.combine(SmbAccess.READ_WRITE, SmbAccess.READ_WRITE))
        assertEquals(SmbAccess.READ_WRITE, SmbAccess.combine(null, SmbAccess.READ_WRITE))
        assertEquals(SmbAccess.READ_WRITE, SmbAccess.combine(SmbAccess.READ_WRITE, null))
        assertNull(SmbAccess.combine(null, null))
    }

    @Test
    fun `MxAc response context parsing`() {
        val ok = byteArrayOf(0, 0, 0, 0, 0xFF.toByte(), 0x01, 0x1F, 0x00)
        assertEquals(0x001F01FFL, SmbAccess.parseMaximalAccessResponse(ok))
        val denied = byteArrayOf(0x22, 0, 0, 0xC0.toByte(), 0xFF.toByte(), 0x01, 0x1F, 0x00)
        assertNull(SmbAccess.parseMaximalAccessResponse(denied)) // QueryStatus = ACCESS_DENIED
        assertNull(SmbAccess.parseMaximalAccessResponse(byteArrayOf(0, 0, 0, 0)))
        assertNull(SmbAccess.parseMaximalAccessResponse(null))
        val high = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0x80.toByte())
        assertEquals(0x80000000L, SmbAccess.parseMaximalAccessResponse(high))
    }

    @Test
    fun `a refused write probe makes a writable MxAc answer read-only (macOS read-only share)`() {
        // macOS: tree and MxAc say full access, but the CREATE asking for FILE_ADD_FILE is refused.
        assertEquals(SmbAccess.READ, SmbAccess.decide(SmbAccess.READ_WRITE, 0x001F01FFL, WriteProbe.DENIED))
        assertEquals(SmbAccess.READ, SmbAccess.decide(null, null, WriteProbe.DENIED))
    }

    @Test
    fun `a granted write probe is writable, but a read-only share still wins`() {
        assertEquals(SmbAccess.READ_WRITE, SmbAccess.decide(SmbAccess.READ_WRITE, 0x001F01FFL, WriteProbe.GRANTED))
        assertEquals(SmbAccess.READ_WRITE, SmbAccess.decide(null, null, WriteProbe.GRANTED)) // no MxAc answer
        assertEquals(SmbAccess.READ, SmbAccess.decide(SmbAccess.READ, 0x001F01FFL, WriteProbe.GRANTED))
    }

    @Test
    fun `without a probe answer MxAc decides as before`() {
        assertEquals(SmbAccess.READ_WRITE, SmbAccess.decide(SmbAccess.READ_WRITE, 0x001F01FFL, null))
        assertEquals(SmbAccess.READ, SmbAccess.decide(SmbAccess.READ_WRITE, 0x001200A9L, null)) // POSIX 755, not owner
        assertEquals(SmbAccess.READ, SmbAccess.decide(SmbAccess.READ, 0x001F01FFL, null)) // Samba read only = yes
        assertEquals(SmbAccess.READ_WRITE, SmbAccess.decide(null, 0x001F01FFL, null))
        assertNull(SmbAccess.decide(null, null, null))
    }

    @Test
    fun `share level answers combine with read winning`() {
        assertEquals(SmbAccess.READ, SmbAccess.both(SmbAccess.READ_WRITE, SmbAccess.READ)) // macOS read-only share
        assertEquals(SmbAccess.READ, SmbAccess.both(SmbAccess.READ, null)) // Samba read only = yes
        assertEquals(SmbAccess.READ_WRITE, SmbAccess.both(SmbAccess.READ_WRITE, SmbAccess.READ_WRITE))
        assertEquals(SmbAccess.READ_WRITE, SmbAccess.both(null, SmbAccess.READ_WRITE))
        assertNull(SmbAccess.both(null, null))
    }

    @Test
    fun `only access denied and write protected count as a refused write`() {
        assertEquals(true, WriteProbe.isDenial(0xC0000022L)) // STATUS_ACCESS_DENIED
        assertEquals(true, WriteProbe.isDenial(0xC00000A2L)) // STATUS_MEDIA_WRITE_PROTECTED
        assertEquals(false, WriteProbe.isDenial(0xC0000043L)) // STATUS_SHARING_VIOLATION
        assertEquals(false, WriteProbe.isDenial(0xC0000034L)) // STATUS_OBJECT_NAME_NOT_FOUND
    }

    @Test
    fun `read-only hints last a minute per share and path, writable answers clear them`() {
        var now = 0L
        val hints = ReadOnlyHints(ttlMs = 60_000, maxEntries = 3, nowNanos = { now })
        hints.remember("Macintosh HD", "Applications", SmbAccess.READ)
        assertEquals(true, hints.isReadOnly("macintosh hd", "Applications")) // share names ignore case
        assertEquals(false, hints.isReadOnly("Macintosh HD", "applications")) // paths do not
        assertEquals(false, hints.isReadOnly("StorageExtension", "Applications"))
        now += 59_000_000_000L
        assertEquals(true, hints.isReadOnly("Macintosh HD", "Applications"))
        now += 2_000_000_000L
        assertEquals(false, hints.isReadOnly("Macintosh HD", "Applications")) // expired
        hints.remember("rw", "open", SmbAccess.READ)
        hints.remember("rw", "open", SmbAccess.READ_WRITE)
        assertEquals(false, hints.isReadOnly("rw", "open"))
        hints.remember("rw", "x", null)
        assertEquals(false, hints.isReadOnly("rw", "x"))
        listOf("a", "b", "c", "d").forEach { hints.remember("rw", it, SmbAccess.READ) } // bounded
        assertEquals(true, hints.isReadOnly("rw", "d"))
    }
}
