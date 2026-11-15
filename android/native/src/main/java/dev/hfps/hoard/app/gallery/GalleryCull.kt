package dev.hfps.hoard.app.gallery

import android.graphics.BitmapFactory
import android.os.Build
import android.util.Size
import dev.hfps.hoard.app.cull.AnalysisJson
import dev.hfps.hoard.app.cull.AnalyzeResult
import dev.hfps.hoard.app.cull.CullBackend
import dev.hfps.hoard.app.cull.CullStore
import dev.hfps.hoard.app.cull.Decision
import dev.hfps.hoard.app.cull.OpenedFolder
import dev.hfps.hoard.app.cull.ShotMetadata
import dev.hfps.hoard.app.cull.SidecarWrites
import dev.hfps.hoard.app.cull.Tile
import dev.hfps.hoard.app.cull.awaitIt
import dev.hfps.hoard.data.CullAnalysisEntity
import dev.hfps.hoard.data.CullDao
import dev.hfps.hoard.data.CullDecisionEntity
import dev.hfps.hoard.data.CullSessionEntity
import dev.hfps.hoard.data.CullSidecarEntity
import dev.hfps.hoard.data.legacy.isoNow
import dev.hfps.hoard.image.CullFileRef
import dev.hfps.hoard.image.CullShotRef
import dev.hfps.hoard.image.ImageCore
import dev.hfps.hoard.image.ImageFile
import dev.hfps.hoard.image.ImageFileResult
import dev.hfps.hoard.image.ScreenFile
import dev.hfps.hoard.image.ScreenSource
import dev.hfps.hoard.image.ShotRequest
import dev.hfps.hoard.image.ZoomInfo
import dev.hfps.hoard.image.cache.FileIdentity
import dev.hfps.hoard.image.source.FileSource
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.preview.HttpRangeSource
import dev.hfps.hoard.server.preview.PreviewSize
import dev.hfps.hoard.smb.Cancellation
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.cull.CullFolder
import dev.hfps.hoard.smb.cull.CullService
import dev.hfps.hoard.smb.cull.SidecarScan
import dev.hfps.hoard.smb.cull.SidecarStamp
import dev.hfps.hoard.smb.location.Location
import dev.hfps.hoard.smb.location.VolumeRegistry
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.xmp.XmpFields
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/** A gallery entry as a culling shot (ADR 0021): id = the entry key; phone files by location path, server files named. */
object GalleryShots {
    fun shot(entry: GalleryEntry): CullShotRef {
        val device = entry.device
        if (device != null) {
            val files = device.files.map { f ->
                val m = f.media
                f.role to CullFileRef(m.name, "${m.locationId}:${if (m.dir.isEmpty()) m.name else m.dir + "/" + m.name}", m.size, Instant.ofEpochMilli(m.modified).toString(), m.modified)
            }
            val raw = files.firstOrNull { it.first == DeviceShot.ROLE_RAW }?.second
            val rendered = files.firstOrNull { it.first == DeviceShot.ROLE_RENDERED }?.second ?: files.firstOrNull { it.first == DeviceShot.ROLE_VIDEO }?.second
            return CullShotRef(entry.key, device.stem, raw, rendered ?: if (raw == null) files.first().second else null, files.map { it.second }.filter { it != raw && it != rendered })
        }
        val ref = CullFileRef(entry.name, "server:${entry.itemId}/${entry.name}", 0, entry.mainFingerprint, 0)
        val stem = DeviceShots.stemOf(entry.name)
        return if (entry.hasRaw) CullShotRef(entry.key, stem, ref, null, emptyList()) else CullShotRef(entry.key, stem, null, ref, emptyList())
    }
}

/**
 * Culling on gallery items (ADR 0021) with the phase 2c viewer: phone files through the image core as a folder's
 * (sidecars written next to them when Save ratings is on), server items through their previews (thumb, screen; the
 * edit when shown) and their originals read by range for 100 % and RAW previews. Analysis runs on the phone's file,
 * or on the server's 2560 px screen preview.
 */
class GalleryCullBackend(
    private val context: android.content.Context,
    private val repo: GalleryRepository,
    private val service: CullService,
    private val volumes: VolumeRegistry,
    private val scope: CoroutineScope,
    entries: List<GalleryEntry>,
) : CullBackend {
    private val byKey = entries.associateBy { it.key }
    private val shots = entries.map(GalleryShots::shot)
    private val requests = ConcurrentHashMap<String, ShotRequest>()
    private val folders = ConcurrentHashMap<String, CullFolder>()
    private val items = ConcurrentHashMap<String, Item>()

    /** Shots shown as their original instead of Reveal's edit (the viewer's Edited / Original toggle). */
    val showOriginal: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun imageVariant(shotId: String): String = if (shotId in showOriginal) "o" else byKey[shotId]?.editPrimary ?: ""

    private val core: ImageCore get() = service.core

    private fun entry(id: String) = byKey[id] ?: smbFail(ErrorCode.NOT_FOUND, "No shot $id")

    private fun edited(e: GalleryEntry) = e.edited && e.key !in showOriginal

    override suspend fun open(): OpenedFolder = OpenedFolder(shots, null, core.libRawAvailable)

    /** Cheap, from the entries (thousands of shots open at once); [details] reads EXIF for the shot on screen. */
    override suspend fun metadata(shotIds: List<String>): Map<String, ShotMetadata?> = shotIds.associateWith { id ->
        val e = byKey[id] ?: return@associateWith null
        ShotMetadata(
            captureTime = e.captureLocal ?: Instant.ofEpochMilli(e.time).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString(),
            captureMillis = e.time,
            model = e.camera,
            width = e.width,
            height = e.height,
        )
    }

    /** Keeps a viewed server shot's originals on the phone (on unmetered networks; at 100 % always). */
    private fun keepOriginals(e: GalleryEntry, always: Boolean) {
        if (e.device != null || e.isVideo || (!always && !repo.unmetered())) return
        scope.launch {
            val item = item(e) ?: return@launch
            for (f in item.files.filter { it.role == "raw" || it.role == "rendered" }) {
                if (repo.originals.keep(f.fingerprint, f.size) != null) requests.remove(e.key)
            }
        }
    }

    override suspend fun details(shotId: String): ShotMetadata? = withContext(Dispatchers.IO) {
        val e = byKey[shotId] ?: return@withContext null
        keepOriginals(e, always = false)
        if (e.device != null && !e.isVideo) {
            runCatching { request(e)?.let { r -> ShotMetadata.of(core.inspect(r.raw ?: r.rendered!!).metadata) } }.getOrNull()
                ?.let { return@withContext it.copy(captureMillis = e.time) }
        }
        val row = e.itemId?.let { repo.db.items().get(it) } ?: return@withContext null
        ShotMetadata(captureTime = row.captureLocal, captureMillis = e.time, make = row.make, model = row.model, lens = row.lens, width = row.width, height = row.height)
    }

    override suspend fun readSidecars(seen: Map<String, SidecarStamp?>, refresh: Boolean): SidecarScan = SidecarScan.EMPTY

    /** Sidecars of phone files, in their folders (ADR 0011 rules); server-only shots have none Hoard can write. */
    override suspend fun writeSidecars(items: List<Pair<String, XmpFields>>, hidden: Boolean): SidecarWrites = withContext(Dispatchers.IO) {
        val results = ArrayList<dev.hfps.hoard.smb.cull.SidecarResult>()
        var readOnly = false
        val byFolder = items.mapNotNull { (id, fields) -> byKey[id]?.device?.let { d -> Triple(d, id, fields) } }.groupBy { "${it.first.locationId}/${it.first.dir}" }
        for ((_, group) in byFolder) {
            val shot = group.first().first
            val folder = runCatching { folderOf(shot) }.getOrNull() ?: continue
            val mapped = group.map { (d, _, fields) -> d.stem.lowercase() to fields }
            val written = runCatching { service.sidecars.enqueue(folder, mapped, hidden).awaitIt() }.getOrNull() ?: continue
            val back = group.associate { (d, id, _) -> d.stem.lowercase() to id }
            results += written.map { r -> r.copy(shotId = back[r.shotId] ?: r.shotId) }
            readOnly = readOnly || service.sidecars.isReadOnly(folder.id)
        }
        SidecarWrites(results, readOnly)
    }

    private fun locationOf(locationId: String): Location.Local =
        if (locationId == "device") Location.Device else Location.Volume(locationId.removePrefix("vol-"))

    private fun folderOf(shot: DeviceShot): CullFolder = folders.getOrPut("${shot.locationId}/${shot.dir}") {
        val loc = locationOf(shot.locationId)
        val listing = volumes.fs(loc).list(shot.dir, Cancellation.NONE)
        service.open(loc, shot.dir, listing.entries)
    }

    // --- Images ----------------------------------------------------------------------------------------------------

    /** The image core's request: phone files by path, server originals by range (RAW of a pair, else the still). */
    private suspend fun request(e: GalleryEntry): ShotRequest? {
        requests[e.key]?.let { return it }
        val made = if (e.device != null) {
            val files = e.device.files.filter { !it.media.video }
            if (files.isEmpty()) return null
            fun image(f: DeviceFile) = File(f.media.path).let { file ->
                ImageFile(FileIdentity("device", f.media.path, f.media.size, f.media.modified), f.media.name, file) { FileSource(file) }
            }
            ShotRequest(raw = files.firstOrNull { it.role == DeviceShot.ROLE_RAW }?.let(::image), rendered = files.firstOrNull { it.role == DeviceShot.ROLE_RENDERED }?.let(::image))
        } else {
            val item = item(e) ?: return null
            val api = repo.api() ?: return null
            val serverId = repo.serverId() ?: return null
            // A kept original is read from the phone; else by range from the server.
            fun image(role: String) = item.files.firstOrNull { it.role == role }?.let { f ->
                val kept = repo.originals.cached(f.fingerprint, f.size)
                ImageFile(FileIdentity("server:$serverId", f.fingerprint, f.size, 0), f.name, kept) { kept?.let { FileSource(it) } ?: HttpRangeSource(api, f.fingerprint, f.size) }
            }
            val raw = image("raw")
            val rendered = image("rendered")
            if (raw == null && rendered == null) return null
            ShotRequest(raw = raw, rendered = rendered)
        }
        requests[e.key] = made
        return made
    }

    private suspend fun item(e: GalleryEntry): Item? {
        val id = e.itemId ?: return null
        items[id]?.let { return it }
        val json = repo.db.items().json(id) ?: return null
        return runCatching { ServerJson.decodeFromString(Item.serializer(), json) }.getOrNull()?.also { items[id] = it }
    }

    private suspend fun serverPreview(e: GalleryEntry, size: PreviewSize): File? {
        val edit = if (edited(e)) e.editPrimary else null
        return repo.previews.get(e.itemId ?: return null, size, edit = edit?.let { "primary" }, version = edit ?: "o")
    }

    private fun usesServer(e: GalleryEntry) = e.device == null || edited(e)

    private val serverImages = ServerShotImages(repo.previews, repo.offlineThumbs)

    private fun editOf(e: GalleryEntry) = if (edited(e)) e.editPrimary else null

    /**
     * A server shot's thumbnail (filmstrip, grid of the viewer): what the phone holds first (the cached thumb, the
     * offline thumbnail), else the server; a shot also on the phone falls back to its own file.
     */
    override suspend fun thumbnail(shotId: String, maxSize: Int): File {
        val e = entry(shotId)
        if (usesServer(e)) {
            val itemId = e.itemId
            if (itemId != null) {
                serverImages.cachedThumb(itemId, editOf(e))?.let { return it }
                (serverImages.fetch(itemId, PreviewSize.Thumb, editOf(e)) as? ServerShotImages.Fetched.Ready)?.let { return it.file }
            }
            if (e.device == null) smbFail(ErrorCode.NOT_FOUND, "No preview")
        }
        if (e.isVideo) return videoFrame(e, maxSize)
        val r = request(e) ?: smbFail(ErrorCode.NOT_FOUND, "No image")
        return core.thumbnailFile(r, maxSize, priority = 50).future.awaitIt().file
    }

    /**
     * A server shot's screen image (2.2.2): the fast step is whatever the phone holds (the screen preview, final; else
     * a thumbnail, low resolution) so the viewer never waits on the network for something to show; the final step is
     * the screen preview from the server. Nothing on the phone and no network: [ErrorCode.UNREACHABLE] (the viewer's
     * offline placeholder), never the error of a broken photo.
     */
    private suspend fun serverScreen(e: GalleryEntry, fast: Boolean): ScreenFile? {
        val itemId = e.itemId ?: return null
        val edit = editOf(e)
        if (fast) serverImages.cachedScreen(itemId, edit)?.let { local -> return screenFileOf(local.file, final = !local.lowRes, lowRes = local.lowRes) }
        return when (val got = serverImages.fetch(itemId, PreviewSize.Screen, edit)) {
            is ServerShotImages.Fetched.Ready -> screenFileOf(got.file, final = true, lowRes = false)
            ServerShotImages.Fetched.Offline -> if (e.device == null) smbFail(ErrorCode.UNREACHABLE, "Offline") else null
            ServerShotImages.Fetched.Missing -> if (e.device == null) smbFail(ErrorCode.NOT_FOUND, "No preview") else null
        }
    }

    private fun screenFileOf(file: File, final: Boolean, lowRes: Boolean): ScreenFile {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.absolutePath, it) }
        return ScreenFile(file, bounds.outWidth, bounds.outHeight, ScreenSource.RENDERED, final = final, lowRes = lowRes)
    }

    override suspend fun screen(shotId: String, longSide: Int, fast: Boolean, background: Boolean): ScreenFile {
        val e = entry(shotId)
        // An edited shot also on the phone shows its own file when the edit cannot be had.
        if (usesServer(e)) serverScreen(e, fast)?.let { return it }
        if (e.isVideo) {
            val file = videoFrame(e, longSide)
            return screenFileOf(file, final = true, lowRes = false)
        }
        val r = request(e) ?: smbFail(ErrorCode.NOT_FOUND, "No image")
        return core.screenFile(r, longSide, fast, priority = if (background) 2 else 0).future.awaitIt()
    }

    /** A phone video's frame (MediaStore's thumbnail) as a JPEG file. */
    private suspend fun videoFrame(e: GalleryEntry, px: Int): File = withContext(Dispatchers.IO) {
        val row = e.device!!.files.first().media
        val out = File(File(context.cacheDir, "gallery-frames"), "${row.id}-${row.modified}-$px.jpg")
        if (!out.isFile) {
            out.parentFile?.mkdirs()
            val bitmap = if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(repo.deviceIndex.uriOf(row), Size(px, px), null)
            } else {
                null
            } ?: smbFail(ErrorCode.NOT_FOUND, "No frame")
            out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
        }
        out
    }

    override suspend fun zoomInfo(shotId: String): ZoomInfo {
        val e = entry(shotId)
        if (e.isVideo) smbFail(ErrorCode.UNKNOWN, "Videos have no 100 %")
        keepOriginals(e, always = true)
        val r = request(e) ?: smbFail(ErrorCode.NOT_FOUND, "No image")
        return core.zoomInfo(r).future.awaitIt()
    }

    override suspend fun tile(shotId: String, tile: Tile): ImageFileResult {
        val r = request(entry(shotId)) ?: smbFail(ErrorCode.NOT_FOUND, "No image")
        return core.tileFile(r, tile.x, tile.y, tile.width, tile.height, tile.sample).future.awaitIt()
    }

    /** What the analysis reads: the phone's files, or the server's screen preview (no original download). */
    private suspend fun analysisRequest(e: GalleryEntry): ShotRequest? {
        if (e.isVideo) return null
        if (e.device != null) return request(e)
        val file = repo.previews.get(e.itemId ?: return null, PreviewSize.Screen, version = "o") ?: return null
        return ShotRequest(rendered = ImageFile(FileIdentity("server-preview", e.itemId, file.length(), file.lastModified()), file.name, file) { FileSource(file) })
    }

    override suspend fun analyze(shotIds: List<String>, urgent: Boolean): AnalyzeResult {
        val results = LinkedHashMap<String, dev.hfps.hoard.app.cull.Analysis?>()
        for (id in shotIds) {
            results[id] = runCatching {
                val r = analysisRequest(entry(id)) ?: return@runCatching null
                AnalysisJson.of(core.analyze(r, if (urgent) 30 else ImageCore.ANALYSIS_PRIORITY).future.awaitIt())
            }.getOrNull()
        }
        return AnalyzeResult(results, service.facesAvailable)
    }

    override suspend fun focusScore(shotId: String): Double? {
        val r = analysisRequest(entry(shotId)) ?: return null
        val step = withContext(Dispatchers.IO) { core.screenPlan(r, 1600).firstOrNull() } ?: return null
        val decoded = core.screen(r, 1600, priority = 20, step = step).future.awaitIt()
        return withContext(Dispatchers.Default) { core.sharpness(decoded.bitmap).best }
    }

    override fun prefetch(shotIds: List<String>, current: Int, longSide: Int) {
        scope.launch {
            val list = shotIds.mapNotNull { byKey[it] }
            val local = list.filter { !usesServer(it) && !it.isVideo }
            val reqs = local.mapNotNull { runCatching { request(it) }.getOrNull() }
            if (reqs.isNotEmpty()) runCatching { core.prefetchFiles(reqs, reqs.indexOfFirst { true }.coerceAtLeast(0), longSide, ahead = 2, behind = 1, zoomAhead = 0, fastAhead = 4) }
            // Server screens of the next shots, one after another.
            for (e in list.drop(current).take(3) + list.take(current).takeLast(1)) if (usesServer(e)) runCatching { serverPreview(e, PreviewSize.Screen) }
        }
    }

    override suspend fun delete(path: String) = smbFail(ErrorCode.UNKNOWN, "The Gallery does not delete files")

    override fun flush() = service.sidecars.flushAll()

    override fun close() {
        folders.values.forEach { service.close(it.id) }
        folders.clear()
    }
}

/**
 * Where gallery culling keeps its decisions (ADR 0021): [GalleryRepository.decide] (server culling, or the phone
 * folder's rows); analyses in Hoard 1's cache, phone files under their own location (shared with the Files tab's
 * culling), server items under `server` + server id.
 */
class GalleryCullStore(private val repo: GalleryRepository, private val dao: CullDao, entries: List<GalleryEntry>) : CullStore {
    private val byKey = entries.associateBy { it.key }

    override suspend fun decisions(): List<CullDecisionEntity> {
        val now = isoNow()
        val current = repo.entries.value?.associateBy { it.key } ?: byKey
        return byKey.keys.mapNotNull { key ->
            val e = current[key] ?: return@mapNotNull null
            if (e.decision.unmarked) null else CullDecisionEntity("gallery", "", "", key, e.decision.flag, e.decision.rating, now)
        }
    }

    override suspend fun session(): CullSessionEntity? = null
    override suspend fun touchSession(shotCount: Int?, lastShotId: String?, setLastShot: Boolean) = Unit
    override suspend fun sidecars(): List<CullSidecarEntity> = emptyList()

    override suspend fun saveDecisions(decisions: List<Triple<String, String?, Int>>, decidedAt: Map<String, Long>) {
        val current = repo.entries.value?.associateBy { it.key } ?: byKey
        repo.decide(decisions.mapNotNull { (key, flag, rating) -> (current[key] ?: byKey[key])?.let { it to EntryDecision(rating, flag) } }, times = decidedAt)
    }

    /** Phone shots' sidecar state goes to their folders' rows (the Files tab's culling reads it). */
    override suspend fun saveSidecars(rows: List<CullDao.SidecarUpdate>) {
        for ((shot, group) in rows.mapNotNull { r -> byKey[r.shotId]?.device?.let { it to r } }.groupBy({ it.first }, { it.second })) {
            dao.saveSidecars(shot.locationId, "", shot.dir, group.map { it.copy(shotId = shot.stem.lowercase()) })
        }
    }

    override suspend fun forgetShots(shotIds: List<String>) = Unit

    override suspend fun analyses(): List<CullAnalysisEntity> {
        val out = ArrayList<CullAnalysisEntity>()
        val shots = byKey.values.map { it to GalleryShots.shot(it) }
        val device = shots.filter { it.first.device != null }.mapNotNull { (e, s) -> (s.rendered ?: s.raw)?.let { e to it } }
        for ((loc, group) in device.groupBy { it.second.path.substringBefore(':') }) {
            val byPath = group.associate { it.second.path.substringAfter(':') to it.second.path }
            for (chunk in byPath.keys.chunked(500)) {
                dao.analysesOf(loc, "", chunk).forEach { row -> byPath[row.path]?.let { out += row.copy(path = it) } }
            }
        }
        val serverId = repo.serverId() ?: return out
        val server = shots.filter { it.first.device == null && it.first.itemId != null }
        val byItem = server.associate { it.first.itemId!! to (it.second.rendered ?: it.second.raw)!! }
        for (chunk in byItem.keys.chunked(500)) {
            dao.analysesOf("server", serverId, chunk).forEach { row -> byItem[row.path]?.let { f -> out += row.copy(path = f.path, size = f.size, modifiedAt = f.modifiedAt) } }
        }
        return out
    }

    override suspend fun putAnalyses(rows: List<CullAnalysisEntity>) {
        val serverId = repo.serverId()
        val mapped = rows.mapNotNull { row ->
            val path = row.path
            when {
                path.startsWith("server:") -> serverId?.let { row.copy(locationId = "server", share = it, path = path.removePrefix("server:").substringBefore('/'), folder = "") }
                path.contains(':') -> {
                    val rel = path.substringAfter(':')
                    row.copy(locationId = path.substringBefore(':'), share = "", path = rel, folder = rel.substringBeforeLast('/', ""))
                }
                else -> null
            }
        }
        if (mapped.isNotEmpty()) dao.putAnalyses(mapped)
    }

    override val external: Flow<Map<String, Decision>> get() = repo.decisions
}
