package dev.hfps.hoard.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import dev.hfps.hoard.smb.bridge.ImportBridgeContract
import dev.hfps.hoard.smb.bridge.ImportBridgeContract.Keys
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Hoard 1's side of the import bridge (ADR 0019 "Side-by-side test build", [ImportBridgeContract]): Hoard Next, a
 * separate app on the same phone, cannot read this app's private database, so this provider hands it a read-only
 * snapshot when it asks.
 *
 * Guarded three times: the manifest requires the `signature` permission [ImportBridgeContract.PERMISSION] (granted only
 * to apps signed with Hoard's key), every call checks that the caller is a known package ([ImportBridgeContract.CALLERS])
 * whose certificate matches this app's, and only `r` opens are served. The snapshot is made per request with SQLite's
 * `VACUUM INTO` (a consistent copy while the WebView may be writing; a file copy under a read transaction before
 * Android 11) and unlinked as soon as its descriptor is open, so nothing stays in the cache.
 */
class ImportBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment) {
        ImportBridgeContract.PATH_DATABASE -> ImportBridgeContract.MIME_DATABASE
        ImportBridgeContract.PATH_SETTINGS -> ImportBridgeContract.MIME_SETTINGS
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("The import bridge is read-only")
        checkCaller()
        val context = context ?: throw FileNotFoundException("No context")
        val source = context.getDatabasePath(DATABASE)
        if (!source.exists()) throw FileNotFoundException("Hoard has no data yet")
        val dir = File(context.cacheDir, "import-bridge").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val snapshot = File(dir, "snapshot-${System.nanoTime()}.db")
        return try {
            snapshot(source, snapshot)
            val served = when (uri.lastPathSegment) {
                ImportBridgeContract.PATH_DATABASE -> snapshot
                ImportBridgeContract.PATH_SETTINGS -> File(dir, "settings-${System.nanoTime()}.json").apply { writeText(settingsJson(snapshot).toString()) }
                else -> throw FileNotFoundException("Unknown document ${uri.path}")
            }
            Log.i(TAG, "served ${uri.lastPathSegment} (${served.length()} bytes) to $callingPackage")
            ParcelFileDescriptor.open(served, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            // The open descriptor keeps the bytes readable; the names go now.
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun checkCaller() {
        val context = context ?: throw SecurityException("No context")
        val same = context.packageManager.checkSignatures(Binder.getCallingUid(), Process.myUid()) == PackageManager.SIGNATURE_MATCH
        if (!ImportBridgeContract.allowed(callingPackage, same)) {
            Log.w(TAG, "refused ${callingPackage ?: "an unknown caller"} (same signature: $same)")
            throw SecurityException("Not allowed")
        }
    }

    /** A consistent single-file copy of [source] (WAL content included) at [target]. */
    private fun snapshot(source: File, target: File) {
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // SQLite 3.28+: VACUUM INTO works on a read-only connection and writes a compact, WAL-free copy.
                db.execSQL("VACUUM INTO ?", arrayOf(target.path))
            } else {
                // Older SQLite: copy the files while a read transaction pins the snapshot, then fold the WAL in.
                db.beginTransaction()
                try {
                    source.copyTo(target, overwrite = true)
                    for (suffix in listOf("-wal", "-journal")) {
                        val companion = File(source.path + suffix)
                        if (companion.exists()) companion.copyTo(File(target.path + suffix), overwrite = true)
                    }
                } finally {
                    db.endTransaction()
                }
                SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { copy ->
                    copy.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                }
                File(target.path + "-wal").delete()
                File(target.path + "-shm").delete()
            }
        }
    }

    private fun settingsJson(snapshot: File): JSONObject {
        val context = context!!
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val out = JSONObject()
            .put(Keys.BRIDGE_VERSION, ImportBridgeContract.VERSION)
            .put(Keys.APP_VERSION, info.versionName)
            .put(Keys.APP_VERSION_CODE, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong())
            .put(Keys.EXPORTED_AT, SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()))
        SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            out.put(Keys.SCHEMA_VERSION, db.rawQuery("PRAGMA user_version", null).use { if (it.moveToFirst()) it.getInt(0) else 0 })
            val counts = JSONObject()
            for (table in ImportBridgeContract.COUNTED_TABLES) {
                runCatching { db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { if (it.moveToFirst()) counts.put(table, it.getInt(0)) } }
            }
            out.put(Keys.COUNTS, counts)
            val settings = JSONObject()
            runCatching {
                val marks = ImportBridgeContract.MIRRORED_SETTINGS.joinToString(",") { "?" }
                db.rawQuery("SELECT key, value FROM settings WHERE key IN ($marks)", ImportBridgeContract.MIRRORED_SETTINGS.toTypedArray()).use { c ->
                    while (c.moveToNext()) settings.put(c.getString(0), c.getString(1))
                }
            }
            out.put(Keys.SETTINGS, settings)
        }
        return out
    }

    // Read-only, file-based: no table interface.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("The import bridge is read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("The import bridge is read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("The import bridge is read-only")

    companion object {
        private const val TAG = "HoardImportBridge"

        /** The Capacitor SQLite database (`hoard` + the plugin's `SQLite.db` suffix). */
        const val DATABASE = "hoardSQLite.db"
    }
}
