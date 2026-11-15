package dev.hfps.hoard.app.ui.cull

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.cull.Analysis
import dev.hfps.hoard.app.cull.BurstPosition
import dev.hfps.hoard.app.cull.BurstStatus
import dev.hfps.hoard.app.cull.CullImages
import dev.hfps.hoard.app.cull.CullView
import dev.hfps.hoard.app.cull.CullViewModel
import dev.hfps.hoard.app.cull.Decision
import dev.hfps.hoard.app.cull.EyeState
import dev.hfps.hoard.app.cull.ExposureVerdict
import dev.hfps.hoard.app.cull.Face
import dev.hfps.hoard.app.cull.Flag
import dev.hfps.hoard.app.cull.RejectMode
import dev.hfps.hoard.app.cull.ScreenImage
import dev.hfps.hoard.app.cull.Shot
import dev.hfps.hoard.app.cull.ShotFlag
import dev.hfps.hoard.app.cull.ShotMetadata
import dev.hfps.hoard.app.cull.Suggestion
import dev.hfps.hoard.app.cull.SuggestionAction
import dev.hfps.hoard.app.cull.ViewerStep
import dev.hfps.hoard.app.cull.burstStatus
import dev.hfps.hoard.app.cull.comparePartner
import dev.hfps.hoard.app.cull.exposureOf
import dev.hfps.hoard.app.cull.eyesSummary
import dev.hfps.hoard.app.cull.keepThisOne
import dev.hfps.hoard.app.cull.of
import dev.hfps.hoard.app.cull.rankBurst
import dev.hfps.hoard.app.cull.rejectBurst
import dev.hfps.hoard.app.cull.sharpnessScore
import dev.hfps.hoard.app.cull.stepOf
import dev.hfps.hoard.app.cull.stepShot
import dev.hfps.hoard.app.cull.suggest
import dev.hfps.hoard.app.cull.withFlag
import dev.hfps.hoard.app.cull.withRating
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.MenuItem
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorFill
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.image.ZoomInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** A vertical swipe longer than this acts (details, or pick / reject). */
private val FLAG_DISTANCE = 90.dp
private const val FLING = 0.45f

private data class BurstMode(val burst: String, val ids: List<String>, val index: Int)

private data class CompareState(val b: String, val ids: List<String>, val face: Face?)

/**
 * The full-screen culling viewer (CullViewer.tsx, ADR 0008).
 *
 * **Folder mode** walks [steps]: a shot, or a whole burst shown by its cover with "View burst (n)"; there ✕ rejects
 * every frame of the burst (one undo step, 1.10.1) and ✓ picks the cover. **Burst mode** walks one burst's frames,
 * with "Keep this one", a quick compare against the suggested best and the burst's suggestion. Swipe to move, swipe
 * up for the details (or pick / reject, a setting), pinch or double tap to 100 %, stars, undo, auto-advance, the
 * filmstrip, the chips on the photo and the details (a bottom panel on phones, a side panel on wide screens).
 */
@Composable
fun CullViewer(
    vm: CullViewModel,
    view: CullView,
    images: CullImages,
    steps: List<ViewerStep>,
    start: ViewerStart,
    onEditInReveal: ((Shot) -> Unit)?,
    onAccept: (List<String>, Collection<Suggestion>?) -> Int,
    onClose: (String?) -> Unit,
) {
    val res = androidx.compose.ui.platform.LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val wide = configuration.screenWidthDp >= 600
    val decisions = view.core.decisions
    var stepIndex by remember { mutableIntStateOf(start.step.coerceIn(0, (steps.size - 1).coerceAtLeast(0))) }
    var burstMode by remember {
        mutableStateOf(start.burst?.let { BurstMode(it.burst, it.shotIds, it.shotIds.indexOf(it.shotId).coerceAtLeast(0)) })
    }
    val order: List<Shot> = remember(burstMode, steps, decisions, view.best, view.byId) {
        val ids = burstMode?.ids ?: steps.map { stepShot(it, decisions, view.best) }
        ids.mapNotNull { view.byId[it] }
    }
    val rawIndex = burstMode?.index ?: stepIndex
    val index = rawIndex.coerceIn(0, (order.size - 1).coerceAtLeast(0))
    val current = order.getOrNull(index)
    val step = if (burstMode == null) steps.getOrNull(index) else null
    var chrome by remember { mutableStateOf(true) }
    var details by remember { mutableStateOf(wide) }
    var compare by remember { mutableStateOf<CompareState?>(null) }
    var source by remember { mutableStateOf<Pair<ScreenImage?, ZoomInfo?>>(null to null) }
    var focusFace by remember { mutableStateOf<Pair<Face, Int>?>(null) }
    val zoom = remember(current?.id) { ZoomState() }
    var swipeDy by remember { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val flagDistance = with(density) { FLAG_DISTANCE.toPx() }
    val exclusionBand = with(density) { 200.dp.toPx() }

    fun setIndex(next: Int) {
        val b = burstMode
        if (b != null) burstMode = b.copy(index = next) else stepIndex = next
    }

    if (current == null) {
        LaunchedEffect(Unit) { onClose(null) }
        return
    }

    LaunchedEffect(current.id) {
        vm.setLastShot(current.id)
        // Analysis follows the viewer: the shot on screen first, then its neighbours.
        vm.focus(current.id)
        images.prefetch(order.map { it.id }, index)
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // The burst on screen: in burst mode the one walked; in folder mode the step's burst, or the shot's.
    val burstOfCurrent = view.bursts[current.id]
    val burstIds: List<String> = burstMode?.ids ?: (step as? ViewerStep.Burst)?.shotIds ?: burstOfCurrent?.let { view.members[it.burst] } ?: emptyList()
    val burstId = burstMode?.burst ?: (step as? ViewerStep.Burst)?.burst ?: burstOfCurrent?.burst
    val ranked = remember(burstIds, view.core.analyses, view.settings) {
        if (burstIds.size >= 2) rankBurst(burstIds, view.core.analyses, view.settings.assist).map { it.shotId } else emptyList()
    }
    // The burst's own suggestion (ADR 0009), whether or not "Suggest" is on for the folder.
    val burstSuggestions = remember(burstMode, view.core.analyses, decisions, view.bursts, view.settings) {
        val b = burstMode ?: return@remember emptyList()
        val shots = b.ids.mapNotNull { view.byId[it] }
        suggest(shots, view.bursts, view.core.analyses, decisions, view.settings.assist).values.filter { it.burst == b.burst }
    }

    val pager = rememberPagerState(initialPage = index) { order.size }
    LaunchedEffect(burstMode?.burst, order.size) { if (pager.currentPage != index && index < order.size) pager.scrollToPage(index) }
    // A swipe settles the pager on a page: that is the shot on screen now (read live, never a value captured here).
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { if (it < pager.pageCount) setIndex(it) } }

    fun goTo(next: Int, animate: Boolean = true) {
        if (next < 0 || next >= order.size || next == index) return
        scope.launch { if (animate && abs(next - index) == 1) pager.animateScrollToPage(next) else pager.scrollToPage(next) }
        setIndex(next)
    }

    fun advance() {
        if (view.settings.autoAdvance && index < order.size - 1) scope.launch {
            delay(120)
            goTo(index + 1)
        }
    }

    fun setFlag(flag: Flag?, mode: RejectMode) {
        // ✕ on a burst in the folder (a stacked step) rejects every frame of it (1.10.1); ✓ picks only the cover.
        if (flag == Flag.Reject && burstMode == null && step is ViewerStep.Burst) {
            val change = vm.apply(rejectBurst(decisions, step.shotIds, mode))
            val rejected = change?.after?.count { it.flag == Flag.Reject } ?: 0
            if (rejected == 0) return
            toaster.show(res.getQuantityString(R.plurals.t_cull_burst_mode_rejected, rejected, rejected.toString()), action = res.getString(R.string.t_cull_undo)) { vm.undo() }
            advance()
            return
        }
        var applied: Flag? = null
        vm.decide(listOf(current.id)) { d, _ ->
            applied = if (mode == RejectMode.Toggle && d.flag == flag) null else flag
            d.withFlag(applied)
        }
        if (applied != null) advance()
    }

    fun rate(stars: Int) {
        vm.decide(listOf(current.id)) { d, _ -> d.withRating(if (d.rating == stars) 0 else stars) }
    }

    fun undo() {
        val change = vm.undo() ?: return
        val shotId = change.shotIds.first()
        val b = burstMode
        val at = if (b != null) b.ids.indexOf(shotId) else stepOf(steps, shotId)
        if (at >= 0 && at != index) goTo(at, animate = false)
    }

    fun enterBurst() {
        if (burstId == null || burstIds.size < 2) return
        compare = null
        burstMode = BurstMode(burstId, burstIds, burstIds.indexOf(current.id).coerceAtLeast(0))
    }

    fun exitBurst(advanceAfter: Boolean = false) {
        val shotId = current.id
        burstMode = null
        compare = null
        val at = stepOf(steps, shotId)
        val base = if (at >= 0) at else stepIndex
        stepIndex = (base + if (advanceAfter) 1 else 0).coerceAtMost(steps.size - 1)
    }

    fun keepFrame(shot: Shot = current) {
        if (burstIds.size < 2) return
        val plan = keepThisOne(decisions, burstIds, shot.id)
        if (plan == null) {
            toaster.show(res.getString(R.string.t_cull_burst_mode_already_kept))
            return
        }
        val change = vm.apply(plan)
        val rejected = change?.shotIds?.withIndex()?.count { (i, id) -> id != shot.id && change.after[i].flag == Flag.Reject } ?: 0
        toaster.show(res.getQuantityString(R.plurals.t_cull_burst_mode_kept, rejected, shot.stem, rejected.toString()), action = res.getString(R.string.t_cull_undo)) { vm.undo() }
        compare = null
        if (burstMode != null && view.settings.autoAdvance) exitBurst(advanceAfter = true)
    }

    /** Quick compare: in a burst, this frame against the suggested best (or the runner-up); elsewhere the next shot. */
    fun toggleCompare() {
        if (compare != null) {
            compare = null
            return
        }
        val analysis = view.core.analyses[current.id]
        val face = analysis?.let { eyesSummary(it, view.settings.eyes).focus }
        if (burstIds.size >= 2) {
            comparePartner(current.id, burstIds, ranked)?.let { compare = CompareState(it, burstIds, face) }
            return
        }
        val other = order.getOrNull(index + 1) ?: order.getOrNull(index - 1)
        if (other != null) compare = CompareState(other.id, order.map { it.id }, face)
    }

    fun back() {
        when {
            compare != null -> compare = null
            details && !wide -> details = false
            burstMode != null -> exitBurst()
            else -> onClose(current.id)
        }
    }
    BackHandler { back() }

    val decision = decisions.of(current.id)
    val analysis = view.core.analyses[current.id]
    val isBest = burstOfCurrent != null && current.id in view.bestShots
    val status = if (burstIds.size >= 2) burstStatus(burstIds, decisions) else null
    val suggestion = if (view.controls.suggesting) view.suggestions[current.id] else burstSuggestions.firstOrNull { it.shotId == current.id }
    fun acceptSuggestion(ids: List<String>) = onAccept(ids, if (view.controls.suggesting) null else burstSuggestions)
    val burstSuggestionIds = suggestion?.burst?.let { b -> (if (view.controls.suggesting) view.suggestions.values else burstSuggestions).filter { it.burst == b }.map { it.shotId } }.orEmpty()

    val detailsContent: @Composable () -> Unit = {
        ShotDetails(
            shot = current,
            metadata = view.core.metadata[current.id],
            analysis = analysis,
            flags = view.flags[current.id],
            best = isBest,
            burst = burstOfCurrent,
            suggestion = suggestion,
            view = view,
            image = source.first,
            zoom = source.second,
            onJumpToFace = { face -> focusFace = face to ((focusFace?.second ?: 0) + 1) },
            onAccept = suggestion?.let { { acceptSuggestion(listOf(current.id)) } },
            onAcceptBurst = if (burstSuggestionIds.size > 1) ({ acceptSuggestion(burstSuggestionIds) }) else null,
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ViewerColors.background)
            .testTag("cull-viewer")
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if ((event.isCtrlPressed || event.isMetaPressed) && event.key == Key.Z) {
                    undo()
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.Escape -> back()
                    Key.DirectionRight -> goTo(index + 1)
                    Key.DirectionLeft -> goTo(index - 1)
                    Key.P -> setFlag(Flag.Pick, RejectMode.Toggle)
                    Key.X -> setFlag(Flag.Reject, RejectMode.Toggle)
                    Key.U -> setFlag(null, RejectMode.Set)
                    Key.One -> rate(1)
                    Key.Two -> rate(2)
                    Key.Three -> rate(3)
                    Key.Four -> rate(4)
                    Key.Five -> rate(5)
                    Key.Zero -> vm.decide(listOf(current.id)) { d, _ -> d.withRating(0) }
                    Key.I -> details = !details
                    Key.C -> toggleCompare()
                    Key.B -> if (burstMode != null) exitBurst() else enterBurst()
                    Key.K -> if (burstIds.size >= 2) keepFrame() else return@onPreviewKeyEvent false
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        val compareState = compare
        val other = compareState?.let { view.byId[it.b] }
        if (compareState != null && other != null) {
            CompareView(
                view = view,
                images = images,
                order = compareState.ids.mapNotNull { view.byId[it] },
                a = current,
                b = other,
                focusFace = compareState.face,
                onDecide = { shot, flag -> vm.decide(listOf(shot.id)) { d, _ -> d.withFlag(if (d.flag == flag) null else flag) } },
                onKeep = if (burstIds.size >= 2) ({ winner -> keepFrame(winner) }) else ({ winner ->
                    val loser = if (winner.id == current.id) other else current
                    vm.decide(listOf(winner.id, loser.id)) { d, id -> d.withFlag(if (id == winner.id) Flag.Pick else Flag.Reject) }
                    compare = null
                }),
                onB = { compare = compareState.copy(b = it) },
                onClose = { compare = null },
            )
            return@Box
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    HorizontalPager(
                        pager,
                        Modifier
                            .fillMaxSize()
                            // Swipes from the screen edges page instead of going back, in a band across the middle
                            // of the photo (Android honours 200 dp per edge; Hoard 1's setGestureExclusion).
                            .systemGestureExclusion { coordinates ->
                                val band = exclusionBand
                                val top = (coordinates.size.height - band) / 2
                                androidx.compose.ui.geometry.Rect(0f, top.coerceAtLeast(0f), coordinates.size.width.toFloat(), top + band)
                            }
                            .testTag("cull-pager"),
                        userScrollEnabled = !zoom.zoomed,
                        beyondViewportPageCount = 1,
                        key = { order.getOrNull(it)?.id ?: it },
                    ) { page ->
                        val shot = order.getOrNull(page) ?: return@HorizontalPager
                        val active = page == index
                        val own = remember(shot.id) { ZoomState() }
                        ZoomableShot(
                            images = images,
                            shot = shot,
                            active = active,
                            zoom = if (active) zoom else own,
                            modifier = if (active) Modifier.offset { IntOffset(0, swipeDy.roundToInt()) } else Modifier,
                            onTap = { chrome = !chrome },
                            onScreen = { image, info -> if (active) source = image to info },
                            focusFace = if (active) focusFace else null,
                            onVerticalSwipe = if (!active) null else { dy, end, velocity ->
                                val swipeDecide = view.settings.swipeDecide && !(details && !wide)
                                if (!end) {
                                    swipeDy = dy * if (swipeDecide) 0.6f else 0.35f
                                } else {
                                    swipeDy = 0f
                                    val up = dy < -flagDistance || velocity < -FLING
                                    val down = dy > flagDistance || velocity > FLING
                                    when {
                                        details && !wide -> if (down) details = false
                                        swipeDecide -> if (up) setFlag(Flag.Pick, RejectMode.Set) else if (down) setFlag(Flag.Reject, RejectMode.Set)
                                        up -> details = true
                                    }
                                }
                            },
                        )
                    }
                    if (view.settings.swipeDecide && swipeDy != 0f) SwipeHints(swipeDy / 0.6f, flagDistance)
                    Column(Modifier.fillMaxWidth()) {
                        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
                            TopBar(
                                current = current,
                                position = if (burstMode != null) stringResource(R.string.t_cull_burst_mode_position, (index + 1).toString(), order.size.toString()) else stringResource(R.string.t_cull_viewer_position, (index + 1).toString(), order.size.toString()),
                                decision = decision,
                                loadingFinal = source.first?.final == false,
                                inBurst = burstMode != null,
                                canUndo = view.core.undo.isNotEmpty(),
                                details = details,
                                canCompare = order.size >= 2 || burstIds.size >= 2,
                                autoAdvance = view.settings.autoAdvance,
                                swipeDecide = view.settings.swipeDecide,
                                onClose = { if (burstMode != null) exitBurst() else onClose(current.id) },
                                onUndo = ::undo,
                                onDetails = { details = !details },
                                onCompare = ::toggleCompare,
                                onAutoAdvance = { vm.setAutoAdvance(it) },
                                onSwipeDecide = { vm.setSwipeDecide(it) },
                                onEditInReveal = onEditInReveal?.let { { it(current) } },
                            )
                        }
                        if (chrome && !(details && !wide)) {
                            InfoChips(current, analysis, view.flags[current.id], isBest, if (burstMode != null) null else burstOfCurrent, view, Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { details = true }
                        }
                    }
                }
                // On phones the details panel takes the bottom half: its own decision bar replaces this one.
                AnimatedVisibility(chrome && !(details && !wide), enter = fadeIn(), exit = fadeOut()) {
                    Column(Modifier.fillMaxWidth().background(ViewerColors.scrim).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
                        if (burstMode != null) {
                            BurstActions(status, onKeep = { keepFrame() }, onCompare = ::toggleCompare, suggestionCount = burstSuggestions.size, onAcceptSuggestion = { acceptSuggestion(burstSuggestions.map { it.shotId }) })
                        } else if (status != null) {
                            FolderBurstRow(status, onView = ::enterBurst)
                        }
                        DecisionBar(decision, onReject = { setFlag(Flag.Reject, RejectMode.Toggle) }, onPick = { setFlag(Flag.Pick, RejectMode.Toggle) }, onRate = ::rate)
                        Filmstrip(view, images, order, index, onSelect = { goTo(it, animate = false) }, burst = burstMode != null)
                    }
                }
                if (details && !wide) {
                    Column(Modifier.fillMaxWidth().fillMaxHeight(0.5f).background(HfpsTheme.colors.card).testTag("cull-details")) {
                        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).size(48.dp, 5.dp).clip(CircleShape).background(HfpsTheme.colors.mutedForeground.copy(alpha = 0.4f)).clickable { details = false })
                        DecisionBar(decision, onReject = { setFlag(Flag.Reject, RejectMode.Toggle) }, onPick = { setFlag(Flag.Pick, RejectMode.Toggle) }, onRate = ::rate, compact = true, dark = false)
                        HorizontalDivider(color = HfpsTheme.colors.hairline)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) { detailsContent() }
                    }
                }
            }
            if (details && wide) {
                Column(Modifier.width(352.dp).fillMaxHeight().background(HfpsTheme.colors.card).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)).testTag("cull-details")) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.t_cull_details_title), style = HfpsTheme.typography.title, color = HfpsTheme.colors.cardForeground, modifier = Modifier.weight(1f))
                        HfpsIconButton(PhosphorRegular.X, stringResource(R.string.t_cull_details_close), { details = false })
                    }
                    HorizontalDivider(color = HfpsTheme.colors.hairline)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { detailsContent() }
                }
            }
        }
    }
}

@Composable
private fun SwipeHints(dy: Float, distance: Float) {
    Box(Modifier.fillMaxSize()) {
        val pick = (-dy / distance).coerceIn(0f, 1f)
        val reject = (dy / distance).coerceIn(0f, 1f)
        if (pick > 0) Pill(stringResource(R.string.t_cull_pick), PhosphorRegular.Check, HfpsTheme.colors.primary, HfpsTheme.colors.primaryForeground, Modifier.align(Alignment.Center).alpha(pick))
        if (reject > 0) Pill(stringResource(R.string.t_cull_reject), PhosphorRegular.X, HfpsTheme.colors.destructive, ViewerColors.foreground, Modifier.align(Alignment.Center).alpha(reject))
    }
}

@Composable
private fun Pill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, bg: Color, fg: Color, modifier: Modifier) {
    Row(modifier.clip(CircleShape).background(bg).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = fg)
        Text(text, style = HfpsTheme.typography.uiMedium, color = fg)
    }
}

@Composable
private fun TopBar(
    current: Shot,
    position: String,
    decision: Decision,
    loadingFinal: Boolean,
    inBurst: Boolean,
    canUndo: Boolean,
    details: Boolean,
    canCompare: Boolean,
    autoAdvance: Boolean,
    swipeDecide: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onDetails: () -> Unit,
    onCompare: () -> Unit,
    onAutoAdvance: (Boolean) -> Unit,
    onSwipeDecide: (Boolean) -> Unit,
    onEditInReveal: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().background(ViewerColors.scrim).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HfpsIconButton(
            if (inBurst) PhosphorRegular.ArrowLeft else PhosphorRegular.X,
            stringResource(if (inBurst) R.string.t_cull_burst_mode_exit else R.string.t_common_close),
            onClose,
            tint = ViewerColors.foreground,
        )
        Column(Modifier.weight(1f)) {
            Text(current.stem, style = HfpsTheme.typography.uiMedium, color = ViewerColors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("viewer-stem"))
            Text(position, style = HfpsTheme.typography.ui, color = ViewerColors.foreground.copy(alpha = 0.7f), maxLines = 1, modifier = Modifier.testTag("viewer-position"))
        }
        FlagMark(decision, Modifier.padding(horizontal = 4.dp))
        if (loadingFinal) CircularProgressIndicator(Modifier.padding(horizontal = 4.dp).size(16.dp), color = ViewerColors.foreground.copy(alpha = 0.7f), strokeWidth = 1.5.dp)
        HfpsIconButton(PhosphorRegular.ArrowCounterClockwise, stringResource(R.string.t_cull_undo), onUndo, enabled = canUndo, tint = ViewerColors.foreground)
        HfpsIconButton(if (details) PhosphorFill.Info else PhosphorRegular.Info, stringResource(R.string.t_cull_details_toggle), onDetails, active = details, tint = if (details) HfpsTheme.colors.primary else ViewerColors.foreground)
        HfpsIconButton(PhosphorRegular.Columns, stringResource(R.string.t_cull_compare_open), onCompare, enabled = canCompare, tint = ViewerColors.foreground)
        var menu by remember { mutableStateOf(false) }
        Box {
            HfpsIconButton(PhosphorRegular.DotsThreeVertical, stringResource(R.string.t_common_more), { menu = true }, tint = ViewerColors.foreground)
            DropdownMenu(menu, { menu = false }, containerColor = HfpsTheme.colors.card) {
                MenuItem(stringResource(R.string.t_cull_auto_advance), if (autoAdvance) PhosphorRegular.CheckSquare else PhosphorRegular.Square, { onAutoAdvance(!autoAdvance) })
                MenuItem(stringResource(R.string.t_cull_viewer_swipe_decide), if (swipeDecide) PhosphorRegular.CheckSquare else PhosphorRegular.Square, { onSwipeDecide(!swipeDecide) })
                if (onEditInReveal != null) {
                    HorizontalDivider(color = HfpsTheme.colors.hairline)
                    MenuItem(stringResource(R.string.t_reveal_edit), PhosphorRegular.SlidersHorizontal, { menu = false; onEditInReveal() })
                }
            }
        }
    }
}

/** The compact cluster on the photo: crown, flags, sharpness, eyes, kind, burst position; opens the details. */
@Composable
fun InfoChips(shot: Shot, analysis: Analysis?, flags: List<ShotFlag>?, best: Boolean, burst: BurstPosition?, view: CullView, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val eyes = analysis?.let { eyesSummary(it, view.settings.eyes) }
    val label = stringResource(R.string.t_cull_details_open)
    FlowRow(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen).semantics { contentDescription = label }.testTag("cull-chips"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (best) Chip(stringResource(R.string.t_cull_assist_best), ChipTone.Primary, PhosphorFill.CrownSimple)
        flags?.forEach { Chip(stringResource(flagText(it)), ChipTone.Warning, flagIcon(it)) }
        if (analysis != null) {
            val score = sharpnessScore(analysis)
            Chip(stringResource(R.string.t_cull_assist_sharpness, score.toString()), icon = PhosphorRegular.CrosshairSimple, text = score.toString())
        }
        if (eyes != null && eyes.verdict == EyeState.Open) {
            Chip(pluralStringResource(R.plurals.t_cull_chips_eyes_open, eyes.prominent.size, eyes.prominent.size.toString()), icon = PhosphorRegular.Eye, text = if (eyes.prominent.size > 1) eyes.prominent.size.toString() else null)
        }
        Chip(stringResource(kindText(shot.kind)), text = stringResource(kindText(shot.kind)))
        if (burst != null) Chip(stringResource(R.string.t_cull_burst, (burst.index + 1).toString(), burst.size.toString()), icon = PhosphorFill.Stack, text = "${burst.index + 1}/${burst.size}")
    }
}

/** Burst mode, above the decision bar: Keep this one, compare with the best, the suggestion, the status. */
@Composable
private fun BurstActions(status: BurstStatus?, onKeep: () -> Unit, onCompare: () -> Unit, suggestionCount: Int, onAcceptSuggestion: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("burst-actions"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(stringResource(R.string.t_cull_burst_mode_keep), onKeep, Modifier.weight(1f).testTag("keep-this-one"), icon = PhosphorFill.CheckCircle)
        HfpsIconButton(PhosphorRegular.Columns, stringResource(R.string.t_cull_burst_mode_compare), onCompare, tint = ViewerColors.foreground)
        if (suggestionCount > 0) HfpsIconButton(PhosphorFill.Sparkle, stringResource(R.string.t_cull_burst_mode_accept_suggestion), onAcceptSuggestion, tint = ViewerColors.foreground)
        // Phones keep the room for the actions (Hoard 1 hides the status below `sm`).
        if (status != null && LocalConfiguration.current.screenWidthDp >= 600) BurstStatusText(status)
    }
}

@Composable
private fun BurstStatusText(status: BurstStatus) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (status.resolved) Icon(PhosphorRegular.Check, null, Modifier.size(16.dp), tint = HfpsTheme.colors.primary)
        Text(
            if (status.resolved) stringResource(R.string.t_cull_burst_mode_resolved, status.kept.toString(), status.size.toString()) else stringResource(R.string.t_cull_burst_mode_pending, status.decided.toString(), status.size.toString()),
            style = HfpsTheme.typography.ui,
            color = if (status.resolved) HfpsTheme.colors.primary else ViewerColors.foreground.copy(alpha = 0.75f),
        )
    }
}

/** Folder mode on a burst: its status and "View burst (n)". */
@Composable
private fun FolderBurstRow(status: BurstStatus, onView: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(stringResource(R.string.t_cull_burst_mode_view, status.size.toString()), onView, Modifier.testTag("view-burst"), icon = PhosphorFill.Stack)
        Spacer(Modifier.weight(1f))
        BurstStatusText(status)
    }
}

/** Reject · 1–5 stars · Pick. */
@Composable
fun DecisionBar(decision: Decision, onReject: () -> Unit, onPick: () -> Unit, onRate: ((Int) -> Unit)?, compact: Boolean = false, dark: Boolean = true) {
    val fg = if (dark) ViewerColors.foreground else HfpsTheme.colors.cardForeground
    Row(Modifier.fillMaxWidth().height(if (compact) 52.dp else 60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        DecisionButton(stringResource(R.string.t_cull_reject), PhosphorRegular.X, decision.flag == Flag.Reject, HfpsTheme.colors.destructive, fg, onReject, "decide-reject")
        if (onRate != null) {
            val label = stringResource(R.string.t_cull_rating)
            Row(Modifier.semantics { contentDescription = label }) {
                (1..5).forEach { stars ->
                    val on = decision.rating >= stars
                    val starLabel = pluralStringResource(R.plurals.t_cull_stars, stars, stars.toString())
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).clickable { onRate(stars) }.semantics { contentDescription = starLabel }.testTag("star-$stars"),
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (on) PhosphorFill.Star else PhosphorRegular.Star, null, Modifier.size(24.dp), tint = if (on) HfpsTheme.colors.warning else fg.copy(alpha = 0.9f)) }
                }
            }
        }
        DecisionButton(stringResource(R.string.t_cull_pick), PhosphorRegular.Check, decision.flag == Flag.Pick, HfpsTheme.colors.primary, fg, onPick, "decide-pick")
    }
}

@Composable
private fun DecisionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, color: Color, fg: Color, onClick: () -> Unit, tag: String) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(if (on) color else Color.Transparent).clickable(onClick = onClick).semantics { contentDescription = label }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(22.dp), tint = if (on) (if (color == HfpsTheme.colors.primary) HfpsTheme.colors.primaryForeground else ViewerColors.foreground) else fg) }
}

/** Thumbnails of the viewing order; the current one outlined and kept in view. Burst mode adds crown, eyes and score. */
@Composable
fun Filmstrip(view: CullView, images: CullImages, order: List<Shot>, index: Int, onSelect: (Int) -> Unit, burst: Boolean = false, highlight: String? = null) {
    val list = rememberLazyListState()
    LaunchedEffect(index) { list.animateScrollToItem((index - 3).coerceAtLeast(0)) }
    val label = stringResource(R.string.t_cull_filmstrip)
    LazyRow(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = label }.testTag("filmstrip"), state = list, contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(order, key = { _, s -> s.id }) { i, shot ->
            val decision = view.core.decisions.of(shot.id)
            val selected = i == index || shot.id == highlight
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .let { if (selected) it.border(2.dp, HfpsTheme.colors.primary, RoundedCornerShape(6.dp)) else it }
                    .clickable { onSelect(i) }
                    .semantics { contentDescription = shot.stem },
            ) {
                ShotThumb(images, shot, Modifier.fillMaxSize(), if (decision.flag == Flag.Reject) 0.45f else 1f)
                if (burst) {
                    if (shot.id in view.bestShots) RoundMark(PhosphorFill.CrownSimple, stringResource(R.string.t_cull_assist_best), HfpsTheme.colors.primary, HfpsTheme.colors.primaryForeground, Modifier.align(Alignment.TopStart).padding(2.dp), 16.dp)
                    if (view.flags[shot.id]?.contains(ShotFlag.EyesClosed) == true) RoundMark(PhosphorRegular.EyeClosed, stringResource(R.string.t_cull_assist_flag_eyes_closed), HfpsTheme.colors.warning, ViewerColors.background, Modifier.align(Alignment.TopEnd).padding(2.dp), 16.dp)
                    view.core.analyses[shot.id]?.let { a ->
                        Text(sharpnessScore(a).toString(), style = HfpsTheme.typography.monoSmall, color = ViewerColors.foreground, modifier = Modifier.align(Alignment.BottomStart).background(ViewerColors.scrim).padding(horizontal = 3.dp))
                    }
                }
                if (decision.flag != null) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(2.dp).size(10.dp).clip(CircleShape).background(if (decision.flag == Flag.Pick) HfpsTheme.colors.primary else HfpsTheme.colors.destructive))
                }
            }
        }
    }
}

// --- Details ------------------------------------------------------------------------------------------------------

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(title)
        content()
    }
    HorizontalDivider(color = HfpsTheme.colors.hairline)
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        Text(value, style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

private fun percent(value: Double, locale: java.util.Locale) = formatNumber(Math.round(value * 1000) / 10.0, locale) + "%"

/** At most this many faces are listed one by one. */
private const val FACE_ROWS = 6

/**
 * Everything about the shot on screen: the suggestion with Accept, the analysis (sharpness, every prominent face with
 * its eyes and Zoom to face, exposure with the histogram and what triggered a flag), then the camera data.
 */
@Composable
fun ShotDetails(
    shot: Shot,
    metadata: ShotMetadata?,
    analysis: Analysis?,
    flags: List<ShotFlag>?,
    best: Boolean,
    burst: BurstPosition?,
    suggestion: Suggestion?,
    view: CullView,
    image: ScreenImage?,
    zoom: ZoomInfo?,
    onJumpToFace: (Face) -> Unit,
    onAccept: (() -> Unit)?,
    onAcceptBurst: (() -> Unit)?,
) {
    val loc = locale()
    Column(Modifier.fillMaxWidth()) {
        if (suggestion != null) {
            Section(stringResource(R.string.t_cull_details_suggestion)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(PhosphorFill.Sparkle, null, Modifier.size(20.dp), tint = if (suggestion.action == SuggestionAction.Keep) HfpsTheme.colors.primary else HfpsTheme.colors.destructive)
                    Column {
                        Text(stringResource(if (suggestion.action == SuggestionAction.Keep) R.string.t_cull_assist_suggest_keep else R.string.t_cull_assist_suggest_reject), style = HfpsTheme.typography.uiMedium, color = HfpsTheme.colors.cardForeground)
                        Text(reasonText(suggestion.reasons), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onAccept != null) PrimaryButton(stringResource(R.string.t_cull_assist_accept), onAccept, Modifier.testTag("accept-suggestion"))
                    if (onAcceptBurst != null) SecondaryButton(stringResource(R.string.t_cull_assist_accept_burst), onAcceptBurst)
                }
            }
        }
        Section(stringResource(R.string.t_cull_details_analysis)) {
            if (analysis == null) {
                Text(stringResource(R.string.t_cull_assist_not_analysed), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
            } else {
                if (!flags.isNullOrEmpty() || best) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (best) Chip(stringResource(R.string.t_cull_assist_best), ChipTone.Primary, PhosphorFill.CrownSimple, stringResource(R.string.t_cull_assist_best))
                        flags?.forEach { Chip(stringResource(flagText(it)), ChipTone.Warning, flagIcon(it), stringResource(flagText(it))) }
                    }
                }
                DetailRow(
                    stringResource(R.string.t_cull_details_sharpness),
                    sharpnessScore(analysis).toString() + " · " + stringResource(if (analysis.sharpness.subjectKind == "face") R.string.t_cull_assist_subject_face else R.string.t_cull_assist_subject_tile),
                )
                val eyes = eyesSummary(analysis, view.settings.eyes)
                when {
                    !analysis.facesDetected || !view.core.facesAvailable -> DetailRow(stringResource(R.string.t_cull_details_faces), stringResource(R.string.t_cull_assist_faces_unavailable))
                    eyes.prominent.isEmpty() -> DetailRow(stringResource(R.string.t_cull_details_faces), stringResource(R.string.t_cull_assist_no_faces))
                    else -> {
                        DetailRow(stringResource(R.string.t_cull_details_faces), pluralStringResource(R.plurals.t_cull_assist_faces, eyes.prominent.size, eyes.prominent.size.toString()) + " · " + stringResource(eyeText(eyes.verdict)))
                        eyes.prominent.take(FACE_ROWS).forEachIndexed { i, face ->
                            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.t_cull_details_face, (i + 1).toString()) + " · " + stringResource(eyeText(eyes.states[i])),
                                    style = HfpsTheme.typography.body,
                                    color = if (eyes.states[i] == EyeState.Closed) HfpsTheme.colors.warning else HfpsTheme.colors.cardForeground,
                                    modifier = Modifier.weight(1f),
                                )
                                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onJumpToFace(face) }.padding(8.dp).testTag("zoom-to-face-$i"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(PhosphorRegular.UserFocus, null, Modifier.size(18.dp), tint = HfpsTheme.colors.primary)
                                    Text(stringResource(R.string.t_cull_assist_jump_to_face), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.primary)
                                }
                            }
                        }
                        if (eyes.prominent.size > FACE_ROWS) {
                            val more = eyes.prominent.size - FACE_ROWS
                            Text(pluralStringResource(R.plurals.t_cull_details_more_faces, more, more.toString()), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                        }
                    }
                }
                val e = analysis.exposure
                val outcome = exposureOf(e)
                val exposure = if (outcome.verdict == ExposureVerdict.Ok) {
                    stringResource(R.string.t_cull_details_exposure_ok)
                } else {
                    val value = when (outcome.reason) {
                        dev.hfps.hoard.app.cull.ExposureReason.BlownArea -> e.blownArea
                        dev.hfps.hoard.app.cull.ExposureReason.Face -> e.faceHighlights
                        dev.hfps.hoard.app.cull.ExposureReason.Shadows -> e.shadows
                        else -> e.mean
                    }
                    stringResource(exposureText(outcome.verdict == ExposureVerdict.Over, outcome.reason!!), percent(value, loc))
                }
                DetailRow(stringResource(R.string.t_cull_details_exposure_label), exposure)
                Histogram(e.histogram)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.Start)) {
                    Text(stringResource(R.string.t_cull_assist_shadows, percent(e.shadows, loc)), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                    Text(stringResource(R.string.t_cull_details_highlights, percent(e.highlights, loc), percent(e.blownArea, loc)), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                }
                Text(
                    stringResource(R.string.t_cull_assist_measured_on, stringResource(screenSourceText(analysis.source)), "${analysis.width} × ${analysis.height}"),
                    style = HfpsTheme.typography.ui,
                    color = HfpsTheme.colors.mutedForeground,
                )
            }
        }
        Section(stringResource(R.string.t_cull_details_camera)) {
            Text(shot.stem, style = HfpsTheme.typography.uiMedium, color = HfpsTheme.colors.cardForeground)
            formatCapture(metadata?.captureTime, loc)?.let { (time, date) -> DetailRow(stringResource(R.string.t_cull_details_taken), "$time · $date") }
            exposureLine(metadata, loc)?.let { DetailRow(stringResource(R.string.t_cull_details_exposure), it) }
            metadata?.focalLength?.let { focal ->
                val number = java.text.NumberFormat.getNumberInstance(loc).apply { maximumFractionDigits = 1 }
                val text = number.format(focal) + " mm" + (metadata.focalLength35?.takeIf { it != focal.roundToInt() }?.let { " " + stringResource(R.string.t_cull_exif_equivalent, "$it mm") } ?: "")
                DetailRow(stringResource(R.string.t_cull_details_focal), text)
            }
            metadata?.lens?.let { DetailRow(stringResource(R.string.t_cull_details_lens), it) }
            val camera = listOfNotNull(metadata?.make?.takeUnless { m -> metadata.model?.uppercase()?.startsWith(m.uppercase()) == true }, metadata?.model).joinToString(" ")
            if (camera.isNotEmpty()) DetailRow(stringResource(R.string.t_cull_details_camera), camera)
            DetailRow(
                stringResource(R.string.t_cull_details_file),
                if (metadata?.width != null && metadata.height != null) "${metadata.width} × ${metadata.height} · ${stringResource(kindText(shot.kind))}" else stringResource(kindText(shot.kind)),
            )
            if (burst != null) DetailRow(stringResource(R.string.t_cull_details_burst), stringResource(R.string.t_cull_details_burst_value, (burst.index + 1).toString(), burst.size.toString()))
            if (image != null) {
                DetailRow(
                    stringResource(R.string.t_cull_details_shown),
                    if (zoom != null) stringResource(zoomSourceText(CullImages.zoomSourceName(zoom.source))) else stringResource(screenSourceText(CullImages.screenSourceName(image.source))),
                )
            }
            if (metadata == null) Text(stringResource(R.string.t_cull_exif_none), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        }
    }
}

/** `15:04:05.733` and the date from the camera clock (no zone: shown as the camera wrote it). */
fun formatCapture(captureTime: String?, locale: java.util.Locale): Pair<String, String>? {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?""").find(captureTime ?: return null) ?: return null
    val (y, mo, d, h, mi, s, ms) = m.destructured
    val date = java.time.LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
    val formatted = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale).format(date)
    return "$h:$mi:$s${if (ms.isNotEmpty()) "." + ms.padEnd(3, '0') else ""}" to formatted
}

/** `1/250 s · f/1.8 · ISO 400` (whatever is known). */
fun exposureLine(m: ShotMetadata?, locale: java.util.Locale): String? {
    m ?: return null
    val number = java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    val parts = listOfNotNull(m.exposure?.let { "$it s" }, m.fNumber?.let { "f/" + number.format(it) }, m.iso?.let { "ISO $it" })
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
