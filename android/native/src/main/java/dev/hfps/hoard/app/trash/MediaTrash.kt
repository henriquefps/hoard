package dev.hfps.hoard.app.trash

import dev.hfps.hoard.data.TrashEntryEntity
import dev.hfps.hoard.data.TrashStates
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/*
 * The phone's trash on Android 11+ (ADR 0023 "Phone, API ≥ 30", phase A3): MediaStore's own trash (`IS_TRASHED`,
 * Android deletes after its 30 days), the one the system's Files and Photos apps show. Hoard's index is the only record
 * of what it trashed there (a trashed item has no path of its own). The logic is here over [MediaStoreOps]
 * (JVM-tested with a fake); [AndroidMediaStoreOps] talks to the provider and to the consent dialogs.
 */

/** One MediaStore row of a phone file. */
data class MediaRow(
    /** `content://media/…/<id>`: the typed collection (images, video, audio) when it has one, else `files`. */
    val uri: String,
    /** The path in the phone's storage (relative to `/storage/emulated/0`), as it was before any trash. */
    val path: String,
    /** Hoard owns the row (`OWNER_PACKAGE_NAME`, its `Pictures/Hoard/` copies): it may trash it without asking. */
    val owned: Boolean,
    val trashed: Boolean,
    val size: Long = 0,
    val modifiedMillis: Long? = null,
    /** `DATE_EXPIRES` (Unix ms): when Android deletes a trashed row. */
    val expiresAt: Long? = null,
    /**
     * An image, video or audio row. Android's consent requests take only those ("All requested items must be Media
     * items", checked on the emulator): a sidecar (a plain `MediaStore.Files` row) is changed directly, which All files
     * access allows.
     */
    val isMedia: Boolean = true,
)

/** The three consent requests of MediaStore (`createTrashRequest(…, true / false)`, `createDeleteRequest`). */
enum class MediaRequest { TRASH, RESTORE, DELETE }

/** What the backend needs from MediaStore. Blocking calls run on an IO thread. */
interface MediaStoreOps {
    /** The live (not trashed) row of [path], or null. */
    fun find(path: String): MediaRow?

    /** Scans [path] into MediaStore (a sidecar, a file no app indexed yet) and returns its row. */
    suspend fun scan(path: String): MediaRow?

    /** The row of [uri], trashed or not; null when it is gone (purged by Android, deleted elsewhere). */
    fun get(uri: String): MediaRow?

    /** A file exists at [path] (restore's "the original path is free" check). */
    fun exists(path: String): Boolean

    /** A direct `IS_TRASHED` update; false when the provider refuses it (a row Hoard may not change without consent). */
    fun updateTrashed(uris: List<String>, trashed: Boolean): Boolean

    /** A direct delete; false when refused. */
    fun delete(uris: List<String>): Boolean

    /** Tries a direct update of rows Hoard does not own before asking (All files access may allow it). */
    val tryDirect: Boolean

    /** A consent request for [uris] (at most [MediaTrashLogic.MAX_PER_REQUEST]); true when the user approved. */
    suspend fun request(kind: MediaRequest, uris: List<String>): Boolean
}

object MediaTrashLogic {
    /** URIs per consent request: they travel in the PendingIntent's binder transaction (ADR 0023). */
    const val MAX_PER_REQUEST = 500

    /**
     * Batches of whole units for the consent requests: each batch holds at most [max] files and a unit is never split
     * across two (one consent covers a whole shot), unless a single unit is larger than [max], which then goes alone in
     * as many batches as it needs.
     */
    fun <T> batches(units: List<List<T>>, max: Int = MAX_PER_REQUEST): List<List<List<T>>> {
        val out = ArrayList<List<List<T>>>()
        var current = ArrayList<List<T>>()
        var size = 0
        for (unit in units) {
            if (unit.size > max) {
                if (current.isNotEmpty()) out += current.also { current = ArrayList(); size = 0 }
                unit.chunked(max).forEach { out += listOf(it) }
                continue
            }
            if (size + unit.size > max) {
                out += current
                current = ArrayList()
                size = 0
            }
            current += unit
            size += unit.size
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /** Rows Hoard owns (changed directly) and the others (direct when allowed, else a consent request). */
    fun split(rows: List<MediaRow>): Pair<List<MediaRow>, List<MediaRow>> = rows.partition { it.owned }

    /**
     * The original paths of a restore that are taken now (by a new file at the same path): the unit is refused, never
     * renamed (ADR 0023 "Restore").
     */
    fun taken(originals: List<String>, exists: (String) -> Boolean): List<String> = originals.filter(exists)
}

/**
 * The MediaStore trash as a [TrashBackend]. Rows are written before each batch (`moving`), with the row's URI, and
 * settled after by reading the rows back (a consent can be declined; Android can refuse items).
 */
class MediaStoreTrashBackend(
    private val index: TrashIndex,
    private val ops: MediaStoreOps,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : TrashBackend {
    override val kind = TrashKind.MEDIA_STORE

    /** A trash, restore or delete step on rows: owned ones directly, the others directly when allowed, else asked. */
    private suspend fun apply(kind: MediaRequest, rows: List<MediaRow>): Boolean {
        if (rows.isEmpty()) return true
        val (owned, others) = MediaTrashLogic.split(rows)
        val direct: (List<String>) -> Boolean = { uris ->
            when (kind) {
                MediaRequest.TRASH -> ops.updateTrashed(uris, true)
                MediaRequest.RESTORE -> ops.updateTrashed(uris, false)
                MediaRequest.DELETE -> ops.delete(uris)
            }
        }
        var ok = owned.isEmpty() || direct(owned.map { it.uri })
        val (media, plain) = others.partition { it.isMedia }
        // Sidecars and other plain files: never in a consent request (Android refuses them there); directly only.
        if (plain.isNotEmpty()) ok = direct(plain.map { it.uri }) && ok
        if (media.isNotEmpty()) {
            val uris = media.map { it.uri }
            val done = (ops.tryDirect && direct(uris)) || ops.request(kind, uris)
            ok = ok && done
        }
        return ok
    }

    override suspend fun trash(plan: TrashPlan, cancelled: () -> Boolean, progress: (TrashProgress) -> Unit): TrashReport {
        val outcomes = ArrayList<TrashOutcome>()
        plan.refused.forEach { outcomes += TrashOutcome.Refused(it.unit, it.reason, message = it.message) }
        val runId = newId()
        var filesDone = 0
        var bytesDone = 0L
        var unitsDone = 0
        fun report(name: String?) = progress(TrashProgress(plan.units.size, unitsDone, plan.files, filesDone, plan.bytes, bytesDone, name))
        report(null)
        // Each unit's rows (a file without a row is scanned first: a sidecar no app indexed).
        val resolved = ArrayList<Pair<PlannedUnit, List<MediaRow>>>()
        for (p in plan.units) {
            if (p.unit.all.any { it.isDirectory }) {
                outcomes += TrashOutcome.Refused(p.unit, TrashRefusal.FOLDER_NOT_TRASHABLE)
                continue
            }
            val rows = try {
                p.unit.all.map { f -> ops.find(f.path) ?: ops.scan(f.path) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (rows == null || rows.any { it == null }) {
                outcomes += TrashOutcome.Refused(p.unit, TrashRefusal.NOT_FOUND)
                continue
            }
            resolved += p to rows.filterNotNull()
        }
        for (batch in MediaTrashLogic.batches(resolved.map { (p, rows) -> rows.map { p to it } })) {
            val units = batch.map { it.first().first }.distinct()
            if (cancelled()) {
                units.forEach { outcomes += TrashOutcome.Cancelled(it.unit) }
                continue
            }
            val at = now()
            val entries = batch.flatten().map { (p, row) ->
                val file = p.unit.all.first { it.path == row.path }
                TrashEntryEntity(
                    id = newId(),
                    runId = runId,
                    locationId = plan.place.locationId,
                    share = plan.place.share,
                    trashRoot = "",
                    dayFolder = "",
                    originalPath = row.path,
                    trashedPath = "",
                    shotKey = p.unit.key,
                    isDirectory = false,
                    size = file.size,
                    modifiedMillis = file.modifiedMillis,
                    trashedAt = at,
                    mediaUri = row.uri,
                    state = TrashStates.MOVING,
                )
            }
            index.put(entries)
            val approved = try {
                apply(MediaRequest.TRASH, batch.flatten().map { it.second })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            // Settled by what the rows say now (a declined request, an item Android refused).
            val settled = ArrayList<TrashEntryEntity>()
            val dropped = ArrayList<TrashEntryEntity>()
            for (e in entries) {
                val row = runCatching { ops.get(e.mediaUri!!) }.getOrNull()
                if (row?.trashed == true) settled += e.copy(state = TrashStates.TRASHED, expiresAt = row.expiresAt) else dropped += e
            }
            if (settled.isNotEmpty()) index.put(settled)
            if (dropped.isNotEmpty()) index.delete(dropped.map { it.id })
            for (p in units) {
                val mine = settled.filter { it.shotKey == p.unit.key }
                val missed = dropped.filter { it.shotKey == p.unit.key }
                outcomes += when {
                    missed.isEmpty() -> TrashOutcome.Trashed(p.unit, mine)
                    mine.isEmpty() -> TrashOutcome.Refused(p.unit, if (approved) TrashRefusal.FAILED else TrashRefusal.DECLINED)
                    else -> TrashOutcome.Partial(p.unit, mine, missed.map { it.originalPath }, TrashRefusal.FAILED)
                }
                filesDone += p.unit.all.size
                bytesDone += p.unit.bytes
                unitsDone++
            }
            report(null)
        }
        return TrashReport(plan.place, outcomes)
    }

    override suspend fun restore(entries: List<TrashEntryEntity>): List<RestoreOutcome> {
        val out = ArrayList<RestoreOutcome>()
        val ready = ArrayList<Pair<List<TrashEntryEntity>, List<MediaRow>>>()
        for (unit in entries.filter { it.state == TrashStates.TRASHED && it.mediaUri != null }.groupBy { Triple(it.locationId, it.shotKey, it.runId) }.values) {
            val rows = unit.associateWith { runCatching { ops.get(it.mediaUri!!) }.getOrNull() }
            // Purged by Android, or restored elsewhere (the system's Files app): the rows are forgotten.
            val gone = rows.filter { (_, r) -> r == null || !r.trashed }.keys.toList()
            if (gone.isNotEmpty()) index.delete(gone.map { it.id })
            val left = unit - gone.toSet()
            if (left.isEmpty()) {
                out += RestoreOutcome.Missing(unit)
                continue
            }
            val taken = MediaTrashLogic.taken(left.map { it.originalPath }) { runCatching { ops.exists(it) }.getOrDefault(true) }
            if (taken.isNotEmpty()) {
                out += RestoreOutcome.Conflict(unit, taken)
                continue
            }
            ready += left to left.map { rows.getValue(it)!! }
        }
        for (batch in MediaTrashLogic.batches(ready.map { (rows, media) -> rows.zip(media) })) {
            val flat = batch.flatten()
            index.setState(flat.map { it.first.id }, TrashStates.RESTORING)
            val ok = runCatching { apply(MediaRequest.RESTORE, flat.map { it.second }) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(false)
            for (unit in batch) {
                val back = unit.filter { (e, _) -> runCatching { ops.get(e.mediaUri!!) }.getOrNull()?.trashed == false }.map { it.first }
                val stay = unit.map { it.first } - back.toSet()
                if (back.isNotEmpty()) index.delete(back.map { it.id })
                if (stay.isNotEmpty()) index.setState(stay.map { it.id }, TrashStates.TRASHED)
                out += when {
                    stay.isEmpty() -> RestoreOutcome.Restored(unit.map { it.first })
                    back.isEmpty() -> RestoreOutcome.Failed(unit.map { it.first }, null, if (ok) null else "declined")
                    else -> RestoreOutcome.Partial(unit.map { it.first }, back, stay, null, null)
                }
            }
        }
        return out
    }

    override suspend fun empty(entries: List<TrashEntryEntity>, cancelled: () -> Boolean): List<TrashEntryEntity> {
        val purged = ArrayList<TrashEntryEntity>()
        val live = ArrayList<Pair<TrashEntryEntity, MediaRow>>()
        for (e in entries.filter { it.state == TrashStates.TRASHED && it.mediaUri != null }) {
            val row = runCatching { ops.get(e.mediaUri!!) }.getOrNull()
            when {
                // Already deleted (Android's 30 days, or elsewhere): nothing left to delete.
                row == null -> {
                    index.delete(listOf(e.id))
                    purged += e
                }
                // Restored elsewhere: it is not in the trash any more; Empty does not touch it.
                !row.trashed -> index.delete(listOf(e.id))
                else -> live += e to row
            }
        }
        for (batch in MediaTrashLogic.batches(live.groupBy { it.first.shotKey }.values.toList())) {
            if (cancelled()) break
            val flat = batch.flatten()
            index.setState(flat.map { it.first.id }, TrashStates.PURGING)
            runCatching { apply(MediaRequest.DELETE, flat.map { it.second }) }.onFailure { if (it is CancellationException) throw it }
            for ((e, _) in flat) {
                if (runCatching { ops.get(e.mediaUri!!) }.getOrNull() == null) {
                    index.delete(listOf(e.id))
                    purged += e
                } else {
                    index.setState(listOf(e.id), TrashStates.TRASHED)
                }
            }
        }
        return purged
    }

    /**
     * The Trash screen's refresh and start-up recovery for phone rows: a row Android purged (its 30 days) or the user
     * restored elsewhere is forgotten; an unsettled row is settled by what MediaStore says; expiry dates are updated.
     */
    suspend fun reconcile(rows: List<TrashEntryEntity>) {
        for (e in rows.filter { it.mediaUri != null }) {
            val row = runCatching { ops.get(e.mediaUri!!) }.getOrElse { if (it is CancellationException) throw it else continue }
            when {
                row == null -> index.delete(listOf(e.id))
                row.trashed -> if (e.state != TrashStates.TRASHED || e.expiresAt != row.expiresAt) {
                    index.put(listOf(e.copy(state = TrashStates.TRASHED, expiresAt = row.expiresAt)))
                }
                else -> index.delete(listOf(e.id))
            }
        }
    }
}
