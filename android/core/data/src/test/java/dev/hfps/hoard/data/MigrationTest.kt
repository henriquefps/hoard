package dev.hfps.hoard.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Room migrations (ADR 0003): a database made exactly as the exported schema 1 describes opens under the current
 * version through [HoardMigrations], keeps its rows, and Room's own check (the schema it expects) passes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun createV1(file: File) = create(file, 1) { db -> db.execSQL("INSERT INTO settings (key, value) VALUES ('cullSaveXmp', 'true')") }

    /** A database made exactly as the exported schema [version] describes, then [rows]. */
    private fun create(file: File, version: Int, rows: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(File("schemas/dev.hfps.hoard.data.HoardDatabase/$version.json").readText()).getJSONObject("database")
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            rows(db)
            db.version = version
        }
    }

    @Test fun version1MigratesToTheCurrentSchema() = runBlocking {
        val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs() }
        createV1(file)
        val db = Room.databaseBuilder(context, HoardDatabase::class.java, file.path)
            .addMigrations(*HoardMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            // Room validates every table against the current schema when it opens the database.
            // 2.2.0: the automatic XMP setting is forced off on upgrade (ADR 0011 amendment).
            assertThat(db.settings().get("cullSaveXmp")).isEqualTo("false")
            db.revealCopies().save(listOf(RevealCopyEntity(uri = "content://media/external_primary/images/media/1", sourceKey = "m/share", sourcePath = "a/DSC1.ARW", size = 10, modifiedMillis = 5, copiedAt = "2026-11-15T00:00:00Z")))
            assertThat(db.revealCopies().find("m/share", listOf("a/DSC1.ARW")).single().size).isEqualTo(10)
            // A changed source copied again keeps its older copy as another row.
            db.revealCopies().save(listOf(RevealCopyEntity(uri = "content://media/external_primary/images/media/2", sourceKey = "m/share", sourcePath = "a/DSC1.ARW", size = 11, modifiedMillis = 6, copiedAt = "2026-11-16T00:00:00Z")))
            assertThat(db.revealCopies().find("m/share", listOf("a/DSC1.ARW")).map { it.size }).containsExactly(11L, 10L).inOrder()
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(3)
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test fun version2MigratesToVersion3AndKeepsCullingRows() = runBlocking {
        val file = context.getDatabasePath("migration-2-3.db").apply { parentFile?.mkdirs() }
        create(file, 2) { db ->
            db.execSQL("INSERT INTO cull_decisions (location_id, share, path, shot_id, flag, rating, updated_at) VALUES ('m1', 'photos', 'shoot', 'dsc1', 'pick', 4, '2026-11-14T00:00:00.000Z')")
            db.execSQL("INSERT INTO cull_sidecars (location_id, share, path, shot_id, decided_at, sidecar_mtime, synced) VALUES ('m1', 'photos', 'shoot', 'dsc1', 5000, 4000, 1)")
            db.execSQL("INSERT INTO reveal_copies (uri, source_key, source_path, size, modified_ms, copied_at) VALUES ('content://media/1', 'm/s', 'a/b.ARW', 10, 5, '2026-11-15T00:00:00Z')")
        }
        val db = Room.databaseBuilder(context, HoardDatabase::class.java, file.path)
            .addMigrations(*HoardMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            val cull = db.cull()
            assertThat(cull.forFolder("m1", "photos", "shoot").single().rating).isEqualTo(4)
            val sidecar = cull.sidecars("m1", "photos", "shoot").single()
            assertThat(listOf(sidecar.decidedAt, sidecar.sidecarMtime, sidecar.synced.toLong())).containsExactly(5000L, 4000L, 1L).inOrder()
            assertThat(db.revealCopies().find("m/s", listOf("a/b.ARW")).single().size).isEqualTo(10)
            // The new table starts empty (every sidecar's next read is a first read) and takes rows.
            assertThat(cull.sidecarReads("m1", "photos", "shoot")).isEmpty()
            cull.saveSidecarReads("m1", "photos", "shoot", listOf(CullSidecarReadEntity("m1", "photos", "shoot", "dsc1", "DSC1.xmp", 512, 4000, 3, "pick", null)), emptyList())
            assertThat(cull.sidecarReads("m1", "photos", "shoot").single().rating).isEqualTo(3)
            cull.saveSidecarReads("m1", "photos", "shoot", emptyList(), listOf("dsc1"))
            assertThat(cull.sidecarReads("m1", "photos", "shoot")).isEmpty()
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(3)
        } finally {
            db.close()
            file.delete()
        }
    }
}
