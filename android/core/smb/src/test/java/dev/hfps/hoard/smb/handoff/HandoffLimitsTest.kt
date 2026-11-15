package dev.hfps.hoard.smb.handoff

import dev.hfps.hoard.smb.stream.StreamReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HandoffLimitsTest {
    @get:Rule val temp = TemporaryFolder()

    // --- Busy contract ---------------------------------------------------------------------------------------------

    @Test
    fun `a busy failure is a FileNotFoundException starting with busy, other failures are not busy`() {
        val busy = HandoffBusy.exception("8 hand-off reads in progress")
        assertTrue(busy is FileNotFoundException)
        assertEquals("busy: 8 hand-off reads in progress, retry", busy.message)
        assertTrue(HandoffBusy.isBusy(busy))
        assertFalse(HandoffBusy.isBusy(FileNotFoundException("This photo is no longer shared with Reveal")))
        assertFalse(HandoffBusy.isBusy(FileNotFoundException("DSC1.ARW: the machine is not reachable (UNREACHABLE)")))
        assertFalse(HandoffBusy.isBusy(IOException("busy: not a FileNotFoundException")))
        assertFalse(HandoffBusy.isBusy(null))
    }

    // --- Limiter --------------------------------------------------------------------------------------------------

    @Test
    fun `the busy contract's constants - a limit of 4 to 8 reads, a wait under 2 s`() {
        assertTrue(HandoffServer.MAX_READS in 4..8)
        assertTrue(HandoffServer.BUSY_WAIT_MS in 1 until HandoffBusy.MAX_WAIT_MS)
        assertThrows(IllegalArgumentException::class.java) { HandoffLimiter(slots = 1, waitMs = HandoffBusy.MAX_WAIT_MS, what = "things") }
    }

    @Test
    fun `slots are handed out up to the limit and reused after release`() {
        val limiter = HandoffLimiter(slots = 2, waitMs = 0, what = "things")
        val a = limiter.acquire()
        val b = limiter.acquire()
        assertEquals(setOf(0, 1), setOf(a.index, b.index))
        assertEquals(2, limiter.active)
        val refused = assertThrows(FileNotFoundException::class.java) { limiter.acquire() }
        assertEquals("busy: 2 things, retry", refused.message)
        a.close()
        a.close() // idempotent: the slot is not given back twice
        assertEquals(1, limiter.active)
        val c = limiter.acquire()
        assertEquals(a.index, c.index)
        assertThrows(FileNotFoundException::class.java) { limiter.acquire() }
    }

    @Test
    fun `a waiter gets the slot released within the wait`() {
        val limiter = HandoffLimiter(slots = 1, waitMs = 1_500, what = "things")
        val held = limiter.acquire()
        val pool = Executors.newSingleThreadExecutor()
        val waiting = pool.submit<Int> { limiter.acquire().use { it.index } }
        while (limiter.queued == 0) Thread.sleep(5)
        held.close()
        assertEquals(0, waiting.get(5, TimeUnit.SECONDS))
        pool.shutdown()
    }

    @Test
    fun `busy only after the bounded wait, never before, however many wait`() {
        val limiter = HandoffLimiter(slots = 1, waitMs = 300, what = "things")
        limiter.acquire()
        val pool = Executors.newFixedThreadPool(20)
        val results = (1..20).map {
            pool.submit<Pair<Throwable?, Long>> {
                val t0 = System.nanoTime()
                val e = runCatching { limiter.acquire() }.exceptionOrNull()
                e to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
            }
        }.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        results.forEach { (e, ms) ->
            assertTrue(HandoffBusy.isBusy(e))
            assertTrue("refused after $ms ms", ms in 280..HandoffBusy.MAX_WAIT_MS)
        }
        assertEquals(0, limiter.queued)
    }

    @Test
    fun `a burst of 200 never exceeds the limit, and every caller is served or refused within the wait`() {
        val limiter = HandoffLimiter(slots = 4, waitMs = 200, what = "things")
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val served = AtomicInteger()
        val refused = AtomicInteger()
        val slowest = AtomicInteger()
        val indexes = Collections.synchronizedSet(HashSet<Int>())
        val pool = Executors.newFixedThreadPool(64)
        val go = CountDownLatch(1)
        val done = CountDownLatch(200)
        repeat(200) {
            pool.execute {
                go.await()
                val t0 = System.nanoTime()
                try {
                    limiter.acquire().use { slot ->
                        indexes += slot.index
                        val now = running.incrementAndGet()
                        peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                        Thread.sleep(20)
                        running.decrementAndGet()
                        served.incrementAndGet()
                    }
                } catch (e: FileNotFoundException) {
                    assertTrue(HandoffBusy.isBusy(e))
                    refused.incrementAndGet()
                    val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0).toInt()
                    slowest.accumulateAndGet(ms) { a, b -> maxOf(a, b) }
                } finally {
                    done.countDown()
                }
            }
        }
        go.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue("peak ${peak.get()}", peak.get() <= 4)
        assertEquals(200, served.get() + refused.get())
        assertTrue(served.get() >= 4)
        assertTrue(refused.get() > 0)
        assertTrue("a refusal took ${slowest.get()} ms", slowest.get() < HandoffBusy.MAX_WAIT_MS)
        assertTrue(indexes.all { it in 0 until 4 })
        assertEquals(0, limiter.active)
        assertEquals(0, limiter.queued)
    }

    // --- Backoff --------------------------------------------------------------------------------------------------

    @Test
    fun `an unreachable machine fails fast for a while, a success forgets it`() {
        var now = 0L
        val backoff = ConnectBackoff(holdMs = 10_000, clock = { now })
        assertNull(backoff.blocked("m|photos"))
        backoff.failed("m|photos", "the machine is not reachable (UNREACHABLE)")
        now += 9_999
        assertEquals("the machine is not reachable (UNREACHABLE)", backoff.blocked("m|photos"))
        assertNull(backoff.blocked("m|other"))
        now += 1
        assertNull(backoff.blocked("m|photos"))
        backoff.failed("m|photos", "x")
        backoff.succeeded("m|photos")
        assertNull(backoff.blocked("m|photos"))
    }

    // --- Burst summary --------------------------------------------------------------------------------------------

    @Test
    fun `one summary line per burst, after it went quiet`() {
        var now = 0L
        val burst = HandoffBurst(quietMs = 2_000, clock = { now })
        assertNull(burst.flush())
        repeat(4) { burst.begin() }
        now += 100
        burst.end(OpenOutcome.CACHED)
        burst.end(OpenOutcome.PROXIED)
        burst.end(OpenOutcome.REFUSED)
        now += 5_000
        assertNull(burst.flush()) // one open still running
        now += 1_000
        burst.begin()
        burst.end(OpenOutcome.FAILED)
        burst.end(OpenOutcome.CACHED)
        now += 1_999
        assertNull(burst.flush())
        now += 1
        assertEquals("open x5 in 6.1 s, 4 concurrent, 1 refused (2 cached, 1 proxied, 1 failed)", burst.flush())
        assertNull(burst.flush())
        assertEquals(0, burst.pending())
        burst.begin()
        burst.end(OpenOutcome.WRITE)
        now += 2_000
        assertEquals("open x1 in 0.0 s, 1 concurrent, 0 refused (0 cached, 0 proxied, 1 written, 0 failed)", burst.flush())
    }

    // --- Small-file cache -----------------------------------------------------------------------------------------

    private class BytesReader(private val bytes: ByteArray, private val delayMs: Long = 0, private val failAt: Long = -1) : StreamReader {
        val reads = AtomicInteger()
        var closed = false
        override fun read(offset: Long, length: Int, out: ByteArray, outOffset: Int): Int {
            reads.incrementAndGet()
            if (delayMs > 0) Thread.sleep(delayMs)
            if (failAt in 0 until offset + length && offset <= failAt) throw IOException("dropped")
            if (offset >= bytes.size) return 0
            val n = minOf(length, bytes.size - offset.toInt())
            System.arraycopy(bytes, offset.toInt(), out, outOffset, n)
            return n
        }
        override fun close() {
            closed = true
        }
    }

    private fun bytes(n: Int) = ByteArray(n) { (it * 7 + it / 251).toByte() }

    @Test
    fun `a small file is copied once, served from the copy, fetched again when it changes`() {
        val cache = HandoffCache(temp.root.resolve("handoff"), maxFileBytes = 1_000_000, maxTotalBytes = 10_000_000)
        val data = bytes(600_000)
        assertTrue(cache.fits(600_000))
        assertFalse(cache.fits(1_000_001))
        assertNull(cache.cached("id1", 600_000, 5))
        val reader = BytesReader(data)
        val copy = cache.fetch("id1", 600_000, 5) { reader }!!
        assertArrayEquals(data, copy.readBytes())
        assertTrue(reader.closed)
        assertEquals(copy, cache.cached("id1", 600_000, 5))
        val again = BytesReader(data)
        assertEquals(copy, cache.fetch("id1", 600_000, 5) { again })
        assertEquals(0, again.reads.get()) // no network for a cached copy
        // The file changed (another mtime): fetched again, the stale copy deleted.
        val changed = bytes(1_000)
        val fresh = cache.fetch("id1", 1_000, 6) { BytesReader(changed) }!!
        assertArrayEquals(changed, fresh.readBytes())
        assertNull(cache.cached("id1", 600_000, 5))
        assertEquals(listOf(fresh.name), temp.root.resolve("handoff").list()!!.toList())
    }

    @Test
    fun `a failed or slow copy leaves nothing behind`() {
        val dir = temp.root.resolve("handoff")
        val failing = HandoffCache(dir, maxFileBytes = 1_000_000)
        assertThrows(IOException::class.java) { failing.fetch("id1", 600_000, 5) { BytesReader(bytes(600_000), failAt = 300_000) } }
        assertEquals(0, dir.list()!!.size)
        // Past the deadline the copy is abandoned: the caller falls back to a proxy descriptor.
        var now = 0L
        val slow = HandoffCache(dir, maxFileBytes = 1_000_000, deadlineMs = 1_000, clock = { now })
        val reader = object : StreamReader {
            override fun read(offset: Long, length: Int, out: ByteArray, outOffset: Int): Int {
                now += 600
                return length
            }
            override fun close() {}
        }
        assertNull(slow.fetch("id1", 900_000, 5) { reader })
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun `the cache is trimmed least recently used first, and cleared on revoke`() {
        val dir = temp.root.resolve("handoff")
        var now = 1_000_000L
        val cache = HandoffCache(dir, maxFileBytes = 1_000, maxTotalBytes = 2_500, clock = { now })
        cache.fetch("a", 1_000, 1) { BytesReader(bytes(1_000)) }!!.setLastModified(now)
        now += 10_000
        cache.fetch("b", 1_000, 1) { BytesReader(bytes(1_000)) }!!.setLastModified(now)
        now += 10_000
        assertNotNull(cache.cached("a", 1_000, 1)) // a used again: b is now the oldest
        now += 10_000
        cache.fetch("c", 1_000, 1) { BytesReader(bytes(1_000)) }
        assertNull(cache.cached("b", 1_000, 1))
        assertNotNull(cache.cached("a", 1_000, 1))
        assertNotNull(cache.cached("c", 1_000, 1))
        cache.clear()
        assertEquals(0, dir.list()!!.size)
    }

    @Test
    fun `concurrent opens of one small file fetch it once`() {
        val cache = HandoffCache(temp.root.resolve("handoff"), maxFileBytes = 1_000_000)
        val data = bytes(500_000)
        val fetches = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val results = (1..8).map {
            pool.submit<ByteArray> {
                cache.fetch("same", 500_000, 1) { fetches.incrementAndGet(); BytesReader(data, delayMs = 5) }!!.readBytes()
            }
        }
        results.forEach { assertArrayEquals(data, it.get(10, TimeUnit.SECONDS)) }
        assertEquals(1, fetches.get())
        pool.shutdown()
    }

    // --- Timed reads ----------------------------------------------------------------------------------------------

    @Test
    fun `a read that takes too long fails instead of blocking, the next one waits for it`() {
        val executor = Executors.newCachedThreadPool()
        val release = CountDownLatch(1)
        val data = bytes(10_000)
        val inner = object : StreamReader {
            val calls = AtomicInteger()
            override fun read(offset: Long, length: Int, out: ByteArray, outOffset: Int): Int {
                if (calls.incrementAndGet() == 1) release.await()
                System.arraycopy(data, offset.toInt(), out, outOffset, length)
                return length
            }
            override fun close() {}
        }
        val reader = TimedReader(inner, executor, timeoutMs = 200)
        val out = ByteArray(100)
        val t0 = System.nanoTime()
        assertThrows(IOException::class.java) { reader.read(0, 100, out) }
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertTrue("took $ms ms", ms in 150..2_000)
        // The stuck read is still running: the next read waits for it within the bound, then fails too.
        assertThrows(IOException::class.java) { reader.read(100, 100, out) }
        release.countDown()
        assertEquals(100, reader.read(200, 100, out))
        assertArrayEquals(data.copyOfRange(200, 300), out)
        reader.close()
        executor.shutdown()
    }

    @Test
    fun `failures of the reader come back as IOExceptions, close closes the reader`() {
        val executor = Executors.newCachedThreadPool()
        val inner = BytesReader(bytes(1_000), failAt = 500)
        val reader = TimedReader(inner, executor, timeoutMs = 1_000)
        val out = ByteArray(100)
        assertEquals(100, reader.read(0, 100, out))
        assertThrows(IOException::class.java) { reader.read(450, 100, out) }
        reader.close()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(inner.closed)
        assertThrows(IOException::class.java) { reader.read(0, 10, out) }
    }
}
