package dev.hfps.hoard.smb

import com.hierynomus.mssmb2.SMB2Dialect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.UUID

class SmbConnectorTest {

    @Test
    fun `guids are compared lowercase without braces`() {
        assertEquals("6ba7b810-9dad-11d1-80b4-00c04fd430c8", SmbConnector.normalizeGuid("{6BA7B810-9DAD-11D1-80B4-00C04FD430C8}"))
        assertNull(SmbConnector.normalizeGuid("  "))
        assertNull(SmbConnector.normalizeGuid(null))
        val uuid = UUID.fromString("6BA7B810-9DAD-11D1-80B4-00C04FD430C8")
        assertEquals("6ba7b810-9dad-11d1-80b4-00c04fd430c8", SmbConnector.guidString(uuid))
    }

    @Test
    fun `dialects use the contract names`() {
        assertEquals("SMB_3_1_1", SmbConnector.dialectName(SMB2Dialect.SMB_3_1_1))
        assertEquals("SMB_3_0_2", SmbConnector.dialectName(SMB2Dialect.SMB_3_0_2))
        assertEquals("SMB_2_1", SmbConnector.dialectName(SMB2Dialect.SMB_2_1))
        assertEquals("SMB_2_0_2", SmbConnector.dialectName(SMB2Dialect.SMB_2_0_2))
    }

    @Test
    fun `a server that accepts TCP but never answers NEGOTIATE times out as TIMEOUT`() {
        ServerSocket(0).use { server ->
            val accepted = mutableListOf<java.net.Socket>()
            thread(isDaemon = true) { runCatching { while (true) accepted += server.accept() } }
            val started = System.nanoTime()
            try {
                runBlocking {
                    SmbConnector.open("127.0.0.1", server.localPort, null, 1_000) { fail("no credentials before NEGOTIATE"); null }
                }
                fail("expected TIMEOUT")
            } catch (e: HoardSmbException) {
                assertEquals(e.message, ErrorCode.TIMEOUT, e.code)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $elapsedMs ms", elapsedMs in 900..4_000)
            accepted.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `a closed port is UNREACHABLE`() {
        val port = ServerSocket(0).use { it.localPort }
        try {
            runBlocking { SmbConnector.open("127.0.0.1", port, null, 1_000) { null } }
            fail("expected UNREACHABLE")
        } catch (e: HoardSmbException) {
            assertEquals(ErrorCode.UNREACHABLE, e.code)
        }
    }

    @Test
    fun `domain can come from the username`() {
        assertEquals("henrique" to "WORKGROUP", SmbConnector.splitUser("WORKGROUP\\henrique", null))
        assertEquals("henrique" to "CORP", SmbConnector.splitUser("henrique", "CORP"))
        assertEquals("CORP\\henrique" to "OTHER", SmbConnector.splitUser("CORP\\henrique", "OTHER"))
        assertEquals("henrique@corp.local" to null, SmbConnector.splitUser("henrique@corp.local", " "))
    }
}
