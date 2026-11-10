package dev.hfps.hoard.smb.transfer

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** An in-memory [TransferFs] with failure injection. */
class MemoryFs(
    override val label: String,
    override val isNetwork: Boolean = false,
    override val addressCount: Int = 1,
) : TransferFs {
    class Node(val isDirectory: Boolean, var bytes: ByteArray = ByteArray(0), var modified: Long? = 1_000L)

    val nodes = LinkedHashMap<String, Node>().apply { put("", Node(true)) }
    var free: Long? = null
    var canRename = true
    /** Throw once from a read when this many bytes of the stream were read. */
    var dropReadAt: Long? = null
    /** Every write fails with this (an unplugged drive). */
    var writeFailure: HoardSmbException? = null
    var bytesRead = 0L
    var reconnects = 0
    var opens = 0

    fun file(path: String, bytes: ByteArray, modified: Long = 1_000L) {
        dirs(TransferPaths.parent(path))
        nodes[path] = Node(false, bytes, modified)
    }

    fun dirs(path: String) {
        if (path.isEmpty()) return
        dirs(TransferPaths.parent(path))
        nodes.getOrPut(path) { Node(true) }
    }

    fun bytes(path: String) = nodes[path]?.bytes

    override fun stat(path: String) = nodes[path]?.let { FileStat(if (path.isEmpty()) label else TransferPaths.name(path), it.isDirectory, it.bytes.size.toLong().takeIf { _ -> !it.isDirectory } ?: 0, it.modified) }

    override fun list(path: String): List<FileStat> =
        nodes.keys.filter { it.isNotEmpty() && TransferPaths.parent(it) == path }.map { stat(it)!! }

    override fun mkdir(path: String) {
        writeFailure?.let { throw it }
        if (nodes[TransferPaths.parent(path)]?.isDirectory != true) throw HoardSmbException(ErrorCode.NOT_FOUND, "no parent")
        nodes.getOrPut(path) { Node(true) }
    }

    override fun openRead(path: String, offset: Long): ReadStream {
        opens++
        val node = nodes[path] ?: throw HoardSmbException(ErrorCode.NOT_FOUND, path)
        var at = offset.toInt()
        return object : ReadStream {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (at >= node.bytes.size) return -1
                val drop = dropReadAt
                if (drop != null && bytesRead >= drop) {
                    dropReadAt = null
                    throw IOException("Connection reset")
                }
                val n = minOf(length, node.bytes.size - at, 3)
                System.arraycopy(node.bytes, at, buffer, offset, n)
                at += n
                bytesRead += n
                return n
            }

            override fun close() {}
        }
    }

    override fun openWrite(path: String, offset: Long): WriteStream {
        writeFailure?.let { throw it }
        val node = nodes.getOrPut(path) { Node(false) }
        node.bytes = node.bytes.copyOf(offset.toInt())
        return object : WriteStream {
            override var position = offset
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writeFailure?.let { throw it }
                node.bytes += buffer.copyOfRange(offset, offset + length)
                position += length
            }
            override fun finish() {}
            override fun close() {}
        }
    }

    override fun delete(path: String) {
        nodes.remove(path)
    }

    override fun rename(from: String, to: String, replace: Boolean): Boolean {
        if (!canRename && TransferPaths.parent(from) != TransferPaths.parent(to)) return false
        if (nodes.containsKey(to) && !replace) throw HoardSmbException(ErrorCode.ALREADY_EXISTS, to)
        val moved = nodes.keys.filter { it == from || it.startsWith("$from/") }
        moved.forEach { key -> nodes[TransferPaths.rebase(key, from, to)] = nodes.remove(key)!! }
        return true
    }

    override fun setModified(path: String, millis: Long): Boolean {
        nodes[path]?.modified = millis
        return true
    }

    override fun freeBytes(path: String) = free

    override fun reconnect(addressIndex: Int) {
        reconnects++
    }

    override fun mapError(error: Throwable) = when (error) {
        is HoardSmbException -> error
        is IOException -> HoardSmbException(ErrorCode.UNREACHABLE, error.message ?: "io", error)
        else -> HoardSmbException(ErrorCode.UNKNOWN, error.message ?: "?", error)
    }
}

class TransferRunnerTest {
    private val nas = Endpoint.Smb("m1", listOf(TransferAddress("192.168.1.10", 445), TransferAddress("nas.ts.net", 445)), "guid", "media")
    private val phone = Endpoint.Device
    private val usb = Endpoint.Volume("1A2B-3C4D")

    private val fs = HashMap<String, MemoryFs>()
    private fun fsOf(endpoint: Endpoint) = fs.getOrPut(endpoint.key) { MemoryFs(endpoint.key, isNetwork = endpoint.isNetwork, addressCount = if (endpoint is Endpoint.Smb) endpoint.addresses.size else 1) }

    private val states = ArrayList<String>()
    private var clock = 0L
    private val hooks = object : RunnerHooks {
        override fun now(): Long { clock += 300; return clock }
        override fun emit(job: TransferJob, bytesPerSecond: Long?) { if (states.lastOrNull() != job.state) states += job.state }
        override fun persist(job: TransferJob) {}
        override fun sleep(ms: Long) {}
        override fun isStopped() = false
    }

    private fun job(op: String, sources: List<TransferSource>, dest: Endpoint, destPath: String = "", conflict: String = Conflicts.RENAME) =
        TransferJob("t", op, sources, dest, destPath, conflict, "2026-11-09T00:00:00.000Z")

    private fun run(job: TransferJob) = TransferRunner(job, ::fsOf, hooks, bufferSize = 8).run()

    private fun bytes(n: Int, seed: Int = 1) = ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    @Test
    fun `copies a folder tree with its totals, contents and modified times`() {
        val camera = fsOf(usb)
        camera.file("DCIM/100MSDCF/DSC0001.ARW", bytes(40), modified = 111)
        camera.file("DCIM/100MSDCF/DSC0001.JPG", bytes(20), modified = 222)
        camera.file("DCIM/100MSDCF/sub/DSC0002.JPG", bytes(5))
        camera.dirs("DCIM/empty")
        fsOf(nas).dirs("Photos")
        val job = job("copy", listOf(TransferSource(usb, "DCIM/100MSDCF")), nas, "Photos")
        run(job)

        assertEquals(TransferStates.COMPLETED, job.state)
        assertEquals(listOf(TransferStates.SCANNING, TransferStates.RUNNING, TransferStates.COMPLETED), states)
        val counts = job.counts()
        assertEquals(3, counts.total)
        assertEquals(3, counts.done)
        assertEquals(65L, job.totalBytes())
        assertEquals(65L, job.bytesDone())
        val nasFs = fsOf(nas)
        assertArrayEquals(bytes(40), nasFs.bytes("Photos/100MSDCF/DSC0001.ARW"))
        assertArrayEquals(bytes(5), nasFs.bytes("Photos/100MSDCF/sub/DSC0002.JPG"))
        assertEquals(111L, nasFs.nodes["Photos/100MSDCF/DSC0001.ARW"]!!.modified)
        assertTrue(nasFs.nodes.keys.none { PartNames.isPart(TransferPaths.name(it)) })
        // Copy keeps the sources.
        assertNotNull(camera.nodes["DCIM/100MSDCF/DSC0001.ARW"])
    }

    @Test
    fun `existing names are renamed, also between two sources of the same name`() {
        fsOf(phone).file("Download/a.jpg", bytes(3, 1))
        fsOf(phone).file("Pictures/a.jpg", bytes(4, 2))
        fsOf(usb).file("a.jpg", bytes(9))
        val job = job("copy", listOf(TransferSource(phone, "Download/a.jpg"), TransferSource(phone, "Pictures/a.jpg")), usb)
        run(job)
        assertEquals(TransferStates.COMPLETED, job.state)
        val drive = fsOf(usb)
        assertArrayEquals(bytes(9), drive.bytes("a.jpg"))
        assertArrayEquals(bytes(3, 1), drive.bytes("a (1).jpg"))
        assertArrayEquals(bytes(4, 2), drive.bytes("a (2).jpg"))
        assertEquals(listOf("a (1).jpg", "a (2).jpg"), job.items.map { it.destPath })
    }

    @Test
    fun `skip and overwrite`() {
        fsOf(phone).file("x.txt", bytes(3))
        fsOf(usb).file("x.txt", bytes(9))
        val skip = job("copy", listOf(TransferSource(phone, "x.txt")), usb, conflict = Conflicts.SKIP)
        run(skip)
        assertEquals(TransferStates.SKIPPED, skip.state)
        assertArrayEquals(bytes(9), fsOf(usb).bytes("x.txt"))

        val overwrite = job("copy", listOf(TransferSource(phone, "x.txt")), usb, conflict = Conflicts.OVERWRITE)
        run(overwrite)
        assertEquals(TransferStates.COMPLETED, overwrite.state)
        assertArrayEquals(bytes(3), fsOf(usb).bytes("x.txt"))
    }

    @Test
    fun `ask stops in conflict until answered, then continues`() {
        fsOf(phone).file("A/1.jpg", bytes(3))
        fsOf(phone).file("A/2.jpg", bytes(4))
        fsOf(usb).file("A/1.jpg", bytes(9))
        val job = job("copy", listOf(TransferSource(phone, "A")), usb, conflict = Conflicts.ASK)
        run(job)
        assertEquals(TransferStates.CONFLICT, job.state)
        assertEquals(1, job.counts().conflicts)
        assertNull(fsOf(usb).bytes("A/2.jpg")) // nothing written before the answer

        job.items.filter { it.conflict == Conflicts.ASK }.forEach { it.conflict = Conflicts.SKIP }
        job.state = TransferStates.QUEUED
        run(job)
        assertEquals(TransferStates.COMPLETED, job.state)
        assertEquals(1, job.counts().skipped)
        assertArrayEquals(bytes(9), fsOf(usb).bytes("A/1.jpg"))
        assertArrayEquals(bytes(4), fsOf(usb).bytes("A/2.jpg"))
    }

    @Test
    fun `a move inside one location is one rename, never a copy`() {
        val phoneFs = fsOf(phone)
        phoneFs.file("DCIM/Camera/big.mp4", bytes(100))
        phoneFs.file("DCIM/Camera/b.jpg", bytes(10))
        phoneFs.dirs("Archive")
        val job = job("move", listOf(TransferSource(phone, "DCIM/Camera")), phone, "Archive")
        run(job)
        assertEquals(TransferStates.COMPLETED, job.state)
        assertEquals(0, phoneFs.opens)
        assertEquals(1, job.counts().total)
        assertArrayEquals(bytes(100), phoneFs.bytes("Archive/Camera/big.mp4"))
        assertNull(phoneFs.nodes["DCIM/Camera"])
    }

    @Test
    fun `a move falls back to copy and delete when the location cannot rename`() {
        val phoneFs = fsOf(phone)
        phoneFs.canRename = false
        phoneFs.file("a/x.bin", bytes(10))
        phoneFs.dirs("b")
        val job = job("move", listOf(TransferSource(phone, "a")), phone, "b")
        run(job)
        assertEquals(TransferStates.COMPLETED, job.state)
        assertArrayEquals(bytes(10), phoneFs.bytes("b/a/x.bin"))
        assertNull(phoneFs.nodes["a/x.bin"])
        assertNull(phoneFs.nodes["a"])
        assertEquals(1, job.counts().total)
    }

    @Test
    fun `a move between locations deletes each source after it is verified, and emptied folders`() {
        val drive = fsOf(usb)
        drive.file("DCIM/1.jpg", bytes(5))
        drive.file("DCIM/sub/2.jpg", bytes(6))
        drive.file("DCIM/keep/3.jpg", bytes(7))
        fsOf(nas).file("DCIM/keep/3.jpg", bytes(1))
        val job = job("move", listOf(TransferSource(usb, "DCIM")), nas, conflict = Conflicts.SKIP)
        run(job)
        assertEquals(TransferStates.COMPLETED, job.state)
        assertNull(drive.nodes["DCIM/1.jpg"])
        assertNull(drive.nodes["DCIM/sub"])
        // The skipped file was not moved: it and its folders stay.
        assertNotNull(drive.nodes["DCIM/keep/3.jpg"])
        assertNotNull(drive.nodes["DCIM"])
        assertArrayEquals(bytes(6), fsOf(nas).bytes("DCIM/sub/2.jpg"))
    }

    @Test
    fun `a folder cannot go into itself`() {
        fsOf(phone).file("A/x", bytes(1))
        fsOf(phone).dirs("A/B")
        val job = job("copy", listOf(TransferSource(phone, "A")), phone, "A/B")
        run(job)
        assertEquals(TransferStates.FAILED, job.state)
        assertEquals(ErrorCode.UNKNOWN, job.errorCode)
    }

    @Test
    fun `a dropped connection reconnects, fails over and resumes at the byte written`() {
        val nasFs = fsOf(nas)
        nasFs.file("movie.mkv", bytes(50))
        nasFs.dropReadAt = 20
        val job = job("copy", listOf(TransferSource(nas, "movie.mkv")), phone)
        run(job)
        assertEquals(TransferStates.COMPLETED, job.state)
        assertTrue(TransferStates.RETRYING in states)
        assertEquals(1, nasFs.reconnects)
        assertArrayEquals(bytes(50), fsOf(phone).bytes("movie.mkv"))
        // 20 bytes before the drop, 30 after: nothing read twice.
        assertEquals(50L, nasFs.bytesRead)
    }

    @Test
    fun `a partial file from an earlier run is continued, not restarted`() {
        fsOf(phone).file("big.bin", bytes(40))
        fsOf(usb).file(PartNames.of("big.bin"), bytes(40).copyOf(24))
        val job = job("copy", listOf(TransferSource(phone, "big.bin")), usb)
        job.scanned = true
        job.items += TransferItem(0, 0, "big.bin", "big.bin", ItemKinds.FILE, 40, 1_000L, bytesDone = 16)
        run(job)
        assertEquals(TransferStates.COMPLETED, job.state)
        assertEquals(16L, fsOf(phone).bytesRead)
        assertArrayEquals(bytes(40), fsOf(usb).bytes("big.bin"))
    }

    @Test
    fun `not enough space stops before any byte is written`() {
        fsOf(phone).file("a.bin", bytes(40))
        fsOf(usb).free = 10
        val job = job("copy", listOf(TransferSource(phone, "a.bin")), usb)
        run(job)
        assertEquals(TransferStates.FAILED, job.state)
        assertEquals(ErrorCode.NO_SPACE, job.errorCode)
        assertEquals(1, job.counts().failed)
        assertNull(fsOf(usb).bytes(PartNames.of("a.bin")))
    }

    @Test
    fun `an unplugged destination stops the job and Retry runs the rest`() {
        fsOf(phone).file("a/1", bytes(5))
        fsOf(phone).file("a/2", bytes(5))
        val drive = fsOf(usb)
        val job = job("copy", listOf(TransferSource(phone, "a")), usb)
        drive.writeFailure = HoardSmbException(ErrorCode.UNAVAILABLE, "unplugged")
        run(job)
        assertEquals(TransferStates.FAILED, job.state)
        assertEquals(ErrorCode.UNAVAILABLE, job.errorCode)
        assertEquals(2, job.counts().failed)

        drive.writeFailure = null
        job.items.forEach { if (it.state == TransferStates.FAILED) it.state = TransferStates.QUEUED }
        job.state = TransferStates.QUEUED
        job.errorCode = null
        job.spaceChecked = false
        job.finishedAt = null
        run(job)
        assertEquals(TransferStates.COMPLETED, job.state)
        assertArrayEquals(bytes(5), drive.bytes("a/2"))
    }

    @Test
    fun `a destination that cannot be opened fails the job instead of leaving it running`() {
        fsOf(phone).file("a.bin", bytes(5))
        val job = job("copy", listOf(TransferSource(phone, "a.bin")), usb)
        job.scanned = true
        job.items += TransferItem(0, 0, "a.bin", "a.bin", ItemKinds.FILE, 5, 1_000L, state = TransferStates.RUNNING, bytesDone = 2)
        val unplugged: (Endpoint) -> TransferFs = { e ->
            if (e == usb) throw HoardSmbException(ErrorCode.UNAVAILABLE, "The volume is not connected") else fsOf(e)
        }
        TransferRunner(job, unplugged, hooks).run()
        assertEquals(TransferStates.FAILED, job.state)
        assertEquals(ErrorCode.UNAVAILABLE, job.errorCode)
        assertEquals(ErrorCode.UNAVAILABLE, job.items.single().errorCode)
    }

    @Test
    fun `a file that fails does not stop the others`() {
        fsOf(phone).file("a/1", bytes(5))
        fsOf(phone).file("a/2", bytes(5))
        val job = job("copy", listOf(TransferSource(phone, "a"), TransferSource(phone, "gone.txt")), usb)
        run(job)
        assertEquals(TransferStates.FAILED, job.state)
        assertEquals(ErrorCode.NOT_FOUND, job.errorCode)
        assertEquals(2, job.counts().done)
        assertEquals(1, job.counts().failed)
    }

    @Test
    fun `a lost job run again treats same-size files as copied`() {
        fsOf(phone).file("a.bin", bytes(5))
        fsOf(usb).file("a.bin", bytes(5))
        val job = TransferJob("t", "copy", listOf(TransferSource(phone, "a.bin")), usb, "", Conflicts.RENAME, "now", resumeExisting = true)
        run(job)
        assertEquals(TransferStates.COMPLETED, job.state)
        assertNull(fsOf(usb).bytes("a (1).bin"))
        assertEquals(0, fsOf(phone).opens)
    }

    @Test
    fun `XMP sidecars are written at the destination only, follow renames, skip skipped files`() {
        fsOf(phone).file("DCIM/DSC1.ARW", bytes(20, 1))
        fsOf(phone).file("DCIM/DSC1.JPG", bytes(10, 2))
        fsOf(phone).file("DCIM/DSC2.JPG", bytes(10, 3))
        fsOf(phone).file("DCIM/DSC3.JPG", bytes(10, 4))
        fsOf(usb).file("Pictures/DSC2.JPG", bytes(5, 9))
        fsOf(usb).file("Pictures/DSC3.JPG", bytes(5, 9))
        val sidecars = mapOf("DCIM/DSC1.ARW" to "<x1/>", "DCIM/DSC2.JPG" to "<x2/>", "DCIM/DSC3.JPG" to "<x3/>")
        val first = TransferJob(
            "t", "copy", listOf("DSC1.ARW", "DSC1.JPG", "DSC2.JPG").map { TransferSource(phone, "DCIM/$it") }, usb, "Pictures", Conflicts.RENAME, "now",
            sidecars = sidecars,
        )
        run(first)
        val dest = fsOf(usb)
        assertEquals("<x1/>", dest.bytes("Pictures/DSC1.xmp")?.toString(Charsets.UTF_8))
        // DSC2.JPG existed: the copy became "DSC2 (1).JPG" and its sidecar follows that name.
        assertNotNull(dest.bytes("Pictures/DSC2 (1).JPG"))
        assertEquals("<x2/>", dest.bytes("Pictures/DSC2 (1).xmp")?.toString(Charsets.UTF_8))
        assertNull(dest.bytes("Pictures/DSC2.xmp"))
        // Never next to the source; no partial names left behind.
        assertTrue(fsOf(phone).nodes.keys.none { it.endsWith(".xmp") })
        assertTrue(dest.nodes.keys.none { it.contains(".part") || it.contains("hoardpart") })
        // A skipped file gets no sidecar.
        val skip = TransferJob("t2", "copy", listOf(TransferSource(phone, "DCIM/DSC3.JPG")), usb, "Pictures", Conflicts.SKIP, "now", sidecars = sidecars)
        run(skip)
        assertNull(dest.bytes("Pictures/DSC3.xmp"))
        // Sidecars survive the job's JSON round trip (a restart mid-copy).
        assertEquals(sidecars, TransferJob.fromJson(first.toJson()).sidecars)
    }

    @Test
    fun `jobs survive a JSON round trip, and 1_3 downloads migrate`() {
        val job = job("move", listOf(TransferSource(nas, "a/b.jpg"), TransferSource(Endpoint.Incoming("s1"), "0")), Endpoint.Tree("content://x/tree/y"), "Photos", Conflicts.ASK)
        job.items += TransferItem(0, 0, "a/b.jpg", "Photos/b (1).jpg", ItemKinds.FILE, 10, 5, TransferStates.FAILED, 4, Conflicts.OVERWRITE, ErrorCode.TIMEOUT, "slow")
        job.state = TransferStates.FAILED
        job.scanned = true
        val back = TransferJob.fromJson(org.json.JSONObject(job.toJson().toString()))
        assertEquals(job.toJson().toString(), back.toJson().toString())
        assertEquals(nas, back.sources[0].endpoint)

        val legacy = org.json.JSONObject(
            """{"transferId":"old","machineId":"m1","addresses":[{"host":"10.0.0.2","port":445}],"expectedServerGuid":"g",
               "share":"media","remotePath":"Movies/a.mkv","destTreeUri":"content://t","destName":"a (1).mkv","totalBytes":100,
               "bytesDone":40,"state":"retrying","startedAt":"2026-11-08T21:14:03.120Z","started":true}""",
        )
        val migrated = TransferJob.fromJson(legacy)
        assertEquals(Endpoint.Tree("content://t"), migrated.destination)
        assertTrue(migrated.scanned)
        val item = migrated.items.single()
        assertEquals("a (1).mkv", item.destPath)
        assertTrue(item.direct)
        assertEquals(40L, item.bytesDone)
        assertFalse(migrated.isTerminal)
    }
}
