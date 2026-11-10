package dev.hfps.hoard.image.sharpness

import kotlin.math.max
import kotlin.math.min

/** A grayscale image, 0..255 per pixel, row-major. */
class Luma(val width: Int, val height: Int, val pixels: ByteArray) {
    init { require(pixels.size >= width * height) }
    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    companion object {
        /** From packed ARGB ints (Bitmap.getPixels, BufferedImage.getRGB): Rec. 601 luma. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): Luma {
            val out = ByteArray(width * height)
            for (i in out.indices) {
                val c = argb[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
            return Luma(width, height, out)
        }
    }
}

/**
 * Focus/blur score: variance of the 4-neighbour Laplacian over a downscaled luminance image.
 *
 * - [global]: whole frame (high = lots of fine detail in focus; depends on content, so compare
 *   shots of one burst, not across scenes).
 * - [tiles]: the same per tile on a [cols] x [rows] grid; [best] is the sharpest tile: a portrait
 *   with a sharp face on a creamy background scores low globally but high on the face's tile, which
 *   is what "is the subject in focus" needs.
 * - Comparable only at the same analysis size (see [Sharpness.ANALYSIS_LONG_SIDE]).
 */
data class SharpnessScore(
    val global: Double,
    val cols: Int,
    val rows: Int,
    val tiles: DoubleArray,
) {
    val bestIndex: Int get() = tiles.indices.maxByOrNull { tiles[it] } ?: -1
    val best: Double get() = tiles.maxOrNull() ?: global

    /** Mean of the top quarter of tiles: robust to one noisy/specular tile. */
    val topQuarter: Double get() {
        if (tiles.isEmpty()) return global
        val sorted = tiles.sortedDescending()
        val n = max(1, sorted.size / 4)
        return sorted.take(n).average()
    }

    override fun equals(other: Any?) = other is SharpnessScore && global == other.global && tiles.contentEquals(other.tiles)
    override fun hashCode() = global.hashCode() * 31 + tiles.contentHashCode()
}

object Sharpness {
    /** Longest side the luminance is reduced to before scoring (cheap: ~0.5 MP). */
    const val ANALYSIS_LONG_SIDE = 1024

    fun score(luma: Luma, cols: Int = 4, rows: Int = 4): SharpnessScore {
        val w = luma.width
        val h = luma.height
        val tileSum = DoubleArray(cols * rows)
        val tileSq = DoubleArray(cols * rows)
        val tileN = LongArray(cols * rows)
        var sum = 0.0
        var sq = 0.0
        var n = 0L
        val p = luma.pixels
        for (y in 1 until h - 1) {
            val row = y * w
            val ty = min(rows - 1, y * rows / h)
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = (p[i - 1].toInt() and 0xFF) + (p[i + 1].toInt() and 0xFF) +
                    (p[i - w].toInt() and 0xFF) + (p[i + w].toInt() and 0xFF) - 4 * (p[i].toInt() and 0xFF)
                val v = lap.toDouble()
                sum += v
                sq += v * v
                n++
                val t = ty * cols + min(cols - 1, x * cols / w)
                tileSum[t] += v
                tileSq[t] += v * v
                tileN[t]++
            }
        }
        val tiles = DoubleArray(cols * rows) { variance(tileSum[it], tileSq[it], tileN[it]) }
        return SharpnessScore(variance(sum, sq, n), cols, rows, tiles)
    }

    /**
     * Variance of the 4-neighbour Laplacian over the rectangle [x0, x1) x [y0, y1) (pixels on the
     * image border are skipped, as in [score]). 0 for regions smaller than 3 x 3.
     */
    fun region(luma: Luma, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val w = luma.width
        val left = max(1, x0)
        val right = min(w - 1, x1)
        val top = max(1, y0)
        val bottom = min(luma.height - 1, y1)
        var sum = 0.0
        var sq = 0.0
        var n = 0L
        val p = luma.pixels
        for (y in top until bottom) {
            val row = y * w
            for (x in left until right) {
                val i = row + x
                val lap = (p[i - 1].toInt() and 0xFF) + (p[i + 1].toInt() and 0xFF) +
                    (p[i - w].toInt() and 0xFF) + (p[i + w].toInt() and 0xFF) - 4 * (p[i].toInt() and 0xFF)
                val v = lap.toDouble()
                sum += v
                sq += v * v
                n++
            }
        }
        return variance(sum, sq, n)
    }

    /** Box-filter downscale so the longest side is at most [longSide] (integer factor). */
    fun downscale(luma: Luma, longSide: Int = ANALYSIS_LONG_SIDE): Luma {
        val factor = (max(luma.width, luma.height) + longSide - 1) / longSide
        if (factor <= 1) return luma
        val w = luma.width / factor
        val h = luma.height / factor
        val out = ByteArray(w * h)
        val area = factor * factor
        for (y in 0 until h) {
            for (x in 0 until w) {
                var acc = 0
                for (dy in 0 until factor) {
                    val row = (y * factor + dy) * luma.width + x * factor
                    for (dx in 0 until factor) acc += luma.pixels[row + dx].toInt() and 0xFF
                }
                out[y * w + x] = (acc / area).toByte()
            }
        }
        return Luma(w, h, out)
    }

    private fun variance(sum: Double, sq: Double, n: Long): Double {
        if (n < 2) return 0.0
        val mean = sum / n
        return (sq / n - mean * mean).coerceAtLeast(0.0)
    }
}
