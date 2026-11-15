package dev.hfps.hoard.server.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `gallery.db` 1 → 2 (Hoard 2.2.0, ADR 0022): the mapping tables are added, everything else is kept. */
@RunWith(AndroidJUnit4::class)
class GalleryMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GalleryDatabase::class.java)

    @Test
    fun migratesOneToTwoKeepingTheQueue() {
        val name = "migration-test.db"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO culling_pending (item_id, field, value, base_rev, modified_at, attempts) VALUES ('i1', 'rating', '4', 3, 100, 0)")
        }
        helper.runMigrationsAndValidate(name, 2, true, GalleryDatabase.MIGRATION_1_2).close()
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDatabase::class.java, name)
            .addMigrations(GalleryDatabase.MIGRATION_1_2).build()
        try {
            runBlocking {
                assertThat(room.culling().pendingFor("i1")).hasSize(1)
                room.mappings().put(SmbMappingEntity("m1", "photos", "Imagens", "srv", "f", "Imagens", "", "g", 1, 1))
                assertThat(room.mappings().all()).hasSize(1)
            }
        } finally {
            room.close()
        }
    }
}
