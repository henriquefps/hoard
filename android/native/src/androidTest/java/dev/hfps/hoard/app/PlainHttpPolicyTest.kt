package dev.hfps.hoard.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.MemoryKeyVault
import dev.hfps.hoard.server.PairingCode
import dev.hfps.hoard.server.db.GalleryDatabase
import dev.hfps.hoard.server.net.InsecureAddressException
import dev.hfps.hoard.server.sync.DeviceInfo
import dev.hfps.hoard.server.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * ADR 0021 "Plain HTTP", on a device, under the app's own network security config. Opt-in (it pairs with a real test
 * server): `-e hoardPlainHttp '<revealsync://pair?… link of a test hoard-server on the LAN>'`, e.g. a container on the
 * emulator's host at `http://10.0.2.2:<port>`. Never against the owner's server.
 */
@RunWith(AndroidJUnit4::class)
class PlainHttpPolicyTest {
    private val link: String? = InstrumentationRegistry.getArguments().getString("hoardPlainHttp")

    private fun plainGet(url: String): Throwable? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }.exceptionOrNull()

    @Test
    fun cleartextIsRefusedExceptThroughTheLanRelay() = runBlocking<Unit> {
        assumeTrue("pass -e hoardPlainHttp <pairing link>", link != null)
        val code = PairingCode.parse(link!!)!!
        // The platform refuses cleartext to a public host, and even to the LAN server when not through the relay.
        assertThat(plainGet("http://example.com/")?.message).contains("not permitted")
        assertThat(plainGet(code.url + "/v1/health")?.message).contains("not permitted")
        // Hoard refuses a public plain-HTTP address before sending anything.
        assertThat(runCatching { dev.hfps.hoard.server.api.ServerApi("http://93.184.215.14", { null }).health() }.exceptionOrNull())
            .isInstanceOf(InsecureAddressException::class.java)
        // The paired LAN server works through the relay: pairing, then a sync.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = GalleryDatabase.open(context, null)
        try {
            val engine = SyncEngine(db, MemoryKeyVault(), DeviceInfo("Emulator", "hoard-debug", "Google", "sdk", "2.1.0-test"))
            engine.pair(code)
            assertThat(engine.sync()).isTrue()
            assertThat(db.account().get()!!.url).isEqualTo(code.url)
            assertThat(engine.api()!!.proxyUrl("x")).startsWith("http://127.0.0.1:")
        } finally {
            db.close()
        }
    }
}
