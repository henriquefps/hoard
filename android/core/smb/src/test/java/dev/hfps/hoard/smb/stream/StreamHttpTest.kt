package dev.hfps.hoard.smb.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class StreamHttpTest {

    private val token = "0123456789abcdef".repeat(4)

    // --- Tokens ---------------------------------------------------------------------------

    @Test
    fun `tokens are 256 random bits in hex`() {
        val a = StreamTokens.generate()
        val b = StreamTokens.generate()
        assertEquals(64, a.length)
        assertTrue(StreamTokens.isWellFormed(a))
        assertNotEquals(a, b)
    }

    @Test
    fun `token shape and comparison`() {
        assertTrue(StreamTokens.isWellFormed(token))
        assertFalse(StreamTokens.isWellFormed(null))
        assertFalse(StreamTokens.isWellFormed(token.dropLast(1)))
        assertFalse(StreamTokens.isWellFormed(token.uppercase()))
        assertFalse(StreamTokens.isWellFormed(token.dropLast(1) + "/"))
        assertTrue(StreamTokens.matches(token, token))
        assertFalse(StreamTokens.matches(token, token.dropLast(1) + "0"))
        assertFalse(StreamTokens.matches(token, null))
        assertFalse(StreamTokens.matches(token, ""))
    }

    // --- Range ----------------------------------------------------------------------------

    @Test
    fun `range forms`() {
        assertEquals(RangeRequest.Full, HttpRanges.parse(null, 1000))
        assertEquals(RangeRequest.Partial(ByteRange(0, 999)), HttpRanges.parse("bytes=0-", 1000))
        assertEquals(RangeRequest.Partial(ByteRange(100, 199)), HttpRanges.parse("bytes=100-199", 1000))
        assertEquals(RangeRequest.Partial(ByteRange(900, 999)), HttpRanges.parse("bytes=-100", 1000))
        assertEquals(RangeRequest.Partial(ByteRange(0, 999)), HttpRanges.parse("bytes=-5000", 1000))
        assertEquals(RangeRequest.Partial(ByteRange(500, 999)), HttpRanges.parse("bytes=500-5000", 1000))
        assertEquals(RangeRequest.Partial(ByteRange(999, 999)), HttpRanges.parse("Bytes=999-999", 1000))
        assertEquals(100L, ByteRange(100, 199).length)
    }

    @Test
    fun `huge offsets for files over 4 GB`() {
        val size = 6L * 1024 * 1024 * 1024
        assertEquals(RangeRequest.Partial(ByteRange(5_000_000_000, size - 1)), HttpRanges.parse("bytes=5000000000-", size))
    }

    @Test
    fun `unsatisfiable ranges`() {
        assertEquals(RangeRequest.Unsatisfiable, HttpRanges.parse("bytes=1000-", 1000))
        assertEquals(RangeRequest.Unsatisfiable, HttpRanges.parse("bytes=2000-3000", 1000))
        assertEquals(RangeRequest.Unsatisfiable, HttpRanges.parse("bytes=-0", 1000))
        assertEquals(RangeRequest.Unsatisfiable, HttpRanges.parse("bytes=0-", 0))
    }

    @Test
    fun `malformed or unsupported ranges are ignored`() {
        for (header in listOf("", "bytes=", "bytes=abc-", "bytes=1-a", "items=0-10", "bytes=0-10,20-30", "bytes=10-5", "bytes=-", "bytes=+1-2", "bytes=1")) {
            assertEquals(header, RangeRequest.Full, HttpRanges.parse(header, 1000))
        }
    }

    // --- MIME -----------------------------------------------------------------------------

    @Test
    fun `mime types`() {
        assertEquals("text/html; charset=utf-8", MimeTypes.forHttp("index.HTML"))
        assertEquals("text/css; charset=utf-8", MimeTypes.forHttp("style.css"))
        assertEquals("text/javascript; charset=utf-8", MimeTypes.forHttp("app.mjs"))
        assertEquals("image/svg+xml", MimeTypes.forHttp("logo.svg"))
        assertEquals("font/woff2", MimeTypes.forHttp("f.woff2"))
        assertEquals("application/wasm", MimeTypes.forHttp("m.wasm"))
        assertEquals("application/octet-stream", MimeTypes.forHttp("README"))
        assertEquals("application/octet-stream", MimeTypes.forHttp(".bashrc"))
        assertEquals("video/x-matroska", MimeTypes.forIntent("Movie.MKV"))
        assertEquals("video/mp4", MimeTypes.forIntent("a.mp4"))
        assertEquals("audio/flac", MimeTypes.forIntent("a.flac"))
        assertEquals("text/html", MimeTypes.forIntent("a.htm"))
        assertNull(MimeTypes.forIntent("a.unknownext"))
    }

    // --- Paths ----------------------------------------------------------------------------

    @Test
    fun `request targets map below the token`() {
        assertEquals(WebTarget(token, "index.html", false), WebPaths.parse("/$token/index.html"))
        assertEquals(WebTarget(token, "css/site.css", false), WebPaths.parse("/$token/css/site.css?v=3#x"))
        assertEquals(WebTarget(token, "", false), WebPaths.parse("/$token"))
        assertEquals(WebTarget(token, "", true), WebPaths.parse("/$token/"))
        assertEquals(WebTarget(token, "img", true), WebPaths.parse("/$token/img/"))
        assertEquals(WebTarget(token, "a b/ç+1.png", false), WebPaths.parse("/$token/a%20b/%C3%A7+1.png"))
        assertEquals(WebTarget(token, "a/b", false), WebPaths.parse("/$token//a///b"))
    }

    @Test
    fun `escapes and junk are refused`() {
        val bad = listOf(
            "$token/x", // no leading slash
            "/$token/../secret.txt",
            "/$token/a/../../x",
            "/$token/%2e%2e/x",
            "/$token/%2E%2E/x",
            "/$token/./x",
            "/$token/a%2fb",
            "/$token/a%2Fb",
            "/$token/a%5cb",
            "/$token/a\\b",
            "/$token/a%00b",
            "/$token/a%0Ab",
            "/$token/c:x",
            "/$token/a%3Ab",
            "/$token/a*b",
            "/$token/bad%zz",
            "/notatoken/index.html",
            "/" + token.uppercase() + "/x",
            "http://127.0.0.1:1/$token/x",
        )
        for (target in bad) assertNull(target, WebPaths.parse(target))
    }

    @Test
    fun `encode segments for urls we hand out`() {
        assertEquals("My%20Page%20%23%201.html", WebPaths.encodeSegment("My Page # 1.html"))
        assertEquals("%C3%A7%3F", WebPaths.encodeSegment("ç?"))
        assertEquals("a/b%20c", WebPaths.encodePath("/a//b c/"))
        // Round trip through the parser.
        val name = "Relatório final (v2) #1.html"
        assertEquals(name, WebPaths.parse("/$token/" + WebPaths.encodeSegment(name))!!.path)
    }

    // --- Requests -------------------------------------------------------------------------

    private fun request(text: String) = HttpRequests.read(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)))

    @Test
    fun `parse a request head`() {
        val r = request("GET /$token/a.css HTTP/1.1\r\nHost: 127.0.0.1:4000\r\nRange: bytes=0-\r\n\r\n")!!
        assertEquals("GET", r.method)
        assertEquals("/$token/a.css", r.target)
        assertEquals("127.0.0.1:4000", r.header("HOST"))
        assertEquals("bytes=0-", r.header("range"))
        assertTrue(r.keepAlive)
        assertFalse(request("GET / HTTP/1.1\r\nConnection: close\r\n\r\n")!!.keepAlive)
        assertFalse(request("GET / HTTP/1.0\r\n\r\n")!!.keepAlive)
        assertTrue(request("GET / HTTP/1.0\r\nConnection: Keep-Alive\r\n\r\n")!!.keepAlive)
        // Bare LF line endings are tolerated.
        assertEquals("HEAD", request("HEAD /x HTTP/1.1\nHost: a\n\n")!!.method)
        // End of stream before any byte: the client closed a kept-alive connection.
        assertNull(request(""))
    }

    @Test
    fun `bad request heads`() {
        for (text in listOf("GET /\r\n\r\n", "GET / HTTP/2\r\n\r\n", "GET / HTTP/1.1\r\nNoColon\r\n\r\n", "GET / HTTP/1.1\r\nHost: a")) {
            try {
                request(text)
                throw AssertionError("expected BadRequest for $text")
            } catch (_: HttpRequests.BadRequest) {
            }
        }
        try {
            request("GET /" + "a".repeat(HttpRequests.MAX_HEAD_BYTES) + " HTTP/1.1\r\n\r\n")
            throw AssertionError("expected BadRequest")
        } catch (_: HttpRequests.BadRequest) {
        }
    }

    // --- Directory index and expiry ------------------------------------------------------

    @Test
    fun `directory index escapes names`() {
        val html = DirectoryIndex.html("<docs>", listOf("b.html" to false, "img" to true, "x\"<y>.txt" to false), hasParent = true)
        assertTrue(html.contains("<title>&lt;docs&gt;</title>"))
        assertTrue(html.contains("href=\"../\""))
        assertTrue(html.indexOf("img/") < html.indexOf("b.html")) // folders first
        assertTrue(html.contains("href=\"x%22%3Cy%3E.txt\""))
        assertTrue(html.contains(">x&quot;&lt;y&gt;.txt<"))
    }

    @Test
    fun `expiry rules`() {
        val now = 10_000_000L
        fun state(kind: StreamKind, busy: Int = 0, holders: Int = 0, idleMs: Long) = StreamExpiry.State(kind, busy, holders, now - idleMs)
        assertFalse(StreamExpiry.isExpired(state(StreamKind.FILE, idleMs = StreamExpiry.FILE_IDLE_MS - 1), now))
        assertTrue(StreamExpiry.isExpired(state(StreamKind.FILE, idleMs = StreamExpiry.FILE_IDLE_MS), now))
        // An open FD (VLC paused for an hour) or a running response never expires.
        assertFalse(StreamExpiry.isExpired(state(StreamKind.FILE, busy = 1, idleMs = 10 * StreamExpiry.FILE_IDLE_MS), now))
        // A page open in the in-app viewer never expires; handed to Chrome it lasts longer than a file.
        assertFalse(StreamExpiry.isExpired(state(StreamKind.FOLDER, holders = 1, idleMs = 10 * StreamExpiry.FOLDER_IDLE_MS), now))
        assertFalse(StreamExpiry.isExpired(state(StreamKind.FOLDER, idleMs = StreamExpiry.FILE_IDLE_MS), now))
        assertTrue(StreamExpiry.isExpired(state(StreamKind.FOLDER, idleMs = StreamExpiry.FOLDER_IDLE_MS), now))
        // A hand-off link's lease (Reveal reading) sets its own, shorter idle time: the service stops soon after.
        val lease = dev.hfps.hoard.smb.handoff.HandoffServer.LEASE_IDLE_MS
        assertFalse(StreamExpiry.isExpired(StreamExpiry.State(StreamKind.FILE, 0, 0, now - lease + 1, lease), now))
        assertTrue(StreamExpiry.isExpired(StreamExpiry.State(StreamKind.FILE, 0, 0, now - lease, lease), now))
        assertFalse(StreamExpiry.isExpired(StreamExpiry.State(StreamKind.FILE, 1, 0, now - 10 * lease, lease), now))
    }
}
