package dev.hfps.hoard.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.data.legacy.CapacitorImporter
import dev.hfps.hoard.data.legacy.ImportFailure
import dev.hfps.hoard.data.legacy.ImportOutcome
import dev.hfps.hoard.data.legacy.ImportProgress
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The one-time import of the Capacitor database (ADR 0019), on databases made by the real JS migrations. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CapacitorImporterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dir: File
    private lateinit var legacy: File
    private lateinit var db: HoardDatabase

    @Before fun setUp() {
        dir = File(context.cacheDir, "importer-test-${System.nanoTime()}").apply { mkdirs() }
        legacy = File(dir, "databases/hoardSQLite.db")
        db = Room.inMemoryDatabaseBuilder(context, HoardDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun importer() = CapacitorImporter(db, legacy, File(dir, "work"), now = { "2026-11-14T12:00:00.000Z" })

    private fun run(onProgress: (ImportProgress) -> Unit = {}) = runBlocking { importer().importIfNeeded(onProgress) }

    private val target get() = db.openHelper.writableDatabase

    private fun <T> withFixture(name: String, block: (SQLiteDatabase) -> T): T {
        val copy = LegacyFixtures.copy(name, File(dir, "expected-$name"))
        return SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use(block)
    }

    @Test fun importsEveryRowOfALatestDatabase() {
        LegacyFixtures.copy(LegacyFixtures.V7, legacy)
        val outcome = run()

        assertThat(outcome).isInstanceOf(ImportOutcome.Imported::class.java)
        val record = (outcome as ImportOutcome.Imported).record
        assertThat(record.legacyVersion).isEqualTo(7)
        assertThat(record.importerVersion).isEqualTo(CapacitorImporter.IMPORTER_VERSION)
        val counts = JSONObject(record.rowCounts)
        withFixture(LegacyFixtures.V7) { expected ->
            for (table in CapacitorImporter.TABLES) {
                val rows = expected.rows(table)
                assertThat(rows).isNotEmpty() // the fixture fills every table
                assertThat(target.rows(table)).containsExactlyElementsIn(rows).inOrder()
                assertThat(counts.getInt(table)).isEqualTo(rows.size)
            }
        }
    }

    @Test fun upgradesAnOldDatabaseExactlyLikeTheJsMigrations() {
        LegacyFixtures.copy(LegacyFixtures.V1, legacy)
        val outcome = run() as ImportOutcome.Imported

        assertThat(outcome.record.legacyVersion).isEqualTo(1)
        withFixture(LegacyFixtures.V1_UPGRADED) { expected ->
            for (table in CapacitorImporter.TABLES) {
                assertThat(target.rows(table)).containsExactlyElementsIn(expected.rows(table)).inOrder()
            }
        }
        // Migration 4 turned 1.x downloads into copies with a JSON source list; migration 7 dropped a setting.
        val paths = target.query("SELECT source_paths FROM transfers WHERE id = 't1'").use { it.moveToFirst(); it.getString(0) }
        assertThat(paths).isEqualTo("""["2025/a \"quoted\" \\ name.jpg"]""")
        assertThat(target.rows("settings").map { it[0] }).doesNotContain("cullXmpSidecars")
    }

    @Test fun neverTouchesTheCapacitorFile() {
        LegacyFixtures.copy(LegacyFixtures.V1, legacy)
        val before = LegacyFixtures.sha256(legacy)
        run()
        assertThat(LegacyFixtures.sha256(legacy)).isEqualTo(before)
        assertThat(File(dir, "work").listFiles().orEmpty().toList()).isEmpty() // the snapshot is gone
    }

    @Test fun runsOncePerInstall() {
        LegacyFixtures.copy(LegacyFixtures.V7, legacy)
        val first = run() as ImportOutcome.Imported
        // Data the native app writes afterwards must survive later launches.
        target.execSQL("DELETE FROM favorites")

        val second = run()

        assertThat(second).isEqualTo(ImportOutcome.AlreadyDone(first.record))
        assertThat(target.rows("favorites")).isEmpty()
    }

    @Test fun aRepeatedCopyGivesTheSameDatabase() {
        LegacyFixtures.copy(LegacyFixtures.V7, legacy)
        run()
        val once = CapacitorImporter.TABLES.associateWith { target.rows(it) }
        target.execSQL("DELETE FROM legacy_imports") // as if the first run had not been recorded

        assertThat(run()).isInstanceOf(ImportOutcome.Imported::class.java)
        for (table in CapacitorImporter.TABLES) assertThat(target.rows(table)).isEqualTo(once[table])
    }

    @Test fun refusesADatabaseFromANewerCapacitorApp() {
        LegacyFixtures.copy(LegacyFixtures.V99, legacy)
        val before = LegacyFixtures.sha256(legacy)

        val outcome = run()

        assertThat(outcome).isInstanceOf(ImportOutcome.Failed::class.java)
        assertThat((outcome as ImportOutcome.Failed).failure).isEqualTo(ImportFailure.TooNew)
        assertThat(runBlocking { db.legacyImports().find(CapacitorImporter.SOURCE) }).isNull()
        assertThat(target.rows("machines")).isEmpty()
        assertThat(LegacyFixtures.sha256(legacy)).isEqualTo(before)
    }

    @Test fun anUnreadableFileFailsWithoutWritingAndCanBeRetried() {
        legacy.parentFile?.mkdirs()
        legacy.writeText("not a database")

        val failed = run()

        assertThat((failed as ImportOutcome.Failed).failure).isEqualTo(ImportFailure.Unreadable)
        assertThat(runBlocking { db.legacyImports().find(CapacitorImporter.SOURCE) }).isNull()
        LegacyFixtures.copy(LegacyFixtures.V7, legacy)
        assertThat(run()).isInstanceOf(ImportOutcome.Imported::class.java)
    }

    @Test fun recordsAFreshInstallOnce() {
        assertThat(run()).isEqualTo(ImportOutcome.NothingToImport)
        val record = runBlocking { db.legacyImports().find(CapacitorImporter.SOURCE) }
        assertThat(record?.status).isEqualTo("none")
        // A database that shows up later is not the Capacitor app's (it cannot run next to this one).
        LegacyFixtures.copy(LegacyFixtures.V7, legacy)
        assertThat(run()).isInstanceOf(ImportOutcome.AlreadyDone::class.java)
        assertThat(target.rows("machines")).isEmpty()
    }

    @Test fun readsRowsStillInTheWriteAheadLog() {
        LegacyFixtures.copy(LegacyFixtures.V7, legacy)
        val open = SQLiteDatabase.openDatabase(legacy.path, null, SQLiteDatabase.OPEN_READWRITE)
        open.enableWriteAheadLogging()
        open.rawQuery("PRAGMA wal_autocheckpoint = 0", null).use { it.moveToFirst() }
        open.execSQL("INSERT INTO machines (id, name, username) VALUES ('in-wal', 'Only in the WAL', '')")
        assertThat(File(legacy.path + "-wal").length()).isGreaterThan(0L)

        run() as ImportOutcome.Imported
        open.close()

        val names = target.query("SELECT name FROM machines WHERE id = 'in-wal'").use { if (it.moveToFirst()) it.getString(0) else null }
        assertThat(names).isEqualTo("Only in the WAL")
    }

    @Test fun reportsProgressPerTable() {
        LegacyFixtures.copy(LegacyFixtures.V7, legacy)
        val seen = mutableListOf<ImportProgress>()
        run { seen += it }
        assertThat(seen.mapNotNull { it.table }).isEqualTo(CapacitorImporter.TABLES)
        assertThat(seen.last()).isEqualTo(ImportProgress(null, CapacitorImporter.TABLES.size, CapacitorImporter.TABLES.size))
    }

    @Test fun keepsTheForeignKeysOfTheCapacitorSchema() {
        LegacyFixtures.copy(LegacyFixtures.V7, legacy)
        run()
        val nas = target.query("SELECT id FROM machines WHERE name = 'NAS'").use { it.moveToFirst(); it.getString(0) }

        target.execSQL("DELETE FROM machines WHERE id = ?", arrayOf(nas))

        for ((table, column) in listOf(
            "machine_addresses" to "machine_id",
            "favorites" to "machine_id",
            "manual_shares" to "machine_id",
            "transfers" to "dest_machine_id",
        )) {
            val left = target.query("SELECT COUNT(*) FROM $table WHERE $column = ?", arrayOf(nas)).use { it.moveToFirst(); it.getInt(0) }
            assertThat(left).isEqualTo(0)
        }
        // Culling rows have no foreign keys (local locations are not rows): deleting a machine leaves them to the app.
        assertThat(target.rows("cull_decisions").map { it.toString() }.any { nas in it }).isTrue()
    }

    @Test fun exposesTheImportedMachinesWithTheirAddressesInOrder() {
        LegacyFixtures.copy(LegacyFixtures.V7, legacy)
        run()
        val machines = runBlocking { db.machines().observeAll().first() }
        assertThat(machines.map { it.machine.name }).containsExactly("NAS", "Família — desktop ✓", "Old laptop").inOrder()
        assertThat(machines.first().ordered.map { it.host })
            .containsExactly("192.168.1.10", "100.101.102.103", "nas.tail1234.ts.net").inOrder()
        assertThat(machines.first().ordered.map { it.kind }).containsExactly("local", "tailscale", "tailscale").inOrder()
    }
}
