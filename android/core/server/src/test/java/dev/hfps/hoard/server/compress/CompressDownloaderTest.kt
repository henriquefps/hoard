package dev.hfps.hoard.server.compress

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.api.ServerApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ADR 0025 A2: the output's range download. A dropped connection (a body shorter than its length) resumes at the bytes
 * that arrived; a run stopped mid-way resumes from the saved offset with `If-Range`; a file that changed meanwhile
 * (another ETag) is sent whole and the part starts over; `404` is not found.
 */
class CompressDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeServer()
    private val api = ServerApi(server.url, { server.key })
    private var body = ByteArray(300_000) { (it * 7 + 3).toByte() }
    private var etag = "\"sha-one\""
    /** Cut the next N responses after this many body bytes (a dropped connection). */
    private var cutAfter: Int? = null
    private var cuts = 0
    private var ignoreRange = false
    private var missing = false
    val ranges = ArrayList<Pair<String?, String?>>()

    init {
        server.extra = handler@{ ex, path, _, _ ->
            if (path != "/v1/compress/items/7/output") return@handler false
            if (missing) {
                server.send(ex, 404, """{"error":{"code":"not_found","message":"no"}}""")
                return@handler true
            }
            val range = ex.requestHeaders.getFirst("Range")
            val ifRange = ex.requestHeaders.getFirst("If-Range")
            ranges += range to ifRange
            ex.responseHeaders.add("ETag", etag)
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
            if (start == null || ignoreRange || (ifRange != null && ifRange != etag)) {
                send(ex, 200, body, 0)
            } else {
                if (start >= body.size) {
                    ex.responseHeaders.add("Content-Range", "bytes */${body.size}")
                    ex.sendResponseHeaders(416, -1)
                    ex.close()
                    return@handler true
                }
                ex.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                send(ex, 206, body, start.toInt())
            }
            true
        }
    }

    private fun send(ex: FakeServer.HttpExchange, status: Int, bytes: ByteArray, from: Int) {
        val part = bytes.copyOfRange(from, bytes.size)
        ex.sendResponseHeaders(status, part.size.toLong())
        val cut = cutAfter
        if (cut != null && cuts > 0) {
            cuts--
            ex.responseBody.write(part, 0, minOf(cut, part.size))
            ex.close()
            return
        }
        ex.responseBody.write(part)
        ex.close()
    }

    @After
    fun tearDown() = server.close()

    private class Saved(var offset: Long = 0, var etag: String? = null)

    private fun download(part: File, saved: Saved, stopAfter: Long? = null) = runBlocking {
        CompressDownloader(api, chunk = 16_384, sleep = {}).download(
            path = api.compressOutputPath("7"),
            part = part,
            size = body.size.toLong(),
            offset = saved.offset,
            etag = saved.etag,
            save = { off, tag -> saved.offset = off; saved.etag = tag },
            stopped = { stopAfter != null && saved.offset >= stopAfter },
        )
    }

    @Test
    fun aDroppedConnectionResumesAtTheBytesThatArrived() {
        cutAfter = 100_000
        cuts = 2
        val part = File(tmp.root, "7.part")
        val saved = Saved()
        assertThat(download(part, saved)).isEqualTo(DownloadOutcome.Done)
        assertThat(part.readBytes()).isEqualTo(body)
        // The first request from 0, then two resumes on the same ETag.
        assertThat(ranges.first().first).isNull()
        assertThat(ranges.drop(1).map { it.second }.toSet()).containsExactly(etag)
        assertThat(ranges.drop(1).map { it.first }).containsExactly("bytes=100000-", "bytes=200000-").inOrder()
    }

    @Test
    fun aStoppedRunResumesFromTheSavedOffsetWithIfRange() {
        val part = File(tmp.root, "7.part")
        val saved = Saved()
        assertThat(download(part, saved, stopAfter = 50_000)).isEqualTo(DownloadOutcome.Stopped)
        assertThat(saved.offset).isAtLeast(50_000L)
        assertThat(saved.etag).isEqualTo(etag)
        // Bytes written after the last saved offset (a kill between the write and the save) are dropped.
        part.appendBytes(ByteArray(1234))
        val from = saved.offset
        ranges.clear()
        assertThat(download(part, saved)).isEqualTo(DownloadOutcome.Done)
        assertThat(ranges.single()).isEqualTo("bytes=$from-" to etag)
        assertThat(part.readBytes()).isEqualTo(body)
    }

    @Test
    fun aChangedFileIsSentWholeAndThePartStartsOver() {
        val part = File(tmp.root, "7.part")
        val saved = Saved()
        download(part, saved, stopAfter = 50_000)
        // The server's file changed: another ETag, so If-Range answers 200 with the whole new file.
        body = ByteArray(300_000) { (it * 13 + 1).toByte() }
        etag = "\"sha-two\""
        assertThat(download(part, saved)).isEqualTo(DownloadOutcome.Done)
        assertThat(part.readBytes()).isEqualTo(body)
        assertThat(saved.etag).isEqualTo(etag)
    }

    @Test
    fun aServerThatIgnoresTheRangeStartsOver() {
        val part = File(tmp.root, "7.part")
        val saved = Saved()
        download(part, saved, stopAfter = 50_000)
        ignoreRange = true
        assertThat(download(part, saved)).isEqualTo(DownloadOutcome.Done)
        assertThat(part.readBytes()).isEqualTo(body)
    }

    @Test
    fun aCompletePartIsDoneAndA404IsNotFound() {
        val part = File(tmp.root, "7.part").apply { writeBytes(body) }
        assertThat(download(part, Saved(body.size.toLong(), etag))).isEqualTo(DownloadOutcome.Done)
        missing = true
        assertThat(download(File(tmp.root, "8.part"), Saved())).isEqualTo(DownloadOutcome.NotFound)
    }

    @Test
    fun aConnectionThatKeepsDroppingGivesUp() {
        cutAfter = 10
        cuts = 100
        val saved = Saved()
        val error = runCatching { download(File(tmp.root, "7.part"), saved) }.exceptionOrNull()
        assertThat(error).isInstanceOf(java.io.IOException::class.java)
        // What arrived is kept for the next run.
        assertThat(saved.offset).isGreaterThan(0L)
    }
}
