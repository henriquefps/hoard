package dev.hfps.hoard.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * The Capacitor app's schema (src/db/migrations.ts, version 7; docs/adr/0003-storage.md) as Room entities: same
 * tables, column names, types, nullability, defaults, keys, indexes and cascades, so the importer copies rows column
 * for column and every id the native side already knows (machine ids for credentials, transfer ids, culling keys)
 * keeps meaning the same thing. Room cannot declare CHECK constraints: the allowed values are listed on each column
 * and enforced by the code that writes them (ADR 0019).
 */

/** `strftime('%Y-%m-%dT%H:%M:%fZ', 'now')`: the ISO-8601 UTC default of every `*_at` text column. */
internal const val NOW = "(strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))"

/** A saved SMB machine (ADR 0002). Its password is in the Android Keystore under [id], never here. */
@Entity(tableName = "machines")
data class MachineEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Phosphor icon id (kebab-case), null for the default. */
    val icon: String?,
    /** Palette colour id, null for the default. */
    val color: String?,
    @ColumnInfo(defaultValue = "''") val username: String,
    val domain: String?,
    /** SMB server GUID pinned on the first successful connect (ADR 0002). */
    @ColumnInfo(name = "server_guid") val serverGuid: String?,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int,
    @ColumnInfo(name = "created_at", defaultValue = NOW) val createdAt: String,
)

/** One way to reach a machine. `kind`: `local`, `tailscale` or `other`. */
@Entity(
    tableName = "machine_addresses",
    foreignKeys = [
        ForeignKey(
            entity = MachineEntity::class,
            parentColumns = ["id"],
            childColumns = ["machine_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["machine_id", "sort_order"], name = "idx_machine_addresses_machine")],
)
data class MachineAddressEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "machine_id") val machineId: String,
    val host: String,
    @ColumnInfo(defaultValue = "445") val port: Int,
    val kind: String,
    val label: String?,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int,
)

/**
 * A pinned folder in any location (ADR 0005): `location_id` is the machine id (SMB, then `machine_id` is the same
 * id), `device` or `vol-<uuid>` (then `machine_id` is null and `share` is '').
 */
@Entity(
    tableName = "favorites",
    foreignKeys = [
        ForeignKey(
            entity = MachineEntity::class,
            parentColumns = ["id"],
            childColumns = ["machine_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["location_id", "share", "path"], unique = true, name = "idx_favorites_location_path"),
        Index(value = ["machine_id"], name = "idx_favorites_machine"),
    ],
)
data class FavoriteEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "location_id") val locationId: String,
    @ColumnInfo(name = "machine_id") val machineId: String?,
    @ColumnInfo(defaultValue = "''") val share: String,
    @ColumnInfo(defaultValue = "''") val path: String,
    val label: String,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int,
    @ColumnInfo(name = "created_at", defaultValue = NOW) val createdAt: String,
)

/** A share opened by name that the server does not list. Unique per machine, case-insensitively. */
@Entity(
    tableName = "manual_shares",
    primaryKeys = ["machine_id", "name"],
    foreignKeys = [
        ForeignKey(
            entity = MachineEntity::class,
            parentColumns = ["id"],
            childColumns = ["machine_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ManualShareEntity(
    @ColumnInfo(name = "machine_id") val machineId: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(name = "created_at", defaultValue = NOW) val createdAt: String,
)

/**
 * The record of a native transfer job (ADR 0007), same id as the job. `op`: `copy` / `move`; `conflict`: `ask`,
 * `rename`, `overwrite`, `skip`; `state`: `queued`, `scanning`, `conflict`, `running`, `retrying`, `completed`,
 * `failed`, `cancelled`, `skipped`. `source_paths` is a JSON array.
 */
@Entity(
    tableName = "transfers",
    foreignKeys = [
        ForeignKey(
            entity = MachineEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_machine_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MachineEntity::class,
            parentColumns = ["id"],
            childColumns = ["dest_machine_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["created_at"], name = "idx_transfers_created"),
        Index(value = ["source_machine_id"], name = "idx_transfers_source_machine"),
        Index(value = ["dest_machine_id"], name = "idx_transfers_dest_machine"),
    ],
)
data class TransferEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(defaultValue = "'copy'") val op: String,
    @ColumnInfo(name = "source_location_id") val sourceLocationId: String,
    @ColumnInfo(name = "source_machine_id") val sourceMachineId: String?,
    @ColumnInfo(name = "source_share", defaultValue = "''") val sourceShare: String,
    @ColumnInfo(name = "source_paths", defaultValue = "'[]'") val sourcePaths: String,
    @ColumnInfo(name = "dest_location_id") val destLocationId: String,
    @ColumnInfo(name = "dest_machine_id") val destMachineId: String?,
    @ColumnInfo(name = "dest_share", defaultValue = "''") val destShare: String,
    @ColumnInfo(name = "dest_path", defaultValue = "''") val destPath: String,
    @ColumnInfo(name = "dest_tree_uri") val destTreeUri: String?,
    val name: String,
    @ColumnInfo(defaultValue = "'rename'") val conflict: String,
    @ColumnInfo(defaultValue = "0") val size: Long,
    @ColumnInfo(name = "bytes_done", defaultValue = "0") val bytesDone: Long,
    @ColumnInfo(name = "items_total", defaultValue = "0") val itemsTotal: Int,
    @ColumnInfo(name = "items_done", defaultValue = "0") val itemsDone: Int,
    @ColumnInfo(name = "items_failed", defaultValue = "0") val itemsFailed: Int,
    @ColumnInfo(name = "items_skipped", defaultValue = "0") val itemsSkipped: Int,
    @ColumnInfo(defaultValue = "'queued'") val state: String,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    val error: String?,
    @ColumnInfo(name = "created_at", defaultValue = NOW) val createdAt: String,
    @ColumnInfo(name = "finished_at") val finishedAt: String?,
)

/** Key / JSON value settings (ADR 0003 lists the keys; the native app adds `themeMode` and `palette`). */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** A culled folder (ADR 0008), keyed like a favorite. */
@Entity(tableName = "cull_sessions", primaryKeys = ["location_id", "share", "path"])
data class CullSessionEntity(
    @ColumnInfo(name = "location_id") val locationId: String,
    @ColumnInfo(defaultValue = "''") val share: String,
    @ColumnInfo(defaultValue = "''") val path: String,
    @ColumnInfo(name = "started_at", defaultValue = NOW) val startedAt: String,
    @ColumnInfo(name = "last_visited_at", defaultValue = NOW) val lastVisitedAt: String,
    @ColumnInfo(name = "last_shot_id") val lastShotId: String?,
    @ColumnInfo(name = "shot_count", defaultValue = "0") val shotCount: Int,
)

/** A picked, rejected or rated shot (lowercase stem). `flag`: `pick`, `reject` or null; `rating` 0..5. */
@Entity(tableName = "cull_decisions", primaryKeys = ["location_id", "share", "path", "shot_id"])
data class CullDecisionEntity(
    @ColumnInfo(name = "location_id") val locationId: String,
    @ColumnInfo(defaultValue = "''") val share: String,
    @ColumnInfo(defaultValue = "''") val path: String,
    @ColumnInfo(name = "shot_id") val shotId: String,
    val flag: String?,
    @ColumnInfo(defaultValue = "0") val rating: Int,
    @ColumnInfo(name = "updated_at", defaultValue = NOW) val updatedAt: String,
)

/** The last "Copy picks" destination per source location; `options` is JSON. */
@Entity(tableName = "cull_destinations", primaryKeys = ["source_location_id", "source_share"])
data class CullDestinationEntity(
    @ColumnInfo(name = "source_location_id") val sourceLocationId: String,
    @ColumnInfo(name = "source_share", defaultValue = "''") val sourceShare: String,
    @ColumnInfo(name = "location_id") val locationId: String,
    @ColumnInfo(defaultValue = "''") val share: String,
    @ColumnInfo(defaultValue = "''") val path: String,
    val label: String?,
    @ColumnInfo(defaultValue = "'{}'") val options: String,
    @ColumnInfo(name = "updated_at", defaultValue = NOW) val updatedAt: String,
)

/** Cached assisted-culling measurements of a file (ADR 0009); `analysis` is JSON. */
@Entity(
    tableName = "cull_analysis",
    primaryKeys = ["location_id", "share", "path"],
    indices = [Index(value = ["location_id", "share", "folder"], name = "idx_cull_analysis_folder")],
)
data class CullAnalysisEntity(
    @ColumnInfo(name = "location_id") val locationId: String,
    @ColumnInfo(defaultValue = "''") val share: String,
    val path: String,
    @ColumnInfo(defaultValue = "''") val folder: String,
    val size: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: String?,
    val version: Int,
    val analysis: String,
    @ColumnInfo(name = "analysed_at", defaultValue = NOW) val analysedAt: String,
)

/** What the XMP import rule needs about a shot's sidecar (ADR 0011). Times are epoch ms; `synced` is 0 or 1. */
@Entity(tableName = "cull_sidecars", primaryKeys = ["location_id", "share", "path", "shot_id"])
data class CullSidecarEntity(
    @ColumnInfo(name = "location_id") val locationId: String,
    @ColumnInfo(defaultValue = "''") val share: String,
    @ColumnInfo(defaultValue = "''") val path: String,
    @ColumnInfo(name = "shot_id") val shotId: String,
    @ColumnInfo(name = "decided_at") val decidedAt: Long?,
    @ColumnInfo(name = "sidecar_mtime") val sidecarMtime: Long?,
    @ColumnInfo(defaultValue = "0") val synced: Int,
)

/**
 * Native only: the outcome of importing a Capacitor database (one row per [source]). Written in the same transaction
 * as the imported rows, so it exists exactly when the import committed.
 */
@Entity(tableName = "legacy_imports")
data class LegacyImportEntity(
    @PrimaryKey val source: String,
    /** `imported`, or `none` when there was no Capacitor database to import. */
    val status: String,
    @ColumnInfo(name = "importer_version") val importerVersion: Int,
    /** The Capacitor database's `user_version` before the importer upgraded its copy. */
    @ColumnInfo(name = "legacy_version") val legacyVersion: Int?,
    /** Rows copied per table, as JSON (`{"machines":3,...}`). */
    @ColumnInfo(name = "row_counts", defaultValue = "'{}'") val rowCounts: String,
    @ColumnInfo(name = "imported_at", defaultValue = NOW) val importedAt: String,
)
