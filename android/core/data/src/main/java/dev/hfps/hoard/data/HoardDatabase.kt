package dev.hfps.hoard.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The native app's database (ADR 0019): the Capacitor schema v7 (see Entities.kt) plus `legacy_imports`. Room
 * version 1 is that starting point; later changes are Room migrations checked against `core/data/schemas/`.
 */
@Database(
    entities = [
        MachineEntity::class,
        MachineAddressEntity::class,
        FavoriteEntity::class,
        ManualShareEntity::class,
        TransferEntity::class,
        SettingEntity::class,
        CullSessionEntity::class,
        CullDecisionEntity::class,
        CullDestinationEntity::class,
        CullAnalysisEntity::class,
        CullSidecarEntity::class,
        LegacyImportEntity::class,
        RevealCopyEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class HoardDatabase : RoomDatabase() {
    abstract fun machines(): MachineDao
    abstract fun favorites(): FavoriteDao
    abstract fun manualShares(): ManualShareDao
    abstract fun transfers(): TransferDao
    abstract fun cull(): CullDao
    abstract fun settings(): SettingsDao
    abstract fun legacyImports(): LegacyImportDao
    abstract fun revealCopies(): RevealCopyDao

    companion object {
        /**
         * File name in the app's databases directory. Not the Capacitor plugin's `hoardSQLite.db`
         * ([dev.hfps.hoard.data.legacy.LegacyDatabase.FILE_NAME]): that file is only ever read, through a copy.
         */
        const val FILE_NAME = "hoard.db"

        fun open(context: Context, name: String? = FILE_NAME): HoardDatabase {
            val builder = if (name == null) {
                Room.inMemoryDatabaseBuilder(context.applicationContext, HoardDatabase::class.java)
            } else {
                Room.databaseBuilder(context.applicationContext, HoardDatabase::class.java, name)
            }
            return builder.addMigrations(*HoardMigrations.ALL).build()
        }
    }
}

/** A machine with its addresses ([addresses] in no order; [ordered] by priority). */
data class MachineWithAddresses(
    @Embedded val machine: MachineEntity,
    @Relation(parentColumn = "id", entityColumn = "machine_id")
    val addresses: List<MachineAddressEntity>,
) {
    /** The addresses in priority order (`sort_order`), the order route detection tries them in (ADR 0002). */
    val ordered: List<MachineAddressEntity> get() = addresses.sortedBy { it.sortOrder }
}

@Dao
interface MachineDao {
    @Transaction
    @Query("SELECT * FROM machines ORDER BY sort_order, created_at")
    fun observeAll(): Flow<List<MachineWithAddresses>>

    @Transaction
    @Query("SELECT * FROM machines ORDER BY sort_order, created_at")
    suspend fun all(): List<MachineWithAddresses>

    @Transaction
    @Query("SELECT * FROM machines WHERE id = :id")
    suspend fun get(id: String): MachineWithAddresses?

    @Transaction
    @Query("SELECT * FROM machines WHERE id = :id")
    fun observe(id: String): Flow<MachineWithAddresses?>

    @Query("SELECT COUNT(*) FROM machines")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sort_order) + 1, 0) FROM machines")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(machine: MachineEntity)

    @Query("UPDATE machines SET name = :name, icon = :icon, color = :color, username = :username, domain = :domain WHERE id = :id")
    suspend fun update(id: String, name: String, icon: String?, color: String?, username: String, domain: String?): Int

    @Query("DELETE FROM machine_addresses WHERE machine_id = :machineId")
    suspend fun deleteAddresses(machineId: String)

    @Insert
    suspend fun insertAddresses(addresses: List<MachineAddressEntity>)

    @Query("UPDATE machines SET server_guid = :guid WHERE id = :id")
    suspend fun setServerGuid(id: String, guid: String?)

    @Query("UPDATE machines SET sort_order = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Int)

    @Query("DELETE FROM machines WHERE id = :id")
    suspend fun delete(id: String)

    // Culling rows have no foreign key (local locations are not rows): `deleteMachine` clears them by hand, as
    // src/db/queries.ts does.
    @Query("DELETE FROM cull_decisions WHERE location_id = :id")
    suspend fun deleteCullDecisions(id: String)

    @Query("DELETE FROM cull_sidecars WHERE location_id = :id")
    suspend fun deleteCullSidecars(id: String)

    @Query("DELETE FROM cull_sessions WHERE location_id = :id")
    suspend fun deleteCullSessions(id: String)

    @Query("DELETE FROM cull_analysis WHERE location_id = :id")
    suspend fun deleteCullAnalysis(id: String)

    @Query("DELETE FROM cull_destinations WHERE source_location_id = :id OR location_id = :id")
    suspend fun deleteCullDestinations(id: String)

    /** Deletes a machine with its addresses, favorites, manual shares and transfers (cascade) and its culling rows. */
    @Transaction
    suspend fun deleteMachine(id: String) {
        delete(id)
        deleteCullDecisions(id)
        deleteCullSidecars(id)
        deleteCullSessions(id)
        deleteCullAnalysis(id)
        deleteCullDestinations(id)
    }

    /** Creates ([create]) or updates a machine and replaces its addresses atomically (src/db/queries.ts `saveMachine`). */
    @Transaction
    suspend fun save(machine: MachineEntity, addresses: List<MachineAddressEntity>, create: Boolean) {
        if (create) {
            insert(machine.copy(sortOrder = nextSortOrder()))
        } else {
            val updated = update(machine.id, machine.name, machine.icon, machine.color, machine.username, machine.domain)
            check(updated == 1) { "Machine ${machine.id} not found" }
            deleteAddresses(machine.id)
        }
        insertAddresses(addresses)
    }

    @Transaction
    suspend fun reorder(ids: List<String>) {
        ids.forEachIndexed { index, id -> setSortOrder(id, index) }
    }
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY sort_order, created_at")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE location_id = :locationId AND share = :share AND path = :path")
    fun observeAt(locationId: String, share: String, path: String): Flow<FavoriteEntity?>

    @Query("SELECT * FROM favorites WHERE location_id = :locationId AND share = :share AND path = :path")
    suspend fun find(locationId: String, share: String, path: String): FavoriteEntity?

    @Query("SELECT COALESCE(MAX(sort_order) + 1, 0) FROM favorites")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(favorite: FavoriteEntity)

    @Query("UPDATE favorites SET label = :label WHERE id = :id")
    suspend fun rename(id: String, label: String)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun remove(id: String)

    @Query("UPDATE favorites SET sort_order = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Int)

    /** Pins a folder; pinning the same place again only renames it (src/db/queries.ts `addFavorite`). Returns the id. */
    @Transaction
    suspend fun add(favorite: FavoriteEntity): String {
        val existing = find(favorite.locationId, favorite.share, favorite.path)
        if (existing != null) {
            rename(existing.id, favorite.label)
            return existing.id
        }
        insert(favorite.copy(sortOrder = nextSortOrder()))
        return favorite.id
    }

    @Transaction
    suspend fun reorder(ids: List<String>) {
        ids.forEachIndexed { index, id -> setSortOrder(id, index) }
    }
}

@Dao
interface ManualShareDao {
    @Query("SELECT * FROM manual_shares WHERE machine_id = :machineId ORDER BY name")
    fun observe(machineId: String): Flow<List<ManualShareEntity>>

    /** Adding a share again (any letter case) keeps the first entry. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(share: ManualShareEntity)

    @Query("DELETE FROM manual_shares WHERE machine_id = :machineId AND name = :name")
    suspend fun remove(machineId: String, name: String)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY created_at DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers")
    suspend fun all(): List<TransferEntity>

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun get(id: String): TransferEntity?

    @Insert
    suspend fun insert(transfer: TransferEntity)

    /** A native progress event (or a `reported()` job) applied to the row (src/db/queries.ts `applyTransferProgress`). */
    @Query(
        """UPDATE transfers SET bytes_done = :bytesDone, size = :totalBytes, state = :state, error_code = :errorCode,
           error = :error, items_total = :itemsTotal, items_done = :itemsDone, items_failed = :itemsFailed,
           items_skipped = :itemsSkipped,
           finished_at = CASE WHEN :finished THEN COALESCE(finished_at, :now) ELSE NULL END
           WHERE id = :id""",
    )
    suspend fun applyProgress(
        id: String,
        state: String,
        bytesDone: Long,
        totalBytes: Long,
        itemsTotal: Int,
        itemsDone: Int,
        itemsFailed: Int,
        itemsSkipped: Int,
        errorCode: String?,
        error: String?,
        finished: Boolean,
        now: String,
    )

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM transfers WHERE state IN ('completed', 'failed', 'cancelled', 'skipped')")
    suspend fun clearFinished(): Int
}

@Dao
abstract class CullDao {
    // --- Sessions (queries.ts getCullSession / touchCullSession) ---

    @Query("SELECT * FROM cull_sessions WHERE location_id = :locationId AND share = :share AND path = :path")
    abstract suspend fun session(locationId: String, share: String, path: String): CullSessionEntity?

    @Upsert
    abstract suspend fun putSession(session: CullSessionEntity)

    /**
     * Starts or continues a folder's culling session: created on the first visit, `last_visited_at` (and the shot count
     * and the last shot when given) updated on every later one.
     */
    @Transaction
    open suspend fun touchSession(locationId: String, share: String, path: String, shotCount: Int? = null, lastShotId: String? = null, setLastShot: Boolean = false) {
        val at = dev.hfps.hoard.data.legacy.isoNow()
        val existing = session(locationId, share, path)
        putSession(
            CullSessionEntity(
                locationId = locationId,
                share = share,
                path = path,
                startedAt = existing?.startedAt ?: at,
                lastVisitedAt = at,
                lastShotId = if (setLastShot) lastShotId else existing?.lastShotId,
                shotCount = shotCount ?: existing?.shotCount ?: 0,
            ),
        )
    }

    // --- Decisions (ADR 0008), keyed by shot id (lowercase stem) ---

    @Query("SELECT * FROM cull_decisions WHERE location_id = :locationId AND share = :share AND path = :path")
    abstract suspend fun forFolder(locationId: String, share: String, path: String): List<CullDecisionEntity>

    @Upsert
    abstract suspend fun putDecision(decision: CullDecisionEntity)

    /** Decisions on the phone's own storage and volumes (the Gallery's phone-only shots, ADR 0021). */
    @Query("SELECT * FROM cull_decisions WHERE location_id = 'device' OR location_id LIKE 'vol-%'")
    abstract fun observeLocal(): kotlinx.coroutines.flow.Flow<List<CullDecisionEntity>>

    @Query("SELECT * FROM cull_decisions WHERE location_id = 'device' OR location_id LIKE 'vol-%'")
    abstract suspend fun localDecisions(): List<CullDecisionEntity>

    @Query("SELECT * FROM cull_decisions WHERE location_id = :locationId AND share = '' AND path = :path AND shot_id = :shotId")
    abstract suspend fun decision(locationId: String, path: String, shotId: String): CullDecisionEntity?

    @Query("SELECT * FROM cull_sidecars WHERE location_id = :locationId AND share = '' AND path = :path")
    abstract suspend fun localSidecars(locationId: String, path: String): List<CullSidecarEntity>

    /**
     * Sidecar rows of the phone's own storage and volumes: `decided_at` (and `sidecar_mtime` for an imported clear)
     * outlive an unmarked shot's deleted decision row, so they date a clear (the Gallery's hand-over, ADR 0021).
     */
    @Query("SELECT * FROM cull_sidecars WHERE location_id = 'device' OR location_id LIKE 'vol-%'")
    abstract suspend fun localSidecarRows(): List<CullSidecarEntity>

    @Query("SELECT * FROM cull_sidecars WHERE location_id = 'device' OR location_id LIKE 'vol-%'")
    abstract fun observeLocalSidecarRows(): kotlinx.coroutines.flow.Flow<List<CullSidecarEntity>>

    @Query("SELECT * FROM cull_analysis WHERE location_id = :locationId AND share = :share AND path IN (:paths)")
    abstract suspend fun analysesOf(locationId: String, share: String, paths: List<String>): List<CullAnalysisEntity>

    @Query("DELETE FROM cull_decisions WHERE location_id = :locationId AND share = :share AND path = :path AND shot_id = :shotId")
    abstract suspend fun deleteDecision(locationId: String, share: String, path: String, shotId: String)

    /**
     * One transaction for a pick, an undo or a bulk change; an unmarked shot's row is deleted (saveCullDecisions).
     * `updated_at` is when the decision was made: now, or [decidedAt] (Unix ms) for a shot listed there, e.g. a decision
     * imported from a sidecar, dated by the sidecar's mtime (ADR 0016 "Client rules" 4).
     */
    @Transaction
    open suspend fun saveDecisions(locationId: String, share: String, path: String, decisions: List<Triple<String, String?, Int>>, decidedAt: Map<String, Long> = emptyMap()) {
        val now = dev.hfps.hoard.data.legacy.isoNow()
        for ((shotId, flag, rating) in decisions) {
            val stars = rating.coerceIn(0, 5)
            if (flag == null && stars == 0) {
                deleteDecision(locationId, share, path, shotId)
            } else {
                val at = decidedAt[shotId]?.let { dev.hfps.hoard.data.legacy.isoOf(it) } ?: now
                putDecision(CullDecisionEntity(locationId, share, path, shotId, flag, stars, at))
            }
        }
    }

    // --- Sidecars (ADR 0011) ---

    @Query("SELECT * FROM cull_sidecars WHERE location_id = :locationId AND share = :share AND path = :path")
    abstract suspend fun sidecars(locationId: String, share: String, path: String): List<CullSidecarEntity>

    @Query("SELECT * FROM cull_sidecars WHERE location_id = :locationId AND share = :share AND path = :path AND shot_id = :shotId")
    abstract suspend fun sidecar(locationId: String, share: String, path: String, shotId: String): CullSidecarEntity?

    @Upsert
    abstract suspend fun putSidecar(row: CullSidecarEntity)

    /** A sidecar row update; a field left null keeps its stored value (saveCullSidecars). */
    data class SidecarUpdate(
        val shotId: String,
        val decidedAt: Long? = null,
        val setDecidedAt: Boolean = false,
        val sidecarMtime: Long? = null,
        val setSidecarMtime: Boolean = false,
        val synced: Boolean? = null,
    )

    @Transaction
    open suspend fun saveSidecars(locationId: String, share: String, path: String, rows: List<SidecarUpdate>) {
        for (row in rows) {
            val existing = sidecar(locationId, share, path, row.shotId)
            putSidecar(
                CullSidecarEntity(
                    locationId = locationId,
                    share = share,
                    path = path,
                    shotId = row.shotId,
                    decidedAt = if (row.setDecidedAt) row.decidedAt else existing?.decidedAt,
                    sidecarMtime = if (row.setSidecarMtime) row.sidecarMtime else existing?.sidecarMtime,
                    synced = row.synced?.let { if (it) 1 else 0 } ?: existing?.synced ?: 0,
                ),
            )
        }
    }

    @Query("DELETE FROM cull_sidecars WHERE location_id = :locationId AND share = :share AND path = :path AND shot_id = :shotId")
    abstract suspend fun deleteSidecar(locationId: String, share: String, path: String, shotId: String)

    /** Forgets shots that no longer exist (after "Delete rejects"). */
    @Transaction
    open suspend fun forgetShots(locationId: String, share: String, path: String, shotIds: List<String>) {
        for (id in shotIds) {
            deleteDecision(locationId, share, path, id)
            deleteSidecar(locationId, share, path, id)
        }
    }

    // --- "Copy picks" destination per source location ---

    @Query("SELECT * FROM cull_destinations WHERE source_location_id = :locationId AND source_share = :share")
    abstract suspend fun destination(locationId: String, share: String): CullDestinationEntity?

    @Upsert
    abstract suspend fun putDestination(destination: CullDestinationEntity)

    // --- Assisted culling cache (ADR 0009) ---

    @Query("SELECT * FROM cull_analysis WHERE location_id = :locationId AND share = :share AND folder = :folder")
    abstract suspend fun analyses(locationId: String, share: String, folder: String): List<CullAnalysisEntity>

    @Upsert
    abstract suspend fun putAnalyses(rows: List<CullAnalysisEntity>)

    @Query("DELETE FROM cull_analysis")
    abstract suspend fun clearAnalyses(): Int
}

@Dao
interface SettingsDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Upsert
    suspend fun put(setting: SettingEntity)
}

@Dao
interface LegacyImportDao {
    @Query("SELECT * FROM legacy_imports WHERE source = :source")
    suspend fun find(source: String): LegacyImportEntity?

    @Query("SELECT * FROM legacy_imports WHERE source = :source")
    fun observe(source: String): Flow<LegacyImportEntity?>
}

@Dao
interface RevealCopyDao {
    /** Every copy of these sources, newest first (a changed source keeps its older copies as rows). */
    @Query("SELECT * FROM reveal_copies WHERE source_key = :sourceKey AND source_path IN (:paths) ORDER BY copied_at DESC")
    suspend fun find(sourceKey: String, paths: List<String>): List<RevealCopyEntity>

    @Upsert
    suspend fun save(rows: List<RevealCopyEntity>)

    @Query("SELECT * FROM reveal_copies")
    suspend fun all(): List<RevealCopyEntity>

    @Query("DELETE FROM reveal_copies WHERE uri IN (:uris)")
    suspend fun deleteUris(uris: List<String>)
}
