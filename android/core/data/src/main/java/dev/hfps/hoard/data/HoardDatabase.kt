package dev.hfps.hoard.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
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
    ],
    version = 1,
    exportSchema = true,
)
abstract class HoardDatabase : RoomDatabase() {
    abstract fun machines(): MachineDao
    abstract fun favorites(): FavoriteDao
    abstract fun settings(): SettingsDao
    abstract fun legacyImports(): LegacyImportDao

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
            return builder.build()
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
    @Query("SELECT * FROM machines ORDER BY sort_order, name")
    fun observeAll(): Flow<List<MachineWithAddresses>>

    @Query("SELECT COUNT(*) FROM machines")
    suspend fun count(): Int
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY sort_order, label")
    fun observeAll(): Flow<List<FavoriteEntity>>
}

@Dao
interface SettingsDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

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
