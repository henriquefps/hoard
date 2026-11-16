package dev.hfps.hoard.server.sync

import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ItemsPage
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.db.CullingEntity
import dev.hfps.hoard.server.db.CullingFingerprintEntity
import dev.hfps.hoard.server.db.ServerItemEntity
import dev.hfps.hoard.server.db.ServerItemFileEntity

/** One page of the items feed as database writes (pure; FeedTest). */
data class FeedWrites(
    val items: List<ServerItemEntity>,
    val files: List<ServerItemFileEntity>,
    val goneItems: List<String>,
    val records: List<CullingEntity>,
    val recordFingerprints: List<CullingFingerprintEntity>,
    /** Records replaced by another (`replacedBy`): their queued changes move there and the old record goes. */
    val replaced: Map<String, String>,
)

object Feed {
    fun writes(page: ItemsPage): FeedWrites {
        val live = ArrayList<ServerItemEntity>()
        val files = ArrayList<ServerItemFileEntity>()
        val gone = ArrayList<String>()
        val replaced = LinkedHashMap<String, String>()
        // A page holds each item once, at its latest state.
        for (item in page.items) {
            if (item.deleted || item.mainFingerprint == null) {
                gone += item.id
                item.replacedBy?.let { replaced[item.id] = it }
                continue
            }
            live += entity(item)
            // A trashed item (include=trash) keeps its row, hidden from the grid; its files no longer back up a phone shot.
            if (item.trashed != null) continue
            files += item.files.distinctBy { it.fingerprint }.map { ServerItemFileEntity(it.fingerprint, item.id, it.role) }
        }
        val records = ArrayList<CullingEntity>()
        val prints = ArrayList<CullingFingerprintEntity>()
        for (record in page.culling) {
            if (record.replacedBy != null) {
                replaced[record.itemId] = record.replacedBy
                continue
            }
            records += CullingRules.entity(record)
            prints += CullingRules.fingerprints(record)
        }
        return FeedWrites(live, files, gone, records, prints, replaced)
    }

    /** The grid's row of an item: names, folder, search text and the primary edit (ADR 0020). */
    fun entity(item: Item): ServerItemEntity {
        val main = item.files.firstOrNull { it.fingerprint == item.mainFingerprint } ?: item.files.firstOrNull()
        val nas = (main?.locations.orEmpty() + item.files.flatMap { it.locations }).firstOrNull { it.source == "nas" && it.path != null }
        val folder = nas?.let { loc ->
            val dir = loc.path!!.substringBeforeLast('/', "")
            listOfNotNull(loc.folderName, dir.ifEmpty { null }).joinToString("/")
        }.orEmpty()
        val primary = item.edits.firstOrNull { it.primary }
        val search = (item.files.map { it.name } + folder).joinToString(" ").lowercase()
        return ServerItemEntity(
            id = item.id,
            rev = item.rev,
            kind = item.kind ?: "photo",
            mainFingerprint = item.mainFingerprint!!,
            sortTime = item.sortTime,
            captureLocal = item.capture?.local,
            make = item.make,
            model = item.model,
            lens = item.lens,
            width = item.width ?: item.video?.width,
            height = item.height ?: item.video?.height,
            orientation = item.orientation,
            durationMs = item.video?.durationMs,
            name = main?.name.orEmpty(),
            folder = folder,
            search = search,
            hasRaw = item.files.any { it.role == "raw" },
            editPrimary = primary?.versionId,
            editCount = item.edits.size,
            editedAt = primary?.updatedAt ?: primary?.editedAt,
            json = ServerJson.encodeToString(Item.serializer(), item),
            trashedSince = item.trashed?.since,
        )
    }
}
