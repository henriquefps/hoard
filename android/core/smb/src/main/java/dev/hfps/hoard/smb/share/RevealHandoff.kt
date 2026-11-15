package dev.hfps.hoard.smb.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import dev.hfps.hoard.image.format.ImageFormats
import dev.hfps.hoard.image.format.ImageKind
import dev.hfps.hoard.smb.CredentialStore
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.viewer.Opener

/**
 * Which installed Reveal gets a hand-off (pure, JVM-tested): the release build (`dev.hfps.reveal`) when installed,
 * else the debug one (`dev.hfps.reveal.debug`), else none. Other apps that answer the action are never chosen: the
 * read grants go to Reveal only, and an explicit package means no chooser.
 */
object RevealPackages {
    const val RELEASE = "dev.hfps.reveal"
    const val DEBUG = "dev.hfps.reveal.debug"

    fun choose(installed: Collection<String>): String? = when {
        RELEASE in installed -> RELEASE
        DEBUG in installed -> DEBUG
        else -> null
    }
}

/**
 * "Edit in Reveal" (ADR 0010): hands photos to Reveal, the owner's RAW editor, with its hand-off contract (Reveal ADR
 * 0011): `dev.hfps.reveal.action.DEVELOP` sent to one Reveal package explicitly ([RevealPackages]: the release build
 * when installed, else the debug one; never a chooser), the photos as `ClipData` (in order, the RAW and the rendering
 * of a pair next to each other), a plain **read** grant on exactly those URIs, and the `TITLE` / `INITIAL_URI` /
 * `CULLING` extras; photos the paired server holds go by reference (`SERVER_ITEMS`, no URI, no grant).
 *
 * URIs (ADR 0010 amendment "2.2.0: local copies, no streaming for new hand-offs"): only `content://media/…` URIs, a
 * photo's own MediaStore URI on the phone or the URI of the copy Hoard made into `Pictures/Hoard/…` (or
 * `Download/Hoard/…`), chosen by the caller (`RevealCopies`). No persistable grant, no write grant, no sidecar items.
 * The hand-off links of [HandoffServer] (`content://<app>.stream/h/…`) are no longer made; the ones already handed
 * out keep being served.
 */
class RevealHandoff(private val context: Context, @Suppress("unused") private val opener: Opener, @Suppress("unused") private val credentials: CredentialStore) {

    /** The Reveal package hand-offs go to (release first), or null when none is installed. */
    fun target(): String? = RevealPackages.choose(
        context.packageManager.queryIntentActivities(probeIntent(), 0).mapNotNull { it.activityInfo?.packageName },
    )

    /** Reveal (release or debug build) is installed and handles the hand-off action. */
    fun isAvailable(): Boolean = target() != null

    /** Starts Reveal. `NO_APP` when it is not installed (or was uninstalled since the UI checked). */
    fun launch(activity: Activity, files: List<Outgoing>, title: String?, initialPath: String?, culling: Map<String, Culling> = emptyMap()) =
        launchMixed(activity, files, title, initialPath, culling, server = null)

    /** The installed Reveal's versionCode (0 when none): server references need [MIN_SERVER_REF_VERSION_CODE]. */
    fun targetVersionCode(): Long {
        val target = target() ?: return 0
        return runCatching {
            val info = context.packageManager.getPackageInfo(target, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(0)
    }

    /** Reveal takes server references (Reveal ADR 0011 amendment W3/H: 0.9.0, versionCode 90000, and later). */
    fun acceptsServerReferences(): Boolean = targetVersionCode() >= MIN_SERVER_REF_VERSION_CODE

    /** A hand-off ready to start: the Reveal package and its intents, in order (one, or a batch). */
    class Prepared(val target: String, val intents: List<Intent>)

    /**
     * One hand-off of URI items ([files]: `content://media/…` URIs, ClipData, a read grant, `CULLING`) and server items
     * ([server]: references to items of the paired hoard-server, no URI, no grant, no culling keys), built by
     * [buildIntents]. Reads the package manager (the target and its versionCode): call it off the main thread.
     */
    fun prepare(files: List<Outgoing>, title: String?, initialPath: String?, culling: Map<String, Culling>, server: ServerItems?): Prepared {
        val target = target() ?: smbFail(ErrorCode.NO_APP, "Reveal is not installed")
        return Prepared(target, buildIntents(target, targetVersionCode(), files, title, initialPath, culling, server))
    }

    /** Starts a [prepared] hand-off from [activity] (Hoard in the foreground). `NO_APP` when Reveal is gone meanwhile. */
    fun start(activity: Activity, prepared: Prepared) {
        val first = prepared.intents.first()
        if (first.resolveActivity(context.packageManager) == null) smbFail(ErrorCode.NO_APP, "Reveal is not installed")
        try {
            if (prepared.intents.size == 1) activity.startActivity(first) else activity.startActivities(prepared.intents.toTypedArray())
        } catch (e: ActivityNotFoundException) {
            smbFail(ErrorCode.NO_APP, "Reveal is not installed")
        }
    }

    /** [prepare] then [start] (tests and callers already off the main thread for the first part). */
    fun launchMixed(
        activity: Activity,
        files: List<Outgoing>,
        title: String?,
        initialPath: String?,
        culling: Map<String, Culling>,
        server: ServerItems?,
    ) = start(activity, prepare(files, title, initialPath, culling, server))

    /** Items of the paired hoard-server sent by reference (Reveal ADR 0011 amendment W3/H). */
    data class ServerItems(val serverId: String, val items: List<ServerItem>, val initialItemId: String? = null)

    /**
     * A shot's decision as Reveal's `CULLING` extra carries it: stars 0..5 and `pick` / `reject` / `none`; since
     * `CULLING_VERSION` 2, when each field was decided (ms) and by which server device (only when Hoard is paired).
     */
    data class Culling(
        val rating: Int,
        val flag: String,
        val label: String? = null,
        val ratingModifiedAt: Long? = null,
        val flagModifiedAt: Long? = null,
        val labelModifiedAt: Long? = null,
        val ratingDevice: String? = null,
        val flagDevice: String? = null,
        val labelDevice: String? = null,
    )

    /** One shot by reference: its item id, the main file's fingerprint, every member's (pairs) and the main name. */
    data class ServerItem(val itemId: String, val fingerprint: String?, val fingerprints: List<String>, val name: String)

    /** One photo to send: its path in the location and the `content://media/…` URI Reveal reads. */
    data class Outgoing(val path: String, val uri: Uri)

    /** What the intent carries (pure, JVM-tested). */
    data class Plan<T>(val items: List<T>, val title: String?, val initial: T?, val flags: Int)

    companion object {
        /** URI items per intent: with their `CULLING` bundles, 200 stay far under the ~1 MB Binder buffer. */
        const val MAX_URI_ITEMS = 200

        /**
         * The intents of one hand-off (pure apart from `Intent`/`Bundle`, Robolectric-tested). URI items go
         * [MAX_URI_ITEMS] per intent, whole shots only ([shotChunks]), each intent with the ClipData, read grant and `CULLING` of its own URIs; the chunk
         * holding [initialPath] goes first, with `INITIAL_URI`. Server items go [MAX_SERVER_ITEMS] per intent. Intent i
         * carries URI chunk i and server batch i (when there are that many), so the count is the larger of the two;
         * more than one intent: every one has `BATCH_INDEX` (0 = the first, with `INITIAL_ITEM` / `INITIAL_URI`) and
         * `BATCH_COUNT`. [versionCode] is the target Reveal's (the `CULLING` version).
         */
        fun buildIntents(
            target: String,
            versionCode: Long,
            files: List<Outgoing>,
            title: String?,
            initialPath: String?,
            culling: Map<String, Culling>,
            server: ServerItems?,
        ): List<Intent> {
            val batches = server?.let { serverBatches(it.items) }.orEmpty()
            if (files.isEmpty() && batches.isEmpty()) smbFail(ErrorCode.UNKNOWN, "No photos to edit")
            val cleanTitle = title?.trim()?.take(MAX_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            val chunks = shotChunks(files).toMutableList()
            val initialChunk = initialPath?.let { p -> chunks.indexOfFirst { c -> c.any { it.path == p } } } ?: -1
            if (initialChunk > 0) chunks.add(0, chunks.removeAt(initialChunk))
            val version = cullingVersionFor(versionCode)
            val count = maxOf(chunks.size, batches.size)
            val intents = (0 until count).map { i ->
                val intent = Intent(ACTION).setType("image/*").setPackage(target)
                cleanTitle?.let { intent.putExtra(EXTRA_TITLE, it) }
                chunks.getOrNull(i)?.let { chunk ->
                    val plan = plan(chunk.map { it.path to it.uri }, cleanTitle, initialPath.takeIf { i == 0 })
                    intent.addFlags(plan.flags)
                    plan.initial?.let { intent.putExtra(EXTRA_INITIAL_URI, it) }
                    val values = cullingValues(chunk.map { it.path to it.uri.toString() }, culling)
                    if (values.isNotEmpty()) {
                        // Version 2 (the `*Device` keys) only to a Reveal that reads it (0.9.0+); older ones get version 1.
                        intent.putExtra(EXTRA_CULLING_VERSION, version)
                        intent.putExtra(EXTRA_CULLING, Bundle().apply { values.forEach { (uri, value) -> putBundle(uri, cullingBundle(value, version)) } })
                    }
                    // The ClipData carries the read grants: exactly these URIs, nothing else (no folder, no tree).
                    val clip = ClipData(ClipDescription(plan.title ?: "Reveal", arrayOf("image/*", "application/octet-stream")), ClipData.Item(plan.items[0]))
                    plan.items.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                    intent.clipData = clip
                }
                batches.getOrNull(i)?.let { batch ->
                    intent.putExtra(EXTRA_SERVER_REF_VERSION, SERVER_REF_VERSION)
                    intent.putExtra(EXTRA_SERVER_ID, server!!.serverId)
                    intent.putParcelableArrayListExtra(EXTRA_SERVER_ITEMS, ArrayList(batch.map(::serverItemBundle)))
                    if (i == 0) server.initialItemId?.takeIf { id -> server.items.any { it.itemId == id } }?.let { intent.putExtra(EXTRA_INITIAL_ITEM, it) }
                }
                intent
            }
            // More than one intent: each says where it stands in the batch (Reveal ADR 0011 W3/H "Batches over 500").
            if (intents.size > 1) intents.forEachIndexed { i, intent ->
                intent.putExtra(EXTRA_BATCH_INDEX, i)
                intent.putExtra(EXTRA_BATCH_COUNT, intents.size)
            }
            return intents
        }

        /** Shot identity, as culling and ImageFormats pair them: the folder plus the case-insensitive stem. */
        private fun shotKey(path: String): String =
            path.substringBeforeLast('/', "") + "/" + ImageFormats.stem(path).lowercase()

        /**
         * Chunks of at most [MAX_URI_ITEMS] items that never split a shot (Reveal pairs RAW + JPEG only within one
         * intent). Whole shots fill a chunk in order of first appearance; a shot that would cross the limit starts the
         * next chunk; a shot larger than the limit goes alone in a chunk of its own.
         */
        internal fun shotChunks(files: List<Outgoing>): List<List<Outgoing>> {
            val shots = LinkedHashMap<String, MutableList<Outgoing>>()
            files.forEach { shots.getOrPut(shotKey(it.path)) { mutableListOf() }.add(it) }
            val chunks = mutableListOf<MutableList<Outgoing>>()
            var current = mutableListOf<Outgoing>()
            for (shot in shots.values) {
                if (shot.size >= MAX_URI_ITEMS) {
                    if (current.isNotEmpty()) chunks.add(current)
                    chunks.add(shot)
                    current = mutableListOf()
                } else {
                    if (current.size + shot.size > MAX_URI_ITEMS) {
                        chunks.add(current)
                        current = mutableListOf()
                    }
                    current.addAll(shot)
                }
            }
            if (current.isNotEmpty()) chunks.add(current)
            return chunks
        }

        private fun cullingBundle(value: Culling, version: Int) = Bundle().apply {
            cullingFields(value, version).forEach { (k, v) ->
                when (v) {
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    else -> putString(k, v.toString())
                }
            }
        }

            private fun serverItemBundle(item: ServerItem) = Bundle().apply {
            serverItemFields(item).forEach { (k, v) ->
                when (v) {
                    is Array<*> -> putStringArray(k, v.map { it.toString() }.toTypedArray())
                    else -> putString(k, v.toString())
                }
            }
        }

        const val ACTION = "dev.hfps.reveal.action.DEVELOP"
        const val EXTRA_TITLE = "dev.hfps.reveal.extra.TITLE"
        const val EXTRA_INITIAL_URI = "dev.hfps.reveal.extra.INITIAL_URI"
        const val EXTRA_CULLING = "dev.hfps.reveal.extra.CULLING"
        const val EXTRA_CULLING_VERSION = "dev.hfps.reveal.extra.CULLING_VERSION"
        const val CULLING_VERSION = 2
        const val KEY_RATING = "rating"
        const val KEY_FLAG = "flag"
        const val KEY_LABEL = "label"

        // Server references (Reveal ADR 0011 amendment W3/H, Hoard ADR 0010 "Server items").
        const val EXTRA_SERVER_REF_VERSION = "dev.hfps.reveal.extra.SERVER_REF_VERSION"
        const val EXTRA_SERVER_ID = "dev.hfps.reveal.extra.SERVER_ID"
        const val EXTRA_SERVER_ITEMS = "dev.hfps.reveal.extra.SERVER_ITEMS"
        const val EXTRA_INITIAL_ITEM = "dev.hfps.reveal.extra.INITIAL_ITEM"
        /** On every intent of a multi-intent batch: its position (0 = the first, with INITIAL_ITEM / INITIAL_URI) and the count. */
        const val EXTRA_BATCH_INDEX = "dev.hfps.reveal.extra.BATCH_INDEX"
        const val EXTRA_BATCH_COUNT = "dev.hfps.reveal.extra.BATCH_COUNT"
        const val SERVER_REF_VERSION = 1
        const val MAX_SERVER_ITEMS = 500
        const val MAX_NAME = 255
        /** Reveal 0.9.0: the first release that reads server references. */
        const val MIN_SERVER_REF_VERSION_CODE = 90000L

        /** Server items in intents of at most [MAX_SERVER_ITEMS], order kept, duplicates (same item id) dropped. */
        fun serverBatches(items: List<ServerItem>): List<List<ServerItem>> =
            items.distinctBy { it.itemId }.chunked(MAX_SERVER_ITEMS)

        /** One server item's Bundle keys (pure): `itemId`, `fingerprint` when known, `fingerprints` for pairs, `name`. */
        fun serverItemFields(item: ServerItem): LinkedHashMap<String, Any> {
            require(item.itemId.isNotBlank()) { "itemId is required" }
            val out = LinkedHashMap<String, Any>()
            out["itemId"] = item.itemId
            item.fingerprint?.let { out["fingerprint"] = it }
            val all = (listOfNotNull(item.fingerprint) + item.fingerprints).distinct()
            if (all.size > 1) out["fingerprints"] = all.toTypedArray()
            if (item.name.isNotEmpty()) out["name"] = item.name.take(MAX_NAME)
            return out
        }

        /** The `CULLING_VERSION` a Reveal of [versionCode] reads: 2 from 0.9.0, else 1. */
        fun cullingVersionFor(versionCode: Long): Int = if (versionCode >= MIN_SERVER_REF_VERSION_CODE) CULLING_VERSION else 1

        /**
         * One URI item's `CULLING` keys (pure): `rating`, `flag`, `label`, the per-field `*ModifiedAt` (read since Reveal
         * 0.8.0 under version 1) and, for [version] 2 only, the per-field `*Device`.
         */
        fun cullingFields(value: Culling, version: Int = CULLING_VERSION): LinkedHashMap<String, Any> {
            val out = LinkedHashMap<String, Any>()
            out[KEY_RATING] = value.rating
            out[KEY_FLAG] = value.flag
            value.label?.let { out[KEY_LABEL] = it }
            value.ratingModifiedAt?.let { out["ratingModifiedAt"] = it }
            value.flagModifiedAt?.let { out["flagModifiedAt"] = it }
            value.labelModifiedAt?.let { out["labelModifiedAt"] = it }
            if (version >= 2) {
                value.ratingDevice?.let { out["ratingDevice"] = it }
                value.flagDevice?.let { out["flagDevice"] = it }
                value.labelDevice?.let { out["labelDevice"] = it }
            }
            return out
        }

        /** Reveal trims the title to this length; Hoard does it first. */
        const val MAX_TITLE = 120

        /**
         * Every item's grant (the intent's flags): read only. Since 2.2.0 never persistable (MediaStore URIs: Reveal
         * keeps what it needs itself) and never write.
         */
        const val ITEM_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION

        fun probeIntent(): Intent = Intent(ACTION).setType("image/*")

        /**
         * The files in hand-off order (pure, JVM-tested): photos only, duplicates dropped, and the
         * files of one shot (same folder, same case-insensitive stem, ADR 0006) together at the
         * place of the first one sent, the RAW first, then the renderings (JPEG, HEIF, PNG/WebP).
         */
        fun order(paths: List<String>): List<String> {
            val groups = LinkedHashMap<String, MutableList<String>>()
            val seen = HashSet<String>()
            for (path in paths) {
                if (!ImageFormats.isPhoto(path) || !seen.add(path)) continue
                val parent = path.substringBeforeLast('/', "")
                val key = parent + "/" + ImageFormats.stem(path).lowercase()
                groups.getOrPut(key) { mutableListOf() }.add(path)
            }
            if (groups.isEmpty()) smbFail(ErrorCode.UNKNOWN, "No photos to edit")
            return groups.values.flatMap { files -> files.sortedBy { rank(ImageFormats.classify(it).kind) } }
        }

        private fun rank(kind: ImageKind) = when (kind) {
            ImageKind.RAW -> 0
            ImageKind.JPEG -> 1
            ImageKind.HEIF -> 2
            else -> 3
        }

        /**
         * The intent's payload from (path, URI) in order (pure, JVM-tested): the URIs, the trimmed title (null when
         * blank), the initial URI only when it is one of the photos sent, and the flags (read only).
         */
        fun <T> plan(files: List<Pair<String, T>>, title: String?, initialPath: String?): Plan<T> {
            if (files.isEmpty()) smbFail(ErrorCode.UNKNOWN, "No photos to edit")
            val items = files.map { it.second }
            val cleanTitle = title?.trim()?.take(MAX_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            val initial = initialPath?.let { wanted -> files.firstOrNull { it.first == wanted }?.second }
            return Plan(items, cleanTitle, initial, ITEM_GRANT)
        }

        /** One shot of a hand-off: its folder, stem (as named in the first file) and files in order. */
        data class Group(val folder: String, val stem: String, val files: List<String>)

        /** [ordered] (already in hand-off order) as shots: same folder, same case-insensitive stem. */
        fun groups(ordered: List<String>): List<Group> {
            val groups = LinkedHashMap<String, MutableList<String>>()
            for (path in ordered) {
                val parent = path.substringBeforeLast('/', "")
                groups.getOrPut(parent + "/" + ImageFormats.stem(path.substringAfterLast('/')).lowercase()) { mutableListOf() } += path
            }
            return groups.values.map { files ->
                val first = files.first()
                Group(first.substringBeforeLast('/', ""), ImageFormats.stem(first.substringAfterLast('/')), files)
            }
        }

        /**
         * The `CULLING` extra's content (pure, JVM-tested; Reveal ADR 0011 "Culling"): for each file sent, in order,
         * its URI string (exactly as in the ClipData) → the decision given for its path. Files without a decision are
         * left out; ratings are clamped to 0..5 and unknown flags dropped (Reveal ignores them field by field anyway).
         */
        fun cullingValues(files: List<Pair<String, String>>, culling: Map<String, Culling>): LinkedHashMap<String, Culling> {
            val byPath = culling.mapKeys { SmbPaths.normalize(it.key) }
            val out = LinkedHashMap<String, Culling>()
            for ((path, uri) in files) {
                val value = byPath[SmbPaths.normalize(path)] ?: continue
                val flag = value.flag.lowercase()
                if (flag != "pick" && flag != "reject" && flag != "none") continue
                out[uri] = value.copy(rating = value.rating.coerceIn(0, 5), flag = flag, label = value.label?.take(64))
            }
            return out
        }
    }
}
