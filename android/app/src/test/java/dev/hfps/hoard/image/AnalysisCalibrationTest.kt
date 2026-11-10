package dev.hfps.hoard.image

import dev.hfps.hoard.image.analysis.Blur
import dev.hfps.hoard.image.analysis.Resample
import dev.hfps.hoard.image.analysis.Rgb
import dev.hfps.hoard.image.analysis.ShotAnalysis
import dev.hfps.hoard.image.format.ImageInspector
import dev.hfps.hoard.image.format.ListedFile
import dev.hfps.hoard.image.format.ShotPairing
import dev.hfps.hoard.image.sharpness.Luma
import dev.hfps.hoard.image.sharpness.Sharpness
import dev.hfps.hoard.image.source.FileSource
import dev.hfps.hoard.image.source.readRange
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Opt-in calibration of the assisted-culling measurements (ADR 0009) on a real shoot (never
 * committed; read-only): `HOARD_ARW_DIR=/path/to/copies ./gradlew testDebugUnitTest --tests
 * '*AnalysisCalibrationTest*'`. For every shot it prints the analysis of the RAW's embedded preview
 * and of the camera JPEG, and of synthetic degradations of the preview (Gaussian and motion blur,
 * ±2 EV), so thresholds can be set from where sharp and degraded frames separate. Faces need the
 * device (ML Kit): see the analysis harness.
 */
class AnalysisCalibrationTest {

    private val dir: File? = System.getenv("HOARD_ARW_DIR")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }

    @Test
    fun `calibrate on a real shoot`() {
        assumeTrue("HOARD_ARW_DIR not set", dir != null)
        val files = dir!!.listFiles()!!.filter { it.isFile }
        val shots = ShotPairing.pair(files.map { ListedFile(it.name, it.length()) })
        println("CAL shot | t(ms) | src | global | best | blurTile | CRmin | mean | hi | lo | hash | ms")
        var previous: Pair<Long?, Pair<String, String>>? = null
        for (shot in shots) {
            val raw = shot.raw ?: continue
            val started = System.nanoTime()
            val (preview, orientation, time) = FileSource(File(dir, raw.name)).use { source ->
                val inspection = ImageInspector.inspect(source, raw.name)
                val p = inspection.largestPreview!!
                Triple(DesktopRgb.read(ByteArrayInputStream(source.readRange(p.offset, p.length.toInt()))), inspection.metadata.orientation, inspection.metadata.captureSortMillis)
            }
            val upright = DesktopRgb.orient(preview, orientation)
            val image = toAnalysis(upright)
            val a = ShotAnalysis.analyze(image, null)
            val ms = (System.nanoTime() - started) / 1_000_000
            val luma = Luma.fromArgb(image.width, image.height, image.argb)
            val tileBlurs = (0 until 16).map { i -> val r = ShotAnalysis.tileRect(i, image.width, image.height); Blur.creteRoffet(luma, r[0], r[1], r[2], r[3]) }
            fun f(v: Double, d: Int = 1) = "%.${d}f".format(v)
            println("CAL ${shot.stem} | $time | preview ${image.width}x${image.height} | ${f(a.global)} | ${f(a.best)} | ${f(a.subjectBlur, 3)} | ${f(tileBlurs.min(), 3)} | ${f(a.exposure.mean, 3)} | ${f(a.exposure.highlights * 100, 2)}% | ${f(a.exposure.shadows * 100, 2)}% | ${a.hash} | $ms | ${a.colors}")
            previous?.let { (t0, sig) ->
                val gap = if (t0 != null && time != null) time - t0 else null
                println("PAIR ${shot.stem} gap=${gap}ms hamming=${ShotAnalysis.hammingDistance(sig.first, a.hash)} color=${f(ShotAnalysis.colorDistance(sig.second, a.colors), 4)}")
            }
            previous = time to (a.hash to a.colors)

            // The camera JPEG through the same pipeline: are the two sources comparable?
            shot.rendered?.let { jpeg ->
                val j = toAnalysis(DesktopRgb.orient(DesktopRgb.read(File(dir, jpeg.name).inputStream()), orientation))
                val b = ShotAnalysis.analyze(j, null)
                println("JPG ${shot.stem} | ${f(b.global)} | ${f(b.best)} | ${f(b.subjectBlur, 3)} | ${f(b.exposure.mean, 3)} | ${f(b.exposure.highlights * 100, 2)}% | hamming-vs-preview=${ShotAnalysis.hammingDistance(a.hash, b.hash)} color=${f(ShotAnalysis.colorDistance(a.colors, b.colors), 4)}")
            }

            // Synthetic degradations of the preview.
            val tile = ShotAnalysis.tileRect(a.bestTile, image.width, image.height)
            for (sigma in listOf(0.7, 1.0, 1.5, 2.0, 3.0)) {
                val blurred = gaussian(image, sigma)
                val l = Luma.fromArgb(blurred.width, blurred.height, blurred.argb)
                val d = ShotAnalysis.analyze(blurred, null)
                println("SYN ${shot.stem} gauss$sigma | best=${f(d.best)} tileLap=${f(Sharpness.region(l, tile[0], tile[1], tile[2], tile[3]))} blurBestTile=${f(d.subjectBlur, 3)} blurSameTile=${f(Blur.creteRoffet(l, tile[0], tile[1], tile[2], tile[3]), 3)}")
            }
            for (length in listOf(6, 12, 24)) {
                val moved = motion(image, length)
                val d = ShotAnalysis.analyze(moved, null)
                println("SYN ${shot.stem} motion$length | best=${f(d.best)} blurBestTile=${f(d.subjectBlur, 3)}")
            }
            for (ev in listOf(-2.0, -1.0, 1.0, 2.0)) {
                val e = ShotAnalysis.analyze(exposure(image, ev), null).exposure
                println("SYN ${shot.stem} ev$ev | mean=${f(e.mean, 3)} hi=${f(e.highlights * 100, 2)}% lo=${f(e.shadows * 100, 2)}%")
            }
        }
    }

    private fun toAnalysis(image: Rgb): Rgb {
        val (w, h) = ShotAnalysis.analysisSize(image.width, image.height)
        return Rgb(w, h, Resample.area(image.argb, image.width, image.height, w, h))
    }

    private fun gaussian(image: Rgb, sigma: Double): Rgb {
        val radius = (sigma * 3).roundToInt().coerceAtLeast(1)
        val kernel = DoubleArray(radius * 2 + 1) { exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma)) }
        val sum = kernel.sum()
        for (i in kernel.indices) kernel[i] /= sum
        return convolve(image, kernel, kernel)
    }

    private fun motion(image: Rgb, length: Int): Rgb = convolve(image, DoubleArray(length) { 1.0 / length }, doubleArrayOf(1.0))

    private fun convolve(image: Rgb, kx: DoubleArray, ky: DoubleArray): Rgb {
        val w = image.width
        val h = image.height
        fun pass(src: IntArray, k: DoubleArray, horizontal: Boolean): IntArray {
            val r = k.size / 2
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0.0; var g = 0.0; var b = 0.0
                for (i in k.indices) {
                    val sx = if (horizontal) (x + i - r).coerceIn(0, w - 1) else x
                    val sy = if (horizontal) y else (y + i - r).coerceIn(0, h - 1)
                    val c = src[sy * w + sx]
                    a += ((c shr 16) and 0xFF) * k[i]; g += ((c shr 8) and 0xFF) * k[i]; b += (c and 0xFF) * k[i]
                }
                out[y * w + x] = (0xFF shl 24) or (a.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or b.roundToInt().coerceIn(0, 255)
            }
            return out
        }
        return Rgb(w, h, pass(pass(image.argb, kx, true), ky, false))
    }

    /** ±EV in linear light (sRGB decoded, scaled, encoded, clipped). */
    private fun exposure(image: Rgb, ev: Double): Rgb {
        val factor = 2.0.pow(ev)
        val lut = IntArray(256) { v ->
            val c = v / 255.0
            val linear = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            val scaled = (linear * factor).coerceIn(0.0, 1.0)
            val s = if (scaled <= 0.0031308) scaled * 12.92 else 1.055 * scaled.pow(1 / 2.4) - 0.055
            (s * 255).roundToInt().coerceIn(0, 255)
        }
        return Rgb(image.width, image.height, IntArray(image.argb.size) { i ->
            val c = image.argb[i]
            (0xFF shl 24) or (lut[(c shr 16) and 0xFF] shl 16) or (lut[(c shr 8) and 0xFF] shl 8) or lut[c and 0xFF]
        })
    }
}

/** javax.imageio RGB decode through reflection (see [Desktop]), plus EXIF orientation 1/3/6/8. */
object DesktopRgb {
    fun read(input: InputStream): Rgb = input.use {
        val image = Class.forName("javax.imageio.ImageIO").getMethod("read", InputStream::class.java).invoke(null, it)
            ?: error("ImageIO could not decode")
        val cls = image.javaClass
        val w = cls.getMethod("getWidth").invoke(image) as Int
        val h = cls.getMethod("getHeight").invoke(image) as Int
        val argb = cls.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java)
            .invoke(image, 0, 0, w, h, null, 0, w) as IntArray
        Rgb(w, h, argb)
    }

    fun orient(image: Rgb, orientation: Int): Rgb {
        val w = image.width
        val h = image.height
        return when (orientation) {
            3 -> Rgb(w, h, IntArray(w * h) { image.argb[w * h - 1 - it] })
            // 6: rotate 90° clockwise; 8: 90° counter-clockwise.
            6 -> Rgb(h, w, IntArray(w * h) { i -> val x = i % h; val y = i / h; image.argb[(h - 1 - x) * w + y] })
            8 -> Rgb(h, w, IntArray(w * h) { i -> val x = i % h; val y = i / h; image.argb[x * w + (w - 1 - y)] })
            else -> image
        }
    }
}
