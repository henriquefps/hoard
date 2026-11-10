package dev.hfps.hoard.image

import dev.hfps.hoard.image.analysis.Blur
import dev.hfps.hoard.image.analysis.DetectedFace
import dev.hfps.hoard.image.analysis.Resample
import dev.hfps.hoard.image.analysis.Rgb
import dev.hfps.hoard.image.analysis.ShotAnalysis
import dev.hfps.hoard.image.sharpness.Luma
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The assisted-culling measurements (ADR 0009) on synthetic images. */
class ShotAnalysisTest {

    private fun gray(w: Int, h: Int, f: (Int, Int) -> Int) = Rgb(w, h, IntArray(w * h) { i ->
        val v = f(i % w, i / w).coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    })

    private fun checker(w: Int, h: Int, cell: Int) = gray(w, h) { x, y -> if ((x / cell + y / cell) % 2 == 0) 230 else 25 }

    private fun boxBlur(image: Rgb, radius: Int): Rgb {
        val w = image.width
        val h = image.height
        val luma = Luma.fromArgb(w, h, image.argb)
        return gray(w, h) { x, y ->
            var sum = 0; var n = 0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val sx = (x + dx).coerceIn(0, w - 1); val sy = (y + dy).coerceIn(0, h - 1)
                sum += luma[sx, sy]; n++
            }
            sum / n
        }
    }

    @Test
    fun `analysis size keeps the aspect and never upscales`() {
        assertEquals(1536 to 1027, ShotAnalysis.analysisSize(1616, 1080))
        assertEquals(1024 to 1536, ShotAnalysis.analysisSize(4000, 6000))
        assertEquals(800 to 600, ShotAnalysis.analysisSize(800, 600))
    }

    @Test
    fun `area resampling averages exactly, also by fractions`() {
        // 4 -> 2: pairs averaged.
        val src = intArrayOf(0, 100, 200, 250).map { (0xFF shl 24) or (it shl 16) or (it shl 8) or it }.toIntArray()
        val out = Resample.area(src, 4, 1, 2, 1)
        assertEquals(50, (out[0] shr 16) and 0xFF)
        assertEquals(225, (out[1] shr 16) and 0xFF)
        // 3 -> 2: each target covers 1.5 source pixels.
        val three = intArrayOf(0, 90, 180).map { (0xFF shl 24) or (it shl 16) or (it shl 8) or it }.toIntArray()
        val two = Resample.area(three, 3, 1, 2, 1)
        assertEquals(30, (two[0] shr 16) and 0xFF) // (0 + 0.5 * 90) / 1.5
        assertEquals(150, (two[1] shr 16) and 0xFF) // (0.5 * 90 + 180) / 1.5
        // Row-by-row and whole-array versions agree.
        val r = Random(7)
        val big = IntArray(97 * 61) { (0xFF shl 24) or r.nextInt(0xFFFFFF) }
        val a = Resample.area(big, 97, 61, 40, 25)
        val b = Resample.area(97, 61, 40, 25) { y, row -> System.arraycopy(big, y * 97, row, 0, 97) }
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `blur rises as an image is blurred, laplacian falls`() {
        val sharp = checker(240, 160, 3)
        val soft = boxBlur(sharp, 2)
        val softer = boxBlur(sharp, 4)
        val a = ShotAnalysis.analyze(sharp, null)
        val b = ShotAnalysis.analyze(soft, null)
        val c = ShotAnalysis.analyze(softer, null)
        assertTrue(a.subjectBlur < b.subjectBlur && b.subjectBlur < c.subjectBlur)
        assertTrue(a.best > b.best && b.best > c.best)
        // A flat region is "fully blurred" by definition.
        val flat = Luma.fromArgb(50, 50, gray(50, 50) { _, _ -> 128 }.argb)
        assertEquals(1.0, Blur.creteRoffet(flat, 0, 0, 50, 50), 0.0)
        assertFalse(a.facesDetected)
        assertEquals("tile", a.subjectKind)
    }

    @Test
    fun `the subject is the largest prominent face, tiny faces do not count`() {
        // Sharp detail on the left half, smooth gradient on the right.
        val image = gray(400, 300) { x, y -> if (x < 200) (if ((x / 2 + y / 2) % 2 == 0) 220 else 30) else 60 + x / 4 }
        val faces = listOf(
            DetectedFace(250f, 100f, 350f, 200f, leftEyeOpen = 0.05f, rightEyeOpen = 0.1f),
            DetectedFace(20f, 20f, 30f, 30f, leftEyeOpen = 0.9f, rightEyeOpen = 0.9f),
        )
        val a = ShotAnalysis.analyze(image, faces)
        assertTrue(a.facesDetected)
        assertEquals("face", a.subjectKind)
        assertEquals(2, a.faces.size)
        assertTrue(a.faces[0].prominent)
        assertFalse("10 px of 400 is a background face", a.faces[1].prominent)
        assertEquals(0.625, a.faces[0].x, 1e-9)
        assertEquals(a.faces[0].blur, a.subjectBlur, 0.0)
        assertTrue("the face sits on the smooth half", a.subject < a.best)
    }

    @Test
    fun `exposure histogram, mean and clipping`() {
        val image = gray(100, 10) { x, _ -> if (x < 20) 255 else if (x < 50) 0 else 128 }
        val e = ShotAnalysis.analyze(image, null).exposure
        assertEquals(1.0, e.histogram.sum(), 1e-9)
        assertEquals(0.2, e.highlights, 1e-9)
        assertEquals(0.3, e.shadows, 1e-9)
        assertEquals(0.5, e.histogram[32], 1e-9)
        assertEquals((0.2 * 255 + 0.5 * 128) / 255, e.mean, 1e-9)
    }

    @Test
    fun `hashes match for near-identical frames and differ for another scene`() {
        val scene = gray(320, 240) { x, y -> (x * 255 / 320 + y / 3) % 256 }
        val shifted = gray(320, 240) { x, y -> ((x + 2) * 255 / 320 + y / 3 + 3) % 256 }
        val other = gray(320, 240) { x, y -> 255 - (y * 255 / 240) }
        val a = ShotAnalysis.analyze(scene, null)
        val b = ShotAnalysis.analyze(shifted, null)
        val c = ShotAnalysis.analyze(other, null)
        assertEquals(16, a.hash.length)
        assertEquals(96, a.colors.length)
        assertTrue(ShotAnalysis.hammingDistance(a.hash, b.hash) <= 6)
        assertTrue(ShotAnalysis.hammingDistance(a.hash, c.hash) >= 20)
        assertTrue(ShotAnalysis.colorDistance(a.colors, b.colors) < ShotAnalysis.colorDistance(a.colors, c.colors))
        assertEquals(0.0, ShotAnalysis.colorDistance(a.colors, a.colors), 0.0)
    }

    @Test
    fun `tile rectangles cover the image like the sharpness grid`() {
        val r0 = ShotAnalysis.tileRect(0, 1536, 1027)
        val r15 = ShotAnalysis.tileRect(15, 1536, 1027)
        assertEquals(listOf(0, 0, 384, 257), r0.toList())
        assertEquals(listOf(1152, 771, 1536, 1027), r15.toList())
    }
}
