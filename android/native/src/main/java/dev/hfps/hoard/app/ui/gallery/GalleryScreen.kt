package dev.hfps.hoard.app.ui.gallery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.gallery.GalleryEntry
import dev.hfps.hoard.app.gallery.GalleryLayout
import dev.hfps.hoard.app.gallery.GalleryPermissions
import dev.hfps.hoard.app.gallery.ThumbSource
import dev.hfps.hoard.app.gallery.GridRow
import dev.hfps.hoard.app.gallery.Kind
import dev.hfps.hoard.app.gallery.Source
import dev.hfps.hoard.app.ui.common.StateMessage
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorFill
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.sync.SyncEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** What the Gallery grid shows (stateless, for tests and previews). */
data class GalleryUi(
    val layout: GalleryLayout?,
    val filter: dev.hfps.hoard.app.gallery.GalleryFilter = dev.hfps.hoard.app.gallery.GalleryFilter(),
    val columns: Int = GalleryViewModel.DEFAULT_COLUMNS,
    val permission: Boolean = true,
    val account: dev.hfps.hoard.server.db.ServerAccountEntity? = null,
    val status: dev.hfps.hoard.server.sync.SyncStatus = dev.hfps.hoard.server.sync.SyncStatus(),
    val total: Int = layout?.entries?.size ?: 0,
    val dismissed: Set<String> = emptySet(),
)

/** Tab 1 (ADR 0021): one grid of the phone's shots and the server's items, newest first. */
@Composable
fun GalleryScreen(container: AppContainer, onOpenSettings: () -> Unit, onPair: () -> Unit, onOpenTrash: (() -> Unit)? = null) {
    val vm: GalleryViewModel = viewModel(factory = GalleryViewModelFactory(container))
    val layout by vm.layout.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    val permission by vm.permission.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val dismissed by vm.dismissed.collectAsStateWithLifecycle()
    val open by vm.open.collectAsStateWithLifecycle()
    val lastViewed by vm.lastViewed.collectAsStateWithLifecycle()
    var filters by remember { mutableStateOf(false) }
    val grid = vm.grid

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPermission() }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshPermission() }

    GalleryContent(
        ui = GalleryUi(layout, filter, columns, permission, account, status, total, dismissed),
        thumbs = container.gallery.thumbs,
        grid = grid,
        onOpenSettings = onOpenSettings,
        onOpenTrash = onOpenTrash,
        onPair = onPair,
        onAskPermission = { askMedia.launch(GalleryPermissions.permissions()) },
        onQuery = vm::setQuery,
        onFilters = { filters = true },
        onClearFilters = { vm.setFilter(dev.hfps.hoard.app.gallery.GalleryFilter(query = filter.query)) },
        onColumns = vm::setColumns,
        onOpen = vm::openViewer,
        onRefresh = vm::syncNow,
        onDismiss = vm::dismiss,
    )

    KeepGridPosition(grid, layout, anchor = { vm.anchor }, setAnchor = { vm.anchor = it }, lastViewed = lastViewed, onRevealed = { vm.lastViewed.value = null })

    androidx.compose.runtime.DisposableEffect(open != null) {
        GalleryChrome.viewerOpen = open != null
        onDispose { GalleryChrome.viewerOpen = false }
    }
    open?.let { request ->
        GalleryViewer(container, vm, request, onClose = { last -> vm.closeViewer(last) })
    }
    if (filters) GalleryFilterSheet(vm, onDismiss = { filters = false })
}

/**
 * The grid stays on its shot (2.2.2): after a reload (a sync adding shots above, a decision changing a filtered list,
 * a new process) the anchor's row is found again by key ([GridAnchors]); back from the viewer, the shot it closed on
 * (the one opened, or the one swiped to) is brought into view when it is not on screen.
 */
@Composable
fun KeepGridPosition(grid: LazyGridState, layout: GalleryLayout?, anchor: () -> GridAnchor?, setAnchor: (GridAnchor) -> Unit, lastViewed: String?, onRevealed: () -> Unit) {
    LaunchedEffect(grid, layout) {
        val current = layout ?: return@LaunchedEffect
        if (!grid.isScrollInProgress) {
            GridAnchors.restore(current, anchor(), grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)?.let { (row, offset) -> grid.scrollToItem(row, offset) }
        }
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            GridAnchors.at(current, index, offset)?.let(setAnchor)
        }
    }
    LaunchedEffect(grid, lastViewed, layout) {
        val key = lastViewed ?: return@LaunchedEffect
        val current = layout ?: return@LaunchedEffect
        if (current.rows.isEmpty()) return@LaunchedEffect
        val info = snapshotFlow { grid.layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
        fun fully(i: androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo) = i.visibleItemsInfo
            .filter { it.offset.y >= i.viewportStartOffset && it.offset.y + it.size.height <= i.viewportEndOffset }.map { it.index }.toSet()
        val columns = info.visibleItemsInfo.groupingBy { it.row }.eachCount().values.maxOrNull() ?: 1
        val lines = info.visibleItemsInfo.map { it.row }.distinct().size
        GridAnchors.reveal(current, key, fully(info), columns, lines)?.let { target ->
            grid.scrollToItem(target)
            val row = current.rowOf(key)
            if (row !in fully(grid.layoutInfo)) grid.scrollToItem(row)
        }
        onRevealed()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryContent(
    ui: GalleryUi,
    thumbs: ThumbSource,
    grid: LazyGridState = rememberLazyGridState(),
    onOpenSettings: () -> Unit = {},
    /** The trash (ADR 0023); no button when null. */
    onOpenTrash: (() -> Unit)? = null,
    onPair: () -> Unit = {},
    onAskPermission: () -> Unit = {},
    onQuery: (String) -> Unit = {},
    onFilters: () -> Unit = {},
    onClearFilters: () -> Unit = {},
    onColumns: (Int) -> Unit = {},
    onOpen: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
    onDismiss: (String) -> Unit = {},
    scroller: FastScrollerState = rememberFastScrollerState(),
) {
    var searching by remember { mutableStateOf(ui.filter.query.isNotEmpty()) }
    var goTo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val filter = ui.filter
    HfpsScaffold(
        topBar = {
            if (searching) {
                SearchBar(filter.query, onQuery = onQuery, onClose = {
                    searching = false
                    onQuery("")
                })
            } else {
                HfpsTopBar(title = stringResource(R.string.gallery_title)) {
                    HfpsIconButton(PhosphorRegular.MagnifyingGlass, stringResource(R.string.gallery_search), { searching = true }, modifier = Modifier.testTag("gallery-search"))
                    if ((ui.layout?.marks?.size ?: 0) > 1) HfpsIconButton(PhosphorRegular.CalendarBlank, stringResource(R.string.gallery_goto), { goTo = true }, modifier = Modifier.testTag("gallery-goto-open"))
                    Box {
                        HfpsIconButton(if (filter.active) PhosphorFill.Funnel else PhosphorRegular.Funnel, stringResource(R.string.gallery_filters), onFilters, active = filter.active, modifier = Modifier.testTag("gallery-filters"))
                        if (filter.count > 0) {
                            Text(
                                filter.count.toString(),
                                style = HfpsTheme.typography.ui.copy(fontSize = 10.sp),
                                color = HfpsTheme.colors.primaryForeground,
                                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).clip(CircleShape).background(HfpsTheme.colors.primary).padding(horizontal = 5.dp),
                            )
                        }
                    }
                    if (onOpenTrash != null) HfpsIconButton(PhosphorRegular.Trash, stringResource(R.string.trash_title), onOpenTrash, modifier = Modifier.testTag("gallery-trash"))
                    HfpsIconButton(PhosphorRegular.Gear, stringResource(R.string.action_settings), onOpenSettings)
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Banner(ui.account, ui.status, ui.dismissed, onPair = onPair, onSettings = onOpenSettings, onDismiss = onDismiss)
            val current = ui.layout
            when {
                current == null -> Box(Modifier.fillMaxSize()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = HfpsTheme.colors.primary, strokeWidth = 2.dp)
                }
                ui.total == 0 && !ui.permission -> StateMessage(
                    PhosphorRegular.Images,
                    stringResource(R.string.gallery_permission_title),
                    body = stringResource(R.string.gallery_permission_text),
                ) { PrimaryButton(stringResource(R.string.gallery_permission_action), onAskPermission, modifier = Modifier.testTag("gallery-permission")) }
                ui.total == 0 -> StateMessage(PhosphorRegular.Images, stringResource(R.string.gallery_empty_title), body = stringResource(R.string.gallery_empty_text))
                current.rows.isEmpty() -> StateMessage(PhosphorRegular.Funnel, stringResource(R.string.gallery_no_match_title), body = stringResource(R.string.gallery_no_match_text)) {
                    GhostButton(stringResource(R.string.gallery_filters_clear), onClearFilters)
                }
                else -> PullToRefreshBox(
                    isRefreshing = ui.status.running,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    GalleryGrid(
                        layout = current,
                        thumbs = thumbs,
                        columns = ui.columns,
                        grid = grid,
                        onColumns = onColumns,
                        onOpen = onOpen,
                        scroller = scroller,
                    )
                }
            }
        }
    }
    val current = ui.layout
    if (goTo && current != null) GoToMonthSheet(current, onGo = { row -> scope.launch { grid.scrollToItem(row) } }, onDismiss = { goTo = false })
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HfpsIconButton(PhosphorRegular.ArrowLeft, stringResource(R.string.gallery_search_close), onClose)
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.gallery_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = dev.hfps.hoard.app.ui.common.fieldColors(),
            modifier = Modifier.weight(1f).padding(end = 8.dp).focusRequester(focus).testTag("gallery-search-field"),
        )
    }
}

/** One line above the grid: pair the server, it refused us, it is offline, or it is syncing. */
@Composable
private fun Banner(
    account: dev.hfps.hoard.server.db.ServerAccountEntity?,
    status: dev.hfps.hoard.server.sync.SyncStatus,
    dismissed: Set<String>,
    onPair: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: (String) -> Unit,
) {
    val c = HfpsTheme.colors
    @Composable
    fun Line(kind: String, text: String, action: String?, onAction: (() -> Unit)?, tint: Color = c.mutedForeground) {
        if (kind in dismissed) return
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(c.card).padding(start = 12.dp).testTag("gallery-banner-$kind"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = HfpsTheme.typography.ui, color = tint, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (action != null && onAction != null) GhostButton(action, onAction)
            HfpsIconButton(PhosphorRegular.X, stringResource(R.string.gallery_sync_dismiss), { onDismiss(kind) })
        }
    }
    when {
        account == null -> Line("unpaired", stringResource(R.string.gallery_sync_unpaired), stringResource(R.string.gallery_sync_unpaired_action), onPair)
        account.lastError == SyncEngine.ERROR_UNAUTHORIZED -> Line("revoked", stringResource(R.string.gallery_sync_revoked), stringResource(R.string.action_settings), onSettings, c.destructive)
        status.running -> {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                Text(stringResource(R.string.gallery_sync_running, account.serverName ?: "hoard-server"), style = HfpsTheme.typography.ui, color = c.mutedForeground)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp), color = c.primary, trackColor = c.muted)
            }
        }
        account.lastError == SyncEngine.ERROR_OFFLINE -> Line("offline", stringResource(R.string.gallery_sync_offline), null, null)
        else -> Unit
    }
}

/** Cell bitmap size buckets, so densities share decoded thumbnails. */
private fun thumbPx(cellPx: Int): Int = when {
    cellPx <= 160 -> 160
    cellPx <= 256 -> 256
    cellPx <= 384 -> 384
    else -> 512
}

@Composable
fun GalleryGrid(
    layout: GalleryLayout,
    thumbs: ThumbSource,
    columns: Int,
    grid: LazyGridState,
    onColumns: (Int) -> Unit,
    onOpen: (String) -> Unit,
    scroller: FastScrollerState = rememberFastScrollerState(),
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) { configuration.locales[0] }
    // Tablets and landscape get more columns at the same zoom.
    val widthDp = configuration.screenWidthDp
    val effective = (columns * (widthDp / 400f).coerceIn(1f, 3f)).roundToInt().coerceAtLeast(2)
    val cellPx = with(density) { (widthDp.dp / effective).roundToPx() }
    val px = thumbPx(cellPx)
    val texts = rememberCellTexts()
    val gridDescription = stringResource(R.string.gallery_grid_description)
    // Pinch: anchor the shot at the top so it stays in view while the grid changes.
    var anchor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(columns, layout) {
        val key = anchor ?: return@LaunchedEffect
        val row = layout.rowOf(key)
        if (row >= 0) grid.scrollToItem(row)
        anchor = null
    }
    fun zoom(by: Int) {
        val first = layout.rows.getOrNull(grid.firstVisibleItemIndex)
        anchor = (first as? GridRow.Cell)?.entry?.key ?: layout.rows.drop(grid.firstVisibleItemIndex).firstOrNull { it is GridRow.Cell }?.let { (it as GridRow.Cell).entry.key }
        onColumns(columns + by)
    }
    Box(
        Modifier
            .fillMaxSize()
            .fastScrollerInput(scroller, layout, grid, scope)
            .pointerInput(columns) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var scale = 1f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            scale *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                            if (scale > 1.3f) {
                                zoom(-1)
                                scale = 1f
                            } else if (scale < 0.77f) {
                                zoom(+1)
                                scale = 1f
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .semantics { contentDescription = gridDescription },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(effective),
            state = grid,
            modifier = Modifier.fillMaxSize().testTag("gallery-grid"),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            var i = 0
            val rows = layout.rows
            while (i < rows.size) {
                val row = rows[i]
                if (row is GridRow.Header) {
                    stickyHeader(key = row.id, contentType = "header") { DateHeader(row, locale) }
                    i++
                    continue
                }
                // The cells of one section.
                val start = i
                while (i < rows.size && rows[i] is GridRow.Cell) i++
                val section = rows.subList(start, i)
                items(section.size, key = { section[it].id }, contentType = { "cell" }) { n ->
                    val cell = section[n] as GridRow.Cell
                    GalleryCell(cell.entry, thumbs, px, compact = widthDp / effective < COMPACT_CELL_DP, onOpen = onOpen, texts = texts)
                }
            }
        }
        FastScroller(layout, grid, scroller, locale, Modifier.align(Alignment.CenterEnd))
    }
}

/** Formatted header texts, cached (a fling composes dozens of headers a second). */
private object HeaderTexts {
    private val days = java.util.concurrent.ConcurrentHashMap<Pair<LocalDate, Locale>, String>()
    private val months = java.util.concurrent.ConcurrentHashMap<Triple<Int, Int, Locale>, String>()
    private val dayFormats = java.util.concurrent.ConcurrentHashMap<Locale, DateTimeFormatter>()
    private val monthFormats = java.util.concurrent.ConcurrentHashMap<Locale, DateTimeFormatter>()

    fun day(day: LocalDate, locale: Locale): String = days.getOrPut(day to locale) {
        dayFormats.getOrPut(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }.format(day).replaceFirstChar { it.titlecase(locale) }
    }

    fun month(year: Int, month: Int, locale: Locale): String = months.getOrPut(Triple(year, month, locale)) {
        monthFormats.getOrPut(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }.format(LocalDate.of(year, month, 1)).replaceFirstChar { it.titlecase(locale) }
    }
}

private fun dayText(day: LocalDate, locale: Locale): String = HeaderTexts.day(day, locale)

private fun monthText(year: Int, month: Int, locale: Locale): String = HeaderTexts.month(year, month, locale)

@Composable
private fun DateHeader(row: GridRow.Header, locale: Locale) {
    val text = if (row.month) monthText(row.day.year, row.day.monthValue, locale) else dayText(row.day, locale)
    Row(
        Modifier.fillMaxWidth().background(HfpsTheme.colors.card).padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp).testTag("gallery-header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = HfpsTheme.typography.uiMedium, color = HfpsTheme.colors.foreground, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(row.count.toString(), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
    }
}

private val BadgeBg = Color(0x99000000)

/** Cells narrower than this show only the decision (a dot). */
private const val COMPACT_CELL_DP = 72

@Composable
private fun Badge(icon: ImageVector, description: String, tint: Color = Color.White, tag: String? = null) {
    Box(
        Modifier.padding(3.dp).size(20.dp).clip(CircleShape).background(BadgeBg).let { if (tag != null) it.testTag(tag) else it },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, Modifier.size(13.dp), tint = tint) }
}

@Composable
private fun Pill(text: String, tag: String? = null, icon: ImageVector? = null) {
    Row(
        Modifier.padding(3.dp).clip(RoundedCornerShape(6.dp)).background(BadgeBg).padding(horizontal = 5.dp, vertical = 1.dp).let { if (tag != null) it.testTag(tag) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(11.dp).padding(end = 2.dp), tint = Color.White)
        Text(text, style = HfpsTheme.typography.ui.copy(fontSize = 10.sp), color = Color.White, maxLines = 1)
    }
}

fun durationText(ms: Long): String {
    val s = (ms + 500) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The cells' fixed texts, resolved once per grid. */
class CellTexts(val device: String, val server: String, val both: String, val motion: String, val raw: String, val edited: String, val picked: String, val rejected: String)

@Composable
fun rememberCellTexts() = CellTexts(
    stringResource(R.string.gallery_badge_device), stringResource(R.string.gallery_badge_server), stringResource(R.string.gallery_badge_both),
    stringResource(R.string.gallery_badge_motion), stringResource(R.string.gallery_badge_raw), stringResource(R.string.gallery_badge_edited),
    stringResource(R.string.gallery_badge_picked), stringResource(R.string.gallery_badge_rejected),
)

@Composable
fun GalleryCell(entry: GalleryEntry, thumbs: ThumbSource, px: Int, compact: Boolean, onOpen: (String) -> Unit, texts: CellTexts = rememberCellTexts()) {
    val image: ImageBitmap? by produceState(thumbs.peek(entry, px), thumbs.key(entry, px)) {
        if (value == null) value = thumbs.load(entry, px)
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .background(HfpsTheme.colors.muted)
            .clickable { onOpen(entry.key) }
            .testTag("gallery-cell")
            .semantics { contentDescription = entry.name },
    ) {
        image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (compact) {
            // The densest zoom: only the decision, as a dot.
            when (entry.decision.flag) {
                "pick" -> Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(8.dp).clip(CircleShape).background(HfpsTheme.colors.primary))
                "reject" -> Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(8.dp).clip(CircleShape).background(HfpsTheme.colors.destructive))
            }
            return@Box
        }
        Row(Modifier.align(Alignment.TopStart)) {
            when (entry.source) {
                Source.Device -> Badge(PhosphorRegular.DeviceMobile, texts.device, tag = "badge-device")
                Source.Server -> Badge(PhosphorRegular.Cloud, texts.server, tag = "badge-server")
                Source.Both -> Badge(PhosphorFill.CloudCheck, texts.both, tag = "badge-both")
            }
        }
        Row(Modifier.align(Alignment.TopEnd)) {
            when (entry.kind) {
                Kind.Pair -> Pill("RAW+JPG", tag = "badge-pair")
                Kind.Motion -> Badge(PhosphorRegular.PlayCircle, texts.motion, tag = "badge-motion")
                Kind.Video -> Pill(entry.durationMs?.let(::durationText) ?: "", tag = "badge-video", icon = PhosphorFill.Play)
                Kind.Photo -> if (entry.hasRaw) Pill(texts.raw, tag = "badge-raw")
            }
        }
        Row(Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.CenterVertically) {
            if (entry.decision.rating > 0) {
                Row(Modifier.padding(3.dp).clip(RoundedCornerShape(6.dp)).background(BadgeBg).padding(horizontal = 4.dp, vertical = 1.dp).testTag("badge-rating"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(PhosphorFill.Star, null, Modifier.size(10.dp), tint = Color(0xFFFFC53D))
                    Text(entry.decision.rating.toString(), style = HfpsTheme.typography.ui.copy(fontSize = 10.sp), color = Color.White, modifier = Modifier.padding(start = 2.dp))
                }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd)) {
            if (entry.edited) Badge(PhosphorRegular.MagicWand, texts.edited, tag = "badge-edited")
            when (entry.decision.flag) {
                "pick" -> Badge(PhosphorFill.CheckCircle, texts.picked, tint = Color(0xFF7DD3A8), tag = "badge-pick")
                "reject" -> Badge(PhosphorFill.XCircle, texts.rejected, tint = Color(0xFFFF8A80), tag = "badge-reject")
            }
        }
    }
}

/**
 * The fast scroller (2.2.2): a thumb on the right edge that shows while the grid moves and fades out when it stops.
 * Only a touch that starts on the thumb, inside a 24 dp strip at the edge, drags it ([ScrollerGeometry.onThumb]); any
 * other touch, the strip included, scrolls the grid as usual, so a flick near the edge never jumps across the library.
 * Dragging scrubs, with the month in a bubble and the years along the track.
 */
@Stable
class FastScrollerState(visible: Boolean = false, val hideAfterMs: Long = 1500) {
    /** Shown (the grid moved lately, or the thumb is held). */
    var visible by mutableStateOf(visible)
    var dragging by mutableStateOf(false)
    /** The thumb's top while dragging, as a fraction of its travel (0 = first row, 1 = last). */
    var dragFraction by mutableFloatStateOf(0f)
}

@Composable
fun rememberFastScrollerState() = remember { FastScrollerState() }

/** The scroller's geometry in the grid's coordinates (px), shared by the drawing and the touch handling. */
object ScrollerGeometry {
    /** Rows below which there is no scroller. */
    const val MIN_ROWS = 60
    val STRIP = 24.dp
    val THUMB_HEIGHT = 44.dp
    val THUMB_WIDTH = 8.dp
    val PADDING = 12.dp
    /** Extra height around the thumb that still grabs it (fingers are wider than 44 dp). */
    val SLOP = 8.dp

    /** The thumb's top for [fraction] of the travel, in a grid [height] px tall. */
    fun thumbTop(fraction: Float, height: Float, thumb: Float, padding: Float): Float = padding + fraction.coerceIn(0f, 1f) * (height - 2 * padding - thumb).coerceAtLeast(0f)

    /** The travel fraction for a thumb whose top is at [top]. */
    fun fractionAt(top: Float, height: Float, thumb: Float, padding: Float): Float {
        val travel = (height - 2 * padding - thumb).coerceAtLeast(1f)
        return ((top - padding) / travel).coerceIn(0f, 1f)
    }

    /** Whether a touch at ([x], [y]) starts on the shown thumb: inside the edge strip and the thumb's height (± slop). */
    fun onThumb(x: Float, y: Float, width: Float, height: Float, fraction: Float, visible: Boolean, strip: Float, thumb: Float, padding: Float, slop: Float): Boolean {
        if (!visible || x < width - strip || x > width) return false
        val top = thumbTop(fraction, height, thumb, padding)
        return y >= top - slop && y <= top + thumb + slop
    }

    /** Where the grid is, as a fraction of its rows. */
    fun fractionOf(firstIndex: Int, rows: Int): Float = firstIndex.toFloat() / (rows - 1).coerceAtLeast(1)

    fun rowAt(fraction: Float, rows: Int): Int = (fraction.coerceIn(0f, 1f) * (rows - 1)).roundToInt()
}

/** The thumb's drag, on the grid's own box (Initial pass): a touch not on the thumb is left to the grid untouched. */
private fun Modifier.fastScrollerInput(state: FastScrollerState, layout: GalleryLayout, grid: LazyGridState, scope: kotlinx.coroutines.CoroutineScope): Modifier =
    if (layout.rows.size < ScrollerGeometry.MIN_ROWS) this else pointerInput(layout, grid, state) {
        val rows = layout.rows.size
        val strip = ScrollerGeometry.STRIP.toPx()
        val thumb = ScrollerGeometry.THUMB_HEIGHT.toPx()
        val padding = ScrollerGeometry.PADDING.toPx()
        val slop = ScrollerGeometry.SLOP.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val fraction = ScrollerGeometry.fractionOf(grid.firstVisibleItemIndex, rows)
            if (!ScrollerGeometry.onThumb(down.position.x, down.position.y, w, h, fraction, state.visible, strip, thumb, padding, slop)) return@awaitEachGesture
            down.consume()
            // The finger keeps its place on the thumb: grabbing never jumps.
            val grab = down.position.y - ScrollerGeometry.thumbTop(fraction, h, thumb, padding)
            state.dragFraction = fraction
            state.dragging = true
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    change.consume()
                    val next = ScrollerGeometry.fractionAt(change.position.y - grab, h, thumb, padding)
                    if (next != state.dragFraction) {
                        state.dragFraction = next
                        val target = ScrollerGeometry.rowAt(next, rows)
                        scope.launch { grid.scrollToItem(target) }
                    }
                }
            } finally {
                state.dragging = false
            }
        }
    }

@Composable
private fun FastScroller(layout: GalleryLayout, grid: LazyGridState, state: FastScrollerState, locale: Locale, modifier: Modifier) {
    val rows = layout.rows.size
    if (rows < ScrollerGeometry.MIN_ROWS) return
    LaunchedEffect(grid, state) {
        snapshotFlow { grid.isScrollInProgress || state.dragging }.collectLatest { moving ->
            if (moving) {
                state.visible = true
            } else if (state.hideAfterMs > 0) {
                delay(state.hideAfterMs)
                state.visible = false
            }
        }
    }
    val dragging = state.dragging
    val fraction = if (dragging) state.dragFraction else ScrollerGeometry.fractionOf(grid.firstVisibleItemIndex, rows)
    val mark = layout.markAt(ScrollerGeometry.rowAt(fraction, rows))
    val density = LocalDensity.current
    AnimatedVisibility(state.visible || dragging, modifier = modifier.fillMaxHeight().width(120.dp), enter = fadeIn(), exit = fadeOut()) {
        BoxWithConstraints(Modifier.fillMaxSize().testTag("gallery-scroller")) {
            val h = constraints.maxHeight.toFloat()
            val thumbPx = with(density) { ScrollerGeometry.THUMB_HEIGHT.toPx() }
            val padPx = with(density) { ScrollerGeometry.PADDING.toPx() }
            val thumbY = ScrollerGeometry.thumbTop(fraction, h, thumbPx, padPx).roundToInt()
            // Year ticks while dragging.
            if (dragging) {
                layout.marks.filter { it.yearStart }.forEach { m ->
                    val y = (padPx + m.row.toFloat() / (rows - 1) * (h - 2 * padPx - with(density) { 16.dp.toPx() })).roundToInt()
                    Text(
                        m.year.toString(),
                        style = HfpsTheme.typography.ui.copy(fontSize = 10.sp),
                        color = HfpsTheme.colors.mutedForeground,
                        modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(-with(density) { 28.dp.roundToPx() }, y) },
                    )
                }
            }
            Row(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbY) }, verticalAlignment = Alignment.CenterVertically) {
                if (dragging && mark != null) {
                    Text(
                        monthText(mark.year, mark.month, locale),
                        style = HfpsTheme.typography.uiMedium,
                        color = HfpsTheme.colors.primaryForeground,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(HfpsTheme.colors.primary).padding(horizontal = 12.dp, vertical = 6.dp).testTag("gallery-scroller-bubble"),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Box(
                    Modifier
                        .padding(end = 2.dp)
                        .size(width = ScrollerGeometry.THUMB_WIDTH, height = ScrollerGeometry.THUMB_HEIGHT)
                        .clip(RoundedCornerShape(4.dp))
                        .background(HfpsTheme.colors.primary.copy(alpha = if (dragging) 1f else 0.7f))
                        .testTag("gallery-scroller-thumb"),
                )
            }
        }
    }
}
