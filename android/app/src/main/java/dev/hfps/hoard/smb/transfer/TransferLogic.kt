package dev.hfps.hoard.smb.transfer

import dev.hfps.hoard.smb.ConflictNames
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

/** Where to (re)start writing. */
object ResumeRules {
    /**
     * `resumeFromBytes` from JS: append only if the partial file has exactly that many bytes (and
     * it is not beyond the remote size); otherwise restart at 0.
     */
    fun requested(requested: Long, existingSize: Long?, totalBytes: Long?): Long =
        if (requested > 0 && existingSize == requested && (totalBytes == null || requested <= totalBytes)) requested else 0

    /**
     * A job native restarts itself (worker rescheduled after the process died): whatever is in the
     * file is a correct prefix (bytes are written in order), so continue after it.
     */
    fun restart(fileSize: Long?, totalBytes: Long): Long =
        if (fileSize != null && fileSize in 0..totalBytes) fileSize else 0
}

/** What `startDownload` does with the destination folder. Pure (unit-tested). */
object DestinationRules {

    sealed interface Decision<out T> {
        /**
         * Retry/resume of a partial file (`resumeFromBytes > 0` and `destName` exists): reuse that
         * document whatever the conflict policy. [fromBytes] > 0 appends, 0 truncates and restarts.
         */
        data class Resume<T>(val document: T, val fromBytes: Long) : Decision<T>

        /** `conflict: "skip"` and the name is taken: nothing is written. */
        data class Skip<T>(val document: T) : Decision<T>

        /** `conflict: "overwrite"` and the name is taken: truncate that document. */
        data class Overwrite<T>(val document: T) : Decision<T>

        /** Create a new document (the name is free, or the next free `name (n).ext`). */
        data class Create(val name: String) : Decision<Nothing>
    }

    fun <T> plan(
        wanted: String,
        conflict: String,
        resumeFromBytes: Long,
        children: List<T>,
        nameOf: (T) -> String,
        sizeOf: (T) -> Long,
    ): Decision<T> {
        val existing = children.firstOrNull { nameOf(it) == wanted }
        if (existing != null && resumeFromBytes > 0) {
            return Decision.Resume(existing, ResumeRules.requested(resumeFromBytes, sizeOf(existing), null))
        }
        if (existing != null) {
            when (conflict) {
                "skip" -> return Decision.Skip(existing)
                "overwrite" -> return Decision.Overwrite(existing)
            }
        }
        return Decision.Create(ConflictNames.firstFree(wanted, children.map(nameOf)))
    }

    /** A transferId may be (re)started unless native still runs a job with that id. */
    fun acceptsNew(existing: TransferJob?): Boolean = existing == null || existing.isTerminal
}
