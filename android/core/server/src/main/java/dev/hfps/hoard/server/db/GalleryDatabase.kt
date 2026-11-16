package dev.hfps.hoard.server.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * `gallery.db` (ADR 0021). Room migrations checked against `core/server/schemas/`: version 2 (Hoard 2.2.0, ADR 0022)
 * adds `smb_mappings` and `smb_mapping_misses`; version 3 (Hoard 2.3.0, ADR 0023) adds `server_items.trashed_since`;
 * version 4 (Hoard 2.4.0, ADR 0024) adds the phone backup: `backup_roots`, `backup_files`, `freed` and
 * `device_media.sha256` / `sha_size` / `sha_modified`; version 5 (Hoard 2.5.0, ADR 0024 A2) adds other phones'
 * backups: `server_item_mirrors`, `server_account.backup_roots` and `mirrors_indexed`.
 */
@Database(
    entities = [
        ServerAccountEntity::class,
        ServerItemEntity::class,
        ServerItemFileEntity::class,
        CullingEntity::class,
        CullingFingerprintEntity::class,
        PendingCullingEntity::class,
        DeviceMediaEntity::class,
        DeviceFolderEntity::class,
        DevicePushedEntity::class,
        SmbMappingEntity::class,
        SmbMappingMissEntity::class,
        BackupRootEntity::class,
        BackupFileEntity::class,
        FreedEntity::class,
        ServerItemMirrorEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun account(): AccountDao
    abstract fun items(): ItemDao
    abstract fun culling(): CullingDao
    abstract fun device(): DeviceDao
    abstract fun mappings(): MappingDao
    abstract fun backup(): BackupDao

    /**
     * Emits whenever the culling records or the queue change (no rows read): a screen that needs only a few items
     * re-reads those, instead of observing the whole tables.
     */
    fun cullingChanges(): kotlinx.coroutines.flow.Flow<Set<String>> = invalidationTracker.createFlow("culling", "culling_pending")

    companion object {
        const val FILE_NAME = "gallery.db"

        /** 2.2.0 (ADR 0022): the SMB ↔ NAS folder mappings and the folders where learning found nothing. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `smb_mappings` (`machine_id` TEXT NOT NULL, `share` TEXT NOT NULL, `smb_prefix` TEXT NOT NULL, " +
                        "`server_id` TEXT NOT NULL, `folder_id` TEXT NOT NULL, `folder_name` TEXT, `server_prefix` TEXT NOT NULL, `smb_guid` TEXT, " +
                        "`learned_at` INTEGER NOT NULL, `verified_at` INTEGER NOT NULL, PRIMARY KEY(`machine_id`, `share`, `smb_prefix`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `smb_mapping_misses` (`machine_id` TEXT NOT NULL, `share` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                        "`server_id` TEXT NOT NULL, `checked_at` INTEGER NOT NULL, PRIMARY KEY(`machine_id`, `share`, `path`))",
                )
            }
        }

        /** 2.3.0 (ADR 0023): trashed server items stay as rows (`include=trash`), hidden from the grid. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `server_items` ADD COLUMN `trashed_since` INTEGER")
            }
        }

        /** 2.4.0 (ADR 0024): the phone backup's roots and files, `freed` (used from 2.6.0), and the SHA-256 of phone files. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `device_media` ADD COLUMN `sha256` TEXT")
                db.execSQL("ALTER TABLE `device_media` ADD COLUMN `sha_size` INTEGER")
                db.execSQL("ALTER TABLE `device_media` ADD COLUMN `sha_modified` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `backup_roots` (`id` TEXT NOT NULL, `volume` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                        "`location_id` TEXT NOT NULL, `folder` TEXT NOT NULL, `types` TEXT NOT NULL, `added_at` INTEGER NOT NULL, " +
                        "`paused` INTEGER NOT NULL DEFAULT 0, `last_pass_at` INTEGER, `server_stats` TEXT, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `backup_files` (`root_id` TEXT NOT NULL, `path` TEXT NOT NULL, `absolute` TEXT NOT NULL, " +
                        "`media_id` INTEGER, `kind` TEXT NOT NULL, `size` INTEGER NOT NULL, `modified` INTEGER NOT NULL, `taken` INTEGER NOT NULL, " +
                        "`fingerprint` TEXT, `fp_size` INTEGER, `fp_modified` INTEGER, `sha256` TEXT, `sha_size` INTEGER, `sha_modified` INTEGER, " +
                        "`state` TEXT NOT NULL, `reported` TEXT, `server_state` TEXT, `item_id` TEXT, `verified_at` INTEGER, `upload_id` TEXT, " +
                        "`upload_offset` INTEGER NOT NULL, `attempts` INTEGER NOT NULL DEFAULT 0, `next_attempt_at` INTEGER, `error` TEXT, " +
                        "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`root_id`, `path`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_backup_files_absolute` ON `backup_files` (`absolute`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_backup_files_state` ON `backup_files` (`state`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `freed` (`path` TEXT NOT NULL, `item_id` TEXT, `fingerprint` TEXT NOT NULL, " +
                        "`bytes` INTEGER NOT NULL, `freed_at` INTEGER NOT NULL, PRIMARY KEY(`path`))",
                )
            }
        }

        /**
         * 2.5.0 (ADR 0024 A2): the items' `mirror` locations and every phone's backup roots. Cached items are indexed
         * from their JSON once ([ItemDao.mirrorJson], `mirrors_indexed`), not pulled again.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `server_item_mirrors` (`item_id` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, " +
                        "`physical_id` TEXT NOT NULL, `device_name` TEXT NOT NULL, `root_id` TEXT NOT NULL, `volume` TEXT NOT NULL, " +
                        "`path` TEXT NOT NULL, `size` INTEGER NOT NULL, `modified_at` INTEGER, `on_device` INTEGER NOT NULL, `gone` TEXT, " +
                        "PRIMARY KEY(`item_id`, `fingerprint`, `root_id`, `path`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_server_item_mirrors_root` ON `server_item_mirrors` (`root_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `idx_server_item_mirrors_physical` ON `server_item_mirrors` (`physical_id`)")
                db.execSQL("ALTER TABLE `server_account` ADD COLUMN `backup_roots` TEXT")
                db.execSQL("ALTER TABLE `server_account` ADD COLUMN `mirrors_indexed` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun open(context: Context, name: String? = FILE_NAME): GalleryDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context.applicationContext, GalleryDatabase::class.java)
            } else {
                Room.databaseBuilder(context.applicationContext, GalleryDatabase::class.java, name)
            }
            return builder.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
        }
    }
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM server_account WHERE id = 1")
    suspend fun get(): ServerAccountEntity?

    @Query("SELECT * FROM server_account WHERE id = 1")
    fun observe(): Flow<ServerAccountEntity?>

    @Upsert
    suspend fun put(account: ServerAccountEntity)

    @Query("UPDATE server_account SET cursor = :cursor, caught_up = :caughtUp WHERE id = 1")
    suspend fun setCursor(cursor: String?, caughtUp: Boolean)

    @Query("UPDATE server_account SET last_sync_at = :at, last_error = NULL, last_error_at = NULL WHERE id = 1")
    suspend fun synced(at: Long)

    @Query("UPDATE server_account SET last_error = :error, last_error_at = :at WHERE id = 1")
    suspend fun failed(error: String, at: Long)

    @Query("UPDATE server_account SET claim_candidates = :json WHERE id = 1")
    suspend fun setClaimCandidates(json: String?)

    @Query("UPDATE server_account SET physical_device_id = :id WHERE id = 1")
    suspend fun setPhysicalDevice(id: String?)

    @Query("UPDATE server_account SET features = :features, server_name = :name, server_version = :version WHERE id = 1")
    suspend fun setServerInfo(features: String, name: String?, version: String?)

    @Query("UPDATE server_account SET backup_roots = :json WHERE id = 1")
    suspend fun setBackupRoots(json: String?)

    @Query("UPDATE server_account SET mirrors_indexed = :done WHERE id = 1")
    suspend fun setMirrorsIndexed(done: Boolean)

    @Query("UPDATE server_account SET culling_declared = 1 WHERE id = 1")
    suspend fun cullingDeclared()

    @Query("DELETE FROM server_account")
    suspend fun clear()
}

data class ItemJsonRow(val id: String, val json: String)

/** What the grid needs of a server item (no JSON). */
data class ServerItemRow(
    val id: String,
    val kind: String,
    @ColumnInfo(name = "main_fingerprint") val mainFingerprint: String,
    @ColumnInfo(name = "sort_time") val sortTime: Long,
    @ColumnInfo(name = "capture_local") val captureLocal: String?,
    val make: String?,
    val model: String?,
    val lens: String?,
    val width: Int?,
    val height: Int?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val name: String,
    val folder: String,
    val search: String,
    @ColumnInfo(name = "has_raw") val hasRaw: Boolean,
    @ColumnInfo(name = "edit_primary") val editPrimary: String?,
    @ColumnInfo(name = "edit_count") val editCount: Int,
)

@Dao
interface ItemDao {
    @Query("SELECT id, kind, main_fingerprint, sort_time, capture_local, make, model, lens, width, height, duration_ms, name, folder, search, has_raw, edit_primary, edit_count FROM server_items WHERE trashed_since IS NULL")
    fun observeRows(): Flow<List<ServerItemRow>>

    /** Items in a trash (ADR 0023), newest trash first: the Trash screen's server photos. */
    @Query("SELECT * FROM server_items WHERE trashed_since IS NOT NULL ORDER BY trashed_since DESC")
    fun observeTrashed(): Flow<List<ServerItemEntity>>

    @Query("SELECT * FROM server_items WHERE trashed_since IS NOT NULL")
    suspend fun trashed(): List<ServerItemEntity>

    /** Item ids newest first, a page at a time (the offline thumbnails job). */
    @Query("SELECT id FROM server_items WHERE trashed_since IS NULL ORDER BY sort_time DESC LIMIT :limit OFFSET :offset")
    suspend fun idsNewestFirst(limit: Int, offset: Int): List<String>

    @Query("SELECT json FROM server_items WHERE id = :id")
    suspend fun json(id: String): String?

    @Query("SELECT * FROM server_items WHERE id = :id")
    suspend fun get(id: String): ServerItemEntity?

    @Query("SELECT COUNT(*) FROM server_items WHERE trashed_since IS NULL")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM server_items WHERE trashed_since IS NULL")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun putItems(items: List<ServerItemEntity>)

    @Upsert
    suspend fun putFiles(files: List<ServerItemFileEntity>)

    @Query("DELETE FROM server_item_files WHERE item_id IN (:ids)")
    suspend fun deleteFiles(ids: List<String>)

    @Query("DELETE FROM server_items WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<String>)

    @Upsert
    suspend fun putMirrors(mirrors: List<ServerItemMirrorEntity>)

    @Query("DELETE FROM server_item_mirrors WHERE item_id IN (:ids)")
    suspend fun deleteMirrors(ids: List<String>)

    /** Every `mirror` location of a live item (ADR 0024 A2). */
    @Query("SELECT m.* FROM server_item_mirrors m JOIN server_items i ON i.id = m.item_id WHERE i.trashed_since IS NULL")
    fun observeMirrors(): Flow<List<ServerItemMirrorEntity>>

    /** Ids and JSON of live items that name a mirror location, a page at a time (gallery.db 5's one-off index). */
    @Query("SELECT id, json FROM server_items WHERE trashed_since IS NULL AND json LIKE '%\"mirror\"%' ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun mirrorJson(limit: Int, offset: Int): List<ItemJsonRow>

    @Query("DELETE FROM server_item_mirrors")
    suspend fun clearMirrors()

    /** Replaces items (their member files and mirror locations) and drops tombstones, in one transaction. */
    @Transaction
    suspend fun apply(live: List<ServerItemEntity>, files: List<ServerItemFileEntity>, gone: List<String>, mirrors: List<ServerItemMirrorEntity> = emptyList()) {
        val touched = live.map { it.id } + gone
        for (chunk in touched.chunked(500)) {
            deleteFiles(chunk)
            deleteMirrors(chunk)
        }
        for (chunk in gone.chunked(500)) deleteItems(chunk)
        if (live.isNotEmpty()) putItems(live)
        if (files.isNotEmpty()) putFiles(files)
        if (mirrors.isNotEmpty()) putMirrors(mirrors)
    }

    /** Fingerprint → item for the given files (each file once; any member). */
    @Query("SELECT * FROM server_item_files WHERE fingerprint IN (:fingerprints)")
    suspend fun filesFor(fingerprints: List<String>): List<ServerItemFileEntity>

    @Query("SELECT fingerprint, item_id, role FROM server_item_files")
    fun observeFiles(): Flow<List<ServerItemFileEntity>>

    @Query("DELETE FROM server_items")
    suspend fun clearItems()

    @Query("DELETE FROM server_item_files")
    suspend fun clearFiles()
}

@Dao
interface CullingDao {
    @Query("SELECT * FROM culling")
    fun observeAll(): Flow<List<CullingEntity>>

    @Query("SELECT * FROM culling WHERE item_id = :itemId")
    suspend fun get(itemId: String): CullingEntity?

    @Query("SELECT * FROM culling WHERE item_id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<CullingEntity>

    @Upsert
    suspend fun put(records: List<CullingEntity>)

    @Query("DELETE FROM culling_fingerprints WHERE item_id IN (:ids)")
    suspend fun deleteFingerprints(ids: List<String>)

    @Upsert
    suspend fun putFingerprints(rows: List<CullingFingerprintEntity>)

    @Query("DELETE FROM culling WHERE item_id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Transaction
    suspend fun apply(records: List<CullingEntity>, fingerprints: List<CullingFingerprintEntity>, gone: List<String>) {
        val touched = records.map { it.itemId } + gone
        for (chunk in touched.chunked(500)) deleteFingerprints(chunk)
        for (chunk in gone.chunked(500)) delete(chunk)
        if (records.isNotEmpty()) put(records)
        if (fingerprints.isNotEmpty()) putFingerprints(fingerprints)
    }

    /** Records replaced by another item (ADR 0014 `replacedBy`): old id → new id. */
    @Query("SELECT item_id, replaced_by FROM culling WHERE replaced_by IS NOT NULL")
    suspend fun replaced(): List<ReplacedRow>

    @Query("SELECT item_id FROM culling_fingerprints WHERE fingerprint IN (:fingerprints)")
    suspend fun recordsWith(fingerprints: List<String>): List<String>

    // --- The queue (rule 2) -----------------------------------------------------------------------------------------

    @Query("SELECT * FROM culling_pending")
    fun observePending(): Flow<List<PendingCullingEntity>>

    @Query("SELECT * FROM culling_pending ORDER BY modified_at LIMIT :limit")
    suspend fun pending(limit: Int): List<PendingCullingEntity>

    /** The whole queue, changes never refused first (a push pass sends each change at most once). */
    @Query("SELECT * FROM culling_pending ORDER BY attempts, modified_at")
    suspend fun pendingAll(): List<PendingCullingEntity>

    @Query("SELECT * FROM culling_pending WHERE item_id = :itemId")
    suspend fun pendingFor(itemId: String): List<PendingCullingEntity>

    /** The queued changes of several items in one query (callers chunk [ids] by 500). */
    @Query("SELECT * FROM culling_pending WHERE item_id IN (:ids)")
    suspend fun pendingForItems(ids: List<String>): List<PendingCullingEntity>

    @Query("SELECT COUNT(*) FROM culling_pending")
    fun observePendingCount(): Flow<Int>

    @Upsert
    suspend fun putPending(rows: List<PendingCullingEntity>)

    /** Drops a queued change once answered, unless a newer decision replaced it meanwhile. */
    @Query("DELETE FROM culling_pending WHERE item_id = :itemId AND field = :field AND modified_at = :modifiedAt AND value = :value")
    suspend fun acknowledge(itemId: String, field: String, modifiedAt: Long, value: String)

    @Query("UPDATE culling_pending SET attempts = attempts + 1 WHERE item_id = :itemId AND field = :field")
    suspend fun attempted(itemId: String, field: String)

    @Query("DELETE FROM culling_pending WHERE item_id = :itemId")
    suspend fun deletePendingFor(itemId: String)

    /**
     * A replaced item's queued changes move to its replacement (rule "Follow replacedBy"), per field: when the
     * replacement already has a change of that field queued, the newer of the two (by `modifiedAt`) stays, as the
     * server's conflict rule would keep it. A moved change gets `baseRev` 0 (it never saw the replacement's field) and
     * starts its attempts again.
     */
    @Transaction
    suspend fun movePending(from: String, to: String) {
        if (from == to) return
        val moving = pendingFor(from)
        if (moving.isEmpty()) return
        val existing = pendingFor(to).associateBy { it.field }
        deletePendingFor(from)
        val keep = moving.mapNotNull { m ->
            val there = existing[m.field]
            if (there != null && there.modifiedAt >= m.modifiedAt) null else m.copy(itemId = to, baseRev = there?.baseRev ?: 0, attempts = 0)
        }
        if (keep.isNotEmpty()) putPending(keep)
    }

    @Query("DELETE FROM culling")
    suspend fun clearRecords()

    @Query("DELETE FROM culling_fingerprints")
    suspend fun clearFingerprints()

    @Query("DELETE FROM culling_pending")
    suspend fun clearPending()
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM device_media")
    fun observeAll(): Flow<List<DeviceMediaEntity>>

    @Query("SELECT * FROM device_media")
    suspend fun all(): List<DeviceMediaEntity>

    @Query("SELECT * FROM device_media WHERE id = :id")
    suspend fun get(id: Long): DeviceMediaEntity?

    @Query("SELECT * FROM device_media WHERE shot_key = :shotKey")
    suspend fun shot(shotKey: String): List<DeviceMediaEntity>

    @Upsert
    suspend fun put(rows: List<DeviceMediaEntity>)

    @Query("DELETE FROM device_media WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    /** Files whose fingerprint is missing or stale, newest first (what the user sees first is matched first). */
    @Query("SELECT * FROM device_media WHERE fingerprint IS NULL OR fp_size != size OR fp_modified != modified ORDER BY taken DESC LIMIT :limit")
    suspend fun needingFingerprint(limit: Int): List<DeviceMediaEntity>

    @Query("SELECT COUNT(*) FROM device_media WHERE fingerprint IS NULL OR fp_size != size OR fp_modified != modified")
    fun observeUnfingerprinted(): Flow<Int>

    @Query("UPDATE device_media SET fingerprint = :fingerprint, fp_size = :size, fp_modified = :modified, motion = :motion WHERE id = :id")
    suspend fun setFingerprint(id: Long, fingerprint: String, size: Long, modified: Long, motion: Boolean)

    /** The fingerprint and, for a file of at most 2 MiB, its SHA-256 from the same read (ADR 0024 "Identity"). */
    @Query(
        "UPDATE device_media SET fingerprint = :fingerprint, fp_size = :size, fp_modified = :modified, motion = :motion, " +
            "sha256 = :sha256, sha_size = CASE WHEN :sha256 IS NULL THEN NULL ELSE :size END, " +
            "sha_modified = CASE WHEN :sha256 IS NULL THEN NULL ELSE :modified END WHERE id = :id",
    )
    suspend fun setIdentity(id: Long, fingerprint: String, size: Long, modified: Long, motion: Boolean, sha256: String?)

    /** A SHA-256 the backup made just before an upload (ADR 0024), for the size and mtime it was made for. */
    @Query("UPDATE device_media SET sha256 = :sha256, sha_size = :size, sha_modified = :modified WHERE id = :id")
    suspend fun setSha(id: Long, sha256: String, size: Long, modified: Long)

    /** The phone's folders with photos or videos, with their counts (Settings › Backup's folder list). */
    @Query("SELECT location_id, dir, COUNT(*) AS files, COALESCE(SUM(size), 0) AS bytes FROM device_media GROUP BY location_id, dir")
    fun observeDirs(): Flow<List<DeviceDirRow>>

    @Query("SELECT * FROM device_folders ORDER BY path")
    fun observeFolders(): Flow<List<DeviceFolderEntity>>

    @Query("SELECT * FROM device_folders")
    suspend fun folders(): List<DeviceFolderEntity>

    @Upsert
    suspend fun putFolder(folder: DeviceFolderEntity)

    @Query("DELETE FROM device_folders WHERE path = :path")
    suspend fun deleteFolder(path: String)

    @Query("SELECT * FROM device_pushed")
    suspend fun pushed(): List<DevicePushedEntity>

    @Upsert
    suspend fun putPushed(rows: List<DevicePushedEntity>)

    @Query("DELETE FROM device_pushed")
    suspend fun clearPushed()
}

data class ReplacedRow(@ColumnInfo(name = "item_id") val itemId: String, @ColumnInfo(name = "replaced_by") val replacedBy: String)

@Dao
interface MappingDao {
    @Query("SELECT * FROM smb_mappings ORDER BY machine_id, share, smb_prefix")
    suspend fun all(): List<SmbMappingEntity>

    @Query("SELECT * FROM smb_mappings ORDER BY machine_id, share, smb_prefix")
    fun observe(): Flow<List<SmbMappingEntity>>

    @Query("SELECT * FROM smb_mappings WHERE machine_id = :machineId AND share = :share")
    suspend fun forShare(machineId: String, share: String): List<SmbMappingEntity>

    @Upsert
    suspend fun put(mapping: SmbMappingEntity)

    @Query("DELETE FROM smb_mappings WHERE machine_id = :machineId AND share = :share AND smb_prefix = :smbPrefix")
    suspend fun forget(machineId: String, share: String, smbPrefix: String)

    @Query("DELETE FROM smb_mappings WHERE folder_id = :folderId")
    suspend fun forgetFolder(folderId: String)

    @Query("SELECT * FROM smb_mapping_misses WHERE machine_id = :machineId AND share = :share AND path = :path")
    suspend fun miss(machineId: String, share: String, path: String): SmbMappingMissEntity?

    @Upsert
    suspend fun putMiss(miss: SmbMappingMissEntity)

    @Query("DELETE FROM smb_mapping_misses WHERE machine_id = :machineId AND share = :share")
    suspend fun clearMisses(machineId: String, share: String)

    @Query("DELETE FROM smb_mappings")
    suspend fun clearMappings()

    @Query("DELETE FROM smb_mapping_misses")
    suspend fun clearAllMisses()
}

/** What Settings › Backup counts per folder: files and bytes by state (and the reason of `paused` / `failed`). */
data class BackupCountRow(
    @ColumnInfo(name = "root_id") val rootId: String,
    val state: String,
    val error: String?,
    val files: Int,
    val bytes: Long,
)

/** A phone folder of the index and what it holds. */
data class DeviceDirRow(@ColumnInfo(name = "location_id") val locationId: String, val dir: String, val files: Int, val bytes: Long)

/** A file's backup state by its path on the phone (the Gallery's badge). */
data class BackupStateRow(val absolute: String, val state: String)

@Dao
interface BackupDao {
    @Query("SELECT * FROM backup_roots ORDER BY volume, path")
    suspend fun roots(): List<BackupRootEntity>

    @Query("SELECT * FROM backup_roots ORDER BY volume, path")
    fun observeRoots(): Flow<List<BackupRootEntity>>

    @Query("SELECT * FROM backup_roots WHERE id = :id")
    suspend fun root(id: String): BackupRootEntity?

    @Upsert
    suspend fun putRoot(root: BackupRootEntity)

    @Query("UPDATE backup_roots SET last_pass_at = :at, server_stats = :stats WHERE id = :id")
    suspend fun passed(id: String, at: Long, stats: String?)

    @Query("UPDATE backup_roots SET types = :types WHERE id = :id")
    suspend fun setTypes(id: String, types: String)

    @Query("DELETE FROM backup_roots WHERE id = :id")
    suspend fun deleteRootRow(id: String)

    @Query("DELETE FROM backup_files WHERE root_id = :rootId")
    suspend fun deleteRootFiles(rootId: String)

    /** Forgets a folder here (the server keeps its backup: a device never deletes one, ADR 0024 "Security"). */
    @Transaction
    suspend fun deleteRoot(id: String) {
        deleteRootFiles(id)
        deleteRootRow(id)
    }

    /** A root that replaced another (the server's `absorbed`): its files move to [to] with their state. */
    @Query("UPDATE OR REPLACE backup_files SET root_id = :to WHERE root_id = :from")
    suspend fun moveFiles(from: String, to: String)

    @Query("SELECT * FROM backup_files WHERE root_id = :rootId")
    suspend fun files(rootId: String): List<BackupFileEntity>

    @Query("SELECT * FROM backup_files WHERE root_id = :rootId ORDER BY taken DESC")
    fun observeFiles(rootId: String): Flow<List<BackupFileEntity>>

    @Query("SELECT * FROM backup_files WHERE root_id = :rootId AND path = :path")
    suspend fun file(rootId: String, path: String): BackupFileEntity?

    @Upsert
    suspend fun putFiles(rows: List<BackupFileEntity>)

    @Query("DELETE FROM backup_files WHERE root_id = :rootId AND path IN (:paths)")
    suspend fun deleteFiles(rootId: String, paths: List<String>)

    @Query("SELECT root_id, state, error, COUNT(*) AS files, COALESCE(SUM(size), 0) AS bytes FROM backup_files GROUP BY root_id, state, error")
    fun observeCounts(): Flow<List<BackupCountRow>>

    @Query("SELECT root_id, state, error, COUNT(*) AS files, COALESCE(SUM(size), 0) AS bytes FROM backup_files GROUP BY root_id, state, error")
    suspend fun counts(): List<BackupCountRow>

    /** Files not (yet) backed up, by phone path: the Gallery's badges (backed-up files show as on the server). */
    @Query("SELECT absolute, state FROM backup_files WHERE state NOT IN ('stored', 'nas', 'verified')")
    fun observeOpenStates(): Flow<List<BackupStateRow>>

    /** A killed run left files `hashing` or `uploading`: they are queued again (the upload id and offset stay). */
    @Query("UPDATE backup_files SET state = 'queued' WHERE state IN ('hashing', 'uploading')")
    suspend fun settleInterrupted()

    /** Rows of a folder turned off while a run was writing them. */
    @Query("DELETE FROM backup_files WHERE root_id NOT IN (SELECT id FROM backup_roots)")
    suspend fun deleteOrphans()

    @Query("SELECT COUNT(*) FROM freed")
    suspend fun freedCount(): Int
}
