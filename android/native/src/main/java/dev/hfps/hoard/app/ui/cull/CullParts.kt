package dev.hfps.hoard.app.ui.cull

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.cull.AssistFilter
import dev.hfps.hoard.app.cull.BurstPosition
import dev.hfps.hoard.app.cull.CullImages
import dev.hfps.hoard.app.cull.Decision
import dev.hfps.hoard.app.cull.ExposureReason
import dev.hfps.hoard.app.cull.EyeState
import dev.hfps.hoard.app.cull.Flag
import dev.hfps.hoard.app.cull.FlagFilter
import dev.hfps.hoard.app.cull.Sensitivity
import dev.hfps.hoard.app.cull.Shot
import dev.hfps.hoard.app.cull.ShotFlag
import dev.hfps.hoard.app.cull.Suggestion
import dev.hfps.hoard.app.cull.SuggestionAction
import dev.hfps.hoard.app.cull.SuggestionReason
import dev.hfps.hoard.designsystem.icons.PhosphorFill
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/** The viewer's own colours: always dark, whatever the app theme (Hoard 1's `bg-viewer`). */
object ViewerColors {
    val background = Color(0xFF0B0C0F)
    val foreground = Color(0xFFF4F4F5)
    val scrim = Color(0xB30B0C0F)
}

// --- Labels -----------------------------------------------------------------------------------------------------

fun kindText(kind: String): Int = when (kind) {
    "raw" -> R.string.t_cull_kind_raw
    "jpeg" -> R.string.t_cull_kind_jpeg
    else -> R.string.t_cull_kind_pair
}

fun flagText(flag: ShotFlag): Int = when (flag) {
    ShotFlag.Blurry -> R.string.t_cull_assist_flag_blurry
    ShotFlag.EyesClosed -> R.string.t_cull_assist_flag_eyes_closed
    ShotFlag.Overexposed -> R.string.t_cull_assist_flag_overexposed
    ShotFlag.Underexposed -> R.string.t_cull_assist_flag_underexposed
}

fun flagIcon(flag: ShotFlag): ImageVector = when (flag) {
    ShotFlag.Blurry -> PhosphorRegular.Scan
    ShotFlag.EyesClosed -> PhosphorRegular.EyeClosed
    ShotFlag.Overexposed -> PhosphorRegular.Sun
    ShotFlag.Underexposed -> PhosphorRegular.Moon
}

fun flagFilterText(filter: FlagFilter): Int = when (filter) {
    FlagFilter.All -> R.string.t_cull_filter_all
    FlagFilter.Picked -> R.string.t_cull_filter_picked
    FlagFilter.Rejected -> R.string.t_cull_filter_rejected
    FlagFilter.Unmarked -> R.string.t_cull_filter_unmarked
}

fun assistFilterText(filter: AssistFilter): Int = when (filter) {
    AssistFilter.Any -> R.string.t_cull_assist_filter_any
    AssistFilter.Flagged -> R.string.t_cull_assist_filter_flagged
    AssistFilter.Clean -> R.string.t_cull_assist_filter_clean
    is AssistFilter.Only -> when (filter.flag) {
        ShotFlag.Blurry -> R.string.t_cull_assist_filter_blurry
        ShotFlag.EyesClosed -> R.string.t_cull_assist_filter_eyes_closed
        ShotFlag.Overexposed -> R.string.t_cull_assist_filter_overexposed
        ShotFlag.Underexposed -> R.string.t_cull_assist_filter_underexposed
    }
}

fun sensitivityText(s: Sensitivity): Int = when (s) {
    Sensitivity.Low -> R.string.t_cull_assist_sensitivity_low
    Sensitivity.Medium -> R.string.t_cull_assist_sensitivity_medium
    Sensitivity.High -> R.string.t_cull_assist_sensitivity_high
}

fun eyeText(state: EyeState): Int = when (state) {
    EyeState.Open -> R.string.t_cull_assist_eyes_open
    EyeState.Closed -> R.string.t_cull_assist_eyes_closed
    EyeState.Unknown -> R.string.t_cull_assist_eyes_unknown
}

fun exposureText(verdictOver: Boolean, reason: ExposureReason): Int = if (verdictOver) {
    when (reason) {
        ExposureReason.BlownArea -> R.string.t_cull_details_over_blown_area
        ExposureReason.Face -> R.string.t_cull_details_over_face
        else -> R.string.t_cull_details_over_mean
    }
} else {
    if (reason == ExposureReason.Shadows) R.string.t_cull_details_under_shadows else R.string.t_cull_details_under_mean
}

fun screenSourceText(source: String): Int = when (source) {
    "rawPreview" -> R.string.t_cull_source_screen_raw_preview
    "rawHalf" -> R.string.t_cull_source_screen_raw_half
    else -> R.string.t_cull_source_screen_rendered
}

fun zoomSourceText(source: String): Int = when (source) {
    "rawFull" -> R.string.t_cull_source_zoom_raw_full
    "rawPreview" -> R.string.t_cull_source_zoom_raw_preview
    else -> R.string.t_cull_source_zoom_rendered
}

/** Why a suggestion, in words ("Blurrier than DSC07705 · Eyes closed"). */
@Composable
fun reasonText(reasons: List<SuggestionReason>): String {
    val res = androidx.compose.ui.platform.LocalResources.current
    return reasons.joinToString(" · ") { r ->
        when (r) {
            is SuggestionReason.FlagReason -> res.getString(flagText(r.flag))
            is SuggestionReason.BlurrierThan -> res.getString(R.string.t_cull_assist_reason_blurrier_than, r.than)
            is SuggestionReason.NotBest -> res.getString(R.string.t_cull_assist_reason_not_best, r.than)
            SuggestionReason.Sharpest -> res.getString(R.string.t_cull_assist_reason_sharpest)
            SuggestionReason.EyesOpen -> res.getString(R.string.t_cull_assist_reason_eyes_open)
            SuggestionReason.AllFlagged -> res.getString(R.string.t_cull_assist_reason_all_flagged)
        }
    }
}

// --- Thumbnails and marks ---------------------------------------------------------------------------------------

/** A shot's thumbnail, loaded while shown; a muted tile while loading or when it cannot be made. */
@Composable
fun ShotThumb(images: CullImages, shot: Shot, modifier: Modifier = Modifier, alpha: Float = 1f) {
    var bitmap by remember(shot) { mutableStateOf<ImageBitmap?>(images.peekThumbnail(shot)) }
    LaunchedEffect(shot) {
        if (bitmap == null) bitmap = runCatching { images.thumbnail(shot) }.getOrNull()
    }
    Box(modifier.background(HfpsTheme.colors.muted)) {
        bitmap?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, alpha = alpha) }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, background: Color = ViewerColors.scrim, foreground: Color = ViewerColors.foreground) {
    Row(
        modifier.height(20.dp).clip(RoundedCornerShape(6.dp)).background(background).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(12.dp), tint = foreground)
        if (text.isNotEmpty()) Text(text, style = HfpsTheme.typography.monoSmall, color = foreground, maxLines = 1)
    }
}

/** RAW+JPG / RAW / JPG. */
@Composable
fun KindBadge(shot: Shot, modifier: Modifier = Modifier) = Badge(stringResource(kindText(shot.kind)), modifier)

/** Read-only stars (grid cells). */
@Composable
fun StarsMark(rating: Int, modifier: Modifier = Modifier) {
    if (rating <= 0) return
    val label = pluralStringResource(R.plurals.t_cull_stars, rating, rating.toString())
    Badge(rating.toString(), modifier.semantics { contentDescription = label }, icon = PhosphorFill.Star)
}

/** "2/5" on a frame of a burst. */
@Composable
fun BurstMark(position: BurstPosition, modifier: Modifier = Modifier, background: Color = ViewerColors.scrim, foreground: Color = ViewerColors.foreground) {
    val label = stringResource(R.string.t_cull_burst, (position.index + 1).toString(), position.size.toString())
    Badge("${position.index + 1}/${position.size}", modifier.semantics { contentDescription = label }, PhosphorFill.Stack, background, foreground)
}

@Composable
fun RoundMark(icon: ImageVector, label: String, background: Color, foreground: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(background).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(size * 0.58f), tint = foreground) }
}

/** The pick (check, primary) or reject (cross, destructive) mark of a shot. */
@Composable
fun FlagMark(decision: Decision, modifier: Modifier = Modifier) {
    val flag = decision.flag ?: return
    if (flag == Flag.Pick) {
        RoundMark(PhosphorRegular.Check, stringResource(R.string.t_cull_flag_picked), HfpsTheme.colors.primary, HfpsTheme.colors.primaryForeground, modifier)
    } else {
        RoundMark(PhosphorRegular.X, stringResource(R.string.t_cull_flag_rejected), HfpsTheme.colors.destructive, ViewerColors.foreground, modifier)
    }
}

/** A shot's problems and the best-in-burst crown, for grid cells. */
@Composable
fun FlagMarks(flags: List<ShotFlag>?, best: Boolean, modifier: Modifier = Modifier) {
    if (!best && flags.isNullOrEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
        if (best) RoundMark(PhosphorFill.CrownSimple, stringResource(R.string.t_cull_assist_best), HfpsTheme.colors.primary, HfpsTheme.colors.primaryForeground)
        flags?.forEach { RoundMark(flagIcon(it), stringResource(flagText(it)), HfpsTheme.colors.warning, ViewerColors.background) }
    }
}

/** The dashed "suggested" label on a grid cell (never looks like a decision). */
@Composable
fun SuggestionMark(suggestion: Suggestion, modifier: Modifier = Modifier) {
    val keep = suggestion.action == SuggestionAction.Keep
    val color = if (keep) HfpsTheme.colors.primary else HfpsTheme.colors.destructive
    val reasons = reasonText(suggestion.reasons)
    val label = stringResource(if (keep) R.string.t_cull_assist_suggest_keep else R.string.t_cull_assist_suggest_reject) + ": " + reasons
    Row(
        modifier
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ViewerColors.scrim)
            .dashedBorder(color, 12.dp)
            .padding(horizontal = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(PhosphorFill.Sparkle, null, Modifier.size(12.dp), tint = ViewerColors.foreground)
        Text(stringResource(if (keep) R.string.t_cull_assist_keep_short else R.string.t_cull_assist_reject_short), style = HfpsTheme.typography.ui, color = ViewerColors.foreground)
    }
}

/** A dashed outline (suggestions: never a solid ring, which reads as a decision). */
fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 2.dp): Modifier = this.drawWithContent {
    drawContent()
    val stroke = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

/** A 64-bin luma histogram as bars, clipped ends highlighted. */
@Composable
fun Histogram(bins: List<Double>, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.t_cull_assist_histogram)
    val bar = HfpsTheme.colors.foreground.copy(alpha = 0.6f)
    val warn = HfpsTheme.colors.warning
    Canvas(modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = label }) {
        if (bins.isEmpty()) return@Canvas
        val max = maxOf(1e-9, bins.max())
        val w = size.width / bins.size
        bins.forEachIndexed { i, v ->
            val h = maxOf(if (v > 0) 0.6f / 32 * size.height else 0f, (kotlin.math.sqrt(v / max) * size.height).toFloat())
            val clipped = (i == 0 || i == bins.size - 1) && v > 0.005
            drawRect(if (clipped) warn else bar, Offset(i * w + w * 0.1f, size.height - h), Size(w * 0.8f, h))
        }
    }
}

/** One small chip of the on-photo cluster (crown, flags, sharpness, eyes, kind, burst position). */
@Composable
fun Chip(label: String, tone: ChipTone = ChipTone.Neutral, icon: ImageVector? = null, text: String? = null) {
    val (bg, fg) = when (tone) {
        ChipTone.Warning -> HfpsTheme.colors.warning to ViewerColors.background
        ChipTone.Primary -> HfpsTheme.colors.primary to HfpsTheme.colors.primaryForeground
        ChipTone.Neutral -> ViewerColors.scrim to ViewerColors.foreground
    }
    Row(
        Modifier.height(28.dp).defaultMinSize(minWidth = 28.dp).clip(CircleShape).background(bg).padding(horizontal = 8.dp).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = fg)
        if (text != null) Text(text, style = HfpsTheme.typography.uiMedium, color = fg, maxLines = 1)
    }
}

enum class ChipTone { Neutral, Warning, Primary }

/** A solid outline (stacks: resolved in primary, pending in warning). */
fun Modifier.outline(color: Color, width: Dp = 2.dp): Modifier = this.border(width, color)
