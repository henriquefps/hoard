package dev.hfps.hoard.server.preview

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.api.ServerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Many callers of one preview or original at once: one download, one complete file, no lock left behind. */
@RunWith(AndroidJUnit4::class)
class CacheConcurrencyTest {
    private val server = FakeServer()
    private val api = ServerApi(server.url, { server.key })
    private val dir = File(System.getProperty("java.io.tmpdir"), "cache-concurrency-" + System.nanoTime())

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    @Test
    fun aKeyedLockAdmitsOneCallerAtATimeAndForgetsTheKeyWhenAllLeft() = runBlocking<Unit> {
        val locks = KeyedLocks()
        val inside = AtomicInteger()
        val peak = AtomicInteger()
        coroutineScope {
            // Callers keep arriving while others wait and leave: the case where a removed entry let a newcomer in.
            repeat(300) { i ->
                launch(Dispatchers.Default) {
                    if (i % 7 == 0) delay(1)
                    locks.withLock("k") {
                        val n = inside.incrementAndGet()
                        peak.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                        yield()
                        inside.decrementAndGet()
                    }
                }
            }
        }
        assertThat(peak.get()).isEqualTo(1)
        assertThat(locks.size()).isEqualTo(0)
    }

    @Test
    fun concurrentPreviewRequestsDownloadOnce() = runBlocking<Unit> {
        val cache = PreviewCache(dir, maxBytes = 10_000_000) { api }
        val files = coroutineScope {
            (1..16).map { async(Dispatchers.IO) { cache.get("item-1", PreviewSize.Screen, version = "o") } }.awaitAll()
        }
        assertThat(files.toSet()).hasSize(1)
        assertThat(files.first()!!.readText()).contains("item-1")
        // One pending answer, then one download, whoever asked.
        assertThat(server.previewAsks.values.sum()).isEqualTo(2)
        assertThat(cache.locks.size()).isEqualTo(0)
        assertThat(dir.walkTopDown().filter { it.name.endsWith(".part") }.toList()).isEmpty()
    }

    @Test
    fun concurrentKeepsOfAnOriginalDownloadItOnceAndWhole() = runBlocking<Unit> {
        val bytes = ByteArray(1_000_000) { (it % 253).toByte() }
        server.originals["v1:x"] = bytes
        val cache = OriginalCache(dir, maxBytes = 100_000_000) { api }
        val files = coroutineScope {
            (1..16).map { async(Dispatchers.IO) { cache.keep("v1:x", bytes.size.toLong()) } }.awaitAll()
        }
        assertThat(files.toSet()).hasSize(1)
        assertThat(files.first()!!.readBytes().contentEquals(bytes)).isTrue()
        assertThat(server.originalReads.get()).isEqualTo(1)
        assertThat(cache.locks.size()).isEqualTo(0)
        assertThat(dir.walkTopDown().filter { it.name.endsWith(".part") }.toList()).isEmpty()
    }

    @Test
    fun twoDownloadsOfOnePreviewNeverShareATemporaryFile() {
        // Even without the lock (two processes): each download writes its own temporary file, renamed when complete.
        val target = File(dir, "it/item-2-thumb.webp")
        val threads = (1..8).map { Thread { api.downloadPreview(api.previewPath("item-2", "thumb", "webp"), target) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertThat(target.readText()).contains("item-2")
        assertThat(target.parentFile!!.listFiles()!!.map { it.name }).containsExactly("item-2-thumb.webp")
    }
}
