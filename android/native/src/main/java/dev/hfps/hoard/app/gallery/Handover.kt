package dev.hfps.hoard.app.gallery

import dev.hfps.hoard.data.CullDecisionEntity
import dev.hfps.hoard.data.CullSidecarEntity
import dev.hfps.hoard.server.db.DevicePushedEntity
import dev.hfps.hoard.server.sync.CullValues

/**
 * The hand-over of a phone shot's decision to the server once the shot is there (ADR 0021 "Culling everywhere"),
 * pure and JVM-tested (`HandoverTest`). A phone shot keeps its decision in its folder's `cull_decisions` row (the Files
 * tab's culling writes the same row): the row's `updated_at` is when it was decided (a sidecar import's mtime, ADR 0016
 * rule 4). An unmark deletes the row, so its time comes from the folder's `cull_sidecars` row, which outlives it
 * (`decided_at`, written by every decision of the culling screen, or `sidecar_mtime` for a clear imported from a
 * sidecar): a clear made after the last hand-over is handed over too, with that time.
 */
object Handover {
    /** What to queue for the server: [values] dated [at]; [clears] also sends fields set back to empty. */
    data class Promotion(val values: CullValues, val at: Long, val clears: Boolean)

    /** One shot's outcome: what to queue (null = nothing), and the hand-over time to remember. */
    data class Step(val promotion: Promotion?, val pushedAt: Long)

    fun millisOf(iso: String?): Long? = iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** When a shot whose row is gone was cleared (null when nothing dates it). */
    fun clearedAt(sidecar: CullSidecarEntity?): Long? {
        sidecar ?: return null
        return listOfNotNull(sidecar.decidedAt, sidecar.sidecarMtime.takeIf { sidecar.synced == 1 }).maxOrNull()
    }

    /**
     * [itemId]: the server item the shot now belongs to; [row] its folder's decision (null = unmarked); [sidecar] its
     * folder's sidecar row; [before] the last hand-over; null when there is nothing to do.
     */
    fun step(itemId: String, row: CullDecisionEntity?, sidecar: CullSidecarEntity?, before: DevicePushedEntity?, now: Long): Step? {
        val at = if (row != null) millisOf(row.updatedAt) ?: 0L else clearedAt(sidecar) ?: 0L
        // Handed over before and not decided (or cleared) again since: nothing to do.
        if (before != null && before.itemId == itemId && at <= before.pushedAt) return null
        val promotion = when {
            row != null && (row.flag != null || row.rating > 0 || before != null) ->
                Promotion(CullValues(row.rating.coerceIn(0, 5), row.flag), at.takeIf { it > 0 } ?: now, clears = before != null)
            // Unmarked after a hand-over (the Files tab deletes the row): the clear goes to the server with its time.
            row == null && before != null && at > 0 -> Promotion(CullValues(), at, clears = true)
            else -> null
        }
        return Step(promotion, maxOf(now, at))
    }
}
