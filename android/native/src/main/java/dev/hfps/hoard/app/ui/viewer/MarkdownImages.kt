package dev.hfps.hoard.app.ui.viewer

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.hfps.hoard.app.files.LinkTarget
import dev.hfps.hoard.app.files.THUMBNAIL_EXTENSIONS
import dev.hfps.hoard.app.files.extensionOf
import dev.hfps.hoard.app.files.resolveLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where an image of a Markdown file comes from, by Hoard 1's rules (MarkdownView.tsx `Image`, links.ts): a path
 * relative to the file (or `/` = the share's root) is a file on the same location, shown through its thumbnail
 * (1600 px, the same native thumbnailer as the browser; types without a thumbnail are not shown); `http(s)` is
 * fetched (no referrer, no cookies); `data:image/…` is decoded; anything else (another scheme, above the root) is not
 * shown. A failure shows the alt text with a broken-image mark.
 */
sealed interface ImageSource {
    data class Share(val path: String) : ImageSource
    data class Web(val url: String) : ImageSource
    data class Data(val url: String) : ImageSource
    data object Blocked : ImageSource
}

fun imageSource(filePath: String, src: String): ImageSource = when (val target = resolveLink(filePath, src)) {
    is LinkTarget.External -> if (target.url.startsWith("mailto:", ignoreCase = true)) ImageSource.Blocked else ImageSource.Web(target.url)
    is LinkTarget.Data -> ImageSource.Data(target.url)
    is LinkTarget.Share -> if (!target.isFolder && extensionOf(target.path.substringAfterLast('/')) in THUMBNAIL_EXTENSIONS) ImageSource.Share(target.path) else ImageSource.Blocked
    else -> ImageSource.Blocked
}

/** Longest side an image of the preview is decoded at (Hoard 1 asked thumbnails of 1600). */
const val MARKDOWN_IMAGE_SIZE = 1600

/** Web images: at most this many bytes, these timeouts. */
private const val WEB_MAX_BYTES = 12 * 1024 * 1024
private const val WEB_TIMEOUT_MS = 15_000

/**
 * Loads the images of one Markdown document. [shareThumbnail] gives a thumbnail file of a path on the file's location
 * (`FileService.thumbnail`); decoded bitmaps stay in a small memory cache shared by the previews.
 */
class MarkdownImages(private val filePath: String, private val cacheKey: String, private val shareThumbnail: suspend (String) -> File) {
    suspend fun load(src: String): ImageBitmap? {
        val source = imageSource(filePath, src)
        val key = when (source) {
            is ImageSource.Share -> "$cacheKey/${source.path}"
            is ImageSource.Web -> source.url
            is ImageSource.Data -> source.url.hashCode().toString() + ":" + source.url.length
            ImageSource.Blocked -> return null
        }
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                when (source) {
                    is ImageSource.Share -> decode(shareThumbnail(source.path).readBytes())
                    is ImageSource.Web -> decode(fetch(source.url))
                    is ImageSource.Data -> decode(Base64.decode(source.url.substringAfter(','), Base64.DEFAULT))
                    ImageSource.Blocked -> null
                }
            }.getOrNull()
        }
        bitmap?.let { cache.put(key, it) }
        return bitmap
    }

    private fun fetch(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = WEB_TIMEOUT_MS
            connection.readTimeout = WEB_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "image/*")
            connection.useCaches = true
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            if (connection.contentLengthLong > WEB_MAX_BYTES) error("Too large")
            connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > WEB_MAX_BYTES) error("Too large")
                }
                return out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private val cache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
        }

        /** Decodes at most [MARKDOWN_IMAGE_SIZE] on the long side (a power-of-two sample, then exact). */
        fun decode(bytes: ByteArray): ImageBitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MARKDOWN_IMAGE_SIZE) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            return bitmap.asImageBitmap()
        }
    }
}
