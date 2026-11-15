package dev.hfps.hoard.app.files

import android.util.Log
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.image.format.ImageFormats
import dev.hfps.hoard.image.source.SmbFileSource
import dev.hfps.hoard.server.Fingerprint
import dev.hfps.hoard.server.paths.FileMatch
import dev.hfps.hoard.server.paths.FingerprintReader
import dev.hfps.hoard.server.paths.FolderResolution
import dev.hfps.hoard.server.paths.MappingDiscovery
import dev.hfps.hoard.server.paths.SmbFolderRef
import dev.hfps.hoard.server.paths.SmbServerPaths
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.smb.SmbEntry
import dev.hfps.hoard.smb.viewer.RemoteFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * SMB folders on the server (ADR 0022), the app's side: which files of an open SMB folder are items of the paired
 * hoard-server. Files and culling call [resolve] when a folder opens (and on pull to refresh); the mapping is learned
 * there from two files' fingerprints (2 MiB each, read through the route engine's session), never more.
 */
class ServerLinks(private val container: AppContainer) {
    val paths: SmbServerPaths by lazy { SmbServerPaths(container.gallery.db, { container.gallery.api() }) }

    private val _resolved = MutableStateFlow<Map<String, FolderResolution>>(emptyMap())

    /** The last resolution of each folder (by [SmbFolderRef.key]): badges follow it. */
    val resolved: StateFlow<Map<String, FolderResolution>> = _resolved.asStateFlow()

    fun ref(location: BrowseLocation, folderPath: String): SmbFolderRef? {
        if (!location.isSmb) return null
        val machine = location.machine ?: return null
        val share = location.share ?: return null
        return SmbFolderRef(machine.machine.id, share, folderPath, machine.machine.serverGuid)
    }

    /** Reads a share file's v1 fingerprint: the first and the last MiB only. */
    fun reader(location: BrowseLocation): FingerprintReader = FingerprintReader { path ->
        val machine = location.machine ?: error("no machine")
        container.files.onSessionCancellable(machine) { session, _ ->
            SmbFileSource(RemoteFiles.openRead(session, location.share.orEmpty(), path), ownsFile = true).use { Fingerprint.of(it) }
        }
    }

    /** Files of a listing as the mapping sees them (paths in the share, sizes, mtimes in ms). */
    fun filesOf(entries: List<SmbEntry>): List<MappingDiscovery.SmbFile> =
        entries.filter { !it.isDirectory }.map { MappingDiscovery.SmbFile(it.path, it.size, IsoTime.parse(it.modifiedAt) ?: 0L) }

    /**
     * Resolves an open SMB folder (null for other locations, when not paired with a 0.10.0 server, or when the folder
     * is not on the server). Never throws: a failure is "not mapped" for now.
     */
    suspend fun resolve(location: BrowseLocation, folderPath: String, entries: List<SmbEntry>, refresh: Boolean = false): FolderResolution? {
        val ref = ref(location, folderPath) ?: return null
        if (entries.none { !it.isDirectory && ImageFormats.isPhoto(it.name) }) return null
        return try {
            if (!paths.available()) return null
            val before = paths.reads.get()
            val r = paths.resolve(ref, filesOf(entries), reader(location), refresh)
            val read = paths.reads.get() - before
            if (r != null) {
                _resolved.update { it + (ref.key to r) }
                Log.i(TAG, "folder ${ref.key}: ${r.matches.values.count { it is FileMatch.Mapped }} of ${r.matches.size} files on the server (folder ${r.mapping.folderId}, $read files read)")
            } else if (read > 0) {
                Log.i(TAG, "folder ${ref.key}: not on the server ($read files read)")
            }
            r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "resolving ${ref.key} failed", e)
            null
        }
    }

    /** Lists the folder, then [resolve]s it (Edit in Reveal from a selection, when the folder was not resolved yet). */
    suspend fun resolveFolder(location: BrowseLocation, folderPath: String, refresh: Boolean = false): FolderResolution? {
        val ref = ref(location, folderPath) ?: return null
        if (!refresh) paths.cached(ref)?.let { return it }
        val entries = runCatching { container.files.run(location) { fs, cancel -> fs.list(folderPath, cancel) }.entries }.getOrNull() ?: return null
        return resolve(location, folderPath, entries, refresh)
    }

    /** Shot id (lower-case stem) → item id of an open folder's mapped shots (a RAW's item before its JPEG's). */
    fun shotItems(resolution: FolderResolution?): Map<String, String> {
        resolution ?: return emptyMap()
        val out = HashMap<String, Pair<String, Boolean>>()
        for ((path, match) in resolution.matches) {
            if (match !is FileMatch.Mapped) continue
            val stem = photoStem(path.substringAfterLast('/')).lowercase()
            val raw = match.role == "raw"
            val cur = out[stem]
            if (cur == null || (raw && !cur.second)) out[stem] = match.itemId to raw
        }
        return out.mapValues { it.value.first }
    }

    companion object {
        const val TAG = "HoardServerPaths"
    }
}
