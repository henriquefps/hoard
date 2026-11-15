package dev.hfps.hoard.server.paths

import dev.hfps.hoard.server.api.Features
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerException
import dev.hfps.hoard.server.db.GalleryDatabase
import dev.hfps.hoard.server.db.SmbMappingEntity
import dev.hfps.hoard.server.db.SmbMappingMissEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** An open SMB folder: machine, share, directory in the share, and the GUID the SMB server answered with (if known). */
data class SmbFolderRef(val machineId: String, val share: String, val path: String, val smbGuid: String?) {
    val key: String get() = "$machineId/${share.lowercase()}/${SharePaths.normalize(path).lowercase()}"
}

/** Reads the v1 fingerprint of one file of the share (2 MiB: the first and the last MiB). */
fun interface FingerprintReader {
    suspend fun fingerprint(path: String): String
}

/**
 * An open folder resolved against the server: the mapping that covers it, what each file is ([matches], by path in
 * the share), the full items the query returned ([items], by id; files found by path only are not in it). [index] and
 * [replacedBy] are what the server answered, kept so that a cached resolution is matched again against the share's
 * current listing (sizes and mtimes) every time it is used.
 */
data class FolderResolution(
    val folder: SmbFolderRef,
    val mapping: PathMapping,
    val matches: Map<String, FileMatch>,
    val items: Map<String, Item>,
    val resolvedAt: Long,
    val index: List<IndexedFile> = emptyList(),
    val replacedBy: Map<String, String> = emptyMap(),
) {
    fun itemOf(path: String): String? = (matches[path] as? FileMatch.Mapped)?.itemId

    /** Matches by path ignoring case (shots name their files as listed, but SMB paths are case-insensitive). */
    fun match(path: String): FileMatch = matches[path] ?: matches.entries.firstOrNull { it.key.equals(path, ignoreCase = true) }?.value ?: FileMatch.Unknown
}

/**
 * SMB folders on the server (ADR 0022): learns the mapping of an opened SMB folder to a NAS folder of the paired
 * server (two fingerprinted files, never more), resolves the folder's files to items (one paged
 * `GET /v1/items?folder=&path=`, `POST /v1/items/by-path` for what the query does not reach), caches each folder for
 * [ttlMs], and looks a single file up on demand ([lookupOne], for a hand-off). Everything here needs the server's
 * `items-by-path` feature; without it (or unpaired) nothing is read and every answer is null.
 */
class SmbServerPaths(
    private val db: GalleryDatabase,
    private val api: suspend () -> ServerApi?,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
) {
    private val cache = ConcurrentHashMap<String, FolderResolution>()
    private val learning = Mutex()

    /**
     * The paired server's id as last read (null: not paired or not read yet). A cached resolution is used only for this
     * server; when it changes (unpair, pairing another server) every cached resolution is dropped.
     */
    @Volatile
    private var serverId: String? = null

    /** Files fingerprinted so far (each is a 2 MiB read of the share): for logs and tests. */
    val reads = AtomicInteger()

    /** Every stored mapping (Settings › Photo server › Mapped folders). */
    fun observe(): Flow<List<PathMapping>> = db.mappings().observe().map { rows -> rows.map(::mappingOf) }

    suspend fun mappings(): List<PathMapping> = db.mappings().all().map(::mappingOf)

    /**
     * The cached resolution of a folder, if still fresh and of the paired server (no I/O). Its matches are those of the
     * listing it was resolved (or last revalidated) with: a hint for the UI only. Anything sent to Reveal goes through
     * [resolve] with the current listing, which matches the cache again against it.
     */
    fun cached(folder: SmbFolderRef): FolderResolution? {
        val current = serverId ?: return null
        return cache[folder.key]?.takeIf { now() - it.resolvedAt <= ttlMs && it.mapping.serverId == current }
    }

    /**
     * The paired server is now [id] (null: unpaired). Another server than before drops every cached resolution: its
     * item ids belong to the old server (the stored mappings go with the pairing, `SyncEngine.clearAll`).
     */
    fun onServer(id: String?) {
        val before = serverId
        serverId = id
        if (before != id) cache.clear()
    }

    /**
     * Hoard changed files under [path] of [share] on [machineId] (a transfer, a rename, a delete): the cached
     * resolutions of that folder and of every folder under it are dropped.
     */
    fun invalidate(machineId: String, share: String, path: String) {
        val prefix = SharePaths.normalize(path)
        cache.entries.removeIf { (_, r) ->
            r.folder.machineId == machineId && r.folder.share.equals(share, ignoreCase = true) && SharePaths.rest(r.folder.path, prefix) != null
        }
    }

    /** Whether the paired server can map paths (paired, and `items-by-path` in its features at the last sync). */
    suspend fun available(): Boolean = db.account().get().also { onServer(it?.serverId) }?.has(Features.ITEMS_BY_PATH) == true

    /**
     * Resolves an open folder: its mapping (learned now if needed and allowed), then its files against the server.
     * [files] are the folder's files (paths in the share). [refresh] (pull to refresh) skips the cache. Null when the
     * folder is not mapped (not paired, an older server, not on the server) or the server cannot be reached.
     */
    suspend fun resolve(folder: SmbFolderRef, listed: List<MappingDiscovery.SmbFile>, reader: FingerprintReader, refresh: Boolean = false): FolderResolution? {
        val account = db.account().get()
        onServer(account?.serverId)
        account ?: return null
        val files = listed.filter { MappingDiscovery.indexed(it.name) }
        if (!refresh) cached(folder)?.let { return revalidate(it, folder, files) }
        if (files.isEmpty()) return null
        if (!account.has(Features.ITEMS_BY_PATH)) return null
        val api = api() ?: return null
        val mapping = mappingFor(folder, files, reader, account.serverId, api) ?: return null
        return try {
            resolveWith(folder, files, mapping, api)
        } catch (e: FolderGone) {
            // The NAS folder id is gone (removed, or added again with a new id): forget, learn once more.
            forget(mapping)
            val again = mappingFor(folder, files, reader, account.serverId, api) ?: return null
            runCatching { resolveWith(folder, files, again, api) }.getOrNull()
        }
    }

    /**
     * A cached resolution used again: still of the paired server ([cached]) and of the same SMB server, and matched
     * again against the folder's **current** listing [files], so a file overwritten, replaced, renamed or deleted on
     * the share since (by Hoard or another client) is never taken for the item it was: the server's answer is reused,
     * the share's sizes and mtimes are not.
     */
    private fun revalidate(cached: FolderResolution, folder: SmbFolderRef, files: List<MappingDiscovery.SmbFile>): FolderResolution? {
        if (!MappingDiscovery.valid(cached.mapping, serverId, folder.smbGuid)) {
            cache.remove(folder.key)
            return null
        }
        val matches = FolderMatch.match(cached.mapping, files, cached.index, cached.replacedBy)
        return cached.copy(folder = folder, matches = matches).also { cache[folder.key] = it }
    }

    /**
     * The mapping covering [folder]: a stored one that still holds, else learned from two files of [files] (unless
     * this folder found nothing less than a day ago).
     */
    private suspend fun mappingFor(folder: SmbFolderRef, files: List<MappingDiscovery.SmbFile>, reader: FingerprintReader, serverId: String, api: ServerApi): PathMapping? =
        learning.withLock {
            val share = folder.share.lowercase()
            val stored = db.mappings().forShare(folder.machineId, share).map(::mappingOf)
            for (m in stored) if (!MappingDiscovery.valid(m, serverId, folder.smbGuid)) db.mappings().forget(m.machineId, m.share, m.smbPrefix)
            MappingDiscovery.best(stored.filter { MappingDiscovery.valid(it, serverId, folder.smbGuid) }, folder.machineId, share, folder.path)?.let { return@withLock it }
            val path = SharePaths.normalize(folder.path).lowercase()
            val miss = db.mappings().miss(folder.machineId, share, path)
            if (miss != null && miss.serverId == serverId && now() - miss.checkedAt < MISS_RETRY_MS) return@withLock null
            val learned = learn(folder, files, reader, serverId, api)
            if (learned == null) {
                db.mappings().putMiss(SmbMappingMissEntity(folder.machineId, share, path, serverId, now()))
                return@withLock null
            }
            db.mappings().put(entityOf(learned))
            db.mappings().clearMisses(folder.machineId, share)
            learned
        }

    /** Learning (ADR 0022 "Mapping"): fingerprint one photo, look it up, derive; the second photo must agree. */
    private suspend fun learn(folder: SmbFolderRef, files: List<MappingDiscovery.SmbFile>, reader: FingerprintReader, serverId: String, api: ServerApi): PathMapping? =
        withContext(Dispatchers.IO) {
            val samples = MappingDiscovery.samples(files)
            if (samples.size < MappingDiscovery.SAMPLE_FILES) return@withContext null
            val first = samples[0]
            val fp1 = runCatching { reader.fingerprint(first.path) }.getOrNull()?.also { reads.incrementAndGet() } ?: return@withContext null
            val found1 = runCatching { api.lookup(listOf(fp1)) }.getOrNull() ?: return@withContext null
            val c1 = MappingDiscovery.candidates(first.path, fp1, found1.items)
            if (c1.isEmpty()) return@withContext null
            val second = samples[1]
            val fp2 = runCatching { reader.fingerprint(second.path) }.getOrNull()?.also { reads.incrementAndGet() } ?: return@withContext null
            val found2 = runCatching { api.lookup(listOf(fp2)) }.getOrNull() ?: return@withContext null
            val agreed = MappingDiscovery.agree(c1, MappingDiscovery.candidates(second.path, fp2, found2.items)) ?: return@withContext null
            val at = now()
            PathMapping(folder.machineId, folder.share.lowercase(), agreed.smbPrefix, serverId, agreed.folderId, agreed.folderName, agreed.serverPrefix, folder.smbGuid, at, at)
        }

    private class FolderGone : Exception()

    /** The folder's files against the index: the query until every photo is seen (or [MAX_QUERY_PAGES]), by-path for the rest. */
    private suspend fun resolveWith(folder: SmbFolderRef, files: List<MappingDiscovery.SmbFile>, mapping: PathMapping, api: ServerApi): FolderResolution? =
        withContext(Dispatchers.IO) {
            val serverDir = mapping.serverPathOf(folder.path) ?: return@withContext null
            val items = LinkedHashMap<String, Item>()
            val index = ArrayList<IndexedFile>()
            var cursor: String? = null
            var pages = 0
            try {
                while (pages < MAX_QUERY_PAGES) {
                    val page = api.itemsInFolder(mapping.folderId, serverDir, cursor, QUERY_LIMIT)
                    pages++
                    page.items.forEach { items[it.id] = it }
                    index += FolderMatch.indexOf(page.items, mapping.folderId)
                    if (!page.hasMore || page.cursor == null || page.cursor == cursor) break
                    if (FolderMatch.complete(mapping, files, index)) break
                    cursor = page.cursor
                }
                // What the query did not reach (a big sub-tree, or nothing at all: then by-path also tells a gone folder).
                val seen = index.map { it.path.lowercase() }.toHashSet()
                val rest = files.mapNotNull { f -> mapping.serverPathOf(f.path)?.takeIf { it.lowercase() !in seen } }
                if (rest.isNotEmpty() && (index.isEmpty() || pages >= MAX_QUERY_PAGES)) {
                    for (chunk in rest.chunked(BY_PATH_MAX)) {
                        val answer = api.itemsByPath(mapping.folderId, chunk)
                        index += answer.found.map { IndexedFile(SharePaths.normalize(it.path), it.itemId, null, it.role, it.size, it.modifiedAt) }
                    }
                }
            } catch (e: ServerException) {
                if (e.status == 404) throw FolderGone()
                return@withContext null
            } catch (e: java.io.IOException) {
                return@withContext null
            }
            val replaced = db.culling().replaced().associate { it.itemId to it.replacedBy }
            val matches = FolderMatch.match(mapping, files, index, replaced)
            FolderResolution(folder, mapping, matches, items, now(), index, replaced).also { if (mapping.serverId == serverId) cache[folder.key] = it }
        }

    /**
     * A file looked up by its own fingerprint (a hand-off, when the index did not match it): the live item that holds
     * it, or null. When it is found outside the folder's mapping, the mapping is forgotten (learned again next time).
     */
    suspend fun lookupOne(folder: SmbFolderRef, path: String, reader: FingerprintReader): Item? = withContext(Dispatchers.IO) {
        val account = db.account().get()
        onServer(account?.serverId)
        account ?: return@withContext null
        val api = api() ?: return@withContext null
        val fp = runCatching { reader.fingerprint(path) }.getOrNull()?.also { reads.incrementAndGet() } ?: return@withContext null
        val answer = runCatching { api.lookup(listOf(fp)) }.getOrNull() ?: return@withContext null
        val item = answer.items.firstOrNull { i -> i.files.any { it.fingerprint == fp } } ?: return@withContext null
        val mapping = MappingDiscovery.best(mappings(), folder.machineId, folder.share.lowercase(), path)
        if (mapping != null && mapping.serverId == account.serverId && !MappingDiscovery.consistent(mapping, fp, item)) forget(mapping)
        item
    }

    /** Forgets a mapping (Settings, or a mismatch): its folders' cached resolutions and misses go with it. */
    suspend fun forget(mapping: PathMapping) {
        db.mappings().forget(mapping.machineId, mapping.share, mapping.smbPrefix)
        db.mappings().clearMisses(mapping.machineId, mapping.share)
        cache.entries.removeIf { it.value.mapping.machineId == mapping.machineId && it.value.mapping.share == mapping.share && it.value.mapping.smbPrefix == mapping.smbPrefix }
    }

    /** Drops the cached resolutions (a culling decision does not need it; a pairing change does). */
    fun clearCache() = cache.clear()

    companion object {
        /** How long a folder's resolution is used without asking the server again (pull to refresh asks at once). */
        const val TTL_MS = 5 * 60_000L
        /** A folder where learning found nothing is not tried again for a day. */
        const val MISS_RETRY_MS = 24 * 3_600_000L
        const val QUERY_LIMIT = 500
        /** Pages of the folder query before the rest is asked by path (a folder whose sub-tree is the whole library). */
        const val MAX_QUERY_PAGES = 4
        const val BY_PATH_MAX = 1000

        fun mappingOf(e: SmbMappingEntity) = PathMapping(e.machineId, e.share, e.smbPrefix, e.serverId, e.folderId, e.folderName, e.serverPrefix, e.smbGuid, e.learnedAt, e.verifiedAt)

        fun entityOf(m: PathMapping) = SmbMappingEntity(m.machineId, m.share.lowercase(), m.smbPrefix, m.serverId, m.folderId, m.folderName, m.serverPrefix, m.smbGuid, m.learnedAt, m.verifiedAt)
    }
}
