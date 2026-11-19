package dev.hfps.hoard.app.trash

import dev.hfps.hoard.data.TrashEntryEntity
import dev.hfps.hoard.server.api.CompressBatchResult

/**
 * The compression side of the trash (ADR 0025 "Originals: the trash"), as of the server items' cache: which trashed
 * server items are compressed originals ([originals]) and which trash entries an unfinished journal holds ([held]).
 */
class TrashCompressionState(val held: HeldTrash, val originals: List<CompressedOriginal>) {
    val index: CompressedIndex = CompressedTrash.byRow(originals)

    /**
     * What no share-side delete may touch (ADR 0025 Amendment (A1), review of #76): the held entries **and** every
     * compressed original, which only the server deletes (its purge route, its own `trashDays`). Empty's plain rows
     * and auto-empty are given this.
     */
    val protect: HeldTrash = held.plus(originals)

    /** Compressed originals with no trash row of a share Hoard knows: the Trash screen's server section. */
    fun serverOnly(rows: List<TrashEntryEntity>): List<CompressedOriginal> {
        val matched = rows.mapNotNull { CompressedTrash.of(it, index)?.itemId }.toSet()
        return originals.filter { it.itemId !in matched }
    }

    companion object {
        val NONE = TrashCompressionState(HeldTrash.NONE, emptyList())
    }
}

/**
 * Rows of a restore or an Empty split three ways (ADR 0025): [held] rows are left alone (Empty never deletes them);
 * rows of a compressed original go through the server's routes ([compressed], by the original's item); the rest is the
 * ordinary trash ([plain]).
 */
data class TrashSplit(
    val held: List<TrashEntryEntity>,
    val compressed: Map<CompressedOriginal, List<TrashEntryEntity>>,
    val plain: List<TrashEntryEntity>,
) {
    companion object {
        fun of(rows: List<TrashEntryEntity>, state: TrashCompressionState): TrashSplit {
            val held = ArrayList<TrashEntryEntity>()
            val compressed = LinkedHashMap<CompressedOriginal, MutableList<TrashEntryEntity>>()
            val plain = ArrayList<TrashEntryEntity>()
            for (row in rows) {
                val original = CompressedTrash.of(row, state.index)
                when {
                    state.held.holdsRow(row) || original?.held == true -> held += row
                    original != null -> compressed.getOrPut(original) { ArrayList() } += row
                    else -> plain += row
                }
            }
            return TrashSplit(held, compressed, plain)
        }

    }
}

/** `POST /v1/compress/restore` and `…/purge` (by gallery item id), as the Trash screen uses them. */
interface CompressRoutes {
    suspend fun restore(itemIds: List<String>): Map<String, CompressBatchResult>

    suspend fun purge(itemIds: List<String>): Map<String, CompressBatchResult>
}

/** What a batch of route calls did, per original's item id. */
data class CompressRouteOutcome(val done: List<String>, val failed: Map<String, String>, val held: List<String>) {
    companion object {
        val EMPTY = CompressRouteOutcome(emptyList(), emptyMap(), emptyList())
    }
}

/**
 * Restore and Delete permanently of compressed originals through the server (ADR 0025): restore undoes the compression
 * (the original back at its path, the output into the trash); purge empties the trashed file. A held original is never
 * sent to purge; the server's own answer decides the rest (`state` for one that is busy).
 */
class CompressedTrashActions(private val routes: suspend () -> CompressRoutes?, private val batch: Int = 1000) {
    suspend fun restore(originals: List<CompressedOriginal>): CompressRouteOutcome = call(originals, purge = false)

    suspend fun purge(originals: List<CompressedOriginal>, held: HeldTrash): CompressRouteOutcome {
        val (kept, free) = originals.partition { it.held || it.itemId in held.serverItems }
        val out = call(free, purge = true)
        return out.copy(held = out.held + kept.map { it.itemId })
    }

    private suspend fun call(originals: List<CompressedOriginal>, purge: Boolean): CompressRouteOutcome {
        val ids = originals.map { it.itemId }.distinct()
        if (ids.isEmpty()) return CompressRouteOutcome.EMPTY
        val api = routes() ?: return CompressRouteOutcome(emptyList(), ids.associateWith { UNREACHABLE }, emptyList())
        val done = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        for (chunk in ids.chunked(batch)) {
            val answer = try {
                if (purge) api.purge(chunk) else api.restore(chunk)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                chunk.forEach { failed[it] = (e as? dev.hfps.hoard.server.api.ServerException)?.let { s -> s.reason ?: s.code } ?: UNREACHABLE }
                continue
            }
            for (id in chunk) {
                val r = answer[id]
                val error = r?.error
                when {
                    r == null -> failed[id] = "not_found"
                    error != null -> failed[id] = error
                    else -> done += id
                }
            }
        }
        return CompressRouteOutcome(done, failed, emptyList())
    }

    companion object {
        const val UNREACHABLE = "unreachable"
    }
}

/** What [emptyTrash] did: share rows deleted, rows of originals the server purged, and the server's answers. */
data class TrashEmptyResult(val deleted: List<TrashEntryEntity>, val routed: List<TrashEntryEntity>, val split: TrashSplit, val server: CompressRouteOutcome)

/**
 * Empty and Delete permanently (ADR 0025 Amendment (A1), review of #76): held rows are left; plain rows are deleted on
 * their share with [TrashCompressionState.protect] (so no compressed original can go that way, whatever matched);
 * compressed originals go only to the server's purge route, and any refusal (no write mount, a failed safety check,
 * `output_missing`, a busy journal) leaves them where they are.
 */
suspend fun emptyTrash(
    rows: List<TrashEntryEntity>,
    state: TrashCompressionState,
    serverOnly: List<CompressedOriginal>,
    actions: CompressedTrashActions,
    deleteOnShare: suspend (List<TrashEntryEntity>, HeldTrash) -> List<TrashEntryEntity>,
): TrashEmptyResult {
    val split = TrashSplit.of(rows, state)
    val deleted = deleteOnShare(split.plain, state.protect)
    val server = actions.purge(split.compressed.keys.toList() + serverOnly, state.held)
    val routed = split.compressed.filterKeys { it.itemId in server.done }.values.flatten()
    return TrashEmptyResult(deleted, routed, split, server)
}
