package dev.hfps.hoard.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Room migrations of the native database (ADR 0003, ADR 0019), each checked against `core/data/schemas/`. */
object HoardMigrations {
    /**
     * 2.2.0: `reveal_copies`, the photos copied into MediaStore for Reveal (ADR 0010), and the automatic XMP setting
     * (`cullSaveXmp`) forced off: sidecars are written only by "Export XMP" (ADR 0011 amendment).
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_REVEAL_COPIES)
            db.execSQL(INDEX_REVEAL_COPIES)
            db.execSQL("UPDATE settings SET value = 'false' WHERE `key` = 'cullSaveXmp'")
        }
    }

    /**
     * 2.2.1: `cull_sidecar_reads`, what Hoard last read from each shot's XMP sidecar (ADR 0011 "First read of a
     * sidecar"). Starts empty: every sidecar's next read is a first read, which only fills Hoard's default fields.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_CULL_SIDECAR_READS)
        }
    }

    const val CREATE_CULL_SIDECAR_READS = "CREATE TABLE IF NOT EXISTS `cull_sidecar_reads` (`location_id` TEXT NOT NULL, " +
        "`share` TEXT NOT NULL DEFAULT '', `path` TEXT NOT NULL DEFAULT '', `shot_id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
        "`size` INTEGER, `modified_ms` INTEGER, `rating` INTEGER NOT NULL, `flag` TEXT NOT NULL, `label` TEXT, " +
        "PRIMARY KEY(`location_id`, `share`, `path`, `shot_id`))"

    const val CREATE_REVEAL_COPIES = "CREATE TABLE IF NOT EXISTS `reveal_copies` (`uri` TEXT NOT NULL, `source_key` TEXT NOT NULL, " +
        "`source_path` TEXT NOT NULL, `size` INTEGER NOT NULL, `modified_ms` INTEGER, " +
        "`copied_at` TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')), PRIMARY KEY(`uri`))"

    const val INDEX_REVEAL_COPIES = "CREATE INDEX IF NOT EXISTS `index_reveal_copies_source` ON `reveal_copies` (`source_key`, `source_path`)"

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}
