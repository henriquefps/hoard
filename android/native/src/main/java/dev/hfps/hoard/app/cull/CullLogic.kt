package dev.hfps.hoard.app.cull

import dev.hfps.hoard.app.files.NaturalComparator
import dev.hfps.hoard.image.CullFileRef
import dev.hfps.hoard.image.CullShotRef
import java.text.Collator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * Pure culling logic (ADR 0008), ported from src/features/cull/model.ts with its tests (CullLogicTest): ordering,
 * filters and progress, decisions with undo, what "Copy picks" and "Delete rejects" touch, the prefetch window and the
 * 100 % tile grid. No Android, no I/O.
 */

typealias Shot = CullShotRef
typealias ShotFile = CullFileRef

/** `pair`, `raw` or `jpeg` (CullShotRef.kind). */
val Shot.files: List<ShotFile> get() = listOfNotNull(raw, rendered) + others

// --- Decisions ------------------------------------------------------------------------------------------------------

enum class Flag(val wire: String) {
    Pick("pick"),
    Reject("reject"),
    ;

    companion object {
        fun of(value: String?): Flag? = entries.firstOrNull { it.wire == value }
    }
}

data class Decision(val flag: Flag? = null, val rating: Int = 0) {
    val unmarked: Boolean get() = flag == null && rating == 0

    companion object {
        val UNMARKED = Decision()
    }
}

/** Shot id → decision (unmarked shots are absent). */
typealias Decisions = Map<String, Decision>

fun Decisions.of(shotId: String): Decision = this[shotId] ?: Decision.UNMARKED

/** One undoable step: what some shots were and became (a pick, a rating, a bulk change). */
data class DecisionChange(val shotIds: List<String>, val before: List<Decision>, val after: List<Decision>)

/** Sets the flag (null = unmark); stars are kept. */
fun Decision.withFlag(flag: Flag?): Decision = Decision(flag, rating)

/** Sets the stars (0 clears). Rating a rejected shot makes it a pick: stars mean "keep, this much". */
fun Decision.withRating(rating: Int): Decision {
    val stars = rating.coerceIn(0, 5)
    return Decision(if (stars > 0 && flag == Flag.Reject) Flag.Pick else flag, stars)
}

/** A change that sets `next(decision)` on each shot, or null when nothing would change. */
fun changeOf(decisions: Decisions, shotIds: List<String>, next: (Decision, String) -> Decision): DecisionChange? {
    val ids = ArrayList<String>()
    val before = ArrayList<Decision>()
    val after = ArrayList<Decision>()
    for (id in shotIds) {
        val current = decisions.of(id)
        val updated = next(current, id)
        if (current == updated) continue
        ids += id
        before += current
        after += updated
    }
    return if (ids.isEmpty()) null else DecisionChange(ids, before, after)
}

/** Decisions after applying (or, with [undo], reverting) a change. */
fun applyChange(decisions: Decisions, change: DecisionChange, undo: Boolean = false): Map<String, Decision> {
    val next = LinkedHashMap(decisions)
    change.shotIds.forEachIndexed { i, id ->
        val value = if (undo) change.before[i] else change.after[i]
        if (value.unmarked) next.remove(id) else next[id] = value
    }
    return next
}

const val UNDO_LIMIT = 200

fun pushUndo(stack: List<DecisionChange>, change: DecisionChange): List<DecisionChange> {
    val next = stack + change
    return if (next.size > UNDO_LIMIT) next.takeLast(UNDO_LIMIT) else next
}

// --- Order and bursts -----------------------------------------------------------------------------------------------

private val natural = NaturalComparator(Collator.getInstance(Locale.ROOT))

/** Capture-time order (camera clock, sub-seconds); name order for ties and for shots without a time (last). */
fun sortByCapture(shots: List<Shot>, metadata: Map<String, ShotMetadata?>): List<Shot> = shots.sortedWith { a, b ->
    val ta = metadata[a.id]?.captureMillis
    val tb = metadata[b.id]?.captureMillis
    when {
        ta != null && tb != null && ta != tb -> ta.compareTo(tb)
        ta == null && tb != null -> 1
        ta != null && tb == null -> -1
        else -> natural.compare(a.stem, b.stem)
    }
}

/** A shot's place in its burst: the burst's first shot id, the 0-based index, the size. */
data class BurstPosition(val burst: String, val index: Int, val size: Int)

// --- Filters and progress -------------------------------------------------------------------------------------------

enum class FlagFilter(val id: String) { All("all"), Picked("picked"), Rejected("rejected"), Unmarked("unmarked") }

data class CullFilter(val flag: FlagFilter = FlagFilter.All, val minStars: Int = 0) {
    val isAll: Boolean get() = flag == FlagFilter.All && minStars == 0
}

fun matchesFilter(decision: Decision, filter: CullFilter): Boolean {
    if (filter.minStars > 0 && decision.rating < filter.minStars) return false
    return when (filter.flag) {
        FlagFilter.Picked -> decision.flag == Flag.Pick
        FlagFilter.Rejected -> decision.flag == Flag.Reject
        FlagFilter.Unmarked -> decision.flag == null
        FlagFilter.All -> true
    }
}

fun filterShots(shots: List<Shot>, decisions: Decisions, filter: CullFilter): List<Shot> =
    if (filter.isAll) shots else shots.filter { matchesFilter(decisions.of(it.id), filter) }

data class CullProgress(val total: Int, val decided: Int, val picked: Int, val rejected: Int, val unmarked: Int, val rated: Int)

fun progressOf(shots: List<Shot>, decisions: Decisions): CullProgress {
    var picked = 0
    var rejected = 0
    var rated = 0
    for (shot in shots) {
        val d = decisions[shot.id] ?: continue
        if (d.flag == Flag.Pick) picked++ else if (d.flag == Flag.Reject) rejected++
        if (d.rating > 0) rated++
    }
    return CullProgress(shots.size, picked + rejected, picked, rejected, shots.size - picked - rejected, rated)
}

/** Index of the next shot without a flag after [from] (wrapping), or -1 when all are decided. */
fun nextUnmarked(order: List<Shot>, decisions: Decisions, from: Int): Int {
    for (step in 1..order.size) {
        val index = Math.floorMod(from + step, order.size)
        if (decisions[order[index].id]?.flag == null) return index
    }
    return -1
}

// --- Copy picks and delete rejects ----------------------------------------------------------------------------------

enum class CopyFiles(val id: String) { Both("both"), Raw("raw"), Jpeg("jpeg") }

enum class CopyFolder(val id: String) { Keep("keep"), New("new"), None("none") }

data class CopyPicksOptions(val files: CopyFiles = CopyFiles.Both, val folder: CopyFolder = CopyFolder.Keep, val subfolder: String? = null)

data class CopyPlanItem(val shotId: String, val file: ShotFile, val fallback: Boolean)

data class CopyPlan(val items: List<CopyPlanItem>, val shots: Int, val bytes: Long, val fallbacks: Int)

/**
 * The files to copy for the picked shots, in [order]: every file of the shot for [CopyFiles.Both]; that kind, or the
 * shot's only file when it lacks it (counted in `fallbacks`).
 */
fun planCopy(order: List<Shot>, decisions: Decisions, files: CopyFiles): CopyPlan {
    val items = ArrayList<CopyPlanItem>()
    var shots = 0
    var fallbacks = 0
    for (shot in order) {
        if (decisions[shot.id]?.flag != Flag.Pick) continue
        shots++
        val all = shot.files
        var fallback = false
        val chosen = if (files == CopyFiles.Both) {
            all
        } else {
            val wanted = if (files == CopyFiles.Raw) shot.raw else shot.rendered
            if (wanted != null) listOf(wanted) else all.also { fallback = true }
        }
        if (fallback) fallbacks++
        chosen.forEach { items += CopyPlanItem(shot.id, it, fallback) }
    }
    return CopyPlan(items, shots, items.sumOf { it.file.size }, fallbacks)
}

/** The destination folder of a copy: `base`, `base/<source folder>` or `base/<subfolder>`. */
fun copyTargetPath(base: String, sourcePath: String, options: CopyPicksOptions): String {
    if (options.folder == CopyFolder.None) return base
    val name = if (options.folder == CopyFolder.Keep) {
        sourcePath.split('/').lastOrNull { it.isNotEmpty() }.orEmpty()
    } else {
        options.subfolder.orEmpty().trim()
    }
    return when {
        name.isEmpty() -> base
        base.isEmpty() -> name
        else -> "$base/$name"
    }
}

data class DeletePlan(val files: List<ShotFile>, val shots: List<String>, val bytes: Long)

fun planDeleteRejects(order: List<Shot>, decisions: Decisions): DeletePlan {
    val files = ArrayList<ShotFile>()
    val shots = ArrayList<String>()
    for (shot in order) {
        if (decisions[shot.id]?.flag != Flag.Reject) continue
        shots += shot.id
        files += shot.files
    }
    return DeletePlan(files, shots, files.sumOf { it.size })
}

/**
 * "Move rejects to trash" (ADR 0023): the rejected shots and the paths of their files. The trash adds what the folder's
 * listing holds next to them (sidecars, a Live Photo's video) and computes each file's trash path.
 */
data class TrashRejectsPlan(val shots: List<String>, val paths: List<String>, val bytes: Long)

fun planTrashRejects(order: List<Shot>, decisions: Decisions): TrashRejectsPlan {
    val plan = planDeleteRejects(order, decisions)
    return TrashRejectsPlan(plan.shots, plan.files.map { it.path }, plan.bytes)
}

// --- Prefetch -------------------------------------------------------------------------------------------------------

data class PrefetchSlice(val shotIds: List<String>, val current: Int)

/** The ids sent to the image core's prefetch for the shot at [index]: a slice of the order around it. */
fun prefetchSlice(order: List<String>, index: Int, ahead: Int, behind: Int): PrefetchSlice {
    if (order.isEmpty() || index < 0 || index >= order.size) return PrefetchSlice(emptyList(), 0)
    val start = max(0, index - behind)
    val end = min(order.size, index + ahead + 1)
    return PrefetchSlice(order.subList(start, end).toList(), index - start)
}

// --- 100 % zoom tiles -----------------------------------------------------------------------------------------------

/** Output size of a tile in pixels (a tile at sample s covers TILE_SIZE·s image pixels). */
const val TILE_SIZE = 512

/** Largest sample (power of two, ≤ 16) that still gives at least one image pixel per device pixel. */
fun sampleForScale(devicePxPerImagePx: Double): Int {
    if (!(devicePxPerImagePx > 0)) return 16
    var sample = 1
    while (sample < 16 && 1.0 / (sample * 2) >= devicePxPerImagePx) sample *= 2
    return sample
}

data class Tile(val key: String, val x: Int, val y: Int, val width: Int, val height: Int, val sample: Int)

data class ImageRect(val x: Double, val y: Double, val width: Double, val height: Double)

/** Tiles covering [visible] (image pixels) at [sample], grid-aligned, ordered from the centre outward. */
fun tilesFor(visible: ImageRect, imageWidth: Int, imageHeight: Int, sample: Int): List<Tile> {
    val step = TILE_SIZE * sample
    val left = max(0, floor(visible.x / step).toInt())
    val top = max(0, floor(visible.y / step).toInt())
    val right = min(ceil(imageWidth.toDouble() / step).toInt(), ceil((visible.x + visible.width) / step).toInt())
    val bottom = min(ceil(imageHeight.toDouble() / step).toInt(), ceil((visible.y + visible.height) / step).toInt())
    val cx = visible.x + visible.width / 2
    val cy = visible.y + visible.height / 2
    val tiles = ArrayList<Pair<Tile, Double>>()
    for (row in top until bottom) {
        for (col in left until right) {
            val x = col * step
            val y = row * step
            val width = min(step, imageWidth - x)
            val height = min(step, imageHeight - y)
            tiles += Tile("$sample:$x:$y", x, y, width, height, sample) to hypot(x + width / 2.0 - cx, y + height / 2.0 - cy)
        }
    }
    return tiles.sortedBy { it.second }.map { it.first }
}

// --- Paging (kept for parity: the Compose pager does its own, the keyboard and the filmstrip use goTo) ---------------

object Paging {
    const val DISTANCE = 0.22
    const val FLING = 0.45
    const val MIN_FLING = 16.0
}

/** Where a horizontal swipe released at [dx] with velocity [vx] lands, from [index] of [count] (see model.ts). */
fun pageTarget(dx: Double, vx: Double, width: Double, index: Int, count: Int): Int {
    val direction = sign(dx)
    if (direction == 0.0 || width <= 0) return index
    val far = abs(dx) >= width * Paging.DISTANCE
    val flung = abs(dx) >= Paging.MIN_FLING && sign(vx) == direction && abs(vx) >= Paging.FLING
    if (!far && !flung) return index
    val next = index - direction.roundToInt()
    return if (next < 0 || next >= count) index else next
}
