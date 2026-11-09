package dev.hfps.hoard.smb.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.hfps.hoard.R
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SafDestinations
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.location.LocalFs
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.stream.MimeTypes
import dev.hfps.hoard.smb.stream.SmbSource
import dev.hfps.hoard.smb.stream.StreamEntry
import dev.hfps.hoard.smb.stream.StreamKind
import dev.hfps.hoard.smb.stream.StreamProvider
import dev.hfps.hoard.smb.stream.StreamRegistry
import dev.hfps.hoard.smb.stream.StreamTokens
import dev.hfps.hoard.smb.viewer.Opener
import dev.hfps.hoard.smb.viewer.RemoteFiles
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/** A file ready for the share sheet. */
data class SharedOut(val uri: Uri, val name: String, val mimeType: String)

/**
 * "Share" on selected files (ADR 0007, "Share out"):
 *  - local files are shared in place (FileProvider for paths, the SAF document otherwise);
 *  - SMB files up to [CACHE_LIMIT] are copied to the cache first: the receiving app (mail,
 *    messaging, Drive) often reads the file later, in the background, after Hoard may have been
 *    frozen or killed, and a cached file needs nothing from Hoard by then;
 *  - larger SMB files go through the proxy file descriptor provider (`StreamProvider`) like
 *    `streamFile`: nothing is downloaded before the share sheet shows, the other app reads (and
 *    seeks) on demand while Hoard's streaming foreground service keeps serving.
 */
class ShareOut(private val context: Context, private val opener: Opener) {

    private val cacheRoot = File(context.cacheDir, "open")

    fun local(fs: LocalFs, paths: List<String>): List<SharedOut> = paths.map { path ->
        val file = fs.file(path)
        if (file.isDirectory) smbFail(ErrorCode.UNKNOWN, "Folders cannot be shared")
        SharedOut(opener.localUri(file), file.name, mimeOf(file.name))
    }

    /** [newSource] opens a fresh connection per streamed file (each token owns and closes its source). */
    fun smb(newSource: () -> SmbSource, paths: List<String>): List<SharedOut> {
        val probe = newSource()
        try {
            return paths.map { raw ->
                val path = SmbPaths.normalize(raw)
                val info = probe.stat(path) ?: smbFail(ErrorCode.NOT_FOUND, "${SmbPaths.name(path)} does not exist")
                if (info.isDirectory) smbFail(ErrorCode.UNKNOWN, "Folders cannot be shared")
                val name = SmbPaths.name(path)
                val mime = mimeOf(name)
                if (info.size <= CACHE_LIMIT) {
                    SharedOut(opener.uriFor(cache(probe, path, info.size, info.modifiedMillis)), name, mime)
                } else {
                    val source = newSource()
                    val entry = StreamEntry(StreamTokens.generate(), StreamKind.FILE, source, path, name, info.size, mime, http = false)
                    val uri = Uri.Builder().scheme("content").authority(StreamProvider.authority(context.packageName))
                        .appendPath(entry.token).appendPath(name).build()
                    entry.contentUri = uri
                    StreamRegistry.add(entry)
                    SharedOut(uri, name, mime)
                }
            }
        } finally {
            SmbSource.closeLater(probe)
        }
    }

    private fun cache(source: SmbSource, path: String, size: Long, modified: Long?): File {
        val dir = File(cacheRoot, "share-" + RemoteFiles.key(source.share.lowercase(), path, size, modified))
        val target = File(dir, SmbPaths.name(path))
        if (target.isFile && target.length() == size) return target
        dir.deleteRecursively()
        dir.mkdirs()
        source.reader(path, size, AtomicLong()).use { reader ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(1 shl 20)
                var at = 0L
                while (at < size) {
                    val n = reader.read(at, buffer.size, buffer, 0)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    at += n
                }
                if (at < size) smbFail(ErrorCode.UNREACHABLE, "${target.name} could not be read completely")
            }
        }
        return target
    }

    /** The share sheet: `ACTION_SEND` for one file, `ACTION_SEND_MULTIPLE` for several. */
    fun launch(activity: Activity, files: List<SharedOut>) {
        if (files.isEmpty()) smbFail(ErrorCode.UNKNOWN, "Nothing to share")
        val type = commonType(files.map { it.mimeType })
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, files[0].uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { it.uri }))
        }
        intent.type = type
        // ClipData carries the read grant to the chosen app (and the share sheet's preview).
        val clip = ClipData.newUri(context.contentResolver, files[0].name, files[0].uri)
        files.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, context.getString(R.string.share_chooser_title))
        try {
            activity.startActivity(chooser)
        } catch (e: android.content.ActivityNotFoundException) {
            smbFail(ErrorCode.NO_APP, "No app can receive these files")
        }
    }

    private fun mimeOf(name: String) = MimeTypes.forIntent(name) ?: SafDestinations.mimeTypeFor(name)

    companion object {
        /** SMB files up to this size are cached before sharing; larger ones stream. */
        const val CACHE_LIMIT = 16L shl 20

        /** Same type, else the shared family ("image" + wildcard), else any type. Pure (JVM-tested). */
        fun commonType(types: List<String>): String {
            val distinct = types.distinct()
            if (distinct.size == 1) return distinct[0]
            val families = distinct.map { it.substringBefore('/') }.distinct()
            return if (families.size == 1) "${families[0]}/*" else "*/*"
        }
    }
}
