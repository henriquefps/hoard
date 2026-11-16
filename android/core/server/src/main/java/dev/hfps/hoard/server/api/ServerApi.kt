package dev.hfps.hoard.server.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A hoard-server answer that is not a success: the HTTP status and the stable error `code` (ADR 0013 "Errors"). */
class ServerException(
    val status: Int,
    val code: String,
    message: String,
    val retryAfterSeconds: Long? = null,
    /** `details.reason` when the server gave one (`preview_unavailable`, `conflict`). */
    val reason: String? = null,
    /** The error's whole `details` (`index`, `field`, `rootId`, `offset`, `uploadId`), when it had one. */
    val details: kotlinx.serialization.json.JsonObject? = null,
) : IOException("$status $code: $message") {
    /** A number of [details] (`index`, `offset`). */
    fun detailLong(name: String): Long? = (details?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

    fun detailString(name: String): String? = (details?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    /** The key was refused (revoked, unknown): the app is no longer paired. */
    val unauthorized: Boolean get() = status == 401 && code == "unauthorized"
}

/** What a preview GET answered (ADR 0015): the file, still pending (ask again after [retryAfterSeconds]), or none. */
sealed interface PreviewResult {
    data class Ready(val file: File, val key: String?, val stale: Boolean) : PreviewResult
    data class Pending(val retryAfterSeconds: Long) : PreviewResult
    data class Unavailable(val reason: String?) : PreviewResult
}

val ServerJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * The /v1 calls of the Gallery, over `HttpURLConnection` (no client library: a dozen JSON calls, a ranged GET and a
 * file download). [key] is the device's `rvk1_` key (null before pairing). Blocking: call from IO.
 */
class ServerApi(
    val baseUrl: String,
    private val key: () -> String?,
    private val userAgent: String = "Hoard",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {
    fun health(): String = request("GET", "/v1/health", auth = false).use { it.readText() }

    fun server(): ServerInfo = get("/v1/server", ServerInfo.serializer())

    fun pair(request: PairRequest): PairResponse = send("POST", "/v1/pair", request, PairRequest.serializer(), PairResponse.serializer(), auth = false)

    fun device(): DeviceView = get("/v1/device", DeviceView.serializer())

    /** `GET /v1/device` with the server's clock (its `Date` header, Unix ms; null when absent): free up's Reveal gate. */
    fun deviceDated(): Dated<DeviceView> = request("GET", "/v1/device").use { Dated(ServerJson.decodeFromString(DeviceView.serializer(), it.readText()), it.date()) }

    fun patchDevice(patch: DevicePatch): DeviceView = send("PATCH", "/v1/device", patch, DevicePatch.serializer(), DeviceView.serializer())

    fun claim(physicalDeviceId: String?): DeviceView = send("POST", "/v1/device/claim", ClaimRequest(physicalDeviceId), ClaimRequest.serializer(), DeviceView.serializer())

    /** The items feed with culling records (ADR 0014, 0016): one cursor for both. */
    fun itemsChanges(cursor: String?, limit: Int, include: String = INCLUDE_ITEMS_CULLING): ItemsPage =
        get("/v1/items/changes?cursor=${enc(cursor.orEmpty())}&limit=$limit&include=${enc(include)}", ItemsPage.serializer())

    fun item(id: String): Item = get("/v1/items/${enc(id)}", Item.serializer())

    /**
     * `GET /v1/items?folder=&path=` (ADR 0014): live items with a file in NAS folder [folder] under directory [path]
     * (sub-directories included; `""` = the whole folder), newest first, keyset-paged.
     */
    fun itemsInFolder(folder: String, path: String, cursor: String?, limit: Int): ItemsQueryPage {
        val q = StringBuilder("/v1/items?folder=${enc(folder)}&limit=$limit")
        if (path.isNotEmpty()) q.append("&path=${enc(path)}")
        if (!cursor.isNullOrEmpty()) q.append("&cursor=${enc(cursor)}")
        return get(q.toString(), ItemsQueryPage.serializer())
    }

    /** `POST /v1/items/by-path` (ADR 0014, hoard-server 0.10.0, feature `items-by-path`): at most 1000 paths. */
    fun itemsByPath(folder: String, paths: List<String>): ByPathResponse =
        send("POST", "/v1/items/by-path", ByPathRequest(folder, paths), ByPathRequest.serializer(), ByPathResponse.serializer())

    /**
     * `POST /v1/nas/walk` (ADR 0023, feature `trash`): asks for a walk of these NAS folders (≤ 100), coalesced by the
     * server; `404` for an unknown folder id.
     */
    fun nasWalk(folders: List<String>): NasWalkResponse =
        send("POST", "/v1/nas/walk", NasWalkRequest(folders), NasWalkRequest.serializer(), NasWalkResponse.serializer())

    fun lookup(fingerprints: List<String>): LookupResponse =
        send("POST", "/v1/items/lookup", LookupRequest(fingerprints), LookupRequest.serializer(), LookupResponse.serializer())

    fun pushCulling(changes: List<CullingChange>): CullingPushResponse =
        send("POST", "/v1/culling", CullingPush(changes), CullingPush.serializer(), CullingPushResponse.serializer())

    fun warm(request: WarmRequest): WarmResponse = send("POST", "/v1/items/previews", request, WarmRequest.serializer(), WarmResponse.serializer())

    /** `/v1/items/{id}/preview` (or `poster`) path with its query; [edit] = `primary` or a version id (ADR 0020). */
    fun previewPath(itemId: String, size: String, format: String, v: String? = null, edit: String? = null, kind: String = "preview"): String =
        "/v1/items/${enc(itemId)}/$kind?size=$size&format=$format" + (v?.let { "&v=${enc(it)}" } ?: "") + (edit?.let { "&edit=${enc(it)}" } ?: "")

    // --- Phone backup (ADR 0024, hoard-server 0.12.0, feature `backup`) ---------------------------------------------

    fun backupRoots(): BackupRoots = get("/v1/backup/roots", BackupRoots.serializer())

    /** `201` new, `200` existing (resumed); `409` reason `covered` (`details.rootId`); `503 storage_not_configured`. */
    fun createBackupRoot(request: BackupRootCreate): BackupRoot =
        send("POST", "/v1/backup/roots", request, BackupRootCreate.serializer(), BackupRoot.serializer())

    fun patchBackupRoot(rootId: String, patch: BackupRootPatch): BackupRoot =
        send("PATCH", "/v1/backup/roots/${enc(rootId)}", patch, BackupRootPatch.serializer(), BackupRoot.serializer())

    /** Stops a root (`DELETE`): no new entries; the server keeps its mirror. */
    fun stopBackupRoot(rootId: String) {
        request("DELETE", "/v1/backup/roots/${enc(rootId)}").use { it.readText() }
    }

    fun backupReport(report: BackupReport): BackupReportResponse =
        send("POST", "/v1/backup/report", report, BackupReport.serializer(), BackupReportResponse.serializer())

    fun backupVerify(request: BackupVerifyRequest): BackupVerifyResponse = backupVerifyDated(request).value

    /** `POST /v1/backup/verify` with the server's clock (its `Date` header): how old each answer's re-hash is. */
    fun backupVerifyDated(request: BackupVerifyRequest): Dated<BackupVerifyResponse> =
        request("POST", "/v1/backup/verify", ServerJson.encodeToString(BackupVerifyRequest.serializer(), request)).use {
            Dated(ServerJson.decodeFromString(BackupVerifyResponse.serializer(), it.readText()), it.date())
        }

    // --- Chunked uploads (ADR 0013 "Blobs") --------------------------------------------------------------------------

    fun createUpload(request: UploadCreate): UploadState =
        send("POST", "/v1/uploads", request, UploadCreate.serializer(), UploadState.serializer())

    /** `HEAD /v1/uploads/{id}`: the server's offset, or null when it does not know the upload (expired, done). */
    fun uploadOffset(uploadId: String): Long? {
        val conn = open("HEAD", "/v1/uploads/${enc(uploadId)}", auth = true)
        try {
            val status = conn.responseCode
            if (status == 404) return null
            if (status !in 200..299) throw readError(conn, status)
            return conn.getHeaderField("Upload-Offset")?.toLongOrNull()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * `PATCH /v1/uploads/{id}`: [length] bytes at [offset], written by [write]. The server's new offset and whether the
     * upload completed; a refusal (`offset_mismatch` with `details.offset`, `checksum_mismatch`, an interrupted chunk)
     * is a [ServerException].
     */
    fun patchUpload(uploadId: String, offset: Long, length: Int, write: (java.io.OutputStream) -> Unit): ChunkResult {
        val conn = open("PATCH", "/v1/uploads/${enc(uploadId)}", auth = true)
        try {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(length.toLong())
            conn.setRequestProperty("Content-Type", "application/offset+octet-stream")
            conn.setRequestProperty("Upload-Offset", offset.toString())
            conn.readTimeout = maxOf(readTimeoutMs, 120_000)
            conn.outputStream.use(write)
            val status = conn.responseCode
            if (status !in 200..299) throw readError(conn, status)
            runCatching { conn.inputStream.close() }
            return ChunkResult(
                offset = conn.getHeaderField("Upload-Offset")?.toLongOrNull() ?: (offset + length),
                complete = conn.getHeaderField("Upload-Complete") == "1",
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Abandons an upload (`DELETE /v1/uploads/{id}`); an unknown one is fine. */
    fun deleteUpload(uploadId: String) {
        val conn = open("DELETE", "/v1/uploads/${enc(uploadId)}", auth = true)
        try {
            val status = conn.responseCode
            if (status != 404 && status !in 200..299) throw readError(conn, status)
        } finally {
            conn.disconnect()
        }
    }

    /** Where requests go: [baseUrl], or the loopback relay for a plain-HTTP LAN / Tailscale server ([LanRelay]). */
    private fun target(): String = dev.hfps.hoard.server.net.LanRelay.route(baseUrl)

    /** The video proxy's URL for this process's player (through the relay for a plain-HTTP server). */
    fun proxyUrl(itemId: String): String = target() + "/v1/items/${enc(itemId)}/proxy"

    fun originalUrl(fingerprint: String): String = target() + "/v1/originals/${enc(fingerprint)}"

    /** Headers a player or a hand-off needs to read [proxyUrl] / [originalUrl] itself. */
    fun authHeaders(): Map<String, String> = key()?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    /** Downloads a preview into [target] (written through a temporary file, renamed when complete). */
    fun downloadPreview(path: String, target: File): PreviewResult {
        val conn = open("GET", path, auth = true)
        try {
            when (val status = conn.responseCode) {
                200 -> {
                    // A unique temporary file per download, renamed over the target when complete (atomic on one
                    // file system): concurrent downloads of one preview never write the same file.
                    target.parentFile?.mkdirs()
                    val tmp = File.createTempFile(target.name + ".", ".part", target.parentFile)
                    try {
                        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
                    } catch (e: IOException) {
                        tmp.delete()
                        throw e
                    }
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        throw IOException("could not store the preview")
                    }
                    return PreviewResult.Ready(target, conn.getHeaderField("X-Hoard-Preview-Version"), conn.getHeaderField("X-Hoard-Preview") == "stale")
                }
                202 -> {
                    conn.errorStream?.close() ?: runCatching { conn.inputStream.close() }
                    return PreviewResult.Pending(conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 2)
                }
                404 -> {
                    val error = readError(conn, status)
                    if (error.code == "preview_unavailable") return PreviewResult.Unavailable(reasonOf(error))
                    throw error
                }
                else -> throw readError(conn, status)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Bytes `[start, start + length)` of an original (`GET /v1/originals/{fingerprint}` with `Range`), and its total size. */
    fun readOriginal(fingerprint: String, start: Long, length: Int, into: ByteArray, offset: Int = 0): Pair<Int, Long> {
        val conn = open("GET", "/v1/originals/${enc(fingerprint)}", auth = true)
        conn.setRequestProperty("Range", "bytes=$start-${start + length - 1}")
        try {
            val status = conn.responseCode
            if (status == 416) return 0 to (conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: start)
            if (status != 206 && status != 200) throw readError(conn, status)
            val total = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: conn.contentLengthLong
            conn.inputStream.use { input ->
                if (status == 200 && start > 0) input.skipFully(start)
                var done = 0
                while (done < length) {
                    val n = input.read(into, offset + done, length - done)
                    if (n < 0) break
                    done += n
                }
                return done to total
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * `GET /v1/originals/{fingerprint}` from [start] to the end (`Range: bytes=<start>-`), as a stream the caller closes
     * (ADR 0024 A2: "Save to this device" through the transfer engine). A server that ignores the range (`200`) is
     * skipped to [start]. Throws [ServerException] for an error answer (`404`: the server no longer holds it).
     */
    fun openOriginal(fingerprint: String, start: Long): InputStream {
        val conn = open("GET", "/v1/originals/${enc(fingerprint)}", auth = true)
        if (start > 0) conn.setRequestProperty("Range", "bytes=$start-")
        val status = try {
            conn.responseCode
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
        if (status != 206 && status != 200) {
            val error = readError(conn, status)
            conn.disconnect()
            throw error
        }
        val input = conn.inputStream
        if (status == 200 && start > 0) input.skipFully(start)
        return object : java.io.FilterInputStream(input) {
            override fun close() {
                runCatching { super.close() }
                conn.disconnect()
            }
        }
    }

    /** `HEAD /v1/originals/{fingerprint}`: the size, or null when the server does not hold it. */
    fun originalSize(fingerprint: String): Long? {
        val conn = open("HEAD", "/v1/originals/${enc(fingerprint)}", auth = true)
        try {
            val status = conn.responseCode
            if (status == 404) return null
            if (status !in 200..299) throw readError(conn, status)
            return conn.contentLengthLong.takeIf { it >= 0 }
        } finally {
            conn.disconnect()
        }
    }

    // --- Plumbing --------------------------------------------------------------------------------------------------

    private fun <T> get(path: String, out: KSerializer<T>): T = request("GET", path).use { ServerJson.decodeFromString(out, it.readText()) }

    private fun <B, T> send(method: String, path: String, body: B, inSer: KSerializer<B>, out: KSerializer<T>, auth: Boolean = true): T =
        request(method, path, ServerJson.encodeToString(inSer, body), auth).use { ServerJson.decodeFromString(out, it.readText()) }

    private class Response(val conn: HttpURLConnection, val stream: InputStream) : AutoCloseable {
        fun readText(): String = stream.readBytes().toString(Charsets.UTF_8)

        /** The response's `Date` header (the server's clock), Unix ms; null when absent. */
        fun date(): Long? = conn.getHeaderFieldDate("Date", -1L).takeIf { it > 0 }

        override fun close() {
            runCatching { stream.close() }
            conn.disconnect()
        }
    }

    private fun request(method: String, path: String, body: String? = null, auth: Boolean = true): Response {
        val conn = open(method, path, auth)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = conn.responseCode
        if (status !in 200..299) {
            val error = readError(conn, status)
            conn.disconnect()
            throw error
        }
        return Response(conn, conn.inputStream)
    }

    private fun open(method: String, path: String, auth: Boolean): HttpURLConnection {
        val conn = URL(target() + path).openConnection() as HttpURLConnection
        // Android's HttpURLConnection takes PATCH (PATCH /v1/device, ADR 0017); the JDK's needs [setMethod].
        setMethod(conn, method)
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", userAgent)
        if (auth) key()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        return conn
    }

    private fun readError(conn: HttpURLConnection, status: Int): ServerException {
        val text = runCatching { (conn.errorStream ?: conn.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
        val info = runCatching { ServerJson.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
        val retry = conn.getHeaderField("Retry-After")?.toLongOrNull()
        val details = info?.details as? kotlinx.serialization.json.JsonObject
        val reason = runCatching { details?.get("reason")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } }.getOrNull()
        return ServerException(status, info?.code ?: "http_$status", info?.message ?: text.take(200), retry, reason, details)
    }

    private fun reasonOf(error: ServerException): String? = error.reason

    companion object {
        fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        /** `HttpURLConnection` refuses PATCH by name; set the protected `method` field (every JDK and Android). */
        internal fun setMethod(conn: HttpURLConnection, method: String) {
            try {
                conn.requestMethod = method
            } catch (_: java.net.ProtocolException) {
                var type: Class<*>? = conn.javaClass
                var done = false
                while (type != null && !done) {
                    runCatching {
                        val field = type.getDeclaredField("method")
                        field.isAccessible = true
                        field.set(conn, method)
                        done = true
                    }
                    type = type.superclass
                }
                // A wrapping implementation (HttpsURLConnectionImpl) keeps the real connection in `delegate`.
                if (!done) {
                    runCatching {
                        val delegate = conn.javaClass.getDeclaredField("delegate").apply { isAccessible = true }.get(conn)
                        var t: Class<*>? = delegate.javaClass
                        while (t != null) {
                            runCatching { t.getDeclaredField("method").apply { isAccessible = true }.set(delegate, method); return }
                            t = t.superclass
                        }
                    }
                    throw java.net.ProtocolException("PATCH not supported")
                }
            }
        }
    }
}

private fun InputStream.skipFully(n: Long) {
    var left = n
    while (left > 0) {
        val skipped = skip(left)
        if (skipped <= 0) {
            if (read() < 0) return
            left--
        } else {
            left -= skipped
        }
    }
}
