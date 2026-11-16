package dev.hfps.hoard.server

import dev.hfps.hoard.image.source.RandomAccessSource
import dev.hfps.hoard.image.source.readFully
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The `v1:` content fingerprint (Reveal ADR 0006 "Identity", ADR 0013, ADR 0014): SHA-256 over the size as 8 bytes
 * big-endian, then the first MiB and the last MiB (a file of at most 2 MiB whole), lower-case hex after `v1:`. The
 * server, Reveal and Hoard check the same vectors (`server/testdata/fingerprint-vectors.txt`).
 */
object Fingerprint {
    const val PREFIX = "v1:"
    private const val CHUNK = 1L shl 20
    private val PATTERN = Regex("^v1:[0-9a-f]{64}$")

    fun isValid(value: String?): Boolean = value != null && PATTERN.matches(value)

    /** Reads at most 2 MiB of [source]. */
    fun of(source: RandomAccessSource): String = of(source.size) { position, buffer, length -> source.readFully(position, buffer, 0, length) }

    fun of(file: File): String = RandomAccessFile(file, "r").use { raf ->
        of(raf.length()) { position, buffer, length ->
            raf.seek(position)
            raf.readFully(buffer, 0, length)
        }
    }

    /**
     * The fingerprint and, for a file of at most 2 MiB (which the fingerprint reads whole), its SHA-256 from the same
     * read (ADR 0024 "Identity": no extra I/O). The SHA-256 is null for a larger file.
     */
    fun withSha(file: File): Pair<String, String?> = RandomAccessFile(file, "r").use { raf ->
        val size = raf.length()
        val content = if (size <= 2 * CHUNK) MessageDigest.getInstance("SHA-256") else null
        val fp = of(size) { position, buffer, length ->
            raf.seek(position)
            raf.readFully(buffer, 0, length)
            content?.update(buffer, 0, length)
        }
        fp to content?.digest()?.toHex()
    }

    fun of(bytes: ByteArray): String = of(bytes.size.toLong()) { position, buffer, length -> System.arraycopy(bytes, position.toInt(), buffer, 0, length) }

    /** [read] fills `buffer[0, length)` with the bytes at `position`. */
    fun of(size: Long, read: (position: Long, buffer: ByteArray, length: Int) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(8)
        for (i in 0 until 8) header[i] = (size ushr (56 - 8 * i)).toByte()
        digest.update(header)
        val buffer = ByteArray(CHUNK.toInt())
        if (size <= 2 * CHUNK) {
            var position = 0L
            while (position < size) {
                val n = minOf(CHUNK, size - position).toInt()
                read(position, buffer, n)
                digest.update(buffer, 0, n)
                position += n
            }
        } else {
            read(0, buffer, CHUNK.toInt())
            digest.update(buffer, 0, CHUNK.toInt())
            read(size - CHUNK, buffer, CHUNK.toInt())
            digest.update(buffer, 0, CHUNK.toInt())
        }
        return PREFIX + digest.digest().toHex()
    }
}

/** ADR 0014 "Item id": the UUID derived from a main file's fingerprint (the id a phone-only shot will have). */
object ItemIds {
    fun derive(mainFingerprint: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(("hoard-item\u0000" + mainFingerprint).toByteArray(Charsets.UTF_8))
        val b = h.copyOf(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x80).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = b.toHex()
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}

internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val v = byte.toInt() and 0xff
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"

/**
 * A pairing code from the server's admin page (ADR 0013 "Pairing"):
 * `revealsync://pair?v=1&url=<server URL, percent-encoded>&token=<token>&server=<serverId>`, parameters in any order.
 */
data class PairingCode(val url: String, val token: String, val serverId: String?) {
    companion object {
        /** The code from a scanned QR or pasted text, or null when it is not one (unknown version, no URL or token). */
        fun parse(text: String): PairingCode? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("revealsync://pair", ignoreCase = true)) return null
            val query = trimmed.substringAfter('?', "")
            val params = query.split('&').filter { it.isNotEmpty() }.associate { part ->
                val key = decode(part.substringBefore('='))
                key to decode(part.substringAfter('=', ""))
            }
            if (params["v"] != "1") return null
            val url = params["url"]?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
            val token = params["token"]?.takeIf { it.isNotBlank() } ?: return null
            return PairingCode(url, token, params["server"]?.takeIf { it.isNotBlank() })
        }

        private fun decode(value: String): String = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}

/**
 * Whether a server URL may be used over plain HTTP (ADR 0021): hoard-server speaks HTTP on the LAN and Tailscale
 * (ADR 0013: TLS is the reverse proxy's job), so `http://` is accepted for loopback, private (RFC 1918, IPv6 ULA),
 * link-local and CGNAT/Tailscale (`100.64.0.0/10`) addresses and for local names (no dot, `.local`, `.lan`, `.home`,
 * `.internal`, `.ts.net`); anything else must be `https://`.
 */
object ServerAddress {
    fun allowed(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.trim('[', ']') ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> isLocal(host)
            else -> false
        }
    }

    fun isLocal(host: String): Boolean {
        if (host == "localhost" || !host.contains('.') && !host.contains(':')) return true
        if (listOf(".local", ".lan", ".home", ".internal", ".ts.net", ".home.arpa").any { host.endsWith(it) }) return true
        // IP literals: loopback, RFC 1918, link-local, Tailscale (100.64.0.0/10), IPv6 ULA (fc00::/7) — parsed, never looked up.
        dev.hfps.hoard.server.net.LanAddresses.literal(host)?.let { return dev.hfps.hoard.server.net.LanAddresses.allowed(it) }
        return false
    }
}
