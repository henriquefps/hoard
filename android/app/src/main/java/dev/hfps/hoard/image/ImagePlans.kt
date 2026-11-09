package dev.hfps.hoard.image

import dev.hfps.hoard.image.cache.FileIdentity
import dev.hfps.hoard.image.source.RandomAccessSource
import java.io.File

/**
 * One file the core can read: its identity (cache key) and how to open it (SMB, local, SAF…).
 * [local] is the file itself when it can be read by path (phone storage, a USB drive with all
 * files access): decoders and LibRaw then read it in place instead of a staged copy.
 */
class ImageFile(
    val identity: FileIdentity,
    val name: String,
    val local: File? = null,
    val open: () -> RandomAccessSource,
) {
    override fun toString() = "ImageFile(${identity.sourceId}:${identity.path})"
}

/** A shot to show: RAW, camera rendering (JPEG/HEIF), or both (see `ShotPairing`). */
class ShotRequest(val raw: ImageFile? = null, val rendered: ImageFile? = null) {
    init { require(raw != null || rendered != null) { "A shot needs a file" } }

    /** The file whose identity names this shot in caches and the scheduler. */
    val primary: ImageFile get() = rendered ?: raw!!
}

/** Where a screen-sized image comes from. */
enum class ScreenSource {
    /** The RAW's embedded JPEG (a few hundred KB read; ZV-E10: 1616x1080). */
    RAW_PREVIEW,
    /** The camera JPEG/HEIF, downsampled. */
    RENDERED,
    /** LibRaw half-size decode of the RAW (only for RAW-only shots whose preview is too small). */
    RAW_HALF,
}

/** Where 100% pixels come from. */
enum class ZoomSource {
    /** Region decode of the camera JPEG. */
    RENDERED,
    /** Region decode of a full-size embedded JPEG (CR3 track 1, Nikon/Pentax JpgFromRaw…). */
    RAW_PREVIEW,
    /** LibRaw full-size rendering cached as a JPEG, then region decoded. */
    RAW_FULL,
}

/**
 * Pure decisions (JVM-tested): what to decode for the screen and for zoom, given what a shot has.
 */
object ImagePlans {

    /** A preview counts as "screen quality" when it covers this share of the target long side. */
    const val PREVIEW_SCREEN_RATIO = 0.75

    /** A preview counts as "full size" for 100% zoom when it is this close to the sensor width. */
    const val PREVIEW_FULL_RATIO = 0.9

    /**
     * Ordered steps: show the first quickly, replace it with the next when ready. The last is the
     * final quality. Empty when nothing is displayable.
     */
    fun screen(hasRendered: Boolean, previewLongSide: Int?, targetLongSide: Int, libRaw: Boolean): List<ScreenSource> {
        val preview = previewLongSide?.takeIf { it > 0 }
        val previewGood = preview != null && preview >= targetLongSide * PREVIEW_SCREEN_RATIO
        return when {
            previewGood -> listOf(ScreenSource.RAW_PREVIEW)
            hasRendered && preview != null -> listOf(ScreenSource.RAW_PREVIEW, ScreenSource.RENDERED)
            hasRendered -> listOf(ScreenSource.RENDERED)
            preview != null && libRaw -> listOf(ScreenSource.RAW_PREVIEW, ScreenSource.RAW_HALF)
            preview != null -> listOf(ScreenSource.RAW_PREVIEW)
            libRaw -> listOf(ScreenSource.RAW_HALF)
            else -> emptyList()
        }
    }

    /** Null when 100% is not possible (no rendering, small preview and no LibRaw). */
    fun zoom(hasRendered: Boolean, previewLongSide: Int?, rawLongSide: Int?, libRaw: Boolean): ZoomSource? {
        val preview = previewLongSide ?: 0
        val sensor = rawLongSide ?: 0
        return when {
            hasRendered -> ZoomSource.RENDERED
            preview > 0 && sensor > 0 && preview >= sensor * PREVIEW_FULL_RATIO -> ZoomSource.RAW_PREVIEW
            libRaw -> ZoomSource.RAW_FULL
            preview > 0 -> ZoomSource.RAW_PREVIEW
            else -> null
        }
    }

    /**
     * Prefetch order around [current] in a list of [count] shots: ahead first (people mostly move
     * forward), then behind. Returns (index, priority), priority 1 = most urgent (0 is the current
     * image itself, requested directly by the UI).
     */
    fun prefetchWindow(current: Int, count: Int, ahead: Int, behind: Int): List<Pair<Int, Int>> {
        if (count <= 0 || current !in 0 until count) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var priority = 1
        for (step in 1..maxOf(ahead, behind)) {
            if (step <= ahead && current + step < count) out += (current + step) to priority++
            if (step <= behind && current - step >= 0) out += (current - step) to priority++
        }
        return out
    }
}

/** One prefetch request of the culling view: which shot, which step, how urgent. */
data class PrefetchStep(val index: Int, val step: Step, val priority: Int) {
    enum class Step { FAST, FINAL, ZOOM }
}

/** Pure prefetch planning for the culling view (JVM-tested). */
object CullPlans {

    /**
     * What to prepare around [current]: the fast screen image of every shot in the window first
     * (so a swipe always lands on something), then the final images in the same order, then the
     * 100% sources of the next [zoomAhead] shots. Priorities start at 1 (0 is the shot on screen,
     * requested directly) and never repeat.
     */
    fun prefetch(current: Int, count: Int, ahead: Int, behind: Int, zoomAhead: Int, fastAhead: Int = ahead): List<PrefetchStep> {
        val window = ImagePlans.prefetchWindow(current, count, ahead, behind).map { it.first }
        // Fast images (a RAW's ~0.5 MB preview) reach further ahead than final ones (a whole
        // 5-8 MB camera JPEG), so paging quickly over a slow link still lands on a picture.
        val fastWindow = ImagePlans.prefetchWindow(current, count, maxOf(ahead, fastAhead), behind).map { it.first }
        var priority = 1
        val out = ArrayList<PrefetchStep>()
        fastWindow.forEach { out += PrefetchStep(it, PrefetchStep.Step.FAST, priority++) }
        window.forEach { out += PrefetchStep(it, PrefetchStep.Step.FINAL, priority++) }
        window.filter { it > current && it - current <= zoomAhead }.forEach { out += PrefetchStep(it, PrefetchStep.Step.ZOOM, priority++) }
        return out
    }

    /**
     * Whether an embedded preview can be served as the screen file as it is (no decode, no
     * re-encode): upright, and not much larger than needed (a 1616 px preview for a 3120 px screen).
     */
    fun servePreviewAsIs(orientation: Int, previewLongSide: Int, targetLongSide: Int): Boolean =
        orientation == 1 && previewLongSide <= targetLongSide * 3 / 2

    /** A tile request clipped to the image; null when it falls outside or is not a valid tile. */
    fun clipTile(x: Int, y: Int, width: Int, height: Int, sample: Int, imageWidth: Int, imageHeight: Int): IntArray? {
        if (sample !in setOf(1, 2, 4, 8, 16) || width <= 0 || height <= 0) return null
        if (width / sample > MAX_TILE_SIDE || height / sample > MAX_TILE_SIDE) return null
        val left = x.coerceAtLeast(0)
        val top = y.coerceAtLeast(0)
        val right = minOf(x + width, imageWidth)
        val bottom = minOf(y + height, imageHeight)
        if (right <= left || bottom <= top) return null
        return intArrayOf(left, top, right - left, bottom - top)
    }

    /** Longest side of one decoded tile, in output pixels. */
    const val MAX_TILE_SIDE = 2048
}
