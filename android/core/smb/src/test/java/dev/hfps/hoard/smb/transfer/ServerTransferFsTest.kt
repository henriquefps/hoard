package dev.hfps.hoard.smb.transfer

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException

/**
 * "Save to this device" (ADR 0024 A2): server originals copied by the one transfer engine (ADR 0007) through the
 * read-only [ServerTransferFs], in [TransferRunnerTest]'s style: range reads, retries, a `404`, a server that goes away.
 */
class ServerTransferFsTest {
    /** A fake `GET /v1/originals/{fingerprint}` with failure injection. */
    private class FakeOriginals : ServerOriginals {
        val files = HashMap<String, ByteArray>()
        /** Every range asked (`fingerprint@offset`). */
        val ranges = ArrayList<String>()
        var heads = 0
        /** The connection drops once when this many bytes of a response were sent. */
        var dropAt: Long? = null
        /** The connection ends early (no error, just the end of the stream) once at this many bytes. */
        var truncateAt: Long? = null
        /** The server is gone: every request fails to connect. */
        var down = false
        /** A revoked key. */
        var unauthorized = false
        var bytesSent = 0L

        override fun size(fingerprint: String): Long? {
            heads++
            check()
            return files[fingerprint]?.size?.toLong()
        }

        private fun check() {
            if (down) throw ConnectException("Connection refused")
            if (unauthorized) throw HoardSmbException(ErrorCode.AUTH_FAILED, "unauthorized")
        }

        override fun open(fingerprint: String, offset: Long): InputStream {
            check()
            val bytes = files[fingerprint] ?: throw HoardSmbException(ErrorCode.NOT_FOUND, "404 not_found")
            ranges += "$fingerprint@$offset"
            var at = offset.toInt()
            return object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (down) throw IOException("Connection reset")
                    if (at >= bytes.size) return -1
                    dropAt?.let { if (bytesSent >= it) { dropAt = null; throw IOException("Connection reset") } }
                    truncateAt?.let { if (bytesSent >= it) { truncateAt = null; return -1 } }
                    val n = minOf(len, bytes.size - at, 5)
                    System.arraycopy(bytes, at, b, off, n)
                    at += n
                    bytesSent += n
                    return n
                }
            }
        }
    }

    private val server = Endpoint.Server("srv1")
    private val phone = Endpoint.Device
    private val originals = FakeOriginals()
    private val serverFs = ServerTransferFs("hoard-server", originals) { 1_700_000_000_000L }
    private val phoneFs = MemoryFs("device")
    private fun fsOf(endpoint: Endpoint): TransferFs = if (endpoint is Endpoint.Server) serverFs else phoneFs

    private val states = ArrayList<String>()
    private var stoppedAfterSleeps = Int.MAX_VALUE
    private var sleeps = 0
    private val hooks = object : RunnerHooks {
        private var clock = 0L
        override fun now(): Long { clock += 300; return clock }
        override fun emit(job: TransferJob, bytesPerSecond: Long?) { if (states.lastOrNull() != job.state) states += job.state }
        override fun persist(job: TransferJob) {}
        override fun sleep(ms: Long) { sleeps++ }
        override fun isStopped() = sleeps >= stoppedAfterSleeps
    }

    private fun bytes(n: Int, seed: Int = 1) = ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    private fun job(vararg paths: String, conflict: String = Conflicts.RENAME) =
        TransferJob("t", "copy", paths.map { TransferSource(server, it) }, phone, "Pictures/From Pixel 8", conflict, "2026-11-16T00:00:00.000Z")

    private fun run(job: TransferJob) = TransferRunner(job, ::fsOf, hooks, bufferSize = 8).run()

    @Test
    fun `a whole shot is copied under each file's own name, keeping the phone's time`() {
        originals.files["v1:raw"] = bytes(40, 1)
        originals.files["v1:jpg"] = bytes(30, 2)
        originals.files["v1:xmp"] = bytes(10, 3)
        val job = job("v1:raw/PXL_1.dng", "v1:jpg/PXL_1.jpg", "v1:xmp/PXL_1.xmp")
        run(job)
        assertEquals(TransferStates.COMPLETED, job.state)
        assertArrayEquals(bytes(40, 1), phoneFs.bytes("Pictures/From Pixel 8/PXL_1.dng"))
        assertArrayEquals(bytes(30, 2), phoneFs.bytes("Pictures/From Pixel 8/PXL_1.jpg"))
        assertArrayEquals(bytes(10, 3), phoneFs.bytes("Pictures/From Pixel 8/PXL_1.xmp"))
        assertEquals(1_700_000_000_000L, phoneFs.nodes["Pictures/From Pixel 8/PXL_1.jpg"]!!.modified)
        assertEquals(80L, job.totalBytes())
        // One range request per file, from its start.
        assertEquals(listOf("v1:raw@0", "v1:jpg@0", "v1:xmp@0"), originals.ranges)
    }

    @Test
    fun `a dropped connection is retried and resumed by range at the byte written`() {
        originals.files["v1:mov"] = bytes(50)
        originals.dropAt = 20
        val job = job("v1:mov/VID_1.mp4")
        run(job)
        assertEquals(TransferStates.COMPLETED, job.state)
        assertTrue(TransferStates.RETRYING in states)
        assertArrayEquals(bytes(50), phoneFs.bytes("Pictures/From Pixel 8/VID_1.mp4"))
        assertEquals(listOf("v1:mov@0", "v1:mov@20"), originals.ranges)
        // Nothing was sent twice.
        assertEquals(50L, originals.bytesSent)
    }

    @Test
    fun `a response that ends early is a dropped connection, never a short file`() {
        originals.files["v1:a"] = bytes(33)
        originals.truncateAt = 15
        val job = job("v1:a/A.jpg")
        run(job)
        assertEquals(TransferStates.COMPLETED, job.state)
        assertArrayEquals(bytes(33), phoneFs.bytes("Pictures/From Pixel 8/A.jpg"))
        assertEquals(listOf("v1:a@0", "v1:a@15"), originals.ranges)
    }

    @Test
    fun `an original the server no longer has fails alone, the others are copied`() {
        originals.files["v1:b"] = bytes(12)
        val job = job("v1:gone/GONE.jpg", "v1:b/B.jpg")
        run(job)
        assertEquals(TransferStates.FAILED, job.state)
        val gone = job.items.first { it.sourcePath == "v1:gone/GONE.jpg" }
        assertEquals(TransferStates.FAILED, gone.state)
        assertEquals(ErrorCode.NOT_FOUND, gone.errorCode)
        assertEquals(TransferStates.COMPLETED, job.items.first { it.sourcePath == "v1:b/B.jpg" }.state)
        assertNull(phoneFs.bytes("Pictures/From Pixel 8/GONE.jpg"))
    }

    @Test
    fun `a 404 during the copy fails that file without retrying`() {
        originals.files["v1:c"] = bytes(20)
        val job = job("v1:c/C.jpg")
        // Scanned while it was there; deleted before its bytes are read.
        TransferRunner(job, { e -> if (e is Endpoint.Server) object : TransferFs by serverFs {
            override fun openRead(path: String, offset: Long): ReadStream { originals.files.remove("v1:c"); return serverFs.openRead(path, offset) }
        } else phoneFs }, hooks, bufferSize = 8).run()
        assertEquals(TransferStates.FAILED, job.state)
        assertEquals(ErrorCode.NOT_FOUND, job.items.single().errorCode)
        assertFalse(TransferStates.RETRYING in states)
        assertNull(phoneFs.bytes("Pictures/From Pixel 8/C.jpg"))
    }

    @Test
    fun `a server that disappears mid-copy stops the job after its retries, and Retry resumes the partial file`() {
        originals.files["v1:big"] = bytes(60)
        val job = job("v1:big/BIG.dng")
        // The server goes away after 25 bytes and stays away through every retry.
        originals.dropAt = 25
        val flaky = object : TransferFs by serverFs {
            override fun openRead(path: String, offset: Long): ReadStream {
                if (offset > 0) originals.down = true
                return serverFs.openRead(path, offset)
            }
        }
        TransferRunner(job, { e -> if (e is Endpoint.Server) flaky else phoneFs }, hooks, bufferSize = 8).run()
        assertEquals(TransferStates.FAILED, job.state)
        assertTrue(TransferStates.RETRYING in states)
        assertEquals(ErrorCode.UNREACHABLE, job.errorCode)
        val partial = phoneFs.bytes("Pictures/From Pixel 8/" + PartNames.of("BIG.dng"))!!
        assertEquals(25, partial.size)
        assertNull(phoneFs.bytes("Pictures/From Pixel 8/BIG.dng"))

        // Back online: Retry continues from byte 25.
        originals.down = false
        originals.ranges.clear()
        job.items.forEach { if (it.state == TransferStates.FAILED) it.state = TransferStates.QUEUED }
        job.state = TransferStates.QUEUED
        job.errorCode = null
        job.spaceChecked = false
        job.finishedAt = null
        run(job)
        assertEquals(TransferStates.COMPLETED, job.state)
        assertEquals(listOf("v1:big@25"), originals.ranges)
        assertArrayEquals(bytes(60), phoneFs.bytes("Pictures/From Pixel 8/BIG.dng"))
    }

    @Test
    fun `a revoked key stops the job at once`() {
        originals.files["v1:d"] = bytes(10)
        originals.unauthorized = true
        val job = job("v1:d/D.jpg")
        run(job)
        assertEquals(TransferStates.FAILED, job.state)
        assertEquals(ErrorCode.AUTH_FAILED, job.errorCode)
        assertFalse(TransferStates.RETRYING in states)
    }

    @Test
    fun `existing names follow the conflict policy`() {
        originals.files["v1:e"] = bytes(10, 7)
        phoneFs.file("Pictures/From Pixel 8/E.jpg", bytes(4))
        val renamed = job("v1:e/E.jpg")
        run(renamed)
        assertArrayEquals(bytes(10, 7), phoneFs.bytes("Pictures/From Pixel 8/E (1).jpg"))
        assertArrayEquals(bytes(4), phoneFs.bytes("Pictures/From Pixel 8/E.jpg"))

        val skipped = job("v1:e/E.jpg", conflict = Conflicts.SKIP)
        run(skipped)
        assertEquals(TransferStates.SKIPPED, skipped.items.single().state)
    }

    @Test
    fun `the server endpoint is read-only and survives a job's JSON round trip`() {
        val e = runCatching { serverFs.openWrite("v1:x/X.jpg", 0) }.exceptionOrNull() as HoardSmbException
        assertEquals(ErrorCode.ACCESS_DENIED, e.code)
        assertFalse(serverFs.canWrite("v1:x/X.jpg"))
        val back = TransferJob.fromJson(job("v1:a/A.jpg").toJson())
        assertEquals(server, back.sources.single().endpoint)
        assertEquals("server:srv1", back.sources.single().endpoint.key)
        assertTrue(back.sources.single().endpoint.isNetwork)
    }

    @Test
    fun `stat names the file, its size and time, an unknown original is missing`() {
        originals.files["v1:f"] = bytes(9)
        val stat = serverFs.stat("v1:f/F.HEIC")!!
        assertEquals("F.HEIC", stat.name)
        assertEquals(9L, stat.size)
        assertFalse(stat.isDirectory)
        assertNull(serverFs.stat("v1:none/N.jpg"))
        assertTrue(serverFs.stat("")!!.isDirectory)
        // One HEAD per original per job.
        serverFs.stat("v1:f/F.HEIC")
        assertEquals(2, originals.heads)
        ByteArrayInputStream(ByteArray(0)).close()
    }
}
