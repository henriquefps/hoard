package dev.hfps.hoard.data.legacy

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.data.LegacyImportEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Why an import did not happen. */
enum class ImportFailure {
    /** The Capacitor database is newer than the migrations this build knows (a newer Capacitor app wrote it). */
    TooNew,

    /** A table the native schema has is missing, or its columns differ from the native table's. */
    SchemaMismatch,

    /** The file could not be opened or read (corrupt, not SQLite). */
    Unreadable,

    /** Anything else (disk full while copying, a constraint the Capacitor data broke). */
    Other,
}

class LegacyImportException(val failure: ImportFailure, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Progress of a running import: [table] is being copied, [done] of [total] tables finished. */
data class ImportProgress(val table: String?, val done: Int, val total: Int)

sealed interface ImportOutcome {
    /** No Capacitor database on this install (a fresh install): recorded, never checked again. */
    data object NothingToImport : ImportOutcome

    /** This install already imported (or recorded that there was nothing to import). */
    data class AlreadyDone(val record: LegacyImportEntity) : ImportOutcome

    /** Imported now; [record] holds the counts. */
    data class Imported(val record: LegacyImportEntity) : ImportOutcome

    /** Nothing was written; the next launch tries again. */
    data class Failed(val failure: ImportFailure, val message: String) : ImportOutcome
}

/**
 * The one-time import of the Capacitor app's database into [database] (ADR 0019).
 *
 * - **Once per install.** The `legacy_imports` row (source `capacitor`) is written in the same transaction as the
 *   rows; while it exists the importer does nothing, so data the native app writes later is never overwritten.
 * - **Idempotent.** The copy replaces the imported tables' rows inside one transaction, so a run that is retried
 *   (after a failure or a crash before commit) produces the same database as a single run.
 * - **Read-only on the source.** It works on a snapshot of `hoardSQLite.db` (+ WAL / journal) in [workDir],
 *   upgraded there with the Capacitor migrations ([LegacyMigrations]) if it is older than v7.
 * - **Versioned.** [IMPORTER_VERSION] is recorded with the counts and the source version.
 *
 * Passwords are not in the database (ADR 0003): the Keystore entries and the encrypted files under the same package
 * keep working as they are. Theme, palette and language lived in the WebView's localStorage and are not imported.
 */
class CapacitorImporter(
    private val database: HoardDatabase,
    private val legacyFile: File,
    private val workDir: File,
    private val now: () -> String = ::isoNow,
) {
    constructor(context: Context, database: HoardDatabase) : this(
        database = database,
        legacyFile = context.getDatabasePath(LegacyDatabase.FILE_NAME),
        workDir = File(context.noBackupFilesDir, "legacy-import"),
    )

    suspend fun importIfNeeded(onProgress: (ImportProgress) -> Unit = {}): ImportOutcome = withContext(Dispatchers.IO) {
        database.legacyImports().find(SOURCE)?.let { return@withContext ImportOutcome.AlreadyDone(it) }
        if (!legacyFile.exists()) {
            val record = record(status = "none", legacyVersion = null, counts = emptyMap())
            database.runInTransaction { writeRecord(database.openHelper.writableDatabase, record) }
            return@withContext ImportOutcome.NothingToImport
        }
        try {
            ImportOutcome.Imported(copy(onProgress))
        } catch (e: LegacyImportException) {
            ImportOutcome.Failed(e.failure, e.message.orEmpty())
        } catch (e: SQLiteException) {
            ImportOutcome.Failed(ImportFailure.Unreadable, e.message.orEmpty())
        } catch (e: java.io.IOException) {
            ImportOutcome.Failed(ImportFailure.Other, e.message.orEmpty())
        }
    }

    private fun copy(onProgress: (ImportProgress) -> Unit): LegacyImportEntity {
        val snapshot = LegacyDatabase.snapshot(legacyFile, workDir)
        try {
            val legacy = try {
                SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READWRITE)
            } catch (e: SQLiteException) {
                throw LegacyImportException(ImportFailure.Unreadable, e.message.orEmpty(), e)
            }
            legacy.use { source ->
                source.setForeignKeyConstraintsEnabled(true)
                val legacyVersion = try {
                    LegacyDatabase.upgrade(source)
                } catch (e: SQLiteException) {
                    throw LegacyImportException(ImportFailure.Unreadable, e.message.orEmpty(), e)
                }
                val counts = linkedMapOf<String, Int>()
                val record = database.runInTransaction<LegacyImportEntity> {
                    val target = database.openHelper.writableDatabase
                    // The Capacitor data satisfied its own foreign keys; checking them at commit lets the tables be
                    // emptied and refilled in any order.
                    target.execSQL("PRAGMA defer_foreign_keys = ON")
                    for (table in TABLES.asReversed()) target.execSQL("DELETE FROM $table")
                    TABLES.forEachIndexed { index, table ->
                        onProgress(ImportProgress(table, index, TABLES.size))
                        counts[table] = copyTable(source, target, table)
                    }
                    val result = record(status = "imported", legacyVersion = legacyVersion, counts = counts)
                    writeRecord(target, result)
                    result
                }
                onProgress(ImportProgress(null, TABLES.size, TABLES.size))
                return record
            }
        } finally {
            LegacyDatabase.deleteSnapshot(snapshot)
        }
    }

    /** Copies every row of [table], column for column with SQLite's own types (text, integer, real, blob, null). */
    private fun copyTable(source: SQLiteDatabase, target: SupportSQLiteDatabase, table: String): Int {
        val columns = columnsOf(target, table)
        val legacyColumns = source.rawQuery("PRAGMA table_info(`$table`)", null).use { c -> c.strings(1) }
        if (legacyColumns.isEmpty()) {
            throw LegacyImportException(ImportFailure.SchemaMismatch, "table $table is missing")
        }
        if (legacyColumns.toSet() != columns.toSet()) {
            throw LegacyImportException(
                ImportFailure.SchemaMismatch,
                "table $table: columns $legacyColumns, native ${columns.sorted()}",
            )
        }
        val select = columns.joinToString(", ") { "`$it`" }
        var rows = 0
        source.rawQuery("SELECT $select FROM `$table`", null).use { cursor ->
            while (cursor.moveToNext()) {
                val values = ContentValues(columns.size)
                columns.forEachIndexed { i, column ->
                    when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> values.putNull(column)
                        Cursor.FIELD_TYPE_INTEGER -> values.put(column, cursor.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> values.put(column, cursor.getDouble(i))
                        Cursor.FIELD_TYPE_BLOB -> values.put(column, cursor.getBlob(i))
                        else -> values.put(column, cursor.getString(i))
                    }
                }
                target.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
                rows++
            }
        }
        return rows
    }

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c -> c.strings(1) }

    private fun Cursor.strings(column: Int): List<String> = buildList { while (moveToNext()) add(getString(column)) }

    private fun record(status: String, legacyVersion: Int?, counts: Map<String, Int>) = LegacyImportEntity(
        source = SOURCE,
        status = status,
        importerVersion = IMPORTER_VERSION,
        legacyVersion = legacyVersion,
        rowCounts = JSONObject(counts as Map<*, *>).toString(),
        importedAt = now(),
    )

    private fun writeRecord(db: SupportSQLiteDatabase, record: LegacyImportEntity) {
        val values = ContentValues().apply {
            put("source", record.source)
            put("status", record.status)
            put("importer_version", record.importerVersion)
            if (record.legacyVersion == null) putNull("legacy_version") else put("legacy_version", record.legacyVersion)
            put("row_counts", record.rowCounts)
            put("imported_at", record.importedAt)
        }
        db.insert("legacy_imports", SQLiteDatabase.CONFLICT_REPLACE, values)
    }

    companion object {
        const val SOURCE = "capacitor"

        /** Bump when the import changes what it writes; recorded with every import. */
        const val IMPORTER_VERSION = 1

        /** The Capacitor tables, parents before children (machines before the rows that reference them). */
        val TABLES = listOf(
            "machines",
            "machine_addresses",
            "favorites",
            "manual_shares",
            "transfers",
            "settings",
            "cull_sessions",
            "cull_decisions",
            "cull_destinations",
            "cull_analysis",
            "cull_sidecars",
        )
    }
}

/** Now as the Capacitor app writes times: ISO-8601 UTC with milliseconds (`2026-11-14T10:00:00.000Z`). */
fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(Date())
