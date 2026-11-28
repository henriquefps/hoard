package dev.hfps.hoard.app.trash

import dev.hfps.hoard.data.TrashEntryEntity
import dev.hfps.hoard.server.paths.PathMapping
import dev.hfps.hoard.server.paths.SharePaths
import dev.hfps.hoard.server.trash.ServerTrashIndex

/**
 * Hoard's own trash rows next to the server's trash (ADR 0023 Amendment (2.10.0)), pure and JVM-tested
 * (`ServerTrashSplitTest`). With a server listing `trash-actions`, a row whose trash root is in a server folder (a NAS
 * folder through a mapping) is a server photo's file: Hoard moved it there over SMB before 2.10.0, and the server lists
 * the same file. Such a shot is shown once, as the server's ([covered]); one the server does not list yet (its walk has
 * not seen it, or the server is not reachable) is shown without actions ([waiting]): restoring or emptying it over
 * SMB is what 2.10.0 stops. Everything else ([local]: the phone, drives, shares outside the server, non-media files) is
 * Hoard's own trash as before.
 */
data class ServerTrashSplit(
    val local: List<TrashEntryEntity>,
    /** Server item id → the rows of shots the server lists (hidden; forgotten when the server restores or deletes it). */
    val covered: Map<String, List<TrashEntryEntity>>,
    val waiting: List<TrashEntryEntity>,
) {
    val coveredRows: List<TrashEntryEntity> get() = covered.values.flatten()

    companion object {
        val NONE = ServerTrashSplit(emptyList(), emptyMap(), emptyList())

        /** Every row is Hoard's own (no server with `trash-actions`). */
        fun allLocal(rows: List<TrashEntryEntity>) = ServerTrashSplit(rows, emptyMap(), emptyList())

        /**
         * [index]: the server's listing (null when it could not be read: every server-folder photo is [waiting]).
         * [serverRoot]: the NAS folder id of a row's trash root, or null when the root is not in a server folder.
         */
        fun of(rows: List<TrashEntryEntity>, index: ServerTrashIndex?, serverRoot: (TrashEntryEntity) -> String?): ServerTrashSplit {
            val local = ArrayList<TrashEntryEntity>()
            val covered = LinkedHashMap<String, MutableList<TrashEntryEntity>>()
            val waiting = ArrayList<TrashEntryEntity>()
            val units = rows.groupBy { listOf(it.locationId, it.share.lowercase(), it.trashRoot.lowercase(), it.dayFolder, it.shotKey) }
            for (unit in units.values) {
                val first = unit.first()
                val folderId = if (first.share.isEmpty()) null else first.serverFolderId ?: serverRoot(first)
                if (folderId == null) {
                    local += unit
                    continue
                }
                // A folder Hoard trashed whole (one row) is covered when the server lists anything at or under it.
                val item = index?.let { ix -> unit.firstNotNullOfOrNull { ix.coverOf(folderId, it.trashedPath) ?: ix.coverUnder(folderId, it.trashedPath) } }
                when {
                    item != null -> covered.getOrPut(item) { ArrayList() } += unit
                    // A folder in a server folder may hold server photos: never Hoard's to restore or delete over SMB
                    // (review of #88: a recursive delete would take every photo inside).
                    unit.any { it.isDirectory || TrashPlanner.isMedia(SharePaths.name(it.originalPath)) } -> waiting += unit
                    else -> local += unit // a plain file or a sidecar alone: the server does not trash those
                }
            }
            return ServerTrashSplit(local, covered, waiting)
        }

        /**
         * The NAS folder of a share trash root through [mappings] (a mapping whose `smbPrefix` is the root, on the same
         * share), or the folder id a trash root row recorded ([recorded], `trash_roots.server_folder_id`).
         */
        fun serverRootOf(row: TrashEntryEntity, mappings: List<PathMapping>, recorded: (TrashPlace, String) -> String?): String? {
            if (row.share.isEmpty()) return null
            recorded(TrashPlace(row.locationId, row.share), row.trashRoot)?.let { return it }
            return mappings.firstOrNull { it.sameShare(row.locationId, row.share) && SharePaths.normalize(it.smbPrefix).equals(SharePaths.normalize(row.trashRoot), ignoreCase = true) }?.folderId
        }
    }
}
