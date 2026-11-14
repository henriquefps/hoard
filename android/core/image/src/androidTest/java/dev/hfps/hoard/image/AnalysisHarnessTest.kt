package dev.hfps.hoard.image

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.hoard.image.analysis.MlKitFaces
import dev.hfps.hoard.image.analysis.Rgb
import dev.hfps.hoard.image.analysis.ShotAnalysis
import dev.hfps.hoard.image.cache.FileIdentity
import dev.hfps.hoard.image.format.ListedFile
import dev.hfps.hoard.image.format.ShotPairing
import dev.hfps.hoard.image.source.FileSource
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Debug harness for assisted culling (ADR 0009): analyses every shot of a folder on the device
 * (embedded preview or JPEG, ML Kit faces) and logs one line per shot (tag HoardAnalysisHarness):
 * timings, sharpness, faces with eye probabilities, exposure, hash. Aggregates go into the ADR;
 * the photos never leave the emulator. Copies only, emulator only:
 * ```
 * adb shell am instrument -w -e class dev.hfps.hoard.image.AnalysisHarnessTest \
 *   [-e dir /path] [-e source jpeg] dev.hfps.hoard.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * `source jpeg` analyses the camera JPEG even when the RAW has a preview (to compare sources).
 * Skipped when the folder is missing or empty.
 */
@RunWith(AndroidJUnit4::class)
class AnalysisHarnessTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun analyseFolder() {
        val dir = args.getString("dir")?.let(::File) ?: File(context.filesDir, "harness")
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        assumeTrue("No files in $dir", files.isNotEmpty())
        val jpegOnly = args.getString("source") == "jpeg"
        val shots = ShotPairing.pair(files.map { ListedFile(it.name, it.length(), it.lastModified()) })
        val root = File(context.cacheDir, "analysis-harness").apply { deleteRecursively() }
        val core = ImageCore(ImageCoreConfig(root = root, memoryCacheBytes = 64L shl 20))
        val faces = MlKitFaces()
        core.faces = faces
        fun imageFile(name: String): ImageFile {
            val file = File(dir, name)
            return ImageFile(FileIdentity("harness", name, file.length(), file.lastModified()), name, file) { FileSource(file) }
        }
        Log.i(TAG, "== ${shots.size} shots in $dir, source ${if (jpegOnly) "jpeg" else "plan"}")
        val started = System.nanoTime()
        var total = 0L
        for (shot in shots) {
            val request = ShotRequest(
                raw = if (jpegOnly && shot.rendered != null) null else shot.raw?.let { imageFile(it.name) },
                rendered = shot.rendered?.let { imageFile(it.name) },
            )
            val result = core.analyze(request, priority = 0).future.get()
            val a = result.analysis
            total += result.millis
            val faceText = a.faces.joinToString(";") { f ->
                "w=%.3f p=%s L=%s R=%s blur=%.3f".format(f.width, f.prominent, f.leftEyeOpen?.let { "%.2f".format(it) }, f.rightEyeOpen?.let { "%.2f".format(it) }, f.blur)
            }
            Log.i(
                TAG,
                "SHOT ${shot.stem} src=${result.source} ${a.width}x${a.height} ms=${result.millis} global=%.1f best=%.1f subject=%.1f kind=${a.subjectKind} blur=%.3f mean=%.3f hi=%.4f blown=%.4f faceHi=%.4f lo=%.4f hash=${a.hash} faces=${a.faces.size} [$faceText]"
                    .format(a.global, a.best, a.subject, a.subjectBlur, a.exposure.mean, a.exposure.highlights, a.exposure.blownArea, a.exposure.faceHighlights, a.exposure.shadows),
            )
        }
        // Synthetic blur with the same faces: where do face regions of degraded frames land?
        if (args.getString("synthetic") == "true") {
            for (shot in shots) {
                val request = ShotRequest(raw = shot.raw?.let { imageFile(it.name) }, rendered = shot.rendered?.let { imageFile(it.name) })
                val (image, _) = core.analysisRgb(request)
                val detected = core.detectFaces(image) ?: continue
                val base = ShotAnalysis.analyze(image, detected)
                val line = StringBuilder("SYN ${shot.stem} kind=${base.subjectKind} orig=%.3f".format(base.subjectBlur))
                for (sigma in listOf(1.0, 1.5, 2.0)) {
                    line.append(" g$sigma=%.3f".format(ShotAnalysis.analyze(gaussian(image, sigma), detected).subjectBlur))
                }
                for (length in listOf(6, 12)) {
                    line.append(" m$length=%.3f".format(ShotAnalysis.analyze(motion(image, length), detected).subjectBlur))
                }
                for (ev in listOf(0.5, 1.0, 1.5, 2.0)) {
                    val e = ShotAnalysis.analyze(exposure(image, ev), detected).exposure
                    line.append(" ev$ev=mean%.3f/blown%.4f/face%.4f".format(e.mean, e.blownArea, e.faceHighlights))
                }
                line.append(" ev0=blown%.4f/face%.4f".format(base.exposure.blownArea, base.exposure.faceHighlights))
                Log.i(TAG, line.toString())
            }
        }
        Log.i(TAG, "== done: ${shots.size} shots, analysis sum $total ms, wall ${(System.nanoTime() - started) / 1_000_000} ms, faces available ${faces.available}")
        faces.close()
        core.close()
        root.deleteRecursively()
    }

    /** ±EV in linear light (sRGB decoded, scaled, encoded, clipped), as in AnalysisCalibrationTest. */
    private fun exposure(image: Rgb, ev: Double): Rgb {
        val factor = Math.pow(2.0, ev)
        val lut = IntArray(256) { v ->
            val c = v / 255.0
            val linear = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            val scaled = (linear * factor).coerceIn(0.0, 1.0)
            val s = if (scaled <= 0.0031308) scaled * 12.92 else 1.055 * Math.pow(scaled, 1 / 2.4) - 0.055
            Math.round(s * 255).toInt().coerceIn(0, 255)
        }
        return Rgb(image.width, image.height, IntArray(image.argb.size) { i ->
            val c = image.argb[i]
            (0xFF shl 24) or (lut[(c shr 16) and 0xFF] shl 16) or (lut[(c shr 8) and 0xFF] shl 8) or lut[c and 0xFF]
        })
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

    companion object {
        private const val TAG = "HoardAnalysisHarness"
    }
}
