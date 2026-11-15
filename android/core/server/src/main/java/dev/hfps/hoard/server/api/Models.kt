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
)

@Serializable
data class PhysicalDevice(val id: String, val name: String? = null, val devices: List<CandidateDevice> = emptyList())

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
    /** `nas`, `backup` (later: `device`). */
    val source: String = "",
    val folderId: String? = null,
    val folderName: String? = null,
    val path: String? = null,
    val modifiedAt: Long? = null,
    val sha256: String? = null,
)

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
)

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

object Features {
    const val ITEMS = "items"
    const val PREVIEWS = "previews"
    const val CULLING = "culling"
    const val DEVICE_IDENTITY = "device-identity"
    const val EDITS = "edits"
    /** `POST /v1/items/by-path` (hoard-server 0.10.0): Hoard maps SMB folders to NAS folders (ADR 0022). */
    const val ITEMS_BY_PATH = "items-by-path"
}

/** The `include` of the items feed Hoard asks for: one cursor for items and culling (ADR 0016 rule 3). */
const val INCLUDE_ITEMS_CULLING = "items,culling"

