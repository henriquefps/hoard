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

    private fun createV1(file: File) {
        val schema = JSONObject(File("schemas/dev.hfps.hoard.data.HoardDatabase/1.json").readText()).getJSONObject("database")
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
            db.execSQL("INSERT INTO settings (key, value) VALUES ('cullSaveXmp', 'true')")
            db.version = 1
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
            // Room validates every table against the version 2 schema when it opens the database.
            // 2.2.0: the automatic XMP setting is forced off on upgrade (ADR 0011 amendment).
            assertThat(db.settings().get("cullSaveXmp")).isEqualTo("false")
            db.revealCopies().save(listOf(RevealCopyEntity(uri = "content://media/external_primary/images/media/1", sourceKey = "m/share", sourcePath = "a/DSC1.ARW", size = 10, modifiedMillis = 5, copiedAt = "2026-11-15T00:00:00Z")))
            assertThat(db.revealCopies().find("m/share", listOf("a/DSC1.ARW")).single().size).isEqualTo(10)
            // A changed source copied again keeps its older copy as another row.
            db.revealCopies().save(listOf(RevealCopyEntity(uri = "content://media/external_primary/images/media/2", sourceKey = "m/share", sourcePath = "a/DSC1.ARW", size = 11, modifiedMillis = 6, copiedAt = "2026-11-16T00:00:00Z")))
            assertThat(db.revealCopies().find("m/share", listOf("a/DSC1.ARW")).map { it.size }).containsExactly(11L, 10L).inOrder()
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(2)
        } finally {
            db.close()
            file.delete()
        }
    }
}
