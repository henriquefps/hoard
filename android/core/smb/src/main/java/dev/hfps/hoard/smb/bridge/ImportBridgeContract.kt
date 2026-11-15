package dev.hfps.hoard.smb.bridge

/**
 * The import bridge between Hoard 1 (the Capacitor `:app`, from 1.11.0) and Hoard Next (`:native`'s side-by-side
 * build, `dev.hfps.hoard.next`), ADR 0019 "Side-by-side test build". Both apps compile this one contract.
 *
 * Hoard 1 serves, through a content provider guarded by a `signature` permission (only an app signed with the same key
 * gets in) and a check of the calling package, two read-only documents:
 * - [PATH_DATABASE]: a consistent snapshot of its database (`hoardSQLite.db`, one file, no WAL), made per request and
 *   deleted as soon as the descriptor is open;
 * - [PATH_SETTINGS]: a JSON summary (app version, schema version, counts, and the appearance Hoard 1 mirrors from the
 *   WebView's localStorage into `settings`).
 *
 * Nothing is writable and no password crosses: the Keystore key behind Hoard 1's saved passwords is bound to Hoard 1.
 */
object ImportBridgeContract {
    /** Hoard 1's application id (release and debug). */
    const val HOARD1_PACKAGE = "dev.hfps.hoard"

    /** The only callers Hoard 1 answers: Hoard Next (release and debug builds share the id). */
    val CALLERS = setOf("dev.hfps.hoard.next")

    const val AUTHORITY = "dev.hfps.hoard.importbridge"
    const val PERMISSION = "dev.hfps.hoard.permission.IMPORT_BRIDGE"

    const val PATH_DATABASE = "database"
    const val PATH_SETTINGS = "settings.json"

    /** Bumped when a document changes shape; Hoard Next refuses a bridge newer than it knows. */
    const val VERSION = 1

    const val MIME_DATABASE = "application/vnd.sqlite3"
    const val MIME_SETTINGS = "application/json"

    /** Keys of [PATH_SETTINGS]. */
    object Keys {
        const val BRIDGE_VERSION = "bridgeVersion"
        const val APP_VERSION = "appVersion"
        const val APP_VERSION_CODE = "appVersionCode"
        const val SCHEMA_VERSION = "schemaVersion"
        const val COUNTS = "counts"
        const val SETTINGS = "settings"
        const val EXPORTED_AT = "exportedAt"
    }

    /** The settings rows Hoard 1 mirrors from localStorage (appearanceMirror.ts) and lists in [PATH_SETTINGS]. */
    val MIRRORED_SETTINGS = listOf("themeMode", "palette", "language", "textViewer")

    /** Tables counted in [PATH_SETTINGS] (what the "Import from Hoard" screen says it will bring). */
    val COUNTED_TABLES = listOf("machines", "favorites", "transfers", "cull_decisions", "cull_analysis")

    /** Whether a caller may read: a known package with the same signing certificate as Hoard 1. */
    fun allowed(callingPackage: String?, sameSignature: Boolean): Boolean =
        sameSignature && callingPackage != null && callingPackage in CALLERS

    fun uri(path: String): String = "content://$AUTHORITY/$path"
}
