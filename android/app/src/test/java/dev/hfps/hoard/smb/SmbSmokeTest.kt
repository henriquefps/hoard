package dev.hfps.hoard.smb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in network smoke test (skipped in the default run): reads each server's GUID with an
 * unauthenticated NEGOTIATE and checks that all addresses answer with the same one.
 *
 *     HOARD_SMOKE_HOSTS="192.168.1.155:445,100.104.128.114:445" ./gradlew testDebugUnitTest --tests '*SmbSmokeTest*'
 *
 * It passes a deliberately wrong pinned GUID: the connector must stop with GUID_MISMATCH (carrying
 * the real GUID) before it ever asks for credentials.
 */
class SmbSmokeTest {

    @Test
    fun `every address of a machine reports the same server GUID`() = runBlocking {
        val hosts = System.getenv("HOARD_SMOKE_HOSTS").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        assumeTrue("HOARD_SMOKE_HOSTS not set", hosts.isNotEmpty())
        val guids = hosts.map { target ->
            val host = target.substringBeforeLast(':')
            val port = target.substringAfterLast(':', "445").toInt()
            try {
                SmbConnector.open(host, port, "00000000-0000-0000-0000-000000000000", 8_000) {
                    throw AssertionError("credentials must not be read before the GUID check")
                }.close()
                throw AssertionError("expected GUID_MISMATCH from $target")
            } catch (e: HoardSmbException) {
                assertEquals("$target: ${e.message}", ErrorCode.GUID_MISMATCH, e.code)
                val guid = e.data["serverGuid"] as String
                println("SMOKE $target → serverGuid=$guid")
                guid
            }
        }
        assertTrue("GUIDs differ: $guids", guids.toSet().size == 1)
        assertTrue(guids.first().matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }
}
