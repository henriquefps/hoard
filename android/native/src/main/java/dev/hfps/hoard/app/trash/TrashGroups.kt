package dev.hfps.hoard.app.trash

import dev.hfps.hoard.data.TrashEntryEntity
import dev.hfps.hoard.data.TrashStates
import dev.hfps.hoard.server.paths.SharePaths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The Trash screen's rows of one folder of one place on one day (ADR 0023 "Trash screen"). */
data class TrashFolderGroup(
    val place: TrashPlace,
    /** The place as the eyebrow shows it: `machine › share`, the drive's name, the phone. */
    val placeLabel: String,
    /** The original folder (in the place). */
    val folder: String,
    val rows: List<TrashEntryEntity>,
    /** False when the place could not be reached at the last refresh: shown greyed, "Not reachable". */
    val reachable: Boolean,
    /** Ids of rows an unfinished compression journal holds (ADR 0025): listed, never emptied, not counted. */
    val held: Set<String> = emptySet(),
    /** Row id → the compressed original it is a file of (ADR 0025: restored and emptied through the server). */
    val compressed: Map<String, CompressedOriginal> = emptyMap(),
) {
    /** The rows Empty and Delete permanently can take (every row but the held ones). */
    val freeRows: List<TrashEntryEntity> get() = rows.filter { it.id !in held }
    val heldRows: List<TrashEntryEntity> get() = rows.filter { it.id in held }
    val files: Int get() = freeRows.size
    val bytes: Long get() = freeRows.sumOf { it.size }
    val shots: Int get() = rows.map { it.shotKey }.distinct().size

    /** Compressed originals in this folder (not held), each with its rows. */
    val originals: List<Pair<CompressedOriginal, List<TrashEntryEntity>>>
        get() = freeRows.filter { it.id in compressed }.groupBy { compressed.getValue(it.id) }.toList()

    /** The phone's trash: when Android deletes the first of these files (MediaStore's `DATE_EXPIRES`). */
    val expiresAt: Long? get() = rows.mapNotNull { it.expiresAt }.minOrNull()
}

/** One day of the trash (the day folder's date; MediaStore entries by their trash time). */
data class TrashDayGroup(val date: LocalDate?, val folders: List<TrashFolderGroup>) {
    val rows: List<TrashEntryEntity> get() = folders.flatMap { it.rows }
    val files: Int get() = folders.sumOf { it.files }
    val bytes: Long get() = folders.sumOf { it.bytes }
    val reachable: Boolean get() = folders.any { it.reachable }
}

object TrashGroups {
    /**
     * Groups the index for the Trash screen: by day (newest first), then by place and folder. Only settled rows
     * (`trashed`) are listed: the others are being moved, restored or purged right now.
     */
    fun of(
        rows: List<TrashEntryEntity>,
        unreachable: Set<TrashPlace>,
        label: (TrashPlace) -> String,
        zone: ZoneId = ZoneId.systemDefault(),
        compression: TrashCompressionState = TrashCompressionState.NONE,
    ): List<TrashDayGroup> {
        val settled = rows.filter { it.state == TrashStates.TRASHED }
        val split = TrashSplit.of(settled, compression)
        val held = split.held.map { it.id }.toHashSet()
        val compressed = split.compressed.flatMap { (o, r) -> r.map { it.id to o } }.toMap()
        return settled
            .groupBy { TrashPaths.dateOf(it.dayFolder) ?: if (it.dayFolder.isEmpty()) Instant.ofEpochMilli(it.trashedAt).atZone(zone).toLocalDate() else null }
            .entries
            .sortedWith(compareByDescending<Map.Entry<LocalDate?, List<TrashEntryEntity>>> { it.key ?: LocalDate.MIN })
            .map { (date, inDay) ->
                val folders = inDay
                    .groupBy { Triple(TrashPlace(it.locationId, it.share), it.locationId, SharePaths.parent(it.originalPath)) }
                    .map { (key, inFolder) ->
                        TrashFolderGroup(
                            place = key.first,
                            placeLabel = label(key.first),
                            folder = key.third,
                            rows = inFolder.sortedBy { it.originalPath.lowercase() },
                            reachable = key.first !in unreachable,
                            held = inFolder.filter { it.id in held }.map { it.id }.toSet(),
                            compressed = inFolder.mapNotNull { r -> compressed[r.id]?.let { r.id to it } }.toMap(),
                        )
                    }
                    .sortedWith(compareBy({ it.placeLabel.lowercase() }, { it.folder.lowercase() }))
                TrashDayGroup(date, folders)
            }
    }

    /** The first restore conflict's path, for the message (null when none). */
    fun firstConflict(outcomes: List<RestoreOutcome>): String? =
        outcomes.filterIsInstance<RestoreOutcome.Conflict>().firstOrNull()?.paths?.firstOrNull()

    /** Files back at their place. */
    fun restoredFiles(outcomes: List<RestoreOutcome>): Int = outcomes.sumOf { o ->
        when (o) {
            is RestoreOutcome.Restored -> o.entries.size
            is RestoreOutcome.Partial -> o.restored.size
            else -> 0
        }
    }
}
