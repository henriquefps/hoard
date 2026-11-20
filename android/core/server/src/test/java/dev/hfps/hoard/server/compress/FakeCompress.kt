package dev.hfps.hoard.server.compress

import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.api.CompressItem
import dev.hfps.hoard.server.api.CompressItemsPage
import dev.hfps.hoard.server.api.CompressPhoneReport
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.backup.FakeBackup
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * hoard-server 0.13's phone routes for the client's tests (ADR 0025 Amendment (S2)): `GET /v1/compress/items?mine=true`,
 * the output by range with its ETag, `…/replaced`, `…/restored` and `…/abandon`. A report swaps the backup's entry
 * ([backup]'s store and entries), as the server's `CompressSwap` does, so the backup's next report answers `stored`.
 */
class FakeCompress(private val server: FakeServer, private val backup: FakeBackup?) {
    val items = LinkedHashMap<String, CompressItem>()
    val outputs = HashMap<String, ByteArray>()
    val reports = ArrayList<Triple<String, String, CompressPhoneReport>>()
    val abandons = ArrayList<Pair<String, String>>()
    val outputReads = ArrayList<String?>()
    /** Items the server still encodes for this phone (queued … verifying). */
    var pending = 0

    init {
        val previous = server.extra
        server.extra = { ex, path, query, body -> handle(ex, path, query, body) || previous?.invoke(ex, path, query, body) == true }
        server.features = server.features + "compress"
    }

    private fun handle(ex: FakeServer.HttpExchange, path: String, query: Map<String, String>, raw: ByteArray): Boolean {
        when {
            path == "/v1/compress/items" && ex.requestMethod == "GET" -> {
                val states = query["state"]?.split(',')?.toSet()
                val waiting = items.values.filter { states == null || it.state in states }
                val list = if (states != null && "queued" in states) List(pending) { CompressItem(id = "p$it", state = "queued") } else waiting
                server.send(ex, 200, ServerJson.encodeToString(CompressItemsPage.serializer(), CompressItemsPage(list)))
                return true
            }
            path.startsWith("/v1/compress/items/") -> {
                val rest = path.removePrefix("/v1/compress/items/")
                val id = rest.substringBefore('/')
                val action = rest.substringAfter('/')
                val item = items[id]
                if (item == null) {
                    server.send(ex, 404, """{"error":{"code":"not_found","message":"no"}}""")
                    return true
                }
                when (action) {
                    "output" -> {
                        val bytes = outputs[id]
                        if (bytes == null || item.state !in setOf("ready", "replacing")) {
                            server.send(ex, 404, """{"error":{"code":"not_found","message":"no"}}""")
                            return true
                        }
                        val range = ex.requestHeaders.getFirst("Range")
                        outputReads += range
                        ex.responseHeaders.add("ETag", "\"${item.outSha256}\"")
                        val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt()
                        if (start == null || ex.requestHeaders.getFirst("If-Range") != "\"${item.outSha256}\"") {
                            server.sendBytes(ex, 200, bytes, emptyMap())
                        } else {
                            server.sendBytes(ex, 206, bytes.copyOfRange(start, bytes.size), mapOf("Content-Range" to "bytes $start-${bytes.size - 1}/${bytes.size}"))
                        }
                    }
                    "replaced", "restored" -> {
                        val report = ServerJson.decodeFromString(CompressPhoneReport.serializer(), raw.toString(Charsets.UTF_8))
                        reports += Triple(id, action, report)
                        val replaced = action == "replaced"
                        val want = if (replaced) item.outFingerprint to item.outSha256 else item.fingerprint to item.sha256
                        if (report.fingerprint != want.first || report.sha256 != want.second) {
                            server.send(ex, 409, """{"error":{"code":"conflict","message":"no","details":{"reason":"mismatch"}}}""")
                            return true
                        }
                        val settled = item.copy(state = if (replaced) "replaced" else "restored", direction = null)
                        items[id] = settled
                        backup?.let { b ->
                            b.store[report.fingerprint] = report.sha256
                            item.path?.let { b.entries[it] = report.fingerprint }
                        }
                        server.send(ex, 200, ServerJson.encodeToString(CompressItem.serializer(), settled))
                    }
                    "abandon" -> {
                        val reason = ServerJson.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject["reason"]!!.jsonPrimitive.content
                        abandons += id to reason
                        val settled = item.copy(state = if (item.state == "replacing") "replaced" else "failed", reason = reason)
                        items[id] = settled
                        server.send(ex, 200, ServerJson.encodeToString(CompressItem.serializer(), settled))
                    }
                    else -> return false
                }
                return true
            }
        }
        return false
    }
}
