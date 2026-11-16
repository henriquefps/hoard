package dev.hfps.hoard.server.backup

import dev.hfps.hoard.server.toHex
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * The full SHA-256 of a phone file (ADR 0024 "Identity"), made only inside the backup's worker, one file at a time,
 * throttled to [THROTTLE_BYTES_PER_SECOND] unless the phone is charging. The platform `MessageDigest` (BoringSSL with
 * the ARMv8 SHA-2 instructions on phones) is far faster than the throttle: storage reads and the throttle set the pace.
 */
object Hasher {
    /** 32 MiB/s while not charging. */
    const val THROTTLE_BYTES_PER_SECOND = 32L shl 20
    private const val BUFFER = 1 shl 20

    /**
     * The file's SHA-256, or null when [stopped] asked to stop. [bytesPerSecond] null = unthrottled. [clock] is
     * monotonic milliseconds.
     */
    suspend fun sha256(
        file: File,
        bytesPerSecond: Long?,
        stopped: () -> Boolean = { false },
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
    ): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        val started = clock()
        var read = 0L
        FileInputStream(file).use { input ->
            while (true) {
                if (stopped()) return null
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                read += n
                if (bytesPerSecond != null && bytesPerSecond > 0) {
                    val due = read * 1000 / bytesPerSecond
                    val ahead = due - (clock() - started)
                    if (ahead > 0) delay(ahead) else yield()
                } else {
                    yield()
                }
            }
        }
        return digest.digest().toHex()
    }
}
