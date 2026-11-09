package dev.hfps.hoard.image

import android.graphics.Bitmap
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.hoard.image.cache.FileIdentity
import dev.hfps.hoard.image.decode.BitmapDecoding
import dev.hfps.hoard.image.decode.IntRect
import dev.hfps.hoard.image.decode.ZoomImage
import dev.hfps.hoard.image.format.ImageInspector
import dev.hfps.hoard.image.format.ListedFile
import dev.hfps.hoard.image.format.ShotPairing
import dev.hfps.hoard.image.raw.Demosaic
import dev.hfps.hoard.image.raw.LibRawDecoder
import dev.hfps.hoard.image.source.CountingSource
import dev.hfps.hoard.image.source.FileSource
import dev.hfps.hoard.image.source.readRange
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Debug harness: runs the image pipeline on a folder of real camera files on the device and logs
 * timings (tag HoardHarness) plus a report at `<external files>/harness-report.txt`.
 *
 * Copy files (copies, never originals) into the debug app's `files/harness` (see ADR 0006 for the
 * `adb push` + `run-as` steps), then on an emulator only:
 * ```
 * adb shell am instrument -w -e class dev.hfps.hoard.image.ImageCoreHarnessTest \
 *   [-e dir /path] [-e target 3120] [-e limit 3] [-e full true] [-e keep true] \
 *   dev.hfps.hoard.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * Skipped when the folder is missing or empty (so `connectedAndroidTest` stays green).
 */
@RunWith(AndroidJUnit4::class)
class ImageCoreHarnessTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val report = StringBuilder()

    private fun log(line: String) {
        Log.i(TAG, line)
        report.append(line).append('\n')
    }

    @Test
    fun runPipelineOnFolder() {
        val dir = args.getString("dir")?.let(::File) ?: File(context.filesDir, "harness")
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        assumeTrue("No files in $dir", files.isNotEmpty())
        val target = args.getString("target")?.toInt() ?: 2400
        val limit = args.getString("limit")?.toInt() ?: Int.MAX_VALUE
        val full = args.getString("full")?.toBoolean() ?: true

        val shots = ShotPairing.pair(files.map { ListedFile(it.name, it.length(), it.lastModified()) }).take(limit)
        log("== Hoard image harness: ${shots.size} shots in $dir, target long side $target, LibRaw ${LibRawDecoder.version ?: "unavailable"}")
        log("device: ${android.os.Build.MODEL} SDK ${android.os.Build.VERSION.SDK_INT}, ${Runtime.getRuntime().availableProcessors()} cores")

        for (shot in shots) {
            log("-- ${shot.stem} (${if (shot.isPair) "RAW+JPEG" else if (shot.isRawOnly) "RAW only" else "JPEG only"})")
            shot.raw?.let { raw -> rawSteps(File(dir, raw.name), target, full) }
            shot.rendered?.let { jpeg -> jpegSteps(File(dir, jpeg.name), target) }
        }
        facadeSteps(dir, shots, target)

        val out = File(context.getExternalFilesDir(null), "harness-report.txt")
        out.writeText(report.toString())
        Log.i(TAG, "report written to $out")
    }

    private fun rawSteps(file: File, target: Int, full: Boolean) {
        // 1. Header + preview extraction (bytes read vs file size).
        val counting = CountingSource(FileSource(file))
        val (inspection, inspectMs) = timed { ImageInspector.inspect(counting, file.name) }
        val headerBytes = counting.bytesRead
        val preview = inspection.largestPreview
        val (bytes, extractMs) = timed { preview?.let { counting.readRange(it.offset, it.length.toInt()) } }
        counting.close()
        log("  ARW ${file.length()} B: inspect ${inspectMs} ms (${headerBytes} B, ${counting.readCalls - 1} reads); " +
            "previews ${inspection.previews.joinToString { "${it.width}x${it.height}/${it.length}B@${it.offset}" }}; " +
            "preview read ${extractMs} ms; total read ${counting.bytesRead} B = ${"%.1f".format(counting.bytesRead * 100.0 / file.length())}%")
        val m = inspection.metadata
        log("  EXIF ${m.make} ${m.model} ${m.captureTime}.${m.subSecond} ${m.exposureLabel}s f/${m.fNumber} ISO${m.iso} ${m.focalLength}mm ${m.lens} o=${m.orientation}")

        // 2. Preview decode for the screen (orientation applied).
        if (bytes != null) {
            val (decoded, ms) = timed { BitmapDecoding.decode(bytes, target, m.orientation) }
            val (score, sharpMs) = timed { ImageCore.sharpness(decoded.bitmap) }
            log("  preview decode ${ms} ms -> ${decoded.bitmap.width}x${decoded.bitmap.height}; sharpness global ${"%.1f".format(score.global)} best ${"%.1f".format(score.best)} (tile ${score.bestIndex}) in ${sharpMs} ms")
            decoded.bitmap.recycle()
        }

        if (!LibRawDecoder.isAvailable) {
            log("  LibRaw unavailable on this ABI")
            return
        }
        // 3. LibRaw half size -> Bitmap.
        System.gc()
        val (half, halfPeak) = peakNative { LibRawDecoder.decodeBitmap(file, halfSize = true) }
        log("  LibRaw half ${half.first.width}x${half.first.height}: ${half.second.totalMs} ms ${half.second.stageMs}; peak native +${mb(halfPeak)} MB")
        val (halfScore, _) = timed { ImageCore.sharpness(half.first) }
        log("  LibRaw half sharpness global ${"%.1f".format(halfScore.global)} best ${"%.1f".format(halfScore.best)}")
        half.first.recycle()

        if (!full) return
        // 4. LibRaw full size -> JPEG on disk -> region decode at 100%.
        for (demosaic in listOf(Demosaic.PPG, Demosaic.AHD)) {
            System.gc()
            val out = File(context.cacheDir, "harness-full-${demosaic.name}.jpg")
            val (render, peak) = peakNative { LibRawDecoder.decodeToJpeg(file, out, halfSize = false, demosaic = demosaic) }
            log("  LibRaw full $demosaic ${render.width}x${render.height} -> ${out.length()} B JPEG: ${render.stats.totalMs} ms ${render.stats.stageMs}; peak native +${mb(peak)} MB")
            regionSteps(out, m.orientation, "LibRaw full $demosaic")
            if (args.getString("keep") == "true") keepSamples(out, m.orientation, "${file.nameWithoutExtension}-$demosaic")
            out.delete()
        }
    }

    private fun jpegSteps(file: File, target: Int) {
        val counting = CountingSource(FileSource(file))
        val (inspection, inspectMs) = timed { ImageInspector.inspect(counting, file.name) }
        counting.close()
        val orientation = inspection.metadata.orientation
        log("  JPG ${file.length()} B: inspect ${inspectMs} ms (${counting.bytesRead} B), ${inspection.metadata.width}x${inspection.metadata.height} o=$orientation rating=${inspection.metadata.rating}")
        System.gc()
        val (pair, peak) = peakNative { timed { BitmapDecoding.decode(file, target, orientation) } }
        val (decoded, ms) = pair
        log("  JPG screen decode ${ms} ms -> ${decoded.bitmap.width}x${decoded.bitmap.height} (sample ${decoded.sampleSize}); peak native +${mb(peak)} MB")
        val (score, sharpMs) = timed { ImageCore.sharpness(decoded.bitmap) }
        log("  JPG sharpness global ${"%.1f".format(score.global)} best ${"%.1f".format(score.best)} (tile ${score.bestIndex}) top¼ ${"%.1f".format(score.topQuarter)} in ${sharpMs} ms")
        decoded.bitmap.recycle()
        regionSteps(file, orientation, "JPG")
    }

    /** `-e keep true`: saves a 1/4 view and a 100% centre tile of a render for a visual check. */
    private fun keepSamples(render: File, orientation: Int, name: String) {
        val dir = File(context.getExternalFilesDir(null), "samples").apply { mkdirs() }
        ZoomImage(render, orientation).use { z ->
            val cx = z.width / 2
            val cy = z.height / 2
            listOf(
                "quarter" to z.decode(IntRect(0, 0, z.width, z.height), 4),
                "tile100" to z.decode(IntRect(cx - 400, cy - 400, cx + 400, cy + 400), 1),
            ).forEach { (kind, bitmap) ->
                File(dir, "$name-$kind.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
            }
        }
    }

    /** Opens a region decoder and decodes a 1024x1024 tile at 100% from the centre and a corner. */
    private fun regionSteps(file: File, orientation: Int, label: String) {
        System.gc()
        val (zoomed, peak) = peakNative {
            val (zoom, openMs) = timed { ZoomImage(file, orientation) }
            zoom.use {
                val cx = it.width / 2
                val cy = it.height / 2
                val (center, centerMs) = timed { it.decode(IntRect(cx - 512, cy - 512, cx + 512, cy + 512), 1) }
                val (corner, cornerMs) = timed { it.decode(IntRect(0, 0, 1024, 1024), 1) }
                val (quarter, quarterMs) = timed { it.decode(IntRect(0, 0, it.width, it.height), 4) }
                val result = "$label region: open ${openMs} ms (${it.width}x${it.height}); 1024² tile 100% centre ${centerMs} ms, corner ${cornerMs} ms; whole at 1/4 ${quarterMs} ms (${quarter.width}x${quarter.height})"
                listOf(center, corner, quarter).forEach(Bitmap::recycle)
                result
            }
        }
        log("  $zoomed; peak native +${mb(peak)} MB")
    }

    /** End to end through the facade: screen jobs, prefetch, zoom, for SMB-like sources (files here). */
    private fun facadeSteps(dir: File, shots: List<dev.hfps.hoard.image.format.Shot>, target: Int) {
        val root = File(context.cacheDir, "harness-core")
        root.deleteRecursively()
        val core = ImageCore(ImageCoreConfig.default(context).copy(root = root))
        try {
            fun file(name: String): ImageFile {
                val f = File(dir, name)
                return ImageFile(FileIdentity("harness", f.path, f.length(), f.lastModified()), name) { FileSource(f) }
            }
            val requests = shots.map { ShotRequest(it.raw?.let { r -> file(r.name) }, it.rendered?.let { j -> file(j.name) }) }
            log("== Facade (ImageCore): ${requests.size} shots")
            for ((i, request) in requests.withIndex()) {
                val plan = core.screenPlan(request, target)
                val quick = timed { core.screen(request, target, step = plan.first()).future.get(60, TimeUnit.SECONDS) }
                core.prefetch(requests, i, target, zoomAhead = 1)
                val final = timed { core.screen(request, target).future.get(120, TimeUnit.SECONDS) }
                val again = timed { core.screen(request, target).future.get(5, TimeUnit.SECONDS) }
                log("  ${request.primary.name}: plan $plan quick ${quick.second} ms (${quick.first.bitmap.width}px), final ${final.second} ms (${final.first.bitmap.width}px), memory hit ${again.second} ms")
            }
            // Zoom on a RAW-only view of the first shot (forces LibRaw full), twice (second from disk cache).
            requests.firstOrNull { it.raw != null }?.let { pair ->
                val rawOnly = ShotRequest(raw = pair.raw)
                repeat(2) { attempt ->
                    val (zoom, ms) = timed { core.zoom(rawOnly).future.get(300, TimeUnit.SECONDS) }
                    log("  zoom RAW-only ${pair.raw!!.name} (${core.zoomPlan(rawOnly)}) attempt ${attempt + 1}: ${ms} ms -> ${zoom.width}x${zoom.height}")
                    zoom.close()
                }
            }
        } finally {
            core.close()
            root.deleteRecursively()
        }
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val value = block()
        return value to (System.nanoTime() - start) / 1_000_000
    }

    /** Runs [block] while sampling the native heap every 5 ms; returns the peak above the start. */
    private fun <T> peakNative(block: () -> T): Pair<T, Long> {
        val base = Debug.getNativeHeapAllocatedSize()
        val peak = AtomicLong(base)
        val running = AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::max)
                Thread.sleep(5)
            }
        }.apply { start() }
        try {
            val value = block()
            peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::max)
            return value to (peak.get() - base)
        } finally {
            running.set(false)
            sampler.join()
        }
    }

    private fun mb(bytes: Long) = "%.0f".format(bytes / 1048576.0)

    private companion object {
        const val TAG = "HoardHarness"
    }
}
