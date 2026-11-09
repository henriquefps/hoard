package dev.hfps.hoard.smb.stream

import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/** Unguessable stream tokens: 256 random bits as 64 lowercase hex characters (java.util.Base64 needs API 26). */
object StreamTokens {
    private val random = SecureRandom()
    private val PATTERN = Regex("^[0-9a-f]{64}$")

    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Shape check before any lookup (cheap rejection of garbage). */
    fun isWellFormed(token: String?): Boolean = token != null && PATTERN.matches(token)

    /** Constant-time comparison, so a local attacker cannot guess a token byte by byte. */
    fun matches(expected: String, candidate: String?): Boolean {
        if (candidate == null) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), candidate.toByteArray(Charsets.US_ASCII))
    }
}

/** A satisfiable byte range, both ends inclusive. */
data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
}

/** Outcome of parsing a `Range` header against a resource of a known size. */
sealed interface RangeRequest {
    /** No (usable) Range header: send the whole body with 200. */
    data object Full : RangeRequest

    /** Send this slice with 206 and `Content-Range`. */
    data class Partial(val range: ByteRange) : RangeRequest

    /** 416 with `Content-Range: bytes * /size`. */
    data object Unsatisfiable : RangeRequest
}

/**
 * `Range: bytes=…` parsing (RFC 9110 §14). One range only: a multi-range request is answered with
 * the whole body (allowed by the RFC, and players never send one). Malformed headers and other units
 * are ignored (200), as the RFC requires.
 */
object HttpRanges {
    fun parse(header: String?, size: Long): RangeRequest {
        if (header == null) return RangeRequest.Full
        val value = header.trim()
        if (!value.startsWith("bytes=", ignoreCase = true)) return RangeRequest.Full
        val spec = value.substring(6).trim()
        if (spec.isEmpty() || spec.contains(',')) return RangeRequest.Full
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeRequest.Full
        val first = spec.substring(0, dash).trim()
        val last = spec.substring(dash + 1).trim()
        if (first.isEmpty()) {
            // Suffix range: the last N bytes.
            val suffix = last.toLongStrict() ?: return RangeRequest.Full
            if (suffix <= 0 || size == 0L) return RangeRequest.Unsatisfiable
            return RangeRequest.Partial(ByteRange((size - suffix).coerceAtLeast(0), size - 1))
        }
        val start = first.toLongStrict() ?: return RangeRequest.Full
        val end = if (last.isEmpty()) Long.MAX_VALUE else (last.toLongStrict() ?: return RangeRequest.Full)
        if (end < start) return RangeRequest.Full
        if (start >= size) return RangeRequest.Unsatisfiable
        return RangeRequest.Partial(ByteRange(start, minOf(end, size - 1)))
    }

    private fun String.toLongStrict(): Long? = if (isNotEmpty() && all { it in '0'..'9' }) toLongOrNull() else null
}

/** Content types by extension for the local web server and the stream provider (pure, JVM-testable). */
object MimeTypes {
    private val TYPES = mapOf(
        // Web pages and their assets.
        "html" to "text/html; charset=utf-8",
        "htm" to "text/html; charset=utf-8",
        "xhtml" to "application/xhtml+xml",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "mjs" to "text/javascript; charset=utf-8",
        "json" to "application/json",
        "map" to "application/json",
        "webmanifest" to "application/manifest+json",
        "wasm" to "application/wasm",
        "xml" to "application/xml",
        "txt" to "text/plain; charset=utf-8",
        "md" to "text/markdown; charset=utf-8",
        "csv" to "text/csv; charset=utf-8",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "ico" to "image/x-icon",
        "bmp" to "image/bmp",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "pdf" to "application/pdf",
        // Video.
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "wmv" to "video/x-ms-wmv",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "3gp" to "video/3gpp",
        "flv" to "video/x-flv",
        // Audio.
        "mp3" to "audio/mpeg",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "aac" to "audio/aac",
        "m4a" to "audio/mp4",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "opus" to "audio/ogg",
        "wma" to "audio/x-ms-wma",
        "aiff" to "audio/aiff",
        // Subtitles (players look for them next to the video over HTTP).
        "srt" to "application/x-subrip",
        "vtt" to "text/vtt; charset=utf-8",
    )

    /** Content type for an HTTP response (text types carry `charset=utf-8`). */
    fun forHttp(name: String): String = TYPES[extension(name)] ?: "application/octet-stream"

    /** MIME type for an intent / `getType` (no parameters), or null when unknown here. */
    fun forIntent(name: String): String? = TYPES[extension(name)]?.substringBefore(';')

    private fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
    }
}

/** A request target split into the token and the share-relative path below the served root. */
data class WebTarget(val token: String, val path: String, val trailingSlash: Boolean)

/**
 * Sanitises request targets of the local server: `/<token>/<percent-encoded path>[?query]`.
 * Returns null (→ 404) for anything that could escape the served folder or confuse the SMB path:
 * `..`, `.`, backslashes, control characters, characters Windows forbids in names, encoded `/`.
 */
object WebPaths {
    private val FORBIDDEN = charArrayOf('\\', ':', '*', '?', '"', '<', '>', '|')

    fun parse(target: String): WebTarget? {
        if (!target.startsWith("/")) return null
        val rawPath = target.substringBefore('?').substringBefore('#')
        val parts = rawPath.substring(1).split('/')
        val token = parts.firstOrNull()?.takeIf(StreamTokens::isWellFormed) ?: return null
        val segments = ArrayList<String>()
        for (raw in parts.drop(1)) {
            if (raw.isEmpty()) continue
            // An encoded separator inside a segment would smuggle a path change past the split.
            if (raw.contains("%2f", ignoreCase = true) || raw.contains("%5c", ignoreCase = true)) return null
            val segment = decode(raw) ?: return null
            if (segment == "." || segment == "..") return null
            if (segment.any { it.code < 32 || it.code == 127 || it in FORBIDDEN }) return null
            segments += segment
        }
        val trailingSlash = rawPath.endsWith("/") && parts.size > 1
        return WebTarget(token, segments.joinToString("/"), trailingSlash)
    }

    private fun decode(segment: String): String? = try {
        // URLDecoder turns '+' into a space (form encoding); paths keep '+' literal.
        URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Percent-encode one path segment for a URL we hand out. */
    fun encodeSegment(segment: String): String {
        val out = StringBuilder()
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~!$&'()*+,;=@") out.append(ch)
            else out.append('%').append("%02X".format(c))
        }
        return out.toString()
    }

    fun encodePath(path: String): String = path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }
}
