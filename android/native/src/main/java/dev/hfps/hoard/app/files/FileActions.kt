package dev.hfps.hoard.app.files

import android.app.Activity
import android.util.Log
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SmbEntry
import dev.hfps.hoard.smb.location.LocalStreamSource
import dev.hfps.hoard.smb.share.RevealHandoff
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.stream.StreamAddress
import dev.hfps.hoard.smb.stream.StreamRequest
import dev.hfps.hoard.smb.stream.SmbSource
import dev.hfps.hoard.smb.transfer.Endpoint
import dev.hfps.hoard.smb.transfer.TransferAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What the browser and the viewers do with a file outside Hoard, over :core:smb exactly as `HoardSmbPlugin` does it:
 * open (cache, stream, web page), share out (`ShareOut`), and "Edit in Reveal" (`RevealHandoff`, ADR 0010). Every
 * failure carries a contract code.
 */
class FileActions(private val container: AppContainer) {
    private val files get() = container.files

    /** The SMB stream request of a file: the machine's own connection with the route in use first. */
    private suspend fun streamRequest(location: BrowseLocation, path: String): StreamRequest {
        val machine = location.machine ?: smbFail(ErrorCode.NOT_FOUND, "Machine not found")
        val route = container.routes.nativeRoute(machine.engineMachine())
        return StreamRequest(
            machineId = machine.machine.id,
            addresses = route.addresses.map { StreamAddress(it.host, it.port ?: 445) },
            expectedServerGuid = route.expectedServerGuid,
            share = location.share.orEmpty(),
            path = path,
        )
    }

    /**
     * A tap outside the built-in viewers, or "Open with…" ([chooser]): web pages are served with their folder,
     * media and big files stream, small files are cached then opened (src/lib/files.ts `openStrategy`).
     */
    suspend fun open(activity: Activity, location: BrowseLocation, entry: SmbEntry, chooser: Boolean) {
        val strategy = openStrategy(entry, withChooser = chooser).let {
            if (it == OpenStrategy.Image || it == OpenStrategy.Text) OpenStrategy.Cache else it
        }
        if (strategy == OpenStrategy.Web) {
            val prepared = withContext(Dispatchers.IO) {
                if (location.isSmb) {
                    container.streamer.prepareWebPage(streamRequest(location, entry.path))
                } else {
                    container.streamer.prepareWebPage(LocalStreamSource(container.appContext, files.localFs(location)), entry.path)
                }
            }
            withContext(Dispatchers.Main) { container.streamer.launchWebPage(activity, prepared, false) }
            return
        }
        if (!location.isSmb) {
            val fs = files.localFs(location)
            val file = withContext(Dispatchers.IO) { fs.file(entry.path) }
            if (file.isDirectory) smbFail(ErrorCode.UNKNOWN, "Cannot open a folder")
            withContext(Dispatchers.Main) { container.opener.view(activity, container.opener.localUri(file), file.name, null, chooser) }
            return
        }
        if (strategy == OpenStrategy.Stream) {
            val request = streamRequest(location, entry.path)
            val prepared = withContext(Dispatchers.IO) { container.streamer.prepareFile(request, null, "content") }
            withContext(Dispatchers.Main) { container.streamer.launchFile(activity, prepared, chooser) }
            return
        }
        val file = fetch(location, entry.path)
        withContext(Dispatchers.Main) { container.opener.view(activity, file, null, chooser) }
    }

    /** An SMB file in the open cache (downloaded once, reused while size and date match). */
    suspend fun fetch(location: BrowseLocation, path: String): File {
        val machine = location.machine ?: smbFail(ErrorCode.NOT_FOUND, "Machine not found")
        return files.onSessionCancellable(machine) { session, cancel -> container.opener.fetch(session, location.share.orEmpty(), path, cancel) }
    }

    /** The system share sheet with [paths] (files only; the caller leaves folders out). */
    suspend fun share(activity: Activity, location: BrowseLocation, paths: List<String>) {
        val shared = withContext(Dispatchers.IO) {
            if (location.isSmb) {
                val request = streamRequest(location, paths.first())
                container.shareOut.smb({
                    SmbSource(request.machineId, request.addresses, request.expectedServerGuid, request.share, container.gateway.credentials::get)
                }, paths)
            } else {
                container.shareOut.local(files.localFs(location), paths)
            }
        }
        withContext(Dispatchers.Main) { container.shareOut.launch(activity, shared) }
    }

    /** Whether Reveal (release, else debug) is installed. */
    fun revealAvailable(): Boolean = runCatching { container.reveal.isAvailable() }.getOrDefault(false)

    /**
     * "Edit in Reveal" (ADR 0010): [paths] in Reveal's order, each with its culling decision (the CULLING extra,
     * ADR 0011) and, when culling writes sidecars, a writable sidecar. Returns how many photos went.
     */
    suspend fun editInReveal(activity: Activity, location: BrowseLocation, folderPath: String, paths: List<String>, title: String?, initialPath: String?): Int {
        if (!revealAvailable()) smbFail(ErrorCode.NO_APP, "Reveal is not installed")
        val settings = container.appSettings
        val current = settings.files.first()
        val decisions = container.database.cull()
            .forFolder(location.id, if (location.isSmb) location.share.orEmpty() else "", folderPath)
            .associateBy { it.shotId }
        val culling = paths.mapNotNull { path ->
            val decision = decisions[photoStem(path.substringAfterLast('/')).lowercase()] ?: return@mapNotNull null
            if (decision.flag == null && decision.rating == 0) return@mapNotNull null
            path to RevealHandoff.Culling(decision.rating, decision.flag ?: "none")
        }.toMap()
        val sidecars = if (current.cullSaveXmp) RevealHandoff.SidecarOptions(current.cullXmpHidden) else null
        val outgoing = withContext(Dispatchers.IO) {
            if (location.isSmb) {
                val request = streamRequest(location, paths.first())
                val guid = request.expectedServerGuid ?: smbFail(ErrorCode.UNKNOWN, "The machine's server GUID is not pinned yet")
                val endpoint = Endpoint.Smb(request.machineId, request.addresses.map { TransferAddress(it.host, it.port) }, guid, request.share)
                container.reveal.smb(endpoint, paths, sidecars, culling)
            } else {
                container.reveal.local(location.localRef!!, files.localFs(location), paths, sidecars, culling)
            }
        }
        Log.i(TAG, "Edit in Reveal: ${outgoing.count { !it.sidecar }} photos, ${outgoing.count { it.sidecar }} sidecars, culling ${culling.size}")
        withContext(Dispatchers.Main) { container.reveal.launch(activity, outgoing, title, initialPath, culling) }
        return outgoing.count { !it.sidecar }
    }

    companion object {
        const val TAG = "HoardFiles"
    }
}
