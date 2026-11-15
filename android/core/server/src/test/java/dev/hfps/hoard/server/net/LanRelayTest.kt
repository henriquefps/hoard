package dev.hfps.hoard.server.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.ServerAddress
import dev.hfps.hoard.server.api.ServerApi
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress

/** ADR 0021 "Plain HTTP": only LAN, Tailscale and loopback addresses, through the loopback relay. */
@RunWith(AndroidJUnit4::class)
class LanRelayTest {
    private val server = FakeServer()

    @After
    fun tearDown() = server.close()

    @Test
    fun lanTailscaleAndLoopbackAddressesAreAllowedPublicOnesAreNot() {
        for (ok in listOf("127.0.0.1", "10.0.2.2", "172.20.0.1", "192.168.1.10", "169.254.3.4", "100.64.0.1", "100.127.255.254", "::1", "fe80::1", "fd7a:115c:a1e0::1", "fc00::5")) {
            assertWithMessage(ok).that(LanAddresses.allowed(InetAddress.getByName(ok))).isTrue()
        }
        for (no in listOf("8.8.8.8", "1.1.1.1", "100.128.0.1", "100.63.255.255", "172.32.0.1", "192.169.0.1", "2001:4860:4860::8888")) {
            assertWithMessage(no).that(LanAddresses.allowed(InetAddress.getByName(no))).isFalse()
        }
        assertThat(LanAddresses.literal("nas.local")).isNull()
        assertThat(ServerAddress.allowed("http://8.8.8.8:3070")).isFalse()
        assertThat(ServerAddress.allowed("http://[fd7a:115c:a1e0::1]:3070")).isTrue()
        assertThat(ServerAddress.allowed("http://100.100.10.1:3070")).isTrue()
        assertThat(ServerAddress.allowed("http://box.tail1234.ts.net:3070")).isTrue()
        assertThat(ServerAddress.allowed("http://photos.example.com")).isFalse()
        assertThat(ServerAddress.allowed("https://photos.example.com")).isTrue()
    }

    @Test
    fun routesHttpsAndLoopbackDirectlyLanThroughTheRelayAndRefusesPublicHttp() {
        assertThat(LanRelay.route("https://box.tail1234.ts.net")).isEqualTo("https://box.tail1234.ts.net")
        assertThat(LanRelay.route("http://127.0.0.1:3070")).isEqualTo("http://127.0.0.1:3070")
        assertThat(runCatching { LanRelay.route("http://8.8.8.8:3070") }.exceptionOrNull()).isInstanceOf(InsecureAddressException::class.java)
        val routed = LanRelay.route("http://192.168.1.10:3070/base/")
        assertThat(routed).matches("""http://127\.0\.0\.1:\d+/base""")
        // One relay per target.
        assertThat(LanRelay.route("http://192.168.1.10:3070")).startsWith(routed.removeSuffix("/base"))
    }

    @Test
    fun theRelayForwardsRequestsToTheServer() {
        val relay = LanRelay.to("127.0.0.1", java.net.URI(server.url).port)
        val api = ServerApi("http://127.0.0.1:${relay.localPort}", { server.key })
        assertThat(api.server().serverId).isEqualTo("fake-server")
        // Several requests, one after another and at once.
        val threads = (1..6).map { Thread { assertThat(api.server().serverId).isEqualTo("fake-server") } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }

    @Test
    fun theRelayNeverConnectsToAPublicAddress() {
        val relay = LanRelay.to("8.8.8.8", 9)
        assertThat(runCatching { relay.resolve() }.exceptionOrNull()).isInstanceOf(InsecureAddressException::class.java)
        val api = ServerApi("http://127.0.0.1:${relay.localPort}", { server.key }, connectTimeoutMs = 2_000, readTimeoutMs = 2_000)
        assertThat(runCatching { api.server() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }
}
