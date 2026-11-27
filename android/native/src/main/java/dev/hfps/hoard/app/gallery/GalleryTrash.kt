package dev.hfps.hoard.app.gallery

import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.paths.PathMapping
import dev.hfps.hoard.server.paths.SharePaths

/*
 * "Move to trash" from the Gallery (ADR 0023, phase A4), pure and JVM-tested (GalleryTrashTest): which files of the
 * selected shots Hoard can reach, grouped by place and folder for TrashService, and what is left out.
 */

/** Files of one folder of one place to trash: the phone (`device`, `vol-<uuid>`) or a share (machine id + share). */
data class GalleryTrashGroup(val locationId: String, val share: String?, val folder: String, val paths: List<String>)

data class GalleryTrashSelection(
    val groups: List<GalleryTrashGroup>,
    /** Server shots whose NAS folder is not mapped to a share yet ("open the folder in Files once"). */
    val noMapping: Int,
    /** Server shots that exist only as uploads (Reveal's "Delete from server" is for those). */
    val uploadOnly: Int,
    /** Item id → the share paths of its NAS files that are planned: hidden at once when they all went (the local hide). */
    val itemPaths: Map<String, List<String>> = emptyMap(),
    /** Server items the server moves itself (feature `trash-actions`, ADR 0023 Amendment (2.10.0)). */
    val serverItems: List<String> = emptyList(),
    /** Of [serverItems], those whose phone copy is selected too (it goes to the phone's trash). */
    val phoneSelected: Set<String> = emptySet(),
) {
    val files: Int get() = groups.sumOf { it.paths.size }
}

object GalleryTrash {
    /**
     * With a server listing `trash-actions` (ADR 0023 Amendment (2.10.0)): the phone's files go to the phone's trash as
     * before; every server item goes to the server, which moves its NAS files and puts backups and uploads in its own
     * trash (no share, no mapping): nothing is left out here, the server's dry run says what it skips and why.
     */
    fun planWithServer(entries: List<GalleryEntry>): GalleryTrashSelection {
        val groups = LinkedHashMap<Triple<String, String?, String>, LinkedHashSet<String>>()
        val server = LinkedHashSet<String>()
        val phone = HashSet<String>()
        for (entry in entries) {
            entry.device?.let { shot ->
                for (f in shot.files) {
                    val path = SharePaths.join(f.media.dir, f.media.name)
                    groups.getOrPut(Triple(f.media.locationId, null, SharePaths.parent(path))) { LinkedHashSet() } += path
                }
            }
            val id = entry.itemId ?: continue
            server += id
            if (entry.device != null) phone += id
        }
        return GalleryTrashSelection(
            groups.map { (k, paths) -> GalleryTrashGroup(k.first, k.second, k.third, paths.toList()) },
            noMapping = 0,
            uploadOnly = 0,
            serverItems = server.toList(),
            phoneSelected = phone,
        )
    }

    /**
     * Every copy of [entries] Hoard can reach (ADR 0023 "Screens › Gallery"): a shot on the phone moves its phone files,
     * a shot on the server moves its NAS files through the share that a mapping ([mappings]) says holds them; a shot on
     * both moves both. A server shot with no mapping for any of its NAS files is left out and counted ([noMapping]); one
     * with no NAS file at all (upload only) too ([uploadOnly]). Files already in a trash are skipped.
     */
    fun plan(entries: List<GalleryEntry>, itemOf: (String) -> Item?, mappings: List<PathMapping>): GalleryTrashSelection {
        val groups = LinkedHashMap<Triple<String, String?, String>, LinkedHashSet<String>>()
        fun add(locationId: String, share: String?, path: String) {
            groups.getOrPut(Triple(locationId, share, SharePaths.parent(path))) { LinkedHashSet() } += path
        }
        var noMapping = 0
        var uploadOnly = 0
        val itemPaths = LinkedHashMap<String, MutableList<String>>()
        for (entry in entries) {
            entry.device?.let { shot -> for (f in shot.files) add(f.media.locationId, null, SharePaths.join(f.media.dir, f.media.name)) }
            val id = entry.itemId ?: continue
            val item = itemOf(id) ?: continue
            val anyNas = item.files.flatMap { it.locations }.filter { it.source == "nas" }
            if (anyNas.isEmpty()) {
                if (item.files.isNotEmpty()) uploadOnly++
                continue
            }
            // Files already in a trash are skipped (nothing to move).
            val nas = anyNas.filter { it.trash == null && it.folderId != null && it.path != null }
            if (nas.isEmpty()) continue
            var reached = false
            for (loc in nas) {
                val mapping = mappings
                    .filter { it.folderId == loc.folderId && SharePaths.rest(loc.path!!, it.serverPrefix) != null }
                    .maxByOrNull { SharePaths.segments(it.serverPrefix).size } ?: continue
                val smbPath = SharePaths.join(mapping.smbPrefix, SharePaths.rest(loc.path!!, mapping.serverPrefix)!!)
                add(mapping.machineId, mapping.share, smbPath)
                itemPaths.getOrPut(id) { ArrayList() } += smbPath
                reached = true
            }
            if (!reached) noMapping++
        }
        return GalleryTrashSelection(
            groups.map { (k, paths) -> GalleryTrashGroup(k.first, k.second, k.third, paths.toList()) },
            noMapping,
            uploadOnly,
            itemPaths,
        )
    }
}

/**
 * The share and trash root of a NAS file in a trash (a server item trashed by another device, `include=trash`): its
 * `trash` path is `<P>/.hoard-trash/<day>/<R>` (folder-relative), so the root on the server is `P`, and on the share the
 * mapping's `smbPrefix` + the rest of `P` under its `serverPrefix`. Null without a mapping of that folder.
 */
fun trashRootOf(location: dev.hfps.hoard.server.api.FileLocation, mappings: List<PathMapping>): Pair<PathMapping, String>? {
    val trash = location.trash ?: return null
    val folderId = location.folderId ?: return null
    val segments = SharePaths.segments(trash)
    val at = segments.indexOfFirst { it == ".hoard-trash" }
    if (at < 0) return null
    val serverRoot = segments.take(at).joinToString("/")
    val mapping = mappings.filter { it.folderId == folderId && SharePaths.rest(serverRoot, it.serverPrefix) != null }
        .maxByOrNull { SharePaths.segments(it.serverPrefix).size } ?: return null
    return mapping to SharePaths.join(mapping.smbPrefix, SharePaths.rest(serverRoot, mapping.serverPrefix)!!)
}

/** Items whose every planned share file is in [trashedPaths] (ADR 0023: hidden from the grid before the feed says so). */
fun hiddenItems(itemPaths: Map<String, List<String>>, trashedPaths: Set<String>): List<String> =
    itemPaths.filter { (_, paths) -> paths.isNotEmpty() && paths.all { it in trashedPaths } }.keys.toList()

/** The Gallery's selection (long press, a day header, Select all over the filtered list). Keys are entry keys. */
data class GallerySelection(val keys: Set<String> = emptySet()) {
    val active: Boolean get() = keys.isNotEmpty()

    fun toggle(key: String) = GallerySelection(if (key in keys) keys - key else keys + key)

    /** A day header: selects the day's shots, or clears them when all were selected. */
    fun day(dayKeys: List<String>) = GallerySelection(if (dayKeys.isNotEmpty() && keys.containsAll(dayKeys)) keys - dayKeys.toSet() else keys + dayKeys)

    /** Select all = the filtered list shown now (the whole library, a folder search, a date range, Rejected). */
    fun all(visible: List<String>) = GallerySelection(visible.toSet())

    /** Keys no longer shown (a filter changed, a shot went to the trash) leave the selection. */
    fun prune(visible: Collection<String>): GallerySelection {
        val shown = visible.toHashSet()
        return if (keys.all { it in shown }) this else GallerySelection(keys.filterTo(HashSet()) { it in shown })
    }
}
