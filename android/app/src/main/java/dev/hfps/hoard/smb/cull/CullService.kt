package dev.hfps.hoard.smb.cull

import android.content.Context
import android.util.Log
import dev.hfps.hoard.image.CullFileRef
import dev.hfps.hoard.image.CullModel
import dev.hfps.hoard.image.CullShotRef
import dev.hfps.hoard.image.ImageCore
import dev.hfps.hoard.image.ImageFile
import dev.hfps.hoard.image.ShotRequest
import dev.hfps.hoard.image.cache.FileIdentity
import dev.hfps.hoard.image.format.PhotoMetadata
import dev.hfps.hoard.image.source.DescriptorSource
import dev.hfps.hoard.image.source.FileSource
import dev.hfps.hoard.image.source.RandomAccessSource
import dev.hfps.hoard.image.source.SmbFileSource
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.smb.SmbEntry
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.SmbSessions
import dev.hfps.hoard.smb.location.Location
import dev.hfps.hoard.smb.location.VolumeRegistry
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.viewer.RemoteFiles
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One folder opened for culling: its shots and where their bytes come from. [location] is
 * refreshed by every call (an SMB session can be replaced by a reconnect between calls).
 */
class CullFolder(
    val id: String,
    val path: String,
    /** Cache identity of the location (`smb:<machine>:<share>`, `device`, `volume:<uuid>`). */
    val sourceId: String,
    val shots: List<CullShotRef>,
    @Volatile var location: Location,
) {
    val byId: Map<String, CullShotRef> = shots.associateBy { it.id }
    val requests = ConcurrentHashMap<String, ShotRequest>()
}

/**
 * Glue between the plugin's culling methods (ADR 0008) and the image core (ADR 0006): opens a
 * folder of any location as shots, and turns a shot id into a [ShotRequest] whose files open
 * through the location (SMB file handles on the current session, plain paths, or SAF
 * descriptors). One [ImageCore] for the app: its worker threads are never interrupted (an
 * interrupted smbj read would close the session for every other call).
 */
class CullService(private val context: Context, private val sessions: SmbSessions, private val volumes: VolumeRegistry) {

    val core: ImageCore by lazy {
        ImageCore(context.applicationContext).also { context.applicationContext.registerComponentCallbacks(it) }
    }

    private val folders = ConcurrentHashMap<String, CullFolder>()

    /** Opens [path] (already listed as [entries]) for culling. */
    fun open(location: Location, path: String, entries: List<SmbEntry>): CullFolder {
        val files = entries.filter { !it.isDirectory }.map { entry ->
            CullFileRef(entry.name, entry.path, entry.size, entry.modifiedAt, IsoTime.parse(entry.modifiedAt) ?: 0L)
        }
        val folder = CullFolder(UUID.randomUUID().toString(), path, sourceIdOf(location), CullModel.shots(files), location)
        folders[folder.id] = folder
        // A few folders at most are culled at once; keep the newest ones.
        if (folders.size > MAX_FOLDERS) folders.keys.filter { it != folder.id }.take(folders.size - MAX_FOLDERS).forEach(::close)
        return folder
    }

    /** The open folder [cullId], now reached through [location]. `NOT_FOUND` when unknown (app restarted). */
    fun folder(cullId: String?, location: Location): CullFolder {
        val folder = cullId?.let { folders[it] } ?: smbFail(ErrorCode.NOT_FOUND, "Culling session expired; open the folder again")
        if (location::class != folder.location::class) smbFail(ErrorCode.UNKNOWN, "location does not match the culling session")
        folder.location = location
        return folder
    }

    fun close(cullId: String) {
        if (folders.remove(cullId) != null && folders.isEmpty()) {
            core.cancelPrefetch()
            core.closeZooms()
        }
    }

    fun shot(folder: CullFolder, shotId: String): CullShotRef =
        folder.byId[shotId] ?: smbFail(ErrorCode.NOT_FOUND, "No shot $shotId in ${folder.path.ifEmpty { "/" }}")

    /** The image core's view of a shot (built once per folder). */
    fun request(folder: CullFolder, shotId: String): ShotRequest = folder.requests.getOrPut(shotId) {
        val shot = shot(folder, shotId)
        ShotRequest(raw = shot.raw?.let { imageFile(folder, it) }, rendered = shot.rendered?.let { imageFile(folder, it) })
    }

    /** EXIF essentials of a shot: the RAW's header when there is one (it has everything), else the JPEG's. */
    fun metadata(folder: CullFolder, shotId: String): PhotoMetadata {
        val request = request(folder, shotId)
        return core.inspect(request.raw ?: request.rendered!!).metadata
    }

    private fun imageFile(folder: CullFolder, file: CullFileRef): ImageFile {
        val identity = FileIdentity(folder.sourceId, file.path, file.size, file.modifiedMillis)
        val local = (folder.location as? Location.Local)?.let { runCatching { volumes.fs(it).pathFile(file.path) }.getOrNull() }
        return ImageFile(identity, file.name, local) { openSource(folder, file) }
    }

    private fun openSource(folder: CullFolder, file: CullFileRef): RandomAccessSource =
        when (val location = folder.location) {
            is Location.Smb -> {
                val session = sessions.get(location.sessionId)
                SmbFileSource(RemoteFiles.openRead(session, location.share, file.path), size = file.size, ownsFile = true)
            }
            is Location.Local -> {
                val fs = volumes.fs(location)
                fs.pathFile(file.path)?.let { FileSource(it) } ?: DescriptorSource(fs.openDescriptor(file.path))
            }
        }

    private fun sourceIdOf(location: Location): String = when (location) {
        is Location.Smb -> "smb:${sessions.get(location.sessionId).machineId}:${location.share.lowercase()}"
        is Location.Local -> volumes.fs(location).cacheId
    }

    companion object {
        const val TAG = "HoardCull"
        private const val MAX_FOLDERS = 4

        fun log(message: String) = Log.i(TAG, message)

        /** Path of a file in [folder] (for tests and logs). */
        fun join(folder: String, name: String) = SmbPaths.join(folder, name)
    }
}
