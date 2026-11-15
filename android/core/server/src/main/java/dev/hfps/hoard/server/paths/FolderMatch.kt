package dev.hfps.hoard.server.paths

import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.sync.CullingMapping
import kotlin.math.abs

/** What the server's index says about one file of an open SMB folder (ADR 0022 "Resolution"). */
sealed interface FileMatch {
    /** The file is a member of [itemId] (matched by its path, size and mtime: trusted like a fingerprint). */
    data class Mapped(val itemId: String, val fingerprint: String?, val role: String?) : FileMatch

    /**
     * A guess, never trusted alone: the path is indexed but the size or mtime differ (changed since the server's walk),
     * or ([renamed]) the path is not indexed but a vanished indexed file of the same directory and extension has its
     * size and mtime (uncompressed RAWs share sizes, a burst shares seconds: a new frame can look like a deleted one).
     * Looked up by its own fingerprint to know (a hand-off); never a badge, never culling.
     */
    data class Changed(val itemId: String, val fingerprint: String?, val renamed: Boolean = false) : FileMatch

    /** Not in the index (new, not walked yet, or not a member of a live item). */
    data object Unknown : FileMatch
}

/** A file of the NAS folder as the server indexed it: from `GET /v1/items` (with its fingerprint) or by-path. */
data class IndexedFile(val path: String, val itemId: String, val fingerprint: String?, val role: String?, val size: Long, val modifiedAt: Long?)

object FolderMatch {
    /**
     * How far apart the share's mtime and the index's may be and still be the same file: SMB gives 100 ns, the server
     * keeps ms, a FAT-formatted disk under Samba keeps 2 s.
     */
    const val MTIME_TOLERANCE_MS = 2_000L

    /** Every NAS file of [folderId] the items carry (live items: the query answers only those). */
    fun indexOf(items: List<Item>, folderId: String): List<IndexedFile> = items.flatMap { item ->
        item.files.flatMap { f ->
            f.locations.filter { it.source == "nas" && it.folderId == folderId && it.path != null }
                .map { IndexedFile(SharePaths.normalize(it.path!!), item.id, f.fingerprint, f.role, f.size, it.modifiedAt) }
        }
    }

    fun sameFile(size: Long, modifiedAt: Long, indexed: IndexedFile): Boolean =
        size == indexed.size && (indexed.modifiedAt == null || abs(indexed.modifiedAt - modifiedAt) <= MTIME_TOLERANCE_MS)

    /**
     * Matches the files of an open SMB folder ([files], paths in the share) against the index under [mapping]: by
     * path (exact, else ignoring case), then size + mtime. A file whose path is not indexed is a **rename candidate**
     * ([FileMatch.Changed] with `renamed`, to be looked up by fingerprint, never [FileMatch.Mapped]) when exactly one
     * indexed file of the same directory and extension, whose own path is gone from the share, has its size and mtime.
     * Item ids follow [replacedBy] (a JPEG-only item replaced when its RAW appeared, ADR 0014).
     */
    fun match(
        mapping: PathMapping,
        files: List<MappingDiscovery.SmbFile>,
        index: List<IndexedFile>,
        replacedBy: Map<String, String> = emptyMap(),
    ): Map<String, FileMatch> {
        val exact = index.associateBy { it.path }
        val folded = index.groupBy { it.path.lowercase() }
        val serverPaths = files.mapNotNull { f -> mapping.serverPathOf(f.path)?.let { f.path to it } }.toMap()
        val present = serverPaths.values.map { it.lowercase() }.toHashSet()
        val out = LinkedHashMap<String, FileMatch>()
        val claimed = HashSet<String>()
        val unmatched = ArrayList<MappingDiscovery.SmbFile>()
        for (f in files) {
            val serverPath = serverPaths[f.path]
            if (serverPath == null) {
                out[f.path] = FileMatch.Unknown
                continue
            }
            val hit = exact[serverPath] ?: folded[serverPath.lowercase()]?.singleOrNull()
            when {
                hit == null -> unmatched += f
                sameFile(f.size, f.modifiedAt, hit) -> {
                    claimed += hit.path
                    out[f.path] = FileMatch.Mapped(CullingMapping.follow(hit.itemId, replacedBy), hit.fingerprint, hit.role)
                }
                else -> {
                    claimed += hit.path
                    out[f.path] = FileMatch.Changed(CullingMapping.follow(hit.itemId, replacedBy), hit.fingerprint)
                }
            }
        }
        // Renames on the share since the walk: the indexed file's path is gone, a new name has its size and mtime.
        val orphans = index.filter { it.path !in claimed && it.path.lowercase() !in present }.groupBy { SharePaths.parent(it.path).lowercase() }
        for (f in unmatched) {
            val serverPath = serverPaths.getValue(f.path)
            val ext = f.name.substringAfterLast('.', "").lowercase()
            val candidates = orphans[SharePaths.parent(serverPath).lowercase()].orEmpty()
                .filter { it.path !in claimed && SharePaths.name(it.path).substringAfterLast('.', "").lowercase() == ext && sameFile(f.size, f.modifiedAt, it) }
            val one = candidates.singleOrNull()
            if (one != null) {
                claimed += one.path
                out[f.path] = FileMatch.Changed(CullingMapping.follow(one.itemId, replacedBy), one.fingerprint, renamed = true)
            } else {
                out[f.path] = FileMatch.Unknown
            }
        }
        return out
    }

    /** Whether the query has seen every photo of the folder (it can stop paging). */
    fun complete(mapping: PathMapping, files: List<MappingDiscovery.SmbFile>, index: List<IndexedFile>): Boolean {
        val paths = index.map { it.path.lowercase() }.toHashSet()
        return files.all { f -> mapping.serverPathOf(f.path)?.lowercase()?.let { it in paths } ?: true }
    }
}
