package dev.hfps.hoard.server.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `gallery.db` migrations: each step keeps what was there (1 → 2 ADR 0022, 2 → 3 ADR 0023, 3 → 4 and 4 → 5 ADR 0024). */
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
            .addMigrations(*GalleryDatabase.MIGRATIONS).build()
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
            .addMigrations(*GalleryDatabase.MIGRATIONS).build()
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

    @Test
    fun migratesThreeToFourAddingThePhoneBackup() {
        val name = "migration-3-4.db"
        helper.createDatabase(name, 3).use { db ->
            db.execSQL(
                "INSERT INTO device_media (id, path, location_id, dir, name, shot_key, mime, size, modified, taken, duration_ms, width, height, " +
                    "orientation, video, fingerprint, fp_size, fp_modified, motion) VALUES (7, '/storage/emulated/0/DCIM/Camera/a.jpg', 'device', " +
                    "'DCIM/Camera', 'a.jpg', 'k', 'image/jpeg', 10, 20, 30, NULL, NULL, NULL, 0, 0, 'v1:x', 10, 20, 0)",
            )
            db.execSQL("INSERT INTO culling_pending (item_id, field, value, base_rev, modified_at, attempts) VALUES ('i1', 'rating', '4', 3, 100, 0)")
        }
        helper.runMigrationsAndValidate(name, 4, true, GalleryDatabase.MIGRATION_3_4).close()
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDatabase::class.java, name)
            .addMigrations(*GalleryDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                // The index and the queue are kept; the new hash columns start empty.
                val row = room.device().get(7)!!
                assertThat(row.fingerprint).isEqualTo("v1:x")
                assertThat(row.sha256).isNull()
                assertThat(room.culling().pendingFor("i1")).hasSize(1)
                room.device().setIdentity(7, "v1:y", 10, 20, false, "abc")
                assertThat(room.device().get(7)!!.let { Triple(it.sha256, it.shaSize, it.shaModified) }).isEqualTo(Triple("abc", 10L, 20L))
                room.backup().putRoot(BackupRootEntity("r1", "primary", "DCIM/Camera", "device", "DCIM/Camera", "image,video", 1))
                room.backup().putFiles(listOf(BackupFileEntity("r1", "DCIM/Camera/a.jpg", "/storage/emulated/0/DCIM/Camera/a.jpg", 7, "image", 10, 20, 30, state = "queued")))
                assertThat(room.backup().counts().single().files).isEqualTo(1)
                assertThat(room.backup().freedCount()).isEqualTo(0)
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun migratesFourToFiveAddingMirrorLocations() {
        val name = "migration-4-5.db"
        helper.createDatabase(name, 4).use { db ->
            db.execSQL(
                "INSERT INTO server_account (id, url, server_id, server_name, server_version, device_id, physical_device_id, app, features, cursor, " +
                    "caught_up, paired_at, culling_declared) VALUES (1, 'http://s', 'srv', 'S', '0.12.0', 'd1', 'p1', 'hoard', 'items,backup', 'c:9', 1, 5, 1)",
            )
            db.execSQL(
                "INSERT INTO server_items (id, rev, kind, main_fingerprint, sort_time, capture_local, make, model, lens, width, height, " +
                    "duration_ms, name, folder, search, has_raw, edit_primary, edit_count, edited_at, json) VALUES ('i1', 3, 'photo', 'fp', 5, NULL, NULL, NULL, " +
                    "NULL, NULL, NULL, NULL, 'a.jpg', '', 'a.jpg', 0, NULL, 0, NULL, '{}')",
            )
        }
        helper.runMigrationsAndValidate(name, 5, true, GalleryDatabase.MIGRATION_4_5).close()
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDatabase::class.java, name)
            .addMigrations(*GalleryDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                // The pairing and the cursor are kept (nothing is pulled again); the cached items still need their index.
                val account = room.account().get()!!
                assertThat(account.cursor).isEqualTo("c:9")
                assertThat(account.mirrorsIndexed).isFalse()
                assertThat(account.backupRoots).isNull()
                room.items().putMirrors(listOf(ServerItemMirrorEntity("i1", "fp", "p2", "Pixel 8", "r1", "primary", "DCIM/Camera/a.jpg", 10, 1, false, "deleted")))
                assertThat(room.items().observeMirrors().first().single().gone).isEqualTo("deleted")
                room.account().setMirrorsIndexed(true)
                assertThat(room.account().get()!!.mirrorsIndexed).isTrue()
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun migratesFiveToSixAddingThePhoneReplaceJournal() {
        val name = "migration-5-6.db"
        helper.createDatabase(name, 5).use { db ->
            db.execSQL(
                "INSERT INTO server_account (id, url, server_id, server_name, server_version, device_id, physical_device_id, app, features, cursor, " +
                    "caught_up, paired_at, culling_declared, backup_roots, mirrors_indexed) VALUES (1, 'http://s', 'srv', 'S', '0.13.1', 'd1', 'p1', 'hoard', " +
                    "'items,backup,compress', 'c:9', 1, 5, 1, NULL, 1)",
            )
            db.execSQL(
                "INSERT INTO backup_files (root_id, path, absolute, media_id, kind, size, modified, taken, fingerprint, fp_size, fp_modified, sha256, sha_size, " +
                    "sha_modified, state, reported, server_state, item_id, verified_at, upload_id, upload_offset, attempts, next_attempt_at, error, updated_at) " +
                    "VALUES ('r', 'DCIM/Camera/VID_1.mp4', '/storage/emulated/0/DCIM/Camera/VID_1.mp4', 7, 'video', 10, 20, 30, 'v1:x', 10, 20, NULL, NULL, NULL, " +
                    "'stored', 'v1:x|10|20', 'stored', 'i1', NULL, NULL, 0, 0, NULL, NULL, 1)",
            )
        }
        helper.runMigrationsAndValidate(name, 6, true, GalleryDatabase.MIGRATION_5_6).close()
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), GalleryDatabase::class.java, name)
            .addMigrations(*GalleryDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                // What was there stays: the pairing, the backup's rows.
                assertThat(room.account().get()!!.cursor).isEqualTo("c:9")
                assertThat(room.backup().filesAt("/storage/emulated/0/DCIM/Camera/VID_1.mp4").single().state).isEqualTo("stored")
                // The new tables work.
                room.compress().putJob(CompressJobEntity("job", "phone", "hevc-cq28", false, 9))
                assertThat(room.compress().jobs().single().target).isEqualTo("phone")
                room.compress().putPhone(
                    CompressPhoneEntity(
                        id = "7", volume = "primary", path = "DCIM/Camera/VID_1.mp4", absolute = "/storage/emulated/0/DCIM/Camera/VID_1.mp4",
                        direction = "compress", sourceFingerprint = "v1:x", sourceSha256 = null, sourceSize = 10, targetFingerprint = "v1:y",
                        targetSha256 = "s", targetSize = 3, state = "writing", report = "replaced",
                    ),
                )
                assertThat(room.compress().unsettledPaths()).containsExactly("/storage/emulated/0/DCIM/Camera/VID_1.mp4")
            }
        } finally {
            room.close()
        }
    }
}
