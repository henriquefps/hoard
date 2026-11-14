package dev.hfps.hoard.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.security.MessageDigest

/** The Capacitor databases made by scripts/legacy-db/fixtures.ts (test resources under `legacy/`). */
object LegacyFixtures {
    const val V7 = "hoard-v7.db"
    const val V1 = "hoard-v1.db"
    const val V1_UPGRADED = "hoard-v1-upgraded.db"
    const val V99 = "hoard-v99.db"

    fun copy(name: String, to: File): File {
        to.parentFile?.mkdirs()
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("legacy/$name")) { "fixture $name" }
        stream.use { input -> to.outputStream().use { input.copyTo(it) } }
        return to
    }

    fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

/** Every row of [table], all columns as SQLite values, in a stable order (for table-to-table comparisons). */
fun SQLiteDatabase.rows(table: String): List<List<Any?>> = rawQuery("SELECT * FROM `$table`", null).use { it.all() }

fun SupportSQLiteDatabase.rows(table: String): List<List<Any?>> = query("SELECT * FROM `$table`").use { it.all() }

private fun Cursor.all(): List<List<Any?>> {
    val names = columnNames.sorted()
    val rows = mutableListOf<List<Any?>>()
    while (moveToNext()) {
        rows += names.map { name ->
            val i = getColumnIndexOrThrow(name)
            when (getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
                Cursor.FIELD_TYPE_BLOB -> getBlob(i).toList()
                else -> getString(i)
            }
        }
    }
    return rows.sortedBy { it.toString() }
}
