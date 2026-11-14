package dev.hfps.hoard.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.data.legacy.CapacitorImporter
import dev.hfps.hoard.data.legacy.ImportOutcome
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The importer on a real device (ADR 0019): the platform's SQLite, the app's own private directories, Capacitor
 * databases made by the real JS migrations. Leaves the app's own databases alone (its files are temporary ones).
 */
@RunWith(AndroidJUnit4::class)
class ImporterOnDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.noBackupFilesDir, "importer-device-test")
    private lateinit var db: HoardDatabase

    @Before fun setUp() {
        dir.deleteRecursively()
        db = Room.databaseBuilder(context, HoardDatabase::class.java, "importer-device-test.db").build()
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase("importer-device-test.db")
        dir.deleteRecursively()
    }

    private fun importFrom(fixture: String): ImportOutcome {
        val legacy = DeviceFixtures.copy(fixture, File(dir, "databases/hoardSQLite.db"))
        return runBlocking { CapacitorImporter(db, legacy, File(dir, "work")).importIfNeeded() }
    }

    @Test fun importsTheLatestSchemaRowForRow() {
        val outcome = importFrom("hoard-v7.db")
        assertThat(outcome).isInstanceOf(ImportOutcome.Imported::class.java)
        val expected = DeviceFixtures.copy("hoard-v7.db", File(dir, "expected.db"))
        SQLiteDatabase.openDatabase(expected.path, null, SQLiteDatabase.OPEN_READONLY).use { legacy ->
            for (table in CapacitorImporter.TABLES) {
                assertThat(db.openHelper.writableDatabase.rows(table)).isEqualTo(legacy.rows(table))
            }
        }
        assertThat(File(dir, "work").listFiles().orEmpty().toList()).isEmpty()
    }

    @Test fun upgradesAHoard1Database() {
        val outcome = importFrom("hoard-v1.db") as ImportOutcome.Imported
        assertThat(outcome.record.legacyVersion).isEqualTo(1)
        val expected = DeviceFixtures.copy("hoard-v1-upgraded.db", File(dir, "expected.db"))
        SQLiteDatabase.openDatabase(expected.path, null, SQLiteDatabase.OPEN_READONLY).use { legacy ->
            for (table in CapacitorImporter.TABLES) {
                assertThat(db.openHelper.writableDatabase.rows(table)).isEqualTo(legacy.rows(table))
            }
        }
    }

    @Test fun thenNeverAgain() {
        importFrom("hoard-v7.db")
        assertThat(runBlocking { CapacitorImporter(db, File(dir, "databases/hoardSQLite.db"), File(dir, "work")).importIfNeeded() })
            .isInstanceOf(ImportOutcome.AlreadyDone::class.java)
    }
}
