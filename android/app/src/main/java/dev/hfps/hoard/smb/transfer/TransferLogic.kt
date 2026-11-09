package dev.hfps.hoard.smb.transfer

import dev.hfps.hoard.smb.ErrorCode

/**
 * Which address to try next after a failure, and when. Pure state machine (unit-tested).
 *
 * - Fatal errors (bad password, no access, file gone, disk full, SAF grant revoked, cancelled) stop
 *   the job: another address would not help.
 * - GUID_MISMATCH means "this address is somebody else's server": skip it right away.
 * - Anything else (connection lost, timeout, unknown I/O): retry the same address with exponential
 *   backoff, then fail over to the next address in priority order. After the last address the list
 *   is walked again ([rounds] times in total), so a Wi-Fi that comes back is picked up again.
 * - An attempt that moved bytes before failing resets the attempt counter: the route works, it
 *   just dropped.
 */
class FailoverPlan(
    private val addressCount: Int,
    private val attemptsPerAddress: Int = 3,
    private val rounds: Int = 2,
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 15_000,
) {
    sealed interface Decision {
        data class Retry(val addressIndex: Int, val delayMs: Long) : Decision
        data object GiveUp : Decision
    }

    init {
        require(addressCount > 0) { "at least one address" }
    }

    var addressIndex = 0
        private set
    private var attempt = 0
    private var round = 0

    fun onFailure(code: ErrorCode, madeProgress: Boolean, fatal: Boolean = false): Decision {
        if (fatal || code in FATAL) return Decision.GiveUp
        if (madeProgress) attempt = 0
        if (code != ErrorCode.GUID_MISMATCH) {
            attempt++
            if (attempt < attemptsPerAddress) {
                return Decision.Retry(addressIndex, backoff(attempt, round))
            }
        }
        // Next address (or the next round over the list).
        attempt = 0
        addressIndex++
        if (addressIndex >= addressCount) {
            addressIndex = 0
            round++
            if (round >= rounds) return Decision.GiveUp
            return Decision.Retry(addressIndex, backoff(attemptsPerAddress, round))
        }
        return Decision.Retry(addressIndex, if (code == ErrorCode.GUID_MISMATCH) 0 else baseDelayMs / 2)
    }

    private fun backoff(attempt: Int, round: Int): Long {
        val exponent = (attempt - 1 + round * 2).coerceIn(0, 10)
        return (baseDelayMs shl exponent).coerceAtMost(maxDelayMs)
    }

    companion object {
        val FATAL = setOf(
            ErrorCode.AUTH_FAILED,
            ErrorCode.ACCESS_DENIED,
            ErrorCode.NOT_FOUND,
            ErrorCode.NO_SPACE,
            ErrorCode.PERMISSION_REVOKED,
            ErrorCode.CANCELLED,
            ErrorCode.ALREADY_EXISTS,
            ErrorCode.NO_APP,
        )
    }
}

/**
 * When to emit `transferProgress` while bytes flow: at most every [intervalMs], and only when the
 * job moved by 1% or 1 MiB (whichever is smaller) — or by anything at all after a full second, so a
 * slow link still updates its speed.
 */
class ProgressThrottle(
    private val intervalMs: Long = 250,
    private val maxStepBytes: Long = 1L shl 20,
    private val heartbeatMs: Long = 1_000,
) {
    private var lastAt = Long.MIN_VALUE / 2
    private var lastBytes = -1L

    fun shouldEmit(nowMs: Long, bytesDone: Long, totalBytes: Long): Boolean {
        val elapsed = nowMs - lastAt
        if (elapsed < intervalMs) return false
        val delta = bytesDone - lastBytes
        if (delta <= 0) return false
        val step = minOf(maxStepBytes, maxOf(1L, totalBytes / 100))
        return delta >= step || elapsed >= heartbeatMs
    }

    fun mark(nowMs: Long, bytesDone: Long) {
        lastAt = nowMs
        lastBytes = bytesDone
    }
}

/** Rolling average speed over the last [windowMs]. */
class SpeedMeter(private val windowMs: Long = 3_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun add(nowMs: Long, bytesDone: Long) {
        samples.addLast(nowMs to bytesDone)
        while (samples.size > 2 && nowMs - samples.first().first > windowMs) samples.removeFirst()
    }

    /** Bytes per second, or null until there is a measurable interval. */
    fun bytesPerSecond(): Long? {
        if (samples.size < 2) return null
        val (t0, b0) = samples.first()
        val (t1, b1) = samples.last()
        val elapsed = t1 - t0
        if (elapsed < 200) return null
        return ((b1 - b0).coerceAtLeast(0) * 1000) / elapsed
    }

    fun reset() = samples.clear()
}

/** Where to (re)start writing a file (ADR 0007, "Resume"). Pure (unit-tested). */
object ResumeRules {
    /**
     * Offset to continue a file whose partial copy has [partSize] bytes (null: no partial file).
     *  - The source changed since the bytes were written (size or modified time): restart at 0.
     *  - [contiguous] endpoints (local files, SAF) write in order, so whatever the partial file
     *    holds (up to the source size) is a correct prefix: continue after it.
     *  - SMB writes are pipelined, so the server file may hold bytes past a hole after a crash:
     *    only [persisted] (bytes acknowledged in order and saved) is trusted, and only while the
     *    server file still has at least that many bytes ("append at the server size if it
     *    matches"); the writer truncates to that offset before appending. Otherwise restart.
     */
    fun offset(persisted: Long, partSize: Long?, total: Long, sourceUnchanged: Boolean, contiguous: Boolean): Long {
        if (!sourceUnchanged || partSize == null || partSize <= 0 || total <= 0) return 0
        if (contiguous) return if (partSize > total) 0 else partSize
        return if (persisted in 1..minOf(partSize, total)) persisted else 0
    }
}

/** Names of the partial files a copy writes before publishing (ADR 0007). Pure. */
object PartNames {
    private const val SUFFIX = ".hoardpart"

    /** `.photo.jpg.hoardpart`, cut so it stays within the 255-character limit of FAT/exFAT/SMB. */
    fun of(name: String): String {
        val room = 255 - 1 - SUFFIX.length
        return "." + (if (name.length > room) name.take(room) else name) + SUFFIX
    }

    fun isPart(name: String) = name.startsWith(".") && name.endsWith(SUFFIX)
}

/** Path helpers for planning (contract paths: `/`-separated, no leading slash). Pure. */
object TransferPaths {
    fun join(folder: String, name: String) = if (folder.isEmpty()) name else "$folder/$name"

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun name(path: String): String = path.substringAfterLast('/')

    /** [child] is [parent] or inside it. */
    fun isSameOrInside(parent: String, child: String): Boolean =
        parent.isEmpty() || child == parent || child.startsWith("$parent/")

    /** Re-root [path] (inside [from]) under [to]. */
    fun rebase(path: String, from: String, to: String): String =
        if (path == from) to else join(to, path.removePrefix("$from/"))
}

/**
 * What a failure means for a job (ADR 0007, "Errors"). Pure.
 *  - [Scope.RETRY]: the route dropped (network endpoints only): reconnect, fail over, resume.
 *  - [Scope.JOB]: nothing else can succeed now (no space, drive gone, access revoked, bad
 *    password, cancelled): the job stops; the items left stay for Retry.
 *  - [Scope.ITEM]: this file is the problem (gone, refused, invalid name): it fails, the job goes on.
 */
object FailureScope {
    enum class Scope { RETRY, JOB, ITEM }

    private val JOB = setOf(
        ErrorCode.AUTH_FAILED, ErrorCode.NO_SPACE, ErrorCode.PERMISSION_REVOKED, ErrorCode.STORAGE_PERMISSION,
        ErrorCode.UNAVAILABLE, ErrorCode.CANCELLED,
    )
    private val RETRY = setOf(ErrorCode.UNREACHABLE, ErrorCode.TIMEOUT, ErrorCode.GUID_MISMATCH, ErrorCode.UNKNOWN)

    fun of(code: ErrorCode, network: Boolean): Scope = when {
        code in JOB -> Scope.JOB
        network && code in RETRY -> Scope.RETRY
        else -> Scope.ITEM
    }
}
