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
            .addMigrations(GalleryDatabase.MIGRATION_1_2, GalleryDatabase.MIGRATION_2_3).build()
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

    @Test
    fun migratesTwoToThreeAddingTrashedSince() {
        val name = "migration-2-3.db"
        helper.createDatabase(name, 2).use { db ->
            db.execSQL(
                "INSERT INTO server_items (id, rev, kind, main_fingerprint, sort_time, capture_local, make, model, lens, width, height, " +
                    "duration_ms, name, folder, search, has_raw, edit_primary, edit_count, edited_at, json) VALUES ('i1', 3, 'photo', 'fp', 5, NULL, NULL, NULL, " +
                    "NULL, NULL, NULL, NULL, 'a.jpg', 'photos', 'a.jpg', 0, NULL, 0, NULL, '{}')",
            )
        }
        helper.runMigrationsAndValidate(name, 3, true, GalleryDatabase.MIGRATION_2_3).close()
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDatabase::class.java, name)
            .addMigrations(GalleryDatabase.MIGRATION_1_2, GalleryDatabase.MIGRATION_2_3).build()
        try {
            runBlocking {
                // The existing row is live (trashed_since NULL) and still in the grid.
                assertThat(room.items().get("i1")!!.trashedSince).isNull()
                assertThat(room.items().count()).isEqualTo(1)
                room.items().putItems(listOf(room.items().get("i1")!!.copy(trashedSince = 7)))
                assertThat(room.items().count()).isEqualTo(0)
                assertThat(room.items().trashed().single().id).isEqualTo("i1")
            }
        } finally {
            room.close()
        }
    }
}
