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
 * adds `smb_mappings` and `smb_mapping_misses`.
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
    ],
    version = 2,
    exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun account(): AccountDao
    abstract fun items(): ItemDao
    abstract fun culling(): CullingDao
    abstract fun device(): DeviceDao
    abstract fun mappings(): MappingDao

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

        fun open(context: Context, name: String? = FILE_NAME): GalleryDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context.applicationContext, GalleryDatabase::class.java)
            } else {
                Room.databaseBuilder(context.applicationContext, GalleryDatabase::class.java, name)
            }
            return builder.addMigrations(MIGRATION_1_2).build()
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

    @Query("UPDATE server_account SET culling_declared = 1 WHERE id = 1")
    suspend fun cullingDeclared()

    @Query("DELETE FROM server_account")
    suspend fun clear()
}

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
    @Query("SELECT id, kind, main_fingerprint, sort_time, capture_local, make, model, lens, width, height, duration_ms, name, folder, search, has_raw, edit_primary, edit_count FROM server_items")
    fun observeRows(): Flow<List<ServerItemRow>>

    /** Item ids newest first, a page at a time (the offline thumbnails job). */
    @Query("SELECT id FROM server_items ORDER BY sort_time DESC LIMIT :limit OFFSET :offset")
    suspend fun idsNewestFirst(limit: Int, offset: Int): List<String>

    @Query("SELECT json FROM server_items WHERE id = :id")
    suspend fun json(id: String): String?

    @Query("SELECT * FROM server_items WHERE id = :id")
    suspend fun get(id: String): ServerItemEntity?

    @Query("SELECT COUNT(*) FROM server_items")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM server_items")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun putItems(items: List<ServerItemEntity>)

    @Upsert
    suspend fun putFiles(files: List<ServerItemFileEntity>)

    @Query("DELETE FROM server_item_files WHERE item_id IN (:ids)")
    suspend fun deleteFiles(ids: List<String>)

    @Query("DELETE FROM server_items WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<String>)

    /** Replaces items (and their member files) and drops tombstones, in one transaction. */
    @Transaction
    suspend fun apply(live: List<ServerItemEntity>, files: List<ServerItemFileEntity>, gone: List<String>) {
        val touched = live.map { it.id } + gone
        for (chunk in touched.chunked(500)) deleteFiles(chunk)
        for (chunk in gone.chunked(500)) deleteItems(chunk)
        if (live.isNotEmpty()) putItems(live)
        if (files.isNotEmpty()) putFiles(files)
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
