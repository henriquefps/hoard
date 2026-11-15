package dev.hfps.hoard.smb.handoff

import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * One proxy descriptor's hold on the read limiter, with idle reclaim (ADR 0010 "Idle reclaim"; pure, JVM-tested).
 *
 * A descriptor starts **active**, holding the [HandoffLimiter.Slot] its open took. With no callback for
 * [HandoffDescriptors.idleReleaseMs] it is **parked**: the slot goes back to the limiter, the descriptor stays open
 * (its SMB reader too). Its next callback that needs the network ([use]) takes a slot again through the same limiter,
 * fairly, waiting at most the limiter's wait; when none frees that callback fails ([IOException], `EIO` for the
 * kernel) and the next one tries again. With no callback for [HandoffDescriptors.retireMs] a parked descriptor is
 * **retired**: Hoard runs its [cleanup] (what the descriptor's `onRelease` would: the SMB reader closed, the lease let
 * go), and every later callback fails at once. The client's eventual close ([close]) runs [cleanup] only when it has
 * not run yet.
 *
 * A descriptor is never parked or retired while a callback is inside [use] (a long read is not idle).
 *
 * Locks: the hold's monitor is never held while calling the limiter (a parked descriptor waiting for a slot holds
 * nothing), so slots and holds cannot deadlock.
 */
class HandoffHold internal constructor(
    private val limiter: HandoffLimiter,
    slot: HandoffLimiter.Slot,
    /** The looper this descriptor's callbacks run on (fixed for its life, see [HandoffDescriptors]). */
    val looper: Int,
    private val clock: () -> Long,
    private val cleanup: () -> Unit,
    private val onReacquire: () -> Unit = {},
) {
    enum class State { ACTIVE, PARKED, RETIRED, CLOSED }

    private var slot: HandoffLimiter.Slot? = slot
    private var inUse = 0
    private var cleaned = false

    var state: State = State.ACTIVE
        private set

    /** When a callback last started or ended (or the open). */
    var lastUse: Long = clock()
        private set

    /** Times a parked descriptor took a slot again. */
    var reacquired: Int = 0
        private set

    /** The descriptor may be handed to another looper owner: retired or closed. */
    val done: Boolean @Synchronized get() = state == State.RETIRED || state == State.CLOSED

    /**
     * Runs [block] (a callback that may do network work) holding a slot: a parked descriptor takes one again first.
     * [IOException] when the descriptor was retired, or no slot freed within the limiter's wait.
     */
    fun <T> use(block: () -> T): T {
        val needsSlot = synchronized(this) {
            if (state == State.RETIRED || state == State.CLOSED) throw IOException("descriptor closed by Hoard (idle too long, or its looper was needed); open the link again")
            inUse++
            lastUse = clock()
            slot == null
        }
        try {
            if (needsSlot) {
                val taken = try {
                    limiter.acquire()
                } catch (e: FileNotFoundException) {
                    throw IOException(e.message, e)
                }
                var giveBack: HandoffLimiter.Slot? = null
                val dead = synchronized(this) {
                    when {
                        state == State.RETIRED || state == State.CLOSED -> {
                            giveBack = taken
                            true
                        }
                        slot == null -> {
                            slot = taken
                            state = State.ACTIVE
                            reacquired++
                            onReacquire()
                            false
                        }
                        else -> {
                            giveBack = taken // another callback took one first
                            false
                        }
                    }
                }
                giveBack?.close()
                if (dead) throw IOException("descriptor closed while waiting for a read slot")
            }
            return block()
        } finally {
            synchronized(this) {
                inUse--
                lastUse = clock()
            }
        }
    }

    /** A callback that needs no slot (a size from memory): only marks the descriptor as used. */
    @Synchronized
    fun touch() {
        lastUse = clock()
    }

    /**
     * Parks or retires the descriptor when it has been idle long enough; returns its state afterwards. Never while a
     * callback is running.
     */
    fun reap(idleReleaseMs: Long, retireMs: Long): State {
        var give: HandoffLimiter.Slot? = null
        var clean = false
        val after = synchronized(this) {
            if (inUse == 0 && (state == State.ACTIVE || state == State.PARKED)) {
                val idle = clock() - lastUse
                if (idle >= idleReleaseMs && slot != null) {
                    give = slot
                    slot = null
                    state = State.PARKED
                }
                if (idle >= retireMs) {
                    state = State.RETIRED
                    clean = !cleaned
                    cleaned = true
                }
            }
            state
        }
        give?.close()
        if (clean) cleanup()
        return after
    }

    /** Retires a parked, idle descriptor now (its looper is needed); false when it is active or in a callback. */
    fun retireIfParked(): Boolean {
        val clean = synchronized(this) {
            if (inUse != 0 || state != State.PARKED) return false
            state = State.RETIRED
            val first = !cleaned
            cleaned = true
            first
        }
        if (clean) cleanup()
        return true
    }

    /** The client closed the descriptor (`onRelease`): the slot back if held, [cleanup] if not run yet. */
    fun close() {
        var give: HandoffLimiter.Slot? = null
        val clean = synchronized(this) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            give = slot
            slot = null
            val first = !cleaned
            cleaned = true
            first
        }
        give?.close()
        if (clean) cleanup()
    }

    @Synchronized
    internal fun holdsSlot(): Boolean = slot != null

    @Synchronized
    internal fun idleFor(): Long = if (inUse > 0) -1 else clock() - lastUse
}

/**
 * The live proxy descriptors and their loopers (pure, JVM-tested). Every proxy descriptor's callbacks run on one
 * looper fixed at its open (Android binds the handler then). There are [maxLive] loopers, created once and reused: a
 * new descriptor takes a looper no live descriptor owns; when none is free, the least recently used **parked**
 * descriptor is retired and its looper reused (a retired descriptor answers its callbacks at once, so sharing its
 * looper delays nobody). Only when every live descriptor is active or inside a callback (at most [maxLive] of them,
 * which [HandoffServer.MAX_READS] slots make rare) does a looper get a second live owner: the least loaded one.
 *
 * So Hoard never runs more than [maxLive] looper threads whatever a client leaks, and an active descriptor's size and
 * reads are not delayed by another live descriptor's callbacks.
 *
 * [reap] (every [tickMs] while any descriptor is live) parks and retires idle descriptors ([HandoffHold.reap]).
 */
class HandoffDescriptors(
    val limiter: HandoffLimiter,
    val maxLive: Int,
    val idleReleaseMs: Long,
    val retireMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxLive >= limiter.slots && idleReleaseMs in 1 until retireMs)
    }

    private val owners = Array(maxLive) { ArrayList<HandoffHold>(1) }

    /** Descriptors not retired or closed. */
    val live: Int @Synchronized get() = owners.sumOf { list -> list.count { !it.done } }

    /** Of [live], those holding a slot. */
    val active: Int @Synchronized get() = owners.sumOf { list -> list.count { !it.done && it.holdsSlot() } }

    /** Descriptors retired by Hoard since the start (idle, or their looper was needed). */
    val retiredTotal: Int get() = retired.get()
    private val retired = AtomicInteger()

    /** Times a parked descriptor took a slot again since the start. */
    val reacquiredTotal: Int get() = reacquired.get()
    private val reacquired = AtomicInteger()

    /** Of [live], those parked. */
    val parked: Int @Synchronized get() = owners.sumOf { list -> list.count { it.state == HandoffHold.State.PARKED } }

    /**
     * A hold for a descriptor opened with [slot]; [cleanup] is what its `onRelease` does. The hold's looper is the
     * index of a looper thread (`0 until maxLive`).
     */
    fun register(slot: HandoffLimiter.Slot, cleanup: () -> Unit): HandoffHold {
        return synchronized(this) {
            prune()
            var looper = owners.indexOfFirst { it.isEmpty() }
            if (looper < 0) {
                // The least recently used parked descriptor gives its looper up.
                val victim = owners.flatMap { it }.filter { it.state == HandoffHold.State.PARKED && it.idleFor() >= 0 }.maxByOrNull { it.idleFor() }
                if (victim != null && victim.retireIfParked()) {
                    retired.incrementAndGet()
                    looper = victim.looper
                    owners[looper].remove(victim)
                }
            }
            if (looper < 0) looper = owners.indices.minBy { owners[it].size }
            HandoffHold(limiter, slot, looper, clock, cleanup) { reacquired.incrementAndGet() }.also { owners[looper] += it }
        }
    }

    /** The client closed [hold]'s descriptor. */
    fun released(hold: HandoffHold) {
        hold.close()
        synchronized(this) { owners[hold.looper].remove(hold) }
    }

    /** Parks and retires idle descriptors; returns how many were parked or retired by this call. */
    fun reap(): Int {
        val holds = synchronized(this) { owners.flatMap { it }.filter { !it.done } }
        var changed = 0
        for (h in holds) {
            val before = h.state
            val after = h.reap(idleReleaseMs, retireMs)
            if (after != before) changed++
            if (after == HandoffHold.State.RETIRED && before != after) retired.incrementAndGet()
        }
        synchronized(this) { prune() }
        return changed
    }

    /** Retired descriptors keep no looper (closed ones were removed by [released]). */
    private fun prune() {
        owners.forEach { list -> list.removeAll { it.done } }
    }
}
