package dev.hfps.hoard.image.cache

import dev.hfps.hoard.image.source.CancelToken
import dev.hfps.hoard.image.source.CancelledException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Background work with priorities, de-duplication and cancellation, for decode + prefetch:
 *
 * - A lower [priority] runs first (0 = the image on screen, 1 = next, 2 = previous, …); equal
 *   priorities run in submission order.
 * - Submitting a key that is already queued or running joins it (one decode per image), and raises
 *   its priority if the new one is more urgent.
 * - [cancel] / [retain] cancel queued work at once and signal running work through its [CancelToken]
 *   (staging and LibRaw check it).
 *
 * Plain threads (not coroutines) so the same scheduler serves JNI decodes that block for seconds.
 */
class PriorityScheduler(threads: Int, name: String = "hoard-image") {

    inner class Job<T> internal constructor(val key: String, @Volatile var priority: Int, private val work: (CancelToken) -> T) : Comparable<Job<*>> {
        internal val seq = sequence.incrementAndGet()
        val token = CancelToken()
        val future = CompletableFuture<T>()
        @Volatile internal var started = false

        override fun compareTo(other: Job<*>): Int = if (priority != other.priority) priority.compareTo(other.priority) else seq.compareTo(other.seq)

        internal fun run() {
            if (token.isCancelled) { future.completeExceptionally(CancelledException()); return }
            try {
                future.complete(work(token))
            } catch (t: Throwable) {
                future.completeExceptionally(if (token.isCancelled) CancelledException() else t)
            }
        }
    }

    private val sequence = AtomicLong()
    private val queue = PriorityBlockingQueue<Job<*>>()
    private val jobs = HashMap<String, Job<*>>()
    private val workers: List<Thread>
    @Volatile private var shutdown = false

    init {
        val factory = ThreadFactory { r -> Thread(r).apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
        workers = (0 until threads).map { i ->
            factory.newThread {
                while (!shutdown) {
                    val job = try { queue.take() } catch (_: InterruptedException) { break }
                    synchronized(jobs) { job.started = true }
                    try {
                        job.run()
                    } finally {
                        synchronized(jobs) { if (jobs[job.key] === job) jobs.remove(job.key) }
                    }
                }
            }.apply { this.name = "$name-$i"; start() }
        }
    }

    /** Submits (or joins) work for [key]. */
    @Suppress("UNCHECKED_CAST")
    fun <T> submit(key: String, priority: Int, work: (CancelToken) -> T): Job<T> {
        synchronized(jobs) {
            val existing = jobs[key] as Job<T>?
            if (existing != null && !existing.token.isCancelled && !existing.future.isDone) {
                if (priority < existing.priority && !existing.started && queue.remove(existing)) {
                    existing.priority = priority
                    queue.add(existing)
                }
                return existing
            }
            val job = Job(key, priority, work)
            jobs[key] = job
            queue.add(job)
            return job
        }
    }

    /** An already finished job (a cache hit), never queued. */
    fun <T> done(key: String, value: T): Job<T> = Job<T>(key, 0) { value }.also { it.future.complete(value) }

    fun cancel(key: String) {
        synchronized(jobs) {
            val job = jobs.remove(key) ?: return
            job.token.cancel()
            if (queue.remove(job)) job.future.completeExceptionally(CancelledException())
        }
    }

    /** Cancels every job whose key is not in [keys] (the prefetch window moved). */
    fun retain(keys: Set<String>) {
        val stale = synchronized(jobs) { jobs.keys.filter { it !in keys } }
        stale.forEach(::cancel)
    }

    fun pendingKeys(): Set<String> = synchronized(jobs) { jobs.keys.toSet() }

    fun shutdown() {
        shutdown = true
        synchronized(jobs) { jobs.values.forEach { it.token.cancel() }; jobs.clear() }
        queue.clear()
        workers.forEach { it.interrupt() }
    }
}
