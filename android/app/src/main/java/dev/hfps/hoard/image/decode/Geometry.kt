package dev.hfps.hoard.image.decode

import kotlin.math.max
import kotlin.math.min

/** Integer rectangle [left, top, right, bottom) (plain Kotlin: testable on the JVM). */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun intersect(width: Int, height: Int): IntRect =
        IntRect(left.coerceIn(0, width), top.coerceIn(0, height), right.coerceIn(0, width), bottom.coerceIn(0, height))
}

/**
 * EXIF orientation (1..8) as "how to turn the stored pixels to show them upright", and the inverse
 * mapping of a rectangle in the upright (displayed) image back to stored pixels, which is what
 * region decoding needs: BitmapRegionDecoder reads stored pixels and knows nothing about EXIF.
 */
object Orientation {

    /** Clockwise rotation in degrees, applied first. */
    fun rotation(orientation: Int): Int = when (orientation) {
        3, 4 -> 180
        5, 6 -> 90
        7, 8 -> 270
        else -> 0
    }

    /** Mirror horizontally, applied after [rotation] (5 = transpose, 7 = transverse). */
    fun flipped(orientation: Int): Boolean = orientation in setOf(2, 4, 5, 7)

    fun transposes(orientation: Int): Boolean = orientation in 5..8

    /** Size shown on screen for a stored [width] x [height]. */
    fun displayedSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        if (transposes(orientation)) height to width else width to height

    /**
     * Maps a point of the displayed image (dw x dh) to the stored image (sw x sh).
     * Point coordinates are pixel edges (0..w), so rectangles map by their corners.
     */
    fun toStored(x: Int, y: Int, storedWidth: Int, storedHeight: Int, orientation: Int): Pair<Int, Int> {
        val sw = storedWidth
        val sh = storedHeight
        return when (orientation) {
            2 -> (sw - x) to y
            3 -> (sw - x) to (sh - y)
            4 -> x to (sh - y)
            5 -> y to x
            6 -> y to (sh - x)
            7 -> (sw - y) to (sh - x)
            8 -> (sw - y) to x
            else -> x to y
        }
    }

    /** Rectangle of the displayed image -> rectangle of stored pixels. */
    fun toStored(rect: IntRect, storedWidth: Int, storedHeight: Int, orientation: Int): IntRect {
        val (x1, y1) = toStored(rect.left, rect.top, storedWidth, storedHeight, orientation)
        val (x2, y2) = toStored(rect.right, rect.bottom, storedWidth, storedHeight, orientation)
        return IntRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }
}

object Sampling {
    /**
     * Largest power-of-two sample size that keeps the decoded image's longest side at least
     * [SCREEN_SLACK] of [targetLongSide]: a 6000 px JPEG for a 3120 px screen decodes at 3000 px
     * (24 MB) instead of 6000 px (96 MB); the 4% difference is invisible after GPU scaling.
     */
    fun sampleSize(width: Int, height: Int, targetLongSide: Int): Int {
        val longest = max(width, height)
        if (targetLongSide <= 0 || longest <= targetLongSide) return 1
        val minimum = targetLongSide * SCREEN_SLACK
        var sample = 1
        while (longest / (sample * 2f) >= minimum) sample *= 2
        return sample
    }

    const val SCREEN_SLACK = 0.9f

    /** Sample size for a region shown at [scale] (displayed px per image px, 1 = 100%). */
    fun sampleForScale(scale: Float): Int {
        if (scale >= 1f) return 1
        var sample = 1
        while (1f / (sample * 2) >= scale) sample *= 2
        return sample
    }
}
