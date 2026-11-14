package dev.hfps.hoard.smb.handoff

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.stream.StreamAddress
import dev.hfps.hoard.smb.stream.StreamTokens
import dev.hfps.hoard.xmp.XmpReader

/**
 * The native route for a cold-started provider (ADR 0010 "Cold start"; pure, JVM-tested). The JS engine
 * (`src/features/route/engine.ts`) stays the app's; this is its small native twin for when the app is not running:
 * the address that last worked first, then the machine's priority order, each one connected with the pinned GUID.
 */
object NativeRoute {
    /**
     * Answers that end the search: the right server (GUID matched) refused, and every other address reaches that same
     * server. A `GUID_MISMATCH` is someone else's server on that address: skipped, like the JS engine does.
     */
    val FINAL = setOf(ErrorCode.AUTH_FAILED, ErrorCode.ACCESS_DENIED, ErrorCode.NOT_FOUND)

    /** [addresses] in priority order with [lastGood] (when still one of them) moved to the front. */
    fun order(addresses: List<StreamAddress>, lastGood: StreamAddress?): List<StreamAddress> {
        val distinct = addresses.distinct()
        if (lastGood == null || lastGood !in distinct) return distinct
        return listOf(lastGood) + distinct.filter { it != lastGood }
    }

    /**
     * [attempt] on each address in order until one works; returns it and its index. A [final] answer stops at once;
     * otherwise the last failure is thrown when none worked.
     */
    fun <T> first(addresses: List<StreamAddress>, final: Set<ErrorCode> = FINAL, attempt: (StreamAddress) -> T): Pair<T, Int> {
        var last: HoardSmbException? = null
        addresses.forEachIndexed { index, address ->
            try {
                return attempt(address) to index
            } catch (e: HoardSmbException) {
                if (e.code in final) throw e
                last = e
            }
        }
        throw last ?: HoardSmbException(ErrorCode.UNREACHABLE, "No address to try")
    }
}

/**
 * Hand-off URIs (ADR 0010): `content://<app>.stream/h/<id>/<tag>%2F<name>`. The id (256 random bits) is the only
 * thing looked up; the last segment decodes to `<folder tag>/<name>`, which Reveal reads as "folder + file name" (as
 * for a SAF document), so the RAW and the JPEG of one folder pair, and the name gives the file type. Pure.
 */
object HandoffUris {
    const val PREFIX = "h"

    /** The id of a hand-off URI's decoded path segments, or null when they are not one. */
    fun idOf(segments: List<String>): String? {
        if (segments.size != 3 || segments[0] != PREFIX) return null
        val id = segments[1]
        if (!StreamTokens.isWellFormed(id)) return null
        val last = segments[2]
        if (last.count { it == '/' } != 1 || last.endsWith('/') || last.startsWith('/')) return null
        return id
    }

    /** The decoded last segment for [tag] and [name]. */
    fun lastSegment(tag: String, name: String) = "$tag/$name"

    fun isHandoff(segments: List<String>) = segments.firstOrNull() == PREFIX
}

/** How a hand-off file may be opened (ADR 0010 "Write-back"). */
enum class HandoffAccess {
    READ,

    /** Start empty ("w", "wt", "rwt"): the whole sidecar is written again. */
    WRITE_TRUNCATE,

    /** Start from the current bytes ("rw", "wa"). */
    READ_WRITE,

    /** Writes go at the end ("wa"). */
    APPEND,
    ;

    val writes: Boolean get() = this != READ

    companion object {
        /**
         * The access for [mode] on a photo or a sidecar (pure, JVM-tested): photos are read-only, always; sidecars may
         * be written in any of Android's modes. Anything else is a [SecurityException] (what the framework expects
         * for a write the provider refuses).
         */
        fun of(mode: String, sidecar: Boolean): HandoffAccess {
            val access = when (mode) {
                "r" -> READ
                "w", "wt", "rwt" -> WRITE_TRUNCATE
                "rw" -> READ_WRITE
                "wa" -> APPEND
                else -> throw IllegalArgumentException("Unknown mode $mode")
            }
            if (access.writes && !sidecar) throw SecurityException("Photos handed to Reveal are read-only")
            return access
        }
    }
}

/**
 * The bytes of a sidecar opened for writing through the provider (pure, JVM-tested): kept in memory (sidecars are
 * small; [max] caps them), committed in one atomic write on the share when the descriptor is synced or released, and
 * only when they are well-formed XMP ([commitBytes]); a sidecar is never replaced by something that is not one.
 */
class SidecarBuffer(initial: ByteArray, private val append: Boolean, private val max: Int = MAX_BYTES) {
    private var data = initial.copyOf(maxOf(initial.size, 1024))
    var size: Int = initial.size
        private set
    var dirty: Boolean = false
        private set

    @Synchronized
    fun read(offset: Long, length: Int, out: ByteArray): Int {
        if (offset >= size) return 0
        val n = minOf(length.toLong(), size - offset).toInt()
        System.arraycopy(data, offset.toInt(), out, 0, n)
        return n
    }

    /** Writes [length] bytes of [bytes] at [offset] (at the end in append mode); false beyond [max]. */
    @Synchronized
    fun write(offset: Long, length: Int, bytes: ByteArray): Boolean {
        val at = if (append) size.toLong() else offset
        val end = at + length
        if (at < 0 || end > max) return false
        if (end > data.size) data = data.copyOf(maxOf(end.toInt(), data.size * 2).coerceAtMost(max))
        System.arraycopy(bytes, 0, data, at.toInt(), length)
        if (end > size) size = end.toInt()
        dirty = true
        return true
    }

    /** The bytes to write, or null when nothing changed or they are not well-formed XMP (logged by the caller). */
    @Synchronized
    fun commitBytes(): ByteArray? {
        if (!dirty) return null
        val bytes = data.copyOf(size)
        if (XmpReader.readOrNull(bytes) == null) return null
        dirty = false
        return bytes
    }

    /** Written (or refused): nothing pending any more. */
    @Synchronized
    fun discard() {
        dirty = false
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
