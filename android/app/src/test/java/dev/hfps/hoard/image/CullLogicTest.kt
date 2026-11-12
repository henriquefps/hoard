package dev.hfps.hoard.image

import dev.hfps.hoard.image.PrefetchStep.Step
import dev.hfps.hoard.image.format.PhotoMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CullLogicTest {

    private fun file(name: String, size: Long = 100) = CullFileRef(name, "DCIM/$name", size, null, 0)

    @Test
    fun `hidden files never become shots (AppleDouble, DS_Store, hidden sidecars)`() {
        val shots = CullModel.shots(
            listOf(
                file("DSC1.ARW"), file("DSC1.JPG"), file("DSC2.ARW"), file("DSC2.JPG"),
                file("._DSC1.ARW"), file("._DSC1.JPG"), file("._DSC2.ARW"), file("._DSC2.JPG"),
                file(".DS_Store"), file("._.DS_Store"), file(".DSC1.xmp"), file(".hidden.jpg"),
            ),
        )
        assertEquals(listOf("dsc1", "dsc2"), shots.map { it.id })
        assertEquals(listOf("pair", "pair"), shots.map { it.kind })
        assertEquals(emptyList<String>(), shots.flatMap { it.others })
        assertEquals(true, dev.hfps.hoard.image.format.ImageFormats.isHidden("DCIM/._X.ARW"))
        assertEquals(false, dev.hfps.hoard.image.format.ImageFormats.isHidden("DCIM/X.ARW"))
    }

    @Test
    fun `pairs a folder into shots with ids, kinds and extras`() {
        val shots = CullModel.shots(
            listOf(
                file("DSC07668.ARW"), file("dsc07668.jpg"), file("DSC07669.ARW"), file("DSC07670.JPG"),
                file("IMG_1.HIF"), file("IMG_1.JPG"), file("notes.txt"), file("DSC10.JPG"), file("DSC9.JPG"),
            ),
        )
        assertEquals(listOf("dsc9", "dsc10", "dsc07668", "dsc07669", "dsc07670", "img_1"), shots.map { it.id })
        val pair = shots.first { it.id == "dsc07668" }
        assertEquals("pair", pair.kind)
        assertEquals("DCIM/DSC07668.ARW", pair.raw?.path)
        assertEquals("dsc07668.jpg", pair.rendered?.name)
        assertEquals("raw", shots.first { it.id == "dsc07669" }.kind)
        assertEquals("jpeg", shots.first { it.id == "dsc07670" }.kind)
        val heif = shots.first { it.id == "img_1" }
        assertEquals("IMG_1.JPG", heif.rendered?.name)
        assertEquals(listOf("IMG_1.HIF"), heif.others.map { it.name })
    }

    @Test
    fun `capture time keeps sub-seconds and refuses empty dates`() {
        assertEquals("2026-08-07T15:04:05.733", CullModel.captureTime(PhotoMetadata(captureTime = "2026:08:07 15:04:05", subSecond = "733")))
        assertEquals("2026-08-07T15:04:05.700", CullModel.captureTime(PhotoMetadata(captureTime = "2026:08:07 15:04:05", subSecond = "7")))
        assertEquals("2026-08-07T15:04:05", CullModel.captureTime(PhotoMetadata(captureTime = "2026:08:07 15:04:05")))
        assertNull(CullModel.captureTime(PhotoMetadata(captureTime = "0000:00:00 00:00:00")))
        assertNull(CullModel.captureTime(PhotoMetadata()))
    }

    @Test
    fun `upright size swaps for rotated frames`() {
        assertEquals(6000 to 4000, CullModel.uprightSize(PhotoMetadata(width = 6000, height = 4000)))
        assertEquals(4000 to 6000, CullModel.uprightSize(PhotoMetadata(width = 6000, height = 4000, orientation = 8)))
        assertNull(CullModel.uprightSize(PhotoMetadata(width = 6000)))
    }

    @Test
    fun `prefetch prepares fast images first, then finals, then 100 percent ahead`() {
        val steps = CullPlans.prefetch(current = 5, count = 20, ahead = 2, behind = 1, zoomAhead = 1)
        assertEquals(
            listOf(6 to Step.FAST, 4 to Step.FAST, 7 to Step.FAST, 6 to Step.FINAL, 4 to Step.FINAL, 7 to Step.FINAL, 6 to Step.ZOOM),
            steps.map { it.index to it.step },
        )
        assertEquals((1..7).toList(), steps.map { it.priority })
        val far = CullPlans.prefetch(current = 5, count = 20, ahead = 1, behind = 0, zoomAhead = 0, fastAhead = 3)
        assertEquals(listOf(6 to Step.FAST, 7 to Step.FAST, 8 to Step.FAST, 6 to Step.FINAL), far.map { it.index to it.step })
        assertEquals(listOf(1), CullPlans.prefetch(0, 2, 3, 1, 0).filter { it.step == Step.FAST }.map { it.index })
        assertTrue(CullPlans.prefetch(0, 0, 3, 1, 1).isEmpty())
        assertTrue(CullPlans.prefetch(7, 3, 3, 1, 1).isEmpty())
    }

    @Test
    fun `an upright preview of about the screen size is served as it is`() {
        assertTrue(CullPlans.servePreviewAsIs(1, 1616, 3120))
        assertTrue(CullPlans.servePreviewAsIs(1, 1616, 1080))
        assertFalse(CullPlans.servePreviewAsIs(8, 1616, 3120))
        assertFalse(CullPlans.servePreviewAsIs(1, 6000, 3120))
    }

    @Test
    fun `tiles are clipped to the image and validated`() {
        assertArrayEquals(intArrayOf(5632, 3584, 368, 416), CullPlans.clipTile(5632, 3584, 512, 512, 1, 6000, 4000))
        assertArrayEquals(intArrayOf(0, 0, 100, 50), CullPlans.clipTile(-20, -10, 120, 60, 2, 6000, 4000))
        assertNull(CullPlans.clipTile(0, 0, 512, 512, 3, 6000, 4000))
        assertNull(CullPlans.clipTile(7000, 0, 512, 512, 1, 6000, 4000))
        assertNull(CullPlans.clipTile(0, 0, 4096, 512, 1, 6000, 4000))
        assertArrayEquals(intArrayOf(0, 0, 4096, 4000), CullPlans.clipTile(0, 0, 4096, 4096, 2, 6000, 4000))
    }
}

class SchedulerUrgentLaneTest {
    @Test
    fun `the image on screen runs while every normal worker is busy`() {
        val scheduler = dev.hfps.hoard.image.cache.PriorityScheduler(1, "test", urgentThreads = 1)
        val release = java.util.concurrent.CountDownLatch(1)
        val busy = scheduler.submit("prefetch", 5) { release.await(5, java.util.concurrent.TimeUnit.SECONDS); "slow" }
        Thread.sleep(50)
        val now = scheduler.submit("screen", 0) { "fast" }
        assertEquals("fast", now.future.get(1, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(busy.future.isDone)
        release.countDown()
        assertEquals("slow", busy.future.get(2, java.util.concurrent.TimeUnit.SECONDS))
        // A queued prefetch raised to priority 0 moves to the urgent lane.
        val block = java.util.concurrent.CountDownLatch(1)
        scheduler.submit("busy", 5) { block.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        Thread.sleep(50)
        val queued = scheduler.submit("next", 3) { "next" }
        scheduler.submit("next", 0) { "next" }
        assertEquals("next", queued.future.get(1, java.util.concurrent.TimeUnit.SECONDS))
        block.countDown()
        scheduler.shutdown()
    }
}
