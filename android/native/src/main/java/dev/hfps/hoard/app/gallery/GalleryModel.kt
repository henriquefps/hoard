package dev.hfps.hoard.app.gallery

import dev.hfps.hoard.image.format.ImageFormats
import dev.hfps.hoard.server.db.DeviceMediaEntity
import dev.hfps.hoard.server.db.ServerItemMirrorEntity
import dev.hfps.hoard.server.db.ServerItemRow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/*
 * The Gallery's model (ADR 0021), pure and JVM-tested (GalleryModelTest): the phone's files grouped into shots as the
 * server groups them (ADR 0014), merged with the server's items so a shot that is in both shows once ("backed up"),
 * sorted newest first, filtered, cut into day (or month) sections for the grid and the fast scroller.
 */

enum class Kind { Photo, Pair, Video, Motion }

enum class Source { Device, Server, Both }

/** One file of a device shot. */
data class DeviceFile(val media: DeviceMediaEntity, val role: String)

/** A shot on the phone: files of one folder with the same case-insensitive stem (RAW, renderings, video). */
data class DeviceShot(
    val key: String,
    val locationId: String,
    val dir: String,
    val stem: String,
    val files: List<DeviceFile>,
) {
    val main: DeviceMediaEntity get() = files.first().media
    val kind: Kind get() = when {
        files.any { it.role == ROLE_RAW } && files.any { it.role == ROLE_RENDERED } -> Kind.Pair
        files.first().role == ROLE_VIDEO -> Kind.Video
        files.any { it.role == ROLE_VIDEO } || files.any { it.media.motion } -> Kind.Motion
        else -> Kind.Photo
    }
    val fingerprints: List<String> get() = files.mapNotNull { it.media.fingerprint?.takeIf { _ -> it.media.fpSize == it.media.size && it.media.fpModified == it.media.modified } }
    /** Every file fingerprinted (until then a backed-up shot may still show as device-only). */
    val fingerprinted: Boolean get() = files.size == fingerprints.size

    companion object {
        const val ROLE_RAW = "raw"
        const val ROLE_RENDERED = "rendered"
        const val ROLE_VIDEO = "video"
    }
}

object DeviceShots {
    private val VIDEO = setOf("mp4", "m4v", "mov", "3gp", "3g2", "mts", "m2ts", "mkv", "webm", "avi")

    fun roleOf(media: DeviceMediaEntity): String {
        val ext = ImageFormats.extension(media.name)
        return when {
            media.video || ext in VIDEO -> DeviceShot.ROLE_VIDEO
            ImageFormats.classify(media.name).isRaw -> DeviceShot.ROLE_RAW
            else -> DeviceShot.ROLE_RENDERED
        }
    }

    /** `<location>/<dir>/<stem, lower case>`: one shot per key. */
    fun shotKey(locationId: String, dir: String, name: String): String = "$locationId/$dir/${stemOf(name).lowercase()}"

    fun stemOf(name: String): String = ImageFormats.stem(name)

    /**
     * Groups files into shots (ADR 0014 "Pairing rule"): RAWs first (by name), then renderings (JPEG before HEIF, then
     * name), then videos. The main file is the first of that order.
     */
    fun group(rows: List<DeviceMediaEntity>): List<DeviceShot> = rows.groupBy { it.shotKey }.map { (key, files) ->
        val ordered = files.map { DeviceFile(it, roleOf(it)) }.sortedWith(
            compareBy<DeviceFile>({ rank(it.role) }, { renderedRank(it.media.name) }, { it.media.name }),
        )
        val main = ordered.first().media
        DeviceShot(key, main.locationId, main.dir, stemOf(main.name), ordered)
    }

    private fun rank(role: String) = when (role) {
        DeviceShot.ROLE_RAW -> 0
        DeviceShot.ROLE_RENDERED -> 1
        else -> 2
    }

    private fun renderedRank(name: String) = when (ImageFormats.extension(name)) {
        "jpg", "jpeg", "jpe", "jfif" -> 0
        "heic", "heif", "hif" -> 1
        else -> 2
    }
}

/** A shot's culling as the grid shows it. */
data class EntryDecision(val rating: Int = 0, val flag: String? = null) {
    val unmarked: Boolean get() = rating == 0 && flag == null
}

/** One cell of the Gallery. [key] is `s:<item id>` for anything on the server, `d:<shot key>` for a phone-only shot. */
data class GalleryEntry(
    val key: String,
    val source: Source,
    val kind: Kind,
    /** Newest-first order: the server's `sortTime` when it knows the shot, else the phone's DATE_TAKEN. */
    val time: Long,
    /** The calendar day of the shot in its own time zone (the camera's wall clock when known). */
    val day: LocalDate,
    val name: String,
    /** `photos/2024/Trip` on the server, `DCIM/Camera` on the phone. */
    val folder: String,
    val camera: String?,
    val lens: String? = null,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val hasRaw: Boolean,
    val itemId: String?,
    val editPrimary: String?,
    val editCount: Int,
    val device: DeviceShot?,
    val mainFingerprint: String?,
    val decision: EntryDecision,
    val search: String,
    /** The camera's wall clock (`2024-05-03T10:20:06.250`) when the server knows it. */
    val captureLocal: String? = null,
    /** A phone-only shot in a backed-up folder that is not backed up yet (ADR 0024: the Gallery's badge). */
    val backup: BackupBadge? = null,
    /** Where a server item is in the phones' backups (ADR 0024 A2: Devices, "Where", the "Deleted from the phone" badge). */
    val mirrors: List<MirrorSpot> = emptyList(),
) {
    val isVideo: Boolean get() = kind == Kind.Video
    val edited: Boolean get() = editPrimary != null
}

data class GalleryInput(
    val server: List<ServerItemRow>,
    val device: List<DeviceShot>,
    /** Member fingerprint → item id (every file of every server item). */
    val serverFiles: Map<String, String>,
    /** Server item id → decision (cache + pending). */
    val serverDecisions: Map<String, EntryDecision>,
    /** Device shot key → local decision (Hoard's `cull_decisions`). */
    val deviceDecisions: Map<String, EntryDecision>,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Phone path → backup state of files not backed up yet (`backup_files`, ADR 0024). */
    val backup: Map<String, String> = emptyMap(),
    /** Item id → its `mirror` locations (ADR 0024 A2). */
    val mirrors: Map<String, List<ServerItemMirrorEntity>> = emptyMap(),
    /** This phone's physical device (ADR 0017): its own backed-up files merge with their items by place. */
    val physicalId: String? = null,
    /**
     * A phone file's new fingerprint → the one the server's item still has, for files this phone rewrote in place
     * (ADR 0025 Amendment (2.8.1), [CompressAliases]): until a sync brings the swapped item, the file shows with it.
     */
    val aliases: Map<String, String> = emptyMap(),
)

/**
 * The fingerprints a phone replace changed (ADR 0025 Amendment (2.8.1)). After a replace (or a restore) the phone's
 * index has the file's new fingerprint at once, while the cached server item keeps the old one, and its mirror the old
 * size, until a sync after the server's swap: the file would show twice (a phone-only cell and the old item). Rows whose
 * write started and that are `written` or `reported` map the file's fingerprint now (the row's target) to the one it
 * had (its source); the Gallery tries it after a direct match, so once the cache has the new item that one wins.
 */
object CompressAliases {
    fun of(rows: List<dev.hfps.hoard.server.db.CompressPhoneEntity>): Map<String, String> {
        val out = HashMap<String, String>()
        // Oldest first: a newer cycle of the same file (a restore after a replace) overrides.
        for (r in rows.sortedBy { it.updatedAt }) {
            if (!r.writeStarted) continue
            if (r.state != dev.hfps.hoard.server.compress.PhoneState.WRITTEN && r.state != dev.hfps.hoard.server.compress.PhoneState.REPORTED) continue
            if (r.targetFingerprint == r.sourceFingerprint) continue
            out[r.targetFingerprint] = r.sourceFingerprint
        }
        return out
    }
}

/** A phone shot's backup as its cell shows it (backed-up shots show as on the server instead). */
enum class BackupBadge { Waiting, Uploading, Paused, Failed;

    companion object {
        /** The shot's files' states → the one that matters most: failed, uploading, paused, waiting. */
        fun of(states: List<String>): BackupBadge? {
            if (states.isEmpty()) return null
            return when {
                states.any { it == "failed" || it == "refused" } -> Failed
                states.any { it == "uploading" || it == "hashing" } -> Uploading
                states.any { it == "paused" } -> Paused
                else -> Waiting
            }
        }
    }
}

object GalleryModel {
    fun entries(input: GalleryInput): List<GalleryEntry> {
        val byItem = HashMap<String, DeviceShot>()
        val out = ArrayList<GalleryEntry>(input.server.size + input.device.size)
        val deviceOnly = ArrayList<DeviceShot>()
        // ADR 0024 A2: this phone's backed-up files are also known by place (its mirror locations), so a file the index
        // has not fingerprinted yet still shows once, with its item, never as a second phone-only cell.
        val own = Mirrors.ownPlaces(input.mirrors.values.flatten(), input.physicalId)
        for (shot in input.device) {
            val item = shot.fingerprints.firstNotNullOfOrNull { input.serverFiles[it] }
                // A file rewritten by a phone replace, before the cache has the swapped item (ADR 0025 2.8.1).
                ?: shot.fingerprints.firstNotNullOfOrNull { fp -> input.aliases[fp]?.let { input.serverFiles[it] } }
                ?: if (own.isEmpty()) null else shot.files.firstNotNullOfOrNull { f ->
                    val volume = Mirrors.volumeOf(f.media.locationId) ?: return@firstNotNullOfOrNull null
                    val path = if (f.media.dir.isEmpty()) f.media.name else "${f.media.dir.trim('/')}/${f.media.name}"
                    own[Mirrors.placeKey(volume, path)]?.takeIf { it.second == f.media.size }?.first
                }
            if (item != null) {
                // Copies of one shot in two phone folders are one item: keep the first (they are the same bytes).
                byItem.putIfAbsent(item, shot)
            } else {
                deviceOnly += shot
            }
        }
        for (row in input.server) {
            val shot = byItem[row.id]
            val kind = when (row.kind) {
                "pair" -> Kind.Pair
                "video" -> Kind.Video
                "motion" -> Kind.Motion
                else -> Kind.Photo
            }
            out += GalleryEntry(
                key = "s:${row.id}",
                source = if (shot != null) Source.Both else Source.Server,
                kind = kind,
                time = row.sortTime,
                day = dayOf(row.captureLocal, row.sortTime, input.zone),
                name = row.name,
                folder = row.folder,
                camera = camera(row.make, row.model),
                lens = row.lens?.trim()?.takeIf { it.isNotEmpty() },
                width = row.width,
                height = row.height,
                durationMs = row.durationMs,
                hasRaw = row.hasRaw,
                itemId = row.id,
                editPrimary = row.editPrimary,
                editCount = row.editCount,
                device = shot,
                mainFingerprint = row.mainFingerprint,
                decision = input.serverDecisions[row.id] ?: EntryDecision(),
                search = row.search + (shot?.let { " " + it.dir.lowercase() } ?: ""),
                captureLocal = row.captureLocal,
                mirrors = input.mirrors[row.id]?.let(Mirrors::spots).orEmpty(),
            )
        }
        for (shot in deviceOnly) {
            val main = shot.main
            out += GalleryEntry(
                key = "d:${shot.key}",
                source = Source.Device,
                kind = shot.kind,
                time = main.taken,
                day = Instant.ofEpochMilli(main.taken).atZone(input.zone).toLocalDate(),
                name = main.name,
                folder = shot.dir,
                camera = null,
                width = main.width,
                height = main.height,
                durationMs = shot.files.firstNotNullOfOrNull { it.media.durationMs },
                hasRaw = shot.files.any { it.role == DeviceShot.ROLE_RAW },
                itemId = null,
                editPrimary = null,
                editCount = 0,
                device = shot,
                mainFingerprint = main.fingerprint,
                decision = input.deviceDecisions[shot.key] ?: EntryDecision(),
                search = (shot.files.joinToString(" ") { it.media.name } + " " + shot.dir).lowercase(),
                backup = if (input.backup.isEmpty()) null else BackupBadge.of(shot.files.mapNotNull { input.backup[it.media.path] }),
            )
        }
        out.sortWith(compareByDescending<GalleryEntry> { it.time }.thenBy { it.key })
        return out
    }

    fun camera(make: String?, model: String?): String? {
        val m = model?.trim().orEmpty()
        val k = make?.trim().orEmpty()
        return when {
            m.isEmpty() && k.isEmpty() -> null
            m.isEmpty() -> k
            k.isEmpty() || m.startsWith(k, ignoreCase = true) -> m
            else -> "$k $m"
        }
    }

    /** The shot's day: the camera's wall clock (`capture.local`), else `sortTime` in the phone's zone. */
    fun dayOf(local: String?, sortTime: Long, zone: ZoneId): LocalDate {
        if (local != null && local.length >= 10) runCatching { return LocalDate.parse(local.substring(0, 10)) }
        return if (sortTime <= 0) LocalDate.ofEpochDay(0) else Instant.ofEpochMilli(sortTime).atZone(zone).toLocalDate()
    }

    /** `capture.local` as epoch ms read as UTC (a floating time), for ordering device and server shots together. */
    fun floating(local: String): Long? = runCatching { LocalDateTime.parse(local).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}

// --- Filters ----------------------------------------------------------------------------------------------------------

enum class KindFilter { All, Photos, Videos, RawPairs, Motion }

enum class SourceFilter { All, Device, Server, Both }

enum class FlagChoice { All, Picked, Rejected, Unmarked }

data class GalleryFilter(
    val kind: KindFilter = KindFilter.All,
    val source: SourceFilter = SourceFilter.All,
    val minRating: Int = 0,
    val flag: FlagChoice = FlagChoice.All,
    val editedOnly: Boolean = false,
    val camera: String? = null,
    val lens: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val query: String = "",
    /** "Where: <phone> backup" (ADR 0024 A2), or one of its folders from Gallery › Devices. */
    val where: Where? = null,
) {
    val active: Boolean get() = this.copy(query = "") != GalleryFilter()
    val count: Int get() = listOf(kind != KindFilter.All, source != SourceFilter.All, minRating > 0, flag != FlagChoice.All, editedOnly, camera != null, lens != null, from != null || to != null, where != null).count { it }

    fun matches(e: GalleryEntry): Boolean {
        if (where != null && e.mirrors.none(where::matches)) return false
        when (kind) {
            KindFilter.All -> Unit
            KindFilter.Photos -> if (e.kind == Kind.Video) return false
            KindFilter.Videos -> if (e.kind != Kind.Video && e.kind != Kind.Motion) return false
            KindFilter.RawPairs -> if (e.kind != Kind.Pair) return false
            KindFilter.Motion -> if (e.kind != Kind.Motion) return false
        }
        when (source) {
            SourceFilter.All -> Unit
            SourceFilter.Device -> if (e.source == Source.Server) return false
            SourceFilter.Server -> if (e.source == Source.Device) return false
            SourceFilter.Both -> if (e.source != Source.Both) return false
        }
        if (e.decision.rating < minRating) return false
        when (flag) {
            FlagChoice.All -> Unit
            FlagChoice.Picked -> if (e.decision.flag != "pick") return false
            FlagChoice.Rejected -> if (e.decision.flag != "reject") return false
            FlagChoice.Unmarked -> if (e.decision.flag != null) return false
        }
        if (editedOnly && !e.edited) return false
        if (camera != null && e.camera != camera) return false
        if (lens != null && e.lens != lens) return false
        if (from != null && e.day < from) return false
        if (to != null && e.day > to) return false
        if (query.isNotBlank()) {
            val words = query.lowercase().split(' ').filter { it.isNotBlank() }
            if (!words.all { e.search.contains(it) }) return false
        }
        return true
    }
}

/** Cameras with counts, most used first (the filter's choices). */
fun cameras(entries: List<GalleryEntry>): List<Pair<String, Int>> = counts(entries.mapNotNull { it.camera })

/** Lenses with counts, most used first. */
fun lenses(entries: List<GalleryEntry>): List<Pair<String, Int>> = counts(entries.mapNotNull { it.lens })

private fun counts(values: List<String>): List<Pair<String, Int>> =
    values.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }

// --- Sections and the fast scroller -----------------------------------------------------------------------------------

/** A grid row: a date header or a cell. */
sealed interface GridRow {
    val id: String

    data class Header(val day: LocalDate, val month: Boolean, val count: Int) : GridRow {
        override val id: String get() = if (month) "m:${day.year}-${day.monthValue}" else "h:$day"
    }

    data class Cell(val entry: GalleryEntry, val index: Int) : GridRow {
        override val id: String get() = entry.key
    }
}

/** A mark on the fast scroller: where a year or a month starts. */
data class ScrollMark(val row: Int, val year: Int, val month: Int, val yearStart: Boolean)

data class GalleryLayout(val rows: List<GridRow>, val entries: List<GalleryEntry>, val marks: List<ScrollMark>) {
    /** The row of entry [key] (to bring a shot back into view after the viewer). */
    fun rowOf(key: String): Int = rows.indexOfFirst { it is GridRow.Cell && it.entry.key == key }

    /** The year and month at [row] (the scroller's bubble). */
    fun markAt(row: Int): ScrollMark? = marks.lastOrNull { it.row <= row } ?: marks.firstOrNull()
}

/** Headers per day, or per month at the densest zoom ([byMonth]); the scroller's marks per month. */
fun layout(entries: List<GalleryEntry>, byMonth: Boolean): GalleryLayout {
    val rows = ArrayList<GridRow>(entries.size + entries.size / 8 + 1)
    val marks = ArrayList<ScrollMark>()
    var currentKey: Long = Long.MIN_VALUE
    var currentMonth = -1
    var currentYear = Int.MIN_VALUE
    var headerAt = -1
    var count = 0
    fun closeHeader() {
        if (headerAt >= 0) rows[headerAt] = (rows[headerAt] as GridRow.Header).copy(count = count)
    }
    entries.forEachIndexed { i, e ->
        val key = if (byMonth) e.day.year * 100L + e.day.monthValue else e.day.toEpochDay()
        if (key != currentKey) {
            closeHeader()
            currentKey = key
            count = 0
            headerAt = rows.size
            rows += GridRow.Header(if (byMonth) e.day.withDayOfMonth(1) else e.day, byMonth, 0)
            val month = e.day.year * 12 + e.day.monthValue
            if (month != currentMonth) {
                marks += ScrollMark(headerAt, e.day.year, e.day.monthValue, e.day.year != currentYear)
                currentMonth = month
                currentYear = e.day.year
            }
        }
        rows += GridRow.Cell(e, i)
        count++
    }
    closeHeader()
    return GalleryLayout(rows, entries, marks)
}
