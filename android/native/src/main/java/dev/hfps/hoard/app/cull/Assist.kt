package dev.hfps.hoard.app.cull

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Assisted culling (ADR 0009), ported from src/features/cull/assist.ts with its tests (AssistTest): flags from the
 * native measurements and the user's sensitivity, burst grouping by capture time and visual similarity, ranking
 * inside a burst, and suggestions. Suggestions never decide: the UI shows them and only the user's accept writes.
 */

/** Native `ShotAnalysis.VERSION`: cached rows with another version are measured again. */
const val ANALYSIS_VERSION = 2

enum class Sensitivity(val id: String) {
    Low("low"),
    Medium("medium"),
    High("high"),
    ;

    companion object {
        fun of(id: String?): Sensitivity? = entries.firstOrNull { it.id == id }
    }
}

data class AssistSettings(val blur: Sensitivity = Sensitivity.Medium, val eyes: Sensitivity = Sensitivity.Medium, val burstGapMs: Long = 1000) {
    companion object {
        val DEFAULT = AssistSettings()
    }
}

val BURST_GAPS_MS = listOf(500L, 1000L, 2000L, 3000L)

/** "Blurry" when the subject's Crété-Roffet blur reaches this (calibration in ADR 0009). */
val BLUR_THRESHOLDS = mapOf(Sensitivity.Low to 0.68, Sensitivity.Medium to 0.6, Sensitivity.High to 0.53)

/** "Eyes closed" when a prominent face has both eye-open probabilities below this. */
val EYES_CLOSED_THRESHOLDS = mapOf(Sensitivity.Low to 0.15, Sensitivity.Medium to 0.3, Sensitivity.High to 0.5)

/** Exposure rules (ADR 0009 §4, recalibrated in 1.7.0). */
object ExposureRules {
    const val OVER_BLOWN_AREA = 0.1
    const val OVER_FACE_HIGHLIGHTS = 0.1
    const val OVER_MEAN = 0.7
    const val UNDER_SHADOWS = 0.08
    const val UNDER_MEAN = 0.2
}

/** Burst similarity rules on the 64-bit dHash and the 4 × 4 colour signature. */
object SimilarityRules {
    const val SCENE_CHANGE_HAMMING = 21
    const val SCENE_CHANGE_COLOR = 0.04
    const val SCENE_CHANGE_COLOR_ALONE = 0.15
    const val NEAR_HAMMING = 12
    const val NEAR_COLOR = 0.06
    const val JOIN_GAP_FACTOR = 4
}

enum class ShotFlag(val id: String) { Blurry("blurry"), EyesClosed("eyesClosed"), Overexposed("overexposed"), Underexposed("underexposed") }

/** A face of the analysis: box 0..1 of the upright image; eye probabilities null when unknown. */
data class Face(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val leftEyeOpen: Double? = null,
    val rightEyeOpen: Double? = null,
    val smiling: Double? = null,
    val prominent: Boolean = true,
    val sharpness: Double = 0.0,
    val blur: Double = 0.0,
)

data class Sharpness(
    val global: Double,
    val tiles: List<Double>,
    val bestTile: Int,
    val subject: Double,
    /** `face` or `tile`. */
    val subjectKind: String,
    val subjectBlur: Double,
)

data class ExposureStats(
    val histogram: List<Double>,
    val mean: Double,
    val highlights: Double,
    val shadows: Double,
    val blownArea: Double,
    val faceHighlights: Double,
)

/** The contract `CullAnalysis` (definitions.ts): what is cached in `cull_analysis` as JSON (see [AnalysisJson]). */
data class Analysis(
    val version: Int,
    /** `rawPreview` or `rendered`. */
    val source: String,
    val width: Int,
    val height: Int,
    val millis: Long,
    val sharpness: Sharpness,
    val facesDetected: Boolean,
    val faces: List<Face>,
    val exposure: ExposureStats,
    val hash: String,
    val colors: String,
)

/** The shot's file that is analysed and keys the cache: its RAW, else its JPEG. */
fun analysedFile(shot: Shot): ShotFile = shot.raw ?: shot.rendered!!

/** 0..100 for display: 100 × (1 − subject blur). */
fun sharpnessScore(analysis: Analysis): Int = (100 * (1 - analysis.sharpness.subjectBlur)).roundToInt()

enum class EyeState { Open, Closed, Unknown }

data class EyesSummary(
    val prominent: List<Face>,
    val states: List<EyeState>,
    val closed: Int,
    val open: Int,
    val verdict: EyeState,
    /** The face to show first ("Zoom to face"): the first with closed eyes, else the largest. */
    val focus: Face?,
)

/** One reading of the eyes for the flag, the chips, the details and the ranking (both eyes below the threshold). */
fun eyesSummary(analysis: Analysis, sensitivity: Sensitivity): EyesSummary {
    val limit = EYES_CLOSED_THRESHOLDS.getValue(sensitivity)
    val prominent = analysis.faces.filter { it.prominent }
    val states = prominent.map { f ->
        val l = f.leftEyeOpen
        val r = f.rightEyeOpen
        when {
            l == null || r == null -> EyeState.Unknown
            l < limit && r < limit -> EyeState.Closed
            else -> EyeState.Open
        }
    }
    val closed = states.count { it == EyeState.Closed }
    val open = states.count { it == EyeState.Open }
    val firstClosed = states.indexOf(EyeState.Closed)
    return EyesSummary(
        prominent,
        states,
        closed,
        open,
        if (closed > 0) EyeState.Closed else if (open > 0) EyeState.Open else EyeState.Unknown,
        prominent.getOrNull(if (firstClosed >= 0) firstClosed else 0),
    )
}

fun eyesClosed(analysis: Analysis, sensitivity: Sensitivity) = eyesSummary(analysis, sensitivity).verdict == EyeState.Closed

fun eyesOpen(analysis: Analysis, sensitivity: Sensitivity) = eyesSummary(analysis, sensitivity).verdict == EyeState.Open

enum class ExposureVerdict { Over, Under, Ok }

enum class ExposureReason { BlownArea, Face, Mean, Shadows }

data class ExposureOutcome(val verdict: ExposureVerdict, val reason: ExposureReason? = null)

fun exposureOf(e: ExposureStats): ExposureOutcome = when {
    e.blownArea >= ExposureRules.OVER_BLOWN_AREA -> ExposureOutcome(ExposureVerdict.Over, ExposureReason.BlownArea)
    e.faceHighlights >= ExposureRules.OVER_FACE_HIGHLIGHTS -> ExposureOutcome(ExposureVerdict.Over, ExposureReason.Face)
    e.mean >= ExposureRules.OVER_MEAN -> ExposureOutcome(ExposureVerdict.Over, ExposureReason.Mean)
    e.shadows >= ExposureRules.UNDER_SHADOWS -> ExposureOutcome(ExposureVerdict.Under, ExposureReason.Shadows)
    e.mean <= ExposureRules.UNDER_MEAN -> ExposureOutcome(ExposureVerdict.Under, ExposureReason.Mean)
    else -> ExposureOutcome(ExposureVerdict.Ok)
}

fun flagsOf(analysis: Analysis, settings: AssistSettings): List<ShotFlag> {
    val flags = ArrayList<ShotFlag>()
    if (analysis.sharpness.subjectBlur >= BLUR_THRESHOLDS.getValue(settings.blur)) flags += ShotFlag.Blurry
    if (eyesClosed(analysis, settings.eyes)) flags += ShotFlag.EyesClosed
    when (exposureOf(analysis.exposure).verdict) {
        ExposureVerdict.Over -> flags += ShotFlag.Overexposed
        ExposureVerdict.Under -> flags += ShotFlag.Underexposed
        ExposureVerdict.Ok -> Unit
    }
    return flags
}

// --- Similarity and bursts ------------------------------------------------------------------------------------------

/** Differing bits of two hex hashes (64 when malformed). */
fun hammingDistance(a: String, b: String): Int {
    if (a.length != b.length || a.isEmpty()) return 64
    var bits = 0
    for (i in a.indices) {
        val x = Character.digit(a[i], 16)
        val y = Character.digit(b[i], 16)
        if (x < 0 || y < 0) return 64
        bits += Integer.bitCount(x xor y)
    }
    return bits
}

/** Mean absolute difference of two colour signatures (hex bytes), 0..1 (1 when malformed). */
fun colorDistance(a: String, b: String): Double {
    if (a.length != b.length || a.isEmpty() || a.length % 2 != 0) return 1.0
    var sum = 0.0
    var i = 0
    while (i < a.length) {
        val x = a.substring(i, i + 2).toIntOrNull(16) ?: return 1.0
        val y = b.substring(i, i + 2).toIntOrNull(16) ?: return 1.0
        sum += abs(x - y)
        i += 2
    }
    return sum / (a.length / 2) / 255
}

data class Similarity(val hamming: Int, val color: Double)

fun similarityOf(a: Analysis, b: Analysis) = Similarity(hammingDistance(a.hash, b.hash), colorDistance(a.colors, b.colors))

fun isSceneChange(s: Similarity) =
    (s.hamming >= SimilarityRules.SCENE_CHANGE_HAMMING && s.color >= SimilarityRules.SCENE_CHANGE_COLOR) ||
        s.color >= SimilarityRules.SCENE_CHANGE_COLOR_ALONE

fun isNearIdentical(s: Similarity) = s.hamming <= SimilarityRules.NEAR_HAMMING && s.color <= SimilarityRules.NEAR_COLOR

/**
 * Bursts in capture order: a shot joins the previous one when taken at most [gapMs] later and not a scene change
 * (unknown similarity never splits), or at most `JOIN_GAP_FACTOR × gapMs` later and near-identical. Runs of 2+.
 */
fun groupBursts(ordered: List<Shot>, metadata: Map<String, ShotMetadata?>, analyses: Map<String, Analysis>, gapMs: Long): Map<String, BurstPosition> {
    val result = LinkedHashMap<String, BurstPosition>()
    var run = ArrayList<String>()
    fun flush() {
        if (run.size >= 2) run.forEachIndexed { index, id -> result[id] = BurstPosition(run[0], index, run.size) }
        run = ArrayList()
    }
    var previous: Shot? = null
    for (shot in ordered) {
        val time = metadata[shot.id]?.captureMillis
        val before = previous?.let { metadata[it.id]?.captureMillis }
        var join = false
        if (previous != null && time != null && before != null && time >= before) {
            val gap = time - before
            val a = analyses[previous.id]
            val b = analyses[shot.id]
            val similarity = if (a != null && b != null) similarityOf(a, b) else null
            if (gap <= gapMs) {
                join = similarity == null || !isSceneChange(similarity)
            } else if (gap <= gapMs * SimilarityRules.JOIN_GAP_FACTOR) {
                join = similarity != null && isNearIdentical(similarity)
            }
        }
        if (!join) flush()
        run += shot.id
        previous = shot
    }
    flush()
    return result
}

/** Burst id → its shot ids in order. */
fun burstMembers(ordered: List<Shot>, bursts: Map<String, BurstPosition>): Map<String, List<String>> {
    val members = LinkedHashMap<String, MutableList<String>>()
    for (shot in ordered) {
        val position = bursts[shot.id] ?: continue
        members.getOrPut(position.burst) { ArrayList() } += shot.id
    }
    return members
}

// --- Ranking and suggestions ----------------------------------------------------------------------------------------

val RANK_PENALTIES = mapOf(ShotFlag.EyesClosed to 1.0, ShotFlag.Blurry to 0.6, ShotFlag.Overexposed to 0.4, ShotFlag.Underexposed to 0.4)

/** A frame sharper than this share of the best one still counts as "as sharp". */
const val BLURRIER_RATIO = 0.8

data class Ranked(val shotId: String, val relativeSharpness: Double, val score: Double, val flags: List<ShotFlag>)

/** Frames of one burst, best first (relative sharpness minus a penalty per flag; ties in capture order). */
fun rankBurst(shotIds: List<String>, analyses: Map<String, Analysis>, settings: AssistSettings): List<Ranked> {
    val known = shotIds.filter { it in analyses }
    val max = known.maxOfOrNull { analyses.getValue(it).sharpness.subject }?.coerceAtLeast(0.0) ?: 0.0
    return known.mapIndexed { order, id ->
        val a = analyses.getValue(id)
        val flags = flagsOf(a, settings)
        val relative = if (max > 0) a.sharpness.subject / max else 1.0
        order to Ranked(id, relative, relative - flags.sumOf { RANK_PENALTIES.getValue(it) }, flags)
    }.sortedWith(compareByDescending<Pair<Int, Ranked>> { it.second.score }.thenBy { it.first }).map { it.second }
}

sealed interface SuggestionReason {
    data object Sharpest : SuggestionReason
    data object EyesOpen : SuggestionReason
    data class FlagReason(val flag: ShotFlag) : SuggestionReason
    data class BlurrierThan(val than: String) : SuggestionReason
    data class NotBest(val than: String) : SuggestionReason
    data object AllFlagged : SuggestionReason
}

enum class SuggestionAction { Keep, Reject }

data class Suggestion(val shotId: String, val action: SuggestionAction, val reasons: List<SuggestionReason>, val burst: String? = null)

/**
 * Suggestions for [ordered]: in every fully analysed burst keep the best frame (none when every frame is flagged) and
 * reject the others with why; outside bursts reject every flagged shot. Decided shots are left out.
 */
fun suggest(
    ordered: List<Shot>,
    bursts: Map<String, BurstPosition>,
    analyses: Map<String, Analysis>,
    decisions: Decisions,
    settings: AssistSettings,
): Map<String, Suggestion> {
    val out = LinkedHashMap<String, Suggestion>()
    val stems = ordered.associate { it.id to it.stem }
    fun undecided(id: String) = decisions[id]?.flag == null
    for ((burst, ids) in burstMembers(ordered, bursts)) {
        if (!ids.all { it in analyses }) continue
        val ranked = rankBurst(ids, analyses, settings)
        val best = ranked.first()
        val allFlagged = ranked.all { it.flags.isNotEmpty() }
        val bestStem = stems[best.shotId] ?: best.shotId
        ranked.forEachIndexed { i, r ->
            if (!undecided(r.shotId)) return@forEachIndexed
            if (i == 0 && !allFlagged) {
                val reasons = mutableListOf<SuggestionReason>(SuggestionReason.Sharpest)
                if (eyesOpen(analyses.getValue(r.shotId), settings.eyes)) reasons += SuggestionReason.EyesOpen
                out[r.shotId] = Suggestion(r.shotId, SuggestionAction.Keep, reasons, burst)
                return@forEachIndexed
            }
            val reasons = r.flags.map<ShotFlag, SuggestionReason> { SuggestionReason.FlagReason(it) }.toMutableList()
            if (allFlagged && i == 0) {
                reasons += SuggestionReason.AllFlagged
            } else if (r.relativeSharpness < BLURRIER_RATIO * best.relativeSharpness) {
                reasons += SuggestionReason.BlurrierThan(bestStem)
            } else if (reasons.isEmpty()) {
                reasons += SuggestionReason.NotBest(bestStem)
            }
            out[r.shotId] = Suggestion(r.shotId, SuggestionAction.Reject, reasons, burst)
        }
    }
    for (shot in ordered) {
        if (shot.id in bursts || !undecided(shot.id)) continue
        val a = analyses[shot.id] ?: continue
        val flags = flagsOf(a, settings)
        if (flags.isNotEmpty()) out[shot.id] = Suggestion(shot.id, SuggestionAction.Reject, flags.map { SuggestionReason.FlagReason(it) })
    }
    return out
}

/** The crowned frame of each burst: the top-ranked frame when it has no flag. */
fun bestInBursts(ordered: List<Shot>, bursts: Map<String, BurstPosition>, analyses: Map<String, Analysis>, settings: AssistSettings): Map<String, String> {
    val best = LinkedHashMap<String, String>()
    for ((burst, ids) in burstMembers(ordered, bursts)) {
        val top = rankBurst(ids, analyses, settings).firstOrNull()
        if (top != null && top.flags.isEmpty()) best[burst] = top.shotId
    }
    return best
}

/** The two frames compare opens with: the burst's top two, else the shot and its neighbour. */
fun comparePair(
    shotId: String,
    order: List<String>,
    bursts: Map<String, BurstPosition>,
    members: Map<String, List<String>>,
    analyses: Map<String, Analysis>,
    settings: AssistSettings,
): Pair<String, String>? {
    val ids = bursts[shotId]?.burst?.let { members[it] }
    if (ids != null && ids.size >= 2) {
        val ranked = rankBurst(ids, analyses, settings).map { it.shotId }
        val all = ranked + ids.filter { it !in ranked }
        return all[0] to all[1]
    }
    val at = order.indexOf(shotId)
    val other = order.getOrNull(at + 1) ?: order.getOrNull(at - 1)
    return other?.let { shotId to it }
}

// --- Order and filters ----------------------------------------------------------------------------------------------

enum class CullSort(val id: String) { Time("time"), Sharpness("sharpness") }

/** Sharpest first (lowest subject blur); shots not analysed yet keep their order at the end. */
fun sortBySharpness(shots: List<Shot>, analyses: Map<String, Analysis>): List<Shot> {
    val index = shots.withIndex().associate { it.value.id to it.index }
    return shots.sortedWith { a, b ->
        val x = analyses[a.id]?.sharpness?.subjectBlur
        val y = analyses[b.id]?.sharpness?.subjectBlur
        when {
            x != null && y != null && x != y -> x.compareTo(y)
            x == null && y != null -> 1
            x != null && y == null -> -1
            else -> index.getValue(a.id).compareTo(index.getValue(b.id))
        }
    }
}

/** `any`, one flag, `flagged` or `clean`. */
sealed interface AssistFilter {
    val id: String

    data object Any : AssistFilter { override val id = "any" }
    data object Flagged : AssistFilter { override val id = "flagged" }
    data object Clean : AssistFilter { override val id = "clean" }
    data class Only(val flag: ShotFlag) : AssistFilter { override val id get() = flag.id }

    companion object {
        val ALL: List<AssistFilter> = listOf(Any, Flagged) + ShotFlag.entries.map { Only(it) } + Clean
    }
}

fun matchesAssist(flags: List<ShotFlag>?, filter: AssistFilter): Boolean = when (filter) {
    AssistFilter.Any -> true
    AssistFilter.Flagged -> flags != null && flags.isNotEmpty()
    AssistFilter.Clean -> flags != null && flags.isEmpty()
    is AssistFilter.Only -> flags != null && filter.flag in flags
}

// --- Grid with stacks -----------------------------------------------------------------------------------------------

sealed interface GridItem {
    val shotId: String
    val index: Int

    data class Single(override val shotId: String, override val index: Int) : GridItem
    data class Stack(val burst: String, override val shotId: String, override val index: Int, val size: Int) : GridItem
}

/**
 * Grid cells for [shots] (filtered, sorted): with [stack], the frames of a burst collapse into one cell showing its
 * cover ([covers]) or its first frame, unless the burst is in [expanded].
 */
fun gridItems(shots: List<Shot>, bursts: Map<String, BurstPosition>, covers: Map<String, String>, expanded: Set<String>, stack: Boolean): List<GridItem> {
    if (!stack) return shots.mapIndexed { i, s -> GridItem.Single(s.id, i) }
    val count = HashMap<String, Int>()
    for (s in shots) bursts[s.id]?.burst?.let { count[it] = (count[it] ?: 0) + 1 }
    val items = ArrayList<GridItem>()
    val seen = HashSet<String>()
    val positions = shots.withIndex().associate { it.value.id to it.index }
    shots.forEachIndexed { index, s ->
        val b = bursts[s.id]?.burst
        if (b == null || (count[b] ?: 0) < 2 || b in expanded) {
            items += GridItem.Single(s.id, index)
            return@forEachIndexed
        }
        if (!seen.add(b)) return@forEachIndexed
        val at = covers[b]?.let { positions[it] } ?: index
        items += GridItem.Stack(b, shots[at].id, at, count.getValue(b))
    }
    return items
}
