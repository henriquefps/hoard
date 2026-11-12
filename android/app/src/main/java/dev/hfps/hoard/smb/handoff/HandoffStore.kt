package dev.hfps.hoard.smb.handoff

import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.stream.StreamAddress
import dev.hfps.hoard.smb.stream.StreamTokens
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** What a hand-off link points at. */
enum class HandoffKind {
    /** A file of an SMB share, served by `StreamProvider` under `content://<app>.stream/h/<id>/…`. */
    SMB,

    /** A file on the phone handed over through the FileProvider (recorded only so "Revoke all" reaches it). */
    LOCAL,
}

/**
 * One file Hoard handed to Reveal (ADR 0010 "Persistable hand-offs"). [id] is random (256 bits, never a path) and
 * stable: the same file handed over again gets the same id, so the grant Reveal persisted keeps matching.
 */
data class HandoffItem(
    val id: String,
    val kind: HandoffKind,
    /** SMB: the machine and share; null for LOCAL. */
    val machineId: String?,
    val share: String?,
    /** SMB: share-relative path (normalised); LOCAL: the URI handed out. */
    val path: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modifiedMillis: Long?,
    /** An XMP sidecar item: the only kind Reveal may write (and only SMB ones are written through Hoard). */
    val sidecar: Boolean,
    /** SMB: an opaque tag of the file's folder (same for every file of one folder, so Reveal pairs a RAW + JPEG). */
    val folderTag: String?,
    /** The content URI handed out (to revoke it). */
    val uri: String,
    val createdAt: Long,
    val lastUsedAt: Long,
)

/** What the content provider needs to reach a machine when Hoard's UI is not running (ADR 0010 "Cold start"). */
data class HandoffMachine(
    val id: String,
    /** The pinned SMB server GUID; a machine without one is never connected to natively. */
    val serverGuid: String?,
    /** In the machine's priority order. */
    val addresses: List<StreamAddress>,
    /** The address that last worked, tried first. */
    val lastGood: StreamAddress? = null,
)

/**
 * The native, persistent table of hand-off links and the mirror of the machines they need: one JSON file in app
 * storage (`noBackupFilesDir`, never backed up), written atomically (temp + rename) on every change. Pure apart from
 * the file (JVM-tested). Thread-safe.
 *
 * Bounded: SMB links unused for [MAX_IDLE_MS] are pruned ([prune]); Android does not let Hoard see which grants other
 * apps persisted (`persistedUriPermissions` lists only Hoard's own), so age is the only signal. LOCAL records are
 * capped at [MAX_LOCAL] (oldest forgotten first, their grants untouched: they cost Hoard nothing to serve).
 */
class HandoffStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = StreamTokens::generate,
) {
    private var loaded = false
    private val items = LinkedHashMap<String, HandoffItem>()
    private val byKey = HashMap<String, String>()
    private val folders = HashMap<String, String>()
    private val machines = LinkedHashMap<String, HandoffMachine>()

    // --- Links ----------------------------------------------------------------------------------------------------

    /**
     * The link of an SMB file, created on first hand-off (a random id, the folder's tag) and refreshed after (name,
     * type, size, mtime, last use). Same machine + share + path (case-insensitively, as SMB) → same id.
     */
    @Synchronized
    fun smbItem(
        machineId: String,
        share: String,
        rawPath: String,
        mimeType: String,
        size: Long,
        modifiedMillis: Long?,
        sidecar: Boolean,
        uriFor: (id: String, folderTag: String, name: String) -> String,
    ): HandoffItem {
        load()
        val path = SmbPaths.normalize(rawPath)
        require(path.isNotEmpty()) { "A hand-off is a file, not the share's root" }
        val name = SmbPaths.name(path)
        val key = smbKey(machineId, share, path)
        val now = clock()
        val existing = byKey[key]?.let(items::get)
        val item = if (existing != null) {
            existing.copy(path = path, name = name, mimeType = mimeType, size = size, modifiedMillis = modifiedMillis, sidecar = sidecar, lastUsedAt = now)
        } else {
            val folderKey = folderKey(machineId, share, SmbPaths.parent(path))
            val tag = folders.getOrPut(folderKey) { newId().take(TAG_LENGTH) }
            val id = uniqueId()
            HandoffItem(id, HandoffKind.SMB, machineId, share, path, name, mimeType, size, modifiedMillis, sidecar, tag, uriFor(id, tag, name), now, now)
        }
        put(key, item)
        save()
        return item
    }

    /** A FileProvider URI handed over (photo or sidecar): recorded once, refreshed after. */
    @Synchronized
    fun localItem(uri: String, name: String, mimeType: String, sidecar: Boolean): HandoffItem {
        load()
        val key = "local|$uri"
        val now = clock()
        val item = byKey[key]?.let(items::get)?.copy(name = name, mimeType = mimeType, sidecar = sidecar, lastUsedAt = now)
            ?: HandoffItem(uniqueId(), HandoffKind.LOCAL, null, null, uri, name, mimeType, 0, null, sidecar, null, uri, now, now)
        put(key, item)
        save()
        return item
    }

    @Synchronized
    fun get(id: String?): HandoffItem? {
        if (!StreamTokens.isWellFormed(id)) return null
        load()
        val item = items[id] ?: return null
        return if (StreamTokens.matches(item.id, id)) item else null
    }

    /** Reveal read [id] now. Written at most once per [TOUCH_EVERY_MS] (an editor reads a file many times). */
    @Synchronized
    fun touch(id: String) {
        load()
        val item = items[id] ?: return
        val now = clock()
        if (now - item.lastUsedAt < TOUCH_EVERY_MS) return
        items[id] = item.copy(lastUsedAt = now)
        save()
    }

    /** The file's size / mtime as last seen (opened, or written through the provider). */
    @Synchronized
    fun updateFile(id: String, size: Long, modifiedMillis: Long?) {
        load()
        val item = items[id] ?: return
        if (item.size == size && item.modifiedMillis == modifiedMillis) return
        items[id] = item.copy(size = size, modifiedMillis = modifiedMillis, lastUsedAt = clock())
        save()
    }

    @Synchronized
    fun count(): Int {
        load()
        return items.size
    }

    @Synchronized
    fun all(): List<HandoffItem> {
        load()
        return items.values.toList()
    }

    /**
     * Drops SMB links unused for [MAX_IDLE_MS] and the oldest LOCAL records beyond [MAX_LOCAL]; returns what was
     * dropped (the caller revokes the SMB ones' grants).
     */
    @Synchronized
    fun prune(): List<HandoffItem> {
        load()
        val now = clock()
        val stale = items.values.filter { it.kind == HandoffKind.SMB && now - it.lastUsedAt >= MAX_IDLE_MS }.toMutableList()
        val locals = items.values.filter { it.kind == HandoffKind.LOCAL }
        if (locals.size > MAX_LOCAL) stale += locals.sortedBy { it.lastUsedAt }.take(locals.size - MAX_LOCAL)
        if (stale.isEmpty()) return emptyList()
        stale.forEach(::remove)
        dropUnusedFolders()
        save()
        return stale
    }

    /** "Revoke all": forgets every link (the caller revokes the grants); returns them. Machines stay. */
    @Synchronized
    fun clear(): List<HandoffItem> {
        load()
        val all = items.values.toList()
        items.clear()
        byKey.clear()
        folders.clear()
        save()
        return all
    }

    // --- Machines -------------------------------------------------------------------------------------------------

    /**
     * The machines as the app knows them now (`syncMachines`): replaces the mirror, keeping each machine's last good
     * address while it is still one of its addresses. Machines no longer listed are forgotten (their links stop being
     * servable: Hoard deleted the machine and its credentials).
     */
    @Synchronized
    fun setMachines(list: List<HandoffMachine>) {
        load()
        val before = HashMap(machines)
        machines.clear()
        for (machine in list) {
            val lastGood = before[machine.id]?.lastGood?.takeIf { it in machine.addresses }
            machines[machine.id] = machine.copy(lastGood = lastGood)
        }
        save()
    }

    /**
     * A hand-off of [machine] (its route in use first): known machines keep their order and only learn the GUID and
     * the address in use; an unknown one (the app never synced it) is added as given.
     */
    @Synchronized
    fun noteRoute(machine: HandoffMachine) {
        load()
        val known = machines[machine.id]
        val first = machine.addresses.firstOrNull()
        machines[machine.id] = if (known == null) {
            machine.copy(lastGood = first)
        } else {
            val addresses = known.addresses + machine.addresses.filter { it !in known.addresses }
            known.copy(serverGuid = machine.serverGuid ?: known.serverGuid, addresses = addresses, lastGood = first ?: known.lastGood)
        }
        save()
    }

    @Synchronized
    fun machine(id: String?): HandoffMachine? {
        load()
        return id?.let(machines::get)
    }

    /** The provider connected through [address]: try it first next time. */
    @Synchronized
    fun setLastGood(machineId: String, address: StreamAddress) {
        load()
        val machine = machines[machineId] ?: return
        if (machine.lastGood == address || address !in machine.addresses) return
        machines[machineId] = machine.copy(lastGood = address)
        save()
    }

    // --- Storage --------------------------------------------------------------------------------------------------

    private fun uniqueId(): String {
        while (true) {
            val id = newId()
            if (id !in items) return id
        }
    }

    private fun put(key: String, item: HandoffItem) {
        items[item.id] = item
        byKey[key] = item.id
    }

    private fun remove(item: HandoffItem) {
        items.remove(item.id)
        byKey.remove(keyOf(item))
    }

    private fun keyOf(item: HandoffItem) = when (item.kind) {
        HandoffKind.SMB -> smbKey(item.machineId.orEmpty(), item.share.orEmpty(), item.path)
        HandoffKind.LOCAL -> "local|${item.path}"
    }

    private fun dropUnusedFolders() {
        val used = items.values.mapNotNullTo(HashSet()) { it.folderTag }
        folders.values.retainAll(used)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        val json = try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A damaged file loses the links (Reveal falls back to its previews); never crash the provider over it.
            return
        }
        json.optJSONArray("items")?.let { array ->
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val item = runCatching { itemOf(o) }.getOrNull() ?: continue
                put(keyOf(item), item)
            }
        }
        json.optJSONObject("folders")?.let { o -> o.keys().forEach { k -> folders[k] = o.getString(k) } }
        json.optJSONArray("machines")?.let { array ->
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val machine = runCatching { machineOf(o) }.getOrNull() ?: continue
                machines[machine.id] = machine
            }
        }
    }

    private fun save() {
        val json = JSONObject()
            .put("version", VERSION)
            .put("items", JSONArray().apply { items.values.forEach { put(itemJson(it)) } })
            .put("folders", JSONObject().apply { folders.forEach { (k, v) -> put(k, v) } })
            .put("machines", JSONArray().apply { machines.values.forEach { put(machineJson(it)) } })
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        const val VERSION = 1

        /** An SMB link nobody read for this long is forgotten (and its grant revoked). */
        const val MAX_IDLE_MS = 180L * 24 * 60 * 60 * 1000
        const val MAX_LOCAL = 20_000
        const val TOUCH_EVERY_MS = 60L * 60 * 1000
        private const val TAG_LENGTH = 32

        private fun smbKey(machineId: String, share: String, path: String) =
            "smb|$machineId|${share.lowercase(Locale.ROOT)}|${path.lowercase(Locale.ROOT)}"

        private fun folderKey(machineId: String, share: String, folder: String) =
            "$machineId|${share.lowercase(Locale.ROOT)}|${folder.lowercase(Locale.ROOT)}"

        private fun itemJson(item: HandoffItem) = JSONObject()
            .put("id", item.id)
            .put("kind", item.kind.name)
            .put("machineId", item.machineId ?: JSONObject.NULL)
            .put("share", item.share ?: JSONObject.NULL)
            .put("path", item.path)
            .put("name", item.name)
            .put("mime", item.mimeType)
            .put("size", item.size)
            .put("modified", item.modifiedMillis ?: JSONObject.NULL)
            .put("sidecar", item.sidecar)
            .put("folderTag", item.folderTag ?: JSONObject.NULL)
            .put("uri", item.uri)
            .put("createdAt", item.createdAt)
            .put("lastUsedAt", item.lastUsedAt)

        private fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

        private fun itemOf(o: JSONObject) = HandoffItem(
            id = o.getString("id").also { require(StreamTokens.isWellFormed(it)) },
            kind = HandoffKind.valueOf(o.getString("kind")),
            machineId = o.stringOrNull("machineId"),
            share = o.stringOrNull("share"),
            path = o.getString("path"),
            name = o.getString("name"),
            mimeType = o.optString("mime", "application/octet-stream"),
            size = o.optLong("size", 0),
            modifiedMillis = if (o.isNull("modified")) null else o.optLong("modified"),
            sidecar = o.optBoolean("sidecar", false),
            folderTag = o.stringOrNull("folderTag"),
            uri = o.getString("uri"),
            createdAt = o.optLong("createdAt", 0),
            lastUsedAt = o.optLong("lastUsedAt", 0),
        )

        private fun addressJson(a: StreamAddress) = JSONObject().put("host", a.host).put("port", a.port)
        private fun addressOf(o: JSONObject) = StreamAddress(o.getString("host"), o.optInt("port", 445))

        private fun machineJson(m: HandoffMachine) = JSONObject()
            .put("id", m.id)
            .put("serverGuid", m.serverGuid ?: JSONObject.NULL)
            .put("addresses", JSONArray().apply { m.addresses.forEach { put(addressJson(it)) } })
            .put("lastGood", m.lastGood?.let(::addressJson) ?: JSONObject.NULL)

        private fun machineOf(o: JSONObject): HandoffMachine {
            val array = o.getJSONArray("addresses")
            return HandoffMachine(
                id = o.getString("id"),
                serverGuid = o.stringOrNull("serverGuid"),
                addresses = (0 until array.length()).map { addressOf(array.getJSONObject(it)) },
                lastGood = o.optJSONObject("lastGood")?.let(::addressOf),
            )
        }
    }
}
