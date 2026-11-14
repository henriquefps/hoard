package dev.hfps.hoard.smb.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Log
import dev.hfps.hoard.smb.IsoTime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One file another app shared. */
data class IncomingItem(val index: Int, val uri: Uri, val name: String, val size: Long, val mimeType: String?)

/** One `ACTION_SEND` / `ACTION_SEND_MULTIPLE` (contract `SharedFiles`). */
data class IncomingShare(
    val shareId: String,
    val items: List<IncomingItem>,
    val text: String?,
    val receivedAt: String,
    /** Not offered to the UI any more (saved or dismissed); kept while a transfer may read it. */
    val cleared: Boolean = false,
    /** URIs refused by [SharedUris] (not `content://`, or Hoard's own providers). */
    val rejected: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("shareId", shareId)
        put("items", JSONArray().apply {
            items.forEach {
                put(JSONObject().put("index", it.index).put("uri", it.uri.toString()).put("name", it.name).put("size", it.size).putOpt("mimeType", it.mimeType))
            }
        })
        putOpt("text", text)
        put("receivedAt", receivedAt)
        put("cleared", cleared)
        if (rejected > 0) put("rejected", rejected)
    }

    companion object {
        fun fromJson(json: JSONObject): IncomingShare {
            val array = json.getJSONArray("items")
            return IncomingShare(
                shareId = json.getString("shareId"),
                items = (0 until array.length()).map {
                    val o = array.getJSONObject(it)
                    IncomingItem(o.getInt("index"), Uri.parse(o.getString("uri")), o.getString("name"), o.optLong("size", -1), o.optString("mimeType").takeIf { s -> o.has("mimeType") && s.isNotEmpty() })
                },
                text = json.optString("text").takeIf { json.has("text") },
                receivedAt = json.getString("receivedAt"),
                cleared = json.optBoolean("cleared"),
                rejected = json.optInt("rejected"),
            )
        }
    }
}

/**
 * Files shared to Hoard (ADR 0007, "Share in"). The intent's URIs are read natively through the
 * ContentResolver; bytes never cross the bridge. The read grant Android gives with the intent lasts
 * while Hoard's task lives (a persistable grant is taken when the other app offers one), so the
 * list is kept in a small file too: a transfer that resumes after the process was restarted still
 * finds its URIs (and fails with `PERMISSION_REVOKED` when the grant is gone).
 */
object IncomingShares {
    const val TAG = "HoardSmb"
    private const val KEEP = 8

    private val shares = LinkedHashMap<String, IncomingShare>()
    private var file: AtomicFile? = null
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(IncomingShare) -> Unit>()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        file = AtomicFile(File(context.applicationContext.filesDir, "hoard_incoming.json"))
        runCatching {
            val array = JSONArray(file!!.readFully().toString(Charsets.UTF_8))
            for (i in 0 until array.length()) IncomingShare.fromJson(array.getJSONObject(i)).let { shares[it.shareId] = it }
        }
    }

    fun addListener(listener: (IncomingShare) -> Unit) = listeners.add(listener)
    fun removeListener(listener: (IncomingShare) -> Unit) = listeners.remove(listener)

    @Synchronized
    fun get(shareId: String): IncomingShare? = shares[shareId]

    /** The latest share the UI has not handled. */
    @Synchronized
    fun latest(): IncomingShare? = shares.values.lastOrNull { !it.cleared }

    @Synchronized
    fun clear(shareId: String) {
        shares[shareId]?.let { shares[shareId] = it.copy(cleared = true) }
        save()
    }

    /** Whether [intent] is a share Hoard should take. */
    fun isShare(intent: Intent?): Boolean =
        intent != null && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE)

    /**
     * Take a share intent: resolve every URI's name and size, keep the read grant when possible,
     * remember it and tell the listeners. Returns null for an intent with nothing in it.
     */
    fun receive(context: Context, intent: Intent): IncomingShare? {
        if (!isShare(intent)) return null
        val resolver = context.contentResolver
        val uris = ArrayList<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            streamList(intent)?.let(uris::addAll)
        } else {
            stream(intent)?.let(uris::add)
        }
        // Some apps only put the URIs in ClipData.
        if (uris.isEmpty()) intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        if (uris.isEmpty() && text == null) return null
        // Only other apps' content: never a file:// path or one of Hoard's own providers, which
        // Hoard would otherwise read with its own permissions (private files, its grants) and copy
        // wherever the user saves ("share intent file theft").
        val (accepted, refused) = uris.distinct().partition { SharedUris.isAllowed(context, it) }
        refused.forEach { Log.w(TAG, "Refused a shared URI: ${SharedUris.describe(it)}") }
        val items = accepted.mapIndexed { index, uri ->
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val (name, size) = describe(resolver, uri, index)
            IncomingItem(index, uri, name, size, runCatching { resolver.getType(uri) }.getOrNull() ?: intent.type)
        }
        val share = IncomingShare(UUID.randomUUID().toString(), items, text, IsoTime.now(), rejected = refused.size)
        synchronized(this) {
            shares[share.shareId] = share
            while (shares.size > KEEP) shares.remove(shares.keys.first())
            save()
        }
        listeners.forEach { runCatching { it(share) } }
        return share
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    @Suppress("DEPRECATION")
    private fun streamList(intent: Intent): List<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
    }

    /** Display name and size from the provider; a name from the URI and -1 when it does not say. */
    fun describe(resolver: ContentResolver, uri: Uri, index: Int): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        if (size < 0) {
            size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it >= 0 } ?: -1
        }
        val clean = SharedNames.clean(name ?: uri.lastPathSegment, index)
        return clean to size
    }

    private fun save() {
        val target = file ?: return
        val array = JSONArray()
        shares.values.forEach { array.put(it.toJson()) }
        val out = try { target.startWrite() } catch (e: Exception) { Log.w(TAG, "Could not save shares", e); return }
        try {
            out.write(array.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(out)
        } catch (e: Exception) {
            target.failWrite(out)
        }
    }
}

/**
 * Which shared URIs Hoard reads (ADR 0007, "Share in"). Another app chooses what it puts in a share
 * intent, and Hoard opens it with Hoard's own identity, so only `content://` URIs of **other** apps'
 * providers are taken: `file://` (Hoard's private `/data/data/dev.hfps.hoard/…`, or anything all
 * files access can read) and Hoard's own authorities (FileProvider with its `storage/` root, the
 * stream provider, any other `dev.hfps.hoard…` provider) are refused. Applied when a share arrives
 * and again before each read.
 */
object SharedUris {
    enum class Refusal { NOT_CONTENT, NO_AUTHORITY, OWN_PROVIDER }

    /**
     * Pure rule (JVM-tested): [ownerPackage] is the package that serves [authority] (null when
     * Android does not know it). Returns why the URI is refused, or null when it may be read.
     */
    fun check(scheme: String?, authority: String?, ownPackage: String, ownerPackage: String?): Refusal? {
        if (!scheme.equals("content", ignoreCase = true)) return Refusal.NOT_CONTENT
        val auth = authority?.substringAfterLast('@')?.trim().orEmpty()
        if (auth.isEmpty()) return Refusal.NO_AUTHORITY
        val own = ownPackage.lowercase(java.util.Locale.ROOT)
        val lower = auth.lowercase(java.util.Locale.ROOT)
        // Every authority in Hoard's manifest starts with the application id; a provider owned by
        // Hoard under another name is caught by the owner check.
        if (lower == own || lower.startsWith("$own.") || lower.startsWith("$own-")) return Refusal.OWN_PROVIDER
        if (ownerPackage != null && ownerPackage.equals(ownPackage, ignoreCase = true)) return Refusal.OWN_PROVIDER
        return null
    }

    fun refusal(context: Context, uri: Uri): Refusal? {
        val authority = uri.authority
        val owner = authority?.let { auth ->
            runCatching { context.packageManager.resolveContentProvider(auth.substringAfterLast('@'), 0)?.packageName }.getOrNull()
        }
        return check(uri.scheme, authority, context.packageName, owner)
    }

    fun isAllowed(context: Context, uri: Uri) = refusal(context, uri) == null

    /** For logs: scheme and authority only (never the path, which may be private). */
    fun describe(uri: Uri) = "${uri.scheme}://${uri.authority ?: ""}"
}

/** File names from other apps, made safe for any destination (pure, JVM-tested). */
object SharedNames {
    private val FORBIDDEN = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    fun clean(raw: String?, index: Int): String {
        val base = raw?.substringAfterLast('/')?.replace(FORBIDDEN, "_")?.trim()?.trimEnd('.', ' ').orEmpty()
        val name = if (base.isEmpty() || base == "." || base == "..") "shared-${index + 1}" else base
        return if (name.length > 200) name.take(200) else name
    }
}
