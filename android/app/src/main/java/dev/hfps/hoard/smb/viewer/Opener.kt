package dev.hfps.hoard.smb.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import dev.hfps.hoard.smb.location.LocalFile
import dev.hfps.hoard.smb.stream.MimeTypes
import dev.hfps.hoard.smb.Cancellation
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SafDestinations
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.SmbSession
import dev.hfps.hoard.smb.smbFail
import java.io.File

/**
 * openFile: download into `cache/open/<key>/<name>` (reused while size and modified time match),
 * then ACTION_VIEW through the FileProvider. The cache is bounded and trimmed least-recently-used.
 */
class Opener(private val context: Context) {

    private val root = File(context.cacheDir, "open")

    /** Blocking; run on an IO thread. Returns the local file. */
    fun fetch(session: SmbSession, share: String, path: String, cancellation: Cancellation = Cancellation.NONE): File {
        val clean = SmbPaths.normalize(path)
        if (clean.isEmpty()) smbFail(ErrorCode.UNKNOWN, "Cannot open a folder")
        RemoteFiles.openRead(session, share, clean).use { remote ->
            val info = RemoteFiles.info(remote)
            if (info.isDirectory) smbFail(ErrorCode.UNKNOWN, "Cannot open a folder")
            val key = RemoteFiles.key(session.handle.serverGuid, share.lowercase(), clean, info.size, info.modifiedMillis)
            val dir = File(root, key)
            val target = File(dir, SmbPaths.name(clean))
            if (target.isFile && target.length() == info.size) {
                dir.setLastModified(System.currentTimeMillis())
                return target
            }
            dir.deleteRecursively()
            RemoteFiles.trim(root, (MAX_CACHE_BYTES - info.size).coerceAtLeast(0))
            RemoteFiles.download(remote, info.size, target, cancellation)
            dir.setLastModified(System.currentTimeMillis())
            return target
        }
    }

    /** Fire ACTION_VIEW (or the "Open with" chooser); NO_APP when nothing on the phone handles the type. */
    fun view(activity: Activity, file: File, mimeType: String?, chooser: Boolean = false) =
        view(activity, uriFor(file), file.name, mimeType, chooser)

    /** The FileProvider URI of a file in the cache or on local storage (`file_paths.xml`). */
    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * A local location's file, shared in place (nothing is copied): its FileProvider URI when read
     * by path, its SAF document URI otherwise (Hoard's persisted grant is passed on read-only).
     */
    fun localUri(file: LocalFile): Uri = file.file?.let(::uriFor) ?: file.documentUri!!

    fun view(activity: Activity, uri: Uri, name: String, mimeType: String?, chooser: Boolean = false) {
        val type = mimeType?.takeIf { it.isNotBlank() } ?: MimeTypes.forIntent(name) ?: SafDestinations.mimeTypeFor(name)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val handlers = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (handlers.isEmpty()) smbFail(ErrorCode.NO_APP, "No app can open $name ($type)")
        try {
            // Several handlers and no default → the system shows its chooser ("Just once" / "Always").
            activity.startActivity(if (chooser) Intent.createChooser(intent, null) else intent)
        } catch (e: ActivityNotFoundException) {
            smbFail(ErrorCode.NO_APP, "No app can open $name ($type)")
        }
    }

    companion object {
        private const val MAX_CACHE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
