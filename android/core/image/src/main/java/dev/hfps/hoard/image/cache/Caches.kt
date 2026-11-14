package dev.hfps.hoard.image.cache

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Identity of one file version: where it lives ([sourceId]: an SMB server GUID + share, "local",
 * a SAF tree URI…), its path there, and its size + modification time (a changed file gets a new key,
 * so stale cache entries are never served, only aged out).
 */
data class FileIdentity(val sourceId: String, val path: String, val size: Long, val modifiedMillis: Long)

/** What is cached for a file. */
enum class Variant(val extension: String) {
    /** Embedded RAW preview JPEG, extracted as-is. */
    PREVIEW("jpg"),
    /** Full-size LibRaw rendering of a RAW (unrotated JPEG), for region decode at 100%. */
    RAW_FULL("jpg"),
    /** Half-size LibRaw rendering (unrotated JPEG). */
    RAW_HALF("jpg"),
    /** A local copy of the original file (camera JPEG for region decode, RAW for LibRaw). */
    ORIGINAL("bin"),
    /** Upright screen-sized JPEG for the culling view (WebView `<img>`). */
    SCREEN("jpg"),
    /** Upright grid thumbnail JPEG. */
    THUMB("jpg"),
    /** Upright JPEG of one region at one sample size (100% zoom). */
    TILE("jpg"),
}

object CacheKeys {
    /**
     * Stable, file-system-safe key: SHA-256 over every field (NUL separated) + the variant + its
     * parameter (e.g. target size), truncated to 40 hex chars. Path case is kept: SMB paths are
     * case-insensitive but the server already returns one canonical spelling, and local/SAF paths are
     * case-sensitive.
     */
    fun key(identity: FileIdentity, variant: Variant, parameter: Any? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(identity.sourceId, identity.path, identity.size, identity.modifiedMillis, variant.name, parameter ?: "").forEach {
            digest.update(it.toString().toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(40)
    }

    fun fileName(identity: FileIdentity, variant: Variant, parameter: Any? = null): String =
        "${key(identity, variant, parameter)}.${variant.extension}"
}

/**
 * LRU by total size (bytes), not count: bitmaps vary from 2 MB (screen) to 30 MB (a 100% tile set).
 * Thread-safe. [onEvict] runs outside the lock.
 */
class SizedLruCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
    private val onEvict: (K, V) -> Unit = { _, _ -> },
) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var bytes = 0L

    val sizeBytes: Long @Synchronized get() = bytes
    val count: Int @Synchronized get() = map.size

    @Synchronized
    operator fun get(key: K): V? = map[key]

    fun put(key: K, value: V) {
        val size = sizeOf(value)
        val evicted = ArrayList<Pair<K, V>>()
        synchronized(this) {
            map.remove(key)?.let { old -> bytes -= sizeOf(old); if (old !== value) evicted += key to old }
            if (size > maxBytes) {
                // Larger than the whole cache: do not keep it (and do not flush everything for it).
                evicted += key to value
            } else {
                map[key] = value
                bytes += size
                val it = map.entries.iterator()
                while (bytes > maxBytes && it.hasNext()) {
                    val e = it.next()
                    if (e.key == key) continue
                    bytes -= sizeOf(e.value)
                    evicted += e.key to e.value
                    it.remove()
                }
            }
        }
        evicted.forEach { (k, v) -> onEvict(k, v) }
    }

    fun remove(key: K): V? {
        val removed = synchronized(this) { map.remove(key)?.also { bytes -= sizeOf(it) } }
        return removed
    }

    /** Drops entries until the cache holds at most [targetBytes] (memory pressure). */
    fun trimTo(targetBytes: Long) {
        val evicted = ArrayList<Pair<K, V>>()
        synchronized(this) {
            val it = map.entries.iterator()
            while (bytes > targetBytes && it.hasNext()) {
                val e = it.next()
                bytes -= sizeOf(e.value)
                evicted += e.key to e.value
                it.remove()
            }
        }
        evicted.forEach { (k, v) -> onEvict(k, v) }
    }

    fun clear() = trimTo(0)

    @Synchronized
    fun keys(): List<K> = map.keys.toList()
}

/**
 * Files under [dir], trimmed least-recently-used first to [maxBytes]. A hit touches the file's
 * mtime (the LRU clock). Writes go through a temp file and an atomic rename, so a crash leaves no
 * half-written entry under the final name.
 */
class DiskCache(val dir: File, private val maxBytes: Long, private val trimEvery: Int = 20) {
    private var writes = 0

    fun file(name: String): File = File(dir, name)

    /** The entry if present (and touches it). */
    fun get(name: String): File? {
        val f = File(dir, name)
        if (!f.isFile || f.length() == 0L) return null
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    /** Writes an entry with [write] into a temp file, then renames it into place. */
    fun put(name: String, write: (File) -> Unit): File {
        dir.mkdirs()
        val target = File(dir, name)
        val temp = File(dir, ".$name.${Thread.currentThread().id}.part")
        try {
            write(temp)
            if (!temp.isFile || temp.length() == 0L) throw IOException("Nothing written for $name")
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Could not move $name into place")
            }
        } finally {
            temp.delete()
        }
        maybeTrim(keep = target)
        return target
    }

    fun remove(name: String) { File(dir, name).delete() }

    @Synchronized
    private fun maybeTrim(keep: File) {
        if (++writes < trimEvery) return
        writes = 0
        trim(keep)
    }

    /** Deletes LRU entries (and stale temp files) until the folder holds at most [maxBytes]. */
    @Synchronized
    fun trim(keep: File? = null) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()
        files.filter { it.name.endsWith(".part") && now - it.lastModified() > 10 * 60_000 }.forEach { it.delete() }
        val entries = files.filter { !it.name.endsWith(".part") && it.exists() }
        var total = entries.sumOf { it.length() }
        for (f in entries.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            if (f == keep) continue
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    fun sizeBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
}
