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
import kotlinx.coroutines.withTimeoutOrNull
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
        // ADR 0022: on an SMB share, the shots the paired server holds go to Reveal ≥ 0.9.0 as server items.
        val server = if (location.isSmb) serverShots(location, folderPath, paths, initialPath) else null
        val uriPaths = server?.plan?.uris ?: paths
        val settings = container.appSettings
        val current = settings.files.first()
        val decisions = container.database.cull()
            .forFolder(location.id, if (location.isSmb) location.share.orEmpty() else "", folderPath)
            .associateBy { it.shotId }
        // CULLING_VERSION 2: when each field was decided, and by which server device when Hoard is paired.
        val device = runCatching { container.gallery.db.account().get()?.deviceId }.getOrNull()
        val culling = uriPaths.mapNotNull { path ->
            val decision = decisions[photoStem(path.substringAfterLast('/')).lowercase()] ?: return@mapNotNull null
            if (decision.flag == null && decision.rating == 0) return@mapNotNull null
            val at = runCatching { java.time.Instant.parse(decision.updatedAt).toEpochMilli() }.getOrNull()
            path to RevealHandoff.Culling(decision.rating, decision.flag ?: "none", ratingModifiedAt = at, flagModifiedAt = at, ratingDevice = device, flagDevice = device)
        }.toMap()
        val sidecars = if (current.cullSaveXmp) RevealHandoff.SidecarOptions(current.cullXmpHidden) else null
        val outgoing = if (uriPaths.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            if (location.isSmb) {
                val request = streamRequest(location, uriPaths.first())
                val guid = request.expectedServerGuid ?: smbFail(ErrorCode.UNKNOWN, "The machine's server GUID is not pinned yet")
                val endpoint = Endpoint.Smb(request.machineId, request.addresses.map { TransferAddress(it.host, it.port) }, guid, request.share)
                container.reveal.smb(endpoint, uriPaths, sidecars, culling)
            } else {
                container.reveal.local(location.localRef!!, files.localFs(location), uriPaths, sidecars, culling)
            }
        }
        val items = server?.items
        if (items != null) {
            // Reveal reads the items' culling from the server: push what is pending for them first (best effort).
            val ids = items.items.map { it.itemId }.toSet()
            if (container.gallery.db.culling().pending(10_000).any { it.itemId in ids }) {
                withTimeoutOrNull(dev.hfps.hoard.app.gallery.GalleryReveal.FLUSH_TIMEOUT_MS) { container.gallery.syncNow() }
            }
        }
        Log.i(TAG, "Edit in Reveal: ${outgoing.count { !it.sidecar }} photos, ${outgoing.count { it.sidecar }} sidecars, culling ${culling.size}, server items ${items?.items?.size ?: 0}")
        val initialUri = initialPath?.takeIf { it in uriPaths }
        withContext(Dispatchers.Main) { container.reveal.launchMixed(activity, outgoing, title, initialUri, culling, items) }
        return outgoing.count { !it.sidecar } + (items?.items?.size ?: 0)
    }

    /** The server side of an SMB hand-off: the plan, and the server items (null when every shot goes as URIs). */
    private class ServerSide(val plan: SmbReveal.Plan, val items: RevealHandoff.ServerItems?)

    /**
     * Which shots of an SMB hand-off the paired server holds (ADR 0022 "Edit in Reveal"): the folder's resolution,
     * then an on-demand lookup of the shots the index did not match (at most [SmbReveal.MAX_LOOKUPS]).
     */
    private suspend fun serverShots(location: BrowseLocation, folderPath: String, paths: List<String>, initialPath: String?): ServerSide? {
        val account = container.gallery.db.account().get() ?: return null
        val accepts = container.reveal.acceptsServerReferences()
        if (!accepts) return null
        val links = container.serverLinks
        val resolution = links.resolveFolder(location, folderPath)
        val match: (String) -> dev.hfps.hoard.server.paths.FileMatch = { p -> resolution?.match(p) ?: dev.hfps.hoard.server.paths.FileMatch.Unknown }
        val ordered = revealOrder(paths)
        val allowLookups = links.paths.available() && (resolution != null || SmbReveal.shots(ordered).size == 1)
        var plan = SmbReveal.plan(ordered, match, accepts, paired = true, lookupsAllowed = allowLookups)
        val found = HashMap<String, dev.hfps.hoard.server.api.Item>()
        if (plan.lookups.isNotEmpty()) {
            val ref = links.ref(location, folderPath)
            val looked = HashMap<String, Pair<String, String?>?>()
            for (shot in plan.lookups) {
                val item = ref?.let { r -> runCatching { links.paths.lookupOne(r, shot.main, links.reader(location)) }.getOrNull() }
                looked[shot.key] = item?.let { found[it.id] = it; it.id to it.files.firstOrNull { f -> f.name.equals(shot.main.substringAfterLast('/'), ignoreCase = true) }?.fingerprint }
            }
            Log.i(TAG, "Edit in Reveal: looked up ${plan.lookups.size} shots, ${looked.values.count { it != null }} on the server")
            plan = SmbReveal.plan(ordered, match, accepts, paired = true, lookupsAllowed = false, looked = looked)
        }
        if (plan.server.isEmpty()) return ServerSide(plan, null)
        val references = plan.server.map { s ->
            val item = found[s.itemId] ?: resolution?.items?.get(s.itemId)
                ?: container.gallery.db.items().json(s.itemId)?.let { runCatching { dev.hfps.hoard.server.api.ServerJson.decodeFromString(dev.hfps.hoard.server.api.Item.serializer(), it) }.getOrNull() }
            item?.let(dev.hfps.hoard.app.gallery.GalleryReveal::reference)
                ?: RevealHandoff.ServerItem(s.itemId, s.fingerprint, emptyList(), s.shot.main.substringAfterLast('/'))
        }
        // INITIAL_ITEM: the shot opened first, when it goes by reference (it wins over INITIAL_URI in Reveal).
        val initial = initialPath?.let { p -> plan.server.firstOrNull { s -> s.shot.paths.any { it.equals(p, ignoreCase = true) } }?.itemId }
        return ServerSide(plan, RevealHandoff.ServerItems(account.serverId, references, initial))
    }

    companion object {
        const val TAG = "HoardFiles"
    }
}
