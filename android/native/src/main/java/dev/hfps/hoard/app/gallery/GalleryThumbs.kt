package dev.hfps.hoard.app.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.hfps.hoard.server.preview.PreviewCache
import dev.hfps.hoard.server.preview.PreviewSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Grid thumbnails (ADR 0021): the phone's own (MediaStore) for anything on the phone, the server's thumb preview
 * (WebP, ≤ 512 px; Reveal's primary edit when there is one, ADR 0020) for the rest, decoded once into a memory cache
 * sized for a few screens of cells. A server preview not made yet is asked again when the cell comes back.
 */
/** Where grid cells get their images (the real one below; tests draw their own). */
interface ThumbSource {
    fun key(entry: GalleryEntry, px: Int): String
    fun peek(entry: GalleryEntry, px: Int): ImageBitmap?
    suspend fun load(entry: GalleryEntry, px: Int): ImageBitmap?
}

class GalleryThumbs(
    private val context: Context,
    private val previews: PreviewCache,
    private val deviceIndex: DeviceIndexer,
    private val offline: dev.hfps.hoard.server.preview.OfflineThumbs? = null,
) : ThumbSource {
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
    }
    private val deviceSlots = Semaphore(4)
    private val serverSlots = Semaphore(6)

    /** Whether a cell shows the server's image (an edit, or a shot the phone does not have). */
    fun fromServer(entry: GalleryEntry, edited: Boolean = true): Boolean = entry.itemId != null && (entry.device == null || (edited && entry.edited))

    override fun key(entry: GalleryEntry, px: Int): String = key(entry, px, true)

    fun key(entry: GalleryEntry, px: Int, edited: Boolean): String =
        if (fromServer(entry, edited)) "s:${entry.itemId}:${if (edited) entry.editPrimary ?: "o" else "o"}:$px" else "d:${entry.device!!.main.id}:${entry.device.main.modified}:$px"

    override fun peek(entry: GalleryEntry, px: Int): ImageBitmap? = memory.get(key(entry, px))

    override suspend fun load(entry: GalleryEntry, px: Int): ImageBitmap? {
        val key = key(entry, px)
        memory.get(key)?.let { return it }
        val bitmap = try {
            if (fromServer(entry)) serverSlots.withPermit { server(entry, px) } else deviceSlots.withPermit { device(entry, px) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        } ?: return null
        return bitmap.asImageBitmap().also { memory.put(key, it) }
    }

    /** The server thumb file (for the viewer's filmstrip and the culling backend). */
    suspend fun serverThumbFile(entry: GalleryEntry, edited: Boolean = true): File? {
        val itemId = entry.itemId ?: return null
        val edit = if (edited) entry.editPrimary else null
        return previews.get(itemId, PreviewSize.Thumb, edit = edit?.let { "primary" }, version = edit ?: "o")
    }

    private suspend fun server(entry: GalleryEntry, px: Int): Bitmap? {
        // The offline thumbnail first (no network), unless Reveal's edit is shown.
        if (!entry.edited && px <= OFFLINE_MAX_PX) {
            val kept = offline?.file(entry.itemId!!)
            if (kept != null) withContext(Dispatchers.IO) { if (kept.isFile) decodeSampled(kept, px) else null }?.let { return it }
        }
        val file = serverThumbFile(entry) ?: return null
        return withContext(Dispatchers.IO) { decodeSampled(file, px) }
    }

    private suspend fun device(entry: GalleryEntry, px: Int): Bitmap? = withContext(Dispatchers.IO) {
        val row = entry.device!!.files.first { !it.media.video || entry.isVideo }.media
        val uri = deviceIndex.uriOf(row)
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(uri, Size(px, px), null)
        } else {
            @Suppress("DEPRECATION")
            if (row.video) MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, row.id - DeviceIndexer.VIDEO_ID_BASE, MediaStore.Video.Thumbnails.MINI_KIND, null)
            else MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, row.id, MediaStore.Images.Thumbnails.MINI_KIND, null)
        }
    }

    fun clear() = memory.evictAll()

    companion object {
        private const val MEMORY_BYTES = 40 shl 20
        /** Cells up to this size use the 256 px offline thumbnail. */
        private const val OFFLINE_MAX_PX = 384

        fun decodeSampled(file: File, px: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
            return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
        }
    }
}
