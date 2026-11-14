package dev.hfps.hoard.smb.transfer

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.SmbConnector
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpFlag
import dev.hfps.hoard.xmp.XmpReader
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A host/port pair of an SMB endpoint (`addresses`, in priority order). */
data class TransferAddress(val host: String, val port: Int)

/** Contract transfer states (jobs and items). */
object TransferStates {
    const val QUEUED = "queued"
    const val SCANNING = "scanning"
    const val CONFLICT = "conflict"
    const val RUNNING = "running"
    const val RETRYING = "retrying"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val SKIPPED = "skipped"

    val TERMINAL = setOf(COMPLETED, FAILED, CANCELLED, SKIPPED)

    fun isTerminal(state: String) = state in TERMINAL
}

/** Contract `ConflictPolicy` plus `ask`. */
object Conflicts {
    const val RENAME = "rename"
    const val OVERWRITE = "overwrite"
    const val SKIP = "skip"
    const val ASK = "ask"

    val POLICIES = setOf(RENAME, OVERWRITE, SKIP)
    val ALL = POLICIES + ASK
}

/**
 * Contract `TransferLocation` / `TransferSourceLocation` (ADR 0007): where a job reads or writes.
 * SMB endpoints carry what native needs to open its own connection; never a session id.
 */
sealed class Endpoint {
    data class Smb(
        val machineId: String,
        val addresses: List<TransferAddress>,
        val expectedServerGuid: String,
        val share: String,
    ) : Endpoint()

    data object Device : Endpoint()
    data class Volume(val uuid: String) : Endpoint()

    /** A SAF folder from `pickDestinationFolder`. */
    data class Tree(val uri: String) : Endpoint()

    /** Files shared to Hoard by another app; source only, paths are item indexes. */
    data class Incoming(val shareId: String) : Endpoint()

    /**
     * Identity for "same place": a move between two paths of the same key is a rename, and a copy
     * inside the same SMB share may be done by the server.
     */
    val key: String
        get() = when (this) {
            is Smb -> "smb:$machineId:${share.lowercase(Locale.ROOT)}"
            Device -> "device"
            is Volume -> "volume:${uuid.uppercase(Locale.ROOT)}"
            is Tree -> "tree:$uri"
            is Incoming -> "incoming:$shareId"
        }

    val isNetwork: Boolean get() = this is Smb

    fun toJson(): JSONObject = when (this) {
        is Smb -> JSONObject().put("kind", "smb").put("machineId", machineId).put("share", share)
            .put("expectedServerGuid", expectedServerGuid)
            .put("addresses", JSONArray().apply { addresses.forEach { put(JSONObject().put("host", it.host).put("port", it.port)) } })
        Device -> JSONObject().put("kind", "device")
        is Volume -> JSONObject().put("kind", "volume").put("uuid", uuid)
        is Tree -> JSONObject().put("kind", "tree").put("uri", uri)
        is Incoming -> JSONObject().put("kind", "incoming").put("shareId", shareId)
    }

    companion object {
        /**
         * Parses a contract location. [validateAddress] checks hosts (the plugin passes `Net`'s rule);
         * rejects `UNKNOWN` for anything malformed.
         */
        fun parse(json: JSONObject?, validateAddress: (String?, Int?) -> Pair<String, Int> = { h, p -> (h ?: "") to (p ?: 445) }): Endpoint {
            json ?: smbFail(ErrorCode.UNKNOWN, "location is required")
            return when (val kind = json.optString("kind")) {
                "smb" -> {
                    val array = json.optJSONArray("addresses") ?: JSONArray()
                    val addresses = (0 until array.length()).map { i ->
                        val a = array.getJSONObject(i)
                        val (host, port) = validateAddress(a.optString("host"), if (a.has("port")) a.optInt("port") else null)
                        TransferAddress(host, port)
                    }
                    if (addresses.isEmpty()) smbFail(ErrorCode.UNKNOWN, "location.addresses is required")
                    Smb(
                        machineId = json.optString("machineId").takeIf { it.isNotEmpty() } ?: smbFail(ErrorCode.UNKNOWN, "location.machineId is required"),
                        addresses = addresses,
                        expectedServerGuid = SmbConnector.normalizeGuid(json.optString("expectedServerGuid").takeIf { it != "null" })
                            ?: smbFail(ErrorCode.UNKNOWN, "location.expectedServerGuid is required for transfers"),
                        share = json.optString("share").takeIf { it.isNotEmpty() } ?: smbFail(ErrorCode.UNKNOWN, "location.share is required"),
                    )
                }
                "device" -> Device
                "volume" -> Volume(dev.hfps.hoard.smb.location.Location.requireUuid(json.optString("uuid")))
                "tree" -> Tree(json.optString("uri").takeIf { it.startsWith("content://") } ?: smbFail(ErrorCode.UNKNOWN, "location.uri must be a content:// tree"))
                "incoming" -> Incoming(json.optString("shareId").takeIf { it.isNotEmpty() } ?: smbFail(ErrorCode.UNKNOWN, "location.shareId is required"))
                else -> smbFail(ErrorCode.UNKNOWN, "Unknown location kind: '$kind'")
            }
        }
    }
}

data class TransferSource(val endpoint: Endpoint, val path: String) {
    fun toJson(): JSONObject = JSONObject().put("location", endpoint.toJson()).put("path", path)

    companion object {
        fun fromJson(json: JSONObject) = TransferSource(Endpoint.parse(json.getJSONObject("location")), json.optString("path"))
    }
}

/** How an item is carried out. */
object ItemKinds {
    /** Copy bytes (then, for a move, delete the source once verified). */
    const val FILE = "file"

    /** Create the folder at `destPath` (merged when it exists). */
    const val FOLDER = "folder"

    /** Same-location move: one rename of `sourcePath` to `destPath` (a whole folder at once). */
    const val RENAME = "rename"
}

/** One unit of a job. Mutated by the engine thread only, under the job's lock. */
class TransferItem(
    val index: Int,
    /** Which of the job's sources it comes from. */
    val source: Int,
    val sourcePath: String,
    var destPath: String,
    val kind: String,
    var size: Long,
    /** Source modified time seen at scan (a change between attempts restarts the file). */
    var modifiedMillis: Long?,
    var state: String = TransferStates.QUEUED,
    /** Bytes known to be in the destination's partial file (a lower bound; persisted). */
    var bytesDone: Long = 0,
    /** `ask` while waiting for an answer, then the answer; null: the job's policy. */
    var conflict: String? = null,
    var errorCode: ErrorCode? = null,
    var errorMessage: String? = null,
    /** A rename item that could not rename and was expanded into copies instead. */
    var superseded: Boolean = false,
    /** Write straight to `destPath` (no partial name): 1.3 downloads migrated mid-way. */
    var direct: Boolean = false,
    /** Display name (incoming files: the name the other app gave). */
    val name: String = SmbPaths.name(destPath),
) {
    val isDirectory: Boolean get() = kind == ItemKinds.FOLDER || (kind == ItemKinds.RENAME && size < 0)

    /** Files and renames count as items for the user; folders to create do not. */
    val counts: Boolean get() = kind != ItemKinds.FOLDER && !superseded

    val isFinished: Boolean get() = TransferStates.isTerminal(state)

    fun fail(error: HoardSmbException) {
        state = TransferStates.FAILED
        errorCode = error.code
        errorMessage = error.message
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("i", index)
        put("s", source)
        put("sp", sourcePath)
        put("dp", destPath)
        put("k", kind)
        put("z", size)
        putOpt("m", modifiedMillis)
        put("st", state)
        put("b", bytesDone)
        putOpt("c", conflict)
        putOpt("ec", errorCode?.name)
        putOpt("em", errorMessage)
        if (superseded) put("x", true)
        if (direct) put("d", true)
        if (name != SmbPaths.name(destPath)) put("n", name)
    }

    companion object {
        fun fromJson(json: JSONObject): TransferItem {
            val destPath = json.getString("dp")
            return TransferItem(
                index = json.getInt("i"),
                source = json.optInt("s"),
                sourcePath = json.getString("sp"),
                destPath = destPath,
                kind = json.optString("k", ItemKinds.FILE),
                size = json.optLong("z"),
                modifiedMillis = if (json.has("m")) json.optLong("m") else null,
                state = json.optString("st", TransferStates.QUEUED),
                bytesDone = json.optLong("b"),
                conflict = json.optString("c").takeIf { json.has("c") },
                errorCode = json.optString("ec").takeIf { json.has("ec") }?.let(ErrorCode::fromName),
                errorMessage = json.optString("em").takeIf { json.has("em") },
                superseded = json.optBoolean("x"),
                direct = json.optBoolean("d"),
                name = json.optString("n").takeIf { json.has("n") } ?: SmbPaths.name(destPath),
            )
        }
    }
}

/** Contract counts of a job, computed from its items. */
data class ItemCounts(val total: Int, val done: Int, val failed: Int, val skipped: Int, val conflicts: Int)

/**
 * A native job: the contract's `NativeTransfer` plus its sources, destination and items. The
 * engine mutates it on the worker thread; the plugin reads snapshots. Every access to [items]
 * holds the job's monitor.
 */
class TransferJob(
    val transferId: String,
    val op: String,
    val sources: List<TransferSource>,
    val destination: Endpoint,
    val destPath: String,
    val conflict: String,
    val startedAt: String,
    /** Retry of a job native had lost: an existing destination file of the same size counts as copied. */
    val resumeExisting: Boolean = false,
    /**
     * XMP sidecars to write next to copied files (ADR 0011 "Copy picks"): source path → the culling fields,
     * merged into the destination's sidecar of that shot (or a new `<destination stem>.xmp`) once the file is copied.
     */
    val sidecars: Map<String, XmpFields> = emptyMap(),
    /** New sidecars are hidden (`.<stem>.xmp`). */
    val hiddenSidecars: Boolean = false,
) {
    @Volatile var state: String = TransferStates.QUEUED
    @Volatile var errorCode: ErrorCode? = null
    @Volatile var errorMessage: String? = null
    @Volatile var activeHost: String? = null
    @Volatile var currentName: String? = null
    @Volatile var finishedAt: String? = null
    /** The scan finished and its items are final (a restart does not scan again). */
    @Volatile var scanned: Boolean = false
    /** The free-space check passed for the current run. */
    @Volatile var spaceChecked: Boolean = false
    /** The UI has this job in its database (`acknowledgeTransfers`): not reported, kept for Retry. */
    @Volatile var acknowledged: Boolean = false
    /** Bytes of the item being copied right now (beyond the finished items). */
    @Volatile var currentBytes: Long = 0
    val items: MutableList<TransferItem> = ArrayList()

    val isTerminal: Boolean get() = TransferStates.isTerminal(state)
    val isMove: Boolean get() = op == "move"

    fun counts(): ItemCounts = synchronized(this) {
        var total = 0; var done = 0; var failed = 0; var skipped = 0; var conflicts = 0
        for (item in items) {
            if (!item.counts) continue
            total++
            when (item.state) {
                TransferStates.COMPLETED -> done++
                TransferStates.FAILED, TransferStates.CANCELLED -> failed++
                TransferStates.SKIPPED -> skipped++
            }
            if (item.conflict == Conflicts.ASK && !item.isFinished) conflicts++
        }
        ItemCounts(total, done, failed, skipped, conflicts)
    }

    /** Bytes of every file to write (renames move no bytes). */
    fun totalBytes(): Long = synchronized(this) { items.sumOf { if (it.kind == ItemKinds.FILE && !it.superseded && it.state != TransferStates.SKIPPED) it.size.coerceAtLeast(0) else 0L } }

    fun bytesDone(): Long = synchronized(this) {
        items.sumOf {
            if (it.kind != ItemKinds.FILE || it.superseded || it.state == TransferStates.SKIPPED) 0L
            else if (it.state == TransferStates.COMPLETED) it.size.coerceAtLeast(0)
            else if (it.state == TransferStates.RUNNING || it.state == TransferStates.RETRYING) it.bytesDone
            else 0L
        }
    }

    /** Display name for notifications: the single source's name, else the first one's. */
    fun displayName(): String = synchronized(this) {
        items.firstOrNull { it.counts }?.name?.takeIf { counts().total == 1 }
    } ?: sources.firstOrNull()?.let { SmbPaths.name(it.path).ifEmpty { it.path } } ?: "Hoard"

    fun toJson(): JSONObject = synchronized(this) {
        JSONObject().apply {
            put("version", 2)
            put("transferId", transferId)
            put("op", op)
            put("sources", JSONArray().apply { sources.forEach { put(it.toJson()) } })
            put("destination", destination.toJson())
            put("destPath", destPath)
            put("conflict", conflict)
            put("startedAt", startedAt)
            put("resumeExisting", resumeExisting)
            if (sidecars.isNotEmpty()) put("sidecars", JSONObject().apply { sidecars.forEach { (k, v) -> put(k, SidecarJson.toJson(v)) } })
            if (hiddenSidecars) put("hiddenSidecars", true)
            put("state", state)
            putOpt("errorCode", errorCode?.name)
            putOpt("errorMessage", errorMessage)
            putOpt("activeHost", activeHost)
            putOpt("finishedAt", finishedAt)
            put("scanned", scanned)
            put("acknowledged", acknowledged)
            put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TransferJob {
            if (!json.has("version")) return fromLegacy(json)
            val sources = json.getJSONArray("sources")
            val job = TransferJob(
                transferId = json.getString("transferId"),
                op = json.optString("op", "copy"),
                sources = (0 until sources.length()).map { TransferSource.fromJson(sources.getJSONObject(it)) },
                destination = Endpoint.parse(json.getJSONObject("destination")),
                destPath = json.optString("destPath"),
                conflict = json.optString("conflict", Conflicts.RENAME),
                startedAt = json.getString("startedAt"),
                resumeExisting = json.optBoolean("resumeExisting"),
                sidecars = json.optJSONObject("sidecars")?.let { o ->
                    o.keys().asSequence().mapNotNull { key -> SidecarJson.fromJson(o.opt(key))?.let { key to it } }.toMap()
                }.orEmpty(),
                hiddenSidecars = json.optBoolean("hiddenSidecars"),
            )
            job.state = json.optString("state", TransferStates.QUEUED)
            job.errorCode = json.optString("errorCode").takeIf { json.has("errorCode") }?.let(ErrorCode::fromName)
            job.errorMessage = json.optString("errorMessage").takeIf { json.has("errorMessage") }
            job.activeHost = json.optString("activeHost").takeIf { json.has("activeHost") }
            job.finishedAt = json.optString("finishedAt").takeIf { json.has("finishedAt") }
            job.scanned = json.optBoolean("scanned")
            job.acknowledged = json.optBoolean("acknowledged")
            val items = json.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) job.items += TransferItem.fromJson(items.getJSONObject(i))
            return job
        }

        /**
         * A 1.3 download job (`hoard_transfers.json`): SMB file → SAF tree, written straight into
         * `destUri` under its final name. It becomes a one-item job whose item writes to that name
         * (no partial name: the bytes already there are the resume point).
         */
        fun fromLegacy(json: JSONObject): TransferJob {
            val addresses = json.optJSONArray("addresses") ?: JSONArray()
            val smb = Endpoint.Smb(
                machineId = json.getString("machineId"),
                addresses = (0 until addresses.length()).map {
                    val a = addresses.getJSONObject(it)
                    TransferAddress(a.getString("host"), a.optInt("port", 445))
                },
                expectedServerGuid = json.optString("expectedServerGuid"),
                share = json.getString("share"),
            )
            val remotePath = json.getString("remotePath")
            val destName = json.getString("destName")
            val job = TransferJob(
                transferId = json.getString("transferId"),
                op = "copy",
                sources = listOf(TransferSource(smb, remotePath)),
                destination = Endpoint.Tree(json.getString("destTreeUri")),
                destPath = "",
                conflict = Conflicts.OVERWRITE,
                startedAt = json.getString("startedAt"),
            )
            job.state = json.optString("state", TransferStates.QUEUED)
            job.errorCode = json.optString("errorCode").takeIf { json.has("errorCode") }?.let(ErrorCode::fromName)
            job.errorMessage = json.optString("errorMessage").takeIf { json.has("errorMessage") }
            job.activeHost = json.optString("activeHost").takeIf { json.has("activeHost") }
            job.finishedAt = json.optString("finishedAt").takeIf { json.has("finishedAt") }
            job.scanned = true
            job.items += TransferItem(
                index = 0, source = 0, sourcePath = remotePath, destPath = destName, kind = ItemKinds.FILE,
                size = json.optLong("totalBytes"),
                modifiedMillis = if (json.has("remoteModifiedMillis")) json.optLong("remoteModifiedMillis") else null,
                state = if (job.isTerminal) job.state else TransferStates.QUEUED,
                bytesDone = json.optLong("bytesDone"),
                conflict = Conflicts.OVERWRITE,
            ).also { it.direct = true }
            return job
        }
    }
}

/**
 * A job's sidecar fields in its saved JSON: `{ "rating": 3, "flag": "pick" }`. Jobs saved by Hoard before 1.9.0 hold
 * the whole XMP document as a string: it is read back into fields (a Hoard sidecar without a pick label is a pick).
 */
object SidecarJson {
    fun toJson(fields: XmpFields): JSONObject = JSONObject().put("rating", fields.rating).put("flag", fields.flag.wire)

    fun fromJson(value: Any?): XmpFields? = when (value) {
        is JSONObject -> fields(value.optInt("rating", 0), value.optString("flag"))
        is String -> XmpReader.readOrNull(value.toByteArray(Charsets.UTF_8))?.fields?.copy(label = null)
        else -> null
    }

    /** Contract values (`rating` 0..5, `flag` pick/reject/none); null when they are out of range. */
    fun fields(rating: Int, flag: String?): XmpFields? {
        if (rating !in 0..XmpFields.MAX_RATING) return null
        val parsed = XmpFlag.parse(flag) ?: return null
        return XmpFields(rating, parsed)
    }
}
