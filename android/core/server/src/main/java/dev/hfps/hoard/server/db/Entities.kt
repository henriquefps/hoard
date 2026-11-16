package dev.hfps.hoard.server.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * `gallery.db` (ADR 0021): the Gallery's own database, apart from Hoard 1's schema in :core:data (`hoard.db`, which
 * stays table for table the Capacitor v7 schema). Most of it is a cache of the paired server (items, culling records)
 * that "Clear cache" or unpairing may drop; the pending culling queue and the device index are not a cache.
 */

/** The pairing (one row, id 1): where the server is, who this installation is there, how far the feed was read. */
@Entity(tableName = "server_account")
data class ServerAccountEntity(
    @PrimaryKey val id: Int = 1,
    val url: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "server_name") val serverName: String?,
    @ColumnInfo(name = "server_version") val serverVersion: String?,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "physical_device_id") val physicalDeviceId: String?,
    /** `hoard` or `hoard-debug` (ADR 0017: the app id with its build variant). */
    val app: String,
    /** The server's `features`, comma-separated. */
    val features: String,
    /** The items feed cursor (opaque, ADR 0013); null = pull from the start. */
    val cursor: String?,
    /** The first full pull finished (until then device items are also checked with `POST /v1/items/lookup`). */
    @ColumnInfo(name = "caught_up", defaultValue = "0") val caughtUp: Boolean = false,
    @ColumnInfo(name = "paired_at") val pairedAt: Long,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "last_error_at") val lastErrorAt: Long? = null,
    /** ADR 0017 candidates to offer once after pairing (JSON), null when there is nothing to ask. */
    @ColumnInfo(name = "claim_candidates") val claimCandidates: String? = null,
    /** `culling` declared with `PATCH /v1/device` (ADR 0016 rule 6). */
    @ColumnInfo(name = "culling_declared", defaultValue = "0") val cullingDeclared: Boolean = false,
    /** Every phone's backup roots (`GET /v1/backup/roots`, JSON) as of the last sync (ADR 0024 A2: Gallery › Devices). */
    @ColumnInfo(name = "backup_roots") val backupRoots: String? = null,
    /**
     * `server_item_mirrors` holds the `mirror` locations of every cached item (gallery.db 5 fills it from the items'
     * JSON once after the upgrade; a new pairing starts filled).
     */
    @ColumnInfo(name = "mirrors_indexed", defaultValue = "0") val mirrorsIndexed: Boolean = true,
) {
    fun has(feature: String): Boolean = feature in features.split(',')
}

/** A live server item (ADR 0014), flattened for the grid and its filters; [json] keeps the whole item. */
@Entity(
    tableName = "server_items",
    indices = [
        Index(value = ["sort_time"], name = "idx_server_items_sort"),
        Index(value = ["kind"], name = "idx_server_items_kind"),
    ],
)
data class ServerItemEntity(
    @PrimaryKey val id: String,
    val rev: Long,
    /** `photo`, `pair`, `video`, `motion`. */
    val kind: String,
    @ColumnInfo(name = "main_fingerprint") val mainFingerprint: String,
    @ColumnInfo(name = "sort_time") val sortTime: Long,
    /** The camera's wall clock (`capture.local`), for date headers in the shot's own time zone. */
    @ColumnInfo(name = "capture_local") val captureLocal: String?,
    val make: String?,
    val model: String?,
    val lens: String?,
    val width: Int?,
    val height: Int?,
    val orientation: Int?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    /** The main file's name (`DSC01000.ARW`). */
    val name: String,
    /** The first location's folder: `<NAS folder name>/<directory>` (`photos/2024/Trip`), or `""` for an upload. */
    val folder: String,
    /** Lower-case names of every member file and the folder, for search. */
    val search: String,
    @ColumnInfo(name = "has_raw") val hasRaw: Boolean,
    /** The primary edit's version id (ADR 0020), null when none. */
    @ColumnInfo(name = "edit_primary") val editPrimary: String?,
    @ColumnInfo(name = "edit_count") val editCount: Int,
    @ColumnInfo(name = "edited_at") val editedAt: Long?,
    val json: String,
    /** In a trash since (Unix ms, ADR 0023: `include=trash`); null = live. Trashed items leave the grid. */
    @ColumnInfo(name = "trashed_since") val trashedSince: Long? = null,
)

/** Which item holds which file (any member, ADR 0014): the "backed up" join of device items. */
@Entity(
    tableName = "server_item_files",
    primaryKeys = ["fingerprint", "item_id"],
    indices = [Index(value = ["item_id"], name = "idx_server_item_files_item")],
)
data class ServerItemFileEntity(
    val fingerprint: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    val role: String,
)

/**
 * A `mirror` location of a cached item's file (ADR 0024 A2): where a phone has (or had) that file, for Gallery ›
 * Devices, the "Where: <phone> backup" filter, the "Deleted from the phone" badge and the merge of this phone's own
 * files with their backed-up items. [path] is relative to the phone's [volume] (`primary` or a volume UUID).
 */
@Entity(
    tableName = "server_item_mirrors",
    primaryKeys = ["item_id", "fingerprint", "root_id", "path"],
    indices = [
        Index(value = ["root_id"], name = "idx_server_item_mirrors_root"),
        Index(value = ["physical_id"], name = "idx_server_item_mirrors_physical"),
    ],
)
data class ServerItemMirrorEntity(
    @ColumnInfo(name = "item_id") val itemId: String,
    val fingerprint: String,
    @ColumnInfo(name = "physical_id") val physicalId: String,
    @ColumnInfo(name = "device_name") val deviceName: String,
    @ColumnInfo(name = "root_id") val rootId: String,
    val volume: String,
    val path: String,
    val size: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long?,
    @ColumnInfo(name = "on_device") val onDevice: Boolean,
    /** `freed` or `deleted` when off the phone. */
    val gone: String?,
)

/**
 * The server's culling record of an item (ADR 0016), per field: value, rev, modifiedAt, deviceId. A null value = the
 * field was never written. [flag] is `pick` / `reject` / `none`.
 */
@Entity(tableName = "culling")
data class CullingEntity(
    @PrimaryKey @ColumnInfo(name = "item_id") val itemId: String,
    val rev: Long,
    @ColumnInfo(name = "replaced_by") val replacedBy: String?,
    val rating: Int?,
    @ColumnInfo(name = "rating_rev") val ratingRev: Long?,
    @ColumnInfo(name = "rating_at") val ratingAt: Long?,
    @ColumnInfo(name = "rating_device") val ratingDevice: String?,
    val flag: String?,
    @ColumnInfo(name = "flag_rev") val flagRev: Long?,
    @ColumnInfo(name = "flag_at") val flagAt: Long?,
    @ColumnInfo(name = "flag_device") val flagDevice: String?,
    val label: String?,
    @ColumnInfo(name = "label_rev") val labelRev: Long?,
    @ColumnInfo(name = "label_at") val labelAt: Long?,
    @ColumnInfo(name = "label_device") val labelDevice: String?,
)

/** A culling record's member fingerprints ("Mapping a record": any member matches). */
@Entity(
    tableName = "culling_fingerprints",
    primaryKeys = ["fingerprint", "item_id"],
    indices = [Index(value = ["item_id"], name = "idx_culling_fingerprints_item")],
)
data class CullingFingerprintEntity(
    val fingerprint: String,
    @ColumnInfo(name = "item_id") val itemId: String,
)

/**
 * A local culling change not yet acknowledged by the server (ADR 0016 rule 2): one per item and field, the last
 * decision wins, with the time it was made. [value] is JSON (`4`, `"pick"`, `"Red"`).
 */
@Entity(tableName = "culling_pending", primaryKeys = ["item_id", "field"])
data class PendingCullingEntity(
    @ColumnInfo(name = "item_id") val itemId: String,
    val field: String,
    val value: String,
    @ColumnInfo(name = "base_rev") val baseRev: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
)

/**
 * One photo or video of the phone (MediaStore). [shotKey] groups files into shots as the server does (folder +
 * directory + case-insensitive stem, ADR 0014). [fingerprint] is computed lazily in the background; it is valid while
 * [size] and [modified] are those it was computed for ([fpSize], [fpModified]).
 */
@Entity(
    tableName = "device_media",
    indices = [
        Index(value = ["shot_key"], name = "idx_device_media_shot"),
        Index(value = ["fingerprint"], name = "idx_device_media_fingerprint"),
    ],
)
data class DeviceMediaEntity(
    @PrimaryKey val id: Long,
    /** Absolute path (`/storage/emulated/0/DCIM/Camera/PXL_1.jpg`). */
    val path: String,
    /** Hoard's location of the file (`device`, `vol-<uuid>`, ADR 0005) and its folder inside it. */
    @ColumnInfo(name = "location_id") val locationId: String,
    val dir: String,
    val name: String,
    @ColumnInfo(name = "shot_key") val shotKey: String,
    val mime: String?,
    val size: Long,
    /** MediaStore DATE_MODIFIED in ms. */
    val modified: Long,
    /** MediaStore DATE_TAKEN (ms, UTC), else [modified]. */
    val taken: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val orientation: Int?,
    val video: Boolean,
    val fingerprint: String? = null,
    @ColumnInfo(name = "fp_size") val fpSize: Long? = null,
    @ColumnInfo(name = "fp_modified") val fpModified: Long? = null,
    /** A Google / Samsung motion photo (MP4 inside the still), checked with the fingerprint. */
    @ColumnInfo(defaultValue = "0") val motion: Boolean = false,
    /**
     * The file's SHA-256 (ADR 0024 "Identity"), valid for [shaSize] and [shaModified]: made in the fingerprint's own
     * read for files of at most 2 MiB, else by the backup just before an upload.
     */
    val sha256: String? = null,
    @ColumnInfo(name = "sha_size") val shaSize: Long? = null,
    @ColumnInfo(name = "sha_modified") val shaModified: Long? = null,
)

/** Folders the owner added to the device index beyond the defaults (DCIM, Pictures, Movies, WhatsApp). */
@Entity(tableName = "device_folders")
data class DeviceFolderEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** Device shots whose local decisions were already handed to the server queue once they reached it (rule 2). */
@Entity(tableName = "device_pushed")
data class DevicePushedEntity(
    @PrimaryKey @ColumnInfo(name = "shot_key") val shotKey: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "pushed_at") val pushedAt: Long,
)

/**
 * A learned SMB ↔ NAS mapping (ADR 0022): `smb_prefix` of `share` on `machine_id` is `server_prefix` of NAS folder
 * `folder_id` of server `server_id`. Learned from two fingerprinted files; forgotten on a mismatch, another server or
 * another SMB server GUID, or from Settings.
 */
@Entity(tableName = "smb_mappings", primaryKeys = ["machine_id", "share", "smb_prefix"])
data class SmbMappingEntity(
    @ColumnInfo(name = "machine_id") val machineId: String,
    /** Lower-case share name (SMB share names are case-insensitive). */
    val share: String,
    @ColumnInfo(name = "smb_prefix") val smbPrefix: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "folder_id") val folderId: String,
    @ColumnInfo(name = "folder_name") val folderName: String?,
    @ColumnInfo(name = "server_prefix") val serverPrefix: String,
    @ColumnInfo(name = "smb_guid") val smbGuid: String?,
    @ColumnInfo(name = "learned_at") val learnedAt: Long,
    @ColumnInfo(name = "verified_at") val verifiedAt: Long,
)

/**
 * A folder where learning found nothing (its photos are not on the server, or the two samples disagreed): not tried
 * again before `checked_at` + a day, so opening it does not read two files every time (ADR 0022).
 */
@Entity(tableName = "smb_mapping_misses", primaryKeys = ["machine_id", "share", "path"])
data class SmbMappingMissEntity(
    @ColumnInfo(name = "machine_id") val machineId: String,
    val share: String,
    /** Lower-case folder path in the share. */
    val path: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "checked_at") val checkedAt: Long,
)

/**
 * A phone folder backed up to the server (ADR 0024 "The app"): the server's root id, the folder as the server names it
 * ([volume] `primary` or the volume UUID, [path] relative to it, `""` = the whole volume) and as Hoard does
 * ([locationId] `device` / `vol-<uuid>`, [folder] inside it), the [types] declared to the server (comma-separated).
 */
@Entity(tableName = "backup_roots")
data class BackupRootEntity(
    @PrimaryKey val id: String,
    val volume: String,
    val path: String,
    @ColumnInfo(name = "location_id") val locationId: String,
    val folder: String,
    val types: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(defaultValue = "0") val paused: Boolean = false,
    /** The last pass that listed, reported and uploaded everything it could (Unix ms). */
    @ColumnInfo(name = "last_pass_at") val lastPassAt: Long? = null,
    /** The server's numbers of the root (`stats`, JSON) as of [lastPassAt]. */
    @ColumnInfo(name = "server_stats") val serverStats: String? = null,
)

/**
 * One file of a backed-up folder (ADR 0024): where it is ([path] relative to its volume, as reported; [absolute] on
 * the phone; [mediaId] its `device_media` row, null for a RAW or sidecar found by listing), what it is ([kind]
 * `image` / `video` / `raw` / `sidecar`; [size], [modified], [taken], its [fingerprint] and [sha256], each valid for the
 * size and mtime it was made for), what the server said ([serverState], [itemId], [verifiedAt]), and the upload
 * ([uploadId], [uploadOffset] saved after every chunk). [state] is what Settings and the Gallery show; [error] the
 * reason of `failed`, `paused` (`wifi`, `charging`) or `refused` (the server's reason).
 */
@Entity(
    tableName = "backup_files",
    primaryKeys = ["root_id", "path"],
    indices = [
        Index(value = ["absolute"], name = "idx_backup_files_absolute"),
        Index(value = ["state"], name = "idx_backup_files_state"),
    ],
)
data class BackupFileEntity(
    @ColumnInfo(name = "root_id") val rootId: String,
    val path: String,
    val absolute: String,
    @ColumnInfo(name = "media_id") val mediaId: Long?,
    val kind: String,
    val size: Long,
    val modified: Long,
    val taken: Long,
    val fingerprint: String? = null,
    @ColumnInfo(name = "fp_size") val fpSize: Long? = null,
    @ColumnInfo(name = "fp_modified") val fpModified: Long? = null,
    val sha256: String? = null,
    @ColumnInfo(name = "sha_size") val shaSize: Long? = null,
    @ColumnInfo(name = "sha_modified") val shaModified: Long? = null,
    val state: String,
    /** `fingerprint|size|modified` as last reported (null = never): reported again when it differs. */
    val reported: String? = null,
    @ColumnInfo(name = "server_state") val serverState: String? = null,
    @ColumnInfo(name = "item_id") val itemId: String? = null,
    @ColumnInfo(name = "verified_at") val verifiedAt: Long? = null,
    @ColumnInfo(name = "upload_id") val uploadId: String? = null,
    @ColumnInfo(name = "upload_offset") val uploadOffset: Long = 0,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long? = null,
    val error: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
)

/** A file free up deleted from the phone after the server verified it (ADR 0024 "Free up space", phase A3). */
@Entity(tableName = "freed")
data class FreedEntity(
    @PrimaryKey val path: String,
    @ColumnInfo(name = "item_id") val itemId: String?,
    val fingerprint: String,
    val bytes: Long,
    @ColumnInfo(name = "freed_at") val freedAt: Long,
)
