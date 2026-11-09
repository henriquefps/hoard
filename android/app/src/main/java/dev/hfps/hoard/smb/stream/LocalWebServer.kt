package dev.hfps.hoard.smb.stream

import android.util.Log
import dev.hfps.hoard.smb.SmbPaths
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A parsed HTTP/1.x request head. Header names are lower-cased. */
data class HttpRequest(val method: String, val target: String, val version: String, val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]

    /** HTTP/1.1 keeps the connection unless `Connection: close`; 1.0 only with `keep-alive`. */
    val keepAlive: Boolean
        get() {
            val connection = header("connection")?.lowercase(Locale.ROOT)
            return if (version == "HTTP/1.1") connection?.contains("close") != true else connection?.contains("keep-alive") == true
        }
}

/** Reads one request head from a stream (pure, JVM-tested). */
object HttpRequests {
    const val MAX_HEAD_BYTES = 16 * 1024

    class BadRequest(message: String) : IOException(message)

    /** Null at a clean end of stream before any byte (the client closed a kept-alive connection). */
    fun read(input: InputStream): HttpRequest? {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        var total = 0
        var previous = -1
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (total == 0) return null
                throw BadRequest("Connection closed mid-request")
            }
            if (++total > MAX_HEAD_BYTES) throw BadRequest("Request head too large")
            if (b == '\n'.code) {
                if (previous == '\r'.code && line.isNotEmpty()) line.setLength(line.length - 1)
                if (line.isEmpty()) {
                    if (lines.isEmpty()) continue // tolerate a stray CRLF between requests
                    break
                }
                lines += line.toString()
                line.setLength(0)
            } else {
                line.append(b.toChar()) // ISO-8859-1: targets are percent-encoded ASCII
            }
            previous = b
        }
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw BadRequest("Malformed request line")
        val headers = HashMap<String, String>()
        for (header in lines.drop(1)) {
            val colon = header.indexOf(':')
            if (colon <= 0) throw BadRequest("Malformed header")
            headers[header.substring(0, colon).trim().lowercase(Locale.ROOT)] = header.substring(colon + 1).trim()
        }
        return HttpRequest(parts[0], parts[1], parts[2], headers)
    }
}

/**
 * The local web server: HTTP/1.1 on **127.0.0.1 only**, on a random port, started when the first
 * HTTP token is created and stopped with the last one ([StreamRegistry]).
 *
 * Every URL is `/<token>/<path>`: the token (256 random bits) names a [StreamEntry] — a folder tree
 * (HTML page + assets) or a single file — and the path is resolved below that entry's root only
 * ([WebPaths] refuses `..`, encoded separators and names SMB cannot have). Unknown tokens are 404.
 * GET and HEAD, single-range `Range` requests (206/416), keep-alive, directory → `index.html` or a
 * plain listing. `Referrer-Policy: no-referrer` keeps the token out of requests to other sites, and
 * the `Host` header must be the loopback address (no DNS-rebinding access from web pages).
 */
object LocalWebServer {
    private const val TAG = "HoardWeb"
    private const val SOCKET_TIMEOUT_MS = 30_000
    private const val BUFFER = 64 * 1024

    @Volatile private var server: ServerSocket? = null
    private val pool = ThreadPoolExecutor(0, 24, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "hoard-web").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = server?.isClosed == false

    /** Start (once) and return the port. */
    @Synchronized
    fun ensureStarted(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = socket
        Thread({ acceptLoop(socket) }, "hoard-web-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "Listening on 127.0.0.1:${socket.localPort}")
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        val socket = server ?: return
        server = null
        runCatching { socket.close() }
        Log.i(TAG, "Stopped")
    }

    fun baseUrl(): String = "http://127.0.0.1:${ensureStarted()}"

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            try {
                pool.execute { serve(client, socket.localPort) }
            } catch (_: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket, port: Int) {
        client.use { socket ->
            try {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream(), BUFFER)
                while (true) {
                    val request = try {
                        HttpRequests.read(input) ?: return
                    } catch (_: HttpRequests.BadRequest) {
                        simple(output, 400, "Bad Request", keepAlive = false, head = false)
                        return
                    }
                    val keepAlive = handle(request, output, port) && request.keepAlive
                    output.flush()
                    if (!keepAlive) return
                }
            } catch (_: SocketTimeoutException) {
                // Idle kept-alive connection.
            } catch (_: SocketException) {
                // The client went away (a player seeking closes its previous request mid-body).
            } catch (e: IOException) {
                Log.d(TAG, "Connection ended: ${e.message}")
            }
        }
    }

    /** Answer one request. Returns false when the connection must close (body not fully sent). */
    private fun handle(request: HttpRequest, out: OutputStream, port: Int): Boolean {
        val head = request.method == "HEAD"
        if (request.method != "GET" && !head) return simple(out, 405, "Method Not Allowed", head = false, extra = mapOf("Allow" to "GET, HEAD"))
        val host = request.header("host")?.lowercase(Locale.ROOT)
        if (host != "127.0.0.1:$port" && host != "localhost:$port") return simple(out, 403, "Forbidden", head = head)
        val target = WebPaths.parse(request.target) ?: return simple(out, 404, "Not Found", head = head)
        val entry = StreamRegistry.get(target.token) ?: return simple(out, 404, "Not Found", head = head)
        entry.activeRequests.incrementAndGet()
        entry.touch()
        try {
            return when (entry.kind) {
                StreamKind.FILE -> serveSingleFile(entry, target, request, out, head)
                StreamKind.FOLDER -> serveFolder(entry, target, request, out, head)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Request failed: ${e.message}")
            return simple(out, 502, "Bad Gateway", head = head)
        } finally {
            entry.activeRequests.decrementAndGet()
            entry.touch()
        }
    }

    private fun serveSingleFile(entry: StreamEntry, target: WebTarget, request: HttpRequest, out: OutputStream, head: Boolean): Boolean {
        if (!entry.http || target.path != entry.name) return simple(out, 404, "Not Found", head = head)
        return sendFile(entry, entry.path, entry.name, entry.size, request, out, head)
    }

    private fun serveFolder(entry: StreamEntry, target: WebTarget, request: HttpRequest, out: OutputStream, head: Boolean): Boolean {
        val remote = if (target.path.isEmpty()) entry.path else SmbPaths.join(entry.path, target.path)
        val info = entry.source.stat(remote) ?: return simple(out, 404, "Not Found", head = head)
        if (!info.isDirectory) return sendFile(entry, remote, SmbPaths.name(remote), info.size, request, out, head)
        if (!target.trailingSlash && target.path.isNotEmpty()) {
            val location = "/${target.token}/${WebPaths.encodePath(target.path)}/"
            return simple(out, 301, "Moved Permanently", head = head, extra = mapOf("Location" to location))
        }
        for (index in listOf("index.html", "index.htm")) {
            val candidate = SmbPaths.join(remote, index)
            val indexInfo = entry.source.stat(candidate)
            if (indexInfo != null && !indexInfo.isDirectory) return sendFile(entry, candidate, index, indexInfo.size, request, out, head)
        }
        val body = DirectoryIndex.html(if (target.path.isEmpty()) SmbPaths.name(entry.path).ifEmpty { entry.source.share } else SmbPaths.name(target.path), entry.source.list(remote), target.path.isNotEmpty())
            .toByteArray(Charsets.UTF_8)
        writeHead(out, 200, "OK", "text/html; charset=utf-8", body.size.toLong(), keepAlive = true)
        if (!head) out.write(body)
        return true
    }

    private fun sendFile(entry: StreamEntry, remotePath: String, name: String, size: Long, request: HttpRequest, out: OutputStream, head: Boolean): Boolean {
        val type = if (entry.kind == StreamKind.FILE) entry.mimeType else MimeTypes.forHttp(name)
        val range = when (val parsed = HttpRanges.parse(request.header("range"), size)) {
            is RangeRequest.Unsatisfiable ->
                return simple(out, 416, "Range Not Satisfiable", head = head, extra = mapOf("Content-Range" to "bytes */$size"))
            is RangeRequest.Partial -> parsed.range
            RangeRequest.Full -> null
        }
        val start = range?.start ?: 0L
        val length = range?.length ?: size
        if (range != null) {
            writeHead(out, 206, "Partial Content", type, length, keepAlive = true, extra = mapOf("Content-Range" to "bytes ${range.start}-${range.end}/$size"))
        } else {
            writeHead(out, 200, "OK", type, length, keepAlive = true)
        }
        if (head || length == 0L) return true
        RemoteReader(entry.source, remotePath, size, entry.fetched).use { reader ->
            val buffer = ByteArray(BUFFER)
            var position = start
            val end = start + length
            while (position < end) {
                val n = reader.read(position, minOf(BUFFER.toLong(), end - position).toInt(), buffer)
                if (n <= 0) return false // the file shrank: the Content-Length is now a lie, close
                out.write(buffer, 0, n)
                position += n
                entry.served.addAndGet(n.toLong())
                entry.touch()
            }
        }
        return true
    }

    private fun writeHead(
        out: OutputStream,
        status: Int,
        reason: String,
        type: String,
        length: Long,
        keepAlive: Boolean,
        extra: Map<String, String> = emptyMap(),
    ) {
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            .append("Content-Type: ").append(type).append("\r\n")
            .append("Content-Length: ").append(length).append("\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .append("Cache-Control: no-cache\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
            .append("Referrer-Policy: no-referrer\r\n")
            .append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
        extra.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
    }

    private fun simple(
        out: OutputStream,
        status: Int,
        reason: String,
        head: Boolean,
        keepAlive: Boolean = true,
        extra: Map<String, String> = emptyMap(),
    ): Boolean {
        val body = "$status $reason\n".toByteArray(Charsets.UTF_8)
        writeHead(out, status, reason, "text/plain; charset=utf-8", body.size.toLong(), keepAlive, extra)
        if (!head) out.write(body)
        return keepAlive
    }
}

/** The plain listing shown for a folder without `index.html` (pure, JVM-tested). */
object DirectoryIndex {
    fun html(title: String, entries: List<Pair<String, Boolean>>, hasParent: Boolean): String {
        val sorted = entries.sortedWith(compareBy<Pair<String, Boolean>>({ !it.second }, { it.first.lowercase(Locale.ROOT) }))
        val items = buildString {
            if (hasParent) append("<li><a href=\"../\">../</a></li>\n")
            for ((name, isDirectory) in sorted) {
                val href = WebPaths.encodeSegment(name) + if (isDirectory) "/" else ""
                append("<li><a href=\"").append(escape(href)).append("\">").append(escape(name)).append(if (isDirectory) "/" else "").append("</a></li>\n")
            }
        }
        return "<!doctype html>\n<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>${escape(title)}</title><style>body{font:16px system-ui,sans-serif;margin:16px}li{padding:6px 0}</style></head>" +
            "<body><h1>${escape(title)}</h1><ul>\n$items</ul></body></html>\n"
    }

    fun escape(text: String): String = buildString {
        for (c in text) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
