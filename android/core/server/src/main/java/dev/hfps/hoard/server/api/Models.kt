package dev.hfps.hoard.server.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * The parts of hoard-server's /v1 (ADR 0013, `server/api/openapi.yaml`) the Gallery uses. Clients ignore unknown
 * fields (ADR 0014: new fields may come), so every model is read with `ignoreUnknownKeys`; optional fields are
 * nullable.
 */

@Serializable
data class PairRequest(
    val token: String,
    val deviceName: String,
    val platform: String = "android",
    val appVersion: String,
    val app: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
)

@Serializable
data class CandidateDevice(
    val id: String,
    val name: String? = null,
    val app: String? = null,
    val platform: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    /** `physicalDevice.devices` only (hoard-server 0.10.0, ADR 0017 "Liveness"): Unix ms, null = never seen. */
    val lastSeenAt: Long? = null,
    /** `physicalDevice.devices` only (hoard-server 0.12.0): what the device declared (`culling`, `freed-adopt`). */
    val features: List<String> = emptyList(),
)

/** ADR 0017: a physical device this installation may be (the server proposes, the user confirms). */
@Serializable
data class Candidate(
    val physicalDeviceId: String,
    val name: String? = null,
    /** `model` (same manufacturer + model) or `other`. */
    val match: String? = null,
    val devices: List<CandidateDevice> = emptyList(),
)

@Serializable
data class PairResponse(
    val deviceId: String,
    val apiKey: String,
    val serverId: String,
    val serverName: String? = null,
    val physicalDeviceId: String? = null,
    val candidates: List<Candidate> = emptyList(),
)

@Serializable
data class ServerLimits(
    val maxPullLimit: Int = 500,
    val maxPushChanges: Int = 500,
    val maxLookup: Int = 1000,
    /** The largest chunk a `PATCH /v1/uploads/{id}` takes (ADR 0013 "Blobs"). */
    val chunkSize: Long? = null,
    val maxBlobSize: Long? = null,
)

/** `GET /v1/server` `backup` (ADR 0024, hoard-server 0.12.0, with the `backup` feature). */
@Serializable
data class BackupLimits(
    val maxRoots: Int = 64,
    val maxReportEntries: Int = 1000,
    val maxVerify: Int = 500,
    val maxPath: Int = 1024,
)

@Serializable
data class PreviewsInfo(
    val version: Int? = null,
    val thumbEdge: Int = 512,
    val screenEdge: Int = 2560,
    val maxWarm: Int = 500,
)

@Serializable
data class ServerInfo(
    val serverId: String,
    val serverName: String? = null,
    val version: String? = null,
    val api: Int = 1,
    val deviceId: String? = null,
    val features: List<String> = emptyList(),
    val limits: ServerLimits = ServerLimits(),
    val previews: PreviewsInfo? = null,
    val backup: BackupLimits? = null,
    /** Video compression (ADR 0025, hoard-server 0.13.0): present with a GPU, the feature `compress` or jobs. */
    val compress: CompressInfo? = null,
)

@Serializable
data class PhysicalDevice(
    val id: String,
    val name: String? = null,
    val devices: List<CandidateDevice> = emptyList(),
    /** The phone's backup roots (ADR 0024, hoard-server 0.12.0). */
    val backup: PhysicalBackup? = null,
)

@Serializable
data class PhysicalBackup(val roots: List<PhysicalBackupRoot> = emptyList())

@Serializable
data class PhysicalBackupRoot(
    val id: String,
    val volume: String,
    val path: String,
    val app: String? = null,
    val active: Boolean = false,
    val since: Long = 0,
    val types: List<String> = emptyList(),
)

@Serializable
data class DeviceView(
    val deviceId: String,
    val name: String? = null,
    val platform: String? = null,
    val app: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val features: List<String> = emptyList(),
    val physicalDevice: PhysicalDevice? = null,
    val candidates: List<Candidate> = emptyList(),
)

@Serializable
data class DevicePatch(
    val app: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val features: List<String>? = null,
)

@Serializable
data class ClaimRequest(val physicalDeviceId: String?)

// --- Items (ADR 0014) -----------------------------------------------------------------------------------------------

@Serializable
data class Capture(val local: String? = null, val offset: String? = null, val utc: Long? = null, val source: String? = null)

@Serializable
data class Gps(val lat: Double? = null, val lon: Double? = null, val alt: Double? = null)

@Serializable
data class FileLocation(
    /** `nas`, `backup`, `mirror` (a phone's backup, ADR 0024). Unknown sources are ignored. */
    val source: String = "",
    val folderId: String? = null,
    val folderName: String? = null,
    /** `nas`: relative to the folder; `mirror`: relative to the phone's volume (`DCIM/Camera/PXL_1.jpg`). */
    val path: String? = null,
    val modifiedAt: Long? = null,
    val sha256: String? = null,
    /** `nas` with `include=trash` (0.11.0, ADR 0023): the file is in a trash, at this folder-relative path. */
    val trash: String? = null,
    /** `mirror` (ADR 0024): the phone it is (or was) on, its name, the backup root and the volume (`primary`, a UUID). */
    val physicalDeviceId: String? = null,
    val deviceName: String? = null,
    val rootId: String? = null,
    val volume: String? = null,
    /** `mirror`: false once the phone no longer has the file (absent = on the phone). */
    val onDevice: Boolean? = null,
    /** `mirror`, off the phone: `freed`, `deleted` or (0.13.0) `compressed` (an unknown value means `deleted`). */
    val gone: String? = null,
    /**
     * A trashed location (0.13.0, ADR 0025 Amendment (S1)): an unfinished compression journal holds this file. Empty,
     * Delete permanently and auto-empty must leave it while held.
     */
    val held: Boolean? = null,
) {
    companion object {
        const val SOURCE_MIRROR = "mirror"
    }
}

@Serializable
data class ItemFile(
    val fingerprint: String,
    /** `raw`, `rendered`, `video`, `sidecar`. */
    val role: String = "rendered",
    /** Absent for an uploaded original (no path, hoard-server 0.9.0): `""`. */
    val name: String = "",
    val format: String? = null,
    val size: Long = 0,
    val locations: List<FileLocation> = emptyList(),
)

@Serializable
data class VideoInfo(
    val durationMs: Long? = null,
    val container: String? = null,
    val codec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rotation: Int? = null,
    val fps: Double? = null,
    val hdr: String? = null,
)

@Serializable
data class MotionInfo(
    /** `file` or `embedded`. */
    val source: String,
    val fingerprint: String? = null,
    val format: String? = null,
    val offset: Long? = null,
    val length: Long? = null,
)

/** ADR 0020: a Reveal edit's preview, the primary first. */
@Serializable
data class ItemEdit(
    val versionId: String,
    val label: String? = null,
    val recipeDigest: String? = null,
    val editedAt: Long? = null,
    val primary: Boolean = false,
    val isVirtualCopy: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
    val updatedAt: Long? = null,
)

@Serializable
data class Item(
    val id: String,
    val rev: Long,
    val deleted: Boolean = false,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val replacedBy: String? = null,
    /** `photo`, `pair`, `video`, `motion`. */
    val kind: String? = null,
    val mainFingerprint: String? = null,
    val sortTime: Long = 0,
    val capture: Capture? = null,
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    val gps: Gps? = null,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int? = null,
    val files: List<ItemFile> = emptyList(),
    val video: VideoInfo? = null,
    val motion: MotionInfo? = null,
    val edits: List<ItemEdit> = emptyList(),
    /** With `include=trash` (0.11.0, ADR 0023): the item is in a trash since [ItemTrash.since]; null = live. */
    val trashed: ItemTrash? = null,
    /** 0.13.0 (ADR 0025): a trashed item a compression replaced (or a restored output): the live item. */
    val compressedTo: String? = null,
    /** 0.13.0 (ADR 0025): on a compression's output. */
    val compressed: ItemCompressed? = null,
)

/** `Item.compressed` (ADR 0025): where an output came from; [original] `trashed` or `purged`. */
@Serializable
data class ItemCompressed(
    val from: String? = null,
    val fromItem: String? = null,
    val profile: String? = null,
    val q: Int? = null,
    val at: Long? = null,
    val original: String? = null,
    val originalSize: Long? = null,
    val metaSidecar: Boolean = false,
) {
    /** The original is still in a trash (the Gallery's "Compressed" badge). */
    val originalInTrash: Boolean get() = original == "trashed"
}

@Serializable
data class ItemTrash(val since: Long = 0)

// --- Culling (ADR 0016) ---------------------------------------------------------------------------------------------

@Serializable
data class CullingField(val value: JsonElement, val rev: Long, val modifiedAt: Long, val deviceId: String? = null)

@Serializable
data class CullingRecord(
    val itemId: String,
    val rev: Long,
    val fingerprints: List<String> = emptyList(),
    val replacedBy: String? = null,
    val rating: CullingField? = null,
    val flag: CullingField? = null,
    val label: CullingField? = null,
)

@Serializable
data class ItemsPage(
    val items: List<Item> = emptyList(),
    val culling: List<CullingRecord> = emptyList(),
    val cursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class CullingChange(
    val itemId: String? = null,
    val fingerprint: String? = null,
    /** `rating`, `flag`, `label`. */
    val field: String,
    val value: JsonElement,
    val baseRev: Long,
    val modifiedAt: Long,
)

@Serializable
data class CullingPush(val changes: List<CullingChange>)

@Serializable
data class CullingResult(
    val index: Int,
    val itemId: String? = null,
    val fingerprint: String? = null,
    val field: String? = null,
    /** `applied`, `unchanged`, `conflict`, `invalid`. */
    val status: String,
    val winner: String? = null,
    val rev: Long? = null,
    val clipped: Boolean = false,
    val code: String? = null,
)

@Serializable
data class CullingPushResponse(val results: List<CullingResult> = emptyList())

@Serializable
data class LookupRequest(val fingerprints: List<String>)

@Serializable
data class LookupMatch(val fingerprint: String, val itemId: String, val role: String? = null)

@Serializable
data class LookupResponse(
    val items: List<Item> = emptyList(),
    val matches: List<LookupMatch> = emptyList(),
    val missing: List<String> = emptyList(),
)

// --- Items by path (ADR 0014 "Items by path", hoard-server 0.10.0; ADR 0022) ----------------------------------------

@Serializable
data class ItemsQueryPage(val items: List<Item> = emptyList(), val cursor: String? = null, val hasMore: Boolean = false)

/** `POST /v1/nas/walk` (ADR 0023, feature `trash`): at most [NasWalkRequest.MAX_FOLDERS] NAS folder ids. */
@Serializable
data class NasWalkRequest(val folders: List<String>) {
    companion object {
        const val MAX_FOLDERS = 100
    }
}

/** `202 {"queued": [...]}`: the folders whose walk was queued (a queued or running walk is not queued twice). */
@Serializable
data class NasWalkResponse(
    val queued: List<String> = emptyList(),
    /** Queued folders whose walk waits for the server's budget, with when it may start (Unix ms). */
    val deferred: Map<String, Long> = emptyMap(),
)

@Serializable
data class ByPathRequest(val folder: String, val paths: List<String>)

@Serializable
data class PathFound(val path: String, val itemId: String, val role: String? = null, val size: Long = 0, val modifiedAt: Long? = null)

@Serializable
data class ByPathResponse(val found: List<PathFound> = emptyList(), val missing: List<String> = emptyList())

// --- Previews (ADR 0015) --------------------------------------------------------------------------------------------

@Serializable
data class WarmRequest(val items: List<String>, val previews: List<String> = listOf("thumb"), val edit: String? = null)

@Serializable
data class PreviewState(val status: String, val v: String? = null, val reason: String? = null)

@Serializable
data class WarmItem(val id: String, val thumb: PreviewState? = null, val screen: PreviewState? = null, val proxy: PreviewState? = null)

@Serializable
data class WarmResponse(val items: List<WarmItem> = emptyList(), val missing: List<String> = emptyList())

@Serializable
data class ErrorBody(val error: ErrorInfo? = null)

@Serializable
data class ErrorInfo(val code: String, val message: String? = null, val details: JsonElement? = null)

// --- Phone backup (ADR 0024, hoard-server 0.12.0) -------------------------------------------------------------------

@Serializable
data class BackupRootStats(
    val files: Int = 0,
    val bytes: Long = 0,
    val stored: Int = 0,
    val nas: Int = 0,
    val waiting: Int = 0,
    val removedFromDevice: Int = 0,
    val freed: Int = 0,
    val verified: Int = 0,
)

@Serializable
data class BackupRootDetails(val absorbed: List<String> = emptyList())

/** A root (`GET`/`POST /v1/backup/roots`): a folder of a phone the server mirrors. */
@Serializable
data class BackupRoot(
    val id: String,
    val physicalDeviceId: String? = null,
    val deviceName: String? = null,
    val mine: Boolean = false,
    val folder: String? = null,
    /** `PRIMARY`/`primary` or the volume UUID, upper-case. */
    val volume: String,
    val path: String,
    val createdBy: String? = null,
    val app: String? = null,
    val createdAt: Long = 0,
    val types: List<String> = emptyList(),
    val paused: Boolean = false,
    val stopped: Boolean = false,
    val active: Boolean = false,
    val lastReportAt: Long? = null,
    val stats: BackupRootStats = BackupRootStats(),
    /** `POST` only: the roots inside the new one whose entries moved to it. */
    val details: BackupRootDetails? = null,
)

@Serializable
data class BackupRoots(val roots: List<BackupRoot> = emptyList())

@Serializable
data class BackupRootCreate(val volume: String, val path: String, val types: List<String>? = null)

@Serializable
data class BackupRootPatch(val paused: Boolean? = null, val types: List<String>? = null)

@Serializable
data class BackupReportEntry(val path: String, val fingerprint: String, val size: Long, val modifiedAt: Long, val sha256: String? = null)

/** [reason]: `deleted`, `moved`, `freed`. */
@Serializable
data class BackupRemoval(val path: String, val reason: String)

@Serializable
data class BackupReport(val rootId: String, val entries: List<BackupReportEntry> = emptyList(), val removed: List<BackupRemoval> = emptyList())

/**
 * One entry's answer. [state]: `stored`, `nas`, `missing`, `in_progress`, `differs`, or `refused` with a [reason]
 * (`unsupported_type`, `invalid_path`, `outside_root`, `reserved_prefix`; the mirror-formats amendment of 0.12.0).
 * [verified]: `yes`, `pending`, `no`.
 */
@Serializable
data class BackupReportResult(
    val index: Int,
    val path: String = "",
    val state: String = "",
    val itemId: String? = null,
    val verified: String = "no",
    val retryAfter: Long? = null,
    val reason: String? = null,
)

/** [state]: `kept`, `moved`, `unknown`. */
@Serializable
data class BackupRemovalResult(val index: Int, val path: String = "", val state: String = "")

@Serializable
data class BackupReportResponse(val results: List<BackupReportResult> = emptyList(), val removed: List<BackupRemovalResult> = emptyList())

@Serializable
data class BackupVerifyEntry(val rootId: String, val path: String, val sha256: String, val size: Long)

/**
 * [maxAge] (ms, hoard-server 0.12.1): a server re-hash older than this is not reused; the server queues a new one and
 * answers `pending`. Older servers ignore it (the client then also checks `verifiedAt` against the server's clock).
 */
@Serializable
data class BackupVerifyRequest(val entries: List<BackupVerifyEntry>, val maxAge: Long? = null)

/** A response with the server's clock (its `Date` header, Unix ms; null when absent). */
data class Dated<T>(val value: T, val serverDate: Long?)

/**
 * One verify answer (ADR 0024 "`POST /v1/backup/verify`"): [verified] `yes` (with the server's [verifiedAt]),
 * `pending` (with [retryAfter]) or `no` with a [reason] (`differs`, `missing`, `changed_on_nas`, `unavailable`,
 * `not_found`); a refused entry (Amendment S1) is `no` with [state] `refused` and its reason. [namedByRecord]: a live
 * Reveal photo or version record names the file's content (the free-up gate).
 */
@Serializable
data class BackupVerifyResult(
    val index: Int,
    val verified: String = "no",
    val verifiedAt: Long? = null,
    val retryAfter: Long? = null,
    val reason: String? = null,
    val namedByRecord: Boolean = false,
    val state: String? = null,
    val detail: String? = null,
)

@Serializable
data class BackupVerifyResponse(val results: List<BackupVerifyResult> = emptyList())

/** `POST /v1/uploads` (ADR 0013 "Blobs"; [wait] and [claim] ADR 0024). Null fields are left out. */
@Serializable
data class UploadCreate(
    val sha256: String,
    val size: Long,
    val kind: String = "original",
    val fingerprint: String? = null,
    val wait: Boolean? = null,
    val claim: Boolean? = null,
)

/** [status]: `exists`, `created`, `resume`, `in_progress` (with [retryAfter]). */
@Serializable
data class UploadState(
    val status: String,
    val retryAfter: Long? = null,
    val uploadId: String? = null,
    val offset: Long = 0,
    val chunkSize: Long? = null,
    val expiresAt: Long? = null,
)

/** A chunk's answer: the server's offset, and whether the upload completed (`Upload-Complete: 1`). */
data class ChunkResult(val offset: Long, val complete: Boolean)

object Features {
    const val ITEMS = "items"
    const val PREVIEWS = "previews"
    const val CULLING = "culling"
    const val DEVICE_IDENTITY = "device-identity"
    const val EDITS = "edits"
    /** `POST /v1/items/by-path` (hoard-server 0.10.0): Hoard maps SMB folders to NAS folders (ADR 0022). */
    const val ITEMS_BY_PATH = "items-by-path"
    /**
     * hoard-server 0.11.0 (ADR 0023): `.hoard-trash` is walked as a trashed state, `include=trash`, and
     * `POST /v1/nas/walk`. Without it Hoard still trashes, but warns that the server will forget Reveal's edits.
     */
    const val TRASH = "trash"
    /** hoard-server 0.12.0 (ADR 0024): the phone backup (roots, report, verify, `wait`/`claim` on uploads). */
    const val BACKUP = "backup"
    /**
     * hoard-server 0.13.0 (ADR 0025): video compression, a usable GPU and at least one usable target. Hoard 2.7.0 offers
     * library jobs only when `compress.targets.nas.ok` (backup jobs when `targets.backup.ok`).
     */
    const val COMPRESS = "compress"
    /**
     * hoard-server 0.15.0 (ADR 0023 Amendment (0.15.0)): the trash for paired devices (`/v1/trash`: move with a dry run,
     * list, restore, delete, empty by group). With it Hoard trashes, restores and empties server photos only through the
     * server, never over SMB (Amendment (2.10.0)).
     */
    const val TRASH_ACTIONS = "trash-actions"
}

/** The `include` of the items feed Hoard asks for: one cursor for items and culling (ADR 0016 rule 3). */
const val INCLUDE_ITEMS_CULLING = "items,culling"

/** The same with trashed items served whole (feature `trash`, ADR 0023). */
const val INCLUDE_ITEMS_CULLING_TRASH = "items,culling,trash"

