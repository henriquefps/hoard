package dev.hfps.hoard.smb.stream

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What a token gives access to. */
enum class StreamKind {
    /** One file, through the content provider (and the HTTP server when [StreamEntry.http]). */
    FILE,

    /** A folder tree served over HTTP (an HTML page and its assets). */
    FOLDER,
}

/**
 * One live token. A FILE entry serves exactly [path]; a FOLDER entry serves [path] and everything
 * below it. Counters drive expiry ([StreamExpiry]) and the statistics logged when it ends.
 */
class StreamEntry(
    val token: String,
    val kind: StreamKind,
    val source: SmbSource,
    /** Share-relative path of the file, or of the served folder. */
    val path: String,
    /** File name (FILE) or the opened page's name (FOLDER), for the notification. */
    val name: String,
    /** File size (FILE); 0 for folders. */
    val size: Long,
    val mimeType: String,
    /** Also reachable at `http://127.0.0.1:<port>/<token>/…`. */
    val http: Boolean,
    /** The content:// URI handed out (FILE), to revoke grants when the token ends. */
    var contentUri: Uri? = null,
) {
    val openFds = AtomicInteger()
    val holders = AtomicInteger()
    val activeRequests = AtomicInteger()
    val fetched = AtomicLong()
    val served = AtomicLong()
    @Volatile var lastUsedAt: Long = System.currentTimeMillis()
    val createdAt: Long = System.currentTimeMillis()

    fun touch() {
        lastUsedAt = System.currentTimeMillis()
    }

    fun snapshot() = StreamExpiry.State(kind, openFds.get() + activeRequests.get(), holders.get(), lastUsedAt)
}

/**
 * When a token ends (pure, JVM-tested):
 *  - never while a file descriptor or an HTTP response is open, or while an in-app viewer holds it;
 *  - FILE: [FILE_IDLE_MS] after the last use (VLC closes and reopens the FD on some seeks and when
 *    it comes back from the background; the chooser may also stay open for a while);
 *  - FOLDER: [FOLDER_IDLE_MS] after the last request once no viewer holds it (a page handed to
 *    Chrome keeps working while the user reads it, and lazy assets still load).
 */
object StreamExpiry {
    const val FILE_IDLE_MS = 15 * 60_000L
    const val FOLDER_IDLE_MS = 30 * 60_000L

    data class State(val kind: StreamKind, val busy: Int, val holders: Int, val lastUsedAt: Long)

    fun isExpired(state: State, now: Long): Boolean {
        if (state.busy > 0 || state.holders > 0) return false
        val idle = if (state.kind == StreamKind.FILE) FILE_IDLE_MS else FOLDER_IDLE_MS
        return now - state.lastUsedAt >= idle
    }
}

/**
 * Process-wide table of live tokens. Adding the first entry starts [StreamService] (a foreground
 * service: without it Android freezes Hoard's cached process while VLC is in front, and every read
 * of the proxy FD would hang); removing the last stops it, and the HTTP server with it.
 */
object StreamRegistry {
    private const val TAG = "HoardStream"
    private val entries = ConcurrentHashMap<String, StreamEntry>()
    private lateinit var appContext: Context
    private val sweeper = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hoard-stream-sweep").apply { isDaemon = true } }
    @Volatile private var sweeping = false

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    fun add(entry: StreamEntry) {
        entries[entry.token] = entry
        if (!sweeping) {
            sweeping = true
            sweeper.scheduleWithFixedDelay(::sweep, 30, 30, TimeUnit.SECONDS)
        }
        changed()
    }

    /** The entry for [token] (constant-time checked), or null when unknown or expired. */
    fun get(token: String?): StreamEntry? {
        if (!StreamTokens.isWellFormed(token)) return null
        val entry = entries[token] ?: return null
        return if (StreamTokens.matches(entry.token, token)) entry else null
    }

    fun all(): List<StreamEntry> = entries.values.sortedBy { it.createdAt }

    fun hasHttp(): Boolean = entries.values.any { it.http }

    /** An in-app viewer shows this token: it cannot expire until [release]. */
    fun hold(token: String): Boolean {
        val entry = get(token) ?: return false
        entry.holders.incrementAndGet()
        entry.touch()
        return true
    }

    fun release(token: String) {
        val entry = get(token) ?: return
        if (entry.holders.decrementAndGet() < 0) entry.holders.set(0)
        entry.touch()
    }

    fun remove(token: String) {
        val entry = entries.remove(token) ?: return
        end(entry)
        changed()
    }

    /** Stop everything (notification "Stop", service timeout). Open FDs start failing with EIO. */
    fun clear() {
        val all = entries.values.toList()
        entries.clear()
        all.forEach(::end)
        changed()
    }

    /** App swiped away: drop what is not in use right now (a playing video keeps going). */
    fun trimIdle() {
        entries.values.filter { it.openFds.get() == 0 && it.activeRequests.get() == 0 }.forEach { entries.remove(it.token)?.let(::end) }
        changed()
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        val expired = entries.values.filter { StreamExpiry.isExpired(it.snapshot(), now) }
        if (expired.isEmpty()) return
        expired.forEach { entries.remove(it.token)?.let(::end) }
        changed()
    }

    private fun end(entry: StreamEntry) {
        Log.i(TAG, "Stream ended: ${entry.name} (${entry.kind}) size=${entry.size} fetched=${entry.fetched.get()} served=${entry.served.get()}")
        entry.contentUri?.let { uri ->
            runCatching { appContext.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        SmbSource.closeLater(entry.source)
    }

    private fun changed() {
        if (!::appContext.isInitialized) return
        if (!hasHttp()) LocalWebServer.stop()
        StreamService.sync(appContext)
    }
}
