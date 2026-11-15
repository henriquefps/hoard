package dev.hfps.hoard.app.gallery

import dev.hfps.hoard.server.preview.OfflineThumbs
import dev.hfps.hoard.server.preview.PreviewCache
import dev.hfps.hoard.server.preview.PreviewSize
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * Where the viewer finds a server shot's image (2.2.2), what the phone holds first and the network after, so a shot
 * opened offline (airplane mode, the server unreachable) shows whatever copy the phone has instead of an error:
 *
 * 1. the screen preview in [PreviewCache] (the shown variant: Reveal's edit, else the original), final;
 * 2. a thumbnail, as a low-resolution stand-in upgraded when the screen preview arrives: the server thumb in
 *    [PreviewCache] (the variant's, then the original's), else the 256 px offline thumbnail ([OfflineThumbs]).
 *
 * The grid draws server cells from the offline thumbnails first (ADR 0021), so before 2.2.2 the viewer and its
 * filmstrip, which only looked in [PreviewCache], found nothing for the shots the grid had shown from them.
 * [edit] is the version id of Reveal's primary edit when it is shown, else null.
 */
class ServerShotImages(private val previews: PreviewCache, private val offline: OfflineThumbs?) {
    /** An image already on the phone; [lowRes] when a thumbnail stands in for the screen preview. */
    data class Local(val file: File, val lowRes: Boolean)

    /** What the network gave: the file, nothing (the server has no such preview), or no answer (offline). */
    sealed interface Fetched {
        data class Ready(val file: File) : Fetched
        data object Missing : Fetched
        data object Offline : Fetched
    }

    private fun cached(itemId: String, size: PreviewSize, edit: String?): File? =
        previews.cached(itemId, size, edit?.let { "primary" }, edit ?: "o")

    /** The best thumbnail on the phone (no network), or null. */
    fun cachedThumb(itemId: String, edit: String?): File? =
        cached(itemId, PreviewSize.Thumb, edit)
            ?: (if (edit != null) cached(itemId, PreviewSize.Thumb, null) else null)
            ?: offline?.file(itemId)?.takeIf { it.isFile }

    /** The best image on the phone for the viewer (no network): the screen preview, else a thumbnail. */
    fun cachedScreen(itemId: String, edit: String?): Local? =
        cached(itemId, PreviewSize.Screen, edit)?.let { Local(it, lowRes = false) }
            ?: cachedThumb(itemId, edit)?.let { Local(it, lowRes = true) }

    /** The preview from the cache or the server; a network failure is [Fetched.Offline], not an error. */
    suspend fun fetch(itemId: String, size: PreviewSize, edit: String?): Fetched = try {
        previews.get(itemId, size, edit = edit?.let { "primary" }, version = edit ?: "o")?.let { Fetched.Ready(it) } ?: Fetched.Missing
    } catch (e: CancellationException) {
        throw e
    } catch (e: dev.hfps.hoard.server.api.ServerException) {
        Fetched.Missing
    } catch (e: IOException) {
        Fetched.Offline
    } catch (e: Throwable) {
        Fetched.Missing
    }
}
