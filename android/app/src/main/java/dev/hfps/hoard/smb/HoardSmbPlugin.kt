package dev.hfps.hoard.smb

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
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
import dev.hfps.hoard.smb.stream.MimeTypes
import dev.hfps.hoard.smb.stream.Streamer
import dev.hfps.hoard.smb.location.HoardFs
import dev.hfps.hoard.smb.location.LocalFile
import dev.hfps.hoard.smb.location.LocalStreamSource
import dev.hfps.hoard.smb.location.Location
import dev.hfps.hoard.smb.location.SafVolumes
import dev.hfps.hoard.smb.location.SmbFs
import dev.hfps.hoard.smb.location.StorageAccess
import dev.hfps.hoard.smb.location.VolumeInfo
import dev.hfps.hoard.smb.location.VolumeMapping
import dev.hfps.hoard.smb.location.VolumeMonitor
import dev.hfps.hoard.smb.location.VolumeRegistry
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
 * Since 1.3.0 the file operations work on any location (ADR 0005): SMB shares through a session,
 * the phone's storage and removable volumes through [HoardFs] ([PathFs] by path with all files
 * access, [SafFs] through a volume's SAF tree).
 *
 * Events: `transferProgress`, `networkChanged`, `volumesChanged`.
 */
@CapacitorPlugin(
    name = "HoardSmb",
    permissions = [
        Permission(alias = HoardSmbPlugin.NOTIFICATIONS, strings = [Manifest.permission.POST_NOTIFICATIONS]),
        // Android 10 and older only; Android 11+ uses the "All files access" switch instead.
        Permission(
            alias = HoardSmbPlugin.STORAGE,
            strings = [Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE],
        ),
    ],
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
    private lateinit var volumes: VolumeRegistry
    private var volumeMonitor: VolumeMonitor? = null
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
        volumes = VolumeRegistry(context)
        volumeMonitor = VolumeMonitor(context) { change ->
            scope.launch {
                val event = JSObject().put("volumes", volumesJson())
                change?.let {
                    event.put("change", JSObject().put("uuid", it.uuid ?: JSONObject.NULL).put("state", it.state))
                }
                notifyListeners("volumesChanged", event)
            }
        }.also { it.start() }
    }

    override fun handleOnDestroy() {
        networkMonitor?.stop()
        volumeMonitor?.stop()
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
    private suspend fun <T> onSession(call: PluginCall, block: (SmbSession, Cancellation) -> T): T =
        onSession(call.getString("sessionId"), block)

    private suspend fun <T> onSession(sessionId: String?, block: (SmbSession, Cancellation) -> T): T {
        return withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]
            val cancellation = Cancellation { job?.isActive == false }
            sessions.use(sessionId) { block(it, cancellation) }
        }
    }

    /** The call's `location` (contract `LocationRef`). */
    private fun PluginCall.location(): Location = Location.parse(getObject("location"))

    /**
     * Run [block] on the location's [HoardFs]: an SMB share through its session (same rules as
     * [onSession]), or a local location (`STORAGE_PERMISSION` / `UNAVAILABLE` when it cannot be read).
     */
    private suspend fun <T> withFs(call: PluginCall, block: (HoardFs, Cancellation) -> T): T =
        when (val location = call.location()) {
            is Location.Smb -> onSession(location.sessionId) { s, cancel -> block(SmbFs(s, location.share), cancel) }
            is Location.Local -> withContext(Dispatchers.IO) {
                val job = coroutineContext[Job]
                block(volumes.fs(location), Cancellation { job?.isActive == false })
            }
        }

    private fun entryJson(entry: SmbEntry) = JSObject().apply {
        put("name", entry.name)
        put("path", entry.path)
        put("isDirectory", entry.isDirectory)
        put("size", entry.size)
        put("modifiedAt", entry.modifiedAt ?: JSONObject.NULL)
        put("isHidden", entry.isHidden)
        if (entry.restricted) put("restricted", true)
    }

    private fun volumeJson(volume: VolumeInfo) = JSObject().apply {
        put("uuid", volume.uuid ?: JSONObject.NULL)
        put("label", volume.label)
        put("primary", volume.primary)
        put("removable", volume.removable)
        put("kind", volume.kind)
        put("state", volume.state)
        volume.path?.let { put("path", it.path) }
        volume.totalBytes?.let { put("totalBytes", it) }
        volume.freeBytes?.let { put("freeBytes", it) }
        volume.fileSystem?.let { put("fileSystem", it) }
        put("access", volume.access)
    }

    private fun volumesJson() = JSArray().apply { volumes.list().forEach { put(volumeJson(it)) } }

    private fun storageAccessJson() =
        JSObject().put("granted", StorageAccess.granted(context)).put("mechanism", StorageAccess.mechanism())

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
        val shares = onSession(call) { s, _ -> SmbBrowser.listSharesWithAccess(s) }
        val list = JSArray()
        shares.forEach { (share, access) ->
            list.put(JSObject().apply {
                put("name", share.name)
                share.remark?.let { put("comment", it) }
                put("isHidden", share.isHidden)
                access?.let { put("access", it.json) }
            })
        }
        JSObject().put("shares", list)
    }

    @PluginMethod
    fun list(call: PluginCall) = run(call) {
        val path = call.getString("path").orEmpty()
        val folder = withFs(call) { fs, cancel -> fs.list(path, cancel) }
        val list = JSArray()
        folder.entries.forEach { list.put(entryJson(it)) }
        JSObject().put("entries", list).apply { folder.access?.let { put("access", it.json) } }
    }

    @PluginMethod
    fun stat(call: PluginCall) = run(call) {
        val path = call.getString("path").orEmpty()
        JSObject().put("entry", entryJson(withFs(call) { fs, _ -> fs.stat(path) }))
    }

    @PluginMethod
    fun createFolder(call: PluginCall) = run(call) {
        val path = call.getString("path").orEmpty()
        val name = call.requireString("name")
        JSObject().put("entry", entryJson(withFs(call) { fs, _ -> fs.createFolder(path, name) }))
    }

    @PluginMethod
    fun rename(call: PluginCall) = run(call) {
        val path = call.getString("path").orEmpty()
        val newName = call.requireString("newName")
        JSObject().put("entry", entryJson(withFs(call) { fs, _ -> fs.rename(path, newName) }))
    }

    @PluginMethod
    fun delete(call: PluginCall) = run(call) {
        val path = call.getString("path").orEmpty()
        val recursive = call.getBoolean("recursive", false) ?: false
        withFs(call) { fs, cancel -> fs.delete(path, recursive, cancel) }
        null
    }

    // --- Local storage (ADR 0005) -----------------------------------------------------------

    @PluginMethod
    fun getStorageAccess(call: PluginCall) = run(call) { storageAccessJson() }

    @PluginMethod
    fun requestStorageAccess(call: PluginCall) {
        if (StorageAccess.granted(context)) {
            call.resolve(storageAccessJson())
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val forApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            val intent = if (forApp.resolveActivity(context.packageManager) != null) forApp else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            try {
                startActivityForResult(call, intent, "onStorageAccessResult")
            } catch (e: Exception) {
                reject(call, HoardSmbException(ErrorCode.UNKNOWN, "No settings screen for all files access: ${e.message}", e))
            }
        } else {
            requestPermissionForAlias(STORAGE, call, "onStoragePermission")
        }
    }

    @ActivityCallback
    private fun onStorageAccessResult(call: PluginCall?, @Suppress("UNUSED_PARAMETER") result: ActivityResult) {
        call ?: return
        call.resolve(storageAccessJson())
        notifyVolumesChanged()
    }

    @PermissionCallback
    private fun onStoragePermission(call: PluginCall) {
        call.resolve(storageAccessJson())
        notifyVolumesChanged()
    }

    /** Access changed: every volume's `access` may have too. */
    private fun notifyVolumesChanged() {
        scope.launch { notifyListeners("volumesChanged", JSObject().put("volumes", volumesJson())) }
    }

    @PluginMethod
    fun listVolumes(call: PluginCall) = run(call) { JSObject().put("volumes", volumesJson()) }

    @PluginMethod
    fun grantVolumeAccess(call: PluginCall) {
        try {
            val uuid = Location.requireUuid(call.getString("uuid"))
            val info = volumes.find(uuid) ?: smbFail(ErrorCode.NOT_FOUND, "No volume $uuid")
            if (!VolumeMapping.isReadable(info.state)) smbFail(ErrorCode.UNAVAILABLE, "${info.label} is not mounted", mapOf("state" to info.state))
            val volume = volumes.storageVolume(uuid)
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && volume != null) {
                volume.createOpenDocumentTreeIntent()
            } else {
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            }
            intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            startActivityForResult(call, intent, "onVolumeGranted")
        } catch (e: Throwable) {
            reject(call, e)
        }
    }

    @ActivityCallback
    private fun onVolumeGranted(call: PluginCall?, result: ActivityResult) {
        call ?: return
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            reject(call, HoardSmbException(ErrorCode.CANCELLED, "No folder was picked"))
            return
        }
        run(call) {
            val uuid = Location.requireUuid(call.getString("uuid"))
            // Android 11+ refuses a volume's root here, so any folder on this volume is accepted.
            val picked = SafVolumes.grantOf(runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull())
            if (picked == null || !picked.first.equals(uuid, ignoreCase = true)) {
                smbFail(ErrorCode.ACCESS_DENIED, "That folder is not on this drive")
            }
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            val info = volumes.find(uuid) ?: smbFail(ErrorCode.UNAVAILABLE, "The volume is gone")
            notifyVolumesChanged()
            volumeJson(info)
        }
    }

    @PluginMethod
    fun openStorageSettings(call: PluginCall) = run(call) {
        val activity = activity ?: smbFail(ErrorCode.UNKNOWN, "No activity")
        withContext(Dispatchers.Main) {
            val candidates = listOf(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), Intent(Settings.ACTION_SETTINGS))
            val intent = candidates.firstOrNull { it.resolveActivity(context.packageManager) != null }
                ?: smbFail(ErrorCode.NO_APP, "No settings app")
            activity.startActivity(intent)
        }
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
            val path = call.requireString("path")
            val chooser = call.getBoolean("chooser", false) ?: false
            when (val location = call.location()) {
                is Location.Smb -> {
                    val file = onSession(location.sessionId) { s, cancel -> opener.fetch(s, location.share, path, cancel) }
                    val activity = activity ?: smbFail(ErrorCode.UNKNOWN, "No activity to open the file from")
                    withContext(Dispatchers.Main) { opener.view(activity, file, call.getString("mimeType"), chooser) }
                }
                is Location.Local -> openLocal(location, path, call.getString("mimeType"), chooser)
            }
            null
        }
    }

    /**
     * A local file opened in place (no copy): its FileProvider or SAF document URI to the app the
     * user picks. Returns the file for `streamFile`'s answer.
     */
    private suspend fun openLocal(location: Location.Local, path: String, mimeType: String?, chooser: Boolean): LocalFile {
        val file = withContext(Dispatchers.IO) { volumes.fs(location).file(path) }
        if (file.isDirectory) smbFail(ErrorCode.UNKNOWN, "Cannot open a folder")
        val activity = activity ?: smbFail(ErrorCode.UNKNOWN, "No activity to open the file from")
        withContext(Dispatchers.Main) { opener.view(activity, opener.localUri(file), file.name, mimeType, chooser) }
        return file
    }

    // --- Streaming and the local web server -------------------------------------------------

    private fun isSmbSource(call: PluginCall) = call.getObject("location")?.optString("kind") == "smb"

    /** The `StreamSourceOptions` of an SMB call (own connection: machine, addresses, GUID, share, path). */
    private fun streamRequest(call: PluginCall): StreamRequest {
        val location = call.getObject("location") ?: smbFail(ErrorCode.UNKNOWN, "location is required")
        val array = location.optJSONArray("addresses")
        val addresses = (0 until (array?.length() ?: 0)).map { index ->
            val it = array!!.getJSONObject(index)
            val (host, port) = Net.requireValidAddress(it.optString("host"), if (it.has("port")) it.optInt("port") else null)
            StreamAddress(host, port)
        }
        if (addresses.isEmpty()) smbFail(ErrorCode.UNKNOWN, "location.addresses is required")
        return StreamRequest(
            machineId = location.optString("machineId").takeIf { it.isNotEmpty() } ?: smbFail(ErrorCode.UNKNOWN, "location.machineId is required"),
            addresses = addresses,
            expectedServerGuid = location.optString("expectedServerGuid").takeIf { it.isNotBlank() && it != "null" },
            share = location.optString("share").takeIf { it.isNotEmpty() } ?: smbFail(ErrorCode.UNKNOWN, "location.share is required"),
            path = call.requireString("path"),
        )
    }

    @PluginMethod
    fun streamFile(call: PluginCall) {
        // Local files are handed over directly: no stream token, no foreground service.
        if (isSmbSource(call) && askForNotifications(call, "onStreamPermission")) return
        doStreamFile(call)
    }

    @PermissionCallback
    private fun onStreamPermission(call: PluginCall) = doStreamFile(call)

    private fun doStreamFile(call: PluginCall) {
        openJob?.cancel(CancellationException("Superseded by a newer open"))
        openJob = run(call) {
            val transport = call.getString("transport") ?: "content"
            if (transport != "content" && transport != "http") smbFail(ErrorCode.UNKNOWN, "transport must be content or http")
            if (!isSmbSource(call)) {
                val location = call.location() as Location.Local
                val file = openLocal(location, call.requireString("path"), call.getString("mimeType"), call.getBoolean("chooser", false) ?: false)
                return@run JSObject().apply {
                    put("uri", (file.file?.let(opener::uriFor) ?: file.documentUri).toString())
                    put("size", file.size)
                    put("mimeType", call.getString("mimeType") ?: MimeTypes.forIntent(file.name) ?: SafDestinations.mimeTypeFor(file.name))
                }
            }
            val request = streamRequest(call)
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
            val target = call.getString("target") ?: "viewer"
            if (target != "viewer" && target != "browser") smbFail(ErrorCode.UNKNOWN, "target must be viewer or browser")
            val prepared = withContext(Dispatchers.IO) {
                if (isSmbSource(call)) {
                    streamer.prepareWebPage(streamRequest(call))
                } else {
                    val location = call.location() as Location.Local
                    streamer.prepareWebPage(LocalStreamSource(context, volumes.fs(location)), call.requireString("path"))
                }
            }
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
        val path = call.requireString("path")
        val maxSize = call.getInt("maxSize") ?: 96
        val thumb = thumbnailSlots.withPermit {
            when (val location = call.location()) {
                is Location.Smb -> onSession(location.sessionId) { s, cancel -> thumbnailer.thumbnail(s, location.share, path, maxSize, cancel) }
                is Location.Local -> withContext(Dispatchers.IO) { thumbnailer.thumbnail(volumes.fs(location), path, maxSize) }
            }
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
        val path = call.requireString("path")
        val maxBytes = call.getDouble("maxBytes")?.toLong()?.coerceIn(1, TextFiles.HARD_LIMIT.toLong())?.toInt()
            ?: TextFiles.DEFAULT_MAX_BYTES
        val truncate = call.getBoolean("truncate", false) ?: false
        val read = withFs(call) { fs, _ -> fs.readText(path, maxBytes, truncate) }
        JSObject().apply {
            put("text", read.text)
            put("encoding", read.encoding)
            put("size", read.size)
            put("modifiedAt", read.modifiedAt ?: JSONObject.NULL)
            put("truncated", read.truncated)
            read.access?.let { put("access", it.json) }
        }
    }

    @PluginMethod
    fun writeTextFile(call: PluginCall) = run(call) {
        val path = call.requireString("path")
        val text = call.getString("text") ?: smbFail(ErrorCode.UNKNOWN, "text is required")
        val encoding = call.getString("encoding") ?: TextCodec.UTF8
        if (encoding !in TextCodec.ENCODINGS) smbFail(ErrorCode.UNKNOWN, "Unknown text encoding: $encoding")
        val mode = call.getString("mode") ?: "overwrite"
        if (mode != "overwrite" && mode != "create") smbFail(ErrorCode.UNKNOWN, "mode must be overwrite or create")
        val expected = call.getString("expectedModifiedAt")?.takeIf { it.isNotBlank() }
        val entry = withFs(call) { fs, _ -> fs.writeText(path, text, encoding, mode, expected) }
        JSObject().put("entry", entryJson(entry))
    }

    companion object {
        const val NOTIFICATIONS = "notifications"
        const val STORAGE = "storage"
        private const val TAG = "HoardSmb"
    }
}
