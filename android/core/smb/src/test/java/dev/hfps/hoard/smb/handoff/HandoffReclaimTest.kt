package dev.hfps.hoard.smb.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Idle reclaim of proxy descriptors (ADR 0010 "Idle reclaim"): [HandoffHold] and [HandoffDescriptors]. */
class HandoffReclaimTest {
    private val now = AtomicLong(1_000_000L)
    private val clock = { now.get() }

    private fun descriptors(slots: Int = 6, waitMs: Long = 0, maxLive: Int = 12) =
        HandoffDescriptors(HandoffLimiter(slots, waitMs, "hand-off reads in progress"), maxLive, IDLE, RETIRE, clock)

    /** Opens a descriptor as `HandoffServer.open` does: a slot from the limiter, then a hold. */
    private fun HandoffDescriptors.open(cleanups: AtomicInteger = AtomicInteger()): HandoffHold =
        register(limiter.acquire()) { cleanups.incrementAndGet() }

    private fun advance(ms: Long) {
        now.addAndGet(ms)
    }

    @Test
    fun `the reclaim's constants - idle release inside the busy retry budget, retire well after, loopers bounded`() {
        assertTrue(HandoffProxy.IDLE_RELEASE_MS in 10_000L..15_000L)
        assertTrue(HandoffProxy.IDLE_RELEASE_MS > HandoffProxy.READ_TIMEOUT_MS)
        assertTrue(HandoffProxy.RETIRE_MS >= 5 * 60_000L)
        assertTrue(HandoffProxy.MAX_LIVE >= HandoffServer.MAX_READS)
    }

    @Test
    fun `leaked descriptors no longer starve new opens - their slots come back after the idle time`() {
        val d = descriptors()
        val leaked = List(6) { d.open() } // a client that never closes
        assertEquals(6, d.limiter.active)
        assertTrue(HandoffBusy.isBusy(assertThrows(FileNotFoundException::class.java) { d.limiter.acquire() }))

        advance(IDLE - 1)
        d.reap()
        assertEquals("not idle long enough yet", 6, d.limiter.active)

        advance(1)
        d.reap()
        assertEquals(0, d.limiter.active)
        assertTrue(leaked.all { it.state == HandoffHold.State.PARKED })
        assertEquals(6, d.live)
        // A well-behaved client gets all 6 slots.
        val fresh = List(6) { d.open() }
        assertEquals(6, d.limiter.active)
        fresh.forEach { d.released(it) }
        assertEquals(0, d.limiter.active)
    }

    @Test
    fun `a parked descriptor's next read takes a slot again, then parks again when idle`() {
        val d = descriptors()
        val cleanups = AtomicInteger()
        val hold = d.open(cleanups)
        advance(IDLE)
        d.reap()
        assertFalse(hold.holdsSlot())
        assertEquals(0, d.limiter.active)

        assertEquals(42, hold.use { 42 })
        assertTrue(hold.holdsSlot())
        assertEquals(HandoffHold.State.ACTIVE, hold.state)
        assertEquals(1, d.limiter.active)
        assertEquals(1, hold.reacquired)
        assertEquals(1, d.reacquiredTotal)

        advance(IDLE)
        d.reap()
        assertEquals(HandoffHold.State.PARKED, hold.state)
        assertEquals(0, d.limiter.active)
        assertEquals("parking is not closing", 0, cleanups.get())
    }

    @Test
    fun `a read never parks mid-call, however long it takes`() {
        val d = descriptors()
        val hold = d.open()
        hold.use {
            advance(IDLE * 3)
            d.reap()
            assertTrue(hold.holdsSlot())
            assertEquals(HandoffHold.State.ACTIVE, hold.state)
        }
        // Idle counts from the end of the read.
        d.reap()
        assertTrue(hold.holdsSlot())
        advance(IDLE)
        d.reap()
        assertFalse(hold.holdsSlot())
    }

    @Test
    fun `a size query keeps a descriptor from parking without taking a slot`() {
        val d = descriptors()
        val hold = d.open()
        advance(IDLE)
        d.reap()
        assertEquals(0, d.limiter.active)
        hold.touch()
        assertEquals("a size is answered from memory", 0, d.limiter.active)
        advance(RETIRE - 1)
        d.reap()
        assertEquals(HandoffHold.State.PARKED, hold.state)
    }

    @Test
    fun `when no slot frees, the parked descriptor's read fails (EIO) and the next read tries again`() {
        val d = descriptors(slots = 1, waitMs = 50)
        val parked = d.open()
        advance(IDLE)
        d.reap()
        val other = d.open()
        val t0 = System.nanoTime()
        val e = assertThrows(IOException::class.java) { parked.use { 1 } }
        assertTrue("the limiter's busy message travels as the cause", HandoffBusy.isBusy(e.cause))
        assertTrue("bounded by the limiter's wait", (System.nanoTime() - t0) / 1_000_000 < 1_000)
        assertEquals(HandoffHold.State.PARKED, parked.state)
        assertFalse(parked.holdsSlot())

        d.released(other)
        assertEquals(7, parked.use { 7 })
        assertTrue(parked.holdsSlot())
    }

    @Test
    fun `a re-acquire waits its turn fairly behind opens already waiting`() {
        val d = descriptors(slots = 1, waitMs = 1_500)
        val parked = d.open()
        advance(IDLE)
        d.reap()
        val holder = d.open()
        val order = Collections.synchronizedList(ArrayList<String>())
        val pool = Executors.newFixedThreadPool(2)
        val opener = pool.submit { d.limiter.acquire().use { order += "open" } }
        while (d.limiter.queued < 1) Thread.sleep(2)
        val reader = pool.submit { parked.use { order += "read" } }
        while (d.limiter.queued < 2) Thread.sleep(2)
        d.released(holder)
        opener.get(5, TimeUnit.SECONDS)
        reader.get(5, TimeUnit.SECONDS)
        pool.shutdown()
        assertEquals(listOf("open", "read"), order.toList())
    }

    @Test
    fun `a long idle descriptor is closed from Hoard's side once, and the client's later close does nothing more`() {
        val d = descriptors()
        val cleanups = AtomicInteger()
        val hold = d.open(cleanups)
        advance(RETIRE)
        d.reap()
        assertEquals(HandoffHold.State.RETIRED, hold.state)
        assertEquals(1, cleanups.get())
        assertEquals(0, d.limiter.active)
        assertEquals(0, d.live)
        assertEquals(1, d.retiredTotal)
        assertThrows(IOException::class.java) { hold.use { 1 } }
        assertEquals("a read of a retired descriptor takes no slot", 0, d.limiter.active)
        d.released(hold) // the client closes it at last
        assertEquals(1, cleanups.get())
        assertEquals(0, d.limiter.active)
    }

    @Test
    fun `a client's close gives the slot back and cleans up once, active or parked`() {
        val d = descriptors()
        val cleanups = AtomicInteger()
        val active = d.open(cleanups)
        val parked = d.open(cleanups)
        advance(IDLE)
        d.reap()
        active.use { }
        assertEquals(1, d.limiter.active)
        d.released(active)
        d.released(parked)
        d.released(parked)
        assertEquals(0, d.limiter.active)
        assertEquals(2, cleanups.get())
        assertEquals(0, d.live)
    }

    @Test
    fun `loopers are bounded - a leaking client retires the least recently used parked descriptor`() {
        val d = descriptors(slots = 2, maxLive = 4)
        val cleanups = AtomicInteger()
        val holds = ArrayList<HandoffHold>()
        repeat(4) { i ->
            holds += d.open(cleanups)
            advance(1_000L * (i + 1))
            if (i % 2 == 1) {
                advance(IDLE)
                d.reap()
            }
        }
        assertEquals(4, d.live)
        assertEquals((0 until 4).toSet(), holds.map { it.looper }.toSet())
        val oldest = holds.first()
        val fifth = d.open(cleanups)
        assertEquals(oldest.looper, fifth.looper)
        assertEquals(HandoffHold.State.RETIRED, oldest.state)
        assertEquals(1, cleanups.get())
        assertEquals(4, d.live)
        assertTrue(fifth.looper in 0 until 4)
    }

    @Test
    fun `a closed descriptor's looper is reused, never a new one`() {
        val d = descriptors(slots = 2, maxLive = 2)
        val a = d.open()
        val b = d.open()
        assertNotEquals(a.looper, b.looper)
        d.released(a)
        val c = d.open()
        assertEquals(a.looper, c.looper)
    }

    @Test
    fun `leaking twenty descriptors then a normal burst - no deadlock, every normal open gets a slot`() {
        val d = HandoffDescriptors(HandoffLimiter(6, 200, "hand-off reads in progress"), 12, 50, 10_000)
        val leaked = Collections.synchronizedList(ArrayList<HandoffHold>())
        val stop = CountDownLatch(1)
        val reaper = Executors.newSingleThreadScheduledExecutor()
        reaper.scheduleWithFixedDelay({ d.reap() }, 10, 10, TimeUnit.MILLISECONDS)
        val pool = Executors.newFixedThreadPool(32)
        // The leaker: 20 opens with the busy retry, never closed; some read again now and then, after longer than the
        // idle time (re-acquire churn).
        val leakers = List(20) {
            pool.submit {
                var hold: HandoffHold? = null
                while (hold == null) {
                    hold = try {
                        d.register(d.limiter.acquire()) {}
                    } catch (e: FileNotFoundException) {
                        Thread.sleep(20)
                        null
                    }
                }
                leaked += hold
                while (!stop.await(120, TimeUnit.MILLISECONDS)) runCatching { if (it % 3 == 0) hold.use { Thread.sleep(1) } }
            }
        }
        Thread.sleep(300)
        // The normal client: 30 opens, read, close, retrying busy.
        val ok = AtomicInteger()
        val normal = List(30) {
            pool.submit {
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val hold = d.register(d.limiter.acquire()) {}
                        hold.use { Thread.sleep(2) }
                        d.released(hold)
                        ok.incrementAndGet()
                        break
                    } catch (e: FileNotFoundException) {
                        Thread.sleep(25)
                    }
                }
            }
        }
        normal.forEach { it.get(10, TimeUnit.SECONDS) }
        stop.countDown()
        leakers.forEach { it.get(10, TimeUnit.SECONDS) }
        assertEquals(30, ok.get())
        assertTrue(d.limiter.active <= 6)
        leaked.toList().forEach { d.released(it) }
        reaper.shutdownNow()
        pool.shutdownNow()
        assertEquals(0, d.limiter.active)
        assertEquals(0, d.live)
    }

    private companion object {
        const val IDLE = 12_000L
        const val RETIRE = 600_000L
    }
}
