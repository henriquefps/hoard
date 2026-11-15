package dev.hfps.hoard.smb.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.hierynomus.smbj.share.DiskShare
import dev.hfps.hoard.image.format.ImageFormats
import dev.hfps.hoard.image.format.ImageKind
import dev.hfps.hoard.smb.CredentialStore
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.handoff.HandoffMachine
import dev.hfps.hoard.smb.handoff.HandoffServer
import dev.hfps.hoard.smb.location.LocalFile
import dev.hfps.hoard.smb.location.LocalFs
import dev.hfps.hoard.smb.location.Location
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.stream.MimeTypes
import dev.hfps.hoard.smb.stream.SmbSource
import dev.hfps.hoard.smb.stream.StreamAddress
import dev.hfps.hoard.smb.transfer.Endpoint
import dev.hfps.hoard.smb.transfer.SmbShareFs
import dev.hfps.hoard.smb.transfer.TransferEndpoints
import dev.hfps.hoard.smb.transfer.TransferFs
import dev.hfps.hoard.smb.transfer.TransferPaths
import dev.hfps.hoard.smb.viewer.Opener
import dev.hfps.hoard.xmp.SidecarNames
import dev.hfps.hoard.xmp.SidecarTool
import dev.hfps.hoard.xmp.SidecarWrite
import dev.hfps.hoard.xmp.Sidecars
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpFlag
import java.util.Locale

/**
 * Which installed Reveal gets a hand-off (pure, JVM-tested): the release build (`dev.hfps.reveal`) when installed,
 * else the debug one (`dev.hfps.reveal.debug`), else none. Other apps that answer the action are never chosen: the
 * grants (persistable, write on sidecars) go to Reveal only, and an explicit package means no chooser.
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
 * of a pair next to each other), a **persistable** read grant on exactly those URIs, and the `TITLE` /
 * `INITIAL_URI` / `CULLING` extras.
 *
 * URIs: local files in place (FileProvider for paths, the SAF document otherwise); SMB files as stable links of
 * [HandoffStore] (`content://<app>.stream/h/<id>/…`, served by [HandoffServer], also after a restart: the provider
 * reconnects on its own). Never copied: a RAW editor reads the whole file anyway.
 *
 * Write-back (ADR 0010 "Write-back", Reveal ADR 0011 "What Reveal writes back"): with `sidecars` set, each shot of a
 * folder Hoard can write also sends its XMP sidecar (the existing one by Hoard's naming rules, else a new
 * `<stem>.xmp` / `.<stem>.xmp` holding Hoard's decision), with read + write + persistable grants on that item only
 * (`grantUriPermission` to the chosen package; the intent's flags never carry write, so no photo is ever writable).
 */
class RevealHandoff(private val context: Context, private val opener: Opener, private val credentials: CredentialStore) {

    private val store get() = HandoffServer.store(context)

    /** The Reveal package hand-offs go to (release first), or null when none is installed. */
    fun target(): String? = RevealPackages.choose(
        context.packageManager.queryIntentActivities(probeIntent(), 0).mapNotNull { it.activityInfo?.packageName },
    )

    /** Reveal (release or debug build) is installed and handles the hand-off action. */
    fun isAvailable(): Boolean = target() != null

    /**
     * The photos of a local location, in place, and with [sidecars] (hidden names or not) their writable sidecars.
     * FileProvider URIs are recorded in the store (so "Revoke all" reaches them).
     */
    fun local(location: Location.Local, fs: LocalFs, paths: List<String>, sidecars: SidecarOptions?, culling: Map<String, Culling>): List<Outgoing> {
        val ordered = order(paths)
        val photos = ordered.associateWith { path ->
            val file = fs.file(path)
            if (file.isDirectory) smbFail(ErrorCode.UNKNOWN, "Folders cannot be edited")
            localUri(file, sidecar = false)
        }
        val xmp = LinkedHashMap<String, Pair<String, Uri>>()
        if (sidecars != null) {
            val found = TransferEndpoints(context, credentials).local(location).use { tfs -> writableSidecars(tfs, ordered, sidecars.hidden, culling) }
            found.forEach { (first, path) -> runCatching { localUri(fs.file(path), sidecar = true) }.getOrNull()?.let { xmp[first] = path to it } }
        }
        return assemble(ordered, photos, xmp).map { Outgoing(it.first, it.second, it.third) }
    }

    /**
     * The photos of an SMB share as stable links, and with [sidecars] their writable sidecars. One connection (with
     * failover across the request's addresses) stats the files and finds or creates the sidecars; the links need none.
     */
    fun smb(endpoint: Endpoint.Smb, paths: List<String>, sidecars: SidecarOptions?, culling: Map<String, Culling>): List<Outgoing> {
        val ordered = order(paths)
        val addresses = endpoint.addresses.map { StreamAddress(it.host, it.port) }
        store.noteRoute(HandoffMachine(endpoint.machineId, endpoint.expectedServerGuid, addresses))
        HandoffServer.prune(context)
        val source = SmbSource(endpoint.machineId, addresses, endpoint.expectedServerGuid, endpoint.share, credentials::get)
        SourceShareFs(source).use { fs ->
            fun link(path: String, sidecar: Boolean): Uri {
                val stat = fs.stat(path) ?: smbFail(ErrorCode.NOT_FOUND, "${SmbPaths.name(path)} does not exist")
                if (stat.isDirectory) smbFail(ErrorCode.UNKNOWN, "Folders cannot be edited")
                val name = SmbPaths.name(path)
                val mime = if (sidecar) XMP_MIME else MimeTypes.forIntent(name) ?: "application/octet-stream"
                val item = store.smbItem(endpoint.machineId, endpoint.share, path, mime, stat.size, stat.modifiedMillis, sidecar) { id, tag, n ->
                    HandoffServer.uriOf(context.packageName, id, tag, n).toString()
                }
                return Uri.parse(item.uri)
            }
            val photos = ordered.associateWith { link(SmbPaths.normalize(it), sidecar = false) }
            val xmp = if (sidecars == null) {
                emptyMap()
            } else {
                writableSidecars(fs, ordered.map(SmbPaths::normalize), sidecars.hidden, culling)
                    .mapKeys { (first, _) -> ordered.first { SmbPaths.normalize(it) == first } }
                    .mapValues { (_, path) -> path to link(path, sidecar = true) }
            }
            source.activeAddress?.let { store.setLastGood(endpoint.machineId, it) }
            return assemble(ordered, photos, xmp).map { Outgoing(it.first, it.second, it.third) }
        }
    }

    private fun localUri(file: LocalFile, sidecar: Boolean): Uri {
        val uri = opener.localUri(file)
        if (file.file != null) {
            store.localItem(uri.toString(), file.name, if (sidecar) XMP_MIME else MimeTypes.forIntent(file.name) ?: "application/octet-stream", sidecar)
        }
        return uri
    }

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

    /**
     * One hand-off of URI items ([files]: photos the server does not have, unchanged contract: ClipData, grants,
     * `CULLING`) and server items ([server]: references to items of the paired hoard-server, no URI, no grant, no
     * culling keys). Server items go [MAX_SERVER_ITEMS] per intent; a larger batch is several intents with the same
     * title (the URI items and the initial item ride on the first).
     */
    fun launchMixed(
        activity: Activity,
        files: List<Outgoing>,
        title: String?,
        initialPath: String?,
        culling: Map<String, Culling>,
        server: ServerItems?,
    ) {
        val target = target() ?: smbFail(ErrorCode.NO_APP, "Reveal is not installed")
        val batches = server?.let { serverBatches(it.items) }.orEmpty()
        if (files.isEmpty() && batches.isEmpty()) smbFail(ErrorCode.UNKNOWN, "No photos to edit")
        val cleanTitle = title?.trim()?.take(MAX_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
        val intents = ArrayList<Intent>()
        var writable: List<Uri> = emptyList()
        val first = Intent(ACTION).setType("image/*").setPackage(target)
        cleanTitle?.let { first.putExtra(EXTRA_TITLE, it) }
        if (files.isNotEmpty()) {
            val plan = plan(files.map { Triple(it.path, it.uri, it.sidecar) }, cleanTitle, initialPath)
            first.addFlags(plan.flags)
            plan.initial?.let { first.putExtra(EXTRA_INITIAL_URI, it) }
            val values = cullingValues(files.filter { !it.sidecar }.map { it.path to it.uri.toString() }, culling)
            if (values.isNotEmpty()) {
                // Version 2 (the `*Device` keys) only to a Reveal that reads it (0.9.0+); older ones get version 1.
                val version = cullingVersionFor(targetVersionCode())
                first.putExtra(EXTRA_CULLING_VERSION, version)
                first.putExtra(EXTRA_CULLING, Bundle().apply { values.forEach { (uri, value) -> putBundle(uri, cullingBundle(value, version)) } })
            }
            // The ClipData carries the read grants: exactly these URIs, nothing else (no folder, no tree).
            val clip = ClipData(ClipDescription(plan.title ?: "Reveal", arrayOf("image/*", XMP_MIME)), ClipData.Item(plan.items[0]))
            plan.items.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            first.clipData = clip
            writable = plan.writable
        }
        if (server != null && batches.isNotEmpty()) {
            batches.forEachIndexed { i, batch ->
                val intent = if (i == 0) first else Intent(ACTION).setType("image/*").setPackage(target).also { next -> cleanTitle?.let { next.putExtra(EXTRA_TITLE, it) } }
                intent.putExtra(EXTRA_SERVER_REF_VERSION, SERVER_REF_VERSION)
                intent.putExtra(EXTRA_SERVER_ID, server.serverId)
                intent.putParcelableArrayListExtra(EXTRA_SERVER_ITEMS, ArrayList(batch.map(::serverItemBundle)))
                if (i == 0) server.initialItemId?.takeIf { id -> server.items.any { it.itemId == id } }?.let { intent.putExtra(EXTRA_INITIAL_ITEM, it) }
                if (i > 0) intents += intent
            }
        }
        intents.add(0, first)
        // More than one intent: each says where it stands in the batch (Reveal ADR 0011 W3/H "Batches over 500").
        if (intents.size > 1) intents.forEachIndexed { i, intent ->
            intent.putExtra(EXTRA_BATCH_INDEX, i)
            intent.putExtra(EXTRA_BATCH_COUNT, intents.size)
        }
        if (first.resolveActivity(context.packageManager) == null) smbFail(ErrorCode.NO_APP, "Reveal is not installed")
        // Write only on the sidecar items, to the one package chosen.
        writable.forEach { context.grantUriPermission(target, it, SIDECAR_GRANT) }
        try {
            if (intents.size == 1) activity.startActivity(first) else activity.startActivities(intents.toTypedArray())
        } catch (e: ActivityNotFoundException) {
            writable.forEach { runCatching { context.revokeUriPermission(target, it, SIDECAR_GRANT) } }
            smbFail(ErrorCode.NO_APP, "Reveal is not installed")
        }
    }

    /** Items of the paired hoard-server sent by reference (Reveal ADR 0011 amendment W3/H). */
    data class ServerItems(val serverId: String, val items: List<ServerItem>, val initialItemId: String? = null)

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

    /** `openInReveal`'s `sidecars`: send writable sidecars, new ones hidden or not (the culling setting). */
    data class SidecarOptions(val hidden: Boolean)

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

    /** One file to send: its path in the location, the URI Reveal reads, and whether it is a (writable) sidecar. */
    data class Outgoing(val path: String, val uri: Uri, val sidecar: Boolean = false)

    /** What the intent carries (pure, JVM-tested). */
    data class Plan<T>(val items: List<T>, val title: String?, val initial: T?, val flags: Int, val writable: List<T>)

    /** An SMB share's file operations over a [SmbSource] (failover across its addresses); closing closes it. */
    private class SourceShareFs(private val source: SmbSource) : SmbShareFs(source.share) {
        override fun share(): DiskShare = source.share()
        override fun close() = source.close()
    }

    companion object {
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

        const val XMP_MIME = "application/rdf+xml"

        /** The grant on a sidecar item: read, write, and Reveal may keep it. */
        const val SIDECAR_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

        /** Every item's grant (the intent's flags): read, persistable; never write. */
        const val ITEM_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

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
         * The intent's payload from (path, URI, is a sidecar) in order (pure, JVM-tested): the URIs, the trimmed title
         * (null when blank), the initial URI only when it is one of the photos sent, the flags (read + persistable on
         * every item, never write) and the items that get the write grant (the sidecars).
         */
        fun <T> plan(files: List<Triple<String, T, Boolean>>, title: String?, initialPath: String?): Plan<T> {
            if (files.none { !it.third }) smbFail(ErrorCode.UNKNOWN, "No photos to edit")
            val items = files.map { it.second }
            val cleanTitle = title?.trim()?.take(MAX_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            val initial = initialPath?.let { wanted -> files.firstOrNull { !it.third && it.first == wanted }?.second }
            return Plan(items, cleanTitle, initial, ITEM_GRANT, files.filter { it.third }.map { it.second })
        }

        /**
         * The photos in [ordered] with each shot's sidecar right after its files (pure): [photos] by path, [sidecars]
         * keyed by the path of the shot's first file.
         */
        fun <T> assemble(ordered: List<String>, photos: Map<String, T>, sidecars: Map<String, Pair<String, T>>): List<Triple<String, T, Boolean>> {
            val out = ArrayList<Triple<String, T, Boolean>>()
            for (group in groups(ordered)) {
                group.files.forEach { out += Triple(it, photos.getValue(it), false) }
                sidecars[group.files.first()]?.let { (path, uri) -> out += Triple(path, uri, true) }
            }
            return out
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
         * Each shot's writable sidecar in [fs] (pure apart from [fs], JVM-tested with `MemoryFs`), keyed by the shot's
         * first file: the existing one (Hoard's naming rules, [SidecarNames]) when it may be written, else a new
         * `<stem>.xmp` (`.<stem>.xmp` when [hidden]) holding the shot's decision from [culling] (by any of its files).
         * A folder that refuses a write is read-only: none of its shots get one. A folder that cannot be listed, or an
         * existing sidecar that is not well-formed (never rewritten), sends none either.
         */
        fun writableSidecars(fs: TransferFs, ordered: List<String>, hidden: Boolean, culling: Map<String, Culling>, date: String = IsoTime.now()): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            val byPath = culling.mapKeys { SmbPaths.normalize(it.key) }
            for ((folder, shots) in groups(ordered).groupBy { it.folder }) {
                val names = try {
                    fs.list(folder).filter { !it.isDirectory && SidecarNames.isSidecar(it.name) }
                        .associateTo(HashMap()) { it.name.lowercase(Locale.ROOT) to it.name }
                } catch (e: InterruptedException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    continue
                }
                for (shot in shots) {
                    val fileNames = shot.files.map { it.substringAfterLast('/') }
                    val existing = SidecarNames.find(shot.stem, fileNames) { names[it] }
                    if (existing != null) {
                        val path = TransferPaths.join(folder, existing)
                        val ok = try {
                            fs.canWrite(path) && runCatching { Sidecars.read(fs, path) }.isSuccess
                        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                            false
                        }
                        if (ok) out[shot.files.first()] = path
                        continue
                    }
                    val value = shot.files.firstNotNullOfOrNull { byPath[SmbPaths.normalize(it)] }
                    val fields = XmpFields(
                        (value?.rating ?: 0).coerceIn(0, XmpFields.MAX_RATING),
                        XmpFlag.parse(value?.flag) ?: XmpFlag.NONE,
                        value?.label,
                    )
                    when (val written = Sidecars.write(fs, folder, shot.stem, null, fields, hidden, SidecarTool.creatorTool, date)) {
                        is SidecarWrite.Written -> {
                            names[written.name.lowercase(Locale.ROOT)] = written.name
                            out[shot.files.first()] = TransferPaths.join(folder, written.name)
                        }
                        is SidecarWrite.Failed -> if (written.error.code == ErrorCode.ACCESS_DENIED) break
                        is SidecarWrite.Malformed -> {}
                    }
                }
            }
            return out
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
