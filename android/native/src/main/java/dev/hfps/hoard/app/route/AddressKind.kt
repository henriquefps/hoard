package dev.hfps.hoard.app.route

import java.util.Locale

/**
 * Address kind detection (ADR 0002), a port of `src/lib/addressKind.ts`. The kind is derived from the host, never
 * asked from the user:
 *  - [Tailscale]: 100.64.0.0/10 (the CGNAT range Tailscale uses), `fd7a:115c:a1e0::/48`, `*.ts.net`;
 *  - [Local]: RFC 1918, link-local, IPv6 ULA, loopback and `.local` / `.lan` / `.home.arpa` names;
 *  - [Other]: everything else (public IPs, public names, bare host names).
 */
enum class AddressKind(val id: String, val priority: Int) {
    Local("local", 0),
    Tailscale("tailscale", 1),
    Other("other", 2);

    companion object {
        fun fromId(id: String?): AddressKind = entries.firstOrNull { it.id == id } ?: Other

        fun detect(rawHost: String): AddressKind {
            val host = rawHost.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]").removeSuffix(".")
            val v4 = parseIpv4(host)
            if (v4 != null) {
                val (a, b) = v4
                return when {
                    a == 100 && b in 64..127 -> Tailscale
                    a == 10 || a == 127 -> Local
                    a == 172 && b in 16..31 -> Local
                    a == 192 && b == 168 -> Local
                    a == 169 && b == 254 -> Local
                    else -> Other
                }
            }
            if (':' in host) {
                // Tailscale's IPv6 range sits inside ULA: check it first.
                if (host.startsWith("fd7a:115c:a1e0:")) return Tailscale
                if (host == "::1" || Regex("^fe[89ab]").containsMatchIn(host) || Regex("^f[cd]").containsMatchIn(host)) {
                    return Local
                }
                return Other
            }
            if (host == "ts.net" || host.endsWith(".ts.net")) return Tailscale
            if (host == "localhost" || Regex("\\.(local|lan|home\\.arpa)$").containsMatchIn(host)) return Local
            return Other
        }

        private fun parseIpv4(host: String): List<Int>? {
            val parts = host.split(".")
            if (parts.size != 4) return null
            val octets = parts.map { p -> if (Regex("^\\d{1,3}$").matches(p)) p.toInt() else -1 }
            return if (octets.all { it in 0..255 }) octets else null
        }

        /** The default priority order: local, then Tailscale, then the rest; stable inside a kind. */
        fun <T> sortByKind(addresses: List<T>, host: (T) -> String): List<T> =
            addresses.withIndex().sortedWith(compareBy({ detect(host(it.value)).priority }, { it.index })).map { it.value }
    }
}

/**
 * What people paste into a host field (`smb://nas.local/share`, `\\192.168.1.20\Public`, `192.168.1.155:4450`,
 * `[fd7a:115c:a1e0::1]:445`): the bare host and a port when one was given, or null. Port of
 * `src/features/machines/address.ts` `parseHostInput`.
 */
data class HostInput(val host: String, val port: Int? = null)

fun parseHostInput(raw: String): HostInput? {
    var text = raw.trim()
    if (text.isEmpty()) return null
    text = text.replace(Regex("^smb://", RegexOption.IGNORE_CASE), "").removePrefix("\\\\")
    text = text.split(Regex("[\\\\/]")).firstOrNull().orEmpty()
    if (text.isEmpty()) return null
    Regex("^\\[([^\\]]+)](?::(\\d+))?$").matchEntire(text)?.let { m ->
        return withPort(m.groupValues[1], m.groupValues[2].ifEmpty { null })
    }
    val colons = text.count { it == ':' }
    if (colons == 1) {
        val (host, port) = text.split(":")
        return withPort(host, port)
    }
    if (text.any { it.isWhitespace() }) return null
    return HostInput(text)
}

private fun withPort(host: String, port: String?): HostInput? {
    if (host.isEmpty() || host.any { it.isWhitespace() }) return null
    if (port.isNullOrEmpty()) return HostInput(host)
    val value = port.toIntOrNull()
    return if (value != null && isValidPort(value)) HostInput(host, value) else HostInput(host)
}

fun isValidPort(port: Int) = port in 1..65535
