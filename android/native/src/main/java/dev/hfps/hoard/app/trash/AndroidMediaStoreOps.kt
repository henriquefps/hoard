package dev.hfps.hoard.app.trash

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * MediaStore's consent dialogs need an activity: [MainActivity][dev.hfps.hoard.app.MainActivity] serves [launcher]
 * while it exists. A request without one is declined (the trash then refuses, nothing is changed).
 */
object MediaConsent {
    /** Starts the PendingIntent's sender and completes with whether the user approved (RESULT_OK). */
    @Volatile
    var launcher: ((PendingIntent, CompletableDeferred<Boolean>) -> Unit)? = null

    suspend fun ask(intent: PendingIntent): Boolean {
        val launch = launcher ?: return false
        val result = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { launch(intent, result) }
        return result.await()
    }
}

/**
 * [MediaStoreOps] over the phone's primary volume (Android 11+). Paths are relative to `/storage/emulated/0`; rows
 * are found by `RELATIVE_PATH` + `DISPLAY_NAME`, typed by `MEDIA_TYPE` (a consent request takes images, videos and
 * audio by their own collections; sidecars and other files go by `MediaStore.Files`).
 */
@RequiresApi(Build.VERSION_CODES.R)
class AndroidMediaStoreOps(private val context: Context) : MediaStoreOps {
    private val resolver = context.contentResolver
    private val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
    private val files: Uri = MediaStore.Files.getContentUri(volume)
    private val root: File = Environment.getExternalStorageDirectory()

    private val columns = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        MediaStore.MediaColumns.IS_TRASHED,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_EXPIRES,
        MediaStore.MediaColumns.DATA,
    )

    private fun typed(id: Long, mediaType: Int): Uri = when (mediaType) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), id)
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(volume), id)
        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volume), id)
        else -> ContentUris.withAppendedId(files, id)
    }

    private fun query(uri: Uri, selection: String?, args: Array<String>?, includeTrashed: Boolean): MediaRow? {
        val extras = Bundle().apply {
            if (selection != null) {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            }
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (includeTrashed) MediaStore.MATCH_INCLUDE else MediaStore.MATCH_EXCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
        }
        resolver.query(uri, columns, extras, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(0)
            val relative = c.getString(2).orEmpty()
            val name = c.getString(3).orEmpty()
            val trashed = c.getInt(5) == 1
            // A trashed row's name is `.trashed-<expiry>-<name>`: the original path is the index's (kept by the caller).
            val display = if (trashed && name.startsWith(TRASHED_PREFIX_MARK)) originalName(name.removePrefix(TRASHED_PREFIX_MARK)) else name
            val path = (relative + display).trimStart('/')
            return MediaRow(
                uri = typed(id, c.getInt(1)).toString(),
                path = path,
                owned = c.getString(4) == context.packageName,
                trashed = trashed,
                size = c.getLong(6),
                modifiedMillis = c.getLong(7).takeIf { it > 0 }?.times(1000),
                expiresAt = if (c.isNull(8)) null else c.getLong(8) * 1000,
                isMedia = c.getInt(1) in MEDIA_TYPES,
            )
        }
        return null
    }

    override fun find(path: String): MediaRow? {
        val clean = path.trim('/')
        val relative = clean.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val name = clean.substringAfterLast('/')
        return query(
            files,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(relative, name),
            includeTrashed = false,
        )
    }

    override suspend fun scan(path: String): MediaRow? {
        val file = File(root, path.trim('/'))
        if (!file.isFile) return null
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, _ -> if (cont.isActive) cont.resume(Unit) }
        }
        return withContext(Dispatchers.IO) { find(path) }
    }

    /** Read by id from `MediaStore.Files` (a typed collection has no `MEDIA_TYPE` column). */
    override fun get(uri: String): MediaRow? {
        val id = ContentUris.parseId(Uri.parse(uri))
        return query(files, "${MediaStore.MediaColumns._ID} = ?", arrayOf(id.toString()), includeTrashed = true)
    }

    override fun exists(path: String): Boolean = File(root, path.trim('/')).exists()

    override fun updateTrashed(uris: List<String>, trashed: Boolean): Boolean = try {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0) }
        uris.forEach { resolver.update(Uri.parse(it), values, null, null) }
        true
    } catch (e: SecurityException) {
        Log.i(TAG, "direct ${if (trashed) "trash" else "restore"} refused: ${e.javaClass.simpleName}")
        false
    }

    override fun delete(uris: List<String>): Boolean = try {
        uris.forEach { resolver.delete(Uri.parse(it), null, null) }
        true
    } catch (e: SecurityException) {
        Log.i(TAG, "direct delete refused: ${e.javaClass.simpleName}")
        false
    }

    /**
     * Rows Hoard does not own are tried directly first while All files access is granted (checked on the emulator,
     * docs/testing/trash.md), except in a debug build with the file `trash-force-consent` in the app's external files
     * folder (to test the consent dialogs).
     */
    override val tryDirect: Boolean
        get() {
            if (!Environment.isExternalStorageManager()) return false
            val force = dev.hfps.hoard.app.BuildConfig.DEBUG && context.getExternalFilesDir(null)?.let { File(it, "trash-force-consent").exists() } == true
            return !force
        }

    override suspend fun request(kind: MediaRequest, uris: List<String>): Boolean {
        val list = uris.map(Uri::parse)
        val intent = try {
            when (kind) {
                MediaRequest.TRASH -> MediaStore.createTrashRequest(resolver, list, true)
                MediaRequest.RESTORE -> MediaStore.createTrashRequest(resolver, list, false)
                MediaRequest.DELETE -> MediaStore.createDeleteRequest(resolver, list)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "consent $kind ${uris.size} could not be made", e)
            throw e
        }
        return MediaConsent.ask(intent).also { Log.i(TAG, "consent $kind ${uris.size}: $it") }
    }

    companion object {
        private const val TAG = "HoardTrash"
        private const val TRASHED_PREFIX_MARK = ".trashed-"
        private val MEDIA_TYPES = setOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO,
        )

        /** `1700000000-IMG_1.JPG` (after `.trashed-`) → `IMG_1.JPG`. */
        fun originalName(name: String): String = name.substringAfter('-', name)

        /** Media management (Android 12+): MediaStore's requests no longer ask the user. */
        fun canManageMedia(context: Context): Boolean = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(context)
    }
}
