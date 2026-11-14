package dev.hfps.hoard.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.hfps.hoard.data.legacy.CapacitorImporter
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Room schema reproduces the Capacitor schema v7 (ADR 0019): for every table the same columns (type, NOT NULL,
 * default, primary key), the same indexes and the same foreign keys as a database made by `src/db/migrations.ts`.
 * CHECK constraints are the documented exception (Room cannot declare them).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SchemaParityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun everyCapacitorTableHasTheSameShape() {
        val room = Room.inMemoryDatabaseBuilder(context, HoardDatabase::class.java).allowMainThreadQueries().build()
        val file = LegacyFixtures.copy(LegacyFixtures.V7, File(context.cacheDir, "parity.db"))
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { legacy ->
                val native = room.openHelper.writableDatabase
                val legacyTables = legacy.strings("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'")
                assertThat(legacyTables).containsExactlyElementsIn(CapacitorImporter.TABLES)
                for (table in CapacitorImporter.TABLES) {
                    assertWithMessage("columns of $table").that(native.columns(table)).isEqualTo(legacy.columns(table))
                    assertWithMessage("indexes of $table").that(native.indexes(table)).isEqualTo(legacy.indexes(table))
                    assertWithMessage("foreign keys of $table").that(native.foreignKeys(table)).isEqualTo(legacy.foreignKeys(table))
                }
            }
        } finally {
            room.close()
            file.delete()
        }
    }

    // name -> "type notnull default pk"; a default written `(expr)` and `expr` are the same default.
    private fun SQLiteDatabase.columns(table: String) = rawQuery("PRAGMA table_info(`$table`)", null).use { it.columnShape() }
    private fun SupportSQLiteDatabase.columns(table: String) = query("PRAGMA table_info(`$table`)").use { it.columnShape() }

    private fun Cursor.columnShape(): Map<String, String> = buildMap {
        while (moveToNext()) {
            val default = getString(4)?.removeSurrounding("(", ")")
            put(getString(1), "${getString(2)} notnull=${getInt(3)} default=$default pk=${getInt(5)}")
        }
    }

    // Index column lists and uniqueness; names are not compared (SQLite names a UNIQUE table constraint itself).
    private fun SQLiteDatabase.indexes(table: String): Set<String> =
        strings("SELECT name FROM pragma_index_list('$table') WHERE origin != 'pk'").map { index ->
            val unique = strings("SELECT \"unique\" FROM pragma_index_list('$table') WHERE name = '$index'").single()
            "${strings("SELECT name FROM pragma_index_info('$index') ORDER BY seqno")} unique=$unique"
        }.toSet()

    private fun SupportSQLiteDatabase.indexes(table: String): Set<String> =
        strings("SELECT name FROM pragma_index_list('$table') WHERE origin != 'pk'").map { index ->
            val unique = strings("SELECT \"unique\" FROM pragma_index_list('$table') WHERE name = '$index'").single()
            "${strings("SELECT name FROM pragma_index_info('$index') ORDER BY seqno")} unique=$unique"
        }.toSet()

    private fun SQLiteDatabase.foreignKeys(table: String): Set<String> =
        strings("SELECT \"table\" || '.' || \"to\" || ' <- ' || \"from\" || ' on delete ' || on_delete FROM pragma_foreign_key_list('$table')").toSet()

    private fun SupportSQLiteDatabase.foreignKeys(table: String): Set<String> =
        strings("SELECT \"table\" || '.' || \"to\" || ' <- ' || \"from\" || ' on delete ' || on_delete FROM pragma_foreign_key_list('$table')").toSet()

    private fun SQLiteDatabase.strings(sql: String): List<String> =
        rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun SupportSQLiteDatabase.strings(sql: String): List<String> =
        query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
}
