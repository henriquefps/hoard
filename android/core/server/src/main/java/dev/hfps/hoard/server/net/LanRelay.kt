package dev.hfps.hoard.server.net

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Plain HTTP to an address that is not on the owner's LAN or tailnet: refused before any byte is sent. */
class InsecureAddressException(host: String) : IOException("plain HTTP is allowed only to LAN, Tailscale and loopback addresses, not to $host")

/** Which addresses hoard-server may be reached at over plain HTTP (ADR 0021 "Plain HTTP"). */
object LanAddresses {
    /**
     * Loopback, RFC 1918 private (10/8, 172.16/12, 192.168/16), link-local (169.254/16, fe80::/10), Tailscale's CGNAT
     * range (100.64.0.0/10) and IPv6 unique local addresses (fc00::/7, which holds Tailscale's fd7a:115c:a1e0::/48).
     */
    fun allowed(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        val b = address.address
        return when (address) {
            is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
            is Inet6Address -> (b[0].toInt() and 0xfe) == 0xfc
            else -> false
        }
    }

    /** [host] as an address when it is an IP literal (parsed, never looked up), else null. */
    fun literal(host: String): InetAddress? {
        val h = host.trim('[', ']')
        val v4 = Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(h)
        if (!v4 && !h.contains(':')) return null
        return runCatching { InetAddress.getByName(h) }.getOrNull()
    }
}

/**
 * Plain HTTP to the paired hoard-server (ADR 0021 "Plain HTTP"). The app's network security config allows cleartext
 * only to the loopback address (as before 2.1.0); hoard-server speaks plain HTTP on the LAN and the tailnet (ADR 0013:
 * HTTPS is the reverse proxy's job). So a plain `http://` server is reached through a relay on `127.0.0.1` that
 * forwards bytes to that one server, and only after checking, at every connection, that every address its name resolves
 * to is a LAN, Tailscale or loopback address ([LanAddresses]). A public `http://` address is refused; `https://` goes
 * straight out through the platform's TLS.
 */
class LanRelay private constructor(private val host: String, private val port: Int) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val localPort: Int get() = socket.localPort

    init {
        Thread({
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread({ serve(client) }, "hoard-relay-conn").apply { isDaemon = true }.start()
            }
        }, "hoard-relay-$host").apply { isDaemon = true }.start()
    }

    /** The server's address, when every address of [host] is allowed; else [InsecureAddressException]. */
    internal fun resolve(): InetAddress {
        val all = LanAddresses.literal(host)?.let { arrayOf(it) } ?: InetAddress.getAllByName(host)
        if (all.isEmpty() || !all.all(LanAddresses::allowed)) throw InsecureAddressException(host)
        return all.first()
    }

    private fun serve(client: Socket) {
        client.use { c ->
            val upstream = try {
                // (Not inside `Socket().apply`: there `port` would be the unconnected socket's own.)
                val address = InetSocketAddress(resolve(), port)
                Socket().also { it.connect(address, CONNECT_TIMEOUT_MS) }
            } catch (e: IOException) {
                runCatching { android.util.Log.w("HoardServer", "relay to $host:$port refused: ${e.message}") }
                return
            }
            upstream.use { u ->
                c.tcpNoDelay = true
                u.tcpNoDelay = true
                val up = Thread({
                    runCatching { c.getInputStream().copyTo(u.getOutputStream(), 64 * 1024) }
                    runCatching { u.shutdownOutput() }
                }, "hoard-relay-up").apply { isDaemon = true }
                up.start()
                runCatching { u.getInputStream().copyTo(c.getOutputStream(), 64 * 1024) }
                runCatching { c.shutdownOutput() }
                up.join(1000)
            }
        }
    }

    override fun close() = socket.close()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private val relays = ConcurrentHashMap<String, LanRelay>()

        /** A relay to [host]:[port] (one per target, kept for the process). */
        fun to(host: String, port: Int): LanRelay = relays.getOrPut("$host:$port") { LanRelay(host, port) }

        /**
         * The base URL to send requests for [baseUrl] to: unchanged for `https://` and for the loopback address (the
         * network security config allows both), else the relay's `http://127.0.0.1:<port>` with the same path. A
         * public IP literal is refused here; a name is checked by the relay when it connects. Never looks a name up
         * (callable from any thread).
         */
        fun route(baseUrl: String): String {
            val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return baseUrl
            if (uri.scheme?.lowercase() != "http") return baseUrl
            val host = uri.host?.lowercase()?.trim('[', ']') ?: return baseUrl
            if (host == "127.0.0.1" || host == "localhost") return baseUrl
            LanAddresses.literal(host)?.let { if (!LanAddresses.allowed(it)) throw InsecureAddressException(host) }
            val relay = to(host, if (uri.port > 0) uri.port else 80)
            return "http://127.0.0.1:${relay.localPort}" + uri.rawPath.orEmpty().trimEnd('/')
        }
    }
}
