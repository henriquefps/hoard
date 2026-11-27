package dev.hfps.hoard.server.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Video compression (ADR 0025, hoard-server 0.13.0): the parts of `/v1/compress/…` and `GET /v1/server`'s `compress`
 * object Hoard 2.7.0 uses (library and backup jobs; the phone's own replace is Hoard 2.8.0). Unknown fields are ignored.
 */

/** One target's state (ADR 0025 Amendment (S2)): every target needs the GPU and a backup folder; `nas` the write mount. */
@Serializable
data class CompressTarget(
    val ok: Boolean = false,
    /** `no_gpu`, `no_tools`, `no_backup_root`, `no_write_mount`, `write_mount_mismatch`, `write_mount_read_only`, `unstable_inode`. */
    val reason: String? = null,
)

@Serializable
data class CompressTargets(
    val nas: CompressTarget? = null,
    val phone: CompressTarget? = null,
    val backup: CompressTarget? = null,
)

/** Journals waiting for the write mount, and originals a failed replace left untracked in a trash (Amendment (S1)). */
@Serializable
data class CompressAttention(val stalled: Int = 0, val stalledSince: Long? = null, val untrackedTrash: Int = 0)

@Serializable
data class CompressProfile(val id: String, val codec: String? = null, val q: Int? = null, val default: Boolean = false, val optIn: Boolean = false)

@Serializable
data class CompressLimits(val maxItemsPerJob: Int = 5000, val maxEstimate: Int = 500, val maxBatch: Int = 1000)

/** `GET /v1/server` `compress`. */
@Serializable
data class CompressInfo(
    val available: Boolean = false,
    val reason: String? = null,
    val targets: CompressTargets? = null,
    val attention: CompressAttention? = null,
    val gpu: String? = null,
    val codecs: List<String> = emptyList(),
    val vmaf: Boolean = false,
    val cudaDecode: Boolean = false,
    val sessions: Int = 1,
    val nasWrite: Boolean = false,
    val offerAv1: Boolean = false,
    val trashDays: Int = 30,
    val profiles: List<CompressProfile> = emptyList(),
    val limits: CompressLimits = CompressLimits(),
)

// --- Selections and requests --------------------------------------------------------------------------------------

@Serializable
data class CompressFolderSelect(val folderId: String, val path: String = "", val recursive: Boolean = true)

@Serializable
data class CompressRootSelect(val rootId: String, val path: String = "")

@Serializable
data class CompressFilter(val from: Long? = null, val to: Long? = null, val minBytes: Long? = null)

/** What to compress: [folders] (target nas), [roots] (targets phone and backup), or [itemIds]. Null fields are left out. */
@Serializable
data class CompressSelect(
    val folders: List<CompressFolderSelect>? = null,
    val roots: List<CompressRootSelect>? = null,
    val filter: CompressFilter? = null,
    val itemIds: List<String>? = null,
)

/** `POST /v1/compress/estimate`. */
@Serializable
data class CompressEstimateRequest(
    val target: String,
    val select: CompressSelect,
    val profile: String? = null,
    val cursor: String? = null,
)

/** `POST /v1/compress/jobs`: [auto] with a single folder turns its "Compress automatically" on. */
@Serializable
data class CompressJobCreate(
    val target: String,
    val select: CompressSelect,
    val profile: String,
    val requestId: String,
    val auto: Boolean? = null,
    val name: String? = null,
)

@Serializable
data class CompressEstimateItem(
    val itemId: String,
    val fingerprint: String? = null,
    val name: String? = null,
    val folderId: String? = null,
    val rootId: String? = null,
    val path: String? = null,
    val durationMs: Long? = null,
    val size: Long = 0,
    /** `encode`, `skip`, `pending`. */
    val decision: String,
    val reason: String? = null,
    val estSize: Long? = null,
    val estSaving: Double? = null,
    val estMs: Long? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class CompressTotals(
    val items: Int = 0,
    val encode: Int = 0,
    val skip: Map<String, Int> = emptyMap(),
    val size: Long = 0,
    val estSize: Long = 0,
    val estMs: Long = 0,
    /** hoard-server 0.13.2 (additive; absent = 0): videos of the selection that sit in a sub-folder of a chosen folder. */
    val subfolders: Int = 0,
)

/** An estimate page; [retryAfterSeconds] is the `Retry-After` header (probes still running), not part of the body. */
@Serializable
data class CompressEstimate(
    val items: List<CompressEstimateItem> = emptyList(),
    val totals: CompressTotals = CompressTotals(),
    val pending: Int = 0,
    val next: String? = null,
    /** The same count as `totals.subfolders`, should a server put it beside `pending` instead. */
    val subfolders: Int = 0,
    @kotlinx.serialization.Transient val retryAfterSeconds: Long? = null,
)

// --- Jobs and items -----------------------------------------------------------------------------------------------

@Serializable
data class CompressJobBytes(val original: Long = 0, val output: Long = 0, val saved: Long = 0, val inTrash: Long = 0)

@Serializable
data class CompressCurrent(val id: String? = null, val itemId: String? = null, val state: String? = null, val percent: Int? = null)

@Serializable
data class CompressProgress(val done: Int = 0, val toDo: Int = 0, val etaMs: Long? = null, val current: CompressCurrent? = null)

@Serializable
data class CompressJob(
    val id: String,
    val target: String = "nas",
    val name: String? = null,
    val profile: String? = null,
    val select: JsonElement? = null,
    val auto: Boolean = false,
    val priority: Int = 2,
    /** `active`, `paused`, `cancelled`, `done`. */
    val state: String = "active",
    val paused: Boolean = false,
    val createdAt: Long = 0,
    val finishedAt: Long? = null,
    val createdBy: String? = null,
    /** Items per state. */
    val counts: Map<String, Int> = emptyMap(),
    val skipped: Map<String, Int> = emptyMap(),
    val failed: Map<String, Int> = emptyMap(),
    val bytes: CompressJobBytes = CompressJobBytes(),
    val progress: CompressProgress = CompressProgress(),
    /** `POST` only. */
    val queued: Int? = null,
    val busy: List<String> = emptyList(),
) {
    val finished: Boolean get() = state == STATE_DONE || state == STATE_CANCELLED

    companion object {
        const val STATE_ACTIVE = "active"
        const val STATE_PAUSED = "paused"
        const val STATE_CANCELLED = "cancelled"
        const val STATE_DONE = "done"
    }
}

@Serializable
data class CompressJobsPage(val jobs: List<CompressJob> = emptyList(), val cursor: String? = null, val hasMore: Boolean = false)

/** `PATCH /v1/compress/jobs/{id}`: null fields are left out; `auto` takes only `false`. */
@Serializable
data class CompressJobPatch(val paused: Boolean? = null, val priority: Int? = null, val auto: Boolean? = null)

@Serializable
data class CompressVmaf(val mean: Double? = null, val low: Double? = null, val mode: String? = null)

@Serializable
data class CompressItem(
    /** The compression item (a `compress_items` row), not the gallery item. */
    val id: String,
    val jobId: String = "",
    /** The original's gallery item when queued. */
    val itemId: String = "",
    val fingerprint: String = "",
    val target: String? = null,
    val source: String? = null,
    val folderId: String? = null,
    val path: String? = null,
    val name: String? = null,
    val rootId: String? = null,
    val state: String = "",
    val reason: String = "",
    val size: Long = 0,
    val estSize: Long? = null,
    val outSize: Long? = null,
    val outFingerprint: String? = null,
    val q: Int? = null,
    val vmaf: CompressVmaf? = null,
    val progress: Double? = null,
    val metaSidecar: Boolean = false,
    val trashPath: String? = null,
    val purgeAfter: Long? = null,
    val updatedAt: Long? = null,
    val durationMs: Long? = null,
    val stalledAt: Long? = null,
    // Phone and backup items (Amendment (S2), "Items in the answers"): the phone file, where the phone keeps it now.
    val volume: String? = null,
    val physicalDeviceId: String? = null,
    /** The phone's mtime of the live side's file (Unix ms). */
    val modifiedAt: Long? = null,
    /** The original's SHA-256 (a blob compression). */
    val sha256: String? = null,
    /** The output's SHA-256. */
    val outSha256: String? = null,
    /** While a phone writes (state `replacing`): `restore` (the original back) or `compress` (a restore again). */
    val direction: String? = null,
    /** hoard-server 0.14.0: an item waiting for room (`reason` `no_space`): where, what it needs, what is free now. */
    val space: CompressItemSpace? = null,
)

/** Where a `no_space` item waits (`nas`: the NAS replace; `work`: the encode's work folder on the backup disk). */
@Serializable
data class CompressItemSpace(val where: String = "nas", val needed: Long? = null, val free: Long? = null)

/** `POST /v1/compress/items/{id}/replaced` and `…/restored`: the file the phone wrote at the path. */
@Serializable
data class CompressPhoneReport(val fingerprint: String, val sha256: String, val size: Long, val modifiedAt: Long)

/** `POST /v1/compress/items/{id}/abandon`: `verify`, `changed`, `gone`, `refused`. */
@Serializable
data class CompressAbandon(val reason: String)

@Serializable
data class CompressItemsPage(val items: List<CompressItem> = emptyList(), val cursor: String? = null, val hasMore: Boolean = false)

/** `cancel`, `restore`, `purge`: a job or ids (compression item ids, or gallery item ids). Null fields are left out. */
@Serializable
data class CompressBatch(val jobId: String? = null, val itemIds: List<String>? = null)

/** One id's answer: its [state], or an [error] (`not_found`, `state`, `not_writable`, `path_taken`). */
@Serializable
data class CompressBatchResult(
    val id: String? = null,
    val state: String? = null,
    val error: String? = null,
    val alsoKeptBy: List<String> = emptyList(),
)

@Serializable
data class CompressBatchAnswer(val items: Map<String, CompressBatchResult> = emptyMap())

@Serializable
data class CompressLookupRequest(val fingerprints: List<String>)

@Serializable
data class CompressLookupEntry(
    /** `original` or `output`. */
    val role: String,
    val id: String,
    val itemId: String,
    val state: String,
    val jobId: String = "",
    val profile: String? = null,
    val purgeAfter: Long? = null,
)

@Serializable
data class CompressLookup(val fingerprints: Map<String, CompressLookupEntry> = emptyMap(), val missing: List<String> = emptyList())

/** Targets, profiles and states as the server names them. */
object Compress {
    const val TARGET_NAS = "nas"
    const val TARGET_PHONE = "phone"
    const val TARGET_BACKUP = "backup"

    const val PROFILE_SAME = "hevc-cq28"
    const val PROFILE_SMALLER = "hevc-cq30"
    const val PROFILE_AV1 = "av1-cq34"

    const val DIRECTION_COMPRESS = "compress"
    const val DIRECTION_RESTORE = "restore"

    const val STATE_READY = "ready"
    const val STATE_REPLACING = "replacing"
    const val STATE_REPLACED = "replaced"
    const val STATE_RESTORED = "restored"

    const val ABANDON_VERIFY = "verify"
    const val ABANDON_CHANGED = "changed"
    const val ABANDON_GONE = "gone"
    const val ABANDON_REFUSED = "refused"

    /** States of an item that has not settled: the Gallery's "Compressing" badge. */
    val UNFINISHED = setOf("queued", "estimating", "encoding", "verifying", "ready", "replacing")

    const val MAX_LOOKUP = 1000
}
