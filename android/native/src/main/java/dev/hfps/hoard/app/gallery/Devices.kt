package dev.hfps.hoard.app.gallery

import dev.hfps.hoard.server.api.BackupRoot
import dev.hfps.hoard.server.db.ServerItemMirrorEntity
import java.util.Locale

/*
 * Other devices see backups (ADR 0024 A2), pure and JVM-tested (DevicesTest): where a server item sits in the phones'
 * backups ([MirrorSpot], from its `mirror` locations), the "Where: <phone> backup" filter ([Where]), the "Deleted from
 * the phone" badge ([OffPhone]) and Gallery › Devices ([DeviceTree]: phone › volume › folders, from
 * `GET /v1/backup/roots` and the items' mirror locations).
 */

/** One place of a server item in a phone's backup: its phone, volume (upper case) and directory on that volume. */
data class MirrorSpot(
    val physicalId: String,
    val deviceName: String,
    val volume: String,
    val dir: String,
    val onDevice: Boolean,
    /** `freed` or `deleted` when off the phone. */
    val gone: String?,
)

/** Why a backed-up shot is no longer on its phone (the cell's badge). */
enum class OffPhone { Deleted, Freed }

/** "Where: <phone> backup" (ADR 0024 A2): a phone, optionally one of its volumes and a folder (sub-folders included). */
data class Where(val physicalId: String, val deviceName: String, val volume: String? = null, val dir: String? = null) {
    fun matches(spot: MirrorSpot): Boolean {
        if (spot.physicalId != physicalId) return false
        if (volume != null && spot.volume != volume) return false
        if (dir != null && dir.isNotEmpty() && spot.dir != dir && !spot.dir.startsWith("$dir/")) return false
        return true
    }
}

object Mirrors {
    /** `primary` / a volume UUID as the server sends them, compared upper case (`PRIMARY`, `1234-ABCD`). */
    fun volume(value: String): String = value.trim().uppercase(Locale.ROOT).ifEmpty { PRIMARY }

    const val PRIMARY = "PRIMARY"

    /** Hoard's location of a phone file (`device`, `vol-<uuid>`, ADR 0005) as a backup volume. */
    fun volumeOf(locationId: String): String? = when {
        locationId == "device" -> PRIMARY
        locationId.startsWith("vol-") && locationId.length > 4 -> volume(locationId.removePrefix("vol-"))
        else -> null
    }

    /** An item's spots: one per phone, volume and directory (the files of a shot share them). */
    fun spots(rows: List<ServerItemMirrorEntity>): List<MirrorSpot> {
        if (rows.isEmpty()) return emptyList()
        val byPlace = LinkedHashMap<Triple<String, String, String>, MirrorSpot>()
        for (r in rows) {
            val dir = r.path.substringBeforeLast('/', "")
            val key = Triple(r.physicalId, volume(r.volume), dir)
            val spot = MirrorSpot(r.physicalId, r.deviceName, key.second, dir, r.onDevice, r.gone)
            val previous = byPlace[key]
            // A place is on the phone while any file of the shot there is; freed wins over deleted.
            byPlace[key] = when {
                previous == null -> spot
                previous.onDevice || spot.onDevice -> previous.copy(onDevice = true, gone = null)
                previous.gone == "freed" || spot.gone == "freed" -> previous.copy(gone = "freed")
                else -> previous
            }
        }
        return byPlace.values.toList()
    }

    /**
     * The badge: a backed-up shot none of whose places (on [physicalId]'s phone only, when the grid shows one phone's
     * backup) still has it. Freed when free up removed it (A3), else deleted.
     */
    fun offPhone(spots: List<MirrorSpot>, physicalId: String? = null): OffPhone? {
        val mine = if (physicalId == null) spots else spots.filter { it.physicalId == physicalId }
        if (mine.isEmpty() || mine.any { it.onDevice }) return null
        return if (mine.any { it.gone == "freed" }) OffPhone.Freed else OffPhone.Deleted
    }

    /**
     * This phone's own files by place (`VOLUME|path`, path case kept as the phone has it) → item id, for the merge of a
     * phone file with its backed-up item before the file is fingerprinted: only places still on the phone, with the
     * file's size.
     */
    fun ownPlaces(rows: Collection<ServerItemMirrorEntity>, physicalId: String?): Map<String, Pair<String, Long>> {
        if (physicalId == null) return emptyMap()
        val map = HashMap<String, Pair<String, Long>>()
        for (r in rows) {
            if (r.physicalId != physicalId || !r.onDevice) continue
            map.putIfAbsent(placeKey(volume(r.volume), r.path), r.itemId to r.size)
        }
        return map
    }

    fun placeKey(volume: String, path: String) = "$volume|${path.trim('/')}"
}

/** A folder of a phone's backup: a directory on a volume, with the shots in it and below it. */
data class DeviceFolder(val volume: String, val dir: String, val shots: Int, val offPhone: Int, val root: Boolean)

data class DeviceVolume(val volume: String, val folders: List<DeviceFolder>, val shots: Int)

/** A phone (a physical device, ADR 0017) with a backup on the server. */
data class BackupDevice(
    val physicalId: String,
    val name: String,
    val mine: Boolean,
    val volumes: List<DeviceVolume>,
    val shots: Int,
    val offPhone: Int,
    /** A root of it was reported in the last 7 days (the server's `active`), null when the server did not say. */
    val active: Boolean?,
    val lastReportAt: Long?,
) {
    val where: Where get() = Where(physicalId, name)
}

object DeviceTree {
    /**
     * Gallery › Devices: every phone with a backup root or a backed-up shot, this phone first, then by name; each with
     * its volumes (internal storage first) and folders (every root and every directory holding shots, by path; counts
     * include sub-folders). Stopped roots stay (their files stay on the server).
     */
    fun build(roots: List<BackupRoot>, entries: List<GalleryEntry>, mine: String?): List<BackupDevice> {
        class Acc(var name: String, var mine: Boolean) {
            val folders = LinkedHashMap<Pair<String, String>, Boolean>() // (volume, dir) → is a root
            val shots = HashSet<String>()
            val volumeShots = HashMap<String, MutableSet<String>>()
            val off = HashSet<String>()
            var active: Boolean? = null
            var lastReport: Long? = null
        }
        val devices = LinkedHashMap<String, Acc>()
        for (root in roots) {
            val id = root.physicalDeviceId ?: continue
            val acc = devices.getOrPut(id) { Acc(root.deviceName.orEmpty(), root.mine || id == mine) }
            if (acc.name.isEmpty()) acc.name = root.deviceName.orEmpty()
            if (root.mine) acc.mine = true
            acc.folders[Mirrors.volume(root.volume) to root.path.trim('/')] = true
            acc.active = (acc.active ?: false) || root.active
            root.lastReportAt?.let { t -> acc.lastReport = maxOf(acc.lastReport ?: 0L, t) }
        }
        // Shots per folder (sub-folders included) and per device.
        val perFolder = HashMap<Triple<String, String, String>, MutableSet<String>>()
        val offPerFolder = HashMap<Triple<String, String, String>, MutableSet<String>>()
        for (e in entries) {
            if (e.mirrors.isEmpty()) continue
            for (spot in e.mirrors) {
                val acc = devices.getOrPut(spot.physicalId) { Acc(spot.deviceName, spot.physicalId == mine) }
                if (acc.name.isEmpty()) acc.name = spot.deviceName
                acc.folders.putIfAbsent(spot.volume to spot.dir, false)
                acc.shots += e.key
                acc.volumeShots.getOrPut(spot.volume) { HashSet() } += e.key
            }
            for (id in e.mirrors.map { it.physicalId }.distinct()) {
                if (Mirrors.offPhone(e.mirrors, id) != null) devices[id]?.off?.add(e.key)
            }
        }
        for (e in entries) {
            for (spot in e.mirrors) {
                val acc = devices[spot.physicalId] ?: continue
                val off = Mirrors.offPhone(e.mirrors, spot.physicalId) != null
                for ((place, _) in acc.folders) {
                    val (volume, dir) = place
                    if (volume != spot.volume) continue
                    if (dir.isEmpty() || spot.dir == dir || spot.dir.startsWith("$dir/")) {
                        val key = Triple(spot.physicalId, volume, dir)
                        perFolder.getOrPut(key) { HashSet() } += e.key
                        if (off) offPerFolder.getOrPut(key) { HashSet() } += e.key
                    }
                }
            }
        }
        return devices.map { (id, acc) ->
            val volumes = acc.folders.keys.groupBy { it.first }.map { (volume, places) ->
                val folders = places.map { (_, dir) ->
                    val key = Triple(id, volume, dir)
                    DeviceFolder(volume, dir, perFolder[key]?.size ?: 0, offPerFolder[key]?.size ?: 0, acc.folders[volume to dir] == true)
                }.sortedWith(compareBy({ it.dir.lowercase(Locale.ROOT) }, { it.dir }))
                DeviceVolume(volume, folders, acc.volumeShots[volume]?.size ?: 0)
            }.sortedWith(compareBy({ it.volume != Mirrors.PRIMARY }, { it.volume }))
            BackupDevice(id, acc.name.ifEmpty { id.take(8) }, acc.mine, volumes, acc.shots.size, acc.off.size, acc.active, acc.lastReport)
        }.sortedWith(compareBy({ !it.mine }, { it.name.lowercase(Locale.ROOT) }, { it.physicalId }))
    }
}
