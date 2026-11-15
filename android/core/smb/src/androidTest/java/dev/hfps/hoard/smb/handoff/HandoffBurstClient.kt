package dev.hfps.hoard.smb.handoff

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileNotFoundException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The client of the hand-off burst stress test (`docs/testing/handoff-burst.md`): this androidTest APK is an app of
 * its own (its own uid, like Reveal), granted Hoard's links by `HandoffBurstSetup`. It opens [opens] of them at once
 * from [threads] threads, reads the head, a middle chunk and the tail of each (checking every byte against the test
 * pattern), optionally holds every descriptor open for `hold` ms, and reports latencies and outcomes. A measurement,
 * not a pass/fail test (the host script judges `system_server`). Opt-in: `-e hoardHandoffBurstClient true`.
 *
 * Arguments: `uris` (comma-separated), `opens` (200), `threads` (200), `hold` (0 ms), `retry` (false: retry `busy:`
 * failures with backoff, as Reveal does), `label`.
 */
@RunWith(AndroidJUnit4::class)
class HandoffBurstClient {
    private data class Result(val openMs: Long, val totalMs: Long, val outcome: String, val tries: Int)

    @Test
    fun burst() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in: -e hoardHandoffBurstClient true", args.getString("hoardHandoffBurstClient") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val uris = args.getString("uris").orEmpty().split(',').filter { it.isNotBlank() }.map(Uri::parse)
        require(uris.isNotEmpty()) { "no uris" }
        val opens = args.getString("opens")?.toInt() ?: 200
        val threads = args.getString("threads")?.toInt() ?: opens
        val hold = args.getString("hold")?.toLong() ?: 0L
        val retry = args.getString("retry") == "true"
        val label = args.getString("label") ?: "burst"
        uris.forEach { runCatching { resolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }

        val results = Collections.synchronizedList(ArrayList<Result>())
        val open = AtomicInteger()
        val peakOpen = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        val go = CountDownLatch(1)
        val opened = CountDownLatch(opens)
        val done = CountDownLatch(opens)
        val started = SystemClock.elapsedRealtime()
        repeat(opens) { i ->
            pool.execute {
                go.await()
                val uri = uris[i % uris.size]
                val t0 = SystemClock.elapsedRealtime()
                var tries = 0
                var openMs = 0L
                var outcome: String
                var pfd: ParcelFileDescriptor? = null
                while (true) {
                    tries++
                    try {
                        pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("null descriptor")
                        openMs = SystemClock.elapsedRealtime() - t0
                        outcome = verify(pfd, seedOf(uri))
                        break
                    } catch (e: FileNotFoundException) {
                        val busy = e.message?.startsWith("busy:") == true
                        if (busy && retry && tries < 8) {
                            Thread.sleep(minOf(4_000L, 250L shl (tries - 1)))
                            continue
                        }
                        openMs = SystemClock.elapsedRealtime() - t0
                        outcome = if (busy) "busy" else "notfound: ${e.message}"
                        break
                    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                        openMs = SystemClock.elapsedRealtime() - t0
                        outcome = "error: ${e.javaClass.simpleName}: ${e.message}"
                        break
                    }
                }
                if (pfd != null) {
                    peakOpen.accumulateAndGet(open.incrementAndGet()) { a, b -> maxOf(a, b) }
                }
                opened.countDown()
                if (pfd != null && hold > 0) opened.await(hold * 4, TimeUnit.MILLISECONDS).also { Thread.sleep(hold) }
                pfd?.let {
                    runCatching { it.close() }
                    open.decrementAndGet()
                }
                results += Result(openMs, SystemClock.elapsedRealtime() - t0, outcome, tries)
                done.countDown()
            }
        }
        go.countDown()
        val finished = done.await(10, TimeUnit.MINUTES)
        val wall = SystemClock.elapsedRealtime() - started
        pool.shutdownNow()

        val list = results.toList()
        val byOutcome = list.groupingBy { it.outcome.substringBefore(':').substringBefore(" ") }.eachCount()
        val okOpen = list.filter { it.outcome == "ok" }.map { it.openMs }.sorted()
        val all = list.map { it.totalMs }.sorted()
        fun pct(values: List<Long>, p: Double) = if (values.isEmpty()) -1 else values[minOf(values.size - 1, (values.size * p).toInt())]
        val summary = String.format(
            Locale.ROOT,
            "%s: %d opens of %d links in %d ms (finished=%s), outcomes=%s, open ms p50=%d p95=%d max=%d, total ms p50=%d p95=%d max=%d, peak open fds=%d, retries=%d",
            label, list.size, uris.size, wall, finished, byOutcome,
            pct(okOpen, 0.5), pct(okOpen, 0.95), okOpen.lastOrNull() ?: -1,
            pct(all, 0.5), pct(all, 0.95), all.lastOrNull() ?: -1,
            peakOpen.get(), list.sumOf { it.tries - 1 },
        )
        Log.i(TAG, summary)
        list.map { it.outcome }.filter { it != "ok" && it != "busy" }.distinct().take(5).forEach { Log.i(TAG, "$label: example failure: $it") }
        instrumentation.sendStatus(0, Bundle().apply { putString("summary", summary) })
    }

    /** Reads the head, a middle chunk and the tail; "ok" when every byte matches the pattern. */
    private fun verify(pfd: ParcelFileDescriptor, seed: Int): String {
        val fd = pfd.fileDescriptor
        val size = Os.fstat(fd).st_size
        val chunk = 64 * 1024L
        val offsets = listOf(0L, size / 2, maxOf(0L, size - chunk)).distinct()
        val buffer = ByteArray(chunk.toInt())
        for (offset in offsets) {
            val want = minOf(chunk, size - offset).toInt()
            var got = 0
            while (got < want) {
                val n = Os.pread(fd, buffer, got, want - got, offset + got)
                if (n <= 0) return "short read at ${offset + got} of $size"
                got += n
            }
            for (k in 0 until want) {
                if (buffer[k] != patternAt(seed, offset + k)) return "wrong byte at ${offset + k}"
            }
        }
        return "ok"
    }

    /** `…/burst-<seed>.<ext>` (SMB) or `local-<i>.bin` (seed 1000 + i). Same formula as the setup's `BurstPattern`. */
    private fun seedOf(uri: Uri): Int {
        val name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        return when {
            name.startsWith("local-") -> 1000 + name.removePrefix("local-").substringBefore('.').toInt()
            else -> name.removePrefix("burst-").substringBefore('.').toInt()
        }
    }

    private fun patternAt(seed: Int, offset: Long): Byte = ((offset and 4095) + seed * 17 + (offset ushr 12) * 13).toInt().toByte()

    private companion object {
        const val TAG = "HoardBurst"
    }
}
