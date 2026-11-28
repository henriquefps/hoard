package dev.hfps.hoard.app.trash

import dev.hfps.hoard.server.paths.SharePaths

/**
 * Files' delete and culling's "Move rejects to trash" in a **mapped server folder** (ADR 0023 Amendment (2.10.0)): with
 * a server listing `trash-actions`, the photos go to the trash through the server (`POST /v1/trash/move`), never over
 * SMB. Pure (JVM-tested, `ServerRoutingTest`): which planned units are server photos, which stay SMB moves (non-media
 * files, sidecars alone, folders without photos, places outside every mapping) and which are refused (the server
 * unreachable, a photo it has not indexed yet, part of a photo in Files, a folder of photos).
 */
object ServerRouting {
    /** What the server answered for the planned photos' server paths. */
    sealed interface Lookup {
        /** Server path → item id (paths the server does not know are absent). */
        data class Found(val items: Map<String, String>) : Lookup

        data object Failed : Lookup
    }

    data class Routed(
        val plan: TrashPlan,
        /** NAS folders with photos the server has not indexed yet (a walk is asked for). */
        val unknownFolders: Set<String>,
    )

    /**
     * The server paths to look up for [plan]: each media file of a unit in a mapped folder, and in Files ([wholeShots]
     * false) the folder's other media files of the same stem, to tell whether the unit is a whole server photo.
     * [serverPath] turns a share path of a unit's root into its folder-relative server path (null outside the mapping).
     */
    fun paths(plan: TrashPlan, listing: List<TrashFile>, wholeShots: Boolean, serverPath: (PlannedUnit, String) -> String?): Map<String, List<String>> {
        val out = LinkedHashMap<String, LinkedHashSet<String>>()
        for (pu in plan.units) {
            val folder = pu.serverFolderId ?: continue
            val media = mediaOf(pu.unit)
            if (media.isEmpty()) continue
            val set = out.getOrPut(folder) { LinkedHashSet() }
            media.forEach { f -> serverPath(pu, f.path)?.let { set += it } }
            if (!wholeShots) siblings(pu.unit, listing).forEach { f -> serverPath(pu, f.path)?.let { set += it } }
        }
        return out.mapValues { it.value.toList() }
    }

    /**
     * Splits [plan]: units with photos in a mapped folder become [ServerUnit]s (every media file known to the server);
     * the others stay as they are. [holdsMedia]: a selected folder has photos below it (or could not be walked).
     */
    fun route(
        plan: TrashPlan,
        listing: List<TrashFile>,
        wholeShots: Boolean,
        serverPath: (PlannedUnit, String) -> String?,
        lookup: Lookup,
        holdsMedia: (String) -> Boolean,
    ): Routed {
        val smb = ArrayList<PlannedUnit>()
        val server = ArrayList<ServerUnit>()
        val refused = ArrayList(plan.refused.map { serverOnly(it) })
        val unknown = LinkedHashSet<String>()
        for (pu in plan.units) {
            val folder = pu.serverFolderId
            if (folder == null) {
                smb += pu
                continue
            }
            if (pu.unit.files.any { it.isDirectory && holdsMedia(it.path) }) {
                refused += RefusedUnit(pu.unit, TrashRefusal.FOLDER_ON_SERVER)
                continue
            }
            val media = mediaOf(pu.unit)
            if (media.isEmpty()) {
                smb += pu // a plain file, a sidecar alone, a folder without photos: the server does not trash those
                continue
            }
            val found = (lookup as? Lookup.Found)?.items
            if (found == null) {
                refused += RefusedUnit(pu.unit, TrashRefusal.PHOTO_SERVER_UNREACHABLE)
                continue
            }
            val paths = media.map { serverPath(pu, it.path) }
            if (paths.any { it == null }) {
                refused += RefusedUnit(pu.unit, TrashRefusal.UNKNOWN_PLACE)
                continue
            }
            val ids = paths.map { found[it!!] }
            if (ids.any { it == null }) {
                refused += RefusedUnit(pu.unit, TrashRefusal.NOT_ON_SERVER_YET)
                unknown += folder
                continue
            }
            val items = ids.filterNotNull().distinct()
            if (!wholeShots) {
                val others = siblings(pu.unit, listing).mapNotNull { f -> serverPath(pu, f.path)?.let { found[it] } }
                if (others.any { it in items }) {
                    refused += RefusedUnit(pu.unit, TrashRefusal.PART_OF_SHOT)
                    continue
                }
            }
            server += ServerUnit(pu.unit, items, folder)
        }
        return Routed(plan.copy(units = smb, refused = refused, server = plan.server + server, oldServerWarning = plan.oldServerWarning && smb.any { it.serverFolderId != null }), unknown)
    }

    /**
     * Refusals that would offer a permanent delete over SMB ([OFFERS_PERMANENT]), for a unit of a mapped server folder:
     * anything already in its `.hoard-trash`, and photo units (a file, or a folder that may hold photos), become
     * [TrashRefusal.SERVER_TRASH_ONLY] (no offer: the Trash screen acts through the server). A plain file elsewhere keeps
     * its refusal.
     */
    fun serverOnly(r: RefusedUnit): RefusedUnit {
        if (r.serverFolderId == null || r.reason !in OFFERS_PERMANENT) return r
        val photo = r.reason == TrashRefusal.ALREADY_IN_TRASH || mediaOf(r.unit).isNotEmpty() || r.unit.files.any { it.isDirectory }
        return if (photo) r.copy(reason = TrashRefusal.SERVER_TRASH_ONLY) else r
    }

    /** The refusals for which the dialog offers the permanent delete (ui/trash `PERMANENT_OFFER`). */
    val OFFERS_PERMANENT: Set<TrashRefusal> = setOf(
        TrashRefusal.ROOT_NOT_WRITABLE,
        TrashRefusal.NOT_SAME_DEVICE,
        TrashRefusal.MOVE_UNSUPPORTED,
        TrashRefusal.ALREADY_IN_TRASH,
        TrashRefusal.PHONE_TRASH_UNAVAILABLE,
        TrashRefusal.FOLDER_NOT_TRASHABLE,
    )

    private fun mediaOf(unit: TrashUnit): List<TrashFile> = unit.files.filter { !it.isDirectory && TrashPlanner.isMedia(it.name) }

    /** The folder's other media files of the unit's stem (a Files delete of the JPEG of a RAW + JPEG). */
    private fun siblings(unit: TrashUnit, listing: List<TrashFile>): List<TrashFile> {
        val mine = unit.files.map { it.path.lowercase() }.toSet()
        val stems = mediaOf(unit).map { stemOf(it.name) }.toSet()
        return listing.filter { f ->
            !f.isDirectory && f.path.lowercase() !in mine && TrashPlanner.isMedia(f.name) && stemOf(f.name) in stems &&
                SharePaths.parent(f.path).equals(unit.folder, ignoreCase = true)
        }
    }

    private fun stemOf(name: String): String = name.substringBeforeLast('.').lowercase()

    /** The folder-relative server path of a share [path] under the unit's root through its mapping's prefixes. */
    fun serverPathOf(root: String, serverPrefix: String?, path: String): String? {
        val rest = SharePaths.rest(path, root) ?: return null
        return SharePaths.join(serverPrefix.orEmpty(), rest)
    }
}
