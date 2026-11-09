package dev.hfps.hoard.smb

import android.Manifest
import android.app.Activity
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import dev.hfps.hoard.smb.transfer.StartDownloadRequest
import dev.hfps.hoard.smb.transfer.TransferAddress
import dev.hfps.hoard.smb.transfer.TransferJob
import dev.hfps.hoard.smb.transfer.TransferManager
import dev.hfps.hoard.smb.transfer.TransferNotifications
import dev.hfps.hoard.smb.transfer.TransferProgress
import dev.hfps.hoard.smb.transfer.TransferStates
import dev.hfps.hoard.smb.stream.StreamAddress
import dev.hfps.hoard.smb.stream.StreamRequest
import dev.hfps.hoard.smb.stream.Streamer
import dev.hfps.hoard.smb.text.TextCodec
import dev.hfps.hoard.smb.text.TextFiles
import dev.hfps.hoard.smb.viewer.Opener
import dev.hfps.hoard.smb.viewer.Thumbnailer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.CancellationException

/**
 * Native side of the HoardSmb contract (`src/plugins/smb/definitions.ts`): smbj (SMB2/3), the
 * Android Keystore (credentials), the Storage Access Framework (destinations), WorkManager
 * foreground jobs (transfers) and NSD (discovery). Every method runs off the main thread and
 * rejects with a `HoardSmbErrorCode` in `error.code`.
 *
 * Events: `transferProgress`, `networkChanged`.
 */
@CapacitorPlugin(
    name = "HoardSmb",
    permissions = [Permission(alias = HoardSmbPlugin.NOTIFICATIONS, strings = [Manifest.permission.POST_NOTIFICATIONS])],
)
class HoardSmbPlugin : Plugin() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = SmbSessions()
    private lateinit var credentials: CredentialStore
    private lateinit var destinations: SafDestinations
    private lateinit var transfers: TransferManager
    private lateinit var opener: Opener
    private lateinit var thumbnailer: Thumbnailer
    private lateinit var streamer: Streamer
    private var networkMonitor: NetworkMonitor? = null
    private val thumbnailSlots = Semaphore(3)
    private var openJob: Job? = null
    private var askedForNotifications = false

    private val transferListener: (TransferProgress) -> Unit = { event ->
        notifyListeners("transferProgress", progressJson(event.job, event.bytesPerSecond))
    }

    override fun load() {
        val context = context.applicationContext
        credentials = CredentialStore(context)
        destinations = SafDestinations(context)
        transfers = TransferManager.get(context)
        opener = Opener(context)
        thumbnailer = Thumbnailer(context)
        streamer = Streamer(context, credentials)
        TransferNotifications.ensureChannels(context)
        transfers.addListener(transferListener)
        scope.launch { runCatching { transfers.reconcile() } }
        networkMonitor = NetworkMonitor(context) { state ->
            notifyListeners("networkChanged", JSObject().put("transport", state.transport).put("vpn", state.vpn))
        }.also { it.start() }
    }

    override fun handleOnDestroy() {
        networkMonitor?.stop()
        transfers.removeListener(transferListener)
        sessions.closeAll()
        scope.cancel()
        super.handleOnDestroy()
    }

    // --- Plumbing -------------------------------------------------------------------------

    /** Run [block] on the IO scope; resolve with its result or reject with a contract code. */
    private fun run(call: PluginCall, block: suspend () -> JSObject?): Job = scope.launch {
        try {
            val result = block()
            if (result == null) call.resolve() else call.resolve(result)
        } catch (e: Throwable) {
            reject(call, e)
        }
    }

    private fun reject(call: PluginCall, e: Throwable) {
        val error = if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) {
            HoardSmbException(ErrorCode.CANCELLED, e.message ?: "Cancelled", e)
        } else {
            SmbErrors.map(e)
        }
        if (error.code == ErrorCode.UNKNOWN) Log.w(TAG, "${call.methodName} failed", e)
        val data = JSObject()
        error.data.forEach { (key, value) -> data.put(key, value ?: JSONObject.NULL) }
        call.reject(error.message ?: error.code.name, error.code.name, null, if (error.data.isEmpty()) null else data)
    }

    private fun PluginCall.requireString(key: String): String =
        getString(key)?.takeIf { it.isNotEmpty() } ?: smbFail(ErrorCode.UNKNOWN, "$key is required")

    private fun PluginCall.timeout(default: Long): Long =
        (getDouble("timeoutMs")?.toLong() ?: default).coerceIn(100, 120_000)

    /**
     * Session-scoped blocking SMB work. Never interrupted: smbj closes the share/connection when a
     * thread is interrupted mid-request, which would kill the session for every other call. Long
     * loops check [Cancellation.check] instead; each SMB request is bounded by smbj's own timeout.
     */
    private suspend fun <T> onSession(call: PluginCall, block: (SmbSession, Cancellation) -> T): T {
        val sessionId = call.getString("sessionId")
        return withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]
            val cancellation = Cancellation { job?.isActive == false }
            sessions.use(sessionId) { block(it, cancellation) }
        }
    }

    private fun entryJson(entry: SmbEntry) = JSObject().apply {
        put("name", entry.name)
        put("path", entry.path)
        put("isDirectory", entry.isDirectory)
        put("size", entry.size)
        put("modifiedAt", entry.modifiedAt ?: JSONObject.NULL)
        put("isHidden", entry.isHidden)
    }

    private fun errorJson(job: TransferJob): JSObject? {
        val code = job.errorCode ?: return null
        if (job.state == TransferStates.COMPLETED || job.state == TransferStates.RUNNING || job.state == TransferStates.QUEUED) return null
        return JSObject().put("code", code.name).put("message", job.errorMessage ?: code.name)
    }

    private fun transferJson(job: TransferJob) = JSObject().apply {
        put("transferId", job.transferId)
        put("machineId", job.machineId)
        put("share", job.share)
        put("remotePath", job.remotePath)
        put("destTreeUri", job.destTreeUri)
        put("destName", job.destName)
        job.destUri?.let { put("destUri", it) }
        put("totalBytes", job.totalBytes)
        put("bytesDone", job.bytesDone)
        put("state", job.state)
        errorJson(job)?.let { put("error", it) }
        job.activeHost?.let { put("activeHost", it) }
        put("startedAt", job.startedAt)
        job.finishedAt?.let { put("finishedAt", it) }
    }

    private fun progressJson(job: TransferJob, bytesPerSecond: Long?) = JSObject().apply {
        put("transferId", job.transferId)
        put("bytesDone", job.bytesDone)
        put("totalBytes", job.totalBytes)
        put("state", job.state)
        errorJson(job)?.let { put("error", it) }
        job.activeHost?.let { put("activeHost", it) }
        if (bytesPerSecond != null && job.state == TransferStates.RUNNING) put("bytesPerSecond", bytesPerSecond)
    }

    // --- Reachability and discovery -------------------------------------------------------

    @PluginMethod
    fun probe(call: PluginCall) = run(call) {
        val (host, port) = Net.requireValidAddress(call.getString("host"), call.getInt("port"))
        val result = Prober.probe(host, port, call.timeout(1_500))
        JSObject().apply {
            put("reachable", result.reachable)
            result.latencyMs?.let { put("latencyMs", it) }
            result.reason?.let { put("reason", it.name) }
        }
    }

    @PluginMethod
    fun discover(call: PluginCall) = run(call) {
        val services = NsdDiscovery(context).discover(call.timeout(3_000))
        val list = JSArray()
        services.forEach { list.put(JSObject().put("name", it.name).put("host", it.host).put("port", it.port)) }
        JSObject().put("services", list)
    }

    // --- Credentials ----------------------------------------------------------------------

    @PluginMethod
    fun setCredentials(call: PluginCall) = run(call) {
        val machineId = call.requireString("machineId")
        val username = call.requireString("username")
        val password = call.getString("password") ?: smbFail(ErrorCode.UNKNOWN, "password is required")
        credentials.set(machineId, username, password, call.getString("domain")?.takeIf { it.isNotBlank() })
        null
    }

    @PluginMethod
    fun hasCredentials(call: PluginCall) = run(call) {
        JSObject().put("value", credentials.has(call.requireString("machineId")))
    }

    @PluginMethod
    fun deleteCredentials(call: PluginCall) = run(call) {
        credentials.delete(call.requireString("machineId"))
        null
    }

    // --- Sessions -------------------------------------------------------------------------

    @PluginMethod
    fun connect(call: PluginCall) = run(call) {
        val machineId = call.requireString("machineId")
        val (host, port) = Net.requireValidAddress(call.getString("host"), call.getInt("port"))
        val expected = call.getString("expectedServerGuid")
        val handle = SmbConnector.open(host, port, expected, call.timeout(5_000)) { credentials.get(machineId) }
        val session = sessions.add(machineId, handle)
        JSObject().apply {
            put("sessionId", session.id)
            put("serverGuid", handle.serverGuid)
            handle.serverName?.let { put("serverName", it) }
            put("dialect", handle.dialect)
        }
    }

    @PluginMethod
    fun disconnect(call: PluginCall) = run(call) {
        call.getString("sessionId")?.let(sessions::remove)
        null
    }

    // --- Browsing -------------------------------------------------------------------------

    @PluginMethod
    fun listShares(call: PluginCall) = run(call) {
        val shares = onSession(call) { s, _ -> SmbBrowser.listShares(s) }
        val list = JSArray()
        shares.forEach { share ->
            list.put(JSObject().apply {
                put("name", share.name)
                share.remark?.let { put("comment", it) }
                put("isHidden", share.isHidden)
            })
        }
        JSObject().put("shares", list)
    }

    @PluginMethod
    fun list(call: PluginCall) = run(call) {
        val share = call.requireString("share")
        val path = call.getString("path").orEmpty()
        val entries = onSession(call) { s, _ -> SmbBrowser.list(s, share, path) }
        val list = JSArray()
        entries.forEach { list.put(entryJson(it)) }
        JSObject().put("entries", list)
    }

    @PluginMethod
    fun stat(call: PluginCall) = run(call) {
        val share = call.requireString("share")
        val path = call.getString("path").orEmpty()
        JSObject().put("entry", entryJson(onSession(call) { s, _ -> SmbBrowser.stat(s, share, path) }))
    }

    @PluginMethod
    fun createFolder(call: PluginCall) = run(call) {
        val share = call.requireString("share")
        val path = call.getString("path").orEmpty()
        val name = call.requireString("name")
        JSObject().put("entry", entryJson(onSession(call) { s, _ -> SmbBrowser.createFolder(s, share, path, name) }))
    }

    @PluginMethod
    fun rename(call: PluginCall) = run(call) {
        val share = call.requireString("share")
        val path = call.getString("path").orEmpty()
        val newName = call.requireString("newName")
        JSObject().put("entry", entryJson(onSession(call) { s, _ -> SmbBrowser.rename(s, share, path, newName) }))
    }

    @PluginMethod
    fun delete(call: PluginCall) = run(call) {
        val share = call.requireString("share")
        val path = call.getString("path").orEmpty()
        val recursive = call.getBoolean("recursive", false) ?: false
        onSession(call) { s, _ -> SmbBrowser.delete(s, share, path, recursive) }
        null
    }

    // --- Destinations (SAF) ---------------------------------------------------------------

    @PluginMethod
    fun pickDestinationFolder(call: PluginCall) {
        try {
            startActivityForResult(call, destinations.pickIntent(), "onDestinationPicked")
        } catch (e: Exception) {
            reject(call, HoardSmbException(ErrorCode.UNKNOWN, "No folder picker available: ${e.message}", e))
        }
    }

    @ActivityCallback
    private fun onDestinationPicked(call: PluginCall?, result: ActivityResult) {
        call ?: return
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            reject(call, HoardSmbException(ErrorCode.CANCELLED, "No folder was picked"))
            return
        }
        run(call) {
            val folder = destinations.persist(uri)
            JSObject().put("uri", folder.uri).put("name", folder.name)
        }
    }

    @PluginMethod
    fun listDestinationFolders(call: PluginCall) = run(call) {
        val list = JSArray()
        destinations.list().forEach { list.put(JSObject().put("uri", it.uri).put("name", it.name)) }
        JSObject().put("folders", list)
    }

    @PluginMethod
    fun releaseDestinationFolder(call: PluginCall) = run(call) {
        destinations.release(call.requireString("uri"))
        null
    }

    // --- Transfers ------------------------------------------------------------------------

    /**
     * Ask once per app run for the notification permission (Android 13+) before work that runs as
     * a foreground service (transfers, streams). Returns true when a prompt is showing; [callback]
     * then continues the call whatever the answer: without it the work runs, just without a
     * visible notification.
     */
    private fun askForNotifications(call: PluginCall, callback: String): Boolean {
        if (Build.VERSION.SDK_INT < 33 || askedForNotifications || activity == null) return false
        val state = getPermissionState(NOTIFICATIONS)
        if (state != PermissionState.PROMPT && state != PermissionState.PROMPT_WITH_RATIONALE) return false
        askedForNotifications = true
        requestPermissionForAlias(NOTIFICATIONS, call, callback)
        return true
    }

    @PluginMethod
    fun startDownload(call: PluginCall) {
        if (askForNotifications(call, "onNotificationPermission")) return
        doStartDownload(call)
    }

    @PermissionCallback
    private fun onNotificationPermission(call: PluginCall) = doStartDownload(call)

    private fun doStartDownload(call: PluginCall) = run(call) {
        val addresses = call.getArray("addresses")?.toList<JSONObject>().orEmpty().map {
            val (host, port) = Net.requireValidAddress(it.optString("host"), if (it.has("port")) it.optInt("port") else null)
            TransferAddress(host, port)
        }
        val request = StartDownloadRequest(
            transferId = call.requireString("transferId"),
            machineId = call.requireString("machineId"),
            addresses = addresses,
            expectedServerGuid = call.getString("expectedServerGuid").orEmpty(),
            share = call.requireString("share"),
            remotePath = call.requireString("remotePath"),
            destTreeUri = call.requireString("destTreeUri"),
            destName = call.getString("destName")?.takeIf { it.isNotBlank() },
            conflict = call.getString("conflict") ?: "rename",
            resumeFromBytes = (call.getDouble("resumeFromBytes") ?: 0.0).toLong().coerceAtLeast(0),
        )
        if (request.conflict !in setOf("rename", "overwrite", "skip")) {
            smbFail(ErrorCode.UNKNOWN, "conflict must be rename, overwrite or skip")
        }
        val job = transfers.start(request)
        JSObject().apply {
            put("transferId", job.transferId)
            put("destName", job.destName)
            put("destUri", job.destUri ?: "")
        }
    }

    @PluginMethod
    fun cancelTransfer(call: PluginCall) = run(call) {
        transfers.cancel(call.requireString("transferId"))
        null
    }

    @PluginMethod
    fun getTransfers(call: PluginCall) = run(call) {
        val list = JSArray()
        transfers.all().forEach { list.put(transferJson(it)) }
        JSObject().put("transfers", list)
    }

    @PluginMethod
    fun acknowledgeTransfers(call: PluginCall) = run(call) {
        val ids = call.getArray("transferIds")?.toList<String>().orEmpty()
        transfers.acknowledge(ids)
        null
    }

    // --- Viewing --------------------------------------------------------------------------

    @PluginMethod
    fun openFile(call: PluginCall) {
        // A newer openFile supersedes the previous one (which rejects CANCELLED).
        openJob?.cancel(CancellationException("Superseded by a newer openFile"))
        openJob = run(call) {
            val share = call.requireString("share")
            val path = call.requireString("path")
            val file = onSession(call) { s, cancel -> opener.fetch(s, share, path, cancel) }
            val activity = activity ?: smbFail(ErrorCode.UNKNOWN, "No activity to open the file from")
            val chooser = call.getBoolean("chooser", false) ?: false
            withContext(Dispatchers.Main) { opener.view(activity, file, call.getString("mimeType"), chooser) }
            null
        }
    }

    // --- Streaming and the local web server -------------------------------------------------

    /** The `StreamSourceOptions` part of a call (own connection: machine, addresses, GUID, share, path). */
    private fun streamRequest(call: PluginCall): StreamRequest {
        val addresses = call.getArray("addresses")?.toList<JSONObject>().orEmpty().map {
            val (host, port) = Net.requireValidAddress(it.optString("host"), if (it.has("port")) it.optInt("port") else null)
            StreamAddress(host, port)
        }
        if (addresses.isEmpty()) smbFail(ErrorCode.UNKNOWN, "addresses is required")
        return StreamRequest(
            machineId = call.requireString("machineId"),
            addresses = addresses,
            expectedServerGuid = call.getString("expectedServerGuid")?.takeIf { it.isNotBlank() },
            share = call.requireString("share"),
            path = call.requireString("path"),
        )
    }

    @PluginMethod
    fun streamFile(call: PluginCall) {
        if (askForNotifications(call, "onStreamPermission")) return
        doStreamFile(call)
    }

    @PermissionCallback
    private fun onStreamPermission(call: PluginCall) = doStreamFile(call)

    private fun doStreamFile(call: PluginCall) {
        openJob?.cancel(CancellationException("Superseded by a newer open"))
        openJob = run(call) {
            val request = streamRequest(call)
            val transport = call.getString("transport") ?: "content"
            if (transport != "content" && transport != "http") smbFail(ErrorCode.UNKNOWN, "transport must be content or http")
            val prepared = withContext(Dispatchers.IO) { streamer.prepareFile(request, call.getString("mimeType"), transport) }
            val activity = activity ?: smbFail(ErrorCode.UNKNOWN, "No activity to open the file from")
            withContext(Dispatchers.Main) { streamer.launchFile(activity, prepared, call.getBoolean("chooser", false) ?: false) }
            JSObject().apply {
                put("uri", prepared.uri.toString())
                put("size", prepared.entry.size)
                put("mimeType", prepared.entry.mimeType)
            }
        }
    }

    @PluginMethod
    fun openWebPage(call: PluginCall) {
        if (askForNotifications(call, "onWebPagePermission")) return
        doOpenWebPage(call)
    }

    @PermissionCallback
    private fun onWebPagePermission(call: PluginCall) = doOpenWebPage(call)

    private fun doOpenWebPage(call: PluginCall) {
        openJob?.cancel(CancellationException("Superseded by a newer open"))
        openJob = run(call) {
            val request = streamRequest(call)
            val target = call.getString("target") ?: "viewer"
            if (target != "viewer" && target != "browser") smbFail(ErrorCode.UNKNOWN, "target must be viewer or browser")
            val prepared = withContext(Dispatchers.IO) { streamer.prepareWebPage(request) }
            val activity = activity ?: smbFail(ErrorCode.UNKNOWN, "No activity to open the page from")
            withContext(Dispatchers.Main) { streamer.launchWebPage(activity, prepared, target == "browser") }
            JSObject().put("url", prepared.uri.toString())
        }
    }

    @PluginMethod
    fun stopStreams(call: PluginCall) = run(call) {
        streamer.stopAll()
        null
    }

    @PluginMethod
    fun thumbnail(call: PluginCall) = run(call) {
        val share = call.requireString("share")
        val path = call.requireString("path")
        val maxSize = call.getInt("maxSize") ?: 96
        val thumb = thumbnailSlots.withPermit {
            onSession(call) { s, cancel -> thumbnailer.thumbnail(s, share, path, maxSize, cancel) }
        }
        JSObject().apply {
            // What Capacitor.convertFileSrc produces: the WebView's local server + /_capacitor_file_.
            put("src", bridge.localUrl + "/_capacitor_file_" + thumb.file.absolutePath)
            put("width", thumb.width)
            put("height", thumb.height)
        }
    }

    // --- Text files (built-in viewer and editor) ------------------------------------------

    @PluginMethod
    fun readTextFile(call: PluginCall) = run(call) {
        val share = call.requireString("share")
        val path = call.requireString("path")
        val maxBytes = call.getDouble("maxBytes")?.toLong()?.coerceIn(1, TextFiles.HARD_LIMIT.toLong())?.toInt()
            ?: TextFiles.DEFAULT_MAX_BYTES
        val truncate = call.getBoolean("truncate", false) ?: false
        val read = onSession(call) { s, _ -> TextFiles.read(s, share, path, maxBytes, truncate) }
        JSObject().apply {
            put("text", read.text)
            put("encoding", read.encoding)
            put("size", read.size)
            put("modifiedAt", read.modifiedAt ?: JSONObject.NULL)
            put("truncated", read.truncated)
        }
    }

    @PluginMethod
    fun writeTextFile(call: PluginCall) = run(call) {
        val share = call.requireString("share")
        val path = call.requireString("path")
        val text = call.getString("text") ?: smbFail(ErrorCode.UNKNOWN, "text is required")
        val encoding = call.getString("encoding") ?: TextCodec.UTF8
        if (encoding !in TextCodec.ENCODINGS) smbFail(ErrorCode.UNKNOWN, "Unknown text encoding: $encoding")
        val mode = call.getString("mode") ?: "overwrite"
        if (mode != "overwrite" && mode != "create") smbFail(ErrorCode.UNKNOWN, "mode must be overwrite or create")
        val expected = call.getString("expectedModifiedAt")?.takeIf { it.isNotBlank() }
        val entry = onSession(call) { s, _ -> TextFiles.write(s, share, path, text, encoding, mode, expected) }
        JSObject().put("entry", entryJson(entry))
    }

    companion object {
        const val NOTIFICATIONS = "notifications"
        private const val TAG = "HoardSmb"
    }
}
