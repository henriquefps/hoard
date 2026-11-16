package dev.hfps.hoard.app.ui.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.data.AppSettings
import dev.hfps.hoard.app.files.LocationKind
import dev.hfps.hoard.app.files.FileService
import dev.hfps.hoard.app.trash.RestoreOutcome
import dev.hfps.hoard.app.trash.TrashDayGroup
import dev.hfps.hoard.app.trash.TrashFolderGroup
import dev.hfps.hoard.app.trash.TrashGroups
import dev.hfps.hoard.app.trash.TrashPlace
import dev.hfps.hoard.app.ui.common.ConfirmDialog
import dev.hfps.hoard.app.ui.common.HoardDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.MenuItem
import dev.hfps.hoard.app.ui.common.bytes
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.data.TrashEntryEntity
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.rememberCollapsingHeaderState
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the Trash screen shows. */
data class TrashUi(
    val days: List<TrashDayGroup> = emptyList(),
    val refreshing: Boolean = false,
    val autoDays: Int = AppSettings.TRASH_AUTO_EMPTY_DEFAULT,
    val loaded: Boolean = false,
    /** Server photos another device trashed whose share Hoard does not know (ADR 0023, A4). */
    val server: List<ServerTrashRow> = emptyList(),
) {
    val files: Int get() = days.sumOf { it.files }
    val bytes: Long get() = days.sumOf { it.bytes }

    /** What Empty trash can delete now: the places that answered the last refresh. */
    val reachableFiles: Int get() = days.sumOf { d -> d.folders.filter { it.reachable }.sumOf { it.files } }
    val reachableBytes: Long get() = days.sumOf { d -> d.folders.filter { it.reachable }.sumOf { it.bytes } }
}

/** A server photo in a trash that Hoard cannot reach through a share: shown with the server's thumbnail, no actions. */
data class ServerTrashRow(val entry: dev.hfps.hoard.app.gallery.GalleryEntry, val since: Long)

/** The rows of [items] (trashed server items) as the Gallery's entries, for their thumbnails. */
fun serverTrashRows(items: List<dev.hfps.hoard.server.db.ServerItemEntity>): List<ServerTrashRow> {
    val rows = items.map {
        dev.hfps.hoard.server.db.ServerItemRow(
            it.id, it.kind, it.mainFingerprint, it.sortTime, it.captureLocal, it.make, it.model, it.lens, it.width, it.height,
            it.durationMs, it.name, it.folder, it.search, it.hasRaw, it.editPrimary, it.editCount,
        )
    }
    val since = items.associate { it.id to (it.trashedSince ?: 0L) }
    val entries = dev.hfps.hoard.app.gallery.GalleryModel.entries(dev.hfps.hoard.app.gallery.GalleryInput(rows, emptyList(), emptyMap(), emptyMap(), emptyMap()))
    return entries.map { ServerTrashRow(it, since[it.itemId] ?: 0L) }.sortedByDescending { it.since }
}

class TrashViewModel(private val container: AppContainer, private val phoneName: String, private val driveName: String) : ViewModel() {
    private val unreachable = MutableStateFlow<Set<TrashPlace>>(emptySet())
    private val refreshing = MutableStateFlow(false)
    private val server = MutableStateFlow<List<ServerTrashRow>>(emptyList())

    val ui: StateFlow<TrashUi> = combine(
        container.trash.observe(),
        unreachable,
        refreshing,
        container.database.machines().observeAll(),
        container.appSettings.trashAutoEmptyDays,
    ) { rows, away, busy, machines, days ->
        val volumes = runCatching { container.volumeRegistry.list() }.getOrDefault(emptyList())
        val label = { place: TrashPlace ->
            val loc = FileService.resolve(place.locationId, place.share.ifEmpty { null }, machines, volumes, phoneName, driveName)
            when {
                loc == null -> listOf(place.locationId, place.share).filter { it.isNotEmpty() }.joinToString(" › ")
                loc.kind == LocationKind.Smb -> listOfNotNull(loc.name, loc.share).joinToString(" › ")
                else -> loc.name
            }
        }
        TrashUi(TrashGroups.of(rows, away, label), busy, days, loaded = true)
    }.combine(server) { ui, srv -> ui.copy(server = srv) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUi())

    init {
        refresh()
    }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                unreachable.value = container.trash.refresh()
                val unplaced = runCatching { container.trash.registerServerTrash() }.getOrDefault(emptyList()).toSet()
                server.value = serverTrashRows(container.gallery.db.items().trashed().filter { it.id in unplaced })
            } finally {
                refreshing.value = false
            }
        }
    }

    suspend fun restore(rows: List<TrashEntryEntity>): List<RestoreOutcome> = container.trash.restore(rows.map { it.id })

    suspend fun delete(rows: List<TrashEntryEntity>): Int = container.trash.empty(rows.map { it.id }).size

    suspend fun emptyAll(): Int = container.trash.emptyAll().size

    companion object {
        fun factory(container: AppContainer, phoneName: String, driveName: String): ViewModelProvider.Factory =
            viewModelFactory { initializer { TrashViewModel(container, phoneName, driveName) } }
    }
}

private sealed interface TrashDialog {
    data class Delete(val rows: List<TrashEntryEntity>) : TrashDialog
    data object Empty : TrashDialog
    data class Conflict(val path: String) : TrashDialog
}

/** The Trash screen (ADR 0023): by day, then folder, with Restore, Delete permanently and Empty trash. */
@Composable
fun TrashScreen(container: AppContainer, onBack: () -> Unit) {
    val phone = stringResource(R.string.t_locations_internal)
    val drive = stringResource(R.string.t_locations_drive)
    val vm: TrashViewModel = viewModel(factory = TrashViewModel.factory(container, phone, drive))
    val ui by vm.ui.collectAsState()
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var dialog by remember { mutableStateOf<TrashDialog?>(null) }
    TrashContent(
        ui = ui,
        thumbs = container.gallery.thumbs,
        onBack = onBack,
        onRefresh = vm::refresh,
        onEmpty = { dialog = TrashDialog.Empty },
        onDelete = { dialog = TrashDialog.Delete(it) },
        onRestore = { rows ->
            scope.launch {
                val outcomes = vm.restore(rows)
                val restored = TrashGroups.restoredFiles(outcomes)
                val conflict = TrashGroups.firstConflict(outcomes)
                if (restored > 0) toaster.show(res.getQuantityString(R.plurals.trash_restored, restored, restored.toString()))
                if (conflict != null) {
                    dialog = TrashDialog.Conflict(conflict)
                } else if (outcomes.any { it is RestoreOutcome.Failed || it is RestoreOutcome.Partial }) {
                    toaster.show(res.getString(R.string.trash_restore_failed))
                }
            }
        },
    )
    when (val d = dialog) {
        is TrashDialog.Delete -> ConfirmDialog(
            title = pluralStringResource(R.plurals.trash_delete_group_title, d.rows.size, formatNumber(d.rows.size, locale())),
            body = pluralStringResource(R.plurals.trash_delete_confirm_body, d.rows.size, formatNumber(d.rows.size, locale()), bytes(d.rows.sumOf { it.size })),
            confirmLabel = stringResource(R.string.trash_delete_permanently),
            destructive = true,
            onDismiss = { dialog = null },
            onConfirm = {
                val n = vm.delete(d.rows)
                toaster.show(res.getQuantityString(R.plurals.trash_purged, n, n.toString()))
            },
        )
        TrashDialog.Empty -> ConfirmDialog(
            title = stringResource(R.string.trash_empty_confirm_title),
            body = pluralStringResource(R.plurals.trash_delete_confirm_body, ui.reachableFiles, formatNumber(ui.reachableFiles, locale()), bytes(ui.reachableBytes)),
            confirmLabel = stringResource(R.string.trash_empty_action),
            destructive = true,
            onDismiss = { dialog = null },
            onConfirm = {
                val n = vm.emptyAll()
                toaster.show(res.getQuantityString(R.plurals.trash_purged, n, n.toString()))
            },
            extra = { EmptyUnreachableNote(ui) },
        )
        is TrashDialog.Conflict -> HoardDialog(
            title = stringResource(R.string.trash_restore_conflict_title),
            onDismiss = { dialog = null },
            confirm = { TextButton({ dialog = null }) { Text(stringResource(R.string.t_common_close), color = HfpsTheme.colors.primary) } },
        ) {
            Text(stringResource(R.string.trash_restore_conflict_body, d.path), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
        }
        null -> Unit
    }
}

/** Empty trash leaves the places that are not reachable now: said in its confirmation (review of PR #59). */
@Composable
fun EmptyUnreachableNote(ui: TrashUi) {
    val away = ui.files - ui.reachableFiles
    if (away > 0) {
        Text(
            pluralStringResource(R.plurals.trash_empty_unreachable_note, away, formatNumber(away, locale())),
            style = HfpsTheme.typography.ui,
            color = HfpsTheme.colors.mutedForeground,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** The screen without its view model (goldens). */
@Composable
fun TrashContent(
    ui: TrashUi,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    onEmpty: () -> Unit = {},
    onRestore: (List<TrashEntryEntity>) -> Unit = {},
    onDelete: (List<TrashEntryEntity>) -> Unit = {},
    thumbs: dev.hfps.hoard.app.gallery.ThumbSource? = null,
) {
    HfpsScaffold(
        header = rememberCollapsingHeaderState(),
        topBar = {
            HfpsTopBar(title = stringResource(R.string.trash_title), onBack = onBack) {
                HfpsIconButton(PhosphorRegular.ArrowsClockwise, stringResource(R.string.trash_refresh), onRefresh, enabled = !ui.refreshing)
                HfpsIconButton(
                    PhosphorRegular.Trash,
                    stringResource(R.string.trash_empty_action),
                    onEmpty,
                    enabled = ui.reachableFiles > 0,
                    tint = HfpsTheme.colors.destructive,
                    modifier = Modifier.testTag("trash-empty"),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("trash-list")) {
                if (ui.refreshing) item("refreshing") { LinearProgressIndicator(Modifier.fillMaxWidth(), color = HfpsTheme.colors.primary, trackColor = HfpsTheme.colors.muted) }
                item("auto") {
                    Text(
                        if (ui.autoDays > 0) stringResource(R.string.trash_auto_note, ui.autoDays) else stringResource(R.string.trash_auto_off),
                        style = HfpsTheme.typography.ui,
                        color = HfpsTheme.colors.mutedForeground,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (ui.loaded && ui.days.isEmpty() && ui.server.isEmpty()) {
                    item("empty") { EmptyTrash() }
                }
                for (day in ui.days) {
                    item("day-${day.date}") { DayHeader(day, onRestore, onDelete) }
                    items(day.folders, key = { "f-${day.date}-${it.place}-${it.folder}" }) { folder ->
                        Column(Modifier.background(HfpsTheme.colors.card)) {
                            if (folder != day.folders.first()) HfpsRowDivider(Modifier.padding(start = 16.dp))
                            FolderRow(folder, onRestore, onDelete)
                        }
                    }
                }
                if (ui.server.isNotEmpty()) {
                    item("server") {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                            Eyebrow(stringResource(R.string.trash_server_section))
                            Text(stringResource(R.string.trash_server_hint), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                        }
                    }
                    items(ui.server, key = { "srv-" + it.entry.key }) { row -> ServerRow(row, thumbs) }
                }
            }
        }
    }
}

@Composable
private fun ServerRow(row: ServerTrashRow, thumbs: dev.hfps.hoard.app.gallery.ThumbSource?) {
    val px = 144
    val image by androidx.compose.runtime.produceState(thumbs?.peek(row.entry, px), row.entry.key) {
        if (value == null && thumbs != null) value = runCatching { thumbs.load(row.entry, px) }.getOrNull()
    }
    Row(
        Modifier.fillMaxWidth().background(HfpsTheme.colors.card).padding(horizontal = 16.dp, vertical = 8.dp).testTag("trash-server-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).background(HfpsTheme.colors.muted, HfpsTheme.shapes.card)) {
            image?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        }
        Column(Modifier.weight(1f)) {
            Text(row.entry.name, style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground, maxLines = 1)
            Text(row.entry.folder, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyTrash() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(PhosphorRegular.Trash, null, Modifier.size(40.dp), tint = HfpsTheme.colors.mutedForeground)
        Text(stringResource(R.string.trash_empty_state_title), style = HfpsTheme.typography.title, color = HfpsTheme.colors.foreground)
        Text(stringResource(R.string.trash_empty_state_body), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
    }
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@Composable
private fun dayLabel(date: LocalDate?): String =
    date?.format(DAY_FORMAT.withLocale(locale())) ?: stringResource(R.string.trash_day_undated)

@Composable
private fun DayHeader(day: TrashDayGroup, onRestore: (List<TrashEntryEntity>) -> Unit, onDelete: (List<TrashEntryEntity>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Eyebrow(dayLabel(day.date))
            Text(
                pluralStringResource(R.plurals.trash_group_summary, day.files, formatNumber(day.files, locale()), bytes(day.bytes)),
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
            )
        }
        GroupMenu(stringResource(R.string.t_locations_actions_for, dayLabel(day.date)), day.reachable, { onRestore(day.rows.filter { r -> day.folders.any { it.reachable && r in it.rows } }) }) {
            onDelete(day.folders.filter { it.reachable }.flatMap { it.rows })
        }
    }
}

@Composable
private fun FolderRow(folder: TrashFolderGroup, onRestore: (List<TrashEntryEntity>) -> Unit, onDelete: (List<TrashEntryEntity>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp).alpha(if (folder.reachable) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(PhosphorRegular.FolderSimple, null, Modifier.size(20.dp), tint = HfpsTheme.colors.mutedForeground)
        Column(Modifier.weight(1f)) {
            Eyebrow(if (folder.reachable) folder.placeLabel else "${folder.placeLabel} · ${stringResource(R.string.trash_not_reachable)}")
            Text(folder.folder.ifEmpty { "/" }, style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground, maxLines = 2)
            Text(
                pluralStringResource(R.plurals.trash_group_summary, folder.files, formatNumber(folder.files, locale()), bytes(folder.bytes)),
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
            )
            folder.expiresAt?.let { at ->
                val date = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                Text(stringResource(R.string.trash_expires, date.format(DAY_FORMAT.withLocale(locale()))), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            }
        }
        GroupMenu(stringResource(R.string.t_locations_actions_for, folder.folder.ifEmpty { folder.placeLabel }), folder.reachable, { onRestore(folder.rows) }) {
            onDelete(folder.rows)
        }
    }
}

@Composable
private fun GroupMenu(description: String, enabled: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HfpsIconButton(PhosphorRegular.DotsThreeVertical, description, { open = true }, enabled = enabled)
        DropdownMenu(open, { open = false }, containerColor = HfpsTheme.colors.card) {
            MenuItem(stringResource(R.string.trash_restore), PhosphorRegular.ArrowCounterClockwise, { open = false; onRestore() })
            MenuItem(stringResource(R.string.trash_delete_permanently), PhosphorRegular.Trash, { open = false; onDelete() }, destructive = true)
        }
    }
}
