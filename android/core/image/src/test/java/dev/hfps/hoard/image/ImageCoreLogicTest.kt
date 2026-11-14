package dev.hfps.hoard.image

import dev.hfps.hoard.image.cache.CacheKeys
import dev.hfps.hoard.image.cache.DiskCache
import dev.hfps.hoard.image.cache.FileIdentity
import dev.hfps.hoard.image.cache.PriorityScheduler
import dev.hfps.hoard.image.cache.SizedLruCache
import dev.hfps.hoard.image.cache.Variant
import dev.hfps.hoard.image.decode.IntRect
import dev.hfps.hoard.image.decode.Orientation
import dev.hfps.hoard.image.decode.Sampling
import dev.hfps.hoard.image.format.ExifTime
import dev.hfps.hoard.image.format.ImageFormats
import dev.hfps.hoard.image.format.ImageKind
import dev.hfps.hoard.image.format.ListedFile
import dev.hfps.hoard.image.format.NaturalOrder
import dev.hfps.hoard.image.format.RawFormat
import dev.hfps.hoard.image.format.ShotPairing
import dev.hfps.hoard.image.sharpness.Luma
import dev.hfps.hoard.image.sharpness.Sharpness
import dev.hfps.hoard.image.source.BlockCachedSource
import dev.hfps.hoard.image.source.ByteArraySource
import dev.hfps.hoard.image.source.CancelledException
import dev.hfps.hoard.image.source.CountingSource
import dev.hfps.hoard.image.source.SourceInputStream
import dev.hfps.hoard.image.source.readRange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ImageCoreLogicTest {

    // --- Classification and pairing ------------------------------------------------------------

    @Test
    fun `classify by extension and magic`() {
        assertEquals(RawFormat.ARW, ImageFormats.classify("DSC07668.ARW").raw)
        assertEquals(RawFormat.NEF, ImageFormats.classify("a.nrw").raw)
        listOf("cr2", "cr3", "raf", "dng", "orf", "rw2", "pef", "srw").forEach { assertTrue(it, ImageFormats.classify("x.$it").isRaw) }
        assertEquals(ImageKind.JPEG, ImageFormats.classify("x.JPEG").kind)
        assertEquals(ImageKind.HEIF, ImageFormats.classify("x.HIF").kind)
        assertEquals(ImageKind.OTHER, ImageFormats.classify("notes.txt").kind)
        assertEquals(ImageKind.OTHER, ImageFormats.classify(".jpg").kind)

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte())
        val tiffLe = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 8, 0, 0, 0)
        // Magic wins over a wrong extension; a TIFF named .arw stays ARW; a TIFF named .tif is not RAW.
        assertEquals(ImageKind.JPEG, ImageFormats.classify("mislabeled.png", jpeg).kind)
        assertEquals(RawFormat.ARW, ImageFormats.classify("a.ARW", tiffLe).raw)
        assertEquals(ImageKind.OTHER, ImageFormats.classify("scan.tif", tiffLe).kind)
        assertEquals(ImageKind.OTHER, ImageFormats.classify("a.ARW", "hello world".toByteArray()).kind)
        assertEquals(RawFormat.CR2, ImageFormats.classify("renamed.bin", tiffLe + "CR".toByteArray()).raw)
        assertEquals(RawFormat.RAF, ImageFormats.classify("x.raf", "FUJIFILMCCD-RAW 0201".toByteArray()).raw)
        assertEquals(RawFormat.CR3, ImageFormats.classify("x.cr3", byteArrayOf(0, 0, 0, 0x18) + "ftypcrx ".toByteArray()).raw)
        assertEquals(RawFormat.ORF, ImageFormats.classify("x.orf", byteArrayOf(0x49, 0x49, 0x52, 0x4F)).raw)
        assertEquals(RawFormat.RW2, ImageFormats.classify("x.rw2", byteArrayOf(0x49, 0x49, 0x55, 0x00)).raw)
        assertEquals(ImageKind.HEIF, ImageFormats.classify("x.heic", byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray()).kind)
    }

    @Test
    fun `pair RAW and JPEG by case-insensitive stem`() {
        val shots = ShotPairing.pair(listOf(
            ListedFile("DSC07668.ARW"), ListedFile("dsc07668.jpg"),
            ListedFile("DSC07673.ARW"),
            ListedFile("DSC07676.JPG"),
            ListedFile("DSC07700.HEIF"), ListedFile("DSC07700.JPG"), ListedFile("DSC07700.ARW"),
            ListedFile("notes.txt"), ListedFile("DSC07668.xmp"),
            ListedFile("IMG_10.JPG"), ListedFile("IMG_9.JPG"),
        ))
        assertEquals(listOf("DSC07668", "DSC07673", "DSC07676", "DSC07700", "IMG_9", "IMG_10"), shots.map { it.stem })
        val pair = shots[0]
        assertTrue(pair.isPair)
        assertEquals("DSC07668.ARW", pair.raw!!.name)
        assertEquals("dsc07668.jpg", pair.rendered!!.name)
        assertTrue(shots[1].isRawOnly)
        assertTrue(shots[2].isRenderedOnly)
        // JPEG preferred over HEIF as the rendering; the HEIF is kept with the shot.
        assertEquals("DSC07700.JPG", shots[3].rendered!!.name)
        assertEquals(listOf("DSC07700.HEIF"), shots[3].others.map { it.name })
        assertEquals(3, shots[3].files.size)
    }

    @Test
    fun `natural order`() {
        val sorted = listOf("DSC10", "dsc2", "DSC1", "DSC02a", "A").sortedWith(NaturalOrder)
        assertEquals(listOf("A", "DSC1", "dsc2", "DSC02a", "DSC10"), sorted)
    }

    // --- Time ----------------------------------------------------------------------------------

    @Test
    fun `EXIF time math matches java time`() {
        val random = Random(7)
        repeat(500) {
            val year = random.nextInt(1970, 2100)
            val month = random.nextInt(1, 13)
            val day = random.nextInt(1, 29)
            val expected = java.time.LocalDate.of(year, month, day).toEpochDay()
            assertEquals(expected, ExifTime.daysFromCivil(year, month, day))
        }
        assertEquals(java.time.Instant.parse("2026-08-06T14:24:48Z").toEpochMilli(), ExifTime.localMillis("2026:08:06 14:24:48"))
        assertNull(ExifTime.localMillis("0000:00:00 00:00:00"))
        assertEquals(733, ExifTime.subSecondMillis("733"))
        assertEquals(50, ExifTime.subSecondMillis("05"))
        assertEquals(-180, ExifTime.offsetMinutes("-03:00"))
        assertEquals(330, ExifTime.offsetMinutes("+05:30"))
    }

    // --- Sources -------------------------------------------------------------------------------

    @Test
    fun `block cache turns many small header reads into few`() {
        val data = ByteArray(1_000_000) { it.toByte() }
        val counting = CountingSource(ByteArraySource(data))
        val cached = BlockCachedSource(counting, blockSize = 64 * 1024)
        repeat(500) { i -> assertArrayEquals(data.copyOfRange(i * 100, i * 100 + 12), cached.readRange(i * 100L, 12)) }
        assertEquals(1, counting.readCalls)
        // A read straddling two blocks returns the right bytes.
        assertArrayEquals(data.copyOfRange(65530, 65550), cached.readRange(65530, 20))
        // Large reads bypass the cache.
        counting.reset()
        assertEquals(300_000, cached.readRange(100_000, 300_000).size)
        assertEquals(300_000L, counting.bytesRead)
        val stream = SourceInputStream(ByteArraySource(data), 10, 5)
        assertArrayEquals(data.copyOfRange(10, 15), stream.readBytes())
    }

    // --- Cache keys and caches -----------------------------------------------------------------

    @Test
    fun `cache keys change with size, mtime, source, path and variant`() {
        val id = FileIdentity("smb:guid/share", "/2026/DSC07668.ARW", 24_877_824, 1_754_500_000_000)
        val key = CacheKeys.key(id, Variant.PREVIEW)
        assertEquals(40, key.length)
        assertTrue(key.matches(Regex("[0-9a-f]{40}")))
        assertEquals(key, CacheKeys.key(id.copy(), Variant.PREVIEW))
        listOf(
            id.copy(size = 1), id.copy(modifiedMillis = 1), id.copy(sourceId = "local"), id.copy(path = "/2026/dsc07668.arw"),
        ).forEach { assertNotEquals(key, CacheKeys.key(it, Variant.PREVIEW)) }
        assertNotEquals(key, CacheKeys.key(id, Variant.RAW_FULL))
        assertNotEquals(CacheKeys.key(id, Variant.PREVIEW, 1), CacheKeys.key(id, Variant.PREVIEW, 2))
        assertTrue(CacheKeys.fileName(id, Variant.RAW_FULL).endsWith(".jpg"))
    }

    @Test
    fun `sized LRU evicts least recently used by bytes`() {
        val evicted = mutableListOf<String>()
        val cache = SizedLruCache<String, ByteArray>(100, { it.size.toLong() }) { k, _ -> evicted += k }
        cache.put("a", ByteArray(40))
        cache.put("b", ByteArray(40))
        cache["a"] // a is now most recent
        cache.put("c", ByteArray(40))
        assertEquals(listOf("b"), evicted)
        assertEquals(80, cache.sizeBytes)
        cache.put("huge", ByteArray(500)) // larger than the cache: not kept, nothing else flushed
        assertNull(cache["huge"])
        assertEquals(setOf("a", "c"), cache.keys().toSet())
        cache.trimTo(40)
        assertEquals(1, cache.count)
        cache.clear()
        assertEquals(0L, cache.sizeBytes)
    }

    @Test
    fun `disk cache writes atomically and trims oldest first`() {
        val dir = Files.createTempDirectory("hoard-disk").toFile()
        try {
            val cache = DiskCache(dir, maxBytes = 2500, trimEvery = 1)
            listOf("a", "b", "c").forEachIndexed { i, name ->
                cache.put(name) { it.writeBytes(ByteArray(1000)) }
                cache.file(name).setLastModified(1_000_000L + i * 1000)
            }
            cache.put("d") { it.writeBytes(ByteArray(1000)) }
            assertNull(cache.get("a"))
            assertNull(cache.get("b"))
            assertTrue(cache.get("d") != null)
            // A failing writer leaves nothing behind.
            runCatching { cache.put("e") { error("boom") } }
            assertNull(cache.get("e"))
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
        } finally {
            dir.deleteRecursively()
        }
    }

    // --- Scheduler -----------------------------------------------------------------------------

    @Test
    fun `scheduler runs by priority, dedupes, and cancels`() {
        val scheduler = PriorityScheduler(threads = 1)
        try {
            val gate = CountDownLatch(1)
            val order = Collections.synchronizedList(mutableListOf<String>())
            val blocker = scheduler.submit("blocker", 0) { gate.await(5, TimeUnit.SECONDS); order += "blocker" }
            Thread.sleep(50) // let the worker take the blocker
            val low = scheduler.submit("low", 5) { order += "low" }
            val high = scheduler.submit("high", 1) { order += "high"; 42 }
            val dup = scheduler.submit("high", 3) { order += "dup"; 0 }
            assertTrue(dup === high)
            val cancelled = scheduler.submit("gone", 2) { order += "gone" }
            val raised = scheduler.submit("raised", 9) { order += "raised" }
            scheduler.submit("raised", 0) { order += "never" } // joins and raises priority
            scheduler.cancel("gone")
            gate.countDown()
            blocker.future.get(5, TimeUnit.SECONDS)
            assertEquals(42, high.future.get(5, TimeUnit.SECONDS))
            low.future.get(5, TimeUnit.SECONDS)
            raised.future.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("blocker", "raised", "high", "low"), order.toList())
            try {
                cancelled.future.get(1, TimeUnit.SECONDS)
                fail("expected cancellation")
            } catch (e: ExecutionException) {
                assertTrue(e.cause is CancelledException)
            }
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun `retain cancels running work through its token`() {
        val scheduler = PriorityScheduler(threads = 1)
        try {
            val started = CountDownLatch(1)
            val job = scheduler.submit("long", 1) { token ->
                started.countDown()
                while (true) { token.throwIfCancelled(); Thread.sleep(5) }
            }
            started.await(5, TimeUnit.SECONDS)
            scheduler.retain(setOf("other"))
            try {
                job.future.get(5, TimeUnit.SECONDS)
                fail("expected cancellation")
            } catch (e: ExecutionException) {
                assertTrue(e.cause is CancelledException)
            }
            assertFalse("long" in scheduler.pendingKeys())
        } finally {
            scheduler.shutdown()
        }
    }

    // --- Geometry ------------------------------------------------------------------------------

    /** Displays a stored w x h grid of ids per EXIF orientation by the definition (rotate CW, then mirror). */
    private fun display(stored: Array<IntArray>, orientation: Int): Array<IntArray> {
        var img = stored
        repeat(Orientation.rotation(orientation) / 90) {
            val h = img.size
            val w = img[0].size
            img = Array(w) { y -> IntArray(h) { x -> img[h - 1 - x][y] } }
        }
        if (Orientation.flipped(orientation)) img = Array(img.size) { y -> img[y].reversedArray() }
        return img
    }

    @Test
    fun `orientation maps displayed rectangles back to stored pixels`() {
        val sw = 6
        val sh = 4
        val stored = Array(sh) { y -> IntArray(sw) { x -> y * sw + x } }
        for (o in 1..8) {
            val shown = display(stored, o)
            val (dw, dh) = Orientation.displayedSize(sw, sh, o)
            assertEquals(dh, shown.size)
            assertEquals(dw, shown[0].size)
            // Every 1x1 displayed pixel maps to the stored pixel holding the same id.
            for (y in 0 until dh) for (x in 0 until dw) {
                val r = Orientation.toStored(IntRect(x, y, x + 1, y + 1), sw, sh, o)
                assertEquals("o=$o ($x,$y)", 1, r.width * r.height)
                assertEquals("o=$o ($x,$y)", shown[y][x], stored[r.top][r.left])
            }
            // A larger rectangle covers exactly the stored ids it shows.
            val rect = IntRect(1, 1, minOf(dw, 4), minOf(dh, 3))
            val ids = (rect.top until rect.bottom).flatMap { y -> (rect.left until rect.right).map { x -> shown[y][x] } }.toSet()
            val r = Orientation.toStored(rect, sw, sh, o)
            val storedIds = (r.top until r.bottom).flatMap { y -> (r.left until r.right).map { x -> stored[y][x] } }.toSet()
            assertEquals("o=$o", ids, storedIds)
        }
    }

    @Test
    fun `sample sizes stay near the target`() {
        assertEquals(1, Sampling.sampleSize(1616, 1080, 3120))
        assertEquals(2, Sampling.sampleSize(6000, 4000, 3120)) // 3000 >= 0.9 * 3120
        assertEquals(1, Sampling.sampleSize(6000, 4000, 3500))
        assertEquals(2, Sampling.sampleSize(6000, 4000, 2400))
        assertEquals(4, Sampling.sampleSize(6000, 4000, 1500))
        assertEquals(1, Sampling.sampleForScale(1f))
        assertEquals(1, Sampling.sampleForScale(0.6f))
        assertEquals(2, Sampling.sampleForScale(0.5f))
        assertEquals(4, Sampling.sampleForScale(0.2f))
    }

    // --- Plans ---------------------------------------------------------------------------------

    @Test
    fun `screen and zoom plans`() {
        // ZV-E10 pair on a 3120 px screen: preview first, then the camera JPEG.
        assertEquals(listOf(ScreenSource.RAW_PREVIEW, ScreenSource.RENDERED), ImagePlans.screen(true, 1616, 3120, libRaw = true))
        // On a 2000 px tablet window the 1616 preview is enough.
        assertEquals(listOf(ScreenSource.RAW_PREVIEW), ImagePlans.screen(true, 1616, 2000, libRaw = true))
        assertEquals(listOf(ScreenSource.RAW_PREVIEW, ScreenSource.RAW_HALF), ImagePlans.screen(false, 1616, 3120, libRaw = true))
        assertEquals(listOf(ScreenSource.RAW_PREVIEW), ImagePlans.screen(false, 1616, 3120, libRaw = false))
        assertEquals(listOf(ScreenSource.RENDERED), ImagePlans.screen(true, null, 3120, libRaw = false))
        assertEquals(emptyList<ScreenSource>(), ImagePlans.screen(false, null, 3120, libRaw = false))

        assertEquals(ZoomSource.RENDERED, ImagePlans.zoom(true, 1616, 6000, true))
        assertEquals(ZoomSource.RAW_FULL, ImagePlans.zoom(false, 1616, 6000, true))
        assertEquals(ZoomSource.RAW_PREVIEW, ImagePlans.zoom(false, 6000, 6000, true)) // CR3 / NEF full-size JPEG
        assertEquals(ZoomSource.RAW_PREVIEW, ImagePlans.zoom(false, 1616, 6000, false))
        assertNull(ImagePlans.zoom(false, null, null, false))
    }

    @Test
    fun `prefetch window is ahead-first and bounded`() {
        assertEquals(listOf(6 to 1, 4 to 2, 7 to 3, 8 to 4), ImagePlans.prefetchWindow(5, 20, ahead = 3, behind = 1))
        assertEquals(listOf(18 to 1, 17 to 2), ImagePlans.prefetchWindow(19, 20, ahead = 3, behind = 2))
        assertEquals(listOf(1 to 1, 2 to 2), ImagePlans.prefetchWindow(0, 3, ahead = 3, behind = 1))
    }

    // --- Sharpness -----------------------------------------------------------------------------

    private fun pattern(w: Int, h: Int, blurRadius: Int, sharpRegion: IntRect? = null): Luma {
        val random = Random(1)
        val base = ByteArray(w * h) { (random.nextInt(256)).toByte() }
        if (blurRadius == 0) return Luma(w, h, base)
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val inSharp = sharpRegion != null && x >= sharpRegion.left && x < sharpRegion.right && y >= sharpRegion.top && y < sharpRegion.bottom
            if (inSharp) { out[y * w + x] = base[y * w + x]; continue }
            var acc = 0
            var n = 0
            for (dy in -blurRadius..blurRadius) for (dx in -blurRadius..blurRadius) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val yy = (y + dy).coerceIn(0, h - 1)
                acc += base[yy * w + xx].toInt() and 0xFF
                n++
            }
            out[y * w + x] = (acc / n).toByte()
        }
        return Luma(w, h, out)
    }

    @Test
    fun `variance of Laplacian ranks sharp above blurred and finds the sharp tile`() {
        val sharp = Sharpness.score(pattern(200, 200, 0))
        val soft = Sharpness.score(pattern(200, 200, 1))
        val blurry = Sharpness.score(pattern(200, 200, 3))
        assertTrue("${sharp.global} > ${soft.global}", sharp.global > soft.global * 3)
        assertTrue("${soft.global} > ${blurry.global}", soft.global > blurry.global * 3)
        assertEquals(0.0, Sharpness.score(Luma(50, 50, ByteArray(2500) { 100 })).global, 1e-9)

        // A sharp subject in the bottom-right tile of a 4x4 grid on a blurred background.
        val subject = Sharpness.score(pattern(200, 200, 3, IntRect(150, 150, 200, 200)))
        assertEquals(15, subject.bestIndex)
        assertTrue(subject.best > subject.global * 3)
    }

    @Test
    fun `downscale averages blocks`() {
        val luma = Luma(4, 2, byteArrayOf(0, 2, 10, 10, 4, 6, 10, 10))
        val half = Sharpness.downscale(luma, longSide = 2)
        assertEquals(2 to 1, half.width to half.height)
        assertEquals(3, half[0, 0])
        assertEquals(10, half[1, 0])
        val big = Sharpness.downscale(Luma(6000, 4000, ByteArray(24_000_000)), 1024)
        assertEquals(1000 to 666, big.width to big.height)
    }
}
