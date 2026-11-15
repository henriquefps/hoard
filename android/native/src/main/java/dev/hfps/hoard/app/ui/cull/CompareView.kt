package dev.hfps.hoard.app.ui.cull

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.cull.CullImages
import dev.hfps.hoard.app.cull.CullView
import dev.hfps.hoard.app.cull.Face
import dev.hfps.hoard.app.cull.Flag
import dev.hfps.hoard.app.cull.Shot
import dev.hfps.hoard.app.cull.of
import dev.hfps.hoard.app.cull.rankBurst
import dev.hfps.hoard.app.cull.sharpnessScore
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorFill
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import kotlinx.coroutines.delay

/**
 * Two shots side by side (stacked on a portrait phone) with synchronized zoom and pan: pinch or double tap either one
 * and both follow (one shared [ZoomState]), so the same eye is checked at 100 % in both (CompareView.tsx). Each pane
 * can be picked or rejected; "Keep" picks one (burst mode: Keep this one). The filmstrip and the arrows change the
 * second shot; the crown marks the better frame by the ADR 0009 ranking.
 */
@Composable
fun CompareView(
    view: CullView,
    images: CullImages,
    /** ADR 0006 focus score of a shot not analysed yet (null = cannot be measured); see [dev.hfps.hoard.app.cull.CullBackend.focusScore]. */
    focusScore: suspend (String) -> Double? = { null },
    order: List<Shot>,
    a: Shot,
    b: Shot,
    focusFace: Face?,
    onDecide: (Shot, Flag) -> Unit,
    onKeep: (Shot) -> Unit,
    onB: (String) -> Unit,
    onClose: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val sideBySide = configuration.screenWidthDp > configuration.screenHeightDp
    val zoom = remember { ZoomState() }
    var focus by remember { mutableStateOf<Pair<Face, Int>?>(null) }
    // Start on the main face once pane a has laid out (the shared zoom carries the view to pane b).
    LaunchedEffect(Unit) {
        if (focusFace != null) {
            delay(350)
            focus = focusFace to 1
        }
    }
    // Shots without an analysis yet fall back to the ADR 0006 focus score (CompareView.tsx), measured once per shot.
    var scores by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    LaunchedEffect(a.id, b.id) {
        for (id in listOf(a.id, b.id)) {
            if (id in scores || view.core.analyses.containsKey(id)) continue
            val score = runCatching { focusScore(id) }.getOrNull() ?: continue
            scores = scores + (id to score)
        }
    }
    val bothAnalysed = view.core.analyses.containsKey(a.id) && view.core.analyses.containsKey(b.id)
    val ranked = remember(a.id, b.id, view.core.analyses, view.settings) { rankBurst(listOf(a.id, b.id), view.core.analyses, view.settings.assist) }
    val better = betterOfPair(a.id, b.id, if (bothAnalysed) ranked.map { it.shotId to it.score } else null, scores)
    val bIndex = order.indexOfFirst { it.id == b.id }

    Column(Modifier.fillMaxSize().background(ViewerColors.background).testTag("cull-compare")) {
        Row(
            Modifier.fillMaxWidth().background(ViewerColors.scrim).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HfpsIconButton(PhosphorRegular.ArrowLeft, stringResource(R.string.t_cull_compare_close), onClose, tint = ViewerColors.foreground)
            Text(stringResource(R.string.t_cull_compare_title), style = HfpsTheme.typography.uiMedium, color = ViewerColors.foreground, modifier = Modifier.weight(1f))
            HfpsIconButton(PhosphorRegular.CaretLeft, stringResource(R.string.t_cull_compare_previous), { order.getOrNull(bIndex - 1)?.takeIf { it.id != a.id }?.let { onB(it.id) } ?: order.getOrNull(bIndex - 2)?.let { onB(it.id) } }, enabled = bIndex > 0, tint = ViewerColors.foreground)
            HfpsIconButton(PhosphorRegular.CaretRight, stringResource(R.string.t_cull_compare_next), { order.getOrNull(bIndex + 1)?.takeIf { it.id != a.id }?.let { onB(it.id) } ?: order.getOrNull(bIndex + 2)?.let { onB(it.id) } }, enabled = bIndex < order.size - 1, tint = ViewerColors.foreground)
        }
        val panes: @Composable (Modifier) -> Unit = { m ->
            Pane(view, images, a, zoom, better == a.id, focus, scores[a.id], onDecide, onKeep, m.testTag("compare-a"))
            Pane(view, images, b, zoom, better == b.id, null, scores[b.id], onDecide, onKeep, m.testTag("compare-b"))
        }
        if (sideBySide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) { panes(Modifier.weight(1f)) }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) { panes(Modifier.weight(1f)) }
        }
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
            Filmstrip(view, images, order, bIndex, onSelect = { i -> order.getOrNull(i)?.takeIf { it.id != a.id }?.let { onB(it.id) } }, highlight = a.id)
        }
    }
}

/**
 * The frame the crown marks (CompareView.tsx): with both shots analysed, the better ADR 0009 ranking unless it is a
 * tie; else the higher ADR 0006 focus score when both are measured and differ; else none.
 */
fun betterOfPair(a: String, b: String, ranked: List<Pair<String, Double>>?, focus: Map<String, Double>): String? {
    if (ranked != null) {
        if (ranked.size != 2) return null
        return ranked[0].first.takeIf { ranked[0].second != ranked[1].second }
    }
    val fa = focus[a] ?: return null
    val fb = focus[b] ?: return null
    return when {
        fa > fb -> a
        fb > fa -> b
        else -> null
    }
}

@Composable
private fun Pane(
    view: CullView,
    images: CullImages,
    shot: Shot,
    zoom: ZoomState,
    better: Boolean,
    focus: Pair<Face, Int>?,
    focusScore: Double?,
    onDecide: (Shot, Flag) -> Unit,
    onKeep: (Shot) -> Unit,
    modifier: Modifier,
) {
    val decision = view.core.decisions.of(shot.id)
    val analysis = view.core.analyses[shot.id]
    Box(modifier.fillMaxWidth()) {
        ZoomableShot(images, shot, active = true, zoom = zoom, focusFace = focus)
        Row(Modifier.fillMaxWidth().background(ViewerColors.scrim).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (better) RoundMark(PhosphorFill.CrownSimple, stringResource(R.string.t_cull_assist_best), HfpsTheme.colors.primary, HfpsTheme.colors.primaryForeground)
            Column(Modifier.weight(1f)) {
                Text(shot.stem, style = HfpsTheme.typography.uiMedium, color = ViewerColors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val score = analysis?.let { sharpnessScore(it) }
                val label = when {
                    score != null -> stringResource(R.string.t_cull_compare_sharpness, score.toString())
                    focusScore != null -> stringResource(R.string.t_cull_compare_focus, Math.round(focusScore).toString())
                    else -> null
                }
                Text(
                    listOfNotNull(stringResource(kindText(shot.kind)), label).joinToString(" · "),
                    style = HfpsTheme.typography.ui,
                    color = ViewerColors.foreground.copy(alpha = 0.75f),
                    modifier = Modifier.testTag("compare-score"),
                )
            }
            FlagMark(decision)
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(ViewerColors.scrim).padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            HfpsIconButton(PhosphorRegular.X, stringResource(R.string.t_cull_reject), { onDecide(shot, Flag.Reject) }, active = decision.flag == Flag.Reject, tint = if (decision.flag == Flag.Reject) HfpsTheme.colors.destructive else ViewerColors.foreground)
            SecondaryButton(stringResource(R.string.t_cull_compare_keep), { onKeep(shot) }, icon = PhosphorFill.CheckCircle)
            HfpsIconButton(PhosphorRegular.Check, stringResource(R.string.t_cull_pick), { onDecide(shot, Flag.Pick) }, active = decision.flag == Flag.Pick, tint = if (decision.flag == Flag.Pick) HfpsTheme.colors.primary else ViewerColors.foreground)
        }
    }
}
