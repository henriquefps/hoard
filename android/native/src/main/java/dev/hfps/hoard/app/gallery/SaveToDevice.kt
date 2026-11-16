package dev.hfps.hoard.app.gallery

import dev.hfps.hoard.app.transfers.DEVICE_LOCATION_ID
import dev.hfps.hoard.app.transfers.FolderPlace
import dev.hfps.hoard.app.transfers.RecentDestination
import dev.hfps.hoard.app.transfers.SERVER_LOCATION_ID
import dev.hfps.hoard.app.transfers.TransferPlace
import dev.hfps.hoard.app.transfers.TransferRequest
import dev.hfps.hoard.server.api.FileLocation
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.smb.transfer.Conflicts
import java.util.Locale

/**
 * "Save to this device" (ADR 0024 A2), pure and JVM-tested (SaveToDeviceTest): whole shots of server items (every
 * file: RAW, renderings, video, the sidecar when the server has one) copied by the one transfer engine (ADR 0007) from
 * the read-only server endpoint (paths `<fingerprint>/<name>`) into a folder of this device: the one the owner chose
 * once, else `Pictures/From <device name>/` (the phone the shot was backed up from; the server's name for NAS photos).
 * Never under `Pictures/Hoard/` or `Download/Hoard/` (Hoard's copies for Reveal, which the index and the backup skip).
 */
object SaveToDevice {
    /** Setting: the folder chosen once (a [RecentDestination] as JSON); absent = `Pictures/From <device name>/`. */
    const val KEY_FOLDER = "gallerySaveFolder"

    /** Hoard's own copies (ADR 0010): never a destination here. */
    val REFUSED = listOf("Pictures/Hoard", "Download/Hoard")

    /** `From Pixel 8`: the device's name without characters a folder may not hold, at most 60 characters. */
    fun folderName(device: String?): String {
        val clean = device.orEmpty()
            .map { c -> if (c < ' ' || c in "/\\:*?\"<>|") ' ' else c }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .trim()
            .take(60)
            .trim()
        return "From " + clean.ifEmpty { "Hoard server" }
    }

    fun defaultFolder(device: String?): FolderPlace = FolderPlace(TransferPlace(DEVICE_LOCATION_ID), "Pictures/" + folderName(device))

    /** Whether [path] is Hoard's copies folder or inside it (any case). */
    fun refused(path: String): Boolean {
        val clean = path.split('/').filter { it.isNotEmpty() }.joinToString("/").lowercase(Locale.ROOT)
        return REFUSED.any { r -> val p = r.lowercase(Locale.ROOT); clean == p || clean.startsWith("$p/") }
    }

    /** The phone a shot was backed up from (its first mirror location), null for NAS photos and uploads. */
    fun deviceOf(item: Item): String? = item.files.flatMap { it.locations }
        .firstOrNull { it.source == FileLocation.SOURCE_MIRROR && !it.deviceName.isNullOrBlank() }?.deviceName?.trim()

    /** A file's name at the destination: its own, else (an upload without a path) `<fingerprint>.<format>`. */
    fun nameOf(fingerprint: String, name: String, format: String?): String {
        val clean = name.substringAfterLast('/').trim()
        if (clean.isNotEmpty() && clean != "." && clean != "..") return clean
        val ext = when (format?.lowercase(Locale.ROOT)) {
            null, "" -> "bin"
            "jpeg" -> "jpg"
            "tiff" -> "tif"
            else -> format.lowercase(Locale.ROOT)
        }
        return fingerprint.removePrefix("v1:").take(16) + "." + ext
    }

    /** The source paths of a shot: every file once, the main file first. */
    fun paths(item: Item): List<String> = item.files.distinctBy { it.fingerprint }
        .sortedBy { if (it.fingerprint == item.mainFingerprint) 0 else 1 }
        .map { "${it.fingerprint}/${nameOf(it.fingerprint, it.name, it.format)}" }

    /** A file's modified time on its phone (or the NAS), kept by the copy. */
    fun modifiedOf(item: Item, fingerprint: String): Long? {
        val locations = item.files.firstOrNull { it.fingerprint == fingerprint }?.locations.orEmpty()
        return (locations.firstOrNull { it.source == FileLocation.SOURCE_MIRROR } ?: locations.firstOrNull { it.source == "nas" })?.modifiedAt?.takeIf { it > 0 }
    }

    /**
     * One transfer per destination folder: [items] grouped by where they go ([chosen], else each shot's default
     * folder), [conflict] the owner's default policy. [fallbackName] names the folder of shots without a phone.
     */
    fun requests(items: List<Item>, chosen: FolderPlace?, serverId: String, conflict: String, fallbackName: String?): List<TransferRequest> {
        val source = TransferPlace(SERVER_LOCATION_ID, share = serverId)
        val byFolder = LinkedHashMap<FolderPlace, MutableList<Item>>()
        for (item in items) {
            val folder = chosen ?: defaultFolder(deviceOf(item) ?: fallbackName)
            byFolder.getOrPut(folder) { ArrayList() } += item
        }
        return byFolder.map { (folder, shots) ->
            val paths = shots.flatMap(::paths)
            val sizes = shots.flatMap { s -> s.files.distinctBy { it.fingerprint } }.sumOf { it.size.coerceAtLeast(0) }
            TransferRequest(
                op = "copy",
                source = source,
                paths = paths,
                name = paths.firstOrNull()?.substringAfter('/').orEmpty(),
                size = sizes,
                itemsTotal = paths.size,
                destination = folder,
                conflict = conflict.takeIf { it in Conflicts.ALL } ?: Conflicts.ASK,
            )
        }
    }

    /** The folder chosen once, from its setting; null = the default per device. */
    fun chosen(json: String?): FolderPlace? = RecentDestination.listFromJson(json?.let { "[$it]" }).firstOrNull()?.folder

    fun chosenJson(folder: FolderPlace, label: String): String =
        RecentDestination(folder.place.locationId, folder.place.share, folder.path, label, folder.place.treeUri).toJson().toString()
}
