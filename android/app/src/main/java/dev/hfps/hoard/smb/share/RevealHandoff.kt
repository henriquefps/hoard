package dev.hfps.hoard.smb.share

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import dev.hfps.hoard.image.format.ImageFormats
import dev.hfps.hoard.image.format.ImageKind
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.location.LocalFs
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.stream.MimeTypes
import dev.hfps.hoard.smb.stream.SmbSource
import dev.hfps.hoard.smb.stream.StreamEntry
import dev.hfps.hoard.smb.stream.StreamKind
import dev.hfps.hoard.smb.stream.StreamProvider
import dev.hfps.hoard.smb.stream.StreamReader
import dev.hfps.hoard.smb.stream.StreamRegistry
import dev.hfps.hoard.smb.stream.StreamSource
import dev.hfps.hoard.smb.stream.StreamTokens
import dev.hfps.hoard.smb.viewer.Opener
import dev.hfps.hoard.smb.viewer.RemoteFileInfo
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * "Edit in Reveal" (ADR 0010): hands photos to Reveal, the owner's RAW editor, with its hand-off
 * contract (Reveal ADR 0011): `dev.hfps.reveal.action.DEVELOP`, the photos as `ClipData` (in order,
 * the RAW and the rendering of a pair next to each other), a read grant on exactly those URIs, and
 * the `TITLE` / `INITIAL_URI` extras.
 *
 * URIs are built like share-out ([ShareOut]): local files in place (FileProvider for paths, the SAF
 * document otherwise), SMB files always **streamed** through [StreamProvider] (never copied: a RAW
 * editor reads the whole file anyway, and a batch of RAWs copied up front would hold Reveal back
 * for minutes). Stream tokens of a hand-off live [HANDOFF_IDLE_MS] after their last read and end
 * with Hoard's process; the UI says so before an SMB hand-off.
 *
 * Culling decisions travel with the photos as values (`CULLING`, Reveal ADR 0011 "Culling"; Hoard ADR 0011), for
 * every shot that has one, whether its folder can hold sidecars or not. Hoard never sends a sidecar (`.xmp`) item
 * and never asks for a write grant (ADR 0010): changes made in Reveal stay in Reveal.
 */
class RevealHandoff(private val context: Context, private val opener: Opener) {

    /** Reveal (release or debug build) is installed and handles the hand-off action. */
    fun isAvailable(): Boolean = context.packageManager.queryIntentActivities(probeIntent(), 0).isNotEmpty()

    /** The photos of a local location, in place. SAF documents may be persisted by Reveal. */
    fun local(fs: LocalFs, paths: List<String>): List<Outgoing> = order(paths).map { path ->
        val file = fs.file(path)
        if (file.isDirectory) smbFail(ErrorCode.UNKNOWN, "Folders cannot be edited")
        val uri = opener.localUri(file)
        Outgoing(path, uri, persistable = file.file == null && isDocumentUri(uri))
    }

    /**
     * The photos of an SMB share as stream URIs. One connection serves the whole batch ([newSource]
     * is called once); it closes when the last token of the batch ends.
     */
    fun smb(newSource: () -> SmbSource, paths: List<String>): List<Outgoing> {
        val ordered = order(paths)
        val shared = SharedSource(newSource())
        val created = mutableListOf<String>()
        try {
            return ordered.map { raw ->
                val path = SmbPaths.normalize(raw)
                val info = shared.stat(path) ?: smbFail(ErrorCode.NOT_FOUND, "${SmbPaths.name(path)} does not exist")
                if (info.isDirectory) smbFail(ErrorCode.UNKNOWN, "Folders cannot be edited")
                val name = SmbPaths.name(path)
                val mime = MimeTypes.forIntent(name) ?: "application/octet-stream"
                val entry = StreamEntry(
                    StreamTokens.generate(), StreamKind.FILE, shared.retain(), path, name, info.size, mime,
                    http = false, idleMs = HANDOFF_IDLE_MS,
                )
                val uri = Uri.Builder().scheme("content").authority(StreamProvider.authority(context.packageName))
                    .appendPath(entry.token).appendPath(name).build()
                entry.contentUri = uri
                StreamRegistry.add(entry)
                created += entry.token
                Outgoing(raw, uri, persistable = false)
            }
        } catch (e: Throwable) {
            // Nothing is sent: end the tokens already made (their references close the connection).
            created.forEach(StreamRegistry::remove)
            throw e
        } finally {
            // Drops the batch's own reference: the connection now lives as long as its tokens.
            SmbSource.closeLater(shared)
        }
    }

    /** Starts Reveal. `NO_APP` when it is not installed (or was uninstalled since the UI checked). */
    fun launch(activity: Activity, files: List<Outgoing>, title: String?, initialPath: String?, culling: Map<String, Culling> = emptyMap()) {
        val plan = plan(files.map { it.path to it.uri }, title, initialPath, persistable = files.any { it.persistable })
        val intent = Intent(ACTION).setType("image/*").addFlags(plan.flags)
        plan.title?.let { intent.putExtra(EXTRA_TITLE, it) }
        plan.initial?.let { intent.putExtra(EXTRA_INITIAL_URI, it) }
        val values = cullingValues(files.map { it.path to it.uri.toString() }, culling)
        if (values.isNotEmpty()) {
            intent.putExtra(EXTRA_CULLING_VERSION, CULLING_VERSION)
            intent.putExtra(
                EXTRA_CULLING,
                Bundle().apply {
                    values.forEach { (uri, value) ->
                        putBundle(uri, Bundle().apply {
                            putInt(KEY_RATING, value.rating)
                            putString(KEY_FLAG, value.flag)
                            value.label?.let { putString(KEY_LABEL, it) }
                        })
                    }
                },
            )
        }
        // The ClipData carries the grants: exactly these URIs, nothing else (no folder, no tree).
        val clip = ClipData(ClipDescription(plan.title ?: "Reveal", arrayOf("image/*")), ClipData.Item(plan.items[0]))
        plan.items.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        if (intent.resolveActivity(context.packageManager) == null) smbFail(ErrorCode.NO_APP, "Reveal is not installed")
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            smbFail(ErrorCode.NO_APP, "Reveal is not installed")
        }
    }

    /** Nothing was sent: end the stream tokens made for [files] at once (no idle wait). */
    fun discard(files: List<Outgoing>) {
        val authority = StreamProvider.authority(context.packageName)
        files.filter { it.uri.authority == authority }.forEach { file ->
            file.uri.pathSegments.firstOrNull()?.let(StreamRegistry::remove)
        }
    }

    private fun isDocumentUri(uri: Uri) = runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)

    /** A shot's decision as Reveal's `CULLING` extra carries it: stars 0..5 and `pick` / `reject` / `none`. */
    data class Culling(val rating: Int, val flag: String, val label: String? = null)

    /** One photo file to send: its path in the location and the URI Reveal reads. */
    data class Outgoing(val path: String, val uri: Uri, val persistable: Boolean)

    /** What the intent carries (pure, JVM-tested). */
    data class Plan<T>(val items: List<T>, val title: String?, val initial: T?, val flags: Int)

    /**
     * Several stream tokens over one [SmbSource]: each [retain] is one reference, every `close()`
     * drops one (the batch's own included), and the connection closes with the last.
     */
    private class SharedSource(private val inner: SmbSource) : StreamSource {
        private val refs = AtomicInteger(1)
        override val label: String get() = inner.label
        override fun stat(path: String): RemoteFileInfo? = inner.stat(path)
        override fun list(path: String): List<Pair<String, Boolean>> = inner.list(path)
        override fun reader(path: String, size: Long, fetched: AtomicLong): StreamReader = inner.reader(path, size, fetched)
        fun retain(): StreamSource = also { refs.incrementAndGet() }
        override fun close() {
            if (refs.decrementAndGet() == 0) inner.close()
        }
    }

    companion object {
        const val ACTION = "dev.hfps.reveal.action.DEVELOP"
        const val EXTRA_TITLE = "dev.hfps.reveal.extra.TITLE"
        const val EXTRA_INITIAL_URI = "dev.hfps.reveal.extra.INITIAL_URI"
        const val EXTRA_CULLING = "dev.hfps.reveal.extra.CULLING"
        const val EXTRA_CULLING_VERSION = "dev.hfps.reveal.extra.CULLING_VERSION"
        const val CULLING_VERSION = 1
        const val KEY_RATING = "rating"
        const val KEY_FLAG = "flag"
        const val KEY_LABEL = "label"

        /** Reveal trims the title to this length; Hoard does it first. */
        const val MAX_TITLE = 120

        /** An SMB photo handed to Reveal stays readable this long after its last read. */
        const val HANDOFF_IDLE_MS = 60 * 60_000L

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
                if (!ImageFormats.classify(path).isDisplayable || !seen.add(path)) continue
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
         * The intent's payload from (path, URI) pairs in order (pure, JVM-tested): the URIs, the
         * trimmed title (null when blank), the initial URI only when it is one of those sent, and
         * the flags — read always, persistable only for documents a provider can persist, never
         * write (no sidecars are sent).
         */
        fun <T> plan(files: List<Pair<String, T>>, title: String?, initialPath: String?, persistable: Boolean): Plan<T> {
            if (files.isEmpty()) smbFail(ErrorCode.UNKNOWN, "No photos to edit")
            val items = files.map { it.second }
            val cleanTitle = title?.trim()?.take(MAX_TITLE)?.trim()?.takeIf { it.isNotEmpty() }
            val initial = initialPath?.let { wanted -> files.firstOrNull { it.first == wanted }?.second }
            var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (persistable) flags = flags or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            return Plan(items, cleanTitle, initial, flags)
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
                out[uri] = Culling(value.rating.coerceIn(0, 5), flag, value.label?.take(64))
            }
            return out
        }
    }
}
