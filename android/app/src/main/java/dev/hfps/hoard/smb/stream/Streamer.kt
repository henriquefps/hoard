package dev.hfps.hoard.smb.stream

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dev.hfps.hoard.smb.CredentialStore
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SafDestinations
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.smbFail

/** What `streamFile` / `openWebPage` receive from JS (see `StreamSourceOptions` in definitions.ts). */
data class StreamRequest(
    val machineId: String,
    val addresses: List<StreamAddress>,
    val expectedServerGuid: String?,
    val share: String,
    val path: String,
)

/** A token ready to hand to another app. */
data class PreparedStream(val entry: StreamEntry, val uri: Uri)

/**
 * The plugin's entry point for streaming (`streamFile`) and the local web server (`openWebPage`).
 * [prepare…] runs on an IO thread (it connects and checks the file); [launch…] on the main thread.
 */
class Streamer(private val context: Context, private val credentials: CredentialStore) {

    init {
        StreamRegistry.init(context)
    }

    private fun source(request: StreamRequest) = SmbSource(
        machineId = request.machineId,
        addresses = request.addresses,
        expectedServerGuid = request.expectedServerGuid,
        share = request.share,
        credentials = credentials::get,
    )

    /**
     * Connect (own connection, failover across [StreamRequest.addresses]), check that the file
     * exists, and register a FILE token. `content` transport needs Android 8 (proxy file
     * descriptors); older phones get the HTTP URL instead.
     */
    fun prepareFile(request: StreamRequest, mimeType: String?, transport: String): PreparedStream {
        val path = SmbPaths.normalize(request.path)
        if (path.isEmpty()) smbFail(ErrorCode.UNKNOWN, "Cannot stream a folder")
        val source = source(request)
        try {
            val (file, info) = source.open(path)
            SmbSource.closeLater(file)
            if (info.isDirectory) smbFail(ErrorCode.UNKNOWN, "Cannot stream a folder")
            val name = SmbPaths.name(path)
            val type = mimeType?.takeIf { it.isNotBlank() } ?: MimeTypes.forIntent(name) ?: SafDestinations.mimeTypeFor(name)
            val http = transport == "http" || Build.VERSION.SDK_INT < Build.VERSION_CODES.O
            val entry = StreamEntry(StreamTokens.generate(), StreamKind.FILE, source, path, name, info.size, type, http)
            val uri = if (http) {
                Uri.parse("${LocalWebServer.baseUrl()}/${entry.token}/${WebPaths.encodeSegment(name)}")
            } else {
                Uri.Builder().scheme("content").authority(StreamProvider.authority(context.packageName))
                    .appendPath(entry.token).appendPath(name).build()
                    .also { entry.contentUri = it }
            }
            StreamRegistry.add(entry)
            return PreparedStream(entry, uri)
        } catch (e: Throwable) {
            source.close()
            throw e
        }
    }

    /** ACTION_VIEW (or the "Open with" chooser) with a one-off read grant. NO_APP ends the token. */
    fun launchFile(activity: Activity, prepared: PreparedStream, chooser: Boolean) {
        val entry = prepared.entry
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(prepared.uri, entry.mimeType)
            // A display title for players that read it (MX Player); VLC 3.7 ignores it for content URIs.
            .putExtra("title", entry.name)
        if (!entry.http) view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val handlers = context.packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
        if (handlers.isEmpty()) {
            StreamRegistry.remove(entry.token)
            smbFail(ErrorCode.NO_APP, "No app can open ${entry.name} (${entry.mimeType})")
        }
        try {
            activity.startActivity(if (chooser) Intent.createChooser(view, null) else view)
        } catch (_: ActivityNotFoundException) {
            StreamRegistry.remove(entry.token)
            smbFail(ErrorCode.NO_APP, "No app can open ${entry.name} (${entry.mimeType})")
        }
    }

    /** Serve the HTML file's folder (and below) and register a FOLDER token; returns the page URL. */
    fun prepareWebPage(request: StreamRequest): PreparedStream {
        val path = SmbPaths.normalize(request.path)
        if (path.isEmpty()) smbFail(ErrorCode.UNKNOWN, "Not a page")
        val source = source(request)
        try {
            val info = source.stat(path) ?: smbFail(ErrorCode.NOT_FOUND, "${SmbPaths.name(path)} does not exist")
            if (info.isDirectory) smbFail(ErrorCode.UNKNOWN, "Not a page")
            val name = SmbPaths.name(path)
            val entry = StreamEntry(StreamTokens.generate(), StreamKind.FOLDER, source, SmbPaths.parent(path), name, 0, "text/html", http = true)
            val url = Uri.parse("${LocalWebServer.baseUrl()}/${entry.token}/${WebPaths.encodeSegment(name)}")
            StreamRegistry.add(entry)
            return PreparedStream(entry, url)
        } catch (e: Throwable) {
            source.close()
            throw e
        }
    }

    fun launchWebPage(activity: Activity, prepared: PreparedStream, inBrowser: Boolean) {
        val intent = if (inBrowser) {
            Intent(Intent.ACTION_VIEW, prepared.uri).addCategory(Intent.CATEGORY_BROWSABLE)
        } else {
            WebViewerActivity.intent(activity, prepared.uri.toString(), prepared.entry.token, prepared.entry.name)
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            StreamRegistry.remove(prepared.entry.token)
            smbFail(ErrorCode.NO_APP, "No browser installed")
        }
    }

    fun stopAll() = StreamRegistry.clear()
}
