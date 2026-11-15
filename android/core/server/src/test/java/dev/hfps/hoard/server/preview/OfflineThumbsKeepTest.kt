package dev.hfps.hoard.server.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.api.ServerApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hoard 2.2.2: a server thumb downloaded for a grid cell or the filmstrip is also kept in the offline thumbnails
 * (the store the grid and the viewer read with no network), exactly as the background job would have written it.
 */
@RunWith(AndroidJUnit4::class)
class OfflineThumbsKeepTest {
    private val server = FakeServer()
    private val api = ServerApi(server.url, { server.key })
    private val root = File(System.getProperty("java.io.tmpdir"), "offline-keep-" + System.nanoTime())
    private val offline = OfflineThumbs(File(root, "thumbs"))
    private val cache = PreviewCache(File(root, "previews"), 10_000_000) { api }
    private var enabled = true

    init {
        server.previewBody = { image(512, 384) }
        // What GalleryRepository wires: the setting decides.
        cache.onOriginalThumb = { id, file -> if (enabled) offline.keep(id, file) }
    }

    @After
    fun tearDown() {
        server.close()
        root.deleteRecursively()
    }

    private fun image(w: Int, h: Int): ByteArray = ByteArrayOutputStream().also { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** The background keeps are done (the thumb itself never waits for them). */
    private fun settle(c: PreviewCache = cache) {
        val end = System.currentTimeMillis() + 20_000
        while (c.pendingKeeps() > 0 && System.currentTimeMillis() < end) Thread.sleep(5)
        assertThat(c.pendingKeeps()).isEqualTo(0)
    }

    private fun kept() = root.resolve("thumbs").walkTopDown().filter { it.isFile }.toList()

    @Test
    fun aThumbFetchedForACellIsKeptOfflineAndServedWithoutTheNetwork() = runBlocking<Unit> {
        val file = cache.get("item-1", PreviewSize.Thumb, version = "o")
        assertThat(file).isNotNull()
        settle()
        val stored = offline.file("item-1")
        assertThat(stored.isFile).isTrue()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(stored.absolutePath, it) }
        assertThat(minOf(bounds.outWidth, bounds.outHeight)).isEqualTo(OfflineThumbs.EDGE)
        assertThat(offline.count()).isEqualTo(1)
        assertThat(kept().map { it.name }).containsExactly("item-1.webp")
        // The server goes away: both stores still answer, and no request was made for the offline copy.
        val asks = server.previewAsks.values.sum()
        server.close()
        assertThat(cache.get("item-1", PreviewSize.Thumb, version = "o")).isEqualTo(file)
        assertThat(offline.has("item-1")).isTrue()
        assertThat(server.previewAsks.values.sum()).isEqualTo(asks)
    }

    @Test
    fun aThumbAlreadyKeptIsNotWrittenAgain() = runBlocking<Unit> {
        cache.get("item-1", PreviewSize.Thumb, version = "o")
        settle()
        val stored = offline.file("item-1")
        stored.setLastModified(1_000)
        val size = stored.length()
        cache.clear()
        cache.get("item-1", PreviewSize.Thumb, version = "o")
        settle()
        assertThat(stored.lastModified()).isEqualTo(1_000)
        assertThat(stored.length()).isEqualTo(size)
    }

    @Test
    fun aCachedThumbTheOfflineStoreLacksIsKeptWithoutANewDownload() = runBlocking<Unit> {
        enabled = false
        cache.get("item-1", PreviewSize.Thumb, version = "o")
        settle()
        assertThat(kept()).isEmpty()
        enabled = true
        val asks = server.previewAsks.values.sum()
        cache.get("item-1", PreviewSize.Thumb, version = "o")
        settle()
        assertThat(offline.has("item-1")).isTrue()
        assertThat(server.previewAsks.values.sum()).isEqualTo(asks)
    }

    @Test
    fun withOfflineThumbnailsOffNothingIsWritten() = runBlocking<Unit> {
        enabled = false
        assertThat(cache.get("item-1", PreviewSize.Thumb, version = "o")).isNotNull()
        settle()
        assertThat(kept()).isEmpty()
        assertThat(offline.count()).isEqualTo(0)
    }

    @Test
    fun onlyAnOriginalsThumbIsKept() = runBlocking<Unit> {
        cache.get("item-1", PreviewSize.Screen, version = "o")
        cache.get("item-2", PreviewSize.Thumb, edit = "primary", version = "v-2")
        settle()
        assertThat(kept()).isEmpty()
    }

    @Test
    fun aFailedOrPartialDownloadWritesNothing() = runBlocking<Unit> {
        // Not an image (a truncated body): the preview cache has it, the offline store refuses it and leaves nothing.
        server.previewBody = { image(512, 384).copyOf(40) }
        cache.get("item-1", PreviewSize.Thumb, version = "o")
        settle()
        assertThat(kept()).isEmpty()
        // The server has none / is unreachable: no file at all.
        val dead = PreviewCache(File(root, "previews2"), 10_000_000) { null }.also { c -> c.onOriginalThumb = { id, f -> offline.keep(id, f) } }
        assertThat(dead.get("item-3", PreviewSize.Thumb, version = "o")).isNull()
        val throwing = PreviewCache(File(root, "previews3"), 10_000_000) { throw java.io.IOException("airplane mode") }.also { c -> c.onOriginalThumb = { id, f -> offline.keep(id, f) } }
        runCatching { throwing.get("item-4", PreviewSize.Thumb, version = "o") }
        settle(dead)
        settle(throwing)
        assertThat(kept()).isEmpty()
    }

    @Test
    fun aFailingKeepNeverFailsThePreview() = runBlocking<Unit> {
        cache.onOriginalThumb = { _, _ -> error("disk full") }
        assertThat(cache.get("item-1", PreviewSize.Thumb, version = "o")).isNotNull()
        settle()
    }

    private fun seed(id: String) = cache.file(id, PreviewSize.Thumb, null, "o").apply {
        parentFile?.mkdirs()
        writeBytes(image(64, 48))
    }

    @Test
    fun theThumbIsReturnedBeforeTheStoreIsDone() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        cache.onOriginalThumb = { id, f -> gate.await(); offline.keep(id, f) }
        // The hook is stuck, yet the load returns the file at once.
        val file = withTimeout(10_000) { cache.get("item-1", PreviewSize.Thumb, version = "o") }
        assertThat(file).isNotNull()
        assertThat(cache.pendingKeeps()).isEqualTo(1)
        assertThat(offline.has("item-1")).isFalse()
        gate.complete(Unit)
        settle()
        assertThat(offline.has("item-1")).isTrue()
    }

    @Test
    fun aDuplicateIdIsWrittenOnce() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        cache.onOriginalThumb = { id, f -> runs.incrementAndGet(); gate.await(); offline.keep(id, f) }
        repeat(20) { cache.get("item-1", PreviewSize.Thumb, version = "o") }
        assertThat(cache.pendingKeeps()).isEqualTo(1)
        gate.complete(Unit)
        settle()
        assertThat(runs.get()).isEqualTo(1)
        assertThat(kept()).hasSize(1)
    }

    @Test
    fun aBurstKeepsAtMostTheBoundPendingAndTheRestIsWrittenOnASecondPass() = runBlocking<Unit> {
        val ids = (1..1000).map { "item-%04d".format(it) }
        ids.forEach { seed(it) }
        val gate = CompletableDeferred<Unit>()
        cache.onOriginalThumb = { id, f -> gate.await(); offline.keep(id, f) }
        var peak = 0
        ids.forEach {
            cache.get(it, PreviewSize.Thumb, version = "o")
            peak = maxOf(peak, cache.pendingKeeps())
        }
        assertThat(peak).isEqualTo(cache.maxPendingKeeps)
        gate.complete(Unit)
        settle()
        val first = offline.count()
        assertThat(first).isAtMost(cache.maxPendingKeeps)
        assertThat(first).isAtLeast(1)
        // Seen again, the dropped ones are kept (bounded passes; the free-running hook never blocks now).
        var passes = 0
        while (offline.count() < ids.size && passes++ < 30) {
            ids.forEach { cache.get(it, PreviewSize.Thumb, version = "o") }
            settle()
        }
        assertThat(offline.count()).isEqualTo(ids.size)
    }
}
