package dev.hfps.hoard.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** The Capacitor fixtures (core/data/fixtures, scripts/legacy-db/fixtures.ts) from the test APK's assets. */
object DeviceFixtures {
    fun copy(name: String, to: File): File {
        to.parentFile?.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets.open("legacy/$name").use { input ->
            to.outputStream().use { input.copyTo(it) }
        }
        return to
    }
}

fun SQLiteDatabase.rows(table: String): List<String> = rawQuery("SELECT * FROM `$table`", null).use { it.all() }

fun SupportSQLiteDatabase.rows(table: String): List<String> = query("SELECT * FROM `$table`").use { it.all() }

/** Rows as text with every column in name order, sorted: equal lists mean equal tables. */
private fun Cursor.all(): List<String> {
    val names = columnNames.sorted()
    val rows = mutableListOf<String>()
    while (moveToNext()) {
        rows += names.joinToString("|") { name ->
            val i = getColumnIndexOrThrow(name)
            when (getType(i)) {
                Cursor.FIELD_TYPE_NULL -> "null"
                Cursor.FIELD_TYPE_INTEGER -> "i:${getLong(i)}"
                Cursor.FIELD_TYPE_FLOAT -> "f:${getDouble(i)}"
                Cursor.FIELD_TYPE_BLOB -> "b:${getBlob(i).toList()}"
                else -> "s:${getString(i)}"
            }
        }
    }
    return rows.sorted()
}
