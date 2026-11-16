package dev.hfps.hoard.server

import dev.hfps.hoard.server.api.CullingField
import dev.hfps.hoard.server.api.CullingPush
import dev.hfps.hoard.server.api.CullingPushResponse
import dev.hfps.hoard.server.api.CullingRecord
import dev.hfps.hoard.server.api.CullingResult
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ItemsPage
import dev.hfps.hoard.server.api.ServerJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * A small hoard-server for the client's tests: pairing, `/v1/server`, `PATCH /v1/device`, the items feed with culling
 * (one revision counter, cursors `fake:i<rev>`), `POST /v1/culling` with ADR 0016's per-field rule, lookup, ranged
 * originals and previews that are pending once.
 */
class FakeServer : AutoCloseable {
    private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val url: String get() = "http://127.0.0.1:${socket.localPort}"
    val key = "rvk1_0123456789abcdef_secretsecretsecretsecretsecretsecretsecr"
    var rev = 0L
    val items = LinkedHashMap<String, Item>()
    /** item id → field → (value, rev, modifiedAt, deviceId). */
    val culling = LinkedHashMap<String, MutableMap<String, CullingField>>()
    val cullingRev = HashMap<String, Long>()
    val replaced = HashMap<String, String>()
    val declared = ArrayList<String>()
    val pushes = ArrayList<CullingPush>()
    val originals = HashMap<String, ByteArray>()
    val previewAsks = ConcurrentHashMap<String, Int>()
    /** The body of a ready preview, by request URI (default: text naming the URI; tests that decode set an image). */
    @Volatile var previewBody: (String) -> ByteArray = { "preview of $it".toByteArray() }
    var deviceId = "dev-hoard"
    var candidates = """[{"physicalDeviceId":"phys-reveal","name":"Pixel 8","match":"model","devices":[{"id":"phys-reveal","app":"reveal","model":"Pixel 8"}]}]"""
    var pageCalls = 0
    var rejectNextCursor = false
    /** `GET /v1/originals/…` requests. */
    val originalReads = java.util.concurrent.atomic.AtomicInteger()
    /** `POST /v1/culling` calls, answered or not. */
    var cullingCalls = 0
    /** Answer every culling push with this error status (null = answer normally). */
    var cullingFailure: Int? = null
    /** Answer culling pushes with an empty `results` (a broken server: no progress). */
    var cullingEmptyResults = false
    /** Answer every feed page with `hasMore` and the cursor it was asked for (a broken server: no progress). */
    var stuckCursor = false
    /** Fail this many `PATCH /v1/device` calls with a 503. */
    var deviceFailures = 0
    /** `GET /v1/server` features (0.10.0 adds `items-by-path`). */
    /** The server id it answers with (another fake server: another id). */
    var serverId = "fake-server"
    var features = listOf("items", "previews", "culling", "device-identity", "edits")
    /** NAS folders the server knows (by-path answers 404 for another id). */
    val nasFolders = HashSet<String>()
    /** `GET /v1/items?folder=` pages and `POST /v1/items/by-path` / lookup calls served. */
    var folderQueries = 0
    var byPathCalls = 0
    var lookupCalls = 0
    /** Answer this many lookups with a 503 (the server is reachable but failing). */
    var lookupFailures = 0
    /** `POST /v1/nas/walk` bodies received (ADR 0023). */
    val walks = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
    /** Answer this many walk requests with `429` and this `Retry-After`. */
    var walkRateLimited = 0
    var walkRetryAfter = 2
    /** Folders whose walk the server defers (when it may start). */
    val walkDeferred = HashMap<String, Long>()
    /** The `include` of the last feed page asked for. */
    @Volatile var lastInclude: String? = null

    init {
        Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                Thread { serve(client) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    /** The parts of an HTTP exchange the handler uses (a minimal HTTP/1.1 server: one request per connection). */
    class HttpExchange(val requestMethod: String, val requestURI: URI, val requestHeaders: Headers, val requestBody: java.io.InputStream, private val out: java.io.OutputStream) {
        val responseHeaders = Headers()
        private var status = 200
        private var length = -1L
        fun sendResponseHeaders(code: Int, len: Long) {
            status = code
            length = len
            val head = StringBuilder("HTTP/1.1 $code X\r\n")
            for ((k, vs) in responseHeaders.map) for (v in vs) head.append("$k: $v\r\n")
            head.append("Content-Length: ${if (len < 0) 0 else len}\r\nConnection: close\r\n\r\n")
            out.write(head.toString().toByteArray())
            out.flush()
        }
        val responseBody: java.io.OutputStream get() = out
        fun close() = out.flush()
    }

    class Headers {
        val map = LinkedHashMap<String, MutableList<String>>()
        fun add(k: String, v: String) { map.getOrPut(k.lowercase()) { ArrayList() } += v }
        fun getFirst(k: String): String? = map[k.lowercase()]?.firstOrNull()
    }

    private fun serve(client: Socket) {
        client.use { s ->
            val input = java.io.BufferedInputStream(s.getInputStream())
            fun line(): String {
                val b = java.io.ByteArrayOutputStream()
                while (true) {
                    val c = input.read()
                    if (c < 0 || c == '\n'.code) break
                    if (c != '\r'.code) b.write(c)
                }
                return b.toString(Charsets.UTF_8)
            }
            val request = line().split(' ')
            if (request.size < 2) return
            val headers = Headers()
            while (true) {
                val h = line()
                if (h.isEmpty()) break
                headers.add(h.substringBefore(':').trim(), h.substringAfter(':').trim())
            }
            val length = headers.getFirst("Content-Length")?.toInt() ?: 0
            val body = ByteArray(length).also { var r = 0; while (r < length) { val n = input.read(it, r, length - r); if (n < 0) break; r += n } }
            val ex = HttpExchange(request[0], URI(request[1]), headers, body.inputStream(), s.getOutputStream())
            runCatching { handle(ex) }.onFailure { runCatching { send(ex, 500, """{"error":{"code":"internal","message":"${it.message}"}}""") } }
            s.getOutputStream().flush()
        }
    }

    fun putItem(item: Item) {
        rev++
        items[item.id] = item.copy(rev = rev)
    }

    fun tombstone(id: String, replacedBy: String?) {
        rev++
        items[id] = Item(id = id, rev = rev, deleted = true, replacedBy = replacedBy)
        if (replacedBy != null) {
            replaced[id] = replacedBy
            // Carried over (ADR 0016 "Items that change identity").
            culling.remove(id)?.let { fields -> culling.getOrPut(replacedBy) { LinkedHashMap() }.putAll(fields) }
            cullingRev[replacedBy] = rev
            cullingRev[id] = rev
        }
    }

    /** A field written by another device (Reveal, the NAS). */
    fun write(itemId: String, field: String, value: JsonPrimitive, at: Long, device: String) {
        rev++
        culling.getOrPut(itemId) { LinkedHashMap() }[field] = CullingField(value, rev, at, device)
        cullingRev[itemId] = rev
    }

    /**
     * The NAS sidecar import (ADR 0016 "The NAS sidecars"): a write by `"nas"` dated by the sidecar's mtime, with no
     * `baseRev` (step 5); the same value is a no-op apart from moving the field's time forward (step 2). Returns
     * whether a revision was made.
     */
    fun nasImport(itemId: String, field: String, value: JsonPrimitive, mtime: Long): Boolean {
        val fields = culling.getOrPut(itemId) { LinkedHashMap() }
        val cur = fields[field]
        if (cur != null && cur.value == value) {
            if (mtime > cur.modifiedAt || (mtime == cur.modifiedAt && "nas" > (cur.deviceId ?: ""))) fields[field] = cur.copy(modifiedAt = mtime, deviceId = "nas")
            return false
        }
        if (cur == null || mtime > cur.modifiedAt || (mtime == cur.modifiedAt && "nas" > (cur.deviceId ?: ""))) {
            rev++
            fields[field] = CullingField(value, rev, mtime, "nas")
            cullingRev[itemId] = rev
            return true
        }
        return false
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.rawPath.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val query = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        if (path == "/v1/pair") {
            val token = ServerJson.parseToJsonElement(body).jsonObject["token"]!!.jsonPrimitive.content
            if (token != "good") return send(ex, 401, """{"error":{"code":"invalid_pairing_token","message":"no"}}""")
            return send(ex, 201, """{"deviceId":"$deviceId","apiKey":"$key","serverId":"$serverId","serverName":"Fake","physicalDeviceId":"$deviceId","candidates":$candidates}""")
        }
        if (ex.requestHeaders.getFirst("Authorization") != "Bearer $key") return send(ex, 401, """{"error":{"code":"unauthorized","message":"no"}}""")
        when {
            path == "/v1/server" -> send(ex, 200, """{"serverId":"$serverId","serverName":"Fake","version":"0.9.0","api":1,"features":${features.joinToString(",", "[", "]") { "\"$it\"" }},"limits":{"maxPullLimit":500}}""")
            path == "/v1/device" && ex.requestMethod == "PATCH" && deviceFailures > 0 -> {
                deviceFailures--
                send(ex, 503, """{"error":{"code":"unavailable","message":"busy"}}""")
            }
            path == "/v1/device" && ex.requestMethod == "PATCH" -> {
                ServerJson.parseToJsonElement(body).jsonObject["features"]?.jsonArray?.forEach { declared += it.jsonPrimitive.content }
                send(ex, 200, """{"deviceId":"$deviceId","features":["culling"]}""")
            }
            path == "/v1/device/claim" -> send(ex, 200, """{"deviceId":"$deviceId","physicalDevice":{"id":"phys-reveal","devices":[]}}""")
            path == "/v1/items/changes" -> {
                pageCalls++
                lastInclude = query["include"]
                val cursor = query["cursor"].orEmpty()
                if (rejectNextCursor && cursor.isNotEmpty()) {
                    rejectNextCursor = false
                    return send(ex, 400, """{"error":{"code":"invalid_cursor","message":"other server"}}""")
                }
                if (stuckCursor) return send(ex, 200, ServerJson.encodeToString(ItemsPage.serializer(), ItemsPage(emptyList(), emptyList(), cursor.ifEmpty { "fake:i0" }, true)))
                val after = if (cursor.isEmpty()) 0 else cursor.substringAfter(":i").toLong()
                val limit = query["limit"]?.toInt() ?: 200
                data class Row(val rev: Long, val item: Item?, val record: CullingRecord?)
                val rows = ArrayList<Row>()
                for (item in items.values) if (item.rev > after) rows += Row(item.rev, item, null)
                for ((id, r) in cullingRev) if (r > after) rows += Row(r, null, record(id))
                rows.sortBy { it.rev }
                val page = rows.take(limit)
                val next = page.lastOrNull()?.rev ?: after
                // Without include=trash a trashed item is a tombstone (ADR 0023 "/v1 additions").
                val withTrash = query["include"].orEmpty().split(',').contains("trash")
                val served = page.mapNotNull { it.item }.map { i -> if (i.trashed != null && !withTrash) Item(id = i.id, rev = i.rev, deleted = true) else i }
                send(ex, 200, ServerJson.encodeToString(ItemsPage.serializer(), ItemsPage(served, page.mapNotNull { it.record }, "fake:i$next", rows.size > limit)))
            }
            path == "/v1/culling" -> {
                cullingCalls++
                cullingFailure?.let { return send(ex, it, """{"error":{"code":"unavailable","message":"refused"}}""") }
                val push = ServerJson.decodeFromString(CullingPush.serializer(), body)
                pushes += push
                if (cullingEmptyResults) return send(ex, 200, ServerJson.encodeToString(CullingPushResponse.serializer(), CullingPushResponse(emptyList())))
                val results = push.changes.mapIndexed { i, c ->
                    var id = c.itemId ?: items.values.firstOrNull { it.files.any { f -> f.fingerprint == c.fingerprint } }?.id ?: ItemIds.derive(c.fingerprint!!)
                    while (replaced[id] != null) id = replaced[id]!!
                    if (c.field == "label" && (c.value as JsonPrimitive).content.length > 64) return@mapIndexed CullingResult(i, c.itemId, c.fingerprint, c.field, "invalid", code = "label_too_long")
                    val fields = culling.getOrPut(id) { LinkedHashMap() }
                    val cur = fields[c.field]
                    when {
                        cur != null && cur.value == c.value -> CullingResult(i, id, field = c.field, status = "unchanged", rev = cur.rev)
                        cur == null || c.baseRev == cur.rev -> {
                            rev++
                            fields[c.field] = CullingField(c.value, rev, c.modifiedAt, deviceId)
                            cullingRev[id] = rev
                            CullingResult(i, id, field = c.field, status = "applied", rev = rev)
                        }
                        c.modifiedAt > cur.modifiedAt || (c.modifiedAt == cur.modifiedAt && deviceId > (cur.deviceId ?: "")) -> {
                            rev++
                            fields[c.field] = CullingField(c.value, rev, c.modifiedAt, deviceId)
                            cullingRev[id] = rev
                            CullingResult(i, id, field = c.field, status = "conflict", winner = "incoming", rev = rev)
                        }
                        else -> CullingResult(i, id, field = c.field, status = "conflict", winner = "server", rev = cur.rev)
                    }
                }
                send(ex, 200, ServerJson.encodeToString(CullingPushResponse.serializer(), CullingPushResponse(results)))
            }
            path == "/v1/items" && ex.requestMethod == "GET" -> {
                folderQueries++
                val folder = query["folder"]
                val dir = query["path"].orEmpty()
                val live = items.values.filter { !it.deleted }.filter { item ->
                    folder == null || item.files.any { f -> f.locations.any { it.source == "nas" && it.folderId == folder && (dir.isEmpty() || it.path.orEmpty().startsWith("$dir/")) } }
                }.sortedByDescending { it.sortTime }
                val offset = query["cursor"]?.toIntOrNull() ?: 0
                val limit = query["limit"]?.toInt() ?: 200
                val page = live.drop(offset).take(limit)
                val more = offset + limit < live.size
                send(ex, 200, ServerJson.encodeToString(dev.hfps.hoard.server.api.ItemsQueryPage.serializer(), dev.hfps.hoard.server.api.ItemsQueryPage(page, if (more) "${offset + limit}" else null, more)))
            }
            path == "/v1/items/by-path" -> {
                byPathCalls++
                val req = ServerJson.decodeFromString(dev.hfps.hoard.server.api.ByPathRequest.serializer(), body)
                if (req.folder !in nasFolders) return send(ex, 404, """{"error":{"code":"not_found","message":"No such NAS folder."}}""")
                val found = ArrayList<dev.hfps.hoard.server.api.PathFound>()
                val missing = ArrayList<String>()
                for (p in req.paths.distinct()) {
                    val hit = items.values.filter { !it.deleted }.firstNotNullOfOrNull { item ->
                        item.files.firstNotNullOfOrNull { f -> f.locations.firstOrNull { it.source == "nas" && it.folderId == req.folder && it.path == p }?.let { loc -> dev.hfps.hoard.server.api.PathFound(p, item.id, f.role, f.size, loc.modifiedAt) } }
                    }
                    if (hit != null) found += hit else missing += p
                }
                send(ex, 200, ServerJson.encodeToString(dev.hfps.hoard.server.api.ByPathResponse.serializer(), dev.hfps.hoard.server.api.ByPathResponse(found, missing)))
            }
            path == "/v1/nas/walk" -> {
                val folders = ServerJson.parseToJsonElement(body).jsonObject["folders"]!!.jsonArray.map { it.jsonPrimitive.content }
                walks += folders
                if (walkRateLimited > 0) {
                    walkRateLimited--
                    return sendBytes(ex, 429, """{"error":{"code":"rate_limited","message":"slow down"}}""".toByteArray(), mapOf("Content-Type" to "application/json", "Retry-After" to walkRetryAfter.toString()))
                }
                if (folders.any { it !in nasFolders }) return send(ex, 404, """{"error":{"code":"not_found","message":"unknown folder"}}""")
                val deferred = folders.filter { it in walkDeferred }.joinToString(",", "{", "}") { "\"$it\":${walkDeferred[it]}" }
                send(ex, 202, folders.joinToString(",", """{"queued":[""", "],\"deferred\":$deferred}") { "\"$it\"" })
            }
            path == "/v1/items/lookup" && lookupFailures > 0 -> {
                lookupFailures--
                lookupCalls++
                send(ex, 503, """{"error":{"code":"unavailable","message":"busy"}}""")
            }
            path == "/v1/items/lookup" -> {
                lookupCalls++
                val wanted = ServerJson.parseToJsonElement(body).jsonObject["fingerprints"]!!.jsonArray.map { it.jsonPrimitive.content }
                val json = buildJsonObject {
                    putJsonArray("items") {
                        items.values.filter { item -> !item.deleted && item.files.any { it.fingerprint in wanted } }
                            .forEach { add(ServerJson.encodeToJsonElement(Item.serializer(), it)) }
                    }
                    putJsonArray("matches") {
                        for (fp in wanted) items.values.filter { !it.deleted && it.files.any { f -> f.fingerprint == fp } }.forEach { item ->
                            add(buildJsonObject { put("fingerprint", fp); put("itemId", item.id); put("role", item.files.first { it.fingerprint == fp }.role) })
                        }
                    }
                    putJsonArray("missing") { wanted.filter { fp -> items.values.none { it.files.any { f -> f.fingerprint == fp } } }.forEach { add(JsonPrimitive(it)) } }
                }
                send(ex, 200, json.toString())
            }
            path.startsWith("/v1/originals/") -> {
                originalReads.incrementAndGet()
                val bytes = originals[java.net.URLDecoder.decode(path.removePrefix("/v1/originals/"), "UTF-8")] ?: return send(ex, 404, """{"error":{"code":"not_found","message":"no"}}""")
                val range = ex.requestHeaders.getFirst("Range")
                if (range == null) return sendBytes(ex, 200, bytes, emptyMap())
                val (a, b) = range.removePrefix("bytes=").split('-').map { it.toLong() }
                val end = minOf(b, bytes.size - 1L)
                sendBytes(ex, 206, bytes.copyOfRange(a.toInt(), end.toInt() + 1), mapOf("Content-Range" to "bytes $a-$end/${bytes.size}"))
            }
            path.endsWith("/preview") -> {
                val n = previewAsks.merge(ex.requestURI.toString(), 1, Int::plus)!!
                if (n == 1) {
                    ex.responseHeaders.add("Retry-After", "1")
                    send(ex, 202, """{"status":"pending","retryAfter":1}""")
                } else {
                    sendBytes(ex, 200, previewBody(ex.requestURI.toString()), mapOf("X-Hoard-Preview-Version" to "k1", "X-Hoard-Preview" to "ready"))
                }
            }
            else -> send(ex, 404, """{"error":{"code":"not_found","message":"$path"}}""")
        }
    }

    private fun record(id: String): CullingRecord {
        val replacedBy = replaced[id]
        val fields = culling[id].orEmpty()
        val prints = if (replacedBy != null) emptyList() else items[id]?.takeIf { !it.deleted }?.files?.map { it.fingerprint }.orEmpty()
        return CullingRecord(id, cullingRev[id] ?: 0, prints, replacedBy, fields["rating"], fields["flag"], fields["label"])
    }

    private fun send(ex: HttpExchange, status: Int, json: String) = sendBytes(ex, status, json.toByteArray(), mapOf("Content-Type" to "application/json"))

    private fun sendBytes(ex: HttpExchange, status: Int, bytes: ByteArray, headers: Map<String, String>) {
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.write(bytes)
        ex.close()
    }

    override fun close() = socket.close()
}
