package dev.hfps.hoard.image.analysis

import dev.hfps.hoard.image.sharpness.Luma
import dev.hfps.hoard.image.sharpness.Sharpness
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Assisted culling measurements (ADR 0009), pure Kotlin so they run (and are calibrated) on the
 * JVM. Every formula here is written out in the ADR so another platform can reproduce the same
 * numbers: inputs are an upright sRGB image resampled to [ShotAnalysis.LONG_SIDE] with an exact
 * area average, Rec. 601 luma, and face boxes from a detector.
 */

/** An upright 8-bit RGB image, packed `0xAARRGGBB`, row-major. */
class Rgb(val width: Int, val height: Int, val argb: IntArray) {
    init { require(width > 0 && height > 0 && argb.size >= width * height) }
}

/** A face from the detector, in analysis-image pixels; probabilities are 0..1 or null when unknown. */
data class DetectedFace(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val leftEyeOpen: Float? = null,
    val rightEyeOpen: Float? = null,
    val smiling: Float? = null,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** A face as reported: box normalised to 0..1 of the upright image, and how sharp it is. */
data class FaceResult(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val leftEyeOpen: Double?,
    val rightEyeOpen: Double?,
    val smiling: Double?,
    /** Big enough to matter (see [ShotAnalysis.prominent]): only these can flag "eyes closed". */
    val prominent: Boolean,
    /** Variance of the Laplacian inside the box. */
    val sharpness: Double,
    /** Crété-Roffet blur inside the box, 0 (sharp) .. 1 (flat). */
    val blur: Double,
)

data class Exposure(
    /** 64 bins of luma (bin = Y / 4), as fractions of the pixels (sum 1). */
    val histogram: DoubleArray,
    /** Mean luma, 0..1. */
    val mean: Double,
    /** Share of pixels with Y >= 250 (blown highlights). */
    val highlights: Double,
    /** Share of pixels with Y <= 5 (crushed shadows). */
    val shadows: Double,
) {
    override fun equals(other: Any?) = other is Exposure && histogram.contentEquals(other.histogram) && mean == other.mean
    override fun hashCode() = histogram.contentHashCode()
}

data class AnalysisResult(
    val version: Int,
    val width: Int,
    val height: Int,
    /** Variance of the Laplacian over the whole frame. */
    val global: Double,
    /** The same per tile, [ShotAnalysis.TILE_COLS] x [ShotAnalysis.TILE_ROWS], row-major. */
    val tiles: DoubleArray,
    val bestTile: Int,
    /** Variance of the Laplacian of the subject: the largest prominent face, else the sharpest tile. */
    val subject: Double,
    /** `face` or `tile`. */
    val subjectKind: String,
    /** Crété-Roffet blur of the subject region: the scale-free number the "blurry" flag uses. */
    val subjectBlur: Double,
    val faces: List<FaceResult>,
    /** Faces were looked for (false: no detector on this device, faces unknown). */
    val facesDetected: Boolean,
    val exposure: Exposure,
    /** 64-bit difference hash (dHash) of the luma, 16 hex digits. */
    val hash: String,
    /** Mean colour of a 4 x 4 grid, 48 bytes as 96 hex digits (R, G, B per cell, row-major). */
    val colors: String,
) {
    val best: Double get() = tiles.getOrElse(bestTile) { global }
    override fun equals(other: Any?) = other is AnalysisResult && hash == other.hash && tiles.contentEquals(other.tiles) && faces == other.faces
    override fun hashCode() = hash.hashCode()
}

object ShotAnalysis {
    /**
     * Bump when any measurement changes: cached results carry it, and the app recomputes a
     * folder whose rows have an older version.
     */
    const val VERSION = 1

    /** Long side of the analysis image (the ZV-E10's embedded preview is 1616 px; JPEGs are reduced). */
    const val LONG_SIDE = 1536
    const val TILE_COLS = 4
    const val TILE_ROWS = 4

    /** A face counts when its box is at least this share of the image's long side... */
    const val MIN_FACE_SHARE = 0.05
    /** ...and at least this share of the largest face's width (a background face does not). */
    const val MIN_FACE_RELATIVE = 0.4

    /** Luma at or above this is a blown highlight; at or below [SHADOW_LUMA] a crushed shadow. */
    const val HIGHLIGHT_LUMA = 250
    const val SHADOW_LUMA = 5

    /** Length of the Crété-Roffet averaging filter (1 x 9 and 9 x 1). */
    const val BLUR_WINDOW = 9

    /** Upright size of the analysis image for a source of [width] x [height] (never upscaled). */
    fun analysisSize(width: Int, height: Int): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= LONG_SIDE) return width to height
        val scale = LONG_SIDE.toDouble() / long
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Everything about one shot. [image] must already be at [analysisSize] (see [Resample.area]). */
    fun analyze(image: Rgb, faces: List<DetectedFace>?): AnalysisResult {
        val luma = Luma.fromArgb(image.width, image.height, image.argb)
        val score = Sharpness.score(luma, TILE_COLS, TILE_ROWS)
        val bestTile = score.bestIndex
        val reported = facesOf(luma, faces.orEmpty())
        val main = reported.filter { it.prominent }.maxByOrNull { it.width * it.height }
        val (subject, kind, blur) = if (main != null) {
            Triple(main.sharpness, "face", main.blur)
        } else {
            val (x0, y0, x1, y1) = tileRect(bestTile, image.width, image.height)
            Triple(score.tiles.getOrElse(bestTile) { score.global }, "tile", Blur.creteRoffet(luma, x0, y0, x1, y1))
        }
        return AnalysisResult(
            version = VERSION,
            width = image.width,
            height = image.height,
            global = score.global,
            tiles = score.tiles,
            bestTile = bestTile,
            subject = subject,
            subjectKind = kind,
            subjectBlur = blur,
            faces = reported,
            facesDetected = faces != null,
            exposure = exposure(luma),
            hash = hex(dHash(luma)),
            colors = colorSignature(image).joinToString("") { "%02x".format(it.toInt() and 0xFF) },
        )
    }

    /** Pixel rectangle of tile [index] (same split as [Sharpness.score]: `x * cols / width`). */
    fun tileRect(index: Int, width: Int, height: Int): IntArray {
        val col = index.coerceAtLeast(0) % TILE_COLS
        val row = index.coerceAtLeast(0) / TILE_COLS
        val x0 = ceilDiv(col * width, TILE_COLS)
        val x1 = ceilDiv((col + 1) * width, TILE_COLS)
        val y0 = ceilDiv(row * height, TILE_ROWS)
        val y1 = ceilDiv((row + 1) * height, TILE_ROWS)
        return intArrayOf(x0, y0, x1, y1)
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    /** Faces normalised, flagged prominent or not, with the sharpness inside each box. */
    fun facesOf(luma: Luma, faces: List<DetectedFace>): List<FaceResult> {
        if (faces.isEmpty()) return emptyList()
        val long = max(luma.width, luma.height).toDouble()
        val largest = faces.maxOf { it.width }.toDouble()
        return faces.sortedByDescending { it.width * it.height }.map { face ->
            val x0 = face.left.toInt().coerceIn(0, luma.width - 1)
            val y0 = face.top.toInt().coerceIn(0, luma.height - 1)
            val x1 = face.right.roundToInt().coerceIn(x0 + 1, luma.width)
            val y1 = face.bottom.roundToInt().coerceIn(y0 + 1, luma.height)
            FaceResult(
                x = x0 / luma.width.toDouble(),
                y = y0 / luma.height.toDouble(),
                width = (x1 - x0) / luma.width.toDouble(),
                height = (y1 - y0) / luma.height.toDouble(),
                leftEyeOpen = face.leftEyeOpen?.toDouble(),
                rightEyeOpen = face.rightEyeOpen?.toDouble(),
                smiling = face.smiling?.toDouble(),
                prominent = prominent(face.width.toDouble(), long, largest),
                sharpness = Sharpness.region(luma, x0, y0, x1, y1),
                blur = Blur.creteRoffet(luma, x0, y0, x1, y1),
            )
        }
    }

    fun prominent(faceWidth: Double, imageLongSide: Double, largestFaceWidth: Double): Boolean =
        faceWidth >= imageLongSide * MIN_FACE_SHARE && faceWidth >= largestFaceWidth * MIN_FACE_RELATIVE

    fun exposure(luma: Luma): Exposure {
        val counts = LongArray(64)
        var sum = 0L
        var high = 0L
        var low = 0L
        val n = luma.width * luma.height
        for (i in 0 until n) {
            val y = luma.pixels[i].toInt() and 0xFF
            counts[y shr 2]++
            sum += y
            if (y >= HIGHLIGHT_LUMA) high++
            if (y <= SHADOW_LUMA) low++
        }
        return Exposure(
            histogram = DoubleArray(64) { counts[it].toDouble() / n },
            mean = sum.toDouble() / n / 255.0,
            highlights = high.toDouble() / n,
            shadows = low.toDouble() / n,
        )
    }

    /**
     * Difference hash: the luma area-averaged to 9 x 8; bit `r * 8 + c` (most significant first)
     * is 1 when cell (c, r) is brighter than cell (c + 1, r).
     */
    fun dHash(luma: Luma): Long {
        val small = Resample.areaGray(luma.pixels, luma.width, luma.height, 9, 8)
        var bits = 0L
        for (r in 0 until 8) for (c in 0 until 8) {
            bits = bits shl 1
            if (small[r * 9 + c] > small[r * 9 + c + 1]) bits = bits or 1L
        }
        return bits
    }

    fun hex(value: Long): String = java.lang.Long.toHexString(value).padStart(16, '0')

    fun hammingDistance(a: String, b: String): Int =
        java.lang.Long.bitCount(java.lang.Long.parseUnsignedLong(a, 16) xor java.lang.Long.parseUnsignedLong(b, 16))

    /** Mean colour of a 4 x 4 grid (area average), R, G, B per cell, rounded. */
    fun colorSignature(image: Rgb): ByteArray {
        val small = Resample.area(image.argb, image.width, image.height, 4, 4)
        val out = ByteArray(48)
        for (i in 0 until 16) {
            val c = small[i]
            out[i * 3] = ((c shr 16) and 0xFF).toByte()
            out[i * 3 + 1] = ((c shr 8) and 0xFF).toByte()
            out[i * 3 + 2] = (c and 0xFF).toByte()
        }
        return out
    }

    /** Mean absolute difference of two colour signatures, 0..1. */
    fun colorDistance(a: String, b: String): Double {
        if (a.length != b.length || a.isEmpty()) return 1.0
        var sum = 0
        for (i in a.indices step 2) sum += abs(a.substring(i, i + 2).toInt(16) - b.substring(i, i + 2).toInt(16))
        return sum / (a.length / 2) / 255.0
    }
}

/** Crété-Roffet no-reference blur (2007): how much a strong blur still changes the image. */
object Blur {
    /**
     * 0 = sharp, 1 = flat. On the rectangle [x0, x1) x [y0, y1) of [luma]: B = F averaged with a
     * 1 x [ShotAnalysis.BLUR_WINDOW] filter (vertical, then separately horizontal, edges clamped);
     * D_F = |F(p) - F(p - 1)|, D_B the same on B, V = max(0, D_F - D_B) along each direction;
     * blur = max over both directions of (ΣD_F - ΣV) / ΣD_F (1 when ΣD_F = 0).
     */
    fun creteRoffet(luma: Luma, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val w = luma.width
        val h = luma.height
        val left = x0.coerceIn(0, w)
        val right = x1.coerceIn(left, w)
        val top = y0.coerceIn(0, h)
        val bottom = y1.coerceIn(top, h)
        if (right - left < 2 || bottom - top < 2) return 1.0
        val half = ShotAnalysis.BLUR_WINDOW / 2
        val p = luma.pixels
        fun at(x: Int, y: Int) = p[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)].toInt() and 0xFF
        // Vertical: column running sums over the window.
        var sumFv = 0.0
        var sumVv = 0.0
        var sumFh = 0.0
        var sumVh = 0.0
        val window = ShotAnalysis.BLUR_WINDOW.toDouble()
        for (x in left until right) {
            var acc = 0
            for (k in -half..half) acc += at(x, top - 1 + k)
            var previousF = at(x, top - 1)
            var previousB = acc / window
            for (y in top until bottom) {
                acc += at(x, y + half) - at(x, y - 1 - half)
                val f = at(x, y)
                val b = acc / window
                val dF = abs(f - previousF).toDouble()
                val dB = abs(b - previousB)
                sumFv += dF
                sumVv += max(0.0, dF - dB)
                previousF = f
                previousB = b
            }
        }
        for (y in top until bottom) {
            var acc = 0
            for (k in -half..half) acc += at(left - 1 + k, y)
            var previousF = at(left - 1, y)
            var previousB = acc / window
            for (x in left until right) {
                acc += at(x + half, y) - at(x - 1 - half, y)
                val f = at(x, y)
                val b = acc / window
                val dF = abs(f - previousF).toDouble()
                val dB = abs(b - previousB)
                sumFh += dF
                sumVh += max(0.0, dF - dB)
                previousF = f
                previousB = b
            }
        }
        val bv = if (sumFv > 0) (sumFv - sumVv) / sumFv else 1.0
        val bh = if (sumFh > 0) (sumFh - sumVh) / sumFh else 1.0
        return max(bv, bh).coerceIn(0.0, 1.0)
    }
}

/** Exact area-average resampling (every source pixel weighs by the share of it a target pixel covers). */
object Resample {
    /** Per target index: first source index and the weights of the source pixels it covers. */
    private class Spans(val first: IntArray, val weights: Array<DoubleArray>)

    private fun spans(source: Int, target: Int): Spans {
        val scale = source.toDouble() / target
        val first = IntArray(target)
        val weights = Array(target) { i ->
            val start = i * scale
            val end = (i + 1) * scale
            val a = start.toInt()
            val b = min(source, kotlin.math.ceil(end - 1e-9).toInt())
            first[i] = a
            DoubleArray(max(1, b - a)) { k ->
                val px = a + k
                (min(end, px + 1.0) - max(start, px.toDouble())).coerceAtLeast(0.0) / scale
            }
        }
        return Spans(first, weights)
    }

    /** RGB (alpha dropped, output opaque) from [sw] x [sh] to [dw] x [dh]; downscale or same size. */
    fun area(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray =
        area(sw, sh, dw, dh) { y, row -> System.arraycopy(src, y * sw, row, 0, sw) }

    /**
     * The same, reading source rows on demand ([row] fills `sw` pixels of row `y`): only the few
     * source rows under the current output row are held, so a 3000 x 2000 decode is reduced
     * without a second full-size copy of it.
     */
    fun area(sw: Int, sh: Int, dw: Int, dh: Int, row: (y: Int, out: IntArray) -> Unit): IntArray {
        val xs = spans(sw, dw)
        val ys = spans(sh, dh)
        val line = IntArray(sw)
        // Horizontal results of source rows still needed (interleaved R, G, B), oldest dropped.
        val cache = HashMap<Int, FloatArray>()
        fun horizontal(y: Int): FloatArray = cache.getOrPut(y) {
            row(y, line)
            val out = FloatArray(dw * 3)
            for (x in 0 until dw) {
                var ar = 0.0; var ag = 0.0; var ab = 0.0
                val ws = xs.weights[x]
                val f = xs.first[x]
                for (k in ws.indices) {
                    val c = line[f + k]
                    val wgt = ws[k]
                    ar += ((c shr 16) and 0xFF) * wgt
                    ag += ((c shr 8) and 0xFF) * wgt
                    ab += (c and 0xFF) * wgt
                }
                out[x * 3] = ar.toFloat(); out[x * 3 + 1] = ag.toFloat(); out[x * 3 + 2] = ab.toFloat()
            }
            out
        }
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val ws = ys.weights[y]
            val f = ys.first[y]
            cache.keys.removeAll { it < f }
            val rows = Array(ws.size) { horizontal(f + it) }
            for (x in 0 until dw) {
                var ar = 0.0; var ag = 0.0; var ab = 0.0
                for (k in ws.indices) {
                    val r = rows[k]
                    ar += r[x * 3] * ws[k]; ag += r[x * 3 + 1] * ws[k]; ab += r[x * 3 + 2] * ws[k]
                }
                out[y * dw + x] = (0xFF shl 24) or (clamp(ar) shl 16) or (clamp(ag) shl 8) or clamp(ab)
            }
        }
        return out
    }

    /** Gray bytes from [sw] x [sh] to [dw] x [dh], as doubles (no rounding: hashes compare exact means). */
    fun areaGray(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int): DoubleArray {
        val xs = spans(sw, dw)
        val ys = spans(sh, dh)
        val mid = DoubleArray(sh * dw)
        for (y in 0 until sh) for (x in 0 until dw) {
            var acc = 0.0
            val ws = xs.weights[x]
            for (k in ws.indices) acc += (src[y * sw + xs.first[x] + k].toInt() and 0xFF) * ws[k]
            mid[y * dw + x] = acc
        }
        val out = DoubleArray(dw * dh)
        for (y in 0 until dh) for (x in 0 until dw) {
            var acc = 0.0
            val ws = ys.weights[y]
            for (k in ws.indices) acc += mid[(ys.first[y] + k) * dw + x] * ws[k]
            out[y * dw + x] = acc
        }
        return out
    }

    private fun clamp(v: Double) = (v + 0.5).toInt().coerceIn(0, 255)
}
