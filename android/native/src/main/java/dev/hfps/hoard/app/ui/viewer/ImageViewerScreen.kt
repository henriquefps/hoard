package dev.hfps.hoard.app.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.HoardApplication
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.BrowseLocation
import dev.hfps.hoard.app.files.FileActions
import dev.hfps.hoard.app.files.FileService
import dev.hfps.hoard.app.files.VIEWER_IMAGE_EXTENSIONS
import dev.hfps.hoard.app.files.extensionOf
import dev.hfps.hoard.app.files.formatBytes
import dev.hfps.hoard.app.files.parentPath
import dev.hfps.hoard.app.files.revealSelection
import dev.hfps.hoard.app.files.NaturalComparator
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.app.ui.browse.findActivity
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.MenuItem
import dev.hfps.hoard.app.ui.common.errorTitle
import dev.hfps.hoard.app.ui.common.formatFullDate
import dev.hfps.hoard.app.ui.common.openErrorText
import dev.hfps.hoard.app.ui.reveal.rememberReveal
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SmbEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator

/** A decoded photo, or why it is not there. */
private sealed interface Loaded {
    data object Loading : Loaded
    data class Ready(val image: ImageBitmap) : Loaded
    data class Failed(val code: ErrorCode) : Loaded
}

/**
 * The built-in image viewer (Hoard 2): the folder's images in a pager, pinch and double-tap zoom, and the file's
 * actions (share, open with, Edit in Reveal). SMB photos come through the open cache (one download, reused);
 * local ones are decoded in place.
 */
@Composable
fun ImageViewerScreen(locationId: String, share: String?, path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as HoardApplication).container
    val actions = remember { FileActions(container) }
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val machines by container.database.machines().observeAll().collectAsState(initial = null)
    val volumes by container.volumes.observe().collectAsState(initial = null)
    val phone = stringResource(R.string.t_locations_internal)
    val drive = stringResource(R.string.t_locations_drive)
    val location = remember(machines, volumes) {
        if (machines == null || volumes == null) null else FileService.resolve(locationId, share, machines!!, volumes!!, phone, drive)
    }
    val folder = parentPath(path) ?: ""
    val siblings by produceState<List<SmbEntry>?>(null, location?.id) {
        val loc = location ?: return@produceState
        value = runCatching {
            container.files.run(loc) { fs, cancel -> fs.list(folder, cancel) }.entries
                .filter { !it.isDirectory && !it.isHidden && extensionOf(it.name) in VIEWER_IMAGE_EXTENSIONS }
                .sortedWith { a, b -> NaturalComparator(Collator.getInstance()).compare(a.name, b.name) }
        }.getOrNull()?.takeIf { list -> list.any { it.path == path } }
            ?: listOf(SmbEntry(path.substringAfterLast('/'), path, false, 0, null, false))
    }
    var chrome by remember { mutableStateOf(true) }
    val reveal = rememberReveal(actions)

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("image-viewer")) {
        val list = siblings
        if (location == null || list == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            val pager = rememberPagerState(initialPage = list.indexOfFirst { it.path == path }.coerceAtLeast(0)) { list.size }
            var zoomed by remember { mutableStateOf(false) }
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, beyondViewportPageCount = 1) { page ->
                val entry = list[page]
                val loaded by produceState<Loaded>(Loaded.Loading, entry.path) {
                    value = try {
                        Loaded.Ready(load(context, actions, container.files, location, entry.path))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Loaded.Failed(errorCodeOf(e))
                    }
                }
                when (val l = loaded) {
                    Loaded.Loading -> Box(Modifier.fillMaxSize()) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                    is Loaded.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(errorTitle(l.code)), color = Color.White, style = HfpsTheme.typography.body)
                    }
                    is Loaded.Ready -> ZoomableImage(l.image, onTap = { chrome = !chrome }, onZoomed = { if (page == pager.currentPage) zoomed = it })
                }
            }
            val current = list.getOrNull(pager.currentPage)
            AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onBack) { Icon(PhosphorRegular.ArrowLeft, stringResource(R.string.t_common_back), tint = Color.White) }
                        Column(Modifier.weight(1f)) {
                            Text(current?.name.orEmpty(), color = Color.White, style = HfpsTheme.typography.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (current != null && current.size > 0) {
                                Text(
                                    "${pager.currentPage + 1} / ${list.size} · " + formatBytes(current.size) + " · " + formatFullDate(context, current.modifiedAt),
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = HfpsTheme.typography.ui,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (current != null) {
                            IconButton({
                                val activity = context.findActivity() ?: return@IconButton
                                scope.launch {
                                    runCatching { actions.share(activity, location, listOf(current.path)) }
                                        .onFailure { toaster.show(context.getString(openErrorText(errorCodeOf(it)))) }
                                }
                            }) { Icon(PhosphorRegular.ShareNetwork, stringResource(R.string.t_transfer_share), tint = Color.White) }
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton({ menu = true }) { Icon(PhosphorRegular.DotsThreeVertical, stringResource(R.string.t_common_more), tint = Color.White) }
                                DropdownMenu(menu, { menu = false }, containerColor = HfpsTheme.colors.card) {
                                    MenuItem(stringResource(R.string.t_browse_open_with), PhosphorRegular.ArrowSquareOut, {
                                        menu = false
                                        val activity = context.findActivity() ?: return@MenuItem
                                        scope.launch {
                                            runCatching { actions.open(activity, location, current, chooser = true) }
                                                .onFailure { toaster.show(context.getString(openErrorText(errorCodeOf(it)))) }
                                        }
                                    })
                                    if (reveal.available) {
                                        MenuItem(stringResource(R.string.t_reveal_edit), PhosphorRegular.SlidersHorizontal, {
                                            menu = false
                                            val paths = revealSelection(listOf(current), list)
                                            reveal.edit(location, folder, paths, folder.substringAfterLast('/').ifEmpty { location.name }, current.path)
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    reveal.Dialogs()
}

/** Decodes [path] at about twice the screen size (SMB through the open cache; local in place). */
private suspend fun load(context: Context, actions: FileActions, files: FileService, location: BrowseLocation, path: String): ImageBitmap {
    val metrics = context.resources.displayMetrics
    val target = (maxOf(metrics.widthPixels, metrics.heightPixels) * 2).coerceAtMost(4096)
    return withContext(Dispatchers.IO) {
        val bitmap = if (location.isSmb) {
            decodeFile(actions.fetch(location, path), target)
        } else {
            val local = files.localFs(location).file(path)
            val file = local.file
            if (file != null) decodeFile(file, target) else decodeUri(context, local.documentUri!!, target)
        }
        bitmap?.asImageBitmap() ?: throw dev.hfps.hoard.smb.HoardSmbException(ErrorCode.UNKNOWN, "Could not decode $path")
    }
}

private fun decodeFile(file: File, target: Int): Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    decodeSource(ImageDecoder.createSource(file), target)
} else {
    sampled(target, { BitmapFactory.decodeFile(file.absolutePath, it) })
}

private fun decodeUri(context: Context, uri: Uri, target: Int): Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    decodeSource(ImageDecoder.createSource(context.contentResolver, uri), target)
} else {
    sampled(target, { o -> context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } })
}

/** ImageDecoder applies EXIF orientation and decodes HEIF / AVIF / WebP; scaled down to [target] on the long side. */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
private fun decodeSource(source: ImageDecoder.Source, target: Int): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
    val long = maxOf(info.size.width, info.size.height)
    if (long > target) {
        val scale = target.toFloat() / long
        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
    }
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
}

private fun sampled(target: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
    return decode(BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Pinch to zoom (1×–6×), pan when zoomed, double tap toggles 2.5×; [onZoomed] lets the pager stop swiping. */
@Composable
private fun ZoomableImage(image: ImageBitmap, onTap: () -> Unit, onZoomed: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        fun clamp(o: Offset, s: Float): Offset {
            val maxX = (width * (s - 1)) / 2
            val maxY = (height * (s - 1)) / 2
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                offset = clamp(Offset((width / 2 - tap.x) * 1.5f, (height / 2 - tap.y) * 1.5f), scale)
                            }
                            onZoomed(scale > 1f)
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 6f)
                        if (next > 1f || scale > 1f) {
                            scale = next
                            offset = if (scale == 1f) Offset.Zero else clamp(offset + pan, scale)
                            onZoomed(scale > 1f)
                        }
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
