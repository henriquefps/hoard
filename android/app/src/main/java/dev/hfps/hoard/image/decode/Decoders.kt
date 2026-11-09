package dev.hfps.hoard.image.decode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import java.io.Closeable
import java.io.File
import java.io.IOException

/** A decoded, upright bitmap and what it came from. */
data class DecodedImage(
    val bitmap: Bitmap,
    /** Upright size of the full image this is a (downsampled) view of. */
    val fullWidth: Int,
    val fullHeight: Int,
    /** Sample size used (1 = full resolution). */
    val sampleSize: Int,
) {
    val byteCount: Long get() = bitmap.allocationByteCount.toLong()
}

/** Downsampled, orientation-correct decoding with BitmapFactory (libjpeg-turbo scaled IDCT for JPEG). */
object BitmapDecoding {

    /** Stored (unrotated) size, or null when the file is not decodable. */
    fun bounds(file: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    fun bounds(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    /**
     * Decodes [file] so its longest side is at least [targetLongSide] (power-of-two subsampling),
     * then applies [orientation]. [hardware] returns a GPU-only bitmap (Android 8+): no Java/native
     * heap, cannot be read back (no sharpness scoring on it).
     */
    fun decode(file: File, targetLongSide: Int, orientation: Int, hardware: Boolean = false): DecodedImage {
        val (w, h) = bounds(file) ?: throw IOException("Not a decodable image: ${file.name}")
        val sample = Sampling.sampleSize(w, h, targetLongSide)
        val options = options(sample, hardware && orientation == 1)
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: throw IOException("Decode failed: ${file.name}")
        return finish(bitmap, w, h, sample, orientation, hardware)
    }

    fun decode(bytes: ByteArray, targetLongSide: Int, orientation: Int, hardware: Boolean = false): DecodedImage {
        val (w, h) = bounds(bytes) ?: throw IOException("Not a decodable image")
        val sample = Sampling.sampleSize(w, h, targetLongSide)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options(sample, hardware && orientation == 1))
            ?: throw IOException("Decode failed")
        return finish(bitmap, w, h, sample, orientation, hardware)
    }

    /** Applies orientation to an already decoded bitmap (LibRaw output, cached renders). */
    fun finish(bitmap: Bitmap, storedWidth: Int, storedHeight: Int, sample: Int, orientation: Int, hardware: Boolean): DecodedImage {
        var out = orient(bitmap, orientation)
        if (hardware && Build.VERSION.SDK_INT >= 26 && out.config != Bitmap.Config.HARDWARE) {
            out.copy(Bitmap.Config.HARDWARE, false)?.let { hw -> out.recycle(); out = hw }
        }
        val (fw, fh) = Orientation.displayedSize(storedWidth, storedHeight, orientation)
        return DecodedImage(out, fw, fh, sample)
    }

    private fun options(sample: Int, hardware: Boolean) = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = if (hardware && Build.VERSION.SDK_INT >= 26) Bitmap.Config.HARDWARE else Bitmap.Config.ARGB_8888
    }

    /** Rotates/mirrors per EXIF [orientation] (see [Orientation]); recycles the input when it copies. */
    fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation !in 2..8) return bitmap
        val matrix = Matrix().apply {
            postRotate(Orientation.rotation(orientation).toFloat())
            if (Orientation.flipped(orientation)) postScale(-1f, 1f)
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }
}

/**
 * Region decoding of one local JPEG for 100% zoom: only the requested tile is decoded, so zooming a
 * 24 MP image never allocates 96 MB. Coordinates are in the UPRIGHT image (EXIF applied); tiles come
 * back upright.
 *
 * BitmapRegionDecoder is internally synchronized; one instance serves a whole zoom session. Close it
 * when the image leaves the screen.
 */
class ZoomImage(val file: File, val orientation: Int) : Closeable {

    private val decoder: BitmapRegionDecoder = open(file)

    /** Stored size. */
    val storedWidth: Int = decoder.width
    val storedHeight: Int = decoder.height

    /** Upright size (what the UI lays out). */
    val width: Int get() = Orientation.displayedSize(storedWidth, storedHeight, orientation).first
    val height: Int get() = Orientation.displayedSize(storedWidth, storedHeight, orientation).second

    /**
     * Decodes the upright rectangle [rect] at [sampleSize] (1 = 100%). Returns an upright bitmap of
     * about rect/sampleSize pixels.
     */
    fun decode(rect: IntRect, sampleSize: Int = 1, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val clipped = rect.intersect(width, height)
        require(!clipped.isEmpty) { "Empty region $rect in ${width}x$height" }
        val stored = Orientation.toStored(clipped, storedWidth, storedHeight, orientation)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize.coerceAtLeast(1)
            inPreferredConfig = config
        }
        val tile = decoder.decodeRegion(Rect(stored.left, stored.top, stored.right, stored.bottom), options)
            ?: throw IOException("Region decode failed for $rect")
        return BitmapDecoding.orient(tile, orientation)
    }

    override fun close() = decoder.recycle()

    private companion object {
        fun open(file: File): BitmapRegionDecoder =
            if (Build.VERSION.SDK_INT >= 31) {
                BitmapRegionDecoder.newInstance(file.path)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(file.path, false)
            } ?: throw IOException("Cannot region-decode ${file.name}")
    }
}
