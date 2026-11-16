package dev.hfps.hoard.app.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import dev.hfps.hoard.app.gallery.DeviceIndexer
import dev.hfps.hoard.app.gallery.GalleryRepository
import dev.hfps.hoard.app.trash.MediaConsent
import dev.hfps.hoard.data.SettingsStore
import dev.hfps.hoard.server.backup.Conditions
import dev.hfps.hoard.server.backup.FreeShot
import dev.hfps.hoard.server.backup.FreeUpEngine
import dev.hfps.hoard.server.backup.FreeUpPlan
import dev.hfps.hoard.server.backup.FreeUpRules
import dev.hfps.hoard.server.backup.FreeUpSource
import dev.hfps.hoard.server.db.BackupFileEntity
import dev.hfps.hoard.server.sync.GallerySyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Free up space on the phone (ADR 0024 "Free up space", Hoard 2.6.0, Android 11+): the rules (Settings › Backup),
 * favourites from MediaStore, culling pushed first, and the deletion through `MediaStore.createDeleteRequest` (the
 * system's dialog is the owner's consent, also with All files access: ADR 0023's option (b) for permanent deletes),
 * in requests of at most 1000 files. Which files are really gone is read back afterwards. The decisions are
 * [FreeUpEngine]'s and [FreeUpPlan]'s.
 */
class PhoneFreeUp(
    private val context: Context,
    private val gallery: GalleryRepository,
    private val settings: SettingsStore,
) : FreeUpSource {
    private val db get() = gallery.db
    val engine: FreeUpEngine by lazy { FreeUpEngine(db, { gallery.sync.api() }, this) }

    // --- Rules (Settings › Backup › Free up space) -----------------------------------------------------------------

    suspend fun rules(): FreeUpRules = FreeUpRules(
        keepRecent = settings.getString(KEY_KEEP_RECENT) != OFF,
        keepFavourites = settings.getString(KEY_KEEP_FAVOURITES) != OFF,
    )

    val observeRules: Flow<FreeUpRules> = combine(settings.observeString(KEY_KEEP_RECENT), settings.observeString(KEY_KEEP_FAVOURITES)) { recent, fav ->
        FreeUpRules(keepRecent = recent != OFF, keepFavourites = fav != OFF)
    }

    suspend fun setRules(rules: FreeUpRules) {
        settings.putString(KEY_KEEP_RECENT, if (rules.keepRecent) ON else OFF)
        settings.putString(KEY_KEEP_FAVOURITES, if (rules.keepFavourites) ON else OFF)
    }

    // --- FreeUpSource ----------------------------------------------------------------------------------------------

    override suspend fun favourites(mediaIds: Collection<Long>): Set<Long> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 30 || mediaIds.isEmpty()) return@withContext emptySet()
        val out = HashSet<Long>()
        fun read(collection: Uri, base: Long) = runCatching {
            context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.IS_FAVORITE} = 1", null, null)?.use { c ->
                while (c.moveToNext()) out += base + c.getLong(0)
            }
        }.onFailure { Log.w(TAG, "free up: favourites unreadable (${it.javaClass.simpleName})") }
        read(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 0L)
        read(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, DeviceIndexer.VIDEO_ID_BASE)
        val wanted = mediaIds.toHashSet()
        out.filterTo(HashSet()) { it in wanted }
    }

    /** Culling decisions go to the server before anything is freed (a sync pushes them and hands phone shots over). */
    override suspend fun pushCulling() {
        if (db.account().get() != null) gallery.syncNow()
    }

    override suspend fun pendingCulling(keys: Set<String>): Set<String> = gallery.pendingCulling(keys)

    override suspend fun hashed(mediaId: Long, sha256: String, size: Long, modified: Long) {
        val row = db.device().get(mediaId) ?: return
        if (row.size == size && row.modified == modified) db.device().setSha(mediaId, sha256, size, modified)
    }

    override fun conditions(): Conditions = PhoneBackup.conditionsOf(context)

    // --- Deletion --------------------------------------------------------------------------------------------------

    /** The content URI of a backed-up MediaStore file (`device_media` ids keep videos above [DeviceIndexer.VIDEO_ID_BASE]). */
    fun uriOf(mediaId: Long): Uri = if (mediaId >= DeviceIndexer.VIDEO_ID_BASE) {
        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId - DeviceIndexer.VIDEO_ID_BASE)
    } else {
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
    }

    /**
     * One system consent for [shots]' media files (`createDeleteRequest`, at most 1000 per request; a debug build reads
     * a smaller size from the file `freeup-request-size` in the app's files folder (`run-as`), to test a partial
     * consent with few files). True when the owner allowed it; what was deleted is read back with [gone] either way.
     */
    @RequiresApi(30)
    suspend fun requestDelete(shots: List<FreeShot>): Boolean {
        val uris = shots.flatMap { s -> s.media.map { uriOf(it.mediaId!!) } }
        if (uris.isEmpty()) return false
        val intent = try {
            MediaStore.createDeleteRequest(context.contentResolver, uris)
        } catch (e: RuntimeException) {
            Log.w(TAG, "free up: the delete request for ${uris.size} files could not be made", e)
            return false
        }
        return MediaConsent.ask(intent).also { Log.i(TAG, "free up: consent for ${uris.size} files: $it") }
    }

    /** The system requests' size: 1000, or a debug override (see [requestDelete]). */
    fun requestSize(): Int {
        if (!dev.hfps.hoard.app.BuildConfig.DEBUG) return FreeUpPlan.MAX_DELETE_REQUEST
        val f = File(context.filesDir, "freeup-request-size")
        return f.takeIf { it.isFile }?.let { runCatching { it.readText().trim().toInt() }.getOrNull() }?.coerceIn(1, FreeUpPlan.MAX_DELETE_REQUEST)
            ?: FreeUpPlan.MAX_DELETE_REQUEST
    }

    /** Of [shots]' media files, the [FreeUpPlan.fileKey]s the system really deleted: no MediaStore row and no file left. */
    suspend fun gone(shots: List<FreeShot>): Set<String> = withContext(Dispatchers.IO) {
        val out = HashSet<String>()
        for (s in shots) for (f in s.media) {
            val row = runCatching {
                context.contentResolver.query(uriOf(f.mediaId!!), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 }
            }.getOrNull() ?: false
            if (!row && !File(f.absolute).exists()) out += FreeUpPlan.fileKey(f)
        }
        out
    }

    /** A sidecar or a listed RAW of a shot whose media went: deleted with All files access; true when it is gone. */
    fun deleteOther(f: BackupFileEntity): Boolean {
        val file = File(f.absolute)
        if (!file.exists()) return true
        if (!PhoneBackup.allFilesAccess(context)) return false
        return file.delete() && !file.exists()
    }

    /** After a deletion: the index drops the rows, the Gallery's items learn `gone: freed`. */
    fun afterDeletion() {
        gallery.deviceIndex.rescanSoon()
        GallerySyncWorker.soon(context)
    }

    companion object {
        const val TAG = "HoardFreeUp"
        const val KEY_KEEP_RECENT = "freeUpKeepRecent"
        const val KEY_KEEP_FAVOURITES = "freeUpKeepFavourites"
        private const val ON = "on"
        private const val OFF = "off"

        /** Android 11 and later only (`createDeleteRequest`); older phones keep the backup without free up. */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= 30
    }
}
