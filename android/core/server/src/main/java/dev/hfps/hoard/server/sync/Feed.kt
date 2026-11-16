package dev.hfps.hoard.server.sync

import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ItemsPage
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.db.CullingEntity
import dev.hfps.hoard.server.db.CullingFingerprintEntity
import dev.hfps.hoard.server.db.ServerItemEntity
import dev.hfps.hoard.server.db.ServerItemFileEntity
import dev.hfps.hoard.server.db.ServerItemMirrorEntity
import dev.hfps.hoard.server.api.FileLocation

/** One page of the items feed as database writes (pure; FeedTest). */
data class FeedWrites(
    val items: List<ServerItemEntity>,
    val files: List<ServerItemFileEntity>,
    val goneItems: List<String>,
    val records: List<CullingEntity>,
    val recordFingerprints: List<CullingFingerprintEntity>,
    /** Records replaced by another (`replacedBy`): their queued changes move there and the old record goes. */
    val replaced: Map<String, String>,
    /** The `mirror` locations of the live items (ADR 0024 A2). */
    val mirrors: List<ServerItemMirrorEntity> = emptyList(),
)

object Feed {
    fun writes(page: ItemsPage): FeedWrites {
        val live = ArrayList<ServerItemEntity>()
        val files = ArrayList<ServerItemFileEntity>()
        val mirrors = ArrayList<ServerItemMirrorEntity>()
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
            mirrors += mirrors(item)
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
        return FeedWrites(live, files, gone, records, prints, replaced, mirrors)
    }

    /**
     * An item's `mirror` locations (ADR 0024: a phone's backup), one row per file and place; locations without a phone,
     * root or path are skipped. `onDevice` absent means on the phone; an unknown `gone` reads as `deleted`.
     */
    fun mirrors(item: Item): List<ServerItemMirrorEntity> = item.files.flatMap { file ->
        file.locations.filter { it.source == FileLocation.SOURCE_MIRROR }.mapNotNull { loc ->
            val physical = loc.physicalDeviceId?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val root = loc.rootId?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val path = loc.path?.trim('/')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val onDevice = loc.onDevice != false
            ServerItemMirrorEntity(
                itemId = item.id,
                fingerprint = file.fingerprint,
                physicalId = physical,
                deviceName = loc.deviceName?.trim().orEmpty(),
                rootId = root,
                volume = loc.volume?.trim()?.ifEmpty { null } ?: "primary",
                path = path,
                size = file.size,
                modifiedAt = loc.modifiedAt,
                onDevice = onDevice,
                gone = if (onDevice) null else if (loc.gone == "freed") "freed" else "deleted",
            )
        }
    }.distinctBy { listOf(it.fingerprint, it.rootId, it.path) }

    /** The grid's row of an item: names, folder, search text and the primary edit (ADR 0020). */
    fun entity(item: Item): ServerItemEntity {
        val main = item.files.firstOrNull { it.fingerprint == item.mainFingerprint } ?: item.files.firstOrNull()
        val nas = (main?.locations.orEmpty() + item.files.flatMap { it.locations }).firstOrNull { it.source == "nas" && it.path != null }
        val folder = nas?.let { loc ->
            val dir = loc.path!!.substringBeforeLast('/', "")
            listOfNotNull(loc.folderName, dir.ifEmpty { null }).joinToString("/")
        } ?: item.files.flatMap { it.locations }.firstOrNull { it.source == FileLocation.SOURCE_MIRROR && it.path != null }?.let { loc ->
            // Only on a phone's backup (ADR 0024 A2): `<phone>/<directory on the phone>`.
            listOfNotNull(loc.deviceName?.trim()?.ifEmpty { null }, loc.path!!.trim('/').substringBeforeLast('/', "").ifEmpty { null }).joinToString("/")
        }.orEmpty()
        val primary = item.edits.firstOrNull { it.primary }
        val devices = item.files.flatMap { it.locations }.filter { it.source == FileLocation.SOURCE_MIRROR }.mapNotNull { it.deviceName?.trim()?.takeIf { n -> n.isNotEmpty() } }.distinct()
        val search = (item.files.map { it.name } + folder + devices).joinToString(" ").lowercase()
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
