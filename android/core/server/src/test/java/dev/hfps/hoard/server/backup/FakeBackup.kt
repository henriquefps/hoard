package dev.hfps.hoard.server.backup

import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.api.BackupReport
import dev.hfps.hoard.server.api.BackupReportResponse
import dev.hfps.hoard.server.api.BackupReportResult
import dev.hfps.hoard.server.api.BackupRemovalResult
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.api.UploadCreate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.UUID

/**
 * hoard-server 0.12.0's phone backup for the client's tests (ADR 0024, `server/api/openapi.yaml`): roots (covered,
 * stopped, absorbed), report (stored / nas / missing / in_progress, per-entry `refused`, or an older server's `422` for
 * a bad entry), removals, and chunked uploads with `wait`, `HEAD` resume and `checksum_mismatch`.
 */
class FakeBackup(private val server: FakeServer) {
    data class Root(val id: String, val volume: String, val path: String, var types: List<String>, var stopped: Boolean = false, var paused: Boolean = false)
    data class Upload(val id: String, val sha: String, val size: Long, val fingerprint: String?, val data: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream())

    val roots = LinkedHashMap<String, Root>()
    /** fingerprint → sha256 of what the store holds. */
    val store = HashMap<String, String>()
    val blobs = HashMap<String, ByteArray>()
    /** Fingerprints an indexed NAS file has. */
    val nas = HashSet<String>()
    /** sha256 of uploads another device is sending (`in_progress` with `wait`). */
    val busy = HashSet<String>()
    val uploads = LinkedHashMap<String, Upload>()
    val creates = ArrayList<UploadCreate>()
    val reports = ArrayList<BackupReport>()
    val heads = ArrayList<String>()
    /** path → the entry the mirror holds (current), and the removals seen. */
    val entries = LinkedHashMap<String, String>()
    val removed = ArrayList<Pair<String, String>>()
    /** Extensions answered `refused` per entry (the mirror-formats contract); [legacy422] answers a `422` instead. */
    var refuseExtensions = setOf<String>()
    var legacy422 = false
    var storageConfigured = true
    /** Bytes of the next chunk to corrupt (a `checksum_mismatch` at completion). */
    var corruptNext = false
    var patches = 0

    init {
        server.features = server.features + "backup"
        server.extra = { ex, path, _, body -> handle(ex, path, body) }
    }

    private fun json(ex: FakeServer.HttpExchange, status: Int, body: String) = server.send(ex, status, body).let { true }

    private fun error(ex: FakeServer.HttpExchange, status: Int, code: String, details: String = "{}") =
        json(ex, status, """{"error":{"code":"$code","message":"$code","details":$details}}""")

    private fun rootJson(r: Root) =
        """{"id":"${r.id}","physicalDeviceId":"phys","deviceName":"Pixel","mine":true,"folder":"Pixel (phys)","volume":"${r.volume}","path":"${r.path}",""" +
            """"createdBy":"dev-hoard","app":"hoard-debug","createdAt":1,"types":${r.types.joinToString(",", "[", "]") { "\"$it\"" }},"paused":${r.paused},""" +
            """"stopped":${r.stopped},"active":${!r.stopped},"lastReportAt":null,"stats":{"files":${entries.size},"bytes":0,"stored":${entries.size},"nas":0,"waiting":0,"removedFromDevice":${removed.size},"freed":0,"verified":0}}"""

    private fun handle(ex: FakeServer.HttpExchange, path: String, raw: ByteArray): Boolean {
        val method = ex.requestMethod
        val text = raw.toString(Charsets.UTF_8)
        when {
            path == "/v1/backup/roots" && method == "GET" -> return json(ex, 200, roots.values.joinToString(",", """{"roots":[""", "]}") { rootJson(it) })
            path == "/v1/backup/roots" && method == "POST" -> {
                if (!storageConfigured) return error(ex, 503, "storage_not_configured")
                val o = ServerJson.parseToJsonElement(text).jsonObject
                val volume = o["volume"]!!.jsonPrimitive.content
                val p = o["path"]!!.jsonPrimitive.content
                val types = o["types"]?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("image", "video")
                roots.values.firstOrNull { it.volume == volume && it.path == p }?.let {
                    it.stopped = false
                    it.paused = false
                    it.types = types
                    return json(ex, 200, rootJson(it))
                }
                roots.values.firstOrNull { !it.stopped && it.volume == volume && p.startsWith(it.path + "/") }?.let {
                    return error(ex, 409, "conflict", """{"reason":"covered","rootId":"${it.id}"}""")
                }
                val absorbed = roots.values.filter { !it.stopped && it.volume == volume && it.path.startsWith("$p/") }
                absorbed.forEach { roots.remove(it.id) }
                val r = Root(UUID.randomUUID().toString(), volume, p, types)
                roots[r.id] = r
                val body = rootJson(r).removeSuffix("}") + ""","details":{"absorbed":${absorbed.joinToString(",", "[", "]") { "\"${it.id}\"" }}}}"""
                return json(ex, 201, body)
            }
            path.startsWith("/v1/backup/roots/") -> {
                val r = roots[path.removePrefix("/v1/backup/roots/")] ?: return error(ex, 404, "not_found")
                if (method == "DELETE") {
                    r.stopped = true
                    return json(ex, 200, """{"status":"stopped"}""")
                }
                val o = ServerJson.parseToJsonElement(text).jsonObject
                o["types"]?.jsonArray?.let { a -> r.types = a.map { it.jsonPrimitive.content } }
                o["paused"]?.jsonPrimitive?.content?.let { r.paused = it == "true" }
                return json(ex, 200, rootJson(r))
            }
            path == "/v1/backup/report" -> {
                val report = ServerJson.decodeFromString(BackupReport.serializer(), text)
                val r = roots[report.rootId] ?: return error(ex, 404, "not_found")
                if (r.stopped) return error(ex, 409, "conflict", """{"reason":"stopped"}""")
                reports += report
                val results = ArrayList<BackupReportResult>()
                for ((i, e) in report.entries.withIndex()) {
                    val ext = e.path.substringAfterLast('.').lowercase()
                    if (ext in refuseExtensions) {
                        if (legacy422) return error(ex, 422, "invalid_request", """{"index":$i,"field":"entries"}""")
                        results += BackupReportResult(i, e.path, "refused", reason = "unsupported_type")
                        continue
                    }
                    entries[e.path] = e.fingerprint
                    val state = when {
                        e.fingerprint in store && (e.sha256 == null || store[e.fingerprint] == e.sha256) -> "stored"
                        e.fingerprint in store -> "differs"
                        e.fingerprint in nas -> "nas"
                        else -> "missing"
                    }
                    results += BackupReportResult(i, e.path, state, itemId = if (state == "stored" || state == "nas") "item-${e.fingerprint.takeLast(6)}" else null)
                }
                val gone = report.removed.mapIndexed { i, rm ->
                    removed += rm.path to rm.reason
                    val had = entries.remove(rm.path) != null
                    BackupRemovalResult(i, rm.path, if (!had) "unknown" else if (rm.reason == "moved") "moved" else "kept")
                }
                return json(ex, 200, ServerJson.encodeToString(BackupReportResponse.serializer(), BackupReportResponse(results, gone)))
            }
            path == "/v1/uploads" && method == "POST" -> {
                val c = ServerJson.decodeFromString(UploadCreate.serializer(), text)
                creates += c
                if (store.containsValue(c.sha256)) {
                    c.fingerprint?.let { store[it] = c.sha256 }
                    return json(ex, 200, """{"status":"exists"}""")
                }
                uploads.values.firstOrNull { it.sha == c.sha256 }?.let { return json(ex, 200, """{"status":"resume","uploadId":"${it.id}","offset":${it.data.size()},"chunkSize":${server.chunkSize}}""") }
                if (c.wait == true && c.sha256 in busy) {
                    ex.responseHeaders.add("Retry-After", "300")
                    return json(ex, 200, """{"status":"in_progress","retryAfter":300}""")
                }
                val u = Upload(UUID.randomUUID().toString(), c.sha256, c.size, c.fingerprint)
                uploads[u.id] = u
                return json(ex, 201, """{"status":"created","uploadId":"${u.id}","offset":0,"chunkSize":${server.chunkSize}}""")
            }
            path.startsWith("/v1/uploads/") -> {
                val u = uploads[path.removePrefix("/v1/uploads/")] ?: return error(ex, 404, "not_found")
                when (method) {
                    "HEAD" -> {
                        heads += u.id
                        server.sendBytes(ex, 204, ByteArray(0), mapOf("Upload-Offset" to u.data.size().toString(), "Upload-Length" to u.size.toString()))
                        return true
                    }
                    "DELETE" -> {
                        uploads.remove(u.id)
                        server.sendBytes(ex, 204, ByteArray(0), emptyMap())
                        return true
                    }
                }
                patches++
                val offset = ex.requestHeaders.getFirst("Upload-Offset")!!.toLong()
                if (offset != u.data.size().toLong()) return error(ex, 409, "offset_mismatch", """{"offset":${u.data.size()}}""")
                if (ex.requestHeaders.getFirst("Content-Type") != "application/offset+octet-stream") return error(ex, 415, "unsupported_media_type")
                val chunk = if (corruptNext) raw.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0] + 1).toByte(); corruptNext = false } else raw
                u.data.write(chunk)
                if (u.data.size() < u.size) {
                    server.sendBytes(ex, 204, ByteArray(0), mapOf("Upload-Offset" to u.data.size().toString()))
                    return true
                }
                val bytes = u.data.toByteArray()
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                if (sha != u.sha) {
                    u.data.reset()
                    return error(ex, 422, "checksum_mismatch", """{"offset":0}""")
                }
                uploads.remove(u.id)
                blobs[sha] = bytes
                u.fingerprint?.let { store[it] = sha }
                server.sendBytes(ex, 204, ByteArray(0), mapOf("Upload-Offset" to bytes.size.toString(), "Upload-Complete" to "1"))
                return true
            }
        }
        return false
    }
}
