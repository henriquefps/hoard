/**
 * A small concurrency-limited, cached loader (thumbnails). Results are cached by key; a request
 * that has not started yet can be cancelled (the row scrolled out of view), so a fast fling through
 * a 5,000-entry folder does not queue 5,000 thumbnails. Failures are cached too, so a broken image
 * is not retried on every scroll.
 */
export class TaskQueue<T> {
  private readonly cache = new Map<string, { value?: T; error?: unknown }>()
  private readonly inflight = new Map<string, Promise<T>>()
  private readonly waiting: { key: string; start: () => void; cancelled: boolean }[] = []
  private running = 0

  private readonly concurrency: number
  private readonly maxEntries: number

  constructor(concurrency = 4, maxEntries = 600) {
    this.concurrency = concurrency
    this.maxEntries = maxEntries
  }

  /** The cached value, if loaded. */
  peek(key: string): T | undefined {
    return this.cache.get(key)?.value
  }

  hasFailed(key: string): boolean {
    return this.cache.get(key)?.error !== undefined
  }

  /**
   * Load `key` with `loader` (once; later calls share the result). `signal` cancels it while it is
   * still waiting for a slot; once running it completes and is cached.
   */
  get(key: string, loader: () => Promise<T>, signal?: AbortSignal): Promise<T> {
    const cached = this.cache.get(key)
    if (cached) {
      return cached.error !== undefined
        ? Promise.reject(cached.error)
        : Promise.resolve(cached.value as T)
    }
    const running = this.inflight.get(key)
    if (running) return running
    const promise = new Promise<T>((resolve, reject) => {
      const job = {
        key,
        cancelled: false,
        start: () => {
          this.running++
          loader()
            .then(
              (value) => {
                this.store(key, { value })
                resolve(value)
              },
              (error: unknown) => {
                this.store(key, { error: error ?? new Error("failed") })
                reject(error)
              }
            )
            .finally(() => {
              this.inflight.delete(key)
              this.running--
              this.next()
            })
        },
      }
      signal?.addEventListener("abort", () => {
        if (job.cancelled || !this.waiting.includes(job)) return
        job.cancelled = true
        this.waiting.splice(this.waiting.indexOf(job), 1)
        this.inflight.delete(key)
        reject(new DOMException("Aborted", "AbortError"))
      })
      this.waiting.push(job)
    })
    this.inflight.set(key, promise)
    this.next()
    return promise
  }

  private store(key: string, entry: { value?: T; error?: unknown }) {
    this.cache.set(key, entry)
    if (this.cache.size > this.maxEntries) {
      const oldest = this.cache.keys().next().value
      if (oldest !== undefined) this.cache.delete(oldest)
    }
  }

  private next() {
    while (this.running < this.concurrency && this.waiting.length > 0) {
      // Newest first: the rows the user is looking at now beat the ones scrolled past.
      const job = this.waiting.pop()!
      job.start()
    }
  }

  /** Jobs currently loading (tests). */
  get active() {
    return this.running
  }

  get queued() {
    return this.waiting.length
  }
}
