package dev.hfps.hoard.server.trash

import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.ServerException
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.api.TrashActionAnswer
import dev.hfps.hoard.server.api.TrashEntryView
import dev.hfps.hoard.server.api.TrashFigures
import dev.hfps.hoard.server.api.TrashGroupIds
import dev.hfps.hoard.server.api.TrashMoveAnswer
import dev.hfps.hoard.server.api.TrashPage
import dev.hfps.hoard.server.api.TrashUnitRef
import dev.hfps.hoard.server.api.TrashUnitView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/*
 * Server photos in the trash, through the server (hoard-server 0.15.0, feature `trash-actions`; ADR 0023 Amendment
 * (2.10.0)): Hoard never trashes, restores or empties a photo the server indexes over SMB. This is the client's model:
 * the dry run and the move in batches of 500 with per-item results and Retry, the listing grouped by day and place,
 * Restore and Delete permanently by unit, Empty by group after confirming each group's figures (the server's 409 with
 * the current figures), and the units Hoard's auto-empty may ask the server to delete. Pure over [TrashRoutes];
 * JVM-tested against a fake server (`ServerTrashTest`).
 */

/** The `/v1/trash` routes (blocking; [ServerTrash] calls them on IO). */
interface TrashRoutes {
    fun list(cursor: String?): TrashPage

    fun move(itemIds: List<String>, dryRun: Boolean): TrashMoveAnswer

    fun restore(units: List<TrashUnitRef>): TrashActionAnswer

    fun delete(units: List<TrashUnitRef>): TrashActionAnswer

    fun empty(confirm: TrashFigures, groups: List<String>?): TrashActionAnswer
}

class ApiTrashRoutes(private val api: ServerApi) : TrashRoutes {
    override fun list(cursor: String?) = api.trashList(cursor)
    override fun move(itemIds: List<String>, dryRun: Boolean) = api.trashMove(itemIds, dryRun)
    override fun restore(units: List<TrashUnitRef>) = api.trashRestore(units)
    override fun delete(units: List<TrashUnitRef>) = api.trashDelete(units)
    override fun empty(confirm: TrashFigures, groups: List<String>?) = api.trashEmpty(confirm, groups)
}

/** Reasons Hoard adds to the server's: the request did not get an answer. */
object TrashReasons {
    /** The server could not be reached (or answered something else than the contract). */
    const val UNREACHABLE = "unreachable"

    /** Cancelled before its batch was sent. */
    const val CANCELLED = "cancelled"

    /** 429 after the retries. */
    const val RATE_LIMITED = "rate_limited"

    /** A held unit (a compression journal holds it): never sent to delete or empty. */
    const val HELD = "held"

    /** A backup-only unit: a device may restore it, only the web gallery deletes it (server-0150b). */
    const val WEB_ONLY = "web_only"

    /** `403 forbidden` for a device that is not a Hoard (server-0150b). */
    const val NOT_HOARD = "not_hoard"

    /** The write mount's problems (`503 unavailable`, `nasBlocked`). */
    val WRITE_MOUNT = setOf("no_write_mount", "write_mount_mismatch", "write_mount_read_only", "unstable_inode")
}

/** What the dry run says the move would do (every batch's answer added up). */
data class MovePreview(
    val asked: Int,
    val nasShots: Int,
    val nasFiles: Int,
    val serverItems: Int,
    val size: Long,
    /** Items whose only copy is this one (emptying the trash deletes them for good). */
    val onlyCopy: Set<String>,
    /** Items with Reveal's edits (they come back on a restore, go at the purge). */
    val edits: Set<String>,
    /** item id → `not_found`, `trashed`, `held`, `busy`, `on_phone`, `nothing`. */
    val skipped: Map<String, String>,
    /** The write mount's problem: NAS shots cannot move now. */
    val nasBlocked: String?,
) {
    /** Items the move would take (not skipped). */
    val movable: Int get() = asked - skipped.size

    fun skippedBy(): Map<String, Int> = skipped.values.groupingBy { it }.eachCount()
}

/** What the move did, per item. */
data class MoveResult(
    val moved: List<String>,
    val skipped: Map<String, String>,
    val failed: Map<String, String>,
) {
    val failedBy: Map<String, Int> get() = failed.values.groupingBy { it }.eachCount()
    val skippedBy: Map<String, Int> get() = skipped.values.groupingBy { it }.eachCount()

    /** The ids "Retry the failed ones" sends again (every failure: the server decides again). */
    val retryable: List<String> get() = failed.keys.toList()

    /** This result with [retry]'s answers for the ids it retried. */
    fun merge(retry: MoveResult): MoveResult {
        val again = (retry.moved + retry.skipped.keys + retry.failed.keys).toSet()
        return MoveResult(
            moved + retry.moved,
            skipped + retry.skipped,
            failed.filterKeys { it !in again } + retry.failed,
        )
    }

    companion object {
        val EMPTY = MoveResult(emptyList(), emptyMap(), emptyMap())
    }
}

/** What a Restore or Delete permanently did, per unit (`refused` by [TrashUnitRef.refKey]). */
data class UnitsResult(
    val done: List<TrashUnitRef>,
    val refused: Map<String, String>,
    /** path_taken: the file in the way. */
    val names: Map<String, String>,
    val size: Long,
) {
    val refusedBy: Map<String, Int> get() = refused.values.groupingBy { it }.eachCount()

    operator fun plus(o: UnitsResult) = UnitsResult(done + o.done, refused + o.refused, names + o.names, size + o.size)

    companion object {
        val EMPTY = UnitsResult(emptyList(), emptyMap(), emptyMap(), 0)
    }
}

/** What Empty did. */
sealed interface EmptyResult {
    data class Done(val result: UnitsResult) : EmptyResult

    /** The trash changed since the figures were shown (`409 conflict` reason `confirm`): these are its figures now. */
    data class Conflict(val groups: Map<String, TrashFigures>, val webOnly: TrashFigures? = null) : EmptyResult

    data class Failed(val reason: String) : EmptyResult
}

/** The whole trash as the server lists it, and its groups' figures (from the first page). */
data class ServerTrashListing(
    val entries: List<TrashEntryView>,
    val groups: Map<String, TrashFigures>,
    /** Backup-only units Empty leaves: only the web gallery deletes them. */
    val webOnly: TrashFigures? = null,
) {
    fun figures(group: String): TrashFigures = groups[group] ?: TrashFigures.ZERO

    companion object {
        val EMPTY = ServerTrashListing(emptyList(), emptyMap())
    }
}

/** One unit of one entry: a line of the Trash screen. */
data class ServerTrashLine(val entry: TrashEntryView, val unit: TrashUnitView) {
    val ref: TrashUnitRef get() = TrashUnitRef(entry.id, unit.key)
    val held: Boolean get() = unit.held || entry.held && unit.group == TrashGroupIds.COMPRESSED

    /** Restore only: only the web gallery deletes it. */
    val webOnly: Boolean get() = !unit.deviceMayDelete
}

/** One place of one day: a NAS folder and directory, or a phone's backup and directory. */
data class ServerTrashPlace(
    val source: String,
    /** The NAS folder's name, or the phone's name (server trash). */
    val name: String,
    val dir: String,
    val lines: List<ServerTrashLine>,
) {
    val free: List<ServerTrashLine> get() = lines.filter { !it.held }
    val heldLines: List<ServerTrashLine> get() = lines.filter { it.held }

    /** What Delete permanently may take here (not held, not backup-only). */
    val deletable: List<ServerTrashLine> get() = free.filter { !it.webOnly }

    /** Shots (items) that are not held, and their bytes. */
    val shots: Int get() = free.map { it.entry.id }.distinct().size
    val bytes: Long get() = free.sumOf { it.unit.size }
}

/** One day of the server's trash ([day]: yyyy-MM-dd, the day folder's date or the server trash's). */
data class ServerTrashDay(val day: String, val places: List<ServerTrashPlace>) {
    val free: List<ServerTrashLine> get() = places.flatMap { it.free }
    val deletable: List<ServerTrashLine> get() = places.flatMap { it.deletable }
    val shots: Int get() = places.sumOf { it.shots }
    val bytes: Long get() = places.sumOf { it.bytes }
}

object ServerTrash {
    const val BATCH = 500
    const val MAX_PAGES = 1000
    const val MAX_RATE_RETRIES = 3
    private const val MAX_WAIT_S = 120L

    // --- Move ------------------------------------------------------------------------------------------------------

    /** The dry run of [ids] (in batches of [BATCH]). Throws when the server cannot be reached. */
    suspend fun dryRun(routes: TrashRoutes, ids: List<String>, sleep: suspend (Long) -> Unit = { delay(it) }): MovePreview {
        val distinct = ids.distinct()
        var nasShots = 0
        var nasFiles = 0
        var serverItems = 0
        var size = 0L
        val onlyCopy = LinkedHashSet<String>()
        val edits = LinkedHashSet<String>()
        val skipped = LinkedHashMap<String, String>()
        var blocked: String? = null
        for (chunk in distinct.chunked(BATCH)) {
            val a = call(sleep) { routes.move(chunk, dryRun = true) }
            nasShots += a.nasShots
            nasFiles += a.nasFiles
            serverItems += a.serverItems
            size += a.size
            onlyCopy += a.warnings.onlyCopy
            edits += a.warnings.edits
            skipped += a.skipped
            if (blocked == null) blocked = a.nasBlocked?.ifEmpty { null }
        }
        return MovePreview(distinct.size, nasShots, nasFiles, serverItems, size, onlyCopy, edits, skipped, blocked)
    }

    /**
     * Moves [ids] (batches of [BATCH]): per item moved, skipped or failed. A batch the server refuses with `503
     * unavailable` (the write mount) fails its items with that reason; one it cannot be asked fails them
     * [TrashReasons.UNREACHABLE]; a `429` waits its `Retry-After` (a few times). [cancelled] is checked between batches
     * (the rest fail [TrashReasons.CANCELLED]: nothing of them moved).
     */
    suspend fun move(
        routes: TrashRoutes,
        ids: List<String>,
        cancelled: () -> Boolean = { false },
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): MoveResult {
        val distinct = ids.distinct()
        val moved = ArrayList<String>()
        val skipped = LinkedHashMap<String, String>()
        val failed = LinkedHashMap<String, String>()
        var done = 0
        progress(0, distinct.size)
        for (chunk in distinct.chunked(BATCH)) {
            if (cancelled()) {
                chunk.forEach { failed[it] = TrashReasons.CANCELLED }
                continue
            }
            try {
                val a = call(sleep) { routes.move(chunk, dryRun = false) }
                moved += a.moved
                skipped += a.skipped
                failed += a.failed
                // An id the server did not answer for (it should not happen): failed, so Retry sends it again.
                for (id in chunk) if (id !in a.skipped && id !in a.failed && id !in a.moved) failed[id] = "error"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = reasonOf(e)
                chunk.forEach { failed[it] = reason }
            }
            done += chunk.size
            progress(done, distinct.size)
        }
        return MoveResult(moved, skipped, failed)
    }

    // --- Listing ---------------------------------------------------------------------------------------------------

    /** Every page of the trash. Throws when the server cannot be reached. */
    suspend fun list(routes: TrashRoutes, sleep: suspend (Long) -> Unit = { delay(it) }): ServerTrashListing {
        val entries = ArrayList<TrashEntryView>()
        var groups: Map<String, TrashFigures> = emptyMap()
        var webOnly: TrashFigures? = null
        var cursor: String? = null
        val seen = HashSet<String>()
        for (page in 0 until MAX_PAGES) {
            val p = call(sleep) { routes.list(cursor) }
            if (page == 0) {
                groups = p.groups.orEmpty()
                webOnly = p.webOnly
            }
            for (e in p.items) if (seen.add(e.id)) entries += e
            val next = p.cursor
            if (!p.hasMore || next.isNullOrEmpty() || next == cursor) break
            cursor = next
        }
        return ServerTrashListing(entries, groups, webOnly)
    }

    /**
     * The Trash screen's server part: by day (newest first), then place (NAS folder + original directory, or the
     * phone's backup + directory), each line one unit. Held units are listed (never deletable).
     */
    fun days(listing: ServerTrashListing): List<ServerTrashDay> {
        val lines = listing.entries.flatMap { e -> e.units.map { ServerTrashLine(e, it) } }
        return lines.groupBy { it.unit.day }.map { (day, inDay) ->
            val places = inDay.groupBy { Triple(it.unit.source, placeName(it.unit), it.unit.dir) }
                .map { (k, l) -> ServerTrashPlace(k.first, k.second, k.third, l.sortedBy { it.entry.name.lowercase() }) }
                .sortedWith(compareBy({ it.name.lowercase() }, { it.dir.lowercase() }))
            ServerTrashDay(day, places)
        }.sortedByDescending { it.day }
    }

    private fun placeName(u: TrashUnitView): String = when (u.source) {
        "nas" -> u.folderName ?: u.folderId.orEmpty()
        else -> u.device.orEmpty()
    }

    // --- Restore, Delete permanently, Empty -------------------------------------------------------------------------

    suspend fun restore(routes: TrashRoutes, units: List<TrashUnitRef>, sleep: suspend (Long) -> Unit = { delay(it) }): UnitsResult =
        units(units, sleep) { routes.restore(it) }

    /**
     * Deletes [lines] for good; held ones (refused [TrashReasons.HELD]) and backup-only ones (refused
     * [TrashReasons.WEB_ONLY]: only the web gallery deletes them) are never sent.
     */
    suspend fun delete(routes: TrashRoutes, lines: List<ServerTrashLine>, sleep: suspend (Long) -> Unit = { delay(it) }): UnitsResult {
        val (held, rest) = lines.partition { it.held }
        val (webOnly, free) = rest.partition { it.webOnly }
        val r = units(free.map { it.ref }.distinct(), sleep) { routes.delete(it) }
        return r.copy(refused = r.refused + held.associate { it.ref.refKey to TrashReasons.HELD } + webOnly.associate { it.ref.refKey to TrashReasons.WEB_ONLY })
    }

    private suspend fun units(refs: List<TrashUnitRef>, sleep: suspend (Long) -> Unit, send: (List<TrashUnitRef>) -> TrashActionAnswer): UnitsResult {
        var out = UnitsResult.EMPTY
        for (chunk in refs.distinct().chunked(BATCH)) {
            out += try {
                val a = call(sleep) { send(chunk) }
                UnitsResult(a.done, a.refused, a.names, a.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = reasonOf(e)
                UnitsResult(emptyList(), chunk.associate { it.refKey to reason }, emptyMap(), 0)
            }
        }
        return out
    }

    /**
     * The figures Empty confirms for [chosen] groups as [shown] gave them. A shot with units in two groups counts in
     * each group's `count` but once in the server's total: when the server's 409 says every chosen group still has
     * the figures shown, its own total is the one the owner saw, and the request is sent once more with it.
     */
    fun confirmOf(shown: Map<String, TrashFigures>, chosen: Collection<String>): TrashFigures =
        chosen.distinct().fold(TrashFigures.ZERO) { acc, g -> acc + (shown[g] ?: TrashFigures.ZERO) }

    /**
     * Empties [chosen] groups (the owner's per-group confirmation), only if they still hold [shown]'s figures; a group
     * left out stays. The server never empties a held unit.
     */
    suspend fun empty(routes: TrashRoutes, shown: Map<String, TrashFigures>, chosen: Collection<String>, sleep: suspend (Long) -> Unit = { delay(it) }): EmptyResult {
        val groups = TrashGroupIds.ALL.filter { it in chosen }
        if (groups.isEmpty()) return EmptyResult.Done(UnitsResult.EMPTY)
        var confirm = confirmOf(shown, groups)
        for (attempt in 0..1) {
            try {
                val a = call(sleep) { routes.empty(confirm, groups) }
                return EmptyResult.Done(UnitsResult(a.done, a.refused, a.names, a.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerException) {
                if (e.status != 409 || e.reason != "confirm") return EmptyResult.Failed(reasonOf(e))
                val now = figuresOf(e.details)
                val unchanged = groups.all { (now[it] ?: TrashFigures.ZERO) == (shown[it] ?: TrashFigures.ZERO) }
                val total = TrashFigures(e.detailLong("count")?.toInt() ?: -1, e.detailLong("size") ?: -1)
                if (attempt == 0 && unchanged && total.count >= 0 && total != confirm) {
                    confirm = total
                    continue
                }
                return EmptyResult.Conflict(now, webOnlyOf(e.details))
            } catch (e: Exception) {
                return EmptyResult.Failed(reasonOf(e))
            }
        }
        return EmptyResult.Failed("error")
    }

    /** `details.webOnly` of a 409 confirm (server-0150b). */
    fun webOnlyOf(details: JsonObject?): TrashFigures? =
        (details?.get("webOnly") as? JsonObject)?.let { runCatching { ServerJson.decodeFromJsonElement(TrashFigures.serializer(), it) }.getOrNull() }

    /** `details.groups` of a 409 confirm. */
    fun figuresOf(details: JsonObject?): Map<String, TrashFigures> {
        val g = details?.get("groups") as? JsonObject ?: return emptyMap()
        return g.mapNotNull { (k, v) -> runCatching { k to ServerJson.decodeFromJsonElement(TrashFigures.serializer(), v) }.getOrNull() }.toMap()
    }

    // --- Auto-empty ------------------------------------------------------------------------------------------------

    /**
     * What Hoard's auto-empty asks the server to delete (ADR 0023 Amendment (2.10.0)): NAS units the server did not
     * move (`by` absent: Hoard's moves over SMB before 0.15.0, which the server's own auto-empty never takes) whose day
     * is on or before [limit] (yyyy-MM-dd: today minus the setting's days, as for Hoard's own day folders), never held
     * ones nor compressed originals. Everything the server moved is its own auto-empty's (no double deletion).
     */
    fun autoEmptyLines(listing: ServerTrashListing, limit: String): List<ServerTrashLine> {
        if (!ISO_DAY.matches(limit)) return emptyList()
        return listing.entries.filter { !it.live && it.compressedTo == null }.flatMap { e ->
            e.units.filter { u ->
                u.source == "nas" && u.by == null && !u.byServer && !u.held && u.group == TrashGroupIds.HOARD &&
                    ISO_DAY.matches(u.day) && u.day <= limit
            }.map { ServerTrashLine(e, it) }
        }
    }

    private val ISO_DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    // --- Plumbing --------------------------------------------------------------------------------------------------

    /** Why a call failed, as a reason the screens map: the server's `details.reason` or code, else unreachable. */
    fun reasonOf(e: Throwable): String = when (e) {
        is ServerException -> when {
            e.status == 503 && e.reason != null -> e.reason
            e.status == 429 -> TrashReasons.RATE_LIMITED
            else -> e.reason ?: e.code
        }
        is IOException -> TrashReasons.UNREACHABLE
        is kotlinx.serialization.SerializationException -> TrashReasons.UNREACHABLE
        else -> TrashReasons.UNREACHABLE
    }

    /** [block] on IO; a `429` waits its `Retry-After` (at most [MAX_RATE_RETRIES] times, [MAX_WAIT_S] each). */
    private suspend fun <T> call(sleep: suspend (Long) -> Unit, block: () -> T): T {
        var retries = 0
        while (true) {
            try {
                return withContext(Dispatchers.IO) { block() }
            } catch (e: ServerException) {
                if (e.status != 429 || retries >= MAX_RATE_RETRIES) throw e
                retries++
                sleep((e.retryAfterSeconds ?: 10L).coerceIn(1, MAX_WAIT_S) * 1000)
            }
        }
    }
}

/**
 * The server's trash by NAS folder and `.hoard-trash/<day>/<path>` tail (lower-case): a file Hoard moved into a
 * `.hoard-trash` over SMB is the same file the server lists (the share's trash root is the NAS folder's `<P>`, so the
 * paths from `.hoard-trash` on are equal). Hoard's SMB rows of server folders that it covers are not shown twice.
 */
class ServerTrashIndex(listing: ServerTrashListing) {
    private val byFolder: Map<String, Map<String, String>>
    private val anyFolder: Map<String, String>

    init {
        val map = HashMap<String, HashMap<String, String>>()
        val all = HashMap<String, String>()
        for (e in listing.entries) {
            val folders = e.units.filter { it.source == "nas" }.mapNotNull { it.folderId }.toSet()
            for (p in e.places) {
                val tail = tailOf(p.trash ?: continue) ?: continue
                all.putIfAbsent(tail, e.id)
                for (f in folders) map.getOrPut(f) { HashMap() }.putIfAbsent(tail, e.id)
            }
        }
        byFolder = map
        anyFolder = all
    }

    /** The server item whose file Hoard trashed at [trashedPath] (a share path) of NAS folder [folderId], or null. */
    fun coverOf(folderId: String?, trashedPath: String): String? {
        val tail = tailOf(trashedPath) ?: return null
        return if (folderId != null) byFolder[folderId]?.get(tail) else anyFolder[tail]
    }

    /** Whether the server lists the file Hoard trashed at [trashedPath] (a share path) of NAS folder [folderId]. */
    fun covers(folderId: String?, trashedPath: String): Boolean = coverOf(folderId, trashedPath) != null

    companion object {
        const val DIR = ".hoard-trash"

        /** `<…>/.hoard-trash/<day>/<path>` → `.hoard-trash/<day>/<path>` (lower-case); null outside a trash. */
        fun tailOf(path: String): String? {
            val segments = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.map { it.lowercase() }
            val at = segments.indexOf(DIR)
            if (at < 0 || at + 2 >= segments.size) return null
            return segments.drop(at).joinToString("/")
        }
    }
}
