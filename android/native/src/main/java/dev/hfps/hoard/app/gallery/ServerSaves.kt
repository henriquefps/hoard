package dev.hfps.hoard.app.gallery

import dev.hfps.hoard.app.transfers.FolderPlace
import dev.hfps.hoard.app.transfers.TransferService
import dev.hfps.hoard.data.SettingsStore
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerException
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.transfer.Endpoint
import dev.hfps.hoard.smb.transfer.ServerOriginals
import dev.hfps.hoard.smb.transfer.ServerTransferFs
import dev.hfps.hoard.smb.transfer.TransferFs
import kotlinx.coroutines.runBlocking
import java.io.InputStream

/**
 * `GET /v1/originals/{fingerprint}` for the transfer engine ([ServerTransferFs], ADR 0024 A2), over the paired server's
 * client: a `404` is `NOT_FOUND` (the file fails alone), a refused key `AUTH_FAILED` (the job stops); anything else is
 * a network failure the engine retries (a `503`, a dropped connection).
 */
class ApiOriginals(private val api: () -> ServerApi?) : ServerOriginals {
    private fun client(): ServerApi = api() ?: throw HoardSmbException(ErrorCode.AUTH_FAILED, "The photo server is not paired")

    private fun mapped(e: ServerException): Exception = when {
        e.status == 404 -> HoardSmbException(ErrorCode.NOT_FOUND, "No longer on the photo server", e)
        e.status == 401 || e.status == 403 -> HoardSmbException(ErrorCode.AUTH_FAILED, "The photo server refused this device", e)
        else -> e
    }

    override fun size(fingerprint: String): Long? = try {
        client().originalSize(fingerprint)
    } catch (e: ServerException) {
        throw mapped(e)
    }

    override fun open(fingerprint: String, offset: Long): InputStream = try {
        client().openOriginal(fingerprint, offset)
    } catch (e: ServerException) {
        throw mapped(e)
    }
}

/**
 * "Save to this device" (ADR 0024 A2): opens the server endpoint for the transfer engine, and turns Gallery entries
 * into transfers ([SaveToDevice]) started through [TransferService] (progress, retries, Cancel and conflicts are the
 * engine's; ADR 0007).
 */
class ServerSaves(private val gallery: GalleryRepository, private val transfers: () -> TransferService, private val settings: SettingsStore) {
    private val hinted = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Each saved file is given to the device index with the fingerprint it has on the server (the bytes are the
     * server's), so it merges with its item at once instead of showing as a second, phone-only shot until it is read.
     * [root] gives a destination's folder on disk (the phone's storage, a volume), null when not a path.
     */
    fun onProgress(job: dev.hfps.hoard.smb.transfer.TransferJob, root: (Endpoint) -> String?) {
        if (job.sources.none { it.endpoint is Endpoint.Server }) return
        val base = root(job.destination) ?: return
        val done = synchronized(job) { job.items.filter { it.state == dev.hfps.hoard.smb.transfer.TransferStates.COMPLETED && it.kind == dev.hfps.hoard.smb.transfer.ItemKinds.FILE }.map { Triple(it.index, it.destPath, it.sourcePath to it.size) } }
        for ((index, dest, source) in done) {
            if (!hinted.add("${job.transferId}/$index")) continue
            val fingerprint = source.first.substringBefore('/')
            gallery.deviceIndex.know(base.trimEnd('/') + "/" + dest.trim('/'), source.second, fingerprint)
        }
    }

    /** The engine's [Endpoint.Server]: only the paired server; null otherwise (the job then fails as unavailable). */
    fun open(endpoint: Endpoint.Server): TransferFs? = runBlocking {
        val account = gallery.db.account().get() ?: return@runBlocking null
        if (account.serverId != endpoint.serverId) return@runBlocking null
        ServerTransferFs(account.serverName ?: "hoard-server", ApiOriginals { runBlocking { gallery.api() } }) { fingerprint -> runBlocking { modifiedOf(fingerprint) } }
    }

    private suspend fun modifiedOf(fingerprint: String): Long? {
        val itemId = gallery.db.items().filesFor(listOf(fingerprint)).firstOrNull()?.itemId ?: return null
        return item(itemId)?.let { SaveToDevice.modifiedOf(it, fingerprint) }
    }

    suspend fun item(itemId: String): Item? = gallery.db.items().json(itemId)?.let { runCatching { ServerJson.decodeFromString(Item.serializer(), it) }.getOrNull() }

    /** The server items among [entries] (phone shots are on this device already). */
    suspend fun items(entries: List<GalleryEntry>): List<Item> = entries.filter { it.source == Source.Server }.mapNotNull { e -> e.itemId?.let { item(it) } }

    suspend fun chosen(): FolderPlace? = SaveToDevice.chosen(settings.getString(SaveToDevice.KEY_FOLDER))

    suspend fun choose(folder: FolderPlace?, label: String) {
        if (folder == null) settings.putString(SaveToDevice.KEY_FOLDER, "") else settings.putString(SaveToDevice.KEY_FOLDER, SaveToDevice.chosenJson(folder, label))
    }

    /** Starts one transfer per destination folder; returns the number of files. */
    suspend fun save(items: List<Item>, folder: FolderPlace?, conflict: String): Int {
        val serverId = gallery.serverId() ?: throw HoardSmbException(ErrorCode.UNAVAILABLE, "The photo server is not paired")
        if (folder != null && SaveToDevice.refused(folder.path)) throw HoardSmbException(ErrorCode.ACCESS_DENIED, "Hoard's copies for Reveal are not a destination")
        val fallback = gallery.db.account().get()?.serverName
        val requests = SaveToDevice.requests(items, folder, serverId, conflict, fallback)
        for (request in requests) transfers().transfer(request)
        return requests.sumOf { it.paths.size }
    }
}
