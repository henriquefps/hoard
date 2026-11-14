package dev.hfps.hoard.smb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.SocketFactory

/** DNS + TCP helpers with real deadlines (InetAddress has no timeout of its own). */
object Net {

    const val DEFAULT_PORT = 445

    fun requireValidAddress(host: String?, port: Int?): Pair<String, Int> {
        val cleanHost = host?.trim().orEmpty()
        if (cleanHost.isEmpty()) smbFail(ErrorCode.UNKNOWN, "host is required")
        if (cleanHost.any { it.isWhitespace() || it == '/' }) smbFail(ErrorCode.UNKNOWN, "Invalid host: $cleanHost")
        val cleanPort = port ?: DEFAULT_PORT
        if (cleanPort !in 1..65535) smbFail(ErrorCode.UNKNOWN, "Invalid port: $cleanPort")
        // `[fe80::1]` from a URL-ish input: InetAddress wants it bare.
        return cleanHost.removePrefix("[").removeSuffix("]") to cleanPort
    }

    /**
     * Resolve [host] within [timeoutMs]. IP literals return immediately. Throws
     * [UnknownHostException] (→ UNREACHABLE) or [SocketTimeoutException] (→ TIMEOUT).
     */
    suspend fun resolve(host: String, timeoutMs: Long): List<InetAddress> = coroutineScope {
        val lookup = async(Dispatchers.IO) { InetAddress.getAllByName(host).toList() }
        try {
            withTimeout(timeoutMs.coerceAtLeast(1)) { lookup.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // The blocking lookup cannot be interrupted; let it finish in the background.
            lookup.cancel()
            throw SocketTimeoutException("DNS lookup for $host timed out")
        }
    }

    /**
     * TCP connect to the first address that answers, all within [timeoutMs] in total. Returns the
     * connected socket; the caller owns it.
     */
    fun connect(addresses: List<InetAddress>, port: Int, timeoutMs: Long): Socket {
        if (addresses.isEmpty()) throw UnknownHostException("No address")
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var last: IOException? = null
        for (address in addresses) {
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).toInt()
            if (remaining <= 0) break
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(address, port), remaining)
                return socket
            } catch (e: IOException) {
                runCatching { socket.close() }
                last = e
            }
        }
        throw last ?: SocketTimeoutException("connect to port $port timed out")
    }

    /**
     * Socket factory for smbj that connects to addresses resolved up front (so DNS honours the
     * deadline) with a connect timeout. smbj still sees the original host name.
     */
    class ResolvedSocketFactory(
        private val addresses: List<InetAddress>,
        private val connectTimeoutMs: Long,
    ) : SocketFactory() {
        override fun createSocket(): Socket = Socket()
        override fun createSocket(host: String?, port: Int): Socket = connect(addresses, port, connectTimeoutMs)
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            connect(addresses, port, connectTimeoutMs)
        override fun createSocket(host: InetAddress, port: Int): Socket = connect(listOf(host), port, connectTimeoutMs)
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            connect(listOf(address), port, connectTimeoutMs)
    }
}

/** `probe`: TCP handshake only, never throws for an unreachable host. */
object Prober {

    data class Result(val reachable: Boolean, val latencyMs: Long? = null, val reason: ErrorCode? = null)

    suspend fun probe(host: String, port: Int, timeoutMs: Long): Result {
        val started = System.nanoTime()
        val addresses = try {
            Net.resolve(host, timeoutMs)
        } catch (e: SocketTimeoutException) {
            return Result(false, reason = ErrorCode.TIMEOUT)
        } catch (e: Exception) {
            return Result(false, reason = ErrorCode.UNREACHABLE)
        }
        val remaining = timeoutMs - (System.nanoTime() - started) / 1_000_000
        if (remaining <= 0) return Result(false, reason = ErrorCode.TIMEOUT)
        return kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            val connectStarted = System.nanoTime()
            try {
                Net.connect(addresses, port, remaining).close()
                Result(true, latencyMs = ((System.nanoTime() - connectStarted) / 1_000_000).coerceAtLeast(1))
            } catch (e: SocketTimeoutException) {
                Result(false, reason = ErrorCode.TIMEOUT)
            } catch (e: IOException) {
                Result(false, reason = ErrorCode.UNREACHABLE)
            }
        }
    }
}
