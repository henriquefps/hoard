package dev.hfps.hoard.server.trash

import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.api.TrashActionAnswer
import dev.hfps.hoard.server.api.TrashEmptyRequest
import dev.hfps.hoard.server.api.TrashEntryView
import dev.hfps.hoard.server.api.TrashFigures
import dev.hfps.hoard.server.api.TrashGroupIds
import dev.hfps.hoard.server.api.TrashMoveAnswer
import dev.hfps.hoard.server.api.TrashMoveRequest
import dev.hfps.hoard.server.api.TrashPage
import dev.hfps.hoard.server.api.TrashPlaceView
import dev.hfps.hoard.server.api.TrashUnitRef
import dev.hfps.hoard.server.api.TrashUnitView
import dev.hfps.hoard.server.api.TrashUnitsRequest
import dev.hfps.hoard.server.api.TrashWarnings
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * hoard-server 0.15.0's `/v1/trash` for the client's tests (ADR 0023 Amendment (0.15.0)): live items that a move puts
 * in the trash as a unit `by` this device, trashed entries with their units and groups, restore and delete by unit
 * (held refused, path_taken with its name), Empty by group behind the confirm figures (409 with the current figures),
 * the write mount's 503, injected per-item failures and a 429.
 */
class FakeTrash(private val server: FakeServer, var today: String = "2026-11-27") {
    /** A live NAS item a move may trash: its folder, directory, name and size. */
    data class Live(val id: String, val folderId: String, val dir: String, val name: String, val size: Long, val files: Int = 1, val skip: String? = null, val onlyCopy: Boolean = false, val edits: Boolean = false)

    val live = LinkedHashMap<String, Live>()
    val trashed = LinkedHashMap<String, TrashEntryView>()

    /** item id → failure reasons the next moves answer (one per move). */
    val failNext = HashMap<String, ArrayDeque<String>>()

    /** The write mount's problem: moves with NAS shots answer 503, dry runs `nasBlocked`. */
    var writeProblem: String? = null

    /** unit refKey → the restore refusal (`path_taken` with a name). */
    val restoreRefused = HashMap<String, String>()

    /** Answer this many requests with 429 (Retry-After 1). */
    var rateLimited = 0

    /** Answer every route `403 forbidden` reason `not_hoard` (a device that is not a Hoard, server-0150b). */
    var notHoard = false

    /** Items per page of `GET /v1/trash`. */
    var pageSize = 2

    val moves = ArrayList<TrashMoveRequest>()
    val deletes = ArrayList<List<TrashUnitRef>>()
    val restores = ArrayList<List<TrashUnitRef>>()
    val empties = ArrayList<TrashEmptyRequest>()

    init {
        val previous = server.extra
        server.extra = { ex, path, query, body -> handle(ex, path, query, body) || previous?.invoke(ex, path, query, body) == true }
        server.features = server.features + "trash" + "trash-actions"
    }

    /** A unit trashed before (Hoard over SMB: `by` absent; or the web: `admin`). */
    fun putTrashed(id: String, name: String, folderId: String, day: String, size: Long, by: String? = null, held: Boolean = false, group: String? = null, compressedTo: String? = null, dir: String = "", root: String = "") {
        val dayDir = listOf(root, ".hoard-trash/$day").filter { it.isNotEmpty() }.joinToString("/")
        val unit = TrashUnitView(
            key = "nas:$folderId:$dayDir", source = "nas", day = day, folderId = folderId, folderName = "Folder $folderId", dir = dir,
            files = 1, size = size, held = held, byServer = by != null, by = by,
            group = group ?: if (compressedTo != null) TrashGroupIds.COMPRESSED else if (by == "admin") TrashGroupIds.WEB else TrashGroupIds.HOARD,
            purgeAfter = if (by != null) 1L else null,
        )
        val original = listOf(dir, name).filter { it.isNotEmpty() }.joinToString("/")
        val fromRoot = if (root.isEmpty()) original else original.removePrefix("$root/")
        val place = TrashPlaceView("nas", "Folder $folderId", original, "$dayDir/$fromRoot", held = held)
        val prev = trashed[id]
        trashed[id] = (prev ?: TrashEntryView(id, "photo", name, size, places = emptyList(), compressedTo = compressedTo, held = held)).let {
            it.copy(units = it.units + unit, places = it.places + place)
        }
    }

    /** A backup-only item in the server trash (key `server`): devices may restore it, only the web deletes it. */
    fun putServerTrashed(id: String, name: String, day: String, size: Long, device: String = "Pixel 8", by: String = "dev-other") {
        val unit = TrashUnitView(
            key = "server", source = "server", day = day, dir = "DCIM/Camera", device = device, files = 1, size = size,
            byServer = true, by = by, group = if (by == "admin") TrashGroupIds.WEB else TrashGroupIds.HOARD, purgeAfter = 1L,
        )
        trashed[id] = TrashEntryView(id, "photo", name, size, places = listOf(TrashPlaceView("mirror", device = device)), units = listOf(unit))
    }

    /** Backup-only units' figures (Empty leaves them). */
    fun webOnly(): TrashFigures {
        val units = trashed.values.flatMap { e -> e.units.filter { it.key == "server" && !it.held }.map { e.id to it } }
        return TrashFigures(units.map { it.first }.distinct().size, units.sumOf { it.second.size })
    }

    fun figures(want: Collection<String>? = null): Pair<TrashFigures, Map<String, TrashFigures>> {
        val groups = TrashGroupIds.ALL.associateWith { TrashFigures.ZERO }.toMutableMap()
        val ids = HashSet<String>()
        var size = 0L
        for (e in trashed.values) {
            val counted = HashSet<String>()
            for (u in e.units) {
                if (u.held || u.key == "server") continue
                val g = groups.getValue(u.group)
                groups[u.group] = TrashFigures(g.count + if (counted.add(u.group)) 1 else 0, g.size + u.size)
                if (want == null || u.group in want) {
                    ids += e.id
                    size += u.size
                }
            }
        }
        return TrashFigures(ids.size, size) to groups
    }

    private fun limited(ex: FakeServer.HttpExchange): Boolean {
        if (rateLimited <= 0) return false
        rateLimited--
        ex.responseHeaders.add("Retry-After", "1")
        server.send(ex, 429, """{"error":{"code":"rate_limited","message":"slow"}}""")
        return true
    }

    private fun handle(ex: FakeServer.HttpExchange, path: String, query: Map<String, String>, raw: ByteArray): Boolean {
        if (!path.startsWith("/v1/trash")) return false
        if (limited(ex)) return true
        if (notHoard) {
            server.send(ex, 403, """{"error":{"code":"forbidden","message":"no","details":{"reason":"not_hoard"}}}""")
            return true
        }
        val body = raw.toString(Charsets.UTF_8)
        when (path) {
            "/v1/trash" -> {
                val all = trashed.values.toList().reversed()
                val start = query["cursor"]?.removePrefix("c")?.toInt() ?: 0
                val page = all.drop(start).take(pageSize)
                val more = start + pageSize < all.size
                val groups = if (query["cursor"] == null) figures().second else null
                val webOnly = if (query["cursor"] == null) webOnly() else null
                server.send(ex, 200, ServerJson.encodeToString(TrashPage.serializer(), TrashPage(page, if (more) "c${start + pageSize}" else null, more, groups, webOnly)))
            }
            "/v1/trash/move" -> {
                val req = ServerJson.decodeFromString(TrashMoveRequest.serializer(), body)
                moves += req
                if (req.itemIds.isEmpty() || req.itemIds.size > 500) {
                    server.send(ex, 422, """{"error":{"code":"invalid_request","message":"1 to 500"}}""")
                    return true
                }
                val nas = req.itemIds.mapNotNull { live[it] }.filter { it.skip == null }
                if (!req.dryRun && nas.isNotEmpty() && writeProblem != null) {
                    server.send(ex, 503, """{"error":{"code":"unavailable","message":"no","details":{"reason":"$writeProblem"}}}""")
                    return true
                }
                val moved = ArrayList<String>()
                val skipped = LinkedHashMap<String, String>()
                val failed = LinkedHashMap<String, String>()
                for (id in req.itemIds) {
                    val l = live[id]
                    when {
                        l == null -> skipped[id] = if (id in trashed) "trashed" else "not_found"
                        l.skip != null -> skipped[id] = l.skip
                        req.dryRun -> Unit
                        failNext[id]?.isNotEmpty() == true -> failed[id] = failNext.getValue(id).removeFirst()
                        else -> {
                            moved += id
                            live.remove(id)
                            val unit = TrashUnitView(
                                key = "nas:${l.folderId}:.hoard-trash/$today", source = "nas", day = today, folderId = l.folderId, folderName = "Folder ${l.folderId}",
                                dir = l.dir, files = l.files, size = l.size, byServer = true, by = server.deviceId, group = TrashGroupIds.HOARD, purgeAfter = 1L,
                            )
                            val original = listOf(l.dir, l.name).filter { it.isNotEmpty() }.joinToString("/")
                            trashed[id] = TrashEntryView(id, "photo", l.name, l.size, places = listOf(TrashPlaceView("nas", "Folder ${l.folderId}", original, ".hoard-trash/$today/$original")), units = listOf(unit))
                        }
                    }
                }
                val ok = req.itemIds.mapNotNull { live[it] ?: if (it in moved) Live(it, "", "", "", trashed.getValue(it).size) else null }.filter { it.id !in skipped }
                val answer = TrashMoveAnswer(
                    moved = moved, skipped = skipped, failed = failed,
                    warnings = TrashWarnings(ok.filter { it.onlyCopy }.map { it.id }, ok.filter { it.edits }.map { it.id }),
                    nasShots = ok.size, nasFiles = ok.sumOf { it.files }, size = ok.sumOf { it.size },
                    nasBlocked = if (req.dryRun) writeProblem else null,
                )
                server.send(ex, 200, ServerJson.encodeToString(TrashMoveAnswer.serializer(), answer))
            }
            "/v1/trash/restore", "/v1/trash/delete" -> {
                val req = ServerJson.decodeFromString(TrashUnitsRequest.serializer(), body)
                val restore = path.endsWith("restore")
                if (restore) restores += req.units else deletes += req.units
                val done = ArrayList<TrashUnitRef>()
                val refused = LinkedHashMap<String, String>()
                val names = LinkedHashMap<String, String>()
                var size = 0L
                for (ref in req.units) {
                    val e = trashed[ref.itemId]
                    val u = e?.units?.firstOrNull { it.key == ref.key }
                    when {
                        u == null -> refused[ref.refKey] = "not_found"
                        u.held -> refused[ref.refKey] = "held"
                        !restore && u.key == "server" -> refused[ref.refKey] = "web_only"
                        restore && restoreRefused[ref.refKey] != null -> {
                            refused[ref.refKey] = restoreRefused.getValue(ref.refKey)
                            names[ref.refKey] = e.name
                        }
                        else -> {
                            done += ref
                            size += u.size
                            drop(ref)
                        }
                    }
                }
                server.send(ex, 200, ServerJson.encodeToString(TrashActionAnswer.serializer(), TrashActionAnswer(done, refused, names, size = size)))
            }
            "/v1/trash/empty" -> {
                val req = ServerJson.decodeFromString(TrashEmptyRequest.serializer(), body)
                empties += req
                val want = req.groups ?: TrashGroupIds.ALL
                val (now, groups) = figures(want)
                if (now != req.confirm) {
                    val g = ServerJson.encodeToString(MapSerializer(String.serializer(), TrashFigures.serializer()), groups)
                    val w = webOnly()
                    server.send(ex, 409, """{"error":{"code":"conflict","message":"confirm","details":{"reason":"confirm","count":${now.count},"size":${now.size},"groups":$g,"webOnly":{"count":${w.count},"size":${w.size}}}}}""")
                    return true
                }
                val refs = trashed.values.flatMap { e -> e.units.filter { !it.held && it.key != "server" && it.group in want }.map { TrashUnitRef(e.id, it.key) } }
                refs.forEach { drop(it) }
                server.send(ex, 200, ServerJson.encodeToString(TrashActionAnswer.serializer(), TrashActionAnswer(refs, size = now.size)))
            }
            else -> return false
        }
        return true
    }

    private fun drop(ref: TrashUnitRef) {
        val e = trashed[ref.itemId] ?: return
        val left = e.units.filter { it.key != ref.key }
        if (left.isEmpty()) trashed.remove(ref.itemId) else trashed[ref.itemId] = e.copy(units = left)
    }
}
