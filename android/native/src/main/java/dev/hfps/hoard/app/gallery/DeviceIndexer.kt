package dev.hfps.hoard.app.gallery

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import dev.hfps.hoard.server.Fingerprint
import dev.hfps.hoard.server.db.DeviceDao
import dev.hfps.hoard.server.db.DeviceFolderEntity
import dev.hfps.hoard.server.db.DeviceMediaEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** Which phone folders the Gallery shows (pure, tested): the defaults plus the folders the owner added. */
object DeviceFolders {
    /** Relative to a storage root (`/storage/emulated/0`, an SD card). */
    val DEFAULTS = listOf(
        "DCIM/",
        "Pictures/",
        "Movies/",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/",
        "WhatsApp/Media/WhatsApp Images/",
        "WhatsApp/Media/WhatsApp Video/",
    )

    /**
     * Hoard's own copies for Reveal (ADR 0010 amendment "2.2.0: local copies"), never shown as phone photos, even under
     * a default or added folder.
     */
    val EXCLUDED = listOf("Pictures/Hoard/", "Download/Hoard/")

    /** Where a phone path lives in Hoard's locations (ADR 0005): `device` + path, or `vol-<uuid>` + path. */
    fun locate(path: String, primaryRoot: String): Pair<String, String>? {
        val root = primaryRoot.trimEnd('/') + "/"
        if (path.startsWith(root)) return "device" to path.removePrefix(root)
        val m = Regex("^/storage/([0-9A-Za-z][0-9A-Za-z-]{0,63})/(.+)$").find(path) ?: return null
        val uuid = m.groupValues[1]
        if (uuid == "emulated" || uuid == "self") return null
        return "vol-$uuid" to m.groupValues[2]
    }

    /** Whether a file (its path inside its location) is shown: under a default folder or one the owner added. */
    fun included(relative: String, absolute: String, added: List<String>): Boolean {
        if (relative.split('/').any { it.startsWith(".") }) return false
        if (EXCLUDED.any { relative.startsWith(it) }) return false
        if (DEFAULTS.any { relative.startsWith(it, ignoreCase = true) }) return true
        return added.any { absolute.startsWith(it.trimEnd('/') + "/") }
    }
}

/** Google / Samsung motion photos (ADR 0014 "Motion photos"): the cheap checks done with the fingerprint read. */
object MotionPhotos {
    fun isMotion(name: String, head: ByteArray, tail: ByteArray): Boolean {
        if (name.contains(".MP.", ignoreCase = true) || name.startsWith("MVIMG_", ignoreCase = true)) return true
        val text = String(head, Charsets.ISO_8859_1)
        if (text.contains("GCamera:MotionPhoto=\"1\"") || text.contains("GCamera:MicroVideo=\"1\"") || text.contains("<GCamera:MotionPhoto>1<")) return true
        // Samsung SEF trailer: the file ends with "SEFT".
        return tail.size >= 4 && String(tail, tail.size - 4, 4, Charsets.ISO_8859_1) == "SEFT"
    }
}

/**
 * The phone's photos and videos (ADR 0021 "Device index"): MediaStore rows of the shown folders into `device_media`,
 * refreshed when MediaStore changes, and the `v1:` fingerprint of each file computed lazily in the background (2 MiB
 * read per file, newest first, one at a time), so a shot already on the server shows once.
 */
class DeviceIndexer(private val context: Context, private val dao: DeviceDao, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var observer: ContentObserver? = null
    private var fingerprintJob: Job? = null
    private var rescanJob: Job? = null

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** Called whenever new fingerprints were stored (the gallery re-matches device shots). */
    var onFingerprints: (() -> Unit)? = null

    /** Called when a scan found new, changed or removed media (the phone backup's debounced run, ADR 0024). */
    var onNewMedia: (() -> Unit)? = null

    /** Absolute folders indexed beyond the defaults and the owner's: the phone backup's roots (ADR 0024). */
    var extraFolders: (suspend () -> List<String>)? = null

    fun start() {
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = rescanSoon()
        }.also {
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, it)
            context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, it)
        }
        scope.launch { scan() }
    }

    fun rescanSoon() {
        rescanJob?.cancel()
        rescanJob = scope.launch {
            delay(1500)
            scan()
        }
    }

    suspend fun addFolder(path: String) {
        dao.putFolder(DeviceFolderEntity(path.trimEnd('/'), System.currentTimeMillis()))
        scan()
    }

    suspend fun removeFolder(path: String) {
        dao.deleteFolder(path)
        scan()
    }

    /**
     * Reads MediaStore and brings `device_media` to it; keeps fingerprints of unchanged files. True when MediaStore was
     * read (a listing the phone backup may take as complete).
     */
    suspend fun scan(): Boolean = mutex.withLock {
        if (!GalleryPermissions.canReadMedia(context)) return@withLock false
        _scanning.value = true
        var ok = false
        try {
            withContext(Dispatchers.IO) {
                val added = dao.folders().map { it.path } + (runCatching { extraFolders?.invoke() }.getOrNull().orEmpty())
                val root = Environment.getExternalStorageDirectory().absolutePath
                val existing = dao.all().associateBy { it.id }
                val found = ArrayList<DeviceMediaEntity>()
                query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, video = false, root, added, existing, found)
                query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video = true, root, added, existing, found)
                val changed = found.filter { existing[it.id] != it }
                for (chunk in changed.chunked(500)) dao.put(chunk)
                val keep = found.mapTo(HashSet()) { it.id }
                val gone = existing.keys.filter { it !in keep }
                for (chunk in gone.chunked(500)) dao.delete(chunk)
                Log.i(TAG, "device index: ${found.size} files, ${changed.size} new or changed, ${gone.size} gone")
                ok = true
                if (changed.isNotEmpty() || gone.isNotEmpty()) runCatching { onNewMedia?.invoke() }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "device index: no access to media ($e)")
        } finally {
            _scanning.value = false
        }
        startFingerprints()
        ok
    }

    private fun query(collection: Uri, video: Boolean, root: String, added: List<String>, existing: Map<Long, DeviceMediaEntity>, out: MutableList<DeviceMediaEntity>) {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            "datetaken",
        )
        if (video) projection += MediaStore.Video.VideoColumns.DURATION else projection += MediaStore.Images.ImageColumns.ORIENTATION
        context.contentResolver.query(collection, projection.toTypedArray(), null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val data = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val width = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val taken = c.getColumnIndexOrThrow("datetaken")
            val extra = c.getColumnIndexOrThrow(if (video) MediaStore.Video.VideoColumns.DURATION else MediaStore.Images.ImageColumns.ORIENTATION)
            while (c.moveToNext()) {
                val path = c.getString(data) ?: continue
                val (location, relative) = DeviceFolders.locate(path, root) ?: continue
                if (!DeviceFolders.included(relative, path, added)) continue
                val fileName = c.getString(name) ?: path.substringAfterLast('/')
                val dir = relative.substringBeforeLast('/', "")
                val rowId = (if (video) VIDEO_ID_BASE else 0L) + c.getLong(id)
                val modifiedMs = c.getLong(modified) * 1000
                val takenMs = c.getLong(taken).takeIf { it > 0 } ?: modifiedMs
                val bytes = c.getLong(size)
                val old = existing[rowId]
                val same = old != null && old.path == path && old.size == bytes && old.modified == modifiedMs
                out += DeviceMediaEntity(
                    id = rowId,
                    path = path,
                    locationId = location,
                    dir = dir,
                    name = fileName,
                    shotKey = DeviceShots.shotKey(location, dir, fileName),
                    mime = c.getString(mime),
                    size = bytes,
                    modified = modifiedMs,
                    taken = takenMs,
                    durationMs = if (video) c.getLong(extra).takeIf { it > 0 } else null,
                    width = c.getInt(width).takeIf { it > 0 },
                    height = c.getInt(height).takeIf { it > 0 },
                    orientation = if (video) null else c.getInt(extra),
                    video = video,
                    fingerprint = if (same) old!!.fingerprint else null,
                    fpSize = if (same) old!!.fpSize else null,
                    fpModified = if (same) old!!.fpModified else null,
                    motion = same && old!!.motion,
                    sha256 = if (same) old!!.sha256 else null,
                    shaSize = if (same) old!!.shaSize else null,
                    shaModified = if (same) old!!.shaModified else null,
                )
            }
        }
    }

    /** Fingerprints the files that need one, newest first, in the background (cheap: 2 MiB per file). */
    fun startFingerprints() {
        if (fingerprintJob?.isActive == true) return
        fingerprintJob = scope.launch(Dispatchers.IO) {
            // Never at start-up: the first reads wait until the app has settled.
            if (!startedOnce) delay(START_DELAY_MS)
            startedOnce = true
            var stored = 0
            while (true) {
                val batch = dao.needingFingerprint(BATCH)
                if (batch.isEmpty()) break
                for (row in batch) {
                    val file = File(row.path)
                    val result = runCatching { fingerprintOf(file, row) }.getOrNull()
                    if (result == null) {
                        // Unreadable now (permission, removed): mark it so it is not retried until it changes.
                        dao.setFingerprint(row.id, UNREADABLE, row.size, row.modified, false)
                    } else {
                        // A file of at most 2 MiB gets its SHA-256 from the same read (ADR 0024 "Identity").
                        dao.setIdentity(row.id, result.fingerprint, row.size, row.modified, result.motion, result.sha256)
                        stored++
                    }
                }
                if (stored > 0) onFingerprints?.invoke()
                stored = 0
                delay(50)
            }
        }
    }

    private fun fingerprintOf(file: File, row: DeviceMediaEntity): Identity = identityOf(file, row.name, row.video)

    /** The content URI of a row (thumbnails, playback, sharing). */
    fun uriOf(row: DeviceMediaEntity): Uri = if (row.video) {
        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, row.id - VIDEO_ID_BASE)
    } else {
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, row.id)
    }

    @Volatile private var startedOnce = false

    /** A file's `v1:` fingerprint, its SHA-256 when at most 2 MiB, and whether it is a motion photo. */
    data class Identity(val fingerprint: String, val sha256: String?, val motion: Boolean)

    companion object {
        /** The identity of a phone file (the index's, and the phone backup's when the index has not got to it yet). */
        fun identityOf(file: File, name: String, video: Boolean): Identity {
            val (fp, sha) = Fingerprint.withSha(file)
            val motion = !video && RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(minOf(raf.length(), 128 * 1024L).toInt()).also { raf.readFully(it) }
                val tail = ByteArray(minOf(raf.length(), 16L).toInt()).also { raf.seek(raf.length() - it.size); raf.readFully(it) }
                MotionPhotos.isMotion(name, head, tail)
            }
            return Identity(fp, sha, motion)
        }

        const val TAG = "HoardGallery"
        private const val START_DELAY_MS = 15_000L
        private const val BATCH = 40
        /** Video rows are stored with this offset so image and video ids never collide in one table. */
        const val VIDEO_ID_BASE = 1L shl 40
        /** A fingerprint that never matches (the file could not be read). */
        const val UNREADABLE = "v1:unreadable"
    }
}

/** Media access: all files access (Files tab) or the media permissions. */
object GalleryPermissions {
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun canReadMedia(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        return permissions().all { context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
    }
}
