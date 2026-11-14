package dev.hfps.hoard.app.ui.transfers

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import dev.hfps.hoard.app.HoardApplication
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.BrowseLocation
import dev.hfps.hoard.app.files.FileService
import dev.hfps.hoard.app.files.LocationKind
import dev.hfps.hoard.app.files.isValidName
import dev.hfps.hoard.app.files.joinPath
import dev.hfps.hoard.app.files.parentPath
import dev.hfps.hoard.app.files.splitPath
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.app.transfers.CONFLICT_POLICIES
import dev.hfps.hoard.app.transfers.FolderPlace
import dev.hfps.hoard.app.transfers.PasteProblem
import dev.hfps.hoard.app.transfers.PickedEntry
import dev.hfps.hoard.app.transfers.TREE_LOCATION_ID
import dev.hfps.hoard.app.transfers.TransferPlace
import dev.hfps.hoard.app.transfers.pasteProblem
import dev.hfps.hoard.app.ui.common.MenuItem
import dev.hfps.hoard.app.ui.common.NameDialog
import dev.hfps.hoard.app.ui.common.StateMessage
import dev.hfps.hoard.app.ui.common.conflictHint
import dev.hfps.hoard.app.ui.common.conflictText
import dev.hfps.hoard.app.ui.common.errorBody
import dev.hfps.hoard.app.ui.common.errorTitle
import dev.hfps.hoard.app.ui.files.machineIcon
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SmbAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why the user is picking: the words differ, the engine is the same (ADR 0007). */
enum class TransferIntent { Copy, Move, Download, Upload, Save }

/** A folder being browsed in the picker. */
private data class PickerFolder(val location: BrowseLocation, val share: String?, val path: String) {
    val place: FolderPlace? get() = if (location.isSmb && share == null) null else FolderPlace(TransferPlace(location.id, share), path)
    val label: String get() = splitPath(path).lastOrNull() ?: share ?: location.name
}

private data class PickerListing(val folders: List<String>? = null, val shares: List<String>? = null, val readOnly: Boolean = false, val error: ErrorCode? = null)

/**
 * "Copy to…", "Move to…", "Download", "Upload" and "Save to…" (src/features/transfers/DestinationPicker.tsx): a full
 * screen over the browser with every place (recent, this phone, drives, favourites, machines, Android folders), then
 * their folders; the conflict policy (the default from Settings) and the action. [onPick] starts the transfer.
 */
@Composable
fun DestinationPicker(
    intent: TransferIntent,
    count: Int,
    source: FolderPlace?,
    entries: List<PickedEntry>,
    onDismiss: () -> Unit,
    onPick: suspend (FolderPlace, String, String) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as HoardApplication).container
    val scope = rememberCoroutineScope()
    val settings by container.appSettings.files.collectAsState(initial = null)
    val machines by container.database.machines().observeAll().collectAsState(initial = emptyList())
    val favorites by container.database.favorites().observeAll().collectAsState(initial = emptyList())
    val volumes by container.volumes.observe().collectAsState(initial = emptyList())
    var trees by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var folder by remember { mutableStateOf<PickerFolder?>(null) }
    var listing by remember { mutableStateOf(PickerListing()) }
    var conflict by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    val policy = conflict ?: settings?.defaultConflict ?: "ask"
    val phone = stringResource(R.string.t_locations_internal)
    val drive = stringResource(R.string.t_locations_drive)

    LaunchedEffect(Unit) { trees = withContext(Dispatchers.IO) { runCatching { container.destinations.list().map { it.uri to it.name } }.getOrDefault(emptyList()) } }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { runCatching { container.destinations.persist(uri) }.getOrNull() } ?: return@launch
            trees = trees.filterNot { it.first == picked.uri } + (picked.uri to picked.name)
        }
    }

    LaunchedEffect(folder, reload) {
        val current = folder ?: return@LaunchedEffect
        listing = PickerListing()
        listing = try {
            if (current.location.isSmb && current.share == null) {
                PickerListing(shares = container.files.listShares(current.location.machine!!).filter { !it.info.isHidden }.map { it.info.name })
            } else {
                val loc = current.location.copy(share = current.share)
                val result = container.files.run(loc) { fs, cancel -> fs.list(current.path, cancel) }
                PickerListing(
                    folders = result.entries.filter { it.isDirectory && !it.isHidden && !it.restricted }.map { it.name }
                        .sortedWith(dev.hfps.hoard.app.files.NaturalComparator(java.text.Collator.getInstance())),
                    readOnly = result.access == SmbAccess.READ,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            PickerListing(error = errorCodeOf(e))
        }
    }

    BackHandler {
        val current = folder
        when {
            current == null -> onDismiss()
            current.path.isNotEmpty() -> folder = current.copy(path = parentPath(current.path) ?: "")
            current.location.isSmb && current.share != null -> folder = current.copy(share = null)
            else -> folder = null
        }
    }

    val title = when (intent) {
        TransferIntent.Copy -> pluralStringResource(R.plurals.t_transfer_title_copy, count, count.toString())
        TransferIntent.Move -> pluralStringResource(R.plurals.t_transfer_title_move, count, count.toString())
        TransferIntent.Download -> pluralStringResource(R.plurals.t_transfer_title_download, count, count.toString())
        TransferIntent.Upload -> pluralStringResource(R.plurals.t_transfer_title_upload, count, count.toString())
        TransferIntent.Save -> pluralStringResource(R.plurals.t_save_picker_title, count, count.toString())
    }
    val actionLabel = stringResource(
        when (intent) {
            TransferIntent.Move -> R.string.t_transfer_move_here
            TransferIntent.Save -> R.string.t_save_here
            else -> R.string.t_transfer_copy_here
        },
    )
    val op = if (intent == TransferIntent.Move) "move" else "copy"

    fun pick(place: FolderPlace, label: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                onPick(place, label, policy)
            } finally {
                busy = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        HfpsScaffold(
            topBar = {
                HfpsTopBar(
                    title = folder?.label ?: title,
                    onBack = {
                        val current = folder
                        when {
                            current == null -> onDismiss()
                            current.path.isNotEmpty() -> folder = current.copy(path = parentPath(current.path) ?: "")
                            current.location.isSmb && current.share != null -> folder = current.copy(share = null)
                            else -> folder = null
                        }
                    },
                    backContentDescription = stringResource(R.string.t_common_back),
                ) {
                    HfpsIconButton(PhosphorRegular.X, stringResource(R.string.t_common_cancel), onDismiss)
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    val current = folder
                    if (current == null) {
                        places(
                            recent = settings?.recentDestinations.orEmpty(),
                            volumes = volumes,
                            machines = machines,
                            favorites = favorites,
                            trees = trees,
                            phone = phone,
                            drive = drive,
                            onRecent = { r ->
                                if (r.locationId == TREE_LOCATION_ID) pick(r.folder, r.label)
                                else FileService.resolve(r.locationId, r.share, machines, volumes, phone, drive)?.let { folder = PickerFolder(it, r.share, r.path) }
                            },
                            onLocation = { loc -> folder = PickerFolder(loc, null, "") },
                            onFavorite = { f ->
                                FileService.resolve(f.locationId, f.share.ifEmpty { null }, machines, volumes, phone, drive)?.let {
                                    folder = PickerFolder(it, f.share.ifEmpty { null }, f.path)
                                }
                            },
                            onTree = { uri, name -> pick(FolderPlace(TransferPlace(TREE_LOCATION_ID, treeUri = uri), ""), name) },
                            onSystemPicker = { treePicker.launch(container.destinations.pickIntent()) },
                        )
                    } else {
                        item {
                            Text(
                                listOfNotNull(current.location.name, current.share, current.path.ifEmpty { null }).joinToString(" / "),
                                style = HfpsTheme.typography.ui,
                                color = HfpsTheme.colors.mutedForeground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        val l = listing
                        when {
                            l.error != null -> item {
                                StateMessage(PhosphorRegular.WarningCircle, stringResource(errorTitle(l.error)), body = stringResource(errorBody(l.error)), tint = HfpsTheme.colors.destructive) {
                                    SecondaryButton(stringResource(R.string.t_common_retry), { reload++ })
                                }
                            }
                            l.shares != null -> items(l.shares) { share ->
                                PlaceRow(PhosphorRegular.HardDrive, share, null) { folder = current.copy(share = share, path = "") }
                                HfpsRowDivider(Modifier.padding(start = 68.dp))
                            }
                            l.folders == null -> item {
                                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp), color = HfpsTheme.colors.primary, strokeWidth = 2.dp)
                                }
                            }
                            l.folders.isEmpty() -> item {
                                Text(stringResource(R.string.t_transfer_no_subfolders), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(16.dp))
                            }
                            else -> items(l.folders) { name ->
                                PlaceRow(PhosphorRegular.FolderSimple, name, null) { folder = current.copy(path = joinPath(current.path, name)) }
                                HfpsRowDivider(Modifier.padding(start = 68.dp))
                            }
                        }
                    }
                }
                // Footer: the conflict policy and the action, for the folder on screen.
                val current = folder
                val place = current?.place
                val problem = if (place != null && source != null) pasteProblem(op, source.place, entries, place) else null
                HorizontalDivider(color = HfpsTheme.colors.hairline)
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val note = when {
                        listing.readOnly && place != null -> stringResource(R.string.t_transfer_read_only_here)
                        problem == PasteProblem.IntoItself -> stringResource(R.string.t_transfer_into_itself)
                        problem == PasteProblem.SameFolder -> stringResource(R.string.t_transfer_same_folder)
                        else -> null
                    }
                    if (note != null) Text(note, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.destructive)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConflictSelector(policy, { conflict = it }, Modifier.weight(1f))
                        if (place != null) {
                            SecondaryButton(stringResource(R.string.t_browse_new_folder), { newFolder = true }, icon = PhosphorRegular.FolderPlus, enabled = !listing.readOnly && listing.folders != null)
                        }
                    }
                    PrimaryButton(
                        text = actionLabel,
                        onClick = { if (place != null && current != null) pick(place, current.label) },
                        enabled = place != null && note == null && listing.folders != null && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (newFolder) {
        val current = folder
        NameDialog(
            title = stringResource(R.string.t_browse_new_folder),
            label = stringResource(R.string.t_browse_folder_name),
            initialValue = stringResource(R.string.t_browse_new_folder_default),
            submitLabel = stringResource(R.string.t_common_create),
            onDismiss = { newFolder = false },
            validate = { v ->
                when {
                    !isValidName(v) -> context.getString(R.string.t_browse_name_invalid)
                    listing.folders.orEmpty().any { it.equals(v.trim(), ignoreCase = true) } -> context.getString(R.string.t_browse_name_taken)
                    else -> null
                }
            },
            onSubmit = { name ->
                if (current == null) return@NameDialog null
                try {
                    container.files.run(current.location.copy(share = current.share)) { fs, _ -> fs.createFolder(current.path, name) }
                    folder = current.copy(path = joinPath(current.path, name))
                    null
                } catch (e: Throwable) {
                    context.getString(errorTitle(errorCodeOf(e)))
                }
            },
        )
    }
}

private fun LazyListScope.places(
    recent: List<dev.hfps.hoard.app.transfers.RecentDestination>,
    volumes: List<dev.hfps.hoard.smb.location.VolumeInfo>,
    machines: List<dev.hfps.hoard.data.MachineWithAddresses>,
    favorites: List<dev.hfps.hoard.data.FavoriteEntity>,
    trees: List<Pair<String, String>>,
    phone: String,
    drive: String,
    onRecent: (dev.hfps.hoard.app.transfers.RecentDestination) -> Unit,
    onLocation: (BrowseLocation) -> Unit,
    onFavorite: (dev.hfps.hoard.data.FavoriteEntity) -> Unit,
    onTree: (String, String) -> Unit,
    onSystemPicker: () -> Unit,
) {
    val knownIds = machines.map { it.machine.id }.toSet() + volumes.map { FileService.locationIdOf(it) } + TREE_LOCATION_ID
    val live = recent.filter { it.locationId in knownIds }
    if (live.isNotEmpty()) {
        item { Section(R.string.t_transfer_recent) }
        items(live) { r ->
            PlaceRow(PhosphorRegular.ClockCounterClockwise, r.label, listOfNotNull(r.share, r.path.ifEmpty { null }).joinToString(" / ").ifEmpty { null }) { onRecent(r) }
        }
    }
    item { Section(R.string.t_locations_this_phone) }
    val local = volumes.filter { dev.hfps.hoard.smb.location.VolumeMapping.isReadable(it.state) }
    items(local) { v ->
        val id = FileService.locationIdOf(v)
        val loc = if (id == "device") BrowseLocation(id, LocationKind.Device, phone, volume = v) else BrowseLocation(id, LocationKind.Volume, v.label.ifEmpty { drive }, volume = v)
        PlaceRow(if (id == "device") PhosphorRegular.DeviceMobile else if (v.kind == "usb") PhosphorRegular.Usb else PhosphorRegular.HardDrive, loc.name, null) { onLocation(loc) }
    }
    if (favorites.isNotEmpty()) {
        item { Section(R.string.t_nav_favorites) }
        items(favorites) { f -> PlaceRow(PhosphorRegular.FolderSimpleStar, f.label, listOfNotNull(f.share.ifEmpty { null }, f.path.ifEmpty { null }).joinToString(" / ").ifEmpty { null }) { onFavorite(f) } }
    }
    if (machines.isNotEmpty()) {
        item { Section(R.string.t_nav_machines) }
        items(machines) { m ->
            PlaceRow(machineIcon(m.machine.icon), m.machine.name, m.ordered.firstOrNull()?.host) {
                onLocation(BrowseLocation(m.machine.id, LocationKind.Smb, m.machine.name, machine = m))
            }
        }
    }
    item { Section(R.string.t_transfer_android_folders) }
    items(trees) { (uri, name) -> PlaceRow(PhosphorRegular.FolderSimple, name, null) { onTree(uri, name) } }
    item { PlaceRow(PhosphorRegular.Plus, null, null, label = R.string.t_transfer_system_picker, onClick = onSystemPicker) }
}

@Composable
private fun Section(text: Int) {
    Eyebrow(stringResource(text), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun PlaceRow(icon: ImageVector, title: String?, subtitle: String?, label: Int? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).defaultMinSize(minHeight = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(HfpsTheme.colors.primary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = HfpsTheme.colors.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(title ?: stringResource(label!!), style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(PhosphorRegular.CaretRight, null, Modifier.size(16.dp), tint = HfpsTheme.colors.mutedForeground)
    }
}

/** "If a file already exists": ask, keep both, replace, skip. */
@Composable
fun ConflictSelector(policy: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HfpsTheme.colors.muted).clickable { open = true }.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(stringResource(R.string.t_transfer_conflict_label), style = HfpsTheme.typography.eyebrow, color = HfpsTheme.colors.mutedForeground)
            Text(stringResource(conflictText(policy)), style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground)
        }
        DropdownMenu(open, { open = false }, containerColor = HfpsTheme.colors.card) {
            CONFLICT_POLICIES.forEach { p ->
                MenuItem(stringResource(conflictText(p)) + " — " + stringResource(conflictHint(p)), null, { open = false; onChange(p) }, trailing = {
                    if (p == policy) Icon(PhosphorRegular.Check, null, Modifier.size(18.dp))
                })
            }
        }
    }
}

