package dev.hfps.hoard.app.gallery

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerException
import dev.hfps.hoard.server.preview.OfflineThumbs
import dev.hfps.hoard.server.preview.PreviewCache
import dev.hfps.hoard.server.preview.PreviewSize
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Hoard 2.2.2: what the Gallery's viewer and filmstrip show for a server shot, phone copies first. The grid draws from
 * the offline thumbnails ([OfflineThumbs]); the viewer used to look only in [PreviewCache], so offline it found nothing
 * for exactly the shots the grid showed.
 */
class ServerShotImagesTest {
    @get:Rule val tmp = TemporaryFolder()

    private var api: suspend () -> ServerApi? = { throw IOException("airplane mode") }

    private fun setup(): Triple<PreviewCache, OfflineThumbs, ServerShotImages> {
        val previews = PreviewCache(tmp.newFolder("previews"), 1L shl 30) { api() }
        val offline = OfflineThumbs(tmp.newFolder("thumbs"))
        return Triple(previews, offline, ServerShotImages(previews, offline))
    }

    private fun File.put(): File = apply {
        parentFile?.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
    }

    private fun PreviewCache.put(id: String, size: PreviewSize, edit: String? = null) = file(id, size, edit?.let { "primary" }, edit ?: "o").put()

    @Test
    fun theFilmstripFindsTheGridsOfflineThumbnail() {
        val (_, offline, images) = setup()
        val kept = offline.file("item-1").put()
        // Same item, same file: the cache keys of the grid and the filmstrip agree.
        assertThat(images.cachedThumb("item-1", edit = null)).isEqualTo(kept)
        assertThat(images.cachedScreen("item-1", edit = null)).isEqualTo(ServerShotImages.Local(kept, lowRes = true))
        assertThat(images.cachedThumb("item-2", edit = null)).isNull()
        assertThat(images.cachedScreen("item-2", edit = null)).isNull()
    }

    @Test
    fun theBestPhoneCopyComesFirst() {
        val (previews, offline, images) = setup()
        offline.file("item-1").put()
        val thumb = previews.put("item-1", PreviewSize.Thumb)
        // A 512 px server thumb beats the 256 px offline one.
        assertThat(images.cachedThumb("item-1", null)).isEqualTo(thumb)
        assertThat(images.cachedScreen("item-1", null)).isEqualTo(ServerShotImages.Local(thumb, lowRes = true))
        // The screen preview is final, not a stand-in.
        val screen = previews.put("item-1", PreviewSize.Screen)
        assertThat(images.cachedScreen("item-1", null)).isEqualTo(ServerShotImages.Local(screen, lowRes = false))
    }

    @Test
    fun anEditWithoutItsOwnCopiesFallsBackToTheOriginals() {
        val (previews, offline, images) = setup()
        val kept = offline.file("item-1").put()
        assertThat(images.cachedThumb("item-1", edit = "v-2")).isEqualTo(kept)
        val original = previews.put("item-1", PreviewSize.Thumb)
        assertThat(images.cachedThumb("item-1", edit = "v-2")).isEqualTo(original)
        val edited = previews.put("item-1", PreviewSize.Thumb, edit = "v-2")
        assertThat(images.cachedThumb("item-1", edit = "v-2")).isEqualTo(edited)
        // The original's screen preview is not the edit: the edit shows as its thumb until its own screen arrives.
        previews.put("item-1", PreviewSize.Screen)
        assertThat(images.cachedScreen("item-1", edit = "v-2")).isEqualTo(ServerShotImages.Local(edited, lowRes = true))
    }

    @Test
    fun aNetworkFailureIsOfflineAndAServerAnswerIsNot() = runBlocking {
        val (previews, _, images) = setup()
        assertThat(images.fetch("item-1", PreviewSize.Screen, null)).isEqualTo(ServerShotImages.Fetched.Offline)
        api = { throw ServerException(500, "internal", "boom") }
        assertThat(images.fetch("item-1", PreviewSize.Screen, null)).isEqualTo(ServerShotImages.Fetched.Missing)
        api = { null }
        assertThat(images.fetch("item-1", PreviewSize.Screen, null)).isEqualTo(ServerShotImages.Fetched.Missing)
        // Cached: no network at all.
        api = { throw IOException("airplane mode") }
        val screen = previews.put("item-1", PreviewSize.Screen)
        assertThat(images.fetch("item-1", PreviewSize.Screen, null)).isEqualTo(ServerShotImages.Fetched.Ready(screen))
    }
}
