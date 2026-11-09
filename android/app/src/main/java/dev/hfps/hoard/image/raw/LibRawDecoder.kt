package dev.hfps.hoard.image.raw

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.Keep
import dev.hfps.hoard.image.source.CancelToken
import dev.hfps.hoard.image.source.CancelledException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Semaphore

/** Thrown by the native layer. [code] is a LibRaw error (negative) or a Hoard one (<= -1000). */
@Keep
class LibRawException(val code: Int, message: String) : IOException(message) {
    val isCancelled: Boolean get() = code == LIBRAW_CANCELLED_BY_CALLBACK

    companion object {
        const val LIBRAW_CANCELLED_BY_CALLBACK = -100010
        const val COMPRESS_UNAVAILABLE = -1000
    }
}

/** Called from native code at every LibRaw stage; return false to cancel. */
@Keep
fun interface RawProgressListener {
    fun onProgress(stage: Int, iteration: Int, expected: Int): Boolean
}

/** JNI entry points (libhoardimage.so). */
internal object LibRawNative {
    @JvmStatic external fun nativeVersion(): String
    @JvmStatic external fun nativeCanCompress(): Boolean
    @JvmStatic external fun nativeDecodeBitmap(path: String, halfSize: Boolean, demosaic: Int, listener: RawProgressListener?): Bitmap?
    @JvmStatic external fun nativeDecodeJpeg(path: String, halfSize: Boolean, demosaic: Int, outPath: String, quality: Int, listener: RawProgressListener?): IntArray?
}

/** LibRaw demosaic algorithms (`user_qual`). */
enum class Demosaic(val code: Int) { LINEAR(0), VNG(1), PPG(2), AHD(3), DCB(4), DHT(11), AAHD(12) }

/** Result of a decode to a file: size of the (unrotated) rendering and where time went. */
data class RawRender(val file: File, val width: Int, val height: Int, val stats: RawDecodeStats)

/** Milliseconds per phase (open+identify, unpack, process, output), for the harness and logs. */
data class RawDecodeStats(val totalMs: Long, val stageMs: Map<String, Long>)

/**
 * Full RAW decoding through LibRaw (vendored, `src/main/cpp`). White balance as shot, camera matrix,
 * sRGB output with the sRGB curve, LibRaw's auto-brightness (same white point as dcraw/darktable's
 * "camera" default). Orientation is NOT applied: callers rotate with the RAW's EXIF orientation.
 *
 * Only one decode runs at a time: a full-size 24 MP decode peaks around 250 MB of native memory.
 */
object LibRawDecoder {

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("hoardimage")
            true
        } catch (e: UnsatisfiedLinkError) {
            // ABIs without the library (armeabi-v7a, x86): RAW files fall back to embedded previews.
            Log.w(TAG, "libhoardimage unavailable: ${e.message}")
            false
        }
    }

    private val gate = Semaphore(1, true)

    val isAvailable: Boolean get() = loaded

    val version: String? get() = if (loaded) LibRawNative.nativeVersion() else null

    /** Half size = 2x2 binning (no demosaic): 6000x4000 -> 3000x2000, ~4x faster. */
    fun decodeBitmap(raw: File, halfSize: Boolean, demosaic: Demosaic = Demosaic.AHD, cancel: CancelToken = CancelToken.NONE): Pair<Bitmap, RawDecodeStats> {
        check(loaded) { "LibRaw is not available on this device" }
        return gated(cancel) { listener ->
            LibRawNative.nativeDecodeBitmap(raw.path, halfSize, demosaic.code, listener)
                ?: throw IOException("LibRaw returned no image")
        }
    }

    /**
     * Decodes [raw] and writes a JPEG to [out] (unrotated), without holding a full-size Bitmap on
     * Android 11+ (AndroidBitmap_compress from the native buffer); older versions go through a
     * Bitmap.
     */
    fun decodeToJpeg(raw: File, out: File, halfSize: Boolean, demosaic: Demosaic = Demosaic.AHD, quality: Int = 92, cancel: CancelToken = CancelToken.NONE): RawRender {
        check(loaded) { "LibRaw is not available on this device" }
        if (LibRawNative.nativeCanCompress()) {
            val (dims, stats) = gated(cancel) { listener ->
                LibRawNative.nativeDecodeJpeg(raw.path, halfSize, demosaic.code, out.path, quality, listener)
                    ?: throw IOException("LibRaw returned no image")
            }
            return RawRender(out, dims[0], dims[1], stats)
        }
        val (bitmap, stats) = decodeBitmap(raw, halfSize, demosaic, cancel)
        try {
            FileOutputStream(out).use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) throw IOException("JPEG encode failed") }
            return RawRender(out, bitmap.width, bitmap.height, stats)
        } finally {
            bitmap.recycle()
        }
    }

    private fun <T> gated(cancel: CancelToken, block: (RawProgressListener) -> T): Pair<T, RawDecodeStats> {
        gate.acquire()
        try {
            cancel.throwIfCancelled()
            val start = System.nanoTime()
            val stages = LinkedHashMap<String, Long>()
            var phase = "open"
            var phaseStart = start
            val listener = RawProgressListener { stage, _, _ ->
                val name = phaseOf(stage)
                if (name != phase) {
                    val now = System.nanoTime()
                    stages[phase] = (stages[phase] ?: 0) + (now - phaseStart) / 1_000_000
                    phase = name
                    phaseStart = now
                }
                !cancel.isCancelled
            }
            val result = try {
                block(listener)
            } catch (e: LibRawException) {
                if (e.isCancelled || cancel.isCancelled) throw CancelledException()
                throw e
            }
            val end = System.nanoTime()
            stages[phase] = (stages[phase] ?: 0) + (end - phaseStart) / 1_000_000
            if (phase != "output") stages["output"] = 0
            return result to RawDecodeStats((end - start) / 1_000_000, stages)
        } finally {
            gate.release()
        }
    }

    /** Groups LibRaw_progress flags (libraw_const.h) into phases. */
    private fun phaseOf(stage: Int): String = when {
        stage <= 0x4 -> "open"              // START, OPEN, IDENTIFY, SIZE_ADJUST
        stage == 0x8 -> "unpack"            // LOAD_RAW
        stage < OUTPUT_STAGE -> "process"   // raw2image, scale colors, demosaic, convert RGB, ...
        else -> "output"                    // hoard: 8-bit conversion + Bitmap copy / JPEG encode
    }

    /** LIBRAW_PROGRESS_STAGE27, reported by hoard_image_jni.cpp before its own output step. */
    private const val OUTPUT_STAGE = 1 shl 27
    private const val TAG = "HoardImage"
}
