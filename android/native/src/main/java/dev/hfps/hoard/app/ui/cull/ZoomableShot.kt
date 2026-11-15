package dev.hfps.hoard.app.ui.cull

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.cull.CullImages
import dev.hfps.hoard.app.cull.Face
import dev.hfps.hoard.app.cull.ImageRect
import dev.hfps.hoard.app.cull.ScreenImage
import dev.hfps.hoard.app.cull.Shot
import dev.hfps.hoard.app.cull.TILE_SIZE
import dev.hfps.hoard.app.cull.sampleForScale
import dev.hfps.hoard.app.cull.tilesFor
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.image.ZoomInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * The zoom of a photo on screen: [scale] over the fitted size (1 = fit) and the image point at the centre, as
 * fractions of the image (so two panes of different sizes can share one: compare's synchronized zoom, viewLink.ts).
 */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var cx by mutableFloatStateOf(0.5f)
    var cy by mutableFloatStateOf(0.5f)
    val zoomed: Boolean get() = scale > 1.01f

    fun reset() {
        scale = 1f
        cx = 0.5f
        cy = 0.5f
    }

    companion object {
        const val MAX_SCALE = 24f
    }
}

/** What a page of the viewer loaded: the screen image (fast, then final), or why not. */
sealed interface ScreenState {
    data object Loading : ScreenState
    data class Ready(val image: ScreenImage) : ScreenState
    data object Failed : ScreenState
}

/**
 * One culling photo (ZoomableImage.tsx): the screen image fitted, pinch or double tap to zoom (double tap: 100 %),
 * pan while zoomed, and at 100 % the image core's tiles over it (grid-aligned, centre first) so the pixels are the
 * full resolution's. One-finger drags at fit are left to the pager (horizontal) and to [onVerticalSwipe].
 */
@Composable
fun ZoomableShot(
    images: CullImages,
    shot: Shot,
    active: Boolean,
    zoom: ZoomState,
    modifier: Modifier = Modifier,
    onTap: () -> Unit = {},
    onScreen: (ScreenImage?, ZoomInfo?) -> Unit = { _, _ -> },
    onVerticalSwipe: ((dy: Float, end: Boolean, velocity: Float) -> Unit)? = null,
    focusFace: Pair<Face, Int>? = null,
) {
    val scope = rememberCoroutineScope()
    var screen by remember(shot) { mutableStateOf<ScreenState>(images.cachedScreen(shot)?.let { ScreenState.Ready(it) } ?: ScreenState.Loading) }
    var info by remember(shot) { mutableStateOf(images.cachedZoom(shot)) }
    var zoomLoading by remember(shot) { mutableStateOf(false) }
    val tiles = remember(shot) { mutableStateMapOf<String, Pair<dev.hfps.hoard.app.cull.Tile, ImageBitmap>>() }

    // Fast, then final (the shot on screen in the foreground, its neighbours in the background).
    LaunchedEffect(shot, active) {
        val current = screen
        if (current !is ScreenState.Ready || !current.image.final) {
            if (current !is ScreenState.Ready) {
                screen = try {
                    ScreenState.Ready(images.screen(shot, fast = true, background = !active))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ScreenState.Failed
                }
            }
            val now = screen
            if (now is ScreenState.Ready && !now.image.final) {
                runCatching { images.screen(shot, fast = false, background = !active) }.onSuccess { screen = ScreenState.Ready(it) }
            }
        }
    }
    LaunchedEffect(screen, info, active) { if (active) onScreen((screen as? ScreenState.Ready)?.image, if (zoom.zoomed) info else null) }
    // Zoomed in: learn the 100 % image (stage the JPEG, extract the full-size preview or render the RAW).
    LaunchedEffect(shot, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow { zoom.zoomed }.distinctUntilChanged().collect { zoomed ->
            if (zoomed && info == null && !zoomLoading) {
                zoomLoading = true
                info = runCatching { images.zoom(shot) }.getOrNull()
                zoomLoading = false
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().testTag("zoomable-${shot.id}")) {
        val box = IntSize(constraints.maxWidth, constraints.maxHeight)
        val ready = screen as? ScreenState.Ready
        // The image's own pixel size: the full resolution once known, else the screen image's.
        val fullW = (info?.width ?: ready?.image?.width ?: 1).toFloat()
        val fullH = (info?.height ?: ready?.image?.height ?: 1).toFloat()
        val fit = min(box.width / fullW, box.height / fullH)

        fun clamp() {
            val shownW = fullW * fit * zoom.scale
            val shownH = fullH * fit * zoom.scale
            val halfX = if (shownW <= box.width) 0.5f else box.width / 2f / shownW
            val halfY = if (shownH <= box.height) 0.5f else box.height / 2f / shownH
            zoom.cx = zoom.cx.coerceIn(halfX, 1 - halfX)
            zoom.cy = zoom.cy.coerceIn(halfY, 1 - halfY)
        }

        // Zoom to face: the face fills about half the screen.
        LaunchedEffect(focusFace) {
            val (face, _) = focusFace ?: return@LaunchedEffect
            val target = (min(box.width / (face.width * fullW * fit), box.height / (face.height * fullH * fit)) * 0.5).toFloat()
            zoom.scale = target.coerceIn(1f, ZoomState.MAX_SCALE)
            zoom.cx = (face.x + face.width / 2).toFloat()
            zoom.cy = (face.y + face.height / 2).toFloat()
            clamp()
        }

        // 100 % tiles for the visible area, once the zoom shows more than the screen image has.
        if (active && info != null && zoom.zoomed) {
            val full = info!!
            LaunchedEffect(full, box) {
                snapshotFlow { Triple(zoom.scale, zoom.cx, zoom.cy) }.collectLatest { (scale, cx, cy) ->
                    delay(60)
                    val devicePerImage = fit * scale
                    val screenPerImage = (ready?.image?.width ?: 0).toFloat() / full.width
                    if (devicePerImage <= screenPerImage * 1.05f) return@collectLatest
                    val visibleW = box.width / devicePerImage
                    val visibleH = box.height / devicePerImage
                    val rect = ImageRect((cx * full.width - visibleW / 2).toDouble(), (cy * full.height - visibleH / 2).toDouble(), visibleW.toDouble(), visibleH.toDouble())
                    val sample = sampleForScale(devicePerImage.toDouble())
                    val wanted = tilesFor(rect, full.width, full.height, sample)
                    tiles.keys.retainAll { key -> key.startsWith("$sample:") || wanted.size > 40 }
                    for (tile in wanted) {
                        if (tile.key in tiles) continue
                        images.cachedTile(shot, tile)?.let { tiles[tile.key] = tile to it; continue }
                        scope.launch { images.tile(shot, tile)?.let { tiles[tile.key] = tile to it } }
                    }
                }
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(shot, box, fullW, fullH) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            scope.launch {
                                if (zoom.zoomed) {
                                    animate(zoom, 1f, 0.5f, 0.5f)
                                } else {
                                    // Double tap: 100 % of the full resolution where it is known, else 2.5 ×.
                                    val known = info ?: runCatching { images.zoom(shot) }.getOrNull()?.also { info = it }
                                    val fitFull = if (known != null) min(box.width / known.width.toFloat(), box.height / known.height.toFloat()) else fit
                                    val target = if (known != null) (1f / fitFull).coerceIn(2f, ZoomState.MAX_SCALE) else 2.5f
                                    val shownW = fullW * fit
                                    val shownH = fullH * fit
                                    val px = ((tap.x - (box.width - shownW) / 2) / shownW).coerceIn(0f, 1f)
                                    val py = ((tap.y - (box.height - shownH) / 2) / shownH).coerceIn(0f, 1f)
                                    animate(zoom, target, px, py)
                                    clamp()
                                }
                            }
                        },
                    )
                }
                .pointerInput(shot, box, fullW, fullH) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var vertical: Boolean? = null
                        var dy = 0f
                        var lastTime = 0L
                        var lastDy = 0f
                        var velocity = 0f
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            val pan = event.calculatePan()
                            if (pressed >= 2 || zoom.zoomed) {
                                val factor = event.calculateZoom()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val before = zoom.scale
                                val next = (before * factor).coerceIn(1f, ZoomState.MAX_SCALE)
                                val shownW = fullW * fit * before
                                val shownH = fullH * fit * before
                                if (centroid != Offset.Unspecified && next != before) {
                                    // Keep the point under the fingers still while scaling.
                                    val fx = zoom.cx + (centroid.x - box.width / 2f) / shownW
                                    val fy = zoom.cy + (centroid.y - box.height / 2f) / shownH
                                    zoom.cx = fx - (fx - zoom.cx) * before / next
                                    zoom.cy = fy - (fy - zoom.cy) * before / next
                                }
                                zoom.scale = next
                                zoom.cx -= pan.x / (fullW * fit * zoom.scale)
                                zoom.cy -= pan.y / (fullH * fit * zoom.scale)
                                if (!zoom.zoomed) zoom.reset() else clamp()
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (onVerticalSwipe != null) {
                                if (vertical == null && (kotlin.math.abs(pan.x) > 2 || kotlin.math.abs(pan.y) > 2)) vertical = kotlin.math.abs(pan.y) > kotlin.math.abs(pan.x)
                                if (vertical == true) {
                                    dy += pan.y
                                    val t = event.changes.first().uptimeMillis
                                    if (lastTime > 0 && t > lastTime) velocity = (dy - lastDy) / (t - lastTime)
                                    lastTime = t
                                    lastDy = dy
                                    onVerticalSwipe(dy, false, 0f)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (vertical == true) onVerticalSwipe?.invoke(dy, true, velocity)
                    }
                },
        ) {
            val image = ready?.image ?: return@Canvas
            val shownW = fullW * fit * zoom.scale
            val shownH = fullH * fit * zoom.scale
            val left = size.width / 2 - zoom.cx * shownW
            val top = size.height / 2 - zoom.cy * shownH
            drawBitmap(image.bitmap, left, top, shownW, shownH)
            val full = info
            if (full != null && zoom.zoomed) {
                val k = shownW / full.width
                for ((tile, bitmap) in tiles.values) {
                    drawBitmap(bitmap, left + tile.x * k, top + tile.y * k, tile.width * k, tile.height * k)
                }
            }
        }

        when {
            screen is ScreenState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = ViewerColors.foreground, strokeWidth = 2.dp)
            screen is ScreenState.Failed -> Text(stringResource(R.string.t_cull_viewer_image_failed), Modifier.align(Alignment.Center), color = ViewerColors.foreground, style = HfpsTheme.typography.body)
            active && zoomLoading -> Text(
                stringResource(if (shot.raw != null && shot.rendered == null) R.string.t_cull_viewer_rendering_raw else R.string.t_cull_viewer_loading_full),
                Modifier.align(Alignment.BottomCenter),
                color = ViewerColors.foreground,
                style = HfpsTheme.typography.ui,
            )
        }
    }
}

private fun DrawScope.drawBitmap(bitmap: ImageBitmap, x: Float, y: Float, w: Float, h: Float) {
    drawImage(
        bitmap,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(x.toInt(), y.toInt()),
        dstSize = IntSize(max(1, (x + w).toInt() - x.toInt()), max(1, (y + h).toInt() - y.toInt())),
    )
}

private suspend fun animate(zoom: ZoomState, scale: Float, cx: Float, cy: Float) {
    val from = Triple(zoom.scale, zoom.cx, zoom.cy)
    val progress = Animatable(0f)
    progress.animateTo(1f, tween(200)) {
        zoom.scale = from.first + (scale - from.first) * value
        zoom.cx = from.second + (cx - from.second) * value
        zoom.cy = from.third + (cy - from.third) * value
    }
}

/** The tile edge in image pixels at a sample (for tests and logs). */
fun tileSpan(sample: Int) = TILE_SIZE * sample
