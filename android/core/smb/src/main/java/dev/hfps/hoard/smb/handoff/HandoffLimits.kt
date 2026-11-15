package dev.hfps.hoard.smb.handoff

import java.io.FileNotFoundException
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The contract of a refused open (ADR 0010 "Bursts of opens", frozen; docs/integration contract 11): a
 * [FileNotFoundException] whose message starts exactly with `busy:`, thrown in one case only: the provider already
 * serves its maximum number of concurrent hand-off reads ([HandoffLimiter.slots]) and a bounded wait
 * ([HandoffLimiter.waitMs], under 2 s) freed no slot. Clients retry those (250 ms doubling up to 4 s, giving up after
 * about 30 s) and tell them apart from a revoked or unreadable link (any other message). Pure.
 */
object HandoffBusy {
    const val PREFIX = "busy:"

    /** The contract's ceiling on [HandoffLimiter.waitMs]. */
    const val MAX_WAIT_MS = 2_000L

    fun exception(detail: String) = FileNotFoundException("$PREFIX $detail, retry")

    fun isBusy(e: Throwable?): Boolean = e is FileNotFoundException && e.message?.startsWith(PREFIX) == true
}

/**
 * A bounded, fair slot pool (pure, JVM-tested): at most [slots] holders at once; other callers wait (first come,
 * first served) at most [waitMs] for a slot, then [acquire] throws [HandoffBusy]. It never refuses before the wait
 * (the busy contract), and never blocks a caller (a binder thread) longer than [waitMs]; the number of waiters is
 * bounded by the binder threads of the process (16).
 *
 * A [Slot] carries its index (`0 until slots`), so a pool of per-slot resources (one looper per proxy descriptor)
 * is reused instead of created per open.
 */
class HandoffLimiter(
    val slots: Int,
    val waitMs: Long,
    /** What is limited, for the busy message (`"6 hand-off reads in progress"`). */
    private val what: String,
) {
    init {
        require(slots > 0 && waitMs >= 0 && waitMs < HandoffBusy.MAX_WAIT_MS)
    }

    private val lock = ReentrantLock(true)
    private val changed = lock.newCondition()
    private val free = ArrayDeque<Int>((0 until slots).toList())
    private val queue = ArrayDeque<Any>()

    /** Holders right now. */
    val active: Int get() = lock.withLock { slots - free.size }

    /** Callers waiting right now. */
    val queued: Int get() = lock.withLock { queue.size }

    inner class Slot internal constructor(val index: Int) : AutoCloseable {
        private var released = false

        /** Gives the slot back (idempotent). */
        override fun close() {
            lock.withLock {
                if (released) return
                released = true
                free.addLast(index)
                changed.signalAll()
            }
        }
    }

    /** A slot, waiting at most [waitMs]; [HandoffBusy] when none frees up in time. */
    fun acquire(): Slot = lock.withLock {
        if (queue.isEmpty() && free.isNotEmpty()) return Slot(free.removeFirst())
        val ticket = Any()
        queue.addLast(ticket)
        try {
            var left = TimeUnit.MILLISECONDS.toNanos(waitMs)
            while (queue.peekFirst() !== ticket || free.isEmpty()) {
                if (left <= 0L) throw busy()
                left = changed.awaitNanos(left)
            }
            Slot(free.removeFirst())
        } finally {
            queue.remove(ticket)
            changed.signalAll()
        }
    }

    private fun busy() = HandoffBusy.exception("$slots $what")
}

/**
 * Remembers that a machine + share could not be reached (pure, JVM-tested): for [holdMs] after a failure, opens fail
 * at once instead of each one trying every address again (a burst of 200 opens on an unreachable share would
 * otherwise mean 200 connect timeouts). A success forgets it.
 */
class ConnectBackoff(private val holdMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val failures = HashMap<String, Pair<Long, String>>()

    /** The reason [key] failed less than [holdMs] ago, or null when it may be tried. */
    @Synchronized
    fun blocked(key: String): String? {
        val (at, why) = failures[key] ?: return null
        if (clock() - at < holdMs) return why
        failures.remove(key)
        return null
    }

    @Synchronized
    fun failed(key: String, why: String) {
        failures[key] = clock() to why
    }

    @Synchronized
    fun succeeded(key: String) {
        failures.remove(key)
    }
}

/** How one open of a hand-off link ended, for [HandoffBurst]. */
enum class OpenOutcome { CACHED, PROXIED, WRITE, REFUSED, FAILED }

/**
 * One log line per burst of opens instead of one per open (pure, JVM-tested): a burst starts with an open and ends
 * once no open started or ended for [quietMs]; [flush] then returns its summary, e.g.
 * `open x37 in 2.1 s, 4 concurrent, 0 refused (30 cached, 7 proxied, 0 failed)`.
 */
class HandoffBurst(private val quietMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private var startedAt = 0L
    private var lastAt = 0L
    private var running = 0
    private var maxRunning = 0
    private val counts = IntArray(OpenOutcome.entries.size)
    private var opens = 0

    @Synchronized
    fun begin() {
        val now = clock()
        if (opens == 0 && running == 0) startedAt = now
        opens++
        running++
        if (running > maxRunning) maxRunning = running
        lastAt = now
    }

    @Synchronized
    fun end(outcome: OpenOutcome) {
        if (running > 0) running--
        counts[outcome.ordinal]++
        lastAt = clock()
    }

    /** Opens of the current burst so far. */
    @Synchronized
    fun pending(): Int = opens

    /** The summary of the burst when it is over (nothing running, quiet for [quietMs]), and resets; else null. */
    @Synchronized
    fun flush(): String? {
        if (opens == 0 || running > 0 || clock() - lastAt < quietMs) return null
        fun n(o: OpenOutcome) = counts[o.ordinal]
        val seconds = (lastAt - startedAt) / 1000.0
        val writes = if (n(OpenOutcome.WRITE) > 0) ", ${n(OpenOutcome.WRITE)} written" else ""
        val line = String.format(
            Locale.ROOT,
            "open x%d in %.1f s, %d concurrent, %d refused (%d cached, %d proxied%s, %d failed)",
            opens, seconds, maxRunning, n(OpenOutcome.REFUSED), n(OpenOutcome.CACHED), n(OpenOutcome.PROXIED), writes, n(OpenOutcome.FAILED),
        )
        opens = 0
        maxRunning = 0
        counts.fill(0)
        return line
    }
}
