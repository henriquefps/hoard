package dev.hfps.hoard.app.transfers

import org.json.JSONArray
import org.json.JSONObject

/*
 * Pure rules for where files go (ADR 0007), ported from src/features/transfers/places.ts and service.ts: the
 * clipboard's "Paste here", the destination picker's checks and its "Recent" list.
 */

const val DEVICE_LOCATION_ID = "device"
const val VOLUME_PREFIX = "vol-"
const val TREE_LOCATION_ID = "tree"
const val INCOMING_LOCATION_ID = "incoming"

/** Location ids (ADR 0005): `device`, `vol-<uuid>`, or anything else is a machine id. */
sealed interface LocationId {
    data object Device : LocationId
    data class Volume(val uuid: String) : LocationId
    data class Machine(val machineId: String) : LocationId

    companion object {
        fun parse(id: String): LocationId = when {
            id == DEVICE_LOCATION_ID -> Device
            id.startsWith(VOLUME_PREFIX) && id.length > VOLUME_PREFIX.length -> Volume(id.removePrefix(VOLUME_PREFIX))
            else -> Machine(id)
        }

        fun ofVolume(uuid: String?) = if (uuid == null) DEVICE_LOCATION_ID else VOLUME_PREFIX + uuid
    }
}

/** Machine ids are anything that is not a local or special location id. */
fun isMachine(locationId: String) = !(
    locationId == DEVICE_LOCATION_ID || locationId.startsWith(VOLUME_PREFIX) ||
        locationId == TREE_LOCATION_ID || locationId == INCOMING_LOCATION_ID
    )

/**
 * A place in app terms, turned into a native endpoint only when a job starts, so SMB endpoints carry the route that
 * is current then: a machine id with [share]; `device`; `vol-<uuid>`; `tree` with [treeUri]; `incoming` with
 * [shareId] (files another app shared; source only).
 */
data class TransferPlace(
    val locationId: String,
    val share: String? = null,
    val treeUri: String? = null,
    val shareId: String? = null,
)

/** A folder in any place. */
data class FolderPlace(val place: TransferPlace, val path: String)

/** What is selected for a transfer: paths in one place, with what the UI already knows. */
data class PickedEntry(val path: String, val name: String, val isDirectory: Boolean, val size: Long)

private fun clean(path: String) = path.split('/').filter { it.isNotEmpty() }.joinToString("/")
private fun sameShare(a: String?, b: String?) = (a ?: "").equals(b ?: "", ignoreCase = true)

/** The same location (and share): moves there are renames, and a folder must not go into itself. */
fun sameLocation(a: TransferPlace, b: TransferPlace) =
    a.locationId == b.locationId && sameShare(a.share, b.share) && (a.treeUri ?: "") == (b.treeUri ?: "")

fun isSameOrInside(parent: String, child: String): Boolean {
    val p = clean(parent)
    val c = clean(child)
    return p.isEmpty() || c == p || c.startsWith("$p/")
}

fun parentOf(path: String) = clean(path).split('/').dropLast(1).joinToString("/")

enum class PasteProblem { IntoItself, SameFolder }

/**
 * Why [entries] (from [source]) cannot go to [target], or null when they can. A copy into the same folder is
 * allowed: it makes "name (1)" copies.
 */
fun pasteProblem(op: String, source: TransferPlace, entries: List<PickedEntry>, target: FolderPlace): PasteProblem? {
    if (!sameLocation(source, target.place)) return null
    if (entries.any { it.isDirectory && isSameOrInside(it.path, target.path) }) return PasteProblem.IntoItself
    if (op == "move" && entries.all { parentOf(it.path) == clean(target.path) }) return PasteProblem.SameFolder
    return null
}

/** A recent destination (setting `recentDestinations`, the JSON Hoard 1 writes). */
data class RecentDestination(
    val locationId: String,
    val share: String?,
    val path: String,
    val label: String,
    val treeUri: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("locationId", locationId)
        share?.let { put("share", it) }
        put("path", path)
        put("label", label)
        treeUri?.let { put("treeUri", it) }
    }

    val folder: FolderPlace get() = FolderPlace(TransferPlace(locationId, share, treeUri), path)

    companion object {
        fun listFromJson(json: String?): List<RecentDestination> = runCatching {
            val array = JSONArray(json ?: return emptyList())
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val locationId = o.opt("locationId") as? String ?: return@mapNotNull null
                val path = o.opt("path") as? String ?: return@mapNotNull null
                val label = o.opt("label") as? String ?: return@mapNotNull null
                RecentDestination(locationId, o.optString("share").ifEmpty { null }, path, label, o.optString("treeUri").ifEmpty { null })
            }
        }.getOrDefault(emptyList())

        fun listToJson(list: List<RecentDestination>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }
}

const val MAX_RECENT_DESTINATIONS = 6

/** Put [destination] first in the recent list (no duplicates), keeping at most [max]. */
fun rememberDestination(list: List<RecentDestination>, destination: RecentDestination, max: Int = MAX_RECENT_DESTINATIONS): List<RecentDestination> {
    fun same(d: RecentDestination) = d.locationId == destination.locationId && sameShare(d.share, destination.share) &&
        clean(d.path) == clean(destination.path) && (d.treeUri ?: "") == (destination.treeUri ?: "")
    return (listOf(destination) + list.filterNot(::same)).take(max)
}

/** The transfer of [entries] from [source] to [destination]. */
data class TransferRequest(
    val op: String,
    val source: TransferPlace,
    val paths: List<String>,
    val name: String,
    val size: Long,
    val itemsTotal: Int,
    val destination: FolderPlace,
    val conflict: String,
)

fun transferRequest(op: String, source: TransferPlace, entries: List<PickedEntry>, destination: FolderPlace, conflict: String) = TransferRequest(
    op = op,
    source = source,
    paths = entries.map { it.path },
    name = entries.firstOrNull()?.name.orEmpty(),
    size = entries.filter { !it.isDirectory }.sumOf { it.size },
    itemsTotal = entries.size,
    destination = destination,
    conflict = conflict,
)

/** The conflict policies of a transfer, the default in Settings first. */
val CONFLICT_POLICIES = listOf("ask", "rename", "overwrite", "skip")
