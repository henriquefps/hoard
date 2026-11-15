package dev.hfps.hoard.app.cull

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.hfps.hoard.image.ScreenSource
import dev.hfps.hoard.image.ZoomInfo
import dev.hfps.hoard.image.ZoomSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A screen image ready to draw, with what it was made from and whether it is the plan's final quality. */
data class ScreenImage(val bitmap: ImageBitmap, val width: Int, val height: Int, val source: ScreenSource, val final: Boolean, val lowRes: Boolean = false)

/** A 100 % tile ready to draw at its place in the full-resolution image. */
data class TileImage(val tile: Tile, val bitmap: ImageBitmap)

/**
 * Image loading for the culling screens (images.ts): grid thumbnails (four at a time), screen images (fast, then
 * final), 100 % zoom info and tiles. The image core writes JPEG files (its disk caches, sizes as 1.10.1: the thumbs
 * have their own); here they are decoded once and kept in small memory caches keyed by the shot's file identity, so
 * paging back and forth never asks twice. A thumbnail whose file vanished is asked for again once (1.10.1).
 */
class CullImages(private val backend: CullBackend, private val scope: CoroutineScope, val longSide: Int) {
    private val thumbs = object : LruCache<String, ImageBitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
    }
    private val screens = object : LruCache<String, ScreenImage>(SCREEN_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ScreenImage) = value.width * value.height * 4
    }
    private val tiles = object : LruCache<String, ImageBitmap>(TILE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val zooms = ConcurrentHashMap<String, ZoomInfo>()
    private val thumbSlots = Semaphore(4)
    private val tileSlots = Semaphore(3)
    private val pending = ConcurrentHashMap<String, Deferred<*>>()
    private val failedThumbs = ConcurrentHashMap.newKeySet<String>()

    /** Measurements (ADR 0008), read with `adb logcat -s HoardCull`. */
    val timings = CullTimings()

    private fun shotKey(shot: Shot) = "${shot.id}|${shot.raw?.size}:${shot.raw?.modifiedAt}|${shot.rendered?.size}:${shot.rendered?.modifiedAt}|${backend.imageVariant(shot.id)}"

    fun peekThumbnail(shot: Shot): ImageBitmap? = thumbs.get(shotKey(shot))

    fun thumbnailFailed(shot: Shot): Boolean = shotKey(shot) in failedThumbs

    /** A grid / filmstrip thumbnail; null when it cannot be made (the tile stays muted). */
    suspend fun thumbnail(shot: Shot): ImageBitmap? {
        val key = shotKey(shot)
        thumbs.get(key)?.let { return it }
        if (key in failedThumbs) return null
        return shared("thumb:$key") {
            thumbSlots.withPermit {
                thumbs.get(key) ?: run {
                    val started = SystemClock.elapsedRealtime()
                    var bitmap = decodeOrNull(backend.thumbnail(shot.id, THUMB_SIZE), hardware = false, rgb565 = true)
                    if (bitmap == null) {
                        // The cached file is gone or unreadable (1.10.1): ask once more, the core writes it anew.
                        timings.record("thumb:reload", 0)
                        bitmap = decodeOrNull(backend.thumbnail(shot.id, THUMB_SIZE), hardware = false, rgb565 = true)
                    }
                    if (bitmap == null) {
                        failedThumbs += key
                        timings.record("thumb:failed", 0)
                        null
                    } else {
                        timings.record("thumb", SystemClock.elapsedRealtime() - started)
                        bitmap.asImageBitmap().also { thumbs.put(key, it) }
                    }
                }
            }
        }
    }

    /** The best screen image already decoded (final, else fast), without loading. */
    fun cachedScreen(shot: Shot): ScreenImage? = screens.get(shotKey(shot) + "|final") ?: screens.get(shotKey(shot) + "|fast")

    /** A screen image; shared by every caller asking for the same one. */
    suspend fun screen(shot: Shot, fast: Boolean, background: Boolean = false): ScreenImage {
        val key = shotKey(shot) + if (fast) "|fast" else "|final"
        screens.get(key)?.let { return it }
        return shared("screen:$key") {
            val started = SystemClock.elapsedRealtime()
            val file = backend.screen(shot.id, longSide, fast, background)
            val bitmap = decodeOrNull(file.file, hardware = true, rgb565 = false)
                ?: throw dev.hfps.hoard.smb.HoardSmbException(dev.hfps.hoard.smb.ErrorCode.UNKNOWN, "Could not decode ${file.file.name}")
            val image = ScreenImage(bitmap.asImageBitmap(), file.width, file.height, file.source, file.final, file.lowRes)
            screens.put(key, image)
            if (file.final) screens.put(shotKey(shot) + "|final", image)
            timings.record("image:${if (fast) "fast" else "final"}", SystemClock.elapsedRealtime() - started)
            image
        }
    }

    fun cachedZoom(shot: Shot): ZoomInfo? = zooms[shotKey(shot)]

    /** Prepares the 100 % image (stages the JPEG, or renders a RAW-only shot) and learns its size. */
    suspend fun zoom(shot: Shot): ZoomInfo {
        val key = shotKey(shot)
        zooms[key]?.let { return it }
        return shared("zoom:$key") {
            val started = SystemClock.elapsedRealtime()
            backend.zoomInfo(shot.id).also {
                zooms[key] = it
                timings.record("zoom:${zoomSourceName(it.source)}", SystemClock.elapsedRealtime() - started)
            }
        }
    }

    fun cachedTile(shot: Shot, tile: Tile): ImageBitmap? = tiles.get(shotKey(shot) + "|" + tile.key)

    suspend fun tile(shot: Shot, tile: Tile): ImageBitmap? {
        val key = shotKey(shot) + "|" + tile.key
        tiles.get(key)?.let { return it }
        return shared("tile:$key") {
            tileSlots.withPermit {
                tiles.get(key) ?: decodeOrNull(backend.tile(shot.id, tile).file, hardware = true, rgb565 = false)?.asImageBitmap()?.also { tiles.put(key, it) }
            }
        }
    }

    fun prefetch(order: List<String>, index: Int) {
        val slice = prefetchSlice(order, index, 6, 1)
        if (slice.shotIds.isNotEmpty()) backend.prefetch(slice.shotIds, slice.current, longSide)
    }

    /**
     * One load per key, whoever asks: it runs in the images' own scope, so a caller that leaves (a cell scrolled
     * away) does not cancel it for the others.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> shared(key: String, load: suspend () -> T): T {
        val created = scope.async(start = CoroutineStart.LAZY) { load() }
        val deferred = (pending.putIfAbsent(key, created) ?: created) as Deferred<T>
        if (deferred !== created) created.cancel()
        deferred.invokeOnCompletion { pending.remove(key, deferred) }
        return deferred.await()
    }

    private suspend fun decodeOrNull(file: File, hardware: Boolean, rgb565: Boolean): Bitmap? = withContext(decodeDispatcher) {
        try {
            if (!file.isFile) return@withContext null
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = when {
                    hardware && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !forceSoftware -> Bitmap.Config.HARDWARE
                    rgb565 -> Bitmap.Config.RGB_565
                    else -> Bitmap.Config.ARGB_8888
                }
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "decode ${file.name}: $e")
            null
        }
    }

    companion object {
        const val TAG = "HoardCull"
        const val THUMB_SIZE = 320
        private const val THUMB_CACHE_BYTES = 48 shl 20
        private const val SCREEN_CACHE_BYTES = 120 shl 20
        private const val TILE_CACHE_BYTES = 64 shl 20

        /** Robolectric cannot draw HARDWARE bitmaps; screenshot tests switch them off. */
        @Volatile var forceSoftware = false

        /** Where files are decoded; Robolectric's native graphics must stay on the test's thread. */
        @Volatile var decodeDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

        fun zoomSourceName(source: ZoomSource) = when (source) {
            ZoomSource.RENDERED -> "rendered"
            ZoomSource.RAW_PREVIEW -> "rawPreview"
            ZoomSource.RAW_FULL -> "rawFull"
        }

        fun screenSourceName(source: ScreenSource) = when (source) {
            ScreenSource.RAW_PREVIEW -> "rawPreview"
            ScreenSource.RENDERED -> "rendered"
            ScreenSource.RAW_HALF -> "rawHalf"
        }

        /** Screen long side to ask for, in device pixels (S24 Ultra: 3120), as Hoard 1. */
        fun screenLongSide(widthPx: Int, heightPx: Int): Int = maxOf(widthPx, heightPx).coerceIn(1024, 4096)
    }
}

/** Culling timings (ADR 0008 measurements): logged per sample and summarised on demand (`summary()`). */
class CullTimings {
    private val samples = ConcurrentHashMap<String, MutableList<Long>>()

    fun record(name: String, ms: Long) {
        val list = samples.getOrPut(name) { java.util.Collections.synchronizedList(ArrayList()) }
        list += ms
        if (list.size > 500) list.removeAt(0)
        Log.i(CullImages.TAG, "timing $name $ms ms")
    }

    /** name → n, median, max (what `hoardCull.summary()` printed for Hoard 1). */
    fun summary(): Map<String, Triple<Int, Long, Long>> = samples.mapValues { (_, list) ->
        val sorted = synchronized(list) { list.sorted() }
        Triple(sorted.size, sorted.getOrElse(sorted.size / 2) { 0 }, sorted.lastOrNull() ?: 0)
    }
}
