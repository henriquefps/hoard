package dev.hfps.hoard.smb.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.hierynomus.smbj.share.File as SmbFile
import dev.hfps.hoard.smb.Cancellation
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.SmbSession
import dev.hfps.hoard.smb.location.LocalFile
import dev.hfps.hoard.smb.location.LocalFs
import dev.hfps.hoard.smb.smbFail
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A generated thumbnail on disk. */
data class Thumbnail(val file: File, val width: Int, val height: Int)

/**
 * Image and video thumbnails, cached as JPEG under `cache/thumbs/` by
 * server GUID + share + path + maxSize + size + modified time.
 *
 * - JPEGs asked at row size first try the EXIF thumbnail from the first 128 KiB (no full download).
 * - Other images are downloaded (up to [MAX_IMAGE_BYTES]) and decoded with subsampling;
 *   ImageDecoder on Android 9+ (HEIF/AVIF/WebP, EXIF orientation), BitmapFactory before.
 * - Videos are never downloaded: MediaMetadataRetriever reads the container through a
 *   [MediaDataSource] that does random-access SMB reads (moov atom, then one frame).
 */
class Thumbnailer(private val context: Context) {

    private val root = File(context.cacheDir, "thumbs")
    private var writesSinceTrim = 0

    /** Blocking; run on an IO thread. */
    fun thumbnail(session: SmbSession, share: String, path: String, maxSize: Int, cancellation: Cancellation = Cancellation.NONE): Thumbnail {
        val clean = SmbPaths.normalize(path)
        val ext = SmbPaths.extension(clean)
        val isImage = ext in IMAGE_EXTENSIONS
        val isVideo = ext in VIDEO_EXTENSIONS
        if (clean.isEmpty() || (!isImage && !isVideo)) {
            smbFail(ErrorCode.UNKNOWN, "Thumbnails are only available for images and videos")
        }
        val size = maxSize.coerceIn(16, 2048)
        RemoteFiles.openRead(session, share, clean).use { remote ->
            val info = RemoteFiles.info(remote)
            if (info.isDirectory) smbFail(ErrorCode.UNKNOWN, "Thumbnails are only available for files")
            val key = RemoteFiles.key(session.handle.serverGuid, share.lowercase(), clean, size, info.size, info.modifiedMillis)
            val target = File(root, "$key.jpg")
            cached(target)?.let { return it }

            val bitmap = when {
                isVideo -> videoFrame(remote, info.size, size)
                else -> image(remote, info.size, ext, size, key, cancellation)
            } ?: smbFail(ErrorCode.UNKNOWN, "Could not decode ${SmbPaths.name(clean)}")
            return save(fit(bitmap, size), target)
        }
    }

    /**
     * A local location's file (phone storage, USB drive, SD card): decoded in place, never copied.
     * Same cache directory and rules; keyed by location + path + size + modified time.
     */
    fun thumbnail(fs: LocalFs, path: String, maxSize: Int): Thumbnail {
        val clean = SmbPaths.normalize(path)
        val ext = SmbPaths.extension(clean)
        val isImage = ext in IMAGE_EXTENSIONS
        val isVideo = ext in VIDEO_EXTENSIONS
        if (clean.isEmpty() || (!isImage && !isVideo)) {
            smbFail(ErrorCode.UNKNOWN, "Thumbnails are only available for images and videos")
        }
        val size = maxSize.coerceIn(16, 2048)
        val file = fs.file(clean)
        if (file.isDirectory) smbFail(ErrorCode.UNKNOWN, "Thumbnails are only available for files")
        val key = RemoteFiles.key(fs.cacheId, clean, size, file.size, file.modifiedMillis)
        val target = File(root, "$key.jpg")
        cached(target)?.let { return it }
        val bitmap = when {
            isVideo -> videoFrame(size) { retriever ->
                if (file.file != null) retriever.setDataSource(file.file.path) else retriever.setDataSource(context, file.documentUri)
            }
            else -> localImage(fs, file, ext, size)
        } ?: smbFail(ErrorCode.UNKNOWN, "Could not decode ${file.name}")
        return save(fit(bitmap, size), target)
    }

    private fun localImage(fs: LocalFs, file: LocalFile, ext: String, size: Int): Bitmap? {
        if ((ext == "jpg" || ext == "jpeg") && size <= EXIF_THUMB_MAX_REQUEST) {
            runCatching {
                val exif = if (file.file != null) ExifInterface(file.file) else fs.openInput(file).use { ExifInterface(it) }
                val thumb = exif.thumbnailBitmap
                if (exif.hasThumbnail() && thumb != null) {
                    if (max(thumb.width, thumb.height) >= size * 3 / 4) return rotate(thumb, exif.rotationDegrees, exif.isFlipped)
                    thumb.recycle()
                }
            }
        }
        if (file.size > MAX_LOCAL_IMAGE_BYTES) smbFail(ErrorCode.UNKNOWN, "Image too large for a thumbnail")
        file.file?.let { return decodeFile(it, size) }
        val uri = file.documentUri ?: return null
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val longest = max(info.size.width, info.size.height)
                    if (longest > size) {
                        val scale = size.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        fs.openInput(file).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val bitmap = fs.openInput(file).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val exif = runCatching { fs.openInput(file).use { ExifInterface(it) } }.getOrNull()
        return rotate(bitmap, exif?.rotationDegrees ?: 0, exif?.isFlipped ?: false)
    }

    private fun cached(target: File): Thumbnail? {
        if (!target.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, bounds)
        if (bounds.outWidth <= 0) {
            target.delete()
            return null
        }
        target.setLastModified(System.currentTimeMillis())
        return Thumbnail(target, bounds.outWidth, bounds.outHeight)
    }

    private fun save(bitmap: Bitmap, target: File): Thumbnail {
        root.mkdirs()
        val temp = File(root, ".${target.name}.part")
        FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        temp.renameTo(target)
        val result = Thumbnail(target, bitmap.width, bitmap.height)
        bitmap.recycle()
        if (++writesSinceTrim >= 50) {
            writesSinceTrim = 0
            RemoteFiles.trim(root, MAX_CACHE_BYTES)
        }
        return result
    }

    // --- Images ---------------------------------------------------------------------------

    private fun image(remote: SmbFile, length: Long, ext: String, size: Int, key: String, cancellation: Cancellation): Bitmap? {
        if ((ext == "jpg" || ext == "jpeg") && size <= EXIF_THUMB_MAX_REQUEST) {
            exifThumbnail(remote, size)?.let { return it }
        }
        if (length > MAX_IMAGE_BYTES) smbFail(ErrorCode.UNKNOWN, "Image too large for a thumbnail")
        val temp = File(root, ".$key.src")
        try {
            RemoteFiles.download(remote, length, temp, cancellation)
            return decodeFile(temp, size)
        } finally {
            temp.delete()
        }
    }

    /** The JPEG's embedded EXIF thumbnail, if it is big enough for [size]. */
    private fun exifThumbnail(remote: SmbFile, size: Int): Bitmap? = runCatching {
        val head = RemoteFiles.head(remote, EXIF_HEAD_BYTES)
        val exif = ExifInterface(ByteArrayInputStream(head))
        if (!exif.hasThumbnail()) return null
        val thumb = exif.thumbnailBitmap ?: return null
        if (max(thumb.width, thumb.height) < size * 3 / 4) {
            thumb.recycle()
            return null
        }
        rotate(thumb, exif.rotationDegrees, exif.isFlipped)
    }.getOrNull()

    private fun decodeFile(file: File, size: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val longest = max(info.size.width, info.size.height)
                    if (longest > size) {
                        val scale = size.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setOnPartialImageListener { true } // show what decoded of a truncated file
                }
            }.getOrNull() ?: decodeLegacy(file, size)
        }
        return decodeLegacy(file, size)
    }

    private fun decodeLegacy(file: File, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val exif = runCatching { ExifInterface(file) }.getOrNull()
        return rotate(bitmap, exif?.rotationDegrees ?: 0, exif?.isFlipped ?: false)
    }

    private fun rotate(bitmap: Bitmap, degrees: Int, flipped: Boolean): Bitmap {
        if (degrees == 0 && !flipped) return bitmap
        val matrix = Matrix().apply {
            if (flipped) postScale(-1f, 1f)
            postRotate(degrees.toFloat())
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    // --- Videos ---------------------------------------------------------------------------

    private fun videoFrame(remote: SmbFile, length: Long, size: Int): Bitmap? =
        videoFrame(size) { it.setDataSource(SmbMediaDataSource(remote, length)) }

    private fun videoFrame(size: Int, source: (MediaMetadataRetriever) -> Unit): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            source(retriever)
            val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000)
            // A frame a little in (skips black/fade-in first frames), capped for short clips.
            val atUs = durationUs?.let { (it / 10).coerceAtMost(3_000_000) } ?: 0
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: size
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: size
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val (w, h) = if (rotation % 180 == 0) width to height else height to width
                val scale = minOf(1f, size.toFloat() / max(w, h).coerceAtLeast(1))
                retriever.getScaledFrameAtTime(
                    atUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (w * scale).roundToInt().coerceAtLeast(1),
                    (h * scale).roundToInt().coerceAtLeast(1),
                )
            } else {
                retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            return frame ?: retriever.frameAtTime
        } catch (e: RuntimeException) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Random-access reads straight from the SMB file for MediaMetadataRetriever. */
    private class SmbMediaDataSource(private val file: SmbFile, private val length: Long) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= length) return -1
            if (size == 0) return 0
            val wanted = minOf(size.toLong(), length - position).toInt()
            val read = file.read(buffer, position, offset, wanted)
            return if (read <= 0) -1 else read
        }

        override fun getSize(): Long = length
        override fun close() = Unit
    }

    /** Scale down so the longest side is at most [size]. */
    private fun fit(bitmap: Bitmap, size: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= size) return bitmap
        val scale = size.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm")
        private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
        /** Local images decode in place (no download), so the cap only guards against absurd files. */
        private const val MAX_LOCAL_IMAGE_BYTES = 512L * 1024 * 1024
        private const val EXIF_HEAD_BYTES = 128 * 1024
        private const val EXIF_THUMB_MAX_REQUEST = 200
        private const val JPEG_QUALITY = 82
        private const val MAX_CACHE_BYTES = 150L * 1024 * 1024
    }
}
