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
import dev.hfps.hoard.server.paths.SharePaths
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.rememberCollapsingHeaderState
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.api.TrashFigures
import dev.hfps.hoard.server.trash.EmptyResult
import dev.hfps.hoard.server.trash.ServerTrash
import dev.hfps.hoard.server.trash.ServerTrashDay
import dev.hfps.hoard.server.trash.ServerTrashLine
import dev.hfps.hoard.server.trash.ServerTrashListing
import dev.hfps.hoard.server.trash.UnitsResult
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
    /** Originals of compressed videos with no row on a share Hoard knows (ADR 0025: NAS without a mapping, backups). */
    val compressed: List<CompressedTrashRow> = emptyList(),
    /**
     * The paired server lists `trash-actions` (ADR 0023 Amendment (2.10.0)): its trash is listed from the server
     * ([serverDays]) and restored, deleted and emptied through it; [days] are only Hoard's own trash.
     */
    val serverMode: Boolean = false,
    val serverDays: List<ServerTrashDay> = emptyList(),
    /** Each group's figures (what Empty confirms). */
    val serverGroups: Map<String, TrashFigures> = emptyMap(),
    /** Backup-only photos Empty leaves (only the web gallery deletes them). */
    val serverWebOnly: TrashFigures? = null,
    /** False when the server's trash could not be read (nothing of it is shown, nothing acts on it over SMB). */
    val serverReachable: Boolean = true,
    /** Hoard's rows of server-folder photos the server does not list yet (shown as a count, no actions). */
    val waiting: Int = 0,
    /** The server's auto-empty, in days (`compress.trashDays`). */
    val serverTrashDays: Int = SERVER_TRASH_DAYS,
) {
    private val freeCompressed: List<CompressedTrashRow> get() = compressed.filter { !it.original.held }

    val files: Int get() = days.sumOf { it.files } + freeCompressed.size
    val bytes: Long get() = days.sumOf { it.bytes } + freeCompressed.sumOf { it.original.size }

    /** What Empty trash can delete now: the places that answered the last refresh, and the server's compressed originals. */
    val reachableFiles: Int get() = days.sumOf { d -> d.folders.filter { it.reachable }.sumOf { it.files } } + freeCompressed.size
    val reachableBytes: Long get() = days.sumOf { d -> d.folders.filter { it.reachable }.sumOf { it.bytes } } + freeCompressed.sumOf { it.original.size }

    /** Entries a compression journal holds (ADR 0025): listed, left by Empty, not counted. */
    val held: Int get() = days.sumOf { d -> d.folders.sumOf { it.heldRows.size } } + compressed.count { it.original.held } +
        serverDays.sumOf { d -> d.places.sumOf { it.heldLines.size } }

    /** Shots the server's Empty can take (not held). */
    val serverShots: Int get() = serverGroups.values.sumOf { it.count }

    /** Empty's lines: each server group, then Hoard's own reachable trash. */
    val emptyLines: List<EmptyLine> get() = emptyLines(serverGroups, reachableFiles, reachableBytes)

    val canEmpty: Boolean get() = reachableFiles > 0 || serverShots > 0
}

/** A compressed original the Trash screen lists in its server section (with the server's thumbnail when cached). */
data class CompressedTrashRow(val original: dev.hfps.hoard.app.trash.CompressedOriginal, val entry: dev.hfps.hoard.app.gallery.GalleryEntry?)

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
    /** The server's trash (ADR 0023 Amendment (2.10.0)): null until read, [ServerState.mode] false without the feature. */
    /** [loaded]: the listing was asked for (null after it means unreachable; before it, still loading). */
    data class ServerState(val mode: Boolean = false, val listing: ServerTrashListing? = null, val trashDays: Int = SERVER_TRASH_DAYS, val loaded: Boolean = true)

    private val serverState = MutableStateFlow(ServerState())

    /** The last split of Hoard's rows against the server's trash (rows to forget after a server restore or delete). */
    @Volatile private var split: dev.hfps.hoard.app.trash.ServerTrashSplit = dev.hfps.hoard.app.trash.ServerTrashSplit.NONE
    private val unreachable = MutableStateFlow<Set<TrashPlace>>(emptySet())
    private val refreshing = MutableStateFlow(false)
    private val server = MutableStateFlow<List<ServerTrashRow>>(emptyList())
    private val compression = MutableStateFlow(dev.hfps.hoard.app.trash.TrashCompressionState.NONE)

    /** Gallery entries of trashed server items (thumbnails of the server section). */
    private val entries = MutableStateFlow<Map<String, dev.hfps.hoard.app.gallery.GalleryEntry>>(emptyMap())

    val ui: StateFlow<TrashUi> = combine(
        combine(container.trash.observe(), compression, serverState) { rows, c, srv -> Triple(rows, c, srv) },
        unreachable,
        refreshing,
        container.database.machines().observeAll(),
        container.appSettings.trashAutoEmptyDays,
    ) { (allRows, comp, srv), away, busy, machines, days ->
        val sp = if (srv.mode) container.trash.split(allRows, srv.listing, enabled = true) else dev.hfps.hoard.app.trash.ServerTrashSplit.allLocal(allRows)
        split = sp
        val rows = sp.local
        val volumes = runCatching { container.volumeRegistry.list() }.getOrDefault(emptyList())
        val label = { place: TrashPlace ->
            val loc = FileService.resolve(place.locationId, place.share.ifEmpty { null }, machines, volumes, phoneName, driveName)
            when {
                loc == null -> listOf(place.locationId, place.share).filter { it.isNotEmpty() }.joinToString(" › ")
                loc.kind == LocationKind.Smb -> listOfNotNull(loc.name, loc.share).joinToString(" › ")
                else -> loc.name
            }
        }
        val groups = TrashGroups.of(rows, away, label, compression = comp)
        if (srv.mode) {
            // The server lists every server photo itself, compressed originals and backups included.
            val listing = srv.listing
            TrashUi(
                groups, busy, days, loaded = true,
                serverMode = true,
                serverDays = listing?.let { ServerTrash.days(it) }.orEmpty(),
                serverGroups = listing?.groups.orEmpty(),
                serverWebOnly = listing?.webOnly,
                serverReachable = listing != null || !srv.loaded,
                waiting = sp.waiting.count { it.state == dev.hfps.hoard.data.TrashStates.TRASHED },
                serverTrashDays = srv.trashDays,
            )
        } else {
            val compressedRows = comp.serverOnly(rows).map { o -> CompressedTrashRow(o, entries.value[o.itemId]) }
            TrashUi(groups, busy, days, loaded = true, compressed = compressedRows)
        }
    }.combine(server) { ui, srv -> if (ui.serverMode) ui else ui.copy(server = srv) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUi())

    init {
        refresh()
    }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                val mode = container.trash.server.enabled()
                if (mode) {
                    // Server mode first (review of #88, B1): Empty is the per-group one from now on, not 2.9's, even
                    // while the listing is on its way (or never comes).
                    val days = container.compress.info.value?.trashDays ?: SERVER_TRASH_DAYS
                    if (!serverState.value.mode) serverState.value = ServerState(true, null, days, loaded = false)
                    serverState.value = ServerState(true, container.trash.server.listing(), days)
                } else if (serverState.value.mode) {
                    serverState.value = ServerState()
                }
                unreachable.value = container.trash.refresh()
                val unplaced = runCatching { container.trash.registerServerTrash() }.getOrDefault(emptyList()).toSet()
                // ADR 0025: what a compression journal holds changes on the server; read it fresh for the screen.
                val state = runCatching { container.trash.compression(refresh = true) }.getOrDefault(dev.hfps.hoard.app.trash.TrashCompressionState.NONE)
                val compressedIds = state.originals.map { it.itemId }.toSet()
                val trashed = container.gallery.db.items().trashed()
                entries.value = serverTrashRows(trashed.filter { it.id in compressedIds }).associateBy { it.entry.itemId.orEmpty() }.mapValues { it.value.entry }
                server.value = serverTrashRows(trashed.filter { it.id in unplaced && it.id !in compressedIds })
                compression.value = state
            } finally {
                refreshing.value = false
            }
        }
    }

    suspend fun restore(rows: List<TrashEntryEntity>): List<RestoreOutcome> = container.trash.restore(rows.map { it.id })

    suspend fun delete(rows: List<TrashEntryEntity>): dev.hfps.hoard.app.trash.TrashService.EmptyOutcome = container.trash.empty(rows.map { it.id }).also { recompute() }

    suspend fun emptyAll(): dev.hfps.hoard.app.trash.TrashService.EmptyOutcome = container.trash.emptyAll().also { recompute() }

    /** Hoard's own trash only (the server's is emptied by group). */
    suspend fun emptyLocal(): dev.hfps.hoard.app.trash.TrashService.EmptyOutcome = container.trash.emptyAll(localOnly = true).also { recompute() }

    /** Server photos back where they were, through the server; Hoard's rows of the restored shots are forgotten. */
    suspend fun serverRestore(lines: List<ServerTrashLine>): UnitsResult =
        container.trash.server.restore(lines.map { it.ref }.distinct()).also { afterServer(it, purged = false) }

    suspend fun serverDelete(lines: List<ServerTrashLine>): UnitsResult =
        container.trash.server.delete(lines).also { afterServer(it, purged = true) }

    /** Empty of server groups, after the per-group confirmation; a 409 answers the figures to show again. */
    suspend fun serverEmpty(shown: Map<String, TrashFigures>, chosen: Set<String>): EmptyResult {
        val before = serverState.value.listing
        return container.trash.server.empty(shown, chosen).also { r ->
            when (r) {
                is EmptyResult.Done -> afterServer(r.result, purged = true, listing = before)
                is EmptyResult.Conflict -> serverState.value = serverState.value.copy(listing = serverState.value.listing?.copy(groups = r.groups, webOnly = r.webOnly ?: serverState.value.listing?.webOnly))
                is EmptyResult.Failed -> Unit
            }
        }
    }

    private suspend fun afterServer(r: UnitsResult, purged: Boolean, listing: ServerTrashListing? = null) {
        val done = r.done.map { it.itemId }.toSet()
        val rows = split.covered.filterKeys { it in done }.values.flatten()
        container.trash.forgetServerRows(rows, purged)
        refresh()
    }

    /** Restore of compressed originals in the server section: undoes their compression (ADR 0025). */
    suspend fun restoreCompressed(rows: List<CompressedTrashRow>): Int =
        container.trash.restoreCompressed(rows.map { it.original }).done.size.also { recompute() }

    suspend fun deleteCompressed(rows: List<CompressedTrashRow>): dev.hfps.hoard.app.trash.CompressRouteOutcome =
        container.trash.purgeCompressed(rows.map { it.original }).also { recompute() }

    /** After an action: the server walks and rebuilds; the cache follows at the next sync, then the screen. */
    private fun recompute() {
        viewModelScope.launch {
            runCatching { container.gallery.syncNow() }
            compression.value = runCatching { container.trash.compression(refresh = false) }.getOrDefault(compression.value)
        }
    }

    companion object {
        fun factory(container: AppContainer, phoneName: String, driveName: String): ViewModelProvider.Factory =
            viewModelFactory { initializer { TrashViewModel(container, phoneName, driveName) } }
    }
}

private sealed interface TrashDialog {
    data class Delete(val rows: List<TrashEntryEntity>) : TrashDialog
    data class DeleteCompressed(val rows: List<CompressedTrashRow>) : TrashDialog
    data object Empty : TrashDialog
    data class Conflict(val path: String) : TrashDialog

    /** Delete permanently of server units. */
    data class ServerDelete(val lines: List<ServerTrashLine>) : TrashDialog

    /**
     * Empty with the per-group confirmation (server mode); [changed]: the server's 409 re-showed its figures, with the
     * groups the owner had [unchecked] still unchecked.
     */
    data class EmptyGroups(val changed: Boolean = false, val unchecked: Set<String> = emptySet()) : TrashDialog
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
        onEmpty = { dialog = if (ui.serverMode) TrashDialog.EmptyGroups() else TrashDialog.Empty },
        onServerRestore = { lines ->
            scope.launch {
                val r = vm.serverRestore(lines)
                val n = r.done.size
                if (n > 0) toaster.show(res.getQuantityString(R.plurals.trash_restored_shots, n, n.toString()))
                val taken = r.refused.entries.firstOrNull { it.value == "path_taken" }
                if (taken != null) {
                    dialog = TrashDialog.Conflict(r.names[taken.key] ?: lines.firstOrNull { it.ref.refKey == taken.key }?.entry?.name.orEmpty())
                }
                for ((why, k) in r.refusedBy.filterKeys { it != "path_taken" }) toaster.show(res.getQuantityString(R.plurals.trash_server_refused, k, k.toString(), serverReasonText(res, why)))
            }
        },
        onServerDelete = { dialog = TrashDialog.ServerDelete(it) },
        onDelete = { dialog = TrashDialog.Delete(it) },
        onRestoreCompressed = { rows ->
            scope.launch {
                val n = vm.restoreCompressed(rows)
                if (n > 0) toaster.show(res.getQuantityString(R.plurals.trash_restored, n, n.toString())) else toaster.show(res.getString(R.string.trash_restore_failed))
            }
        },
        onDeleteCompressed = { dialog = TrashDialog.DeleteCompressed(it) },
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
                val o = vm.delete(d.rows)
                toaster.show(res.getQuantityString(R.plurals.trash_purged, o.count, o.count.toString()))
                for ((reason, n) in o.refused) toaster.show(refusedText(res, reason, n))
            },
        )
        is TrashDialog.DeleteCompressed -> ConfirmDialog(
            title = pluralStringResource(R.plurals.trash_delete_group_title, d.rows.size, formatNumber(d.rows.size, locale())),
            body = pluralStringResource(R.plurals.trash_delete_confirm_body, d.rows.size, formatNumber(d.rows.size, locale()), bytes(d.rows.sumOf { it.original.size })),
            confirmLabel = stringResource(R.string.trash_delete_permanently),
            destructive = true,
            onDismiss = { dialog = null },
            onConfirm = {
                val o = vm.deleteCompressed(d.rows)
                toaster.show(res.getQuantityString(R.plurals.trash_purged, o.done.size, o.done.size.toString()))
                for ((reason, n) in o.failed.values.groupingBy { it }.eachCount()) toaster.show(refusedText(res, reason, n))
            },
        )
        TrashDialog.Empty -> ConfirmDialog(
            title = stringResource(R.string.trash_empty_confirm_title),
            body = pluralStringResource(R.plurals.trash_delete_confirm_body, ui.reachableFiles, formatNumber(ui.reachableFiles, locale()), bytes(ui.reachableBytes)),
            confirmLabel = stringResource(R.string.trash_empty_action),
            destructive = true,
            onDismiss = { dialog = null },
            onConfirm = {
                val o = vm.emptyAll()
                toaster.show(res.getQuantityString(R.plurals.trash_purged, o.count, o.count.toString()))
                for ((reason, n) in o.refused) toaster.show(refusedText(res, reason, n))
            },
            extra = { EmptyUnreachableNote(ui) },
        )
        is TrashDialog.ServerDelete -> {
            val shots = d.lines.map { it.entry.id }.distinct().size
            ConfirmDialog(
                title = pluralStringResource(R.plurals.trash_server_delete_title, shots, formatNumber(shots, locale())),
                body = pluralStringResource(R.plurals.trash_server_delete_body, shots, formatNumber(shots, locale()), bytes(d.lines.sumOf { it.unit.size })),
                confirmLabel = stringResource(R.string.trash_delete_permanently),
                destructive = true,
                onDismiss = { dialog = null },
                onConfirm = {
                    val r = vm.serverDelete(d.lines)
                    toaster.show(res.getQuantityString(R.plurals.trash_purged_shots, r.done.size, r.done.size.toString()))
                    for ((why, k) in r.refusedBy) toaster.show(res.getQuantityString(R.plurals.trash_server_refused, k, k.toString(), serverReasonText(res, why)))
                },
            )
        }
        is TrashDialog.EmptyGroups -> {
            val lines = ui.emptyLines
            var checked by remember(d) { mutableStateOf(lines.map { it.key }.toSet() - d.unchecked) }
            var busy by remember(d) { mutableStateOf(false) }
            EmptyGroupsDialog(
                lines = lines,
                checked = checked,
                onChecked = { checked = it },
                changed = d.changed,
                held = ui.held,
                busy = busy,
                webOnly = ui.serverWebOnly,
                onDismiss = { dialog = null },
                onConfirm = {
                    busy = true
                    scope.launch {
                        val groups = checked - EmptyLine.LOCAL
                        var purged = 0
                        var conflict = false
                        if (groups.isNotEmpty()) {
                            when (val r = vm.serverEmpty(ui.serverGroups, groups)) {
                                is EmptyResult.Done -> {
                                    purged += r.result.done.map { it.itemId }.distinct().size
                                    for ((why, k) in r.result.refusedBy) toaster.show(res.getQuantityString(R.plurals.trash_server_refused, k, k.toString(), serverReasonText(res, why)))
                                }
                                is EmptyResult.Conflict -> conflict = true
                                is EmptyResult.Failed -> toaster.show(serverReasonText(res, r.reason))
                            }
                        }
                        if (conflict) {
                            // The trash changed since it was shown: its figures now, each group checked again.
                            dialog = TrashDialog.EmptyGroups(changed = true, unchecked = lines.map { it.key }.toSet() - checked)
                            return@launch
                        }
                        if (EmptyLine.LOCAL in checked) {
                            val o = vm.emptyLocal()
                            if (o.count > 0) toaster.show(res.getQuantityString(R.plurals.trash_purged, o.count, o.count.toString()))
                            for ((reason, n) in o.refused) toaster.show(refusedText(res, reason, n))
                        }
                        if (purged > 0) toaster.show(res.getQuantityString(R.plurals.trash_purged_shots, purged, purged.toString()))
                        dialog = null
                    }
                },
            )
        }
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

/**
 * Why the server did not empty [n] compressed originals (ADR 0025, review of #76): they stay in the trash; only the
 * server deletes them.
 */
fun refusedText(res: android.content.res.Resources, reason: String, n: Int): String {
    val why = when (reason) {
        "no_write_mount", "write_mount_mismatch", "write_mount_read_only", "unstable_inode" -> res.getString(R.string.compress_refused_write_mount)
        "not_writable" -> res.getString(R.string.compress_refused_not_writable)
        "output_missing", "live_missing" -> res.getString(R.string.compress_refused_output_missing)
        "busy", "state" -> res.getString(R.string.compress_refused_busy)
        "path_taken" -> res.getString(R.string.compress_refused_path_taken)
        "not_found" -> res.getString(R.string.compress_refused_not_found)
        dev.hfps.hoard.app.trash.CompressedTrashActions.UNREACHABLE -> res.getString(R.string.compress_refused_offline)
        else -> res.getString(R.string.compress_refused_other, reason)
    }
    return res.getQuantityString(R.plurals.compress_purge_refused, n, n.toString(), why)
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
    // ADR 0025: what a compression journal holds stays, and is not in the count above.
    if (ui.held > 0) {
        Text(
            pluralStringResource(R.plurals.compress_trash_held_note, ui.held, formatNumber(ui.held, locale())),
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
    onRestoreCompressed: (List<CompressedTrashRow>) -> Unit = {},
    onDeleteCompressed: (List<CompressedTrashRow>) -> Unit = {},
    onServerRestore: (List<ServerTrashLine>) -> Unit = {},
    onServerDelete: (List<ServerTrashLine>) -> Unit = {},
    /** Places shown open (goldens); a tap opens or closes a place. */
    expandedPlaces: Set<String> = emptySet(),
) {
    var open by remember { mutableStateOf(emptySet<String>()) }
    HfpsScaffold(
        header = rememberCollapsingHeaderState(),
        topBar = {
            HfpsTopBar(title = stringResource(R.string.trash_title), onBack = onBack) {
                HfpsIconButton(PhosphorRegular.ArrowsClockwise, stringResource(R.string.trash_refresh), onRefresh, enabled = !ui.refreshing)
                HfpsIconButton(
                    PhosphorRegular.Trash,
                    stringResource(R.string.trash_empty_action),
                    onEmpty,
                    enabled = ui.canEmpty,
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
                if (ui.loaded && ui.days.isEmpty() && ui.server.isEmpty() && ui.compressed.isEmpty() && ui.serverDays.isEmpty() && ui.waiting == 0 && ui.serverReachable) {
                    item("empty") { EmptyTrash() }
                }
                if (ui.serverMode && (ui.serverDays.isNotEmpty() || ui.waiting > 0 || !ui.serverReachable)) {
                    item("srv-header") { ServerSectionHeader(ui.serverReachable, ui.serverTrashDays, ui.waiting) }
                    for (day in ui.serverDays) {
                        item("srv-day-${day.day}") { ServerDayHeader(day, dayLabel(runCatching { LocalDate.parse(day.day) }.getOrNull()), onServerRestore, onServerDelete) }
                        items(day.places, key = { "srv-p-${day.day}-${it.source}-${it.name}-${it.dir}" }) { place ->
                            Column(Modifier.background(HfpsTheme.colors.card)) {
                                if (place != day.places.first()) HfpsRowDivider(Modifier.padding(start = 16.dp))
                                val key = "${day.day}|${place.source}|${place.name}|${place.dir}"
                                ServerPlaceRow(place, onServerRestore, onServerDelete, expanded = key in open || key in expandedPlaces) {
                                    open = if (key in open) open - key else open + key
                                }
                            }
                        }
                    }
                    if (ui.days.isNotEmpty()) item("local-header") { LocalSectionHeader() }
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
                if (ui.compressed.isNotEmpty()) {
                    item("compressed") {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                            Eyebrow(stringResource(R.string.compress_trash_section))
                            Text(stringResource(R.string.compress_restore_desc), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                        }
                    }
                    items(ui.compressed, key = { "cmp-" + it.original.itemId }) { row ->
                        Column(Modifier.background(HfpsTheme.colors.card)) {
                            if (row != ui.compressed.first()) HfpsRowDivider(Modifier.padding(start = 16.dp))
                            CompressedRow(row, thumbs, { onRestoreCompressed(listOf(row)) }, { onDeleteCompressed(listOf(row)) })
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
        GroupMenu(stringResource(R.string.t_locations_actions_for, dayLabel(day.date)), day.reachable, { onRestore(day.folders.filter { it.reachable }.flatMap { it.freeRows }) }) {
            onDelete(day.folders.filter { it.reachable }.flatMap { it.freeRows })
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
        GroupMenu(stringResource(R.string.t_locations_actions_for, folder.folder.ifEmpty { folder.placeLabel }), folder.reachable && folder.freeRows.isNotEmpty(), { onRestore(folder.freeRows) }) {
            onDelete(folder.freeRows)
        }
    }
    // ADR 0025: each compressed original of the folder (restore = undo the compression), then what a journal holds.
    for ((original, rows) in folder.originals) {
        CompressedLine(
            name = original.name,
            detail = stringResource(R.string.compress_trash_label) + " · " + stringResource(R.string.compress_trash_saves, bytes(rows.sumOf { it.size })),
            enabled = folder.reachable,
            onRestore = { onRestore(rows) },
            onDelete = { onDelete(rows) },
        )
    }
    for (row in folder.heldRows) {
        CompressedLine(name = SharePaths.name(row.originalPath), detail = stringResource(R.string.compress_trash_held), enabled = false, onRestore = null, onDelete = null)
    }
}

/** One compressed original (or a held entry) under its folder: the name, the label and its own menu. */
@Composable
private fun CompressedLine(name: String, detail: String, enabled: Boolean, onRestore: (() -> Unit)?, onDelete: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 4.dp, bottom = 8.dp).testTag("trash-compressed-line"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(PhosphorRegular.ArrowsIn, null, Modifier.size(16.dp), tint = HfpsTheme.colors.mutedForeground)
        Column(Modifier.weight(1f)) {
            Text(name, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.cardForeground, maxLines = 1)
            Text(detail, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, maxLines = 2)
        }
        if (onRestore != null && onDelete != null) {
            CompressedMenu(stringResource(R.string.t_locations_actions_for, name), enabled, onRestore, onDelete)
        } else {
            Box(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun CompressedMenu(description: String, enabled: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HfpsIconButton(PhosphorRegular.DotsThreeVertical, description, { open = true }, enabled = enabled)
        DropdownMenu(open, { open = false }, containerColor = HfpsTheme.colors.card) {
            MenuItem(stringResource(R.string.compress_restore), PhosphorRegular.ArrowCounterClockwise, { open = false; onRestore() })
            MenuItem(stringResource(R.string.trash_delete_permanently), PhosphorRegular.Trash, { open = false; onDelete() }, destructive = true)
        }
    }
}

/** A compressed original the server keeps (no share row): its thumbnail, name, label and menu, or "Held". */
@Composable
private fun CompressedRow(row: CompressedTrashRow, thumbs: dev.hfps.hoard.app.gallery.ThumbSource?, onRestore: () -> Unit, onDelete: () -> Unit) {
    val px = 144
    val entry = row.entry
    val image by androidx.compose.runtime.produceState(entry?.let { e -> thumbs?.peek(e, px) }, entry?.key) {
        if (value == null && thumbs != null && entry != null) value = runCatching { thumbs.load(entry, px) }.getOrNull()
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).testTag("trash-compressed-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).background(HfpsTheme.colors.muted, HfpsTheme.shapes.card), contentAlignment = Alignment.Center) {
            image?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                ?: Icon(PhosphorRegular.FileVideo, null, Modifier.size(20.dp), tint = HfpsTheme.colors.mutedForeground)
        }
        Column(Modifier.weight(1f)) {
            Text(row.original.name, style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground, maxLines = 1)
            Text(
                if (row.original.held) {
                    stringResource(R.string.compress_trash_held)
                } else {
                    stringResource(R.string.compress_trash_label) + " · " + stringResource(R.string.compress_trash_saves, bytes(row.original.size))
                },
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
                maxLines = 2,
            )
        }
        if (!row.original.held) CompressedMenu(stringResource(R.string.t_locations_actions_for, row.original.name), true, onRestore, onDelete) else Box(Modifier.size(48.dp))
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
