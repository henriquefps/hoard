package dev.hfps.hoard.app.ui.browse

import androidx.compose.ui.focus.focusRequester

import androidx.compose.runtime.derivedStateOf

import androidx.compose.foundation.layout.widthIn

import androidx.compose.foundation.border

import dev.hfps.hoard.app.ui.common.addressKindText

import dev.hfps.hoard.app.ui.common.problemText

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.BrowseLocation
import dev.hfps.hoard.app.files.FileActions
import dev.hfps.hoard.app.files.FileFilter
import dev.hfps.hoard.app.files.FileKind
import dev.hfps.hoard.app.files.LocationKind
import dev.hfps.hoard.app.files.OpenStrategy
import dev.hfps.hoard.app.files.SortKey
import dev.hfps.hoard.app.files.SortOrder
import dev.hfps.hoard.app.files.fileKind
import dev.hfps.hoard.app.files.hasPhotos
import dev.hfps.hoard.app.files.CULL_MIN_PHOTOS
import dev.hfps.hoard.app.files.hasThumbnail
import dev.hfps.hoard.app.files.isTextFile
import dev.hfps.hoard.app.files.isValidName
import dev.hfps.hoard.app.files.isValidShareName
import dev.hfps.hoard.app.files.openStrategy
import dev.hfps.hoard.app.files.parentPath
import dev.hfps.hoard.app.files.revealSelection
import dev.hfps.hoard.app.files.splitPath
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.app.transfers.ClipboardContent
import dev.hfps.hoard.app.transfers.FolderPlace
import dev.hfps.hoard.app.transfers.PasteTarget
import dev.hfps.hoard.app.ui.common.ConfirmDialog
import dev.hfps.hoard.app.ui.common.FileTile
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.MenuItem
import dev.hfps.hoard.app.ui.common.NameDialog
import dev.hfps.hoard.app.ui.common.RouteLine
import dev.hfps.hoard.app.ui.common.StateMessage
import dev.hfps.hoard.app.ui.common.bytes
import dev.hfps.hoard.app.ui.common.errorBody
import dev.hfps.hoard.app.ui.common.errorTitle
import dev.hfps.hoard.app.ui.common.filterText
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.relativeDate
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.app.ui.common.openErrorText
import dev.hfps.hoard.app.ui.common.sortDirectionText
import dev.hfps.hoard.app.ui.common.sortKeyText
import dev.hfps.hoard.app.ui.transfers.DestinationPicker
import dev.hfps.hoard.app.ui.transfers.TransferIntent
import dev.hfps.hoard.app.ui.transfers.rememberStartTransfer
import dev.hfps.hoard.app.ui.reveal.rememberReveal
import dev.hfps.hoard.app.ui.files.XmpExportRequest
import dev.hfps.hoard.app.ui.files.rememberXmpExport
import dev.hfps.hoard.designsystem.components.FileNameText
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.components.rememberCollapsingHeaderState
import dev.hfps.hoard.designsystem.icons.PhosphorFill
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SmbEntry
import kotlinx.coroutines.launch

/** Where the browser goes (the shell's navigation). */
class BrowseNav(
    val back: () -> Unit,
    /** Another folder or share of a location (`share` null: the machine's share list). */
    val open: (locationId: String, share: String?, path: String) -> Unit,
    val home: () -> Unit,
    val editMachine: (String) -> Unit,
    val image: (BrowseLocation, String) -> Unit,
    val text: (BrowseLocation, String, Boolean) -> Unit,
    val transfers: () -> Unit,
    /** The folder in culling mode (ADR 0008). */
    val cull: (BrowseLocation, String) -> Unit = { _, _ -> },
)

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private sealed interface BrowseDialog {
    data object NewFolder : BrowseDialog
    data object NewTextFile : BrowseDialog
    data object OpenShare : BrowseDialog
    data class Rename(val entry: SmbEntry) : BrowseDialog
    data class Delete(val entries: List<SmbEntry>) : BrowseDialog
    data class TransferTo(val intent: TransferIntent, val entries: List<SmbEntry>) : BrowseDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(viewModel: BrowseViewModel, state: BrowseUiState, actions: FileActions, nav: BrowseNav, clipboard: dev.hfps.hoard.app.transfers.Clipboard) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val location = state.location
    var dialog by remember { mutableStateOf<BrowseDialog?>(null) }
    val listState = rememberLazyListState()
    // The toolbar (search, filter, sort) is item 1: once it has scrolled away, the top bar offers the search.
    val toolbarGone by remember { derivedStateOf { listState.firstVisibleItemIndex >= 2 } }
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val compactSearch = searchOpen && toolbarGone
    LaunchedEffect(toolbarGone) { if (!toolbarGone) searchOpen = false }
    val header = rememberCollapsingHeaderState()
    val startTransfer = rememberStartTransfer(nav.transfers)
    val reveal = rememberReveal(actions)
    val xmpExport = rememberXmpExport()

    LaunchedEffect(location?.machine?.machine?.id) { viewModel.ensureRoute() }
    // Back from a viewer, the editor or another app: the folder may have changed meanwhile.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { viewModel.refreshIfShown() }

    BackHandler(enabled = state.inSelection) { viewModel.clearSelection() }

    // A USB drive or SD card pulled out while browsing it: say so and leave (Hoard 1's useLeaveWhenUnplugged).
    var seenVolume by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(location?.volume?.state, location?.kind) {
        val loc = location ?: return@LaunchedEffect
        if (loc.kind != LocationKind.Volume) return@LaunchedEffect
        val readable = loc.volume?.state.let { it == "mounted" || it == "mountedReadOnly" }
        if (readable) {
            seenVolume = loc.name
        } else if (seenVolume != null) {
            toaster.show(res.getString(R.string.t_locations_unplugged, seenVolume))
            nav.home()
        }
    }

    // "Paste here" targets the folder on screen while it is open.
    val readOnlyReason = readOnlyReason(location, state.path)
    val folder = state.folder
    DisposableEffect(folder, state.readOnly, state.title) {
        if (folder != null) clipboard.setTarget(PasteTarget(folder, state.title, if (state.readOnly) readOnlyReason else null))
        onDispose { if (folder != null) clipboard.clearTarget(folder) }
    }

    if (state.notFound) {
        HfpsScaffold(topBar = { HfpsTopBar(stringResource(R.string.t_errors_not_found_title), onBack = nav.back) }) {
            StateMessage(PhosphorRegular.WarningCircle, stringResource(R.string.t_errors_not_found_title), body = stringResource(R.string.t_machines_not_found)) {
                SecondaryButton(stringResource(R.string.t_locations_back_home), nav.home)
            }
        }
        return
    }

    fun openEntry(entry: SmbEntry, chooser: Boolean = false) {
        val loc = location ?: return
        if (entry.restricted) {
            toaster.show(res.getString(R.string.t_locations_restricted_title))
            return
        }
        if (entry.isDirectory) {
            nav.open(loc.id, state.share, entry.path)
            return
        }
        when (openStrategy(entry, chooser)) {
            OpenStrategy.Image -> nav.image(loc, entry.path)
            OpenStrategy.Text -> nav.text(loc, entry.path, false)
            else -> {
                if (state.opening != null) return
                val activity = context.findActivity() ?: return
                viewModel.setOpening(entry.path)
                scope.launch {
                    try {
                        actions.open(activity, loc, entry, chooser)
                    } catch (e: Throwable) {
                        val code = errorCodeOf(e)
                        if (code != ErrorCode.CANCELLED) toaster.show(res.getString(openErrorText(code)) + " · " + entry.name)
                    } finally {
                        viewModel.setOpening(null)
                    }
                }
            }
        }
    }

    fun toClipboard(op: String, entries: List<SmbEntry>) {
        val picked = viewModel.picked(entries)
        val place = state.folder ?: return
        if (picked.isEmpty()) return
        clipboard.set(ClipboardContent(op, place, state.title, picked))
        viewModel.clearSelection()
        val text = res.getQuantityString(
            if (op == "move") R.plurals.t_transfer_clipboard_cut else R.plurals.t_transfer_clipboard_copied,
            picked.size,
            picked.size.toString(),
        )
        toaster.show(text + "\n" + res.getString(R.string.t_transfer_clipboard_hint))
    }

    fun shareOut(entries: List<SmbEntry>) {
        val loc = location ?: return
        val filesOnly = entries.filter { !it.isDirectory }
        if (filesOnly.isEmpty()) {
            toaster.show(res.getString(R.string.t_transfer_share_folders_only))
            return
        }
        val activity = context.findActivity() ?: return
        scope.launch {
            try {
                actions.share(activity, loc, filesOnly.map { it.path })
                if (filesOnly.size < entries.size) {
                    val skipped = entries.size - filesOnly.size
                    toaster.show(res.getQuantityString(R.plurals.t_transfer_share_folders_skipped, skipped, skipped.toString()))
                }
            } catch (e: Throwable) {
                val code = errorCodeOf(e)
                if (code != ErrorCode.CANCELLED) toaster.show(res.getString(openErrorText(code)))
            }
        }
    }

    fun editInReveal(entries: List<SmbEntry>) {
        val loc = location ?: return
        val paths = revealSelection(entries, state.listing.entries.orEmpty())
        if (paths.isEmpty()) return
        val one = entries.singleOrNull()?.takeIf { !it.isDirectory }?.path
        reveal.edit(loc, state.path, paths, state.title, one)
        if (entries.size > 1) viewModel.clearSelection()
    }

    fun exportXmp(entries: List<SmbEntry>) {
        val loc = location ?: return
        val folder = entries.singleOrNull()?.takeIf { it.isDirectory }
        if (folder != null) {
            xmpExport.export(XmpExportRequest(loc.place, folder.path, null))
        } else {
            val paths = revealSelection(entries, state.listing.entries.orEmpty())
            if (paths.isEmpty()) return
            xmpExport.export(XmpExportRequest(loc.place, state.path, paths))
        }
        if (entries.size > 1) viewModel.clearSelection()
    }

    fun copyPath(path: String) {
        val text = viewModel.displayPath(path)
        val manager = context.getSystemService(ClipboardManager::class.java)
        if (manager == null) {
            toaster.show(res.getString(R.string.t_browse_copy_failed))
        } else {
            manager.setPrimaryClip(ClipData.newPlainText(text, text))
            toaster.show(res.getString(R.string.t_browse_path_copied) + "\n" + text)
        }
    }

    val rowActions = RowActions(
        open = { openEntry(it) },
        openWith = { openEntry(it, chooser = true) },
        openInViewer = { e -> location?.let { nav.text(it, e.path, false) } },
        clipboard = ::toClipboard,
        transferTo = { intent, list -> dialog = BrowseDialog.TransferTo(intent, list) },
        share = ::shareOut,
        reveal = if (reveal.available) ::editInReveal else null,
        exportXmp = ::exportXmp,
        favorite = { e -> scope.launch { viewModel.addFavorite(e.path, e.name); toaster.show(res.getString(R.string.t_favorites_added, e.name)) } },
        copyPath = { copyPath(it.path) },
        rename = { dialog = BrowseDialog.Rename(it) },
        select = { viewModel.startSelection(it) },
        delete = { dialog = BrowseDialog.Delete(listOf(it)) },
    )

    HfpsScaffold(
        header = header,
        topBar = {
            if (state.inSelection) {
                SelectionBar(
                    state = state,
                    readOnlyReason = readOnlyReason.takeIf { state.readOnly },
                    onClear = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAll,
                    actions = rowActions,
                    onDelete = { if (state.selectedEntries.isNotEmpty()) dialog = BrowseDialog.Delete(state.selectedEntries) },
                )
            } else {
                if (compactSearch) {
                    // 1.9.1's compact bar: the folder's search while its toolbar is scrolled away.
                    Row(
                        Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 12.dp).testTag("compact-search"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        HfpsIconButton(PhosphorRegular.ArrowLeft, stringResource(R.string.t_common_close), {
                            searchOpen = false
                            viewModel.setQuery("")
                        })
                        SearchField(state.query, viewModel::setQuery, Modifier.weight(1f).focusRequester(searchFocus))
                    }
                    LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                } else HfpsTopBar(title = state.title, onBack = nav.back, titleIsFileName = state.path.isNotEmpty()) {
                    if (toolbarGone && state.listing.entries != null && !state.showsShares) {
                        HfpsIconButton(PhosphorRegular.MagnifyingGlass, stringResource(R.string.t_browse_search), { searchOpen = true }, Modifier.testTag("compact-search-open"), active = state.query.isNotEmpty())
                    }
                    if (location != null && !state.showsShares && state.photos >= CULL_MIN_PHOTOS) {
                        HfpsIconButton(
                            icon = PhosphorRegular.Aperture,
                            contentDescription = stringResource(R.string.t_cull_open),
                            onClick = { nav.cull(location, state.path) },
                            modifier = Modifier.testTag("browse-cull"),
                        )
                    }
                    val canPin = location != null && !state.showsShares
                    if (canPin) {
                        val pinned = state.favorite != null
                        HfpsIconButton(
                            icon = if (pinned) PhosphorFill.Star else PhosphorRegular.Star,
                            contentDescription = stringResource(if (pinned) R.string.t_favorites_remove else R.string.t_favorites_add),
                            active = pinned,
                            onClick = {
                                scope.launch {
                                    val now = viewModel.toggleFavorite(state.title)
                                    toaster.show(res.getString(if (now) R.string.t_favorites_added else R.string.t_favorites_removed, state.title))
                                }
                            },
                        )
                    }
                    if (location != null) HeaderMenu(location, state.showHidden, nav, viewModel)
                }
            }
        },
    ) {
        PullToRefreshBox(
            isRefreshing = state.listing.loading && (state.listing.entries != null || state.listing.shares != null),
            onRefresh = { viewModel.load(quiet = true, refresh = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().testTag("browse-list"), state = listState) {
                item(key = "trail", contentType = "trail") {
                    Trail(state, location, nav, readOnlyReason)
                }
                when {
                    location == null -> items(8, contentType = { "skeleton" }) { SkeletonRow(it) }
                    state.showsShares -> sharesContent(state, location, nav, viewModel, rowActionsShare(location, viewModel, scope, toaster, context, ::copyPathShare), onOpenByName = { dialog = BrowseDialog.OpenShare }, onRetry = viewModel::retry, onEditMachine = nav.editMachine)
                    else -> folderContent(
                        state = state,
                        location = location,
                        viewModel = viewModel,
                        rowActions = rowActions,
                        readOnlyReason = readOnlyReason,
                        onNewFolder = { dialog = BrowseDialog.NewFolder },
                        onNewTextFile = { dialog = BrowseDialog.NewTextFile },
                        onBackHome = nav.home,
                        onUp = { parentPath(state.path)?.let { nav.open(location.id, state.share, it) } ?: nav.back() },
                        onEditMachine = nav.editMachine,
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        BrowseDialog.NewFolder -> NameDialog(
            title = stringResource(R.string.t_browse_new_folder),
            label = stringResource(R.string.t_browse_folder_name),
            initialValue = stringResource(R.string.t_browse_new_folder_default),
            submitLabel = stringResource(R.string.t_common_create),
            onDismiss = { dialog = null },
            validate = nameValidator(context, state.listing.entries.orEmpty(), null),
            onSubmit = { name ->
                viewModel.createFolder(name)?.let { res.getString(errorTitle(it)) }
                    ?: null.also { toaster.show(res.getString(R.string.t_browse_folder_created, name)) }
            },
        )
        BrowseDialog.NewTextFile -> NameDialog(
            title = stringResource(R.string.t_browse_new_text_file),
            label = stringResource(R.string.t_browse_file_name),
            initialValue = stringResource(R.string.t_browse_new_text_file_default),
            submitLabel = stringResource(R.string.t_common_create),
            selectBaseName = true,
            onDismiss = { dialog = null },
            validate = nameValidator(context, state.listing.entries.orEmpty(), null),
            onSubmit = { name ->
                val (created, error) = viewModel.createTextFile(name)
                if (error != null) {
                    res.getString(errorTitle(error))
                } else {
                    toaster.show(res.getString(R.string.t_browse_file_created, name))
                    location?.let { loc -> created?.let { nav.text(loc, it, true) } }
                    null
                }
            },
        )
        BrowseDialog.OpenShare -> NameDialog(
            title = stringResource(R.string.t_browse_open_share_by_name_title),
            description = stringResource(R.string.t_browse_open_share_by_name_body),
            label = stringResource(R.string.t_browse_share_name),
            initialValue = "",
            submitLabel = stringResource(R.string.t_browse_open),
            onDismiss = { dialog = null },
            validate = { if (isValidShareName(it)) null else res.getString(R.string.t_browse_share_name_invalid) },
            onSubmit = { name ->
                when (val error = viewModel.openShareByName(name)) {
                    null -> {
                        location?.let { nav.open(it.id, name, "") }
                        null
                    }
                    ErrorCode.NOT_FOUND -> res.getString(R.string.t_browse_share_not_found)
                    else -> res.getString(errorTitle(error))
                }
            },
        )
        is BrowseDialog.Rename -> NameDialog(
            title = stringResource(R.string.t_browse_rename),
            description = d.entry.name,
            label = stringResource(R.string.t_browse_new_name),
            initialValue = d.entry.name,
            submitLabel = stringResource(R.string.t_browse_rename),
            selectBaseName = !d.entry.isDirectory,
            onDismiss = { dialog = null },
            validate = nameValidator(context, state.listing.entries.orEmpty(), d.entry.name),
            onSubmit = { name -> viewModel.rename(d.entry, name)?.let { res.getString(errorTitle(it)) } },
        )
        is BrowseDialog.Delete -> {
            // The owner's decision 1 (ADR 0023): a plain delete in Files moves to the trash (whole shots with their
            // sidecars); permanent delete is offered only for what the trash cannot take.
            val loc = location
            if (loc != null) {
                val container = (context.applicationContext as dev.hfps.hoard.app.HoardApplication).container
                val request = remember(d) { dev.hfps.hoard.app.ui.trash.TrashRequest(loc, viewModel.path, d.entries.map { it.path }) }
                dev.hfps.hoard.app.ui.trash.MoveToTrashDialog(
                    container,
                    request,
                    onDismiss = { dialog = null },
                    onDone = { viewModel.afterTrash() },
                    shots = false,
                )
            } else {
                dialog = null
            }
        }
        is BrowseDialog.TransferTo -> {
            val loc = location
            val source = state.folder
            if (loc != null && source != null) {
                val picked = viewModel.picked(d.entries)
                DestinationPicker(
                    intent = d.intent,
                    count = picked.size,
                    source = source,
                    entries = picked,
                    onDismiss = { dialog = null },
                    onPick = { destination, label, conflict ->
                        val op = if (d.intent == TransferIntent.Move) "move" else "copy"
                        if (startTransfer(op, source.place, picked, destination, label, conflict)) {
                            viewModel.clearSelection()
                            dialog = null
                        }
                    },
                )
            }
        }
    }
    reveal.Dialogs()
    xmpExport.Dialogs()
}

/** "Copy path" of a share row: the share's root. */
private fun copyPathShare(context: Context, text: String, toaster: dev.hfps.hoard.app.ui.common.Toaster) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(text, text))
    toaster.show(context.getString(R.string.t_browse_path_copied) + "\n" + text)
}

private fun rowActionsShare(
    location: BrowseLocation,
    viewModel: BrowseViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    toaster: dev.hfps.hoard.app.ui.common.Toaster,
    context: Context,
    copy: (Context, String, dev.hfps.hoard.app.ui.common.Toaster) -> Unit,
) = ShareActions(
    favorite = { name ->
        scope.launch {
            viewModel.addFavorite("", name, shareName = name)
            toaster.show(context.getString(R.string.t_favorites_added, name))
        }
    },
    copyPath = { name ->
        val machine = location.machine
        val host = machine?.ordered?.firstOrNull()?.host.orEmpty()
        copy(context, dev.hfps.hoard.app.files.uncPath(host, name, ""), toaster)
    },
    remove = { name ->
        scope.launch {
            viewModel.removeManualShare(name)
            toaster.show(context.getString(R.string.t_browse_share_removed, name))
        }
    },
)

private fun nameValidator(context: Context, entries: List<SmbEntry>, except: String?): (String) -> String? = { value ->
    when {
        !isValidName(value) -> context.getString(R.string.t_browse_name_invalid)
        entries.any { it.name.equals(value.trim(), ignoreCase = true) && it.name != except } -> context.getString(R.string.t_browse_name_taken)
        else -> null
    }
}

/** Why writing is off here (Hoard 1's `useReadOnlyReason`). */
@Composable
fun readOnlyReason(location: BrowseLocation?, path: String): String {
    if (location != null && !location.isSmb) {
        return stringResource(if (path.isEmpty()) R.string.t_access_drive_read_only else R.string.t_access_local_folder_read_only)
    }
    val machine = location?.machine?.machine
    val user = machine?.username?.trim()?.takeIf { it.isNotEmpty() }?.let { u -> machine.domain?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it\\$u" } ?: u }
    val share = path.isEmpty()
    return when {
        user == null && share -> stringResource(R.string.t_access_share_read_only_anonymous)
        user == null -> stringResource(R.string.t_access_folder_read_only_anonymous)
        share -> stringResource(R.string.t_access_share_read_only, user)
        else -> stringResource(R.string.t_access_folder_read_only, user)
    }
}

// --- Header -----------------------------------------------------------------------------------------------------------

@Composable
private fun HeaderMenu(location: BrowseLocation, showHidden: Boolean, nav: BrowseNav, viewModel: BrowseViewModel) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val container = (context.applicationContext as dev.hfps.hoard.app.HoardApplication).container
    val settings = container.appSettings
    // ADR 0025: a share folder a mapping places on the server can be compressed there (when the server offers it).
    val offer by container.compress.nasOffer.collectAsState()
    val machineId = location.machine?.machine?.id
    val share = location.share
    // Resolved when the menu opens: the folder's mapping may be learned after the folder opened.
    val compressFolder by androidx.compose.runtime.produceState<dev.hfps.hoard.app.compress.CompressFolder?>(null, machineId, share, viewModel.path, offer, open) {
        value = if (offer != dev.hfps.hoard.server.compress.CompressOffer.Hidden && location.isSmb && machineId != null && share != null) {
            runCatching { container.compress.folderOf(machineId, share, viewModel.path) }.getOrNull()
        } else {
            null
        }
    }
    var compressing by remember { mutableStateOf<dev.hfps.hoard.app.ui.compress.CompressRequest?>(null) }
    compressing?.let { request -> dev.hfps.hoard.app.ui.compress.CompressSheet(container, request, onDismiss = { compressing = null }) }
    Box {
        HfpsIconButton(PhosphorRegular.DotsThreeVertical, stringResource(R.string.t_common_more), { open = true })
        DropdownMenu(open, { open = false }, containerColor = HfpsTheme.colors.card) {
            compressFolder?.let { folder ->
                val part = dev.hfps.hoard.app.compress.CompressPart(dev.hfps.hoard.server.api.Compress.TARGET_NAS, folder.select)
                MenuItem(stringResource(R.string.compress_action_folder), PhosphorRegular.ArrowsIn, {
                    open = false
                    compressing = dev.hfps.hoard.app.ui.compress.CompressRequest(listOf(part))
                }, modifier = Modifier.testTag("browse-compress-folder"))
                MenuItem(stringResource(R.string.compress_auto_title), PhosphorRegular.ArrowsClockwise, {
                    open = false
                    compressing = dev.hfps.hoard.app.ui.compress.CompressRequest(listOf(part), autoFolder = folder)
                }, modifier = Modifier.testTag("browse-compress-auto"))
                HorizontalDivider(color = HfpsTheme.colors.hairline)
            }
            MenuItem(
                stringResource(R.string.t_browse_show_hidden),
                if (showHidden) PhosphorRegular.EyeSlash else PhosphorRegular.Eye,
                {
                    open = false
                    scope.launch { settings.setShowHidden(!showHidden) }
                },
                trailing = { if (showHidden) Icon(PhosphorRegular.Check, null, Modifier.size(18.dp)) },
            )
            MenuItem(stringResource(R.string.t_browse_refresh), PhosphorRegular.ArrowsClockwise, {
                open = false
                viewModel.load(quiet = true)
            })
            HorizontalDivider(color = HfpsTheme.colors.hairline)
            val machine = location.machine
            if (machine != null) {
                MenuItem(stringResource(R.string.t_machines_edit), PhosphorRegular.PencilSimple, {
                    open = false
                    nav.editMachine(machine.machine.id)
                })
            } else {
                MenuItem(
                    stringResource(if (location.kind == LocationKind.Volume) R.string.t_locations_eject_in_settings else R.string.t_locations_storage_settings),
                    if (location.kind == LocationKind.Volume) PhosphorRegular.Eject else PhosphorRegular.Gear,
                    {
                        open = false
                        val intent = listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                            .firstOrNull { it.resolveActivity(context.packageManager) != null }
                        intent?.let { context.findActivity()?.startActivity(it) }
                    },
                )
            }
        }
    }
}

/** Breadcrumbs (location › share › folders) and the status line (route, or the drive's state; read-only badge). */
@Composable
private fun Trail(state: BrowseUiState, location: BrowseLocation?, nav: BrowseNav, readOnlyReason: String) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
        if (location != null) {
            val crumbs = buildList {
                add(Crumb(location.name, location.id, if (location.isSmb) null else "", ""))
                if (location.isSmb && state.share != null) add(Crumb(state.share, location.id, state.share, ""))
                val segments = splitPath(state.path)
                segments.forEachIndexed { index, segment ->
                    add(Crumb(segment, location.id, state.share, segments.take(index + 1).joinToString("/")))
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                crumbs.forEachIndexed { index, crumb ->
                    if (index > 0) Icon(PhosphorRegular.CaretRight, null, Modifier.size(12.dp), tint = HfpsTheme.colors.mutedForeground)
                    val last = index == crumbs.lastIndex
                    Text(
                        crumb.label,
                        style = HfpsTheme.typography.ui.copy(fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (last) HfpsTheme.colors.cardForeground else HfpsTheme.colors.mutedForeground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .combinedClickable(enabled = !last, onClick = {
                                if (crumb.share == null && location.isSmb) nav.open(crumb.locationId, null, "") else nav.open(crumb.locationId, crumb.share?.ifEmpty { null }, crumb.path)
                            })
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                if (location.isSmb) {
                    RouteLine(state.route)
                } else {
                    LocationStatus(location)
                }
                if (state.readOnly) ReadOnlyBadge(readOnlyReason)
            }
        }
    }
}

private data class Crumb(val label: String, val locationId: String, val share: String?, val path: String)

@Composable
fun LocationStatus(location: BrowseLocation) {
    val volume = location.volume
    val text = when {
        volume == null -> if (location.kind == LocationKind.Volume) stringResource(R.string.t_locations_not_connected) else null
        volume.freeBytes != null && volume.totalBytes != null ->
            stringResource(R.string.t_locations_free_of, bytes(volume.freeBytes!!), bytes(volume.totalBytes!!))
        else -> null
    } ?: return
    Text(text, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, maxLines = 1)
}

@Composable
fun ReadOnlyBadge(reason: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(HfpsTheme.colors.muted)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = reason },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(PhosphorRegular.LockSimple, null, Modifier.size(12.dp), tint = HfpsTheme.colors.mutedForeground)
        Text(stringResource(R.string.t_access_read_only), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
    }
}

// --- Selection --------------------------------------------------------------------------------------------------------

/** Like Android's contextual action bar: the selection's actions replace the top bar while selecting. */
@Composable
private fun SelectionBar(
    state: BrowseUiState,
    readOnlyReason: String?,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    actions: RowActions,
    onDelete: () -> Unit,
) {
    val selected = state.selectedEntries
    val none = selected.isEmpty()
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HfpsIconButton(PhosphorRegular.X, stringResource(R.string.t_browse_clear_selection), onClear)
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(
                pluralStringResource(R.plurals.t_browse_selected, selected.size, selected.size.toString()),
                style = HfpsTheme.typography.title,
                color = HfpsTheme.colors.foreground,
                maxLines = 1,
            )
            if (!none) Text(bytes(selected.sumOf { it.size }), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        }
        HfpsIconButton(PhosphorRegular.Copy, stringResource(R.string.t_transfer_copy), { actions.clipboard("copy", selected) }, enabled = !none)
        HfpsIconButton(PhosphorRegular.Scissors, stringResource(R.string.t_transfer_move), { actions.clipboard("move", selected) }, enabled = !none && readOnlyReason == null)
        HfpsIconButton(PhosphorRegular.ShareNetwork, stringResource(R.string.t_transfer_share), { actions.share(selected) }, enabled = !none)
        HfpsIconButton(PhosphorRegular.Trash, stringResource(R.string.t_common_delete), onDelete, enabled = !none && readOnlyReason == null, tint = HfpsTheme.colors.destructive)
        Box {
            HfpsIconButton(PhosphorRegular.DotsThreeVertical, stringResource(R.string.t_common_more), { menu = true })
            DropdownMenu(menu, { menu = false }, containerColor = HfpsTheme.colors.card) {
                MenuItem(stringResource(R.string.t_transfer_copy_to), PhosphorRegular.FolderSimple, { menu = false; actions.transferTo(TransferIntent.Copy, selected) }, enabled = !none)
                MenuItem(stringResource(R.string.t_transfer_move_to), PhosphorRegular.ArrowsLeftRight, { menu = false; actions.transferTo(TransferIntent.Move, selected) }, enabled = !none && readOnlyReason == null)
                if (state.location?.isSmb == true) {
                    MenuItem(stringResource(R.string.t_browse_download), PhosphorRegular.DownloadSimple, { menu = false; actions.transferTo(TransferIntent.Download, selected) }, enabled = !none)
                } else {
                    MenuItem(stringResource(R.string.t_transfer_upload), PhosphorRegular.UploadSimple, { menu = false; actions.transferTo(TransferIntent.Upload, selected) }, enabled = !none)
                }
                val reveal = actions.reveal
                if (reveal != null && hasPhotos(selected)) {
                    MenuItem(stringResource(R.string.t_reveal_edit), PhosphorRegular.SlidersHorizontal, { menu = false; reveal(selected) })
                }
                if (hasPhotos(selected)) {
                    MenuItem(stringResource(R.string.xmp_export), PhosphorRegular.FileText, { menu = false; actions.exportXmp(selected) })
                }
                HorizontalDivider(color = HfpsTheme.colors.hairline)
                MenuItem(stringResource(R.string.t_browse_select_all), PhosphorRegular.CheckSquare, { menu = false; onSelectAll() })
                if (readOnlyReason != null) ReadOnlyNote(readOnlyReason)
            }
        }
    }
}

@Composable
private fun ReadOnlyNote(reason: String) {
    Text(
        reason,
        style = HfpsTheme.typography.ui,
        color = HfpsTheme.colors.mutedForeground,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(240.dp),
    )
}

// --- Rows ---------------------------------------------------------------------------------------------------------------

/** What a row's menu (and the selection bar) can do. */
class RowActions(
    val open: (SmbEntry) -> Unit,
    val openWith: (SmbEntry) -> Unit,
    val openInViewer: (SmbEntry) -> Unit,
    val clipboard: (String, List<SmbEntry>) -> Unit,
    val transferTo: (TransferIntent, List<SmbEntry>) -> Unit,
    val share: (List<SmbEntry>) -> Unit,
    /** Null when Reveal is not installed. */
    val reveal: ((List<SmbEntry>) -> Unit)?,
    /** "Export XMP" of the selected photos' shots, or of a folder (ADR 0011 amendment "2.2.0"). */
    val exportXmp: (List<SmbEntry>) -> Unit = {},
    val favorite: (SmbEntry) -> Unit,
    val copyPath: (SmbEntry) -> Unit,
    val rename: (SmbEntry) -> Unit,
    val select: (SmbEntry) -> Unit,
    val delete: (SmbEntry) -> Unit,
)

class ShareActions(val favorite: (String) -> Unit, val copyPath: (String) -> Unit, val remove: (String) -> Unit)

private fun androidx.compose.foundation.lazy.LazyListScope.folderContent(
    state: BrowseUiState,
    location: BrowseLocation,
    viewModel: BrowseViewModel,
    rowActions: RowActions,
    readOnlyReason: String,
    onNewFolder: () -> Unit,
    onNewTextFile: () -> Unit,
    onBackHome: () -> Unit,
    onUp: () -> Unit,
    onEditMachine: (String) -> Unit,
) {
    val entries = state.listing.entries
    val error = state.listing.error
    if (!state.inSelection && entries != null && entries.isNotEmpty()) {
        item(key = "toolbar", contentType = "toolbar") {
            Toolbar(state, viewModel, readOnlyReason, onNewFolder, onNewTextFile)
        }
    }
    when {
        error != null && entries == null -> item(key = "error") {
            LocationError(error, location, state.listing.errorRoute != null, state.listing.loading, viewModel::retry, onBackHome, onUp, onEditMachine, state.listing.errorRoute?.attempts.orEmpty())
        }
        entries == null -> items(8, contentType = { "skeleton" }) { SkeletonRow(it) }
        entries.isEmpty() -> item(key = "empty") {
            StateMessage(
                PhosphorRegular.FolderPlus,
                stringResource(R.string.t_browse_empty_title),
                body = if (state.readOnly) readOnlyReason else stringResource(R.string.t_browse_empty_body),
            ) {
                SecondaryButton(stringResource(R.string.t_browse_new_folder), onNewFolder, icon = PhosphorRegular.FolderPlus, enabled = !state.readOnly)
                SecondaryButton(stringResource(R.string.t_browse_new_text_file), onNewTextFile, icon = PhosphorRegular.FilePlus, enabled = !state.readOnly)
            }
        }
        state.visible.isEmpty() -> item(key = "nomatch") {
            StateMessage(
                PhosphorRegular.MagnifyingGlass,
                stringResource(R.string.t_browse_no_matches_title),
                body = if (entries.all { it.isHidden } && !state.showHidden) stringResource(R.string.t_browse_only_hidden) else stringResource(R.string.t_browse_no_matches_body),
            ) {
                if (state.query.isNotEmpty() || state.filter != FileFilter.All) {
                    SecondaryButton(stringResource(R.string.t_browse_clear_filters), {
                        viewModel.setQuery("")
                        viewModel.setFilter(FileFilter.All)
                    })
                }
            }
        }
        else -> {
            items(state.visible, key = { it.path }, contentType = { "row" }) { entry ->
                FileRow(
                    entry = entry,
                    location = location,
                    selected = entry.path in state.selected,
                    selectionMode = state.inSelection,
                    opening = state.opening == entry.path,
                    readOnly = state.readOnly,
                    readOnlyReason = readOnlyReason,
                    actions = rowActions,
                    onToggle = { viewModel.toggle(entry) },
                    onLongPress = { viewModel.startSelection(entry) },
                    onServer = entry.path in state.listing.onServer,
                )
            }
            item(key = "footer", contentType = "footer") {
                val total = state.visible.sumOf { it.size }
                val count = state.visible.size
                Text(
                    pluralStringResource(R.plurals.t_browse_count, count, formatNumber(count, locale())) + if (total > 0) " · " + bytes(total) else "",
                    style = HfpsTheme.typography.ui,
                    color = HfpsTheme.colors.mutedForeground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sharesContent(
    state: BrowseUiState,
    location: BrowseLocation,
    nav: BrowseNav,
    viewModel: BrowseViewModel,
    actions: ShareActions,
    onOpenByName: () -> Unit,
    onRetry: () -> Unit,
    onEditMachine: (String) -> Unit,
) {
    val shares = state.listing.shares
    val error = state.listing.error
    when {
        error != null && shares == null -> item(key = "error") {
            LocationError(error, location, state.listing.errorRoute != null, state.listing.loading, onRetry, nav.home, nav.back, onEditMachine, state.listing.errorRoute?.attempts.orEmpty())
        }
        shares == null -> items(6, contentType = { "skeleton" }) { SkeletonRow(it) }
        shares.isEmpty() -> item(key = "empty") {
            StateMessage(PhosphorRegular.HardDrive, stringResource(R.string.t_browse_no_shares_title), body = stringResource(R.string.t_browse_no_shares_body)) {
                SecondaryButton(stringResource(R.string.t_browse_open_share_by_name), onOpenByName, icon = PhosphorRegular.Plus)
            }
        }
        else -> {
            item(key = "shares-title") {
                dev.hfps.hoard.designsystem.components.Eyebrow(stringResource(R.string.t_browse_shares), Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
            }
            items(shares, key = { "share-" + it.name }) { share ->
                ShareRow(share, location, nav, actions, readOnlyReasonText = null)
                HfpsRowDivider(Modifier.padding(start = 68.dp))
            }
            item(key = "open-by-name") {
                Box(Modifier.padding(16.dp)) { SecondaryButton(stringResource(R.string.t_browse_open_share_by_name), onOpenByName, icon = PhosphorRegular.Plus) }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") viewModel
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShareRow(share: ShareItem, location: BrowseLocation, nav: BrowseNav, actions: ShareActions, readOnlyReasonText: String?) {
    var menu by remember { mutableStateOf(false) }
    val reason = readOnlyReason(location, "")
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .combinedClickable(onClick = { nav.open(location.id, share.name, "") })
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(HfpsTheme.colors.primary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(PhosphorRegular.HardDrive, null, Modifier.size(20.dp), tint = HfpsTheme.colors.primary)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(share.name, style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                if (share.access == dev.hfps.hoard.smb.SmbAccess.READ) ReadOnlyBadge(readOnlyReasonText ?: reason)
            }
            val subtitle = share.comment?.takeIf { it.isNotBlank() } ?: if (share.manual) stringResource(R.string.t_browse_added_by_name) else null
            if (subtitle != null) Text(subtitle, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            HfpsIconButton(PhosphorRegular.DotsThreeVertical, stringResource(R.string.t_browse_actions_for, share.name), { menu = true })
            DropdownMenu(menu, { menu = false }, containerColor = HfpsTheme.colors.card) {
                MenuItem(stringResource(R.string.t_favorites_add), PhosphorRegular.Star, { menu = false; actions.favorite(share.name) })
                MenuItem(stringResource(R.string.t_browse_copy_path), PhosphorRegular.Copy, { menu = false; actions.copyPath(share.name) })
                if (share.manual) {
                    HorizontalDivider(color = HfpsTheme.colors.hairline)
                    MenuItem(stringResource(R.string.t_browse_remove_share), PhosphorRegular.Trash, { menu = false; actions.remove(share.name) }, destructive = true)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: SmbEntry,
    location: BrowseLocation,
    selected: Boolean,
    selectionMode: Boolean,
    opening: Boolean,
    readOnly: Boolean,
    readOnlyReason: String,
    actions: RowActions,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    /** The paired server holds this file (ADR 0022): the Gallery's "on the server" mark. */
    onServer: Boolean = false,
) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val kind = fileKind(entry)
    val container = (context.applicationContext as dev.hfps.hoard.app.HoardApplication).container
    val thumbnailKey = if (hasThumbnail(entry)) "${location.cacheKey}/${entry.path}:${entry.size}:${entry.modifiedAt}" else null
    var menu by remember { mutableStateOf(false) }
    val details = when {
        entry.restricted -> stringResource(R.string.t_browse_restricted_short)
        entry.isDirectory -> relativeDate(context, entry.modifiedAt)
        else -> bytes(entry.size) + " · " + relativeDate(context, entry.modifiedAt)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) HfpsTheme.colors.primary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(
                onClick = { if (selectionMode) onToggle() else actions.open(entry) },
                onLongClick = onLongPress,
            )
            .defaultMinSize(minHeight = 64.dp)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
            .testTag("row-" + entry.name),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FileTile(
            kind = kind,
            thumbnailKey = thumbnailKey,
            load = thumbnailKey?.let { { container.files.thumbnail(location, entry.path, 160).file } },
        )
        Column(Modifier.weight(1f)) {
            FileNameText(
                entry.name,
                style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                color = if (entry.isHidden) HfpsTheme.colors.mutedForeground else HfpsTheme.colors.cardForeground,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onServer) {
                    val label = stringResource(R.string.gallery_badge_server)
                    Icon(PhosphorRegular.Cloud, label, Modifier.size(14.dp).testTag("badge-server"), tint = HfpsTheme.colors.primary)
                }
                Text(details, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, maxLines = 1)
            }
        }
        when {
            opening -> CircularProgressIndicator(Modifier.size(20.dp).padding(end = 12.dp), strokeWidth = 2.dp, color = HfpsTheme.colors.primary)
            selectionMode -> Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = HfpsTheme.colors.primary, uncheckedColor = HfpsTheme.colors.mutedForeground),
            )
            else -> Box {
                HfpsIconButton(PhosphorRegular.DotsThreeVertical, stringResource(R.string.t_browse_actions_for, entry.name), { menu = true })
                DropdownMenu(menu, { menu = false }, containerColor = HfpsTheme.colors.card) {
                    RowMenu(entry, location, readOnly, readOnlyReason, actions) { menu = false }
                }
            }
        }
    }
}

@Composable
private fun RowMenu(entry: SmbEntry, location: BrowseLocation, readOnly: Boolean, readOnlyReason: String, actions: RowActions, close: () -> Unit) {
    fun run(block: () -> Unit): () -> Unit = {
        close()
        block()
    }
    if (!entry.isDirectory && isTextFile(entry.name)) {
        MenuItem(stringResource(R.string.t_browse_open_in_viewer), PhosphorRegular.FileText, run { actions.openInViewer(entry) })
    }
    if (!entry.isDirectory) MenuItem(stringResource(R.string.t_browse_open_with), PhosphorRegular.ArrowSquareOut, run { actions.openWith(entry) })
    if (!entry.restricted) {
        HorizontalDivider(color = HfpsTheme.colors.hairline)
        MenuItem(stringResource(R.string.t_transfer_copy), PhosphorRegular.Copy, run { actions.clipboard("copy", listOf(entry)) })
        MenuItem(stringResource(R.string.t_transfer_move), PhosphorRegular.Scissors, run { actions.clipboard("move", listOf(entry)) }, enabled = !readOnly)
        MenuItem(stringResource(R.string.t_transfer_copy_to), PhosphorRegular.FolderSimple, run { actions.transferTo(TransferIntent.Copy, listOf(entry)) })
        MenuItem(stringResource(R.string.t_transfer_move_to), PhosphorRegular.ArrowsLeftRight, run { actions.transferTo(TransferIntent.Move, listOf(entry)) }, enabled = !readOnly)
        if (location.isSmb) {
            MenuItem(stringResource(R.string.t_browse_download), PhosphorRegular.DownloadSimple, run { actions.transferTo(TransferIntent.Download, listOf(entry)) })
        } else {
            MenuItem(stringResource(R.string.t_transfer_upload), PhosphorRegular.UploadSimple, run { actions.transferTo(TransferIntent.Upload, listOf(entry)) })
        }
        if (!entry.isDirectory) MenuItem(stringResource(R.string.t_transfer_share), PhosphorRegular.ShareNetwork, run { actions.share(listOf(entry)) })
        val reveal = actions.reveal
        if (reveal != null && hasPhotos(listOf(entry))) {
            MenuItem(stringResource(R.string.t_reveal_edit), PhosphorRegular.SlidersHorizontal, run { reveal(listOf(entry)) })
        }
        if (entry.isDirectory || hasPhotos(listOf(entry))) {
            MenuItem(stringResource(R.string.xmp_export), PhosphorRegular.FileText, run { actions.exportXmp(listOf(entry)) })
        }
        HorizontalDivider(color = HfpsTheme.colors.hairline)
    }
    if (entry.isDirectory && !entry.restricted) MenuItem(stringResource(R.string.t_favorites_add), PhosphorRegular.Star, run { actions.favorite(entry) })
    MenuItem(stringResource(R.string.t_browse_copy_path), PhosphorRegular.Copy, run { actions.copyPath(entry) })
    MenuItem(stringResource(R.string.t_browse_rename), PhosphorRegular.PencilSimple, run { actions.rename(entry) }, enabled = !readOnly)
    MenuItem(stringResource(R.string.t_browse_select_mode), PhosphorRegular.CheckSquare, run { actions.select(entry) })
    HorizontalDivider(color = HfpsTheme.colors.hairline)
    MenuItem(stringResource(R.string.t_common_delete), PhosphorRegular.Trash, run { actions.delete(entry) }, enabled = !readOnly, destructive = true)
    if (readOnly) ReadOnlyNote(readOnlyReason)
}

// --- Toolbar ------------------------------------------------------------------------------------------------------------

@Composable
private fun Toolbar(state: BrowseUiState, viewModel: BrowseViewModel, readOnlyReason: String, onNewFolder: () -> Unit, onNewTextFile: () -> Unit) {
    var filterMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SearchField(state.query, viewModel::setQuery, Modifier.weight(1f))
        Box {
            HfpsIconButton(PhosphorRegular.Funnel, stringResource(R.string.t_browse_filter), { filterMenu = true }, active = state.filter != FileFilter.All)
            DropdownMenu(filterMenu, { filterMenu = false }, containerColor = HfpsTheme.colors.card) {
                FileFilter.entries.forEach { f ->
                    MenuItem(stringResource(filterText(f)), null, { filterMenu = false; viewModel.setFilter(f) }, trailing = {
                        if (f == state.filter) Icon(PhosphorRegular.Check, null, Modifier.size(18.dp))
                    })
                }
            }
        }
        Box {
            HfpsIconButton(if (state.sort.ascending) PhosphorRegular.SortAscending else PhosphorRegular.SortDescending, stringResource(R.string.t_browse_sort), { sortMenu = true })
            DropdownMenu(sortMenu, { sortMenu = false }, containerColor = HfpsTheme.colors.card) {
                SortKey.entries.forEach { key ->
                    listOf(true, false).forEach { asc ->
                        val order = SortOrder(key, asc)
                        MenuItem(
                            stringResource(sortKeyText(key)) + " · " + stringResource(sortDirectionText(key, asc)),
                            null,
                            { sortMenu = false; viewModel.setSort(order) },
                            trailing = { if (order == state.sort) Icon(PhosphorRegular.Check, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }
        Box {
            HfpsIconButton(PhosphorRegular.Plus, stringResource(R.string.t_browse_new_menu), { newMenu = true })
            DropdownMenu(newMenu, { newMenu = false }, containerColor = HfpsTheme.colors.card) {
                MenuItem(stringResource(R.string.t_browse_new_folder), PhosphorRegular.FolderPlus, { newMenu = false; onNewFolder() }, enabled = !state.readOnly)
                MenuItem(stringResource(R.string.t_browse_new_text_file), PhosphorRegular.FilePlus, { newMenu = false; onNewTextFile() }, enabled = !state.readOnly)
                if (state.readOnly) ReadOnlyNote(readOnlyReason)
            }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String = stringResource(R.string.t_browse_search)) {
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(HfpsTheme.colors.muted)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(PhosphorRegular.MagnifyingGlass, null, Modifier.size(18.dp), tint = HfpsTheme.colors.mutedForeground)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(label, style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = HfpsTheme.typography.body.copy(color = HfpsTheme.colors.cardForeground),
                cursorBrush = SolidColor(HfpsTheme.colors.primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                PhosphorRegular.XCircle,
                stringResource(R.string.t_browse_clear_search),
                Modifier.size(18.dp).clip(RoundedCornerShape(50)).combinedClickableCompat { onChange("") },
                tint = HfpsTheme.colors.mutedForeground,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

// --- States -------------------------------------------------------------------------------------------------------------

@Composable
private fun LocationError(
    code: ErrorCode,
    location: BrowseLocation,
    routeFailure: Boolean,
    retrying: Boolean,
    onRetry: () -> Unit,
    onHome: () -> Unit,
    onUp: () -> Unit,
    onEditMachine: (String) -> Unit,
    attempts: List<dev.hfps.hoard.app.route.AddressAttempt> = emptyList(),
) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val title = when (code) {
        ErrorCode.UNAVAILABLE -> stringResource(R.string.t_locations_unavailable_title, location.name)
        else -> stringResource(errorTitle(code))
    }
    val body = when (code) {
        ErrorCode.UNAVAILABLE -> stringResource(R.string.t_locations_unavailable_body)
        ErrorCode.STORAGE_PERMISSION -> stringResource(if (location.kind == LocationKind.Volume) R.string.t_locations_permission_volume_body else R.string.t_locations_permission_body)
        else -> stringResource(errorBody(code))
    }
    // Unreachable, timed out or a different server: which addresses were tried and what each answered (SmbErrorState).
    val showRoutes = routeFailure && (code == ErrorCode.UNREACHABLE || code == ErrorCode.TIMEOUT || code == ErrorCode.GUID_MISMATCH) && attempts.isNotEmpty()
    if (showRoutes) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            StateMessage(PhosphorRegular.WarningCircle, title, body = body, tint = HfpsTheme.colors.destructive) { LocationErrorActions(code, location, routeFailure, retrying, onRetry, onUp, onEditMachine) }
            TriedRoutes(attempts, Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp))
        }
        return
    }
    StateMessage(PhosphorRegular.WarningCircle, title, body = body, tint = HfpsTheme.colors.destructive) { LocationErrorActions(code, location, routeFailure, retrying, onRetry, onUp, onEditMachine) }
    @Suppress("UNUSED_EXPRESSION") onHome
}

@Composable
private fun LocationErrorActions(
    code: ErrorCode,
    location: BrowseLocation,
    routeFailure: Boolean,
    retrying: Boolean,
    onRetry: () -> Unit,
    onUp: () -> Unit,
    onEditMachine: (String) -> Unit,
) {
    val context = LocalContext.current
    run {
        when {
            code == ErrorCode.STORAGE_PERMISSION -> SecondaryButton(stringResource(R.string.t_locations_permission_allow), {
                dev.hfps.hoard.app.ui.storage.StorageAccessIntents.open(context)
            })
            routeFailure && (code == ErrorCode.AUTH_FAILED || code == ErrorCode.GUID_MISMATCH) && location.machine != null ->
                SecondaryButton(stringResource(if (code == ErrorCode.AUTH_FAILED) R.string.t_errors_edit_credentials else R.string.t_errors_review_machine), {
                    onEditMachine(location.machine.machine.id)
                })
            code == ErrorCode.NOT_FOUND -> SecondaryButton(stringResource(R.string.t_locations_go_up), onUp)
            else -> Unit
        }
        SecondaryButton(stringResource(R.string.t_common_retry), onRetry, enabled = !retrying, icon = PhosphorRegular.ArrowsClockwise)
    }
}

/** One line per address tried: host, kind and what happened (Hoard 1's TriedRoutes). */
@Composable
fun TriedRoutes(attempts: List<dev.hfps.hoard.app.route.AddressAttempt>, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, HfpsTheme.colors.border, RoundedCornerShape(10.dp)).testTag("tried-routes"),
    ) {
        attempts.forEachIndexed { i, attempt ->
            if (i > 0) HorizontalDivider(color = HfpsTheme.colors.hairline)
            val connectCode = attempt.connect?.takeIf { it != "ok" }?.let { name -> ErrorCode.entries.firstOrNull { it.name == name } ?: ErrorCode.UNKNOWN }
            val outcome = when {
                connectCode != null -> stringResource(problemText(connectCode))
                attempt.probe != null -> if (attempt.probe.reachable) {
                    stringResource(R.string.t_route_reachable, (attempt.probe.latencyMs ?: 0).toString())
                } else {
                    stringResource(problemText(attempt.probe.reason ?: ErrorCode.UNREACHABLE))
                }
                else -> stringResource(R.string.t_route_not_tried)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(attempt.host + if (attempt.port != 445) ":${attempt.port}" else "", style = HfpsTheme.typography.monoSmall, color = HfpsTheme.colors.cardForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(addressKindText(attempt.kind)) + " · " + outcome, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            }
        }
    }
}

@Composable
fun SkeletonRow(index: Int) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(HfpsTheme.colors.muted))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.height(14.dp).fillMaxWidth(0.4f + ((index * 37) % 45) / 100f).clip(RoundedCornerShape(4.dp)).background(HfpsTheme.colors.muted))
            Box(Modifier.height(10.dp).width(96.dp).clip(RoundedCornerShape(4.dp)).background(HfpsTheme.colors.muted))
        }
    }
    Spacer(Modifier.height(0.dp))
}
