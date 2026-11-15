package dev.hfps.hoard.server.paths

import dev.hfps.hoard.image.format.ImageFormats
import dev.hfps.hoard.server.api.FileLocation
import dev.hfps.hoard.server.api.Item

/*
 * SMB folders on the server (ADR 0022): a prefix of an SMB share (machine + share + directory) is the same tree as a
 * prefix of one of the server's NAS folders (folder id + relative directory). Pure and JVM-tested (`PathMappingTest`).
 */

/** Paths inside a share or a NAS folder: `/`-separated, no leading or trailing `/`, `""` = the root. */
object SharePaths {
    fun normalize(path: String): String =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    fun segments(path: String): List<String> = normalize(path).split('/').filter { it.isNotEmpty() }

    fun join(a: String, b: String): String = normalize(if (a.isEmpty()) b else if (b.isEmpty()) a else "$a/$b")

    fun parent(path: String): String = normalize(path).substringBeforeLast('/', "")

    fun name(path: String): String = normalize(path).substringAfterLast('/')

    /**
     * Whether [path] is [prefix] or inside it, segment by segment, ignoring case (SMB names are case-insensitive), and
     * the rest after it (`null` when not under it).
     */
    fun rest(path: String, prefix: String): String? {
        val p = segments(path)
        val q = segments(prefix)
        if (q.size > p.size) return null
        for (i in q.indices) if (!p[i].equals(q[i], ignoreCase = true)) return null
        return p.drop(q.size).joinToString("/")
    }
}

/**
 * One learned mapping: `smbPrefix` of `share` on `machineId` is `serverPrefix` of the NAS folder `folderId` of the
 * paired server `serverId`. [smbGuid] is the SMB server's GUID when it was learned (a machine that answers with another
 * GUID is another server: the mapping no longer holds).
 */
data class PathMapping(
    val machineId: String,
    val share: String,
    val smbPrefix: String,
    val serverId: String,
    val folderId: String,
    val folderName: String?,
    val serverPrefix: String,
    val smbGuid: String?,
    val learnedAt: Long,
    val verifiedAt: Long,
) {
    /** The NAS path (relative to the folder) of a share path, or null when the path is not under [smbPrefix]. */
    fun serverPathOf(smbPath: String): String? = SharePaths.rest(smbPath, smbPrefix)?.let { SharePaths.join(serverPrefix, it) }

    fun covers(smbPath: String): Boolean = SharePaths.rest(smbPath, smbPrefix) != null

    fun sameShare(machineId: String, share: String): Boolean = this.machineId == machineId && this.share.equals(share, ignoreCase = true)
}

/** The prefixes two paths of the same file give: everything before their longest common tail of segments. */
data class DerivedPrefix(val smbPrefix: String, val folderId: String, val folderName: String?, val serverPrefix: String)

object MappingDiscovery {
    /** How many files are fingerprinted to learn a mapping: one to find it, one to check it. */
    const val SAMPLE_FILES = 2

    /** Files whose size is below this are not good samples when bigger ones exist (every v1 read is 2 MiB anyway). */
    const val MIN_SAMPLE_BYTES = 64 * 1024L

    /** Video names the server indexes (ADR 0014 "Pairing rule"). */
    private val VIDEOS = setOf("mp4", "m4v", "mov", "3gp", "3g2", "mts", "m2ts", "mkv", "webm", "avi")

    /** Whether the server makes items of this file name: photos and videos (sidecars are members, never matched here). */
    fun indexed(name: String): Boolean =
        !name.startsWith(".") && (ImageFormats.isPhoto(name) || name.substringAfterLast('.', "").lowercase() in VIDEOS)

    /** A file of the open SMB folder: its path in the share, size and mtime (Unix ms). */
    data class SmbFile(val path: String, val size: Long, val modifiedAt: Long) {
        val name: String get() = SharePaths.name(path)
    }

    /**
     * The two photos to fingerprint (the first finds the mapping, the second checks it), from the folder's files:
     * photos only (`ImageFormats`), not hidden, not empty, preferring files of at least [MIN_SAMPLE_BYTES]; the first
     * and the last by name, so two different shots. Fewer than two photos: nothing (a mapping is never trusted on one
     * file).
     */
    fun samples(files: List<SmbFile>): List<SmbFile> {
        val photos = files.filter { it.size > 0 && ImageFormats.isPhoto(it.name) }
        val pool = photos.filter { it.size >= MIN_SAMPLE_BYTES }.takeIf { it.size >= SAMPLE_FILES } ?: photos
        if (pool.size < SAMPLE_FILES) return emptyList()
        val sorted = pool.sortedBy { it.name.lowercase() }
        return listOf(sorted.first(), sorted.last())
    }

    /**
     * The mapping one file implies: [smbPath] (in the share) and a NAS [location] of the same bytes. The common tail
     * of segments must include the file name (compared ignoring case, as SMB does); the rest of each path is the
     * prefix. Null when the names differ or the location is not a NAS path.
     */
    fun derive(smbPath: String, location: FileLocation): DerivedPrefix? {
        if (location.source != "nas") return null
        val folderId = location.folderId ?: return null
        val nasPath = location.path ?: return null
        val a = SharePaths.segments(smbPath)
        val b = SharePaths.segments(nasPath)
        if (a.isEmpty() || b.isEmpty()) return null
        var common = 0
        while (common < a.size && common < b.size && a[a.size - 1 - common].equals(b[b.size - 1 - common], ignoreCase = true)) common++
        if (common == 0) return null
        return DerivedPrefix(
            smbPrefix = a.dropLast(common).joinToString("/"),
            folderId = folderId,
            folderName = location.folderName,
            serverPrefix = b.dropLast(common).joinToString("/"),
        )
    }

    /**
     * Every mapping the lookup answer allows for [smbPath] whose bytes have [fingerprint]: one per NAS location of
     * that file in the items found (copies in two folders give two), the most specific first (the longest common
     * tail: the shortest prefixes).
     */
    fun candidates(smbPath: String, fingerprint: String, items: List<Item>): List<DerivedPrefix> =
        items.asSequence()
            .flatMap { it.files.asSequence() }
            .filter { it.fingerprint == fingerprint }
            .flatMap { it.locations.asSequence() }
            .mapNotNull { derive(smbPath, it) }
            .distinct()
            .sortedBy { SharePaths.segments(it.smbPrefix).size + SharePaths.segments(it.serverPrefix).size }
            .toList()

    /**
     * The mapping both samples agree on: the first file's candidates, kept when the second file's candidates hold the
     * same folder and prefixes. Null when they disagree (or either is not on the server).
     */
    fun agree(first: List<DerivedPrefix>, second: List<DerivedPrefix>): DerivedPrefix? =
        first.firstOrNull { a -> second.any { b -> b.folderId == a.folderId && b.serverPrefix == a.serverPrefix && b.smbPrefix.equals(a.smbPrefix, ignoreCase = true) } }

    /**
     * A file looked up on demand (hand-off) under [mapping]: consistent when the item has a NAS location in the
     * mapping's folder under its server prefix. An item found only elsewhere means the mapping is wrong (the share
     * prefix is not that folder any more): it is forgotten and learned again.
     */
    fun consistent(mapping: PathMapping, fingerprint: String, item: Item): Boolean =
        item.files.filter { it.fingerprint == fingerprint }.flatMap { it.locations }.any { loc ->
            loc.source == "nas" && loc.folderId == mapping.folderId && loc.path?.let { SharePaths.rest(it, mapping.serverPrefix) } != null
        }

    /** The most specific mapping of [machineId] / [share] that covers [path] (the longest SMB prefix). */
    fun best(mappings: List<PathMapping>, machineId: String, share: String, path: String): PathMapping? =
        mappings.filter { it.sameShare(machineId, share) && it.covers(path) }.maxByOrNull { SharePaths.segments(it.smbPrefix).size }

    /**
     * Whether a stored mapping still holds: the same paired server, and the SMB server answering with the GUID it had
     * (an unknown GUID on either side is not a change).
     */
    fun valid(mapping: PathMapping, serverId: String?, smbGuid: String?): Boolean =
        mapping.serverId == serverId && (mapping.smbGuid == null || smbGuid == null || mapping.smbGuid.equals(smbGuid, ignoreCase = true))
}
