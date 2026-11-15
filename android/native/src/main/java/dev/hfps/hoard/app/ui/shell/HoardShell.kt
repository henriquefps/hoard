package dev.hfps.hoard.app.ui.shell

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.FileActions
import dev.hfps.hoard.app.files.engineMachine
import dev.hfps.hoard.app.transfers.PasteProblem
import dev.hfps.hoard.app.transfers.TREE_LOCATION_ID
import dev.hfps.hoard.app.transfers.pasteProblem
import dev.hfps.hoard.app.ui.browse.BrowseNav
import dev.hfps.hoard.app.ui.browse.findActivity
import dev.hfps.hoard.app.ui.browse.BrowseScreen
import dev.hfps.hoard.app.ui.browse.BrowseViewModel
import dev.hfps.hoard.app.ui.common.ConfirmDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.NameDialog
import dev.hfps.hoard.app.ui.common.rememberToaster
import dev.hfps.hoard.app.ui.files.FavoriteItem
import dev.hfps.hoard.app.ui.files.FilesActions
import dev.hfps.hoard.app.ui.files.FilesScreen
import dev.hfps.hoard.app.ui.files.FilesViewModel
import dev.hfps.hoard.app.ui.files.MachineItem
import dev.hfps.hoard.app.ui.gallery.GalleryScreen
import dev.hfps.hoard.app.ui.machines.MachineFormScreen
import dev.hfps.hoard.app.ui.machines.MachineFormViewModel
import dev.hfps.hoard.app.ui.save.SaveScreen
import dev.hfps.hoard.app.ui.settings.FilesSettingsSection
import dev.hfps.hoard.app.ui.settings.LicencesScreen
import dev.hfps.hoard.app.ui.settings.SettingsScreen
import dev.hfps.hoard.app.ui.settings.SettingsViewModel
import dev.hfps.hoard.app.ui.storage.StorageAccessIntents
import dev.hfps.hoard.app.ui.storage.StorageAccessScreen
import dev.hfps.hoard.app.ui.transfers.TransferConflicts
import dev.hfps.hoard.app.ui.transfers.TransfersScreen
import dev.hfps.hoard.app.ui.transfers.TransfersSheet
import dev.hfps.hoard.app.ui.transfers.rememberStartTransfer
import dev.hfps.hoard.app.ui.viewer.ImageViewerScreen
import dev.hfps.hoard.app.ui.viewer.TextViewerScreen
import dev.hfps.hoard.designsystem.components.HfpsDestination
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsNavigationBar
import dev.hfps.hoard.designsystem.components.HfpsNavigationRail
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorFill
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsMotion
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.location.SafVolumes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The two tabs (Gallery, Files), in this order. */
enum class Tab { Gallery, Files }

/**
 * Where the main navigation goes: a bottom bar on phones in portrait, a rail at the start edge on tablets
 * (600dp wide or more) and on short screens (500dp tall or less: a phone in landscape), as Hoard 1.9.1 did with its
 * sidebar rail, so the content keeps the height.
 */
fun usesRail(width: Dp, height: Dp): Boolean = width >= 600.dp || height <= 500.dp

/** Phones (narrower than 600dp) see the transfers in a bottom sheet; tablets go to the Transfers screen. */
fun usesTransfersSheet(width: Dp): Boolean = width < 600.dp

/** Set by the activity when another app shared files to Hoard: the shell opens "Save to Hoard". */
object ShellRequests {
    val openSave = MutableStateFlow(false)
}

/** The app shell: the tabs and the screens behind them, over the real data in [container]. */
@Composable
fun HoardShell(container: AppContainer) {
    val navController = rememberNavController()
    val files: FilesViewModel = viewModel(factory = FilesViewModel.factory(container))
    val settings: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val snackbar = remember { SnackbarHostState() }
    val toaster = rememberToaster(snackbar)
    val actions = remember { FileActions(container) }
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val phoneName = stringResource(R.string.t_locations_internal)
    val driveName = stringResource(R.string.t_locations_drive)
    val openSave by ShellRequests.openSave.collectAsState()
    val clipboardShown = container.clipboard.content.collectAsState().value != null
    val scope = rememberCoroutineScope()
    LaunchedEffect(openSave) {
        if (openSave) {
            ShellRequests.openSave.value = false
            navController.navigate(SaveRoute) { launchSingleTop = true }
        }
    }

    fun openLocation(locationId: String, share: String?, path: String) =
        navController.navigate(BrowseRoute(locationId, share, path))

    // The transfer indicator and "View" actions: a bottom sheet on phones, the Transfers screen on tablets (Hoard 1's
    // TransfersDrawer below 768 px).
    var transfersSheet by remember { mutableStateOf(false) }
    val windowWidth = with(androidx.compose.ui.platform.LocalDensity.current) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
    fun openTransfers() {
        if (usesTransfersSheet(windowWidth)) transfersSheet = true else navController.navigate(TransfersRoute) { launchSingleTop = true }
    }

    CompositionLocalProvider(LocalToaster provides toaster) {
        Box(Modifier.fillMaxSize()) {
            ShellLayout(navController) { inFilesTab ->
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        NavHost(
                            navController = navController,
                            // Files first while the Gallery is a placeholder (it gets content in phase 3).
                            startDestination = FilesRoute,
                            enterTransition = { fadeIn(HfpsMotion.standard()) },
                            exitTransition = { fadeOut(HfpsMotion.fast()) },
                            popEnterTransition = { fadeIn(HfpsMotion.standard()) },
                            popExitTransition = { fadeOut(HfpsMotion.fast()) },
                        ) {
                            composable<GalleryRoute> { GalleryScreen(onOpenSettings = { navController.navigate(SettingsRoute) }) }
                            composable<FilesRoute> {
                                FilesTab(container, files, navController, ::openLocation, ::openTransfers)
                            }
                            composable<BrowseRoute> { entry ->
                                val route = entry.toRoute<BrowseRoute>()
                                val vm: BrowseViewModel = viewModel(
                                    factory = BrowseViewModel.factory(container, route.locationId, route.share, route.path, phoneName, driveName),
                                )
                                val state by vm.state.collectAsStateWithLifecycle()
                                BrowseScreen(
                                    viewModel = vm,
                                    state = state,
                                    actions = actions,
                                    clipboard = container.clipboard,
                                    nav = BrowseNav(
                                        back = { navController.popBackStack() },
                                        open = { id, share, path -> navigateToFolder(navController, id, share, path) },
                                        home = { navController.popBackStack(FilesRoute, inclusive = false) },
                                        editMachine = { navController.navigate(MachineFormRoute(it)) },
                                        image = { loc, path -> navController.navigate(ImageViewerRoute(loc.id, loc.share, path)) },
                                        text = { loc, path, edit -> navController.navigate(TextViewerRoute(loc.id, loc.share, path, edit)) },
                                        transfers = { openTransfers() },
                                        cull = { loc, path -> navController.navigate(CullRoute(loc.id, loc.share, path)) },
                                    ),
                                )
                            }
                            composable<CullRoute> { entry ->
                                val route = entry.toRoute<CullRoute>()
                                dev.hfps.hoard.app.ui.cull.CullScreen(
                                    container = container,
                                    locationId = route.locationId,
                                    share = route.share,
                                    path = route.path,
                                    onBack = { navController.popBackStack() },
                                    onTransfers = { openTransfers() },
                                    onOpenSettings = { navController.navigate(SettingsRoute) },
                                )
                            }
                            composable<MachineFormRoute> { entry ->
                                val route = entry.toRoute<MachineFormRoute>()
                                val vm: MachineFormViewModel = viewModel(factory = MachineFormViewModel.factory(container, route.machineId))
                                val state by vm.state.collectAsStateWithLifecycle()
                                MachineFormScreen(
                                    viewModel = vm,
                                    state = state,
                                    onBack = { navController.popBackStack() },
                                    onSaved = { id ->
                                        navController.popBackStack()
                                        if (route.machineId == null) openLocation(id, null, "")
                                    },
                                    onDeleted = { navController.popBackStack(FilesRoute, inclusive = false) },
                                )
                            }
                            composable<TransfersRoute> {
                                TransfersScreen(
                                    onBack = { navController.popBackStack() },
                                    onOpenDestination = { t ->
                                        if (t.row.destLocationId != TREE_LOCATION_ID) {
                                            openLocation(t.row.destLocationId, t.row.destShare.ifEmpty { null }, t.row.destPath)
                                        }
                                    },
                                )
                            }
                            composable<SaveRoute> {
                                SaveScreen(onBack = { navController.popBackStack() }, onTransfers = { openTransfers() })
                            }
                            composable<StorageAccessRoute> { StorageAccessScreen(onDone = { navController.popBackStack() }) }
                            composable<ImageViewerRoute> { entry ->
                                val route = entry.toRoute<ImageViewerRoute>()
                                ImageViewerScreen(route.locationId, route.share, route.path, onBack = { navController.popBackStack() })
                            }
                            composable<TextViewerRoute> { entry ->
                                val route = entry.toRoute<TextViewerRoute>()
                                TextViewerScreen(route.locationId, route.share, route.path, route.edit, onBack = { navController.popBackStack() }, onOpenFile = { target ->
                                    navController.navigate(TextViewerRoute(route.locationId, route.share, target)) {
                                        popUpTo<TextViewerRoute> { inclusive = true }
                                    }
                                })
                            }
                            composable<SettingsRoute> {
                                val state by settings.state.collectAsStateWithLifecycle()
                                SettingsScreen(
                                    state = state,
                                    onBack = { navController.popBackStack() },
                                    onThemeMode = settings::setThemeMode,
                                    onPalette = settings::setPalette,
                                    onOpenLicences = { navController.navigate(LicencesRoute) },
                                    files = { FilesSettingsSection() },
                                    culling = { dev.hfps.hoard.app.ui.cull.AssistSettingsFields(container) },
                                    language = remember { dev.hfps.hoard.app.data.AppLanguage.current(context) },
                                    onLanguage = { tag ->
                                        scope.launch { container.settingsStore.putString("language", tag ?: "") }
                                        dev.hfps.hoard.app.data.AppLanguage.set(context.findActivityOrSelf(), tag)
                                    },
                                )
                            }
                            composable<LicencesRoute> { LicencesScreen(onBack = { navController.popBackStack() }) }
                        }
                    }
                    if (inFilesTab) ClipboardBar(container, onTransfers = { openTransfers() })
                }
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(bottom = if (clipboardShown) 144.dp else 72.dp),
            ) { data ->
                Snackbar(data, containerColor = HfpsTheme.colors.foreground, contentColor = HfpsTheme.colors.background, actionColor = HfpsTheme.colors.primary.copy(alpha = 1f))
            }
            TransferConflicts()
            if (transfersSheet) {
                TransfersSheet(
                    onDismiss = { transfersSheet = false },
                    onOpenAll = { navController.navigate(TransfersRoute) { launchSingleTop = true } },
                    onOpenDestination = { t ->
                        if (t.row.destLocationId != TREE_LOCATION_ID) openLocation(t.row.destLocationId, t.row.destShare.ifEmpty { null }, t.row.destPath)
                    },
                )
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") context
}

private fun android.content.Context.findActivityOrSelf(): android.content.Context = findActivity() ?: this

/** A breadcrumb or "up": back to that folder when it is on the back stack, a new entry otherwise. */
private fun navigateToFolder(nav: NavHostController, locationId: String, share: String?, path: String) {
    val target = BrowseRoute(locationId, share, path)
    if (!nav.popBackStack(target, inclusive = false)) nav.navigate(target)
}

@Composable
private fun FilesTab(
    container: AppContainer,
    files: FilesViewModel,
    navController: NavHostController,
    openLocation: (String, String?, String) -> Unit,
    openTransfers: () -> Unit,
) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val state by files.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<FavoriteItem?>(null) }
    var deleting by remember { mutableStateOf<MachineItem?>(null) }
    var granting by remember { mutableStateOf<String?>(null) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        files.storageGranted.value = StorageAccessIntents.granted(context)
    }
    // A drive attached while the Files tab is open: "SD card connected · Open" (Hoard 1's VolumeNotices).
    var knownDrives by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(state.removable, state.loading) {
        if (state.loading) return@LaunchedEffect
        val mounted = state.removable.filter { it.state == "mounted" || it.state == "mountedReadOnly" }
        val before = knownDrives
        knownDrives = mounted.mapNotNull { it.uuid }.toSet()
        if (before == null) return@LaunchedEffect
        mounted.filter { it.uuid !in before }.forEach { volume ->
            toaster.show(
                res.getString(if (volume.kind == "usb") R.string.t_locations_usb_connected else R.string.t_locations_sd_connected) + " · " + volume.label,
                action = res.getString(R.string.t_locations_open),
                onAction = { openLocation(volume.locationId, null, "") },
            )
        }
    }
    // First run: the all files access screen, once (Hoard 1's storageIntroSeen).
    LaunchedEffect(Unit) {
        val seen = container.appSettings.files.first().storageIntroSeen
        if (!seen && !StorageAccessIntents.granted(context)) navController.navigate(StorageAccessRoute)
    }
    val grantLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uuid = granting ?: return@rememberLauncherForActivityResult
        granting = null
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@rememberLauncherForActivityResult
        // Android 11+ refuses a volume's root here, so any folder on this volume is accepted.
        val picked = SafVolumes.grantOf(runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull())
        if (picked == null || !picked.first.equals(uuid, ignoreCase = true)) {
            toaster.show(res.getString(R.string.t_errors_access_denied_title))
            return@rememberLauncherForActivityResult
        }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        openLocation("vol-$uuid", null, "")
    }
    FilesScreen(
        state = state,
        onOpenSettings = { navController.navigate(SettingsRoute) },
        actions = FilesActions(
            openLocation = openLocation,
            addMachine = { navController.navigate(MachineFormRoute()) },
            editMachine = { navController.navigate(MachineFormRoute(it)) },
            recheck = { id ->
                scope.launch { container.database.machines().get(id)?.let { container.routes.check(it.engineMachine(), force = true) } }
            },
            deleteMachine = { deleting = it },
            transfers = openTransfers,
            storageAccess = { navController.navigate(StorageAccessRoute) },
            renameFavorite = { renaming = it },
            removeFavorite = { f ->
                scope.launch {
                    container.database.favorites().remove(f.id)
                    toaster.show(res.getString(R.string.t_favorites_removed, f.label))
                }
            },
            reorderFavorites = { ids -> scope.launch { container.database.favorites().reorder(ids) } },
            reorderMachines = { ids -> scope.launch { container.database.machines().reorder(ids) } },
            moveMachine = { m, delta ->
                val ids = state.machines.map { it.id }
                val from = ids.indexOf(m.id)
                scope.launch { container.database.machines().reorder(dev.hfps.hoard.app.ui.common.moved(ids, from, from + delta)) }
            },
            moveFavorite = { f, delta ->
                scope.launch {
                    val ids = state.favorites.map { it.id }.toMutableList()
                    val from = ids.indexOf(f.id)
                    val to = (from + delta).coerceIn(0, ids.lastIndex)
                    ids.add(to, ids.removeAt(from))
                    container.database.favorites().reorder(ids)
                }
            },
            grantVolume = { volume ->
                val uuid = volume.uuid ?: return@FilesActions
                val storage = container.volumeRegistry.storageVolume(uuid)
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && storage != null) storage.createOpenDocumentTreeIntent() else Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
                granting = uuid
                runCatching { grantLauncher.launch(intent) }
            },
            storageSettings = {
                listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                    .firstOrNull { it.resolveActivity(context.packageManager) != null }?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            },
        ),
    )
    renaming?.let { favorite ->
        NameDialog(
            title = stringResource(R.string.t_favorites_rename),
            label = stringResource(R.string.t_favorites_label),
            initialValue = favorite.label,
            submitLabel = stringResource(R.string.t_common_save),
            onDismiss = { renaming = null },
            validate = { if (it.isBlank()) res.getString(R.string.t_favorites_label_required) else null },
            onSubmit = { label ->
                container.database.favorites().rename(favorite.id, label)
                null
            },
        )
    }
    deleting?.let { machine ->
        ConfirmDialog(
            title = stringResource(R.string.t_machines_delete_title, machine.name),
            body = stringResource(R.string.t_machines_delete_body),
            confirmLabel = stringResource(R.string.t_common_delete),
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = {
                container.database.machines().deleteMachine(machine.id)
                container.gateway.deleteCredentials(machine.id)
                container.routes.forget(machine.id)
                toaster.show(res.getString(R.string.t_machines_delete_done, machine.name))
            },
        )
    }
}

/**
 * The clipboard bar (ADR 0007): what "Copy" / "Move" put aside and "Paste here" for the folder on screen. Off where it
 * cannot work (no folder open, a read-only folder, a folder into itself, a move into its own folder).
 */
@Composable
private fun ClipboardBar(container: AppContainer, onTransfers: () -> Unit) {
    val content by container.clipboard.content.collectAsState()
    val target by container.clipboard.target.collectAsState()
    val settings by container.appSettings.files.collectAsState(initial = null)
    val start = rememberStartTransfer(onTransfers)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val clip = content ?: return
    val here = target
    val problem = here?.let { pasteProblem(clip.op, clip.source.place, clip.entries, it.folder) }
    val reason = when {
        here == null -> stringResource(R.string.t_transfer_clipboard_open_folder)
        here.readOnlyReason != null -> here.readOnlyReason
        problem == PasteProblem.IntoItself -> stringResource(R.string.t_transfer_into_itself)
        problem == PasteProblem.SameFolder -> stringResource(R.string.t_transfer_same_folder)
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().background(HfpsTheme.colors.card).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(HfpsTheme.colors.primary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(if (clip.op == "move") PhosphorRegular.Scissors else PhosphorRegular.Copy, null, Modifier.size(18.dp), tint = HfpsTheme.colors.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(
                pluralStringResource(if (clip.op == "move") R.plurals.t_transfer_clipboard_move else R.plurals.t_transfer_clipboard_copy, clip.entries.size, clip.entries.size.toString()),
                style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                color = HfpsTheme.colors.cardForeground,
                maxLines = 1,
            )
            Text(
                reason ?: stringResource(R.string.t_transfer_clipboard_from, clip.sourceLabel),
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PrimaryButton(stringResource(R.string.t_transfer_paste), {
            val folder = here ?: return@PrimaryButton
            busy = true
            scope.launch {
                try {
                    val ok = start(clip.op, clip.source.place, clip.entries, folder.folder, folder.label, settings?.defaultConflict ?: "ask")
                    // A move cannot be pasted twice; a copy can (into another folder).
                    if (ok && clip.op == "move") container.clipboard.clear()
                } finally {
                    busy = false
                }
            }
        }, icon = PhosphorRegular.ClipboardText, enabled = here != null && reason == null && !busy)
        HfpsIconButton(PhosphorRegular.X, stringResource(R.string.t_transfer_clipboard_clear), { container.clipboard.clear() })
    }
}

/** Places the navigation (bar or rail) around [content]; shown only on the tabs (the Files tab includes browsing). */
@Composable
fun ShellLayout(navController: NavHostController, content: @Composable (inFilesTab: Boolean) -> Unit) {
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val tab = when {
        destination == null -> null
        destination.hasRoute<GalleryRoute>() -> Tab.Gallery
        destination.hasRoute<FilesRoute>() || destination.hasRoute<BrowseRoute>() -> Tab.Files
        else -> null
    }
    TabbedLayout(
        selected = tab,
        onSelect = { selected ->
            // The tab on screen again: back to its root (the Files tab's home), like Android's own tab bars.
            if (selected == tab) {
                navController.popBackStack(if (selected == Tab.Gallery) GalleryRoute else FilesRoute, inclusive = false)
                return@TabbedLayout
            }
            navController.navigate(if (selected == Tab.Gallery) GalleryRoute else FilesRoute) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
        content = { content(tab == Tab.Files) },
    )
}

/** Stateless layout of the shell; [selected] null (a full-screen destination) hides the navigation. */
@Composable
fun TabbedLayout(selected: Tab?, onSelect: (Tab) -> Unit, content: @Composable () -> Unit) {
    val destinations = listOf(
        HfpsDestination(stringResource(R.string.nav_gallery), PhosphorRegular.Images, PhosphorFill.Images),
        HfpsDestination(stringResource(R.string.nav_files), PhosphorRegular.Folders, PhosphorFill.Folders),
    )
    BoxWithConstraints(Modifier.fillMaxSize().background(HfpsTheme.colors.background)) {
        val rail = usesRail(maxWidth, maxHeight)
        when {
            selected == null -> content()
            rail -> Row(Modifier.fillMaxSize()) {
                HfpsNavigationRail(destinations, selected.ordinal, { onSelect(Tab.entries[it]) }, Modifier.fillMaxHeight())
                Column(
                    Modifier
                        .weight(1f)
                        .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
                ) { content() }
            }
            else -> Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                ) { content() }
                HfpsNavigationBar(destinations, selected.ordinal, { onSelect(Tab.entries[it]) })
            }
        }
    }
}
