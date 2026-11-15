package dev.hfps.hoard.app.ui.gallery

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.cull.CullImages
import dev.hfps.hoard.app.cull.CullKey
import dev.hfps.hoard.app.cull.CullStatus
import dev.hfps.hoard.app.cull.CullViewModel
import dev.hfps.hoard.app.cull.Shot
import dev.hfps.hoard.app.cull.viewerSteps
import dev.hfps.hoard.app.cull.stepOf
import dev.hfps.hoard.app.files.FileActions
import dev.hfps.hoard.app.files.FileService
import dev.hfps.hoard.app.gallery.DeviceShot
import dev.hfps.hoard.app.gallery.GalleryCullBackend
import dev.hfps.hoard.app.gallery.GalleryCullStore
import dev.hfps.hoard.app.gallery.GalleryEntry
import dev.hfps.hoard.app.gallery.GalleryReveal
import dev.hfps.hoard.app.gallery.Kind
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.app.ui.browse.findActivity
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.errorTitle
import dev.hfps.hoard.app.ui.cull.ViewerExtras
import dev.hfps.hoard.app.ui.cull.ViewerStart
import dev.hfps.hoard.app.ui.cull.CullViewer
import dev.hfps.hoard.app.ui.cull.accept
import dev.hfps.hoard.app.ui.reveal.rememberReveal
import dev.hfps.hoard.designsystem.icons.PhosphorFill
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import kotlinx.coroutines.launch

private class GalleryCullFactory(private val container: AppContainer, private val vm: GalleryViewModel, private val open: GalleryOpen) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val repo = container.gallery
        val backend = GalleryCullBackend(container.appContext, repo, container.cullService(), container.volumeRegistry, container.scope, open.entries)
        vm.backends[open.id] = backend
        return CullViewModel(
            key = CullKey("gallery", "", "#${open.id}"),
            backend = backend,
            store = GalleryCullStore(repo, container.database.cull(), open.entries),
            settings = container.appSettings.cull,
            appSettings = container.appSettings,
            newestFirst = true,
            analysisWindow = ANALYSIS_WINDOW,
        ) as T
    }
}

/** Shots analysed around the one on screen (ADR 0009 measurements on the phone's files or the server's previews). */
private const val ANALYSIS_WINDOW = 6

/**
 * The Gallery's viewer (ADR 0021): phase 2c's culling viewer over the grid's items (pick / reject / stars, bursts,
 * compare, assisted culling where analysis exists), plus Edited / Original for Reveal's edits, playback for videos and
 * motion photos, and Edit in Reveal (phone files as today, server items through SERVER hand-off links).
 */
@Composable
fun GalleryViewer(container: AppContainer, gallery: GalleryViewModel, open: GalleryOpen, onClose: (String?) -> Unit) {
    val vm: CullViewModel = viewModel(key = "gallery-cull:${open.id}", factory = GalleryCullFactory(container, gallery, open))
    val view by vm.view.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val longSide = remember(configuration) {
        with(density) { CullImages.screenLongSide(configuration.screenWidthDp.dp.roundToPx(), configuration.screenHeightDp.dp.roundToPx()) }
    }
    val images = remember(vm) { vm.images(longSide) }
    val byKey = remember(open) { open.entries.associateBy { it.key } }
    val backend = gallery.backends[open.id]
    var variant by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf<GalleryEntry?>(null) }
    val actions = remember { FileActions(container) }
    val reveal = rememberReveal(actions)
    val machines by container.database.machines().observeAll().collectAsState(initial = null)
    val volumes by container.volumes.observe().collectAsState(initial = null)
    val phone = stringResource(R.string.t_locations_internal)
    val drive = stringResource(R.string.t_locations_drive)

    LaunchedEffect(vm) { vm.setStack(false) }

    val ready = view.core.status == CullStatus.Ready && view.core.metadataDone
    if (!ready) {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("gallery-viewer-loading")) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Color.White, strokeWidth = 2.dp)
        }
        return
    }
    val steps = remember(open.id) { viewerSteps(view.visible.map { it.id }, view.bursts, view.members, collapse = false) }
    val start = remember(open.id) { ViewerStart(stepOf(steps, open.startKey).coerceAtLeast(0)) }

    fun editInReveal(shot: Shot) {
        val entry = byKey[shot.id] ?: return
        val activity = context.findActivity() ?: return
        scope.launch {
            vm.settle()
            try {
                val paired = container.gallery.db.account().get() != null
                when (GalleryReveal.route(entry, paired, container.reveal.acceptsServerReferences())) {
                    GalleryReveal.Route.Server -> GalleryReveal.sendServer(container.gallery, container.reveal, activity, listOf(entry), entry.folder.substringAfterLast('/').ifEmpty { null }, entry)
                    GalleryReveal.Route.Uri -> {
                        val device = entry.device!!
                        val location = FileService.resolve(device.locationId, null, machines.orEmpty(), volumes.orEmpty(), phone, drive) ?: return@launch
                        val paths = device.files.filter { it.role != DeviceShot.ROLE_VIDEO }.map { f -> if (device.dir.isEmpty()) f.media.name else device.dir + "/" + f.media.name }
                        if (paths.isEmpty()) return@launch
                        reveal.edit(location, device.dir, paths, device.dir.substringAfterLast('/').ifEmpty { null }, paths.first())
                    }
                    GalleryReveal.Route.Unavailable -> toaster.show(res.getString(R.string.gallery_reveal_update))
                }
            } catch (e: Throwable) {
                when (val code = errorCodeOf(e)) {
                    ErrorCode.CANCELLED -> Unit
                    ErrorCode.NO_APP -> toaster.show(res.getString(R.string.t_reveal_not_installed))
                    else -> toaster.show(res.getString(R.string.t_reveal_failed) + "\n" + res.getString(errorTitle(code)))
                }
            }
        }
    }

    val extras = ViewerExtras(
        topActions = { shot ->
            val entry = byKey[shot.id]
            if (entry != null && entry.edited && backend != null) {
                val original = shot.id in backend.showOriginal
                Text(
                    stringResource(if (original) R.string.gallery_viewer_original else R.string.gallery_viewer_edited),
                    style = HfpsTheme.typography.uiMedium,
                    color = if (original) Color.White else HfpsTheme.colors.primaryForeground,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (original) Color(0x33FFFFFF) else HfpsTheme.colors.primary)
                        .clickable {
                            if (original) backend.showOriginal.remove(shot.id) else backend.showOriginal.add(shot.id)
                            variant++
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("gallery-edited-toggle"),
                )
            }
        },
        overlay = { shot -> PlayButton(byKey[shot.id], onPlay = { playing = it }) },
        imageKey = { shot -> if (backend != null && shot.id in backend.showOriginal) "o$variant" else "e$variant" },
    )

    CullViewer(
        vm = vm,
        view = view,
        images = images,
        steps = steps,
        start = start,
        onEditInReveal = if (reveal.available) ::editInReveal else null,
        onAccept = { ids, from -> accept(vm, ids, from, toaster, res) },
        onClose = { last ->
            vm.focus(null)
            onClose(last)
        },
        extras = extras,
    )
    reveal.Dialogs()
    playing?.let { entry -> VideoPlayer(container, entry, onClose = { playing = null }) }
}

@Composable
private fun BoxScope.PlayButton(entry: GalleryEntry?, onPlay: (GalleryEntry) -> Unit) {
    entry ?: return
    if (!canPlay(entry)) return
    Box(
        Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color(0x88000000)).clickable { onPlay(entry) }.testTag("gallery-play"),
        contentAlignment = Alignment.Center,
    ) { Icon(PhosphorFill.Play, stringResource(R.string.gallery_viewer_play), Modifier.size(32.dp), tint = Color.White) }
}

/** A video, a motion photo with its video next to it on the phone, or anything with a server proxy. */
fun canPlay(entry: GalleryEntry): Boolean = when (entry.kind) {
    Kind.Video -> true
    Kind.Motion -> entry.itemId != null || entry.device?.files?.any { it.role == DeviceShot.ROLE_VIDEO } == true
    else -> false
}

/**
 * Plays a video in Hoard (Android's VideoView): the phone's file when it has it, else the server's proxy (H.264, ≤ 720p,
 * ranges; ADR 0015) streamed with the device key.
 */
@Composable
fun VideoPlayer(container: AppContainer, entry: GalleryEntry, onClose: () -> Unit) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val res = androidx.compose.ui.platform.LocalResources.current
    var source by remember { mutableStateOf<Pair<Uri, Map<String, String>>?>(null) }
    LaunchedEffect(entry.key) {
        val video = entry.device?.files?.firstOrNull { it.role == DeviceShot.ROLE_VIDEO }?.media
        source = if (video != null) {
            container.gallery.deviceIndex.uriOf(video) to emptyMap()
        } else {
            val api = container.gallery.api()
            if (api == null || entry.itemId == null) null else Uri.parse(api.proxyUrl(entry.itemId)) to api.authHeaders()
        }
        if (source == null) {
            toaster.show(res.getString(R.string.gallery_video_failed))
            onClose()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("gallery-video")) {
            val current = source
            if (current == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                            setOnErrorListener { _, _, _ ->
                                toaster.show(res.getString(R.string.gallery_video_failed))
                                onClose()
                                true
                            }
                            setOnPreparedListener { it.isLooping = entry.kind == Kind.Motion }
                            setVideoURI(current.first, current.second)
                            start()
                        }
                    },
                    onRelease = { it.stopPlayback() },
                    modifier = Modifier.align(Alignment.Center).fillMaxSize(),
                )
            }
        }
    }
}
