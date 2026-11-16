package dev.hfps.hoard.app.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.util.Log
import dev.hfps.hoard.app.gallery.DeviceFolders
import dev.hfps.hoard.app.gallery.DeviceIndexer
import dev.hfps.hoard.app.gallery.GalleryRepository
import dev.hfps.hoard.data.SettingsStore
import dev.hfps.hoard.server.Fingerprint
import dev.hfps.hoard.server.backup.AddRootResult
import dev.hfps.hoard.server.backup.BackupEngine
import dev.hfps.hoard.server.backup.BackupKind
import dev.hfps.hoard.server.backup.BackupPaths
import dev.hfps.hoard.server.backup.BackupPlan
import dev.hfps.hoard.server.backup.BackupProgress
import dev.hfps.hoard.server.backup.BackupRules
import dev.hfps.hoard.server.backup.BackupRunResult
import dev.hfps.hoard.server.backup.BackupSource
import dev.hfps.hoard.server.backup.BackupWorker
import dev.hfps.hoard.server.backup.Conditions
import dev.hfps.hoard.server.backup.Listing
import dev.hfps.hoard.server.backup.PhoneFile
import dev.hfps.hoard.server.db.BackupRootEntity
import dev.hfps.hoard.server.db.DeviceMediaEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File

/**
 * The phone backup in the app (ADR 0024 "The app", phase A1): the folders the owner turned on, read two ways (the
 * device index's MediaStore rows, and with All files access a listing for RAWs and sidecars MediaStore misses), the
 * rules (Settings › Backup), the phone's conditions, and the worker's scheduling. The runs themselves are
 * [BackupEngine]'s, inside [BackupWorker] only, never at start-up.
 */
class PhoneBackup(
    private val context: Context,
    private val gallery: GalleryRepository,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : BackupSource {
    private val db get() = gallery.db
    val engine: BackupEngine by lazy { BackupEngine(db, { gallery.sync.api() }, this) }

    /** The last scan of the index read MediaStore (a run's listings may count as complete). */
    @Volatile private var indexed = false

    /** Process start: the index includes the roots, new media asks for a run soon, the periodic run is kept. */
    fun start() {
        hook()
        scope.launch(Dispatchers.IO) { runCatching { reschedule() } }
    }

    /** The index includes the roots and tells about new media (also in a process the worker started cold). */
    private fun hook() {
        val index = gallery.deviceIndex
        if (index.extraFolders == null) index.extraFolders = { db.backup().roots().mapNotNull { absoluteFolder(it.locationId, it.folder) } }
        if (index.onNewMedia == null) index.onNewMedia = { scope.launch { runCatching { if (db.backup().roots().isNotEmpty()) BackupWorker.soon(context, rules()) } } }
    }

    // --- Rules (Settings › Backup) ---------------------------------------------------------------------------------

    override suspend fun rules(): BackupRules = BackupRules(
        photosUnmetered = settings.getString(KEY_UNMETERED) != OFF,
        photosCharging = settings.getString(KEY_CHARGING) == ON,
        videosCharging = settings.getString(KEY_VIDEOS_CHARGING) != OFF,
    )

    val observeRules: Flow<BackupRules> = combine(
        settings.observeString(KEY_UNMETERED),
        settings.observeString(KEY_CHARGING),
        settings.observeString(KEY_VIDEOS_CHARGING),
    ) { unmetered, charging, videos -> BackupRules(unmetered != OFF, charging == ON, videos != OFF) }

    suspend fun setRules(rules: BackupRules) {
        settings.putString(KEY_UNMETERED, if (rules.photosUnmetered) ON else OFF)
        settings.putString(KEY_CHARGING, if (rules.photosCharging) ON else OFF)
        settings.putString(KEY_VIDEOS_CHARGING, if (rules.videosCharging) ON else OFF)
        reschedule()
    }

    /** The periodic run while a folder is on (its constraints follow the rules); none otherwise. */
    suspend fun reschedule() {
        if (db.backup().roots().isEmpty() || db.account().get() == null) {
            BackupWorker.cancel(context)
            return
        }
        BackupWorker.schedule(context, rules())
    }

    // --- Folders ---------------------------------------------------------------------------------------------------

    /** Turns a folder on (`device` / `vol-<uuid>` and the folder in it). The first run waits for its constraints. */
    suspend fun add(locationId: String, folder: String): AddRootResult {
        val result = engine.addRoot(locationId, folder)
        if (result is AddRootResult.Added) {
            gallery.deviceIndex.scan()
            reschedule()
            BackupWorker.soon(context, rules())
        }
        return result
    }

    /** Turns a folder off: stopped on the server (its backup stays there), forgotten here. */
    suspend fun remove(rootId: String) {
        engine.removeRoot(rootId)
        reschedule()
    }

    /** "Back up now": at once, within the rules. */
    fun now() = BackupWorker.now(context)

    /** A run (the worker's): the index is read first, so the listings are current. */
    suspend fun run(stopped: () -> Boolean, foreground: suspend (BackupProgress) -> Unit): BackupRunResult {
        hook()
        indexed = gallery.deviceIndex.scan()
        val result = engine.run(stopped, foreground)
        Log.i(TAG, "backup run: ${result.outcome}, ${result.uploaded} uploaded, ${result.bytes} bytes")
        return result
    }

    // --- BackupSource ----------------------------------------------------------------------------------------------

    override fun types(): List<String> = BackupKind.typesFor(allFilesAccess(context))

    override suspend fun list(root: BackupRootEntity, types: Set<String>): Listing = withContext(Dispatchers.IO) {
        val base = absoluteFolder(root.locationId, root.folder) ?: return@withContext Listing(emptyList(), complete = false)
        val volume = volumeRoot(root.locationId) ?: return@withContext Listing(emptyList(), complete = false)
        val mounted = runCatching { Environment.getExternalStorageState(File(volume)) == Environment.MEDIA_MOUNTED }.getOrDefault(false) && File(base).isDirectory
        val rows = db.device().all().filter { it.locationId == root.locationId && BackupPaths.within(root.folder, relative(it)) }
        val files = ArrayList<PhoneFile>()
        val seen = HashSet<String>()
        var unsupported = 0
        for (row in rows) {
            val kind = BackupPlan.kindOf(row.name, mediaStore = true, video = row.video)
            if (kind == null) {
                unsupported++
                continue
            }
            if (kind !in types) continue
            seen += row.path
            files += PhoneFile(
                path = relative(row),
                absolute = row.path,
                mediaId = row.id,
                kind = kind,
                size = row.size,
                modified = row.modified,
                taken = row.taken,
                fingerprint = row.fingerprint?.takeIf { Fingerprint.isValid(it) && row.fpSize == row.size && row.fpModified == row.modified },
                sha256 = row.sha256?.takeIf { row.shaSize == row.size && row.shaModified == row.modified },
            )
        }
        var listed = true
        if ((BackupKind.RAW in types || BackupKind.SIDECAR in types) && allFilesAccess(context) && mounted) {
            // RAWs MediaStore refuses (CR3) and sidecars it never lists, found by listing the folder (paced).
            listed = walk(File(base), volume, root, types, seen, files)
        }
        Listing(files, complete = mounted && indexed && listed, unsupported = unsupported)
    }

    private suspend fun walk(dir: File, volume: String, root: BackupRootEntity, types: Set<String>, seen: Set<String>, out: MutableList<PhoneFile>): Boolean {
        val queue = ArrayDeque<File>().apply { add(dir) }
        var visited = 0
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            val children = d.listFiles() ?: return false
            for (f in children) {
                if (f.name.startsWith(".")) continue
                val rel = f.absolutePath.removePrefix(volume.trimEnd('/') + "/")
                if (DeviceFolders.EXCLUDED.any { "$rel/".startsWith(it) || rel.startsWith(it) }) continue
                if (f.isDirectory) {
                    queue += f
                    continue
                }
                if (f.absolutePath in seen) continue
                val kind = BackupPlan.kindOf(f.name, mediaStore = false, video = false) ?: continue
                if (kind !in types) continue
                val modified = f.lastModified()
                out += PhoneFile(rel, f.absolutePath, null, kind, f.length(), modified, modified)
            }
            if (++visited % 20 == 0) yield()
        }
        return true
    }

    override suspend fun identified(mediaId: Long, fingerprint: String, sha256: String?, size: Long, modified: Long) {
        val row = db.device().get(mediaId) ?: return
        if (row.size != size || row.modified != modified) return
        val motion = !row.video && runCatching { DeviceIndexer.identityOf(File(row.path), row.name, row.video).motion }.getOrDefault(false)
        db.device().setIdentity(mediaId, fingerprint, size, modified, motion, sha256)
    }

    override suspend fun hashed(mediaId: Long, sha256: String, size: Long, modified: Long) {
        val row = db.device().get(mediaId) ?: return
        if (row.size == size && row.modified == modified) db.device().setSha(mediaId, sha256, size, modified)
    }

    override fun conditions(): Conditions = conditionsOf(context)

    companion object {
        const val TAG = "HoardBackup"
        const val KEY_UNMETERED = "backupOnlyUnmetered"
        const val KEY_CHARGING = "backupOnlyCharging"
        const val KEY_VIDEOS_CHARGING = "backupVideosCharging"
        private const val ON = "on"
        private const val OFF = "off"

        fun allFilesAccess(context: Context): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

        /** On an unmetered network now, and charging now. */
        fun conditionsOf(context: Context): Conditions {
            val unmetered = runCatching {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }.getOrDefault(false)
            val charging = runCatching {
                val bm = context.getSystemService(BatteryManager::class.java)
                bm.isCharging || bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_FULL
            }.getOrDefault(false)
            return Conditions(unmetered, charging)
        }

        /** Where a volume is mounted: the primary storage, or `/storage/<UUID>`. */
        fun volumeRoot(locationId: String): String? = when {
            locationId == "device" -> Environment.getExternalStorageDirectory().absolutePath
            locationId.startsWith("vol-") -> "/storage/" + locationId.removePrefix("vol-")
            else -> null
        }

        fun absoluteFolder(locationId: String, folder: String): String? = volumeRoot(locationId)?.let { v ->
            if (folder.isEmpty()) v else v.trimEnd('/') + "/" + folder
        }

        /** A `device_media` row's path relative to its volume (what the server is told). */
        fun relative(row: DeviceMediaEntity): String = if (row.dir.isEmpty()) row.name else "${row.dir}/${row.name}"
    }
}
