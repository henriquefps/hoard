package dev.hfps.hoard.app.ui.cull

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.cull.AssistFilter
import dev.hfps.hoard.app.cull.CullBackend
import dev.hfps.hoard.app.cull.CullEvent
import dev.hfps.hoard.app.cull.CullFilter
import dev.hfps.hoard.app.cull.CullImages
import dev.hfps.hoard.app.cull.CullKey
import dev.hfps.hoard.app.cull.CullSort
import dev.hfps.hoard.app.cull.CullStatus
import dev.hfps.hoard.app.cull.CullView
import dev.hfps.hoard.app.cull.CullViewModel
import dev.hfps.hoard.app.cull.Flag
import dev.hfps.hoard.app.cull.FlagFilter
import dev.hfps.hoard.app.cull.GridItem
import dev.hfps.hoard.app.cull.NativeCullBackend
import dev.hfps.hoard.app.cull.Shot
import dev.hfps.hoard.app.cull.SuggestionAction
import dev.hfps.hoard.app.cull.ViewerStep
import dev.hfps.hoard.app.cull.burstStatus
import dev.hfps.hoard.app.cull.files
import dev.hfps.hoard.app.cull.nextUnmarked
import dev.hfps.hoard.app.cull.of
import dev.hfps.hoard.app.cull.stepOf
import dev.hfps.hoard.app.cull.viewerSteps
import dev.hfps.hoard.app.files.BrowseLocation
import dev.hfps.hoard.app.files.FileActions
import dev.hfps.hoard.app.files.FileService
import dev.hfps.hoard.app.files.revealOrder
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.MenuItem
import dev.hfps.hoard.app.ui.common.StateMessage
import dev.hfps.hoard.app.ui.common.errorBody
import dev.hfps.hoard.app.ui.common.errorTitle
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.app.ui.reveal.rememberReveal
import dev.hfps.hoard.app.ui.files.XmpExportRequest
import dev.hfps.hoard.app.ui.files.rememberXmpExport
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorFill
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import kotlinx.coroutines.launch

/** Where the viewer opens: a folder step, optionally straight into that burst at one frame. */
data class ViewerStart(val step: Int, val burst: BurstStart? = null)

data class BurstStart(val burst: String, val shotIds: List<String>, val shotId: String)

/**
 * `CullRoute` (ADR 0008, 0009): a folder of any location as shots (RAW + JPEG pairs collapsed), a dense grid in
 * capture order with bursts stacked, analysis flags and suggestions, filters, progress, the full-screen viewer and
 * the finish actions (copy picks, Edit in Reveal, delete rejects). The port of `CullPage.tsx`.
 */
@Composable
fun CullScreen(container: AppContainer, locationId: String, share: String?, path: String, onBack: () -> Unit, onTransfers: () -> Unit, onOpenSettings: () -> Unit) {
    val machines by container.database.machines().observeAll().collectAsState(initial = null)
    val volumes by container.volumes.observe().collectAsState(initial = null)
    val phone = stringResource(R.string.t_locations_internal)
    val drive = stringResource(R.string.t_locations_drive)
    val location = remember(machines, volumes) {
        if (machines == null || volumes == null) null else FileService.resolve(locationId, share, machines!!, volumes!!, phone, drive)
    }
    val title = path.split('/').lastOrNull { it.isNotEmpty() } ?: share ?: location?.name.orEmpty()
    // A USB drive or SD card pulled out while culling it: say so and leave (Hoard 1's useLeaveWhenUnplugged).
    val toaster = LocalToaster.current
    val res = androidx.compose.ui.platform.LocalResources.current
    var seenVolume by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(location?.volume?.state, location?.kind) {
        val loc = location ?: return@LaunchedEffect
        if (loc.kind != dev.hfps.hoard.app.files.LocationKind.Volume) return@LaunchedEffect
        val readable = loc.volume?.state.let { it == "mounted" || it == "mountedReadOnly" }
        if (readable) {
            seenVolume = loc.name
        } else if (seenVolume != null) {
            toaster.show(res.getString(R.string.t_locations_unplugged, seenVolume))
            onBack()
        }
    }
    when {
        machines == null || volumes == null -> HfpsScaffold(topBar = { HfpsTopBar(title, onBack = onBack) }) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = HfpsTheme.colors.primary, strokeWidth = 2.dp)
        }
        location == null -> HfpsScaffold(topBar = { HfpsTopBar(stringResource(R.string.t_errors_not_found_title), onBack = onBack) }) {
            StateMessage(PhosphorRegular.WarningCircle, stringResource(errorTitle(ErrorCode.NOT_FOUND)), Modifier.align(Alignment.Center), body = stringResource(errorBody(ErrorCode.NOT_FOUND)), tint = HfpsTheme.colors.destructive)
        }
        else -> {
            val key = CullKey(location.id, if (location.isSmb) location.share.orEmpty() else "", path)
            val vm: CullViewModel = viewModel(key = "cull:${key}", factory = CullViewModelFactory(container, location, key))
            CullFolderScreen(container, vm, location, title, onBack, onTransfers, onOpenSettings)
        }
    }
}

class CullViewModelFactory(
    private val container: AppContainer,
    private val location: BrowseLocation,
    private val key: CullKey,
    private val backend: (() -> CullBackend)? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val b = backend?.invoke() ?: NativeCullBackend(
            container.cullService(), container.routes, container.gateway, container.volumeRegistry, location, key.path,
            onDeleted = { if (location.isSmb) container.serverLinks.invalidate(location, key.path) },
        )
        val dao = container.database.cull()
        if (location.isSmb && backend == null) {
            // ADR 0022: on a mapped SMB folder, decisions also go to the server's culling of the same items.
            val links = container.serverLinks
            val store = dev.hfps.hoard.app.cull.MappedCullStore(
                container.appContext, dev.hfps.hoard.app.cull.FolderCullStore(dao, key), dao, key, container.gallery,
                items = { links.shotItems(links.resolveFolder(location, key.path)) },
            )
            return CullViewModel(key, b, store, container.appSettings.cull, appSettings = container.appSettings) as T
        }
        return CullViewModel(key, b, dao, container.appSettings.cull, appSettings = container.appSettings) as T
    }
}

/** What the grid's ⋯ and finish menus open. */
private sealed interface CullDialog {
    data object Settings : CullDialog
    data object Copy : CullDialog
    data object Delete : CullDialog
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CullFolderScreen(
    container: AppContainer,
    vm: CullViewModel,
    location: BrowseLocation,
    title: String,
    onBack: () -> Unit,
    onTransfers: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val view by vm.view.collectAsStateWithLifecycle()
    val serverShots by vm.serverShots.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val actions = remember { FileActions(container) }
    val reveal = rememberReveal(actions)
    val xmpExport = rememberXmpExport()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val longSide = remember(configuration) {
        with(density) { CullImages.screenLongSide(configuration.screenWidthDp.dp.roundToPx(), configuration.screenHeightDp.dp.roundToPx()) }
    }
    val images = remember(vm) { vm.images(longSide) }
    var viewer by remember { mutableStateOf<Pair<List<ViewerStep>, ViewerStart>?>(null) }
    var dialog by remember { mutableStateOf<CullDialog?>(null) }
    var revealShot by remember { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.onPause() }
    LaunchedEffect(vm) {
        vm.messages.collect { event ->
            when (event) {
                CullEvent.SaveFailed -> toaster.show(res.getString(R.string.t_cull_save_failed))
            }
        }
    }

    /** "Edit in Reveal" (ADR 0010): shots with every file (RAW + JPEG), titled by the folder, with CULLING. */
    fun editInReveal(shots: List<Shot>, initial: Shot?) {
        scope.launch {
            vm.settle()
            reveal.edit(location, vm.key.path, revealOrder(shots.flatMap { s -> s.files.map { it.path } }), title, initial?.let { (it.raw ?: it.rendered)?.path })
        }
    }

    /** "Export XMP" of the folder's decided shots (ADR 0011 amendment "2.2.0"): the decisions saved first. */
    fun exportXmp() {
        scope.launch {
            vm.settle()
            val paths = view.core.shots.filter { view.core.decisions[it.id] != null }.flatMap { s -> s.files.map { it.path } }
            if (paths.isEmpty()) return@launch
            xmpExport.export(XmpExportRequest(location.place, vm.key.path, paths))
        }
    }

    /** Opens the viewer on [shotId]; a frame of a burst (a stack, an expanded frame) opens burst mode on it. */
    fun openShot(shotId: String, intoBurst: Boolean) {
        val steps = viewerSteps(view.visible.map { it.id }, view.bursts, view.members, view.collapse)
        val step = stepOf(steps, shotId).coerceAtLeast(0)
        val burst = view.bursts[shotId]?.burst
        val members = burst?.let { view.members[it] }
        viewer = steps to ViewerStart(step, if (intoBurst && burst != null && members != null && members.size >= 2) BurstStart(burst, members, shotId) else null)
    }

    fun resume() {
        val visible = view.visible
        if (visible.isEmpty()) return
        val ids = visible.map { it.id }
        val last = view.core.session?.lastShotId?.let { ids.indexOf(it) } ?: -1
        val next = nextUnmarked(visible, view.core.decisions, if (last >= 0) last - 1 else -1)
        val at = if (next >= 0) next else last.coerceAtLeast(0)
        openShot(ids[at], view.collapse && next >= 0)
    }

    val ready = view.core.status == CullStatus.Ready && view.core.shots.isNotEmpty()
    HfpsScaffold(
        topBar = {
            HfpsTopBar(title = title, onBack = onBack, titleIsFileName = true) {
                if (ready) {
                    HfpsIconButton(
                        icon = PhosphorFill.Play,
                        contentDescription = stringResource(if (view.progress.decided > 0) R.string.t_cull_resume else R.string.t_cull_start),
                        onClick = ::resume,
                        enabled = view.visible.isNotEmpty(),
                        active = true,
                        modifier = Modifier.testTag("cull-start"),
                    )
                    HfpsIconButton(
                        icon = if (view.controls.suggesting) PhosphorFill.Sparkle else PhosphorRegular.Sparkle,
                        contentDescription = stringResource(R.string.t_cull_assist_suggest),
                        active = view.controls.suggesting,
                        onClick = { vm.setSuggesting(!view.controls.suggesting) },
                        modifier = Modifier.testTag("cull-suggest"),
                    )
                    FinishMenu(view, reveal.available, onCopy = { dialog = CullDialog.Copy }, onDelete = { dialog = CullDialog.Delete }, onReveal = { shots -> editInReveal(shots, null) }, onExportXmp = ::exportXmp)
                    CullMenu(view, vm, onSettings = { dialog = CullDialog.Settings })
                }
            }
        },
    ) {
        when {
            view.core.status == CullStatus.Loading -> GridSkeleton()
            view.core.status == CullStatus.Error -> {
                val code = view.core.errorCode ?: ErrorCode.UNKNOWN
                StateMessage(
                    PhosphorRegular.WarningCircle,
                    stringResource(errorTitle(code)),
                    Modifier.align(Alignment.Center),
                    body = stringResource(errorBody(code)),
                    tint = HfpsTheme.colors.destructive,
                ) { PrimaryButton(stringResource(R.string.t_common_retry), vm::reload) }
            }
            view.core.shots.isEmpty() -> StateMessage(PhosphorRegular.Images, stringResource(R.string.t_cull_empty_title), Modifier.align(Alignment.Center), body = stringResource(R.string.t_cull_empty_body))
            else -> {
                // Closing the viewer keeps the shot that was last viewed in view.
                LaunchedEffect(revealShot) {
                    val id = revealShot ?: return@LaunchedEffect
                    var index = view.items.indexOfFirst { it.shotId == id }
                    val burst = view.bursts[id]?.burst
                    if (index < 0 && burst != null) index = view.items.indexOfFirst { it is GridItem.Stack && it.burst == burst }
                    if (index >= 0) grid.animateScrollToItem(index + 1)
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    state = grid,
                    modifier = Modifier.fillMaxSize().testTag("cull-grid"),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item(key = "toolbar", span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Toolbar(view, vm, onSettings = { dialog = CullDialog.Settings })
                            if (view.controls.suggesting) SuggestionBar(view, onAcceptAll = { accept(vm, view.suggestions.keys.toList(), null, toaster, res) }, onHide = { vm.setSuggesting(false) })
                        }
                    }
                    if (view.visible.isEmpty()) {
                        item(key = "nomatch", span = { GridItemSpan(maxLineSpan) }) {
                            StateMessage(PhosphorRegular.Funnel, stringResource(R.string.t_cull_no_match_title), body = stringResource(R.string.t_cull_no_match_body))
                        }
                    }
                    items(view.items, key = { if (it is GridItem.Stack) "stack:${it.burst}" else it.shotId }) { item ->
                        GridCell(view, images, item, onOpen = ::openShot, onToggleBurst = vm::toggleBurst, onServer = item.shotId in serverShots)
                    }
                }
            }
        }
    }

    viewer?.let { (steps, start) ->
        CullViewer(
            vm = vm,
            view = view,
            images = images,
            steps = steps,
            start = start,
            onEditInReveal = if (reveal.available) ({ shot -> editInReveal(listOf(shot), shot) }) else null,
            onAccept = { ids, from -> accept(vm, ids, from, toaster, res) },
            onClose = { last ->
                vm.focus(null)
                viewer = null
                revealShot = last
            },
        )
    }
    when (dialog) {
        CullDialog.Settings -> AssistSettingsSheet(container, onDismiss = { dialog = null })
        CullDialog.Copy -> CopyPicksDialog(container, vm, view, location, onDismiss = { dialog = null }, onTransfers = onTransfers)
        CullDialog.Delete -> DeleteRejectsDialog(vm, view, location, onDismiss = { dialog = null })
        null -> Unit
    }
    reveal.Dialogs()
    xmpExport.Dialogs()
}

/** Accepts suggestions as one undo step, with "Undo" on the toast. */
fun accept(vm: CullViewModel, ids: List<String>, from: Collection<dev.hfps.hoard.app.cull.Suggestion>?, toaster: dev.hfps.hoard.app.ui.common.Toaster, res: android.content.res.Resources): Int {
    val count = vm.accept(ids, from)
    if (count > 0) toaster.show(res.getQuantityString(R.plurals.t_cull_assist_accepted, count, count.toString()), action = res.getString(R.string.t_cull_undo)) { vm.undo() }
    return count
}

@Composable
private fun GridSkeleton() {
    LazyVerticalGrid(GridCells.Adaptive(112.dp), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp), userScrollEnabled = false) {
        items(18) { Box(Modifier.aspectRatio(1f).background(HfpsTheme.colors.muted)) }
    }
}

// --- Header -------------------------------------------------------------------------------------------------------

@Composable
private fun FinishMenu(view: CullView, revealAvailable: Boolean, onCopy: () -> Unit, onDelete: () -> Unit, onReveal: (List<Shot>) -> Unit, onExportXmp: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HfpsIconButton(PhosphorRegular.FlagCheckered, stringResource(R.string.t_cull_finish), { open = true }, Modifier.testTag("cull-finish"))
        DropdownMenu(open, { open = false }, containerColor = HfpsTheme.colors.card) {
            val p = view.progress
            MenuItem(pluralStringResource(R.plurals.t_cull_copy_menu, p.picked, p.picked.toString()), PhosphorRegular.Copy, { open = false; onCopy() }, enabled = p.picked > 0)
            if (revealAvailable) {
                val picks = view.picks
                MenuItem(pluralStringResource(R.plurals.t_reveal_edit_picks, picks.size, picks.size.toString()), PhosphorRegular.SlidersHorizontal, { open = false; onReveal(picks) }, enabled = picks.isNotEmpty())
                MenuItem(pluralStringResource(R.plurals.t_reveal_edit_shown, view.visible.size, view.visible.size.toString()), PhosphorRegular.SlidersHorizontal, { open = false; onReveal(view.visible) }, enabled = view.visible.isNotEmpty())
            }
            MenuItem(stringResource(R.string.xmp_export_decided), PhosphorRegular.FileText, { open = false; onExportXmp() }, enabled = p.decided > 0)
            MenuItem(
                if (view.readOnly) stringResource(R.string.t_cull_delete_read_only) else pluralStringResource(R.plurals.t_cull_delete_menu, p.rejected, p.rejected.toString()),
                PhosphorRegular.Trash,
                { open = false; onDelete() },
                enabled = p.rejected > 0 && !view.readOnly,
                destructive = true,
            )
        }
    }
}

/** The grid's filters and sort as menu items (Hoard 1's compact bar ⋯ while the filter rows are scrolled away). */
@Composable
private fun CullMenu(view: CullView, vm: CullViewModel, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<String?>(null) }
    Box {
        HfpsIconButton(PhosphorRegular.DotsThreeVertical, stringResource(R.string.t_common_more), { open = true; sub = null })
        DropdownMenu(open, { open = false }, containerColor = HfpsTheme.colors.card) {
            when (sub) {
                null -> {
                    if (view.controls.suggesting) {
                        MenuItem(pluralStringResource(R.plurals.t_cull_assist_accept_all, view.suggestions.size, view.suggestions.size.toString()), PhosphorRegular.Sparkle, {
                            open = false
                            vm.accept(view.suggestions.keys.toList())
                        }, enabled = view.suggestions.isNotEmpty())
                    }
                    MenuItem(stringResource(R.string.t_cull_filter_label), PhosphorRegular.Funnel, { sub = "flag" })
                    MenuItem(stringResource(R.string.t_cull_filter_stars), PhosphorRegular.Star, { sub = "stars" })
                    MenuItem(stringResource(R.string.t_cull_assist_filter_label), PhosphorRegular.Sparkle, { sub = "assist" })
                    MenuItem(stringResource(R.string.t_cull_assist_sort_label), PhosphorRegular.SortAscending, { sub = "sort" })
                    MenuItem(
                        stringResource(R.string.t_cull_assist_stack),
                        PhosphorRegular.Stack,
                        { vm.setStack(!view.controls.stack) },
                        enabled = view.controls.sort == CullSort.Time,
                        trailing = { if (view.controls.stack && view.controls.sort == CullSort.Time) Icon(PhosphorRegular.Check, null, Modifier.size(18.dp), tint = HfpsTheme.colors.primary) },
                    )
                    MenuItem(stringResource(R.string.t_cull_assist_settings), PhosphorRegular.Gear, { open = false; onSettings() })
                }
                "flag" -> FlagFilter.entries.forEach { f ->
                    Choice(flagLabel(view, f), view.controls.filter.flag == f) { vm.setFilter(view.controls.filter.copy(flag = f)); open = false }
                }
                "stars" -> (0..5).forEach { n ->
                    Choice(starsLabel(n), view.controls.filter.minStars == n) { vm.setFilter(view.controls.filter.copy(minStars = n)); open = false }
                }
                "assist" -> AssistFilter.ALL.forEach { f ->
                    Choice(assistLabel(view, f), view.controls.assistFilter == f) { vm.setAssistFilter(f); open = false }
                }
                "sort" -> CullSort.entries.forEach { s ->
                    Choice(stringResource(if (s == CullSort.Time) R.string.t_cull_assist_sort_time else R.string.t_cull_assist_sort_sharpness), view.controls.sort == s) { vm.setSort(s); open = false }
                }
            }
        }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    MenuItem(text, if (selected) PhosphorRegular.RadioButton else PhosphorRegular.Circle, onClick)
}

@Composable
fun flagLabel(view: CullView, f: FlagFilter): String {
    val p = view.progress
    val count = when (f) {
        FlagFilter.All -> p.total
        FlagFilter.Picked -> p.picked
        FlagFilter.Rejected -> p.rejected
        FlagFilter.Unmarked -> p.unmarked
    }
    return stringResource(flagFilterText(f)) + " · " + formatNumber(count, locale())
}

@Composable
fun starsLabel(n: Int): String = if (n == 0) stringResource(R.string.t_cull_filter_any_stars) else pluralStringResource(R.plurals.t_cull_filter_min_stars, n, n.toString())

@Composable
fun assistLabel(view: CullView, f: AssistFilter): String =
    if (f == AssistFilter.Any) stringResource(R.string.t_cull_assist_filter_any) else stringResource(assistFilterText(f)) + " · " + formatNumber(view.assistCounts[f] ?: 0, locale())

// --- Toolbar ------------------------------------------------------------------------------------------------------

/** Progress, filters and the analysis status above the grid (they scroll away with it). */
@Composable
private fun Toolbar(view: CullView, vm: CullViewModel, onSettings: () -> Unit) {
    val loc = locale()
    val p = view.progress
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.t_cull_progress, formatNumber(p.decided, loc), formatNumber(p.total, loc), formatNumber(p.picked, loc), formatNumber(p.rejected, loc)),
            style = HfpsTheme.typography.ui,
            color = HfpsTheme.colors.mutedForeground,
            modifier = Modifier.testTag("cull-progress"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Select(flagLabel(view, view.controls.filter.flag), FlagFilter.entries.map { it to flagLabel(view, it) }, testTag = "cull-filter-flag") { vm.setFilter(view.controls.filter.copy(flag = it)) }
            Select(starsLabel(view.controls.filter.minStars), (0..5).map { it to starsLabel(it) }) { vm.setFilter(view.controls.filter.copy(minStars = it)) }
            Select(assistLabel(view, view.controls.assistFilter), AssistFilter.ALL.map { it to assistLabel(view, it) }, testTag = "cull-filter-assist") { vm.setAssistFilter(it) }
            Select(
                stringResource(if (view.controls.sort == CullSort.Time) R.string.t_cull_assist_sort_time else R.string.t_cull_assist_sort_sharpness),
                listOf(CullSort.Time to stringResource(R.string.t_cull_assist_sort_time), CullSort.Sharpness to stringResource(R.string.t_cull_assist_sort_sharpness)),
            ) { vm.setSort(it) }
            Row {
                HfpsIconButton(
                    icon = if (view.controls.stack && view.controls.sort == CullSort.Time) PhosphorFill.Stack else PhosphorRegular.Stack,
                    contentDescription = stringResource(R.string.t_cull_assist_stack),
                    active = view.controls.stack && view.controls.sort == CullSort.Time,
                    enabled = view.controls.sort == CullSort.Time,
                    onClick = { vm.setStack(!view.controls.stack) },
                )
                HfpsIconButton(PhosphorRegular.Gear, stringResource(R.string.t_cull_assist_settings), onSettings)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinearProgressIndicator(
                progress = { if (p.total > 0) p.decided.toFloat() / p.total else 0f },
                modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).semantics { contentDescription = "progress" },
                color = HfpsTheme.colors.primary,
                trackColor = HfpsTheme.colors.muted,
                drawStopIndicator = {},
            )
            Text("${if (p.total > 0) Math.round(p.decided * 100.0 / p.total) else 0}%", style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            val a = view.analysis
            val status = when {
                !view.core.metadataDone -> stringResource(R.string.t_cull_reading_exif)
                a.analysing -> stringResource(R.string.t_cull_assist_analysing, formatNumber(a.done, loc), formatNumber(a.total, loc))
                a.failed > 0 -> pluralStringResource(R.plurals.t_cull_assist_failed, a.failed, a.failed.toString())
                else -> stringResource(R.string.t_cull_assist_analysed)
            }
            if (view.core.metadataDone && a.analysing && a.running) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = HfpsTheme.colors.mutedForeground)
            Text(status, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, maxLines = 1, modifier = Modifier.testTag("cull-analysis"))
        }
    }
}

/** A select (Hoard 1's Select): the current label, a menu of options. */
@Composable
fun <T> Select(label: String, options: List<Pair<T, String>>, testTag: String? = null, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(HfpsTheme.colors.muted)
                .combinedClickable(onClick = { open = true })
                .padding(horizontal = 12.dp)
                .let { if (testTag != null) it.testTag(testTag) else it },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.foreground, maxLines = 1)
            Icon(PhosphorRegular.CaretDown, null, Modifier.size(14.dp), tint = HfpsTheme.colors.mutedForeground)
        }
        DropdownMenu(open, { open = false }, containerColor = HfpsTheme.colors.card) {
            options.forEach { (value, text) -> MenuItem(text, null, { open = false; onSelect(value) }) }
        }
    }
}

/** Under the toolbar while suggestions show: what they would do, Accept all, Hide. */
@Composable
private fun SuggestionBar(view: CullView, onAcceptAll: () -> Unit, onHide: () -> Unit) {
    val keep = view.suggestions.values.count { it.action == SuggestionAction.Keep }
    val reject = view.suggestions.size - keep
    val loc = locale()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp).testTag("cull-suggestions"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(PhosphorFill.Sparkle, null, Modifier.size(20.dp), tint = HfpsTheme.colors.primary)
            Column(Modifier.weight(1f)) {
                Text(
                    if (view.suggestions.isEmpty()) stringResource(R.string.t_cull_assist_suggest_none) else stringResource(R.string.t_cull_assist_suggest_summary, formatNumber(keep, loc), formatNumber(reject, loc)),
                    style = HfpsTheme.typography.uiMedium,
                    color = HfpsTheme.colors.foreground,
                )
                Text(
                    stringResource(if (view.analysis.analysing) R.string.t_cull_assist_suggest_partial else R.string.t_cull_assist_suggest_hint),
                    style = HfpsTheme.typography.ui,
                    color = HfpsTheme.colors.mutedForeground,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(pluralStringResource(R.plurals.t_cull_assist_accept_all, view.suggestions.size, view.suggestions.size.toString()), onAcceptAll, Modifier.weight(1f), enabled = view.suggestions.isNotEmpty())
            SecondaryButton(stringResource(R.string.t_cull_assist_dismiss), onHide)
        }
    }
}

// --- Grid ---------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCell(view: CullView, images: CullImages, item: GridItem, onOpen: (String, Boolean) -> Unit, onToggleBurst: (String) -> Unit, onServer: Boolean = false) {
    val shot = view.byId[item.shotId] ?: return
    val serverLabel = stringResource(R.string.gallery_badge_server)
    val decision = view.core.decisions.of(shot.id)
    val burst = view.bursts[shot.id]
    val suggestion = if (view.controls.suggesting) view.suggestions[shot.id] else null
    val stack = item as? GridItem.Stack
    val status = stack?.let { burstStatus(view.members[it.burst].orEmpty(), view.core.decisions) }
    val expanded = burst != null && burst.burst in view.controls.expanded
    val label = stringResource(
        R.string.t_cull_cell_label,
        shot.stem,
        stringResource(kindText(shot.kind)),
        stringResource(
            when (decision.flag) {
                Flag.Pick -> R.string.t_cull_flag_picked
                Flag.Reject -> R.string.t_cull_flag_rejected
                null -> R.string.t_cull_flag_unmarked
            },
        ),
    )
    val alpha = when {
        decision.flag == Flag.Reject -> 0.4f
        suggestion?.action == SuggestionAction.Reject && decision.flag == null -> 0.6f
        else -> 1f
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .testTag("cell-${shot.id}")
            .semantics { contentDescription = label }
            .combinedClickable(
                onClick = { onOpen(shot.id, stack != null || (view.collapse && expanded)) },
                onLongClick = {
                    // Long press on a burst expands or stacks it.
                    val target = stack?.burst ?: if (view.collapse) burst?.burst else null
                    if (target != null) onToggleBurst(target)
                },
            ),
    ) {
        ShotThumb(images, shot, Modifier.fillMaxSize(), alpha)
        if (status != null) Box(Modifier.fillMaxSize().outline(if (status.resolved) HfpsTheme.colors.primary else HfpsTheme.colors.warning.copy(alpha = 0.8f)))
        if (suggestion != null) Box(Modifier.fillMaxSize().dashedBorder(if (suggestion.action == SuggestionAction.Keep) HfpsTheme.colors.primary else HfpsTheme.colors.destructive, 0.dp))
        Column(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
            FlagMark(decision)
            FlagMarks(view.flags[shot.id], burst != null && shot.id in view.bestShots)
        }
        if (suggestion != null) SuggestionMark(suggestion, Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 28.dp))
        Row(Modifier.align(Alignment.BottomStart).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            KindBadge(shot)
            // ADR 0022: the paired server holds this shot (a mapped SMB folder).
            if (onServer) Badge("", Modifier.testTag("badge-server").semantics { contentDescription = serverLabel }, icon = PhosphorRegular.Cloud)
        }
        StarsMark(decision.rating, Modifier.align(Alignment.BottomEnd).padding(4.dp))
        when {
            stack != null && status != null -> {
                val resolved = status.resolved
                val bg = if (resolved) HfpsTheme.colors.primary else HfpsTheme.colors.warning
                val fg = if (resolved) HfpsTheme.colors.primaryForeground else ViewerColors.background
                val text = if (resolved) stringResource(R.string.t_cull_burst_mode_resolved_short, status.kept.toString(), stack.size.toString()) else stack.size.toString()
                val description = pluralStringResource(R.plurals.t_cull_assist_expand, stack.size, stack.size.toString()) + " · " +
                    if (resolved) stringResource(R.string.t_cull_burst_mode_resolved, status.kept.toString(), stack.size.toString()) else stringResource(R.string.t_cull_burst_mode_pending, status.decided.toString(), stack.size.toString())
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(bg)
                        .combinedClickable(onClick = { onToggleBurst(stack.burst) })
                        .padding(horizontal = 6.dp)
                        .semantics { contentDescription = description }
                        .testTag("stack-${stack.burst}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(if (resolved) PhosphorRegular.Check else PhosphorFill.Stack, null, Modifier.size(14.dp), tint = fg)
                    Text(text, style = HfpsTheme.typography.uiMedium, color = fg)
                    Icon(PhosphorRegular.CaretDown, null, Modifier.size(12.dp), tint = fg)
                }
            }
            burst != null && view.collapse && burst.index == 0 && expanded -> {
                val collapseLabel = stringResource(R.string.t_cull_assist_collapse)
                Box(Modifier.align(Alignment.TopStart).padding(4.dp).combinedClickable(onClick = { onToggleBurst(burst.burst) }).semantics { contentDescription = collapseLabel }) {
                    BurstMark(burst, background = HfpsTheme.colors.primary, foreground = HfpsTheme.colors.primaryForeground)
                }
            }
            burst != null -> BurstMark(burst, Modifier.align(Alignment.TopStart).padding(4.dp))
        }
    }
}
