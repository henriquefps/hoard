package dev.hfps.hoard.app.compress

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import dev.hfps.hoard.app.backup.PhoneBackup
import dev.hfps.hoard.app.gallery.DeviceIndexer
import dev.hfps.hoard.app.gallery.GalleryRepository
import dev.hfps.hoard.app.trash.MediaConsent
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.backup.BackupRunResult
import dev.hfps.hoard.server.compress.CompressEngine
import dev.hfps.hoard.server.compress.CompressProgress
import dev.hfps.hoard.server.compress.CompressRunResult
import dev.hfps.hoard.server.compress.CompressSource
import dev.hfps.hoard.server.compress.CompressWorker
import dev.hfps.hoard.server.compress.MediaLookup
import dev.hfps.hoard.server.compress.PhoneAbility
import dev.hfps.hoard.server.compress.PhoneMedia
import dev.hfps.hoard.server.compress.PhoneWrite
import dev.hfps.hoard.server.db.CompressPhoneEntity
import dev.hfps.hoard.server.sync.GallerySyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * The phone's replace of compressed videos in the app (ADR 0025 "Phone replace flow (Hoard)", phase A2): the
 * [CompressSource] over MediaStore and the phone's files (Android 11+ with All files access), the worker's scheduling,
 * and the screen's consent step. The order and the journal are [CompressEngine]'s.
 */
class PhoneCompress(
    private val context: Context,
    private val gallery: GalleryRepository,
    private val scope: CoroutineScope,
    /** Downloads only on unmetered networks (the backup's "Only on Wi-Fi" rule, on by default). */
    private val unmetered: suspend () -> Boolean = { true },
) : CompressSource {
    private val db get() = gallery.db
    val engine: CompressEngine by lazy { CompressEngine(db, { gallery.sync.api() }, this) }

    /** The journal (Settings › Compress videos' phone section). */
    val rows: Flow<List<CompressPhoneEntity>> get() = db.compress().observePhone()

    val progress: StateFlow<CompressProgress> get() = engine.progress

    /** Videos waiting for the owner's consent (the "ready to replace" banner, ADR 0025 Amendment (2.8.1)). */
    val waitingConsent: Flow<Int> by lazy { rows.map { dev.hfps.hoard.server.compress.PhoneOverview.of(it).waiting.size }.distinctUntilChanged() }

    /** A worker's run (also the screen's [allow]). */
    suspend fun run(stopped: () -> Boolean, foreground: suspend (CompressProgress) -> Unit = {}): CompressRunResult {
        cleanParts()
        onForeground = foreground
        return try {
            engine.run(stopped).also { Log.i(TAG, "replace run: ${it.outcome}, ${it.replaced} replaced, ${it.waitingConsent} waiting for consent") }
        } finally {
            onForeground = null
        }
    }

    @Volatile private var onForeground: (suspend (CompressProgress) -> Unit)? = null

    /** "Allow": the screen is up, so the rows waiting for consent are asked (one system dialog per batch) and written. */
    suspend fun allow(): CompressRunResult = scope.async(Dispatchers.IO) {
        // In the app's scope: leaving the screen does not cut a write short (it would be redone, but need not be).
        engine.run(interactive = true).also {
            if (it.waitingConsent == 0) CompressNotifications.cancelConsent(context)
            scheduleAfter(it)
        }
    }.await()

    /**
     * After a run outside the worker (the consent step): a row that backs off (a report the server did not answer, a
     * write without room) gets the worker's follow-up as a worker run would, instead of waiting for the next sync
     * (ADR 0025 Amendment (2.8.1)).
     */
    internal suspend fun scheduleAfter(result: CompressRunResult) {
        val unmetered = runCatching { unmetered() }.getOrDefault(true)
        val retryAt = result.retryAt
        when {
            retryAt != null -> CompressWorker.later(context, unmetered, ((retryAt - System.currentTimeMillis()) / 60_000).coerceIn(1, 360))
            result.serverPending -> CompressWorker.later(context, unmetered)
        }
    }

    /** "Keep originals": the waiting rows are given up; the server cancels them (the originals stay). */
    suspend fun keepOriginals(ids: Collection<String>) {
        engine.keepOriginals(ids)
        CompressNotifications.cancelConsent(context)
        CompressWorker.now(context)
    }

    /** A run now (a restore asked from the Trash screen, "Replace now"). */
    fun kick() = CompressWorker.now(context)

    /** A phone job was started: a run in a minute (the server encodes meanwhile; it asks again while it still does). */
    fun afterPhoneJob() {
        if (supported()) scope.launch(Dispatchers.IO) { CompressWorker.later(context, unmetered = runCatching { unmetered() }.getOrDefault(true), delayMinutes = 1) }
    }

    /** Settings › Compress videos opened: a run now if this phone has anything waiting (cheap when nothing does). */
    fun onScreen() {
        if (supported()) scope.launch(Dispatchers.IO) { CompressWorker.soon(context, unmetered = runCatching { unmetered() }.getOrDefault(true)) }
    }

    /** After a Gallery sync: a run when the server compresses for this phone (cheap when nothing waits). */
    suspend fun afterSync() {
        if (!supported()) return
        if (db.account().get() == null) return
        val info = gallery.sync.compress.value ?: return
        if (info.targets?.phone?.ok != true && db.compress().unsettledPaths().isEmpty()) return
        CompressWorker.soon(context, unmetered = runCatching { unmetered() }.getOrDefault(true))
    }

    /**
     * After a backup pass that stored new videos (ADR 0025 "Triggering"): when this phone has a folder compressed
     * automatically, the server compresses them now, so a run follows in a few minutes (and every 15 minutes while
     * the server still works on them).
     */
    suspend fun afterBackup(result: BackupRunResult) {
        if (result.videosStored <= 0 || !supported()) return
        val api = gallery.api() ?: return
        val automatic = runCatching {
            withContext(Dispatchers.IO) { api.compressJobs(auto = true, limit = 100).jobs.any { it.target == Compress.TARGET_PHONE && !it.finished } }
        }.getOrDefault(false)
        if (automatic) CompressWorker.soon(context, unmetered = runCatching { unmetered() }.getOrDefault(true), delayMinutes = AFTER_BACKUP_MINUTES)
    }

    /** Part files of rows that are gone (an uninstall removes the folder itself). */
    private suspend fun cleanParts() = withContext(Dispatchers.IO) {
        val known = db.compress().phone().mapNotNull { it.part }.toSet()
        for (dir in partDirs()) dir.listFiles()?.filter { it.isFile && it.absolutePath !in known }?.forEach { it.delete() }
    }

    private fun partDirs(): List<File> = context.getExternalFilesDirs(null).filterNotNull().map { File(it, PART_DIR) }

    // --- CompressSource --------------------------------------------------------------------------------------------

    override fun supported(): Boolean = ability() == PhoneAbility.Ready

    fun ability(): PhoneAbility = when {
        Build.VERSION.SDK_INT < 30 -> PhoneAbility.OldAndroid
        // Never throws (Robolectric's Environment has no volumes): unknown is no access.
        !runCatching { PhoneBackup.allFilesAccess(context) }.getOrDefault(false) -> PhoneAbility.NoAllFiles
        else -> PhoneAbility.Ready
    }

    override fun absolute(volume: String, path: String): String? {
        val root = if (volume == "primary") Environment.getExternalStorageDirectory().absolutePath else "/storage/$volume"
        if (!File(root).isDirectory) return null
        val clean = path.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
        if (clean.isEmpty()) return null
        return "$root/$clean"
    }

    /** `getExternalFilesDir` of the file's own volume (the part and the file on one storage), else the primary one. */
    override fun partFile(row: CompressPhoneEntity): File {
        val dirs = context.getExternalFilesDirs(null).filterNotNull()
        val volumeRoot = row.absolute.split('/').take(if (row.absolute.startsWith("/storage/emulated/")) 4 else 3).joinToString("/")
        val base = dirs.firstOrNull { it.absolutePath.startsWith("$volumeRoot/") } ?: dirs.firstOrNull() ?: File(context.filesDir, "ext")
        return File(File(base, PART_DIR), "${row.id.filter { it.isLetterOrDigit() || it == '-' }}.part")
    }

    /** No row and a failed query are told apart: neither leads to a write, but only a failed query is asked again. */
    override suspend fun media(absolute: String): MediaLookup = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 30) return@withContext MediaLookup.Unreadable
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
        )
        runCatching<MediaLookup> {
            // A null cursor is a provider that did not answer: unreadable, not "no row".
            val cursor = context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), columns, "${MediaStore.MediaColumns.DATA} = ?", arrayOf(absolute), null)
                ?: return@runCatching MediaLookup.Unreadable
            cursor.use { c ->
                if (!c.moveToFirst()) return@use MediaLookup.Missing
                val id = c.getLong(0)
                val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                MediaLookup.Found(PhoneMedia(
                    mediaId = if (video) DeviceIndexer.VIDEO_ID_BASE + id else id,
                    path = c.getString(2) ?: absolute,
                    size = c.getLong(3),
                    modified = c.getLong(4) * 1000,
                    dateTaken = c.getLong(5).takeIf { !c.isNull(5) && it > 0 },
                    durationMs = c.getLong(6).takeIf { !c.isNull(6) && it > 0 },
                    favorite = c.getInt(7) == 1,
                    owner = c.getString(8),
                ))
            }
        }.onFailure { Log.w(TAG, "replace: MediaStore unreadable (${it.javaClass.simpleName})") }.getOrDefault(MediaLookup.Unreadable)
    }

    private fun uriOf(mediaId: Long): Uri = if (mediaId >= DeviceIndexer.VIDEO_ID_BASE) {
        ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), mediaId - DeviceIndexer.VIDEO_ID_BASE)
    } else {
        ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), mediaId)
    }

    override suspend fun compareMedia(part: File, phone: File): String? = withContext(Dispatchers.IO) {
        val a = runCatching { facts(part) }.getOrElse { return@withContext "unreadable part" }
        val b = runCatching { facts(phone) }.getOrElse { return@withContext null } // the phone file unreadable: the check decides
        when {
            a.durationMs == null || b.durationMs == null -> null.takeIf { a.durationMs == b.durationMs } ?: "duration"
            kotlin.math.abs(a.durationMs - b.durationMs) > DURATION_SLACK_MS -> "duration ${a.durationMs} vs ${b.durationMs}"
            a.video != b.video -> "video tracks ${a.video} vs ${b.video}"
            a.audio != b.audio -> "audio tracks ${a.audio} vs ${b.audio}"
            a.width != b.width || a.height != b.height -> "size ${a.width}x${a.height} vs ${b.width}x${b.height}"
            else -> null
        }
    }

    private data class Facts(val durationMs: Long?, val width: Int?, val height: Int?, val video: Int, val audio: Int)

    private fun facts(file: File): Facts {
        val r = MediaMetadataRetriever()
        val (duration, width, height) = try {
            r.setDataSource(file.absolutePath)
            Triple(
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
            )
        } finally {
            runCatching { r.release() }
        }
        val e = MediaExtractor()
        var video = 0
        var audio = 0
        try {
            e.setDataSource(file.absolutePath)
            for (i in 0 until e.trackCount) {
                val mime = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                // Data tracks (DJI telemetry, timecode) are dropped by the recipe: only video and audio count.
                if (mime.startsWith("video/")) video++ else if (mime.startsWith("audio/")) audio++
            }
        } finally {
            runCatching { e.release() }
        }
        return Facts(duration, width, height, video, audio)
    }

    override fun needsConsent(media: PhoneMedia): Boolean =
        media.owner != context.packageName && !(Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(context))

    override suspend fun requestWrite(media: List<PhoneMedia>): Boolean {
        if (Build.VERSION.SDK_INT < 30 || media.isEmpty()) return false
        val intent = try {
            MediaStore.createWriteRequest(context.contentResolver, media.map { uriOf(it.mediaId) })
        } catch (e: RuntimeException) {
            Log.w(TAG, "replace: the write request for ${media.size} files could not be made", e)
            return false
        }
        return MediaConsent.ask(intent)
    }

    @RequiresApi(30)
    override suspend fun write(row: CompressPhoneEntity, media: PhoneMedia, part: File, mtime: Long): PhoneWrite = withContext(Dispatchers.IO) {
        // No IS_PENDING: on an existing row MediaStore moves the file to a `.pending-` name (seen on Android 16), and a
        // process killed before it is cleared would leave the video under that name. The journal and the part make the
        // write redoable instead; other apps may see a half-written file for the seconds the copy takes (Amendment (A2)).
        val target = File(row.absolute)
        FileInputStream(part).use { input ->
            FileOutputStream(target, false).use { out ->
                val buffer = ByteArray(BUFFER)
                var copied = 0L
                var paused = false
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    copied += n
                    if (!paused && copied >= part.length() / 2) {
                        paused = true
                        debugPause(out)
                    }
                }
                out.flush()
                out.fd.sync()
            }
        }
        target.setLastModified(mtime)
        // The row of a rewritten file must not keep the old size (ADR 0024 A2): scanned, the mtime set back, scanned again.
        scan(row.absolute)
        File(row.absolute).setLastModified(mtime)
        scan(row.absolute)
        PhoneWrite(pending = false)
    }

    /** The debug build's kill test: `files/compress-pause-write` holds seconds to wait half-way through a write. */
    private suspend fun debugPause(out: FileOutputStream) {
        if (!dev.hfps.hoard.app.BuildConfig.DEBUG) return
        val f = File(context.filesDir, "compress-pause-write")
        val seconds = f.takeIf { it.isFile }?.let { runCatching { it.readText().trim().toLong() }.getOrNull() } ?: return
        out.flush()
        out.fd.sync()
        Log.i(TAG, "replace: paused half-way through a write for $seconds s (debug)")
        delay(seconds * 1000)
    }

    private suspend fun scan(path: String) {
        withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ -> if (cont.isActive) cont.resume(Unit) }
            }
        }
    }

    override suspend fun setDateTaken(mediaId: Long, dateTaken: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.update(uriOf(mediaId), ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken) }, null, null) > 0 }
            .getOrDefault(false)
    }

    override suspend fun foreground(progress: CompressProgress) {
        onForeground?.invoke(progress)
    }

    override fun consentWaiting(count: Int) {
        CompressNotifications.consent(context, count)
    }

    /**
     * Files were replaced or restored and reported (ADR 0025 Amendment (2.8.1)): the Gallery syncs, so its cached item
     * takes the new fingerprint. The engine calls it once per run that reported something; the sync's own run reports
     * nothing new, so they never loop.
     */
    override fun reported(count: Int) {
        Log.i(TAG, "replace: $count reported, the Gallery syncs")
        GallerySyncWorker.soon(context)
    }

    /** Process start: a journal left mid-way (a kill during a write) is finished by a run soon. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val open = db.compress().phone().any { it.state !in dev.hfps.hoard.server.compress.PhoneState.SETTLED }
                if (open && supported()) CompressWorker.soon(context, unmetered = runCatching { unmetered() }.getOrDefault(true))
            }
        }
    }

    companion object {
        const val TAG = "HoardCompress"
        const val PART_DIR = "compress"
        private const val BUFFER = 1 shl 20
        private const val DURATION_SLACK_MS = 100L
        private const val SCAN_TIMEOUT_MS = 15_000L
        const val AFTER_BACKUP_MINUTES = 10L
    }
}
