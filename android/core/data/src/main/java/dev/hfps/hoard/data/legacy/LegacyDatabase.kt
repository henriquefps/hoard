package dev.hfps.hoard.data.legacy

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The Capacitor app's database file (ADR 0003, ADR 0019). `@capacitor-community/sqlite` opens database `hoard`
 * (`src/db/client.ts`) as `<databases dir>/hoardSQLite.db` (`CapacitorSQLite.createConnection` appends `SQLite.db`),
 * through SQLCipher with an empty key and `androidIsEncryption: false`: a plain SQLite file. Same package, same
 * data directory, so the native app finds it where the Capacitor app left it.
 */
object LegacyDatabase {
    const val FILE_NAME = "hoardSQLite.db"

    /** The files SQLite may keep next to a database: a WAL log, its index, a rollback journal. */
    private val COMPANIONS = listOf("-wal", "-shm", "-journal")

    /**
     * Copies [source] and its companion files to [dir] and returns the copy. The importer only ever opens the copy,
     * so the Capacitor file stays exactly as it was (the upgrade and the WAL checkpoint happen on the copy).
     */
    fun snapshot(source: File, dir: File): File {
        dir.mkdirs()
        val copy = File(dir, source.name)
        deleteSnapshot(copy)
        source.copyTo(copy, overwrite = true)
        for (suffix in COMPANIONS) {
            val companion = File(source.path + suffix)
            if (companion.exists()) companion.copyTo(File(copy.path + suffix), overwrite = true)
        }
        return copy
    }

    fun deleteSnapshot(copy: File) {
        copy.delete()
        for (suffix in COMPANIONS) File(copy.path + suffix).delete()
    }

    /**
     * Brings [db] (a snapshot) from its `user_version` to [LegacyMigrations.LATEST_VERSION] the way `migrate()` in
     * `src/db/migrations.ts` does: each pending migration with its version bump in one transaction, foreign keys on.
     * Returns the version it started from.
     */
    fun upgrade(db: SQLiteDatabase, migrations: List<LegacyMigration> = LegacyMigrations.ALL): Int {
        val start = db.version
        val latest = migrations.lastOrNull()?.version ?: 0
        if (start > latest) throw LegacyImportException(ImportFailure.TooNew, "database version $start, importer knows $latest")
        for (migration in migrations.filter { it.version > start }) {
            db.beginTransaction()
            try {
                for (statement in SqlStatements.split(migration.sql)) db.execSQL(statement)
                db.version = migration.version
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return start
    }
}

/** Splits a migration script into statements for `execSQL`, which runs one statement per call. */
object SqlStatements {
    /**
     * Statements of [sql] separated by `;` outside string literals, quoted identifiers and comments; blank ones
     * dropped. Enough for the migrations in `src/db/migrations.ts` (no triggers, whose bodies contain `;`).
     */
    fun split(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' || c == '"' || c == '`' || c == '[' -> {
                    val close = if (c == '[') ']' else c
                    val end = sql.indexOf(close, i + 1).let { if (it < 0) sql.length - 1 else it }
                    current.append(sql, i, end + 1)
                    i = end + 1
                    continue
                }
                c == '-' && sql.startsWith("--", i) -> {
                    val end = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                    i = end
                    continue
                }
                c == '/' && sql.startsWith("/*", i) -> {
                    val end = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                    i = end
                    continue
                }
                c == ';' -> {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add)
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        current.toString().trim().takeIf { it.isNotEmpty() }?.let(statements::add)
        return statements
    }
}
