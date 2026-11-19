package dev.hfps.hoard.app.trash

import dev.hfps.hoard.data.TrashEntryEntity
import dev.hfps.hoard.server.api.FileLocation
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.paths.PathMapping
import dev.hfps.hoard.server.paths.SharePaths

/**
 * The trash entries an unfinished compression journal holds (ADR 0025 Amendment (S1), "Waiting journals are visible and
 * their trash entries held"): Hoard's Empty, Delete permanently and auto-empty must leave them. [paths] are SMB trash
 * paths (lower-case, per place) of held NAS trash locations seen through a mapping; [serverItems] the server items with
 * any held trash location (NAS or a backup's kept side).
 *
 * [tails] are the held files' paths from their `.hoard-trash` on (`.hoard-trash/<day>/<path>`, lower-case): a share
 * whose mapping Hoard has not learned cannot be matched by its path, so a trash file there with the same day folder and
 * path is held too (keeping a file too many is safe; deleting a held one is not).
 *
 * [unknown]: the held state could not be refreshed from a server that lists `compress`. Then every row of a trash root
 * mapped to a NAS folder ([TrashEntryEntity.serverFolderId]) counts as held: a journal may have started since the last
 * sync, and an entry deleted under it would lose the original.
 */
class HeldTrash(
    paths: Collection<Pair<TrashPlace, String>> = emptyList(),
    val serverItems: Set<String> = emptySet(),
    val unknown: Boolean = false,
    tails: Collection<String> = emptyList(),
) {
    private val byPlace: Map<TrashPlace, Set<String>> = paths.groupBy({ key(it.first) }, { SharePaths.normalize(it.second).lowercase() }).mapValues { it.value.toSet() }
    private val tails: Set<String> = tails.mapNotNull { tailOf(it) }.toSet()

    val empty: Boolean get() = byPlace.isEmpty() && serverItems.isEmpty() && !unknown && tails.isEmpty()

    /** A held file is exactly [path], or (a trashed folder) inside it. */
    fun holds(place: TrashPlace, path: String): Boolean {
        val p = SharePaths.normalize(path).lowercase()
        val held = byPlace[key(place)].orEmpty()
        if (p in held || held.any { SharePaths.rest(it, p) != null }) return true
        val tail = tailOf(p) ?: return false
        return tail in tails || tails.any { SharePaths.rest(it, tail) != null }
    }

    /** Any held file inside [dir] (a day folder that auto-empty would delete whole). */
    fun holdsUnder(place: TrashPlace, dir: String): Boolean {
        val d = SharePaths.normalize(dir).lowercase()
        if (byPlace[key(place)].orEmpty().any { SharePaths.rest(it, d) != null }) return true
        val tail = tailOf(d) ?: return false
        return tails.any { SharePaths.rest(it, tail) != null }
    }

    /** A row Empty and Delete permanently must leave. */
    fun holdsRow(row: TrashEntryEntity): Boolean {
        if (unknown && row.serverFolderId != null) return true
        if (row.serverItemId != null && row.serverItemId in serverItems) return true
        if (row.trashedPath.isEmpty()) return false
        return holds(TrashPlace(row.locationId, row.share), row.trashedPath)
    }

    /** A root auto-empty must not touch at all this run ([unknown] state and a root mapped to a NAS folder). */
    fun holdsRoot(serverFolderId: String?): Boolean = unknown && serverFolderId != null

    companion object {
        val NONE = HeldTrash()

        private fun key(place: TrashPlace) = TrashPlace(place.locationId, place.share.lowercase())

        /** `<…>/.hoard-trash/<day>/<path>` → `.hoard-trash/<day>/<path>` (lower-case); null outside a trash. */
        fun tailOf(path: String): String? {
            val segments = SharePaths.segments(SharePaths.normalize(path).lowercase())
            val at = segments.indexOf(TrashPaths.DIR.lowercase())
            if (at < 0 || at + 1 >= segments.size) return null
            return segments.drop(at).joinToString("/")
        }
    }
}

/**
 * A trashed server item a compression replaced (ADR 0025 "Originals: the trash"): the original of a compressed video,
 * or the output after a restore. Restore undoes the compression (`POST /v1/compress/restore`), Delete permanently and
 * Empty go through `POST /v1/compress/purge`; both take the item's own id.
 */
data class CompressedOriginal(
    val itemId: String,
    val name: String,
    /** The trashed file's size: what emptying it saves. */
    val size: Long,
    /** The live item (the output, or the original after a restore). */
    val compressedTo: String,
    val held: Boolean,
    val since: Long,
    /** Its NAS trash files as SMB paths through a mapping (lower-case), to match the trash rows of a share. */
    val smbPaths: List<Pair<TrashPlace, String>>,
)

object CompressedTrash {
    /** The SMB path of a NAS trash location through [mappings] (null when no mapping covers it). */
    fun smbPathOf(location: FileLocation, mappings: List<PathMapping>): Pair<TrashPlace, String>? {
        val trash = location.trash ?: return null
        val folderId = location.folderId ?: return null
        val mapping = mappings.filter { it.folderId == folderId && SharePaths.rest(trash, it.serverPrefix) != null }
            .maxByOrNull { SharePaths.segments(it.serverPrefix).size } ?: return null
        return TrashPlace(mapping.machineId, mapping.share) to SharePaths.join(mapping.smbPrefix, SharePaths.rest(trash, mapping.serverPrefix)!!)
    }

    /**
     * The held trash of [items] (decoded, `include=trash`): trashed ones, and live ones too (an original hard-linked
     * into the trash while its replace waits is a trash location of a live item).
     */
    fun held(items: List<Item>, mappings: List<PathMapping>, unknown: Boolean = false): HeldTrash {
        val paths = ArrayList<Pair<TrashPlace, String>>()
        val ids = HashSet<String>()
        val tails = ArrayList<String>()
        for (item in items) {
            for (loc in item.files.flatMap { it.locations }) {
                if (loc.held != true) continue
                ids += item.id
                smbPathOf(loc, mappings)?.let { paths += it }
                loc.trash?.let { tails += it }
            }
        }
        return HeldTrash(paths, ids, unknown, tails)
    }

    /** The compressed originals among [trashed] items (those with `compressedTo`), newest first. */
    fun originals(trashed: List<Pair<Item, Long>>, mappings: List<PathMapping>): List<CompressedOriginal> =
        trashed.mapNotNull { (item, since) ->
            val to = item.compressedTo ?: return@mapNotNull null
            val main = item.files.firstOrNull { it.fingerprint == item.mainFingerprint } ?: item.files.firstOrNull()
            val locations = item.files.flatMap { it.locations }
            CompressedOriginal(
                itemId = item.id,
                name = main?.name?.ifEmpty { null } ?: locations.firstNotNullOfOrNull { it.path }?.let(SharePaths::name) ?: item.id,
                size = main?.size ?: 0,
                compressedTo = to,
                held = locations.any { it.held == true },
                since = since,
                smbPaths = locations.mapNotNull { smbPathOf(it, mappings) },
            )
        }.sortedByDescending { it.since }

    /** [originals] by the SMB trash path of each of their files (lower-case), to label and route the trash rows. */
    fun byRow(originals: List<CompressedOriginal>): Map<Pair<TrashPlace, String>, CompressedOriginal> {
        val out = HashMap<Pair<TrashPlace, String>, CompressedOriginal>()
        for (o in originals) for ((place, path) in o.smbPaths) out[TrashPlace(place.locationId, place.share.lowercase()) to SharePaths.normalize(path).lowercase()] = o
        return out
    }

    /** The compressed original a trash row is a file of, or null. */
    fun of(row: TrashEntryEntity, index: Map<Pair<TrashPlace, String>, CompressedOriginal>): CompressedOriginal? =
        if (row.trashedPath.isEmpty()) null else index[TrashPlace(row.locationId, row.share.lowercase()) to SharePaths.normalize(row.trashedPath).lowercase()]
}
