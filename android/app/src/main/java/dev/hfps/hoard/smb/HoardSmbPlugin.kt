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
import dev.hfps.hoard.smb.share.IncomingShare
import dev.hfps.hoard.smb.share.IncomingShares
import dev.hfps.hoard.smb.share.ShareOut
import dev.hfps.hoard.smb.stream.SmbSource
import dev.hfps.hoard.smb.transfer.Endpoint
import dev.hfps.hoard.smb.transfer.StartTransferRequest
import dev.hfps.hoard.smb.transfer.TransferJob
import dev.hfps.hoard.smb.transfer.TransferSource
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
import dev.hfps.hoard.smb.cull.CullFolder
import dev.hfps.hoard.image.ImageCore
import dev.hfps.hoard.smb.cull.CullAnalysisJson
import dev.hfps.hoard.smb.cull.CullService
import dev.hfps.hoard.image.CullModel
import dev.hfps.hoard.image.ScreenSource
import dev.hfps.hoard.image.ZoomSource
import dev.hfps.hoard.image.cache.PriorityScheduler
import dev.hfps.hoard.image.source.CancelledException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.future.await
import java.io.File
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
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
 * Since 1.4.0 transfers go from any location to any location (ADR 0007), and Hoard shares files
 * to other apps and receives them (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`).
 *
 * Events: `transferProgress`, `networkChanged`, `volumesChanged`, `filesShared`.
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
    private lateinit var cull: CullService
    private val metadataSlots = Semaphore(4)
    private var volumeMonitor: VolumeMonitor? = null
    private val thumbnailSlots = Semaphore(3)
    private var openJob: Job? = null
    private var askedForNotifications = false

    private lateinit var shareOut: ShareOut

    private val transferListener: (TransferProgress) -> Unit = { event ->
        notifyListeners("transferProgress", progressJson(event.job, event.bytesPerSecond))
    }

    private val shareListener: (IncomingShare) -> Unit = { share ->
        notifyListeners("filesShared", sharedJson(share), true)
    }

    override fun load() {
        val context = context.applicationContext
        credentials = CredentialStore(context)
        destinations = SafDestinations(context)
        transfers = TransferManager.get(context)
        opener = Opener(context)
        thumbnailer = Thumbnailer(context)
        streamer = Streamer(context, credentials)
        shareOut = ShareOut(context, opener)
        IncomingShares.init(context)
        IncomingShares.addListener(shareListener)
        // Launched (or relaunched) from another app's share sheet.
        activity?.intent?.takeIf { IncomingShares.isShare(it) }?.let { intent ->
            scope.launch { runCatching { IncomingShares.receive(context, intent) } }
            activity?.intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        }
        TransferNotifications.ensureChannels(context)
        transfers.addListener(transferListener)
        scope.launch { runCatching { transfers.reconcile() } }
        networkMonitor = NetworkMonitor(context) { state ->
            notifyListeners("networkChanged", JSObject().put("transport", state.transport).put("vpn", state.vpn))
        }.also { it.start() }
        volumes = VolumeRegistry(context)
        cull = CullService(context, sessions, volumes)
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

    /** `singleTask`: a share while Hoard is running arrives here. */
    override fun handleOnNewIntent(intent: Intent) {
        super.handleOnNewIntent(intent)
        if (!IncomingShares.isShare(intent)) return
        scope.launch { runCatching { IncomingShares.receive(context, intent) } }
    }

    override fun handleOnDestroy() {
        IncomingShares.removeListener(shareListener)
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
        if (job.state != TransferStates.FAILED && job.state != TransferStates.RETRYING && job.state != TransferStates.CANCELLED) return null
        return JSObject().put("code", code.name).put("message", job.errorMessage ?: code.name)
    }

    /** The fields `NativeTransfer` and `TransferProgressEvent` share. */
    private fun jobFields(job: TransferJob) = JSObject().apply {
        val counts = job.counts()
        put("transferId", job.transferId)
        put("state", job.state)
        put("totalBytes", job.totalBytes())
        put("bytesDone", job.bytesDone())
        put("itemsTotal", counts.total)
        put("itemsDone", counts.done)
        put("itemsFailed", counts.failed)
        put("itemsSkipped", counts.skipped)
        if (counts.conflicts > 0) put("conflicts", counts.conflicts)
        job.currentName?.let { put("currentName", it) }
        errorJson(job)?.let { put("error", it) }
        job.activeHost?.let { put("activeHost", it) }
    }

    private fun transferJson(job: TransferJob) = jobFields(job).apply {
        put("op", job.op)
        put("startedAt", job.startedAt)
        job.finishedAt?.let { put("finishedAt", it) }
    }

    private fun progressJson(job: TransferJob, bytesPerSecond: Long?) = jobFields(job).apply {
        if (bytesPerSecond != null && job.state == TransferStates.RUNNING) put("bytesPerSecond", bytesPerSecond)
    }

    private fun sharedJson(share: IncomingShare) = JSObject().apply {
        put("shareId", share.shareId)
        put("files", JSArray().apply {
            share.items.forEach {
                put(JSObject().put("index", it.index).put("name", it.name).put("size", it.size).apply { it.mimeType?.let { m -> put("mimeType", m) } })
            }
        })
        share.text?.let { put("text", it) }
        if (share.rejected > 0) put("rejected", share.rejected)
        put("receivedAt", share.receivedAt)
    }

    private fun endpoint(json: JSONObject?): Endpoint = Endpoint.parse(json) { host, port -> Net.requireValidAddress(host, port) }

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
    fun startTransfer(call: PluginCall) {
        if (askForNotifications(call, "onNotificationPermission")) return
        doStartTransfer(call)
    }

    @PermissionCallback
    private fun onNotificationPermission(call: PluginCall) = doStartTransfer(call)

    private fun doStartTransfer(call: PluginCall) = run(call) {
        val sources = call.getArray("sources")?.toList<JSONObject>().orEmpty().map {
            TransferSource(endpoint(it.optJSONObject("location")), it.optString("path"))
        }
        val destination = call.getObject("destination") ?: smbFail(ErrorCode.UNKNOWN, "destination is required")
        val job = transfers.start(
            StartTransferRequest(
                transferId = call.requireString("transferId"),
                op = call.getString("op") ?: "copy",
                sources = sources,
                destination = endpoint(destination.optJSONObject("location")),
                destPath = destination.optString("path"),
                conflict = call.getString("conflict") ?: "rename",
                resume = call.getBoolean("resume", false) ?: false,
                sidecars = call.getArray("sidecars")?.toList<JSONObject>().orEmpty().associate {
                    (it.optString("sourcePath").takeIf { p -> p.isNotEmpty() } ?: smbFail(ErrorCode.UNKNOWN, "sidecars[].sourcePath is required")) to
                        (it.optString("content").takeIf { c -> c.isNotEmpty() } ?: smbFail(ErrorCode.UNKNOWN, "sidecars[].content is required"))
                },
            ),
        )
        JSObject().put("transferId", job.transferId)
    }

    @PluginMethod
    fun resolveTransferConflicts(call: PluginCall) = run(call) {
        val items = call.getArray("items")?.toList<JSONObject>().orEmpty().associate { it.getInt("index") to it.getString("policy") }
        transfers.resolveConflicts(call.requireString("transferId"), call.getString("all"), items)
        null
    }

    @PluginMethod
    fun getTransferItems(call: PluginCall) = run(call) {
        val job = transfers.store.get(call.requireString("transferId")) ?: smbFail(ErrorCode.NOT_FOUND, "The transfer's details are gone")
        val states = call.getArray("states")?.toList<String>()?.toSet()
        val conflictsOnly = call.getBoolean("conflictsOnly", false) ?: false
        val offset = (call.getInt("offset") ?: 0).coerceAtLeast(0)
        val limit = (call.getInt("limit") ?: 200).coerceIn(1, 1000)
        val matching = synchronized(job) {
            job.items.filter {
                !it.superseded && (it.counts || it.state == TransferStates.FAILED) &&
                    (states == null || it.state in states) &&
                    (!conflictsOnly || (it.conflict == "ask" && !it.isFinished))
            }.map { item ->
                JSObject().apply {
                    put("index", item.index)
                    put("sourcePath", item.sourcePath)
                    put("destPath", item.destPath)
                    put("isDirectory", item.isDirectory)
                    put("size", item.size.coerceAtLeast(0))
                    put("bytesDone", item.bytesDone)
                    put("state", if (item.state == TransferStates.QUEUED || TransferStates.isTerminal(item.state) || item.state == TransferStates.RUNNING || item.state == TransferStates.RETRYING) item.state else TransferStates.QUEUED)
                    item.conflict?.let { put("conflict", it) }
                    item.errorCode?.let { put("error", JSObject().put("code", it.name).put("message", item.errorMessage ?: it.name)) }
                }
            }
        }
        val page = JSArray()
        matching.drop(offset).take(limit).forEach { page.put(it) }
        JSObject().put("items", page).put("total", matching.size)
    }

    // --- Share in and out -------------------------------------------------------------------

    @PluginMethod
    fun shareFiles(call: PluginCall) = run(call) {
        val location = call.getObject("location") ?: smbFail(ErrorCode.UNKNOWN, "location is required")
        val paths = call.getArray("paths")?.toList<String>().orEmpty()
        if (paths.isEmpty()) smbFail(ErrorCode.UNKNOWN, "paths is required")
        val files = withContext(Dispatchers.IO) {
            if (location.optString("kind") == "smb") {
                val request = streamRequest(call, paths[0])
                shareOut.smb({
                    SmbSource(request.machineId, request.addresses, request.expectedServerGuid, request.share, credentials::get)
                }, paths)
            } else {
                shareOut.local(volumes.fs(Location.parse(location) as Location.Local), paths)
            }
        }
        val activity = activity ?: smbFail(ErrorCode.UNKNOWN, "No activity to share from")
        withContext(Dispatchers.Main) { shareOut.launch(activity, files) }
        null
    }

    @PluginMethod
    fun getSharedFiles(call: PluginCall) = run(call) {
        JSObject().put("shared", IncomingShares.latest()?.let(::sharedJson) ?: JSONObject.NULL)
    }

    @PluginMethod
    fun clearSharedFiles(call: PluginCall) = run(call) {
        IncomingShares.clear(call.requireString("shareId"))
        null
    }

    @PluginMethod
    fun cancelTransfer(call: PluginCall) = run(call) {
        transfers.cancel(call.requireString("transferId"))
        null
    }

    @PluginMethod
    fun getTransfers(call: PluginCall) = run(call) {
        val list = JSArray()
        transfers.reported().forEach { list.put(transferJson(it)) }
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
    private fun streamRequest(call: PluginCall, path: String = call.requireString("path")): StreamRequest {
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
            path = path,
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


    // --- Culling (ADR 0008) -----------------------------------------------------------------

    /** URL of a native file for `<img src>` (what Capacitor.convertFileSrc produces). */
    private fun fileSrc(file: File) = bridge.localUrl + "/_capacitor_file_" + file.absolutePath

    /** The call's open culling folder, reached through the call's `location` now. */
    private fun PluginCall.cullFolder(): CullFolder = cull.folder(getString("cullId"), location())

    /**
     * Waits for an image-core job. Its failures carry contract codes: cancelled work (superseded
     * prefetch, closed view) is `CANCELLED`; an SMB failure that killed the connection drops the
     * session and rejects `NOT_FOUND`, so the UI reconnects (the same rule as [SmbSessions.use]).
     */
    private suspend fun <T> await(folder: CullFolder, job: PriorityScheduler.Job<T>): T = try {
        job.future.await()
    } catch (e: Throwable) {
        val cause = generateSequence(e) { if (it is ExecutionException || it is CompletionException) it.cause else null }.last()
        if (cause is CancelledException) throw HoardSmbException(ErrorCode.CANCELLED, "Superseded", cause)
        val location = folder.location
        if (location is Location.Smb && cause !is HoardSmbException && SmbErrors.isSessionGone(cause)) {
            sessions.remove(location.sessionId)
            throw HoardSmbException(ErrorCode.NOT_FOUND, "Session expired: ${SmbErrors.map(cause).message}", cause)
        }
        throw cause
    }

    private fun cullFileJson(file: dev.hfps.hoard.image.CullFileRef) = JSObject().apply {
        put("name", file.name)
        put("path", file.path)
        put("size", file.size)
        put("modifiedAt", file.modifiedAt ?: JSONObject.NULL)
    }

    private fun screenSourceName(source: ScreenSource) = when (source) {
        ScreenSource.RAW_PREVIEW -> "rawPreview"
        ScreenSource.RENDERED -> "rendered"
        ScreenSource.RAW_HALF -> "rawHalf"
    }

    private fun zoomSourceName(source: ZoomSource) = when (source) {
        ZoomSource.RENDERED -> "rendered"
        ZoomSource.RAW_PREVIEW -> "rawPreview"
        ZoomSource.RAW_FULL -> "rawFull"
    }

    private fun PluginCall.requireShotId(): String = requireString("shotId")

    private fun PluginCall.positiveInt(key: String, default: Int? = null, max: Int = 16_384): Int {
        val value = getInt(key) ?: default ?: smbFail(ErrorCode.UNKNOWN, "$key is required")
        if (value !in 1..max) smbFail(ErrorCode.UNKNOWN, "$key must be between 1 and $max")
        return value
    }

    @PluginMethod
    fun cullOpen(call: PluginCall) = run(call) {
        val started = System.nanoTime()
        val path = call.getString("path").orEmpty()
        val location = call.location()
        val listing = withFs(call) { fs, cancel -> fs.list(path, cancel) }
        val folder = withContext(Dispatchers.IO) {
            if (location is Location.Smb) sessions.use(location.sessionId) { cull.open(location, path, listing.entries) } else cull.open(location, path, listing.entries)
        }
        CullService.log("open ${folder.shots.size} shots in ${(System.nanoTime() - started) / 1_000_000} ms")
        val shots = JSArray()
        folder.shots.forEach { shot ->
            shots.put(JSObject().apply {
                put("id", shot.id)
                put("stem", shot.stem)
                put("kind", shot.kind)
                shot.raw?.let { put("raw", cullFileJson(it)) }
                shot.rendered?.let { put("rendered", cullFileJson(it)) }
                put("others", JSArray().apply { shot.others.forEach { put(cullFileJson(it)) } })
            })
        }
        JSObject().put("cullId", folder.id).put("shots", shots).put("libRaw", cull.core.libRawAvailable).apply {
            listing.access?.let { put("access", it.json) }
        }
    }

    @PluginMethod
    fun cullMetadata(call: PluginCall) = run(call) {
        val folder = call.cullFolder()
        val ids = call.getArray("shotIds")?.toList<String>().orEmpty()
        if (ids.size > 500) smbFail(ErrorCode.UNKNOWN, "At most 500 shots per call")
        val results = kotlinx.coroutines.coroutineScope {
            ids.map { id ->
                async(Dispatchers.IO) {
                    metadataSlots.withPermit {
                        val item = JSObject().put("id", id)
                        try {
                            val m = cull.metadata(folder, id)
                            item.put("metadata", JSObject().apply {
                                CullModel.captureTime(m)?.let { put("captureTime", it) }
                                m.captureSortMillis?.let { put("captureMillis", it) }
                                m.offsetTime?.let { put("offset", it) }
                                m.make?.let { put("make", it) }
                                m.model?.let { put("model", it) }
                                m.lens?.let { put("lens", it) }
                                m.exposureLabel?.let { put("exposure", it) }
                                m.fNumber?.let { put("fNumber", it) }
                                m.iso?.let { put("iso", it) }
                                m.focalLength?.let { put("focalLength", it) }
                                m.focalLength35?.let { put("focalLength35", it) }
                                CullModel.uprightSize(m)?.let { (w, h) -> put("width", w); put("height", h) }
                                put("orientation", m.orientation)
                                m.rating?.let { put("rating", it) }
                            })
                        } catch (e: Throwable) {
                            val error = SmbErrors.map(e)
                            item.put("error", JSObject().put("code", error.code.name).put("message", error.message ?: error.code.name))
                        }
                        item
                    }
                }
            }.awaitAll()
        }
        JSObject().put("shots", JSArray().apply { results.forEach { put(it) } })
    }

    @PluginMethod
    fun cullImage(call: PluginCall) = run(call) {
        val folder = call.cullFolder()
        val shotId = call.requireShotId()
        val longSide = call.positiveInt("longSide", max = 8192)
        val quality = call.getString("quality") ?: "final"
        if (quality != "fast" && quality != "final") smbFail(ErrorCode.UNKNOWN, "quality must be fast or final")
        val started = System.nanoTime()
        // Neighbours of the shot on screen wait like prefetch (and join it); the shot itself is urgent.
        val priority = if (call.getBoolean("background", false) == true) BACKGROUND_PRIORITY else 0
        val file = await(folder, cull.core.screenFile(cull.request(folder, shotId), longSide, fast = quality == "fast", priority = priority))
        CullService.log("image $quality $shotId ${screenSourceName(file.source)} in ${(System.nanoTime() - started) / 1_000_000} ms")
        JSObject().apply {
            put("src", fileSrc(file.file))
            put("width", file.width)
            put("height", file.height)
            put("source", screenSourceName(file.source))
            put("final", file.final)
        }
    }

    @PluginMethod
    fun cullThumbnail(call: PluginCall) = run(call) {
        val folder = call.cullFolder()
        val maxSize = call.positiveInt("maxSize", max = 1024)
        val thumb = await(folder, cull.core.thumbnailFile(cull.request(folder, call.requireShotId()), maxSize, priority = 50))
        JSObject().put("src", fileSrc(thumb.file)).put("width", thumb.width).put("height", thumb.height)
    }

    @PluginMethod
    fun cullPrefetch(call: PluginCall) = run(call) {
        val folder = call.cullFolder()
        val ids = call.getArray("shotIds")?.toList<String>().orEmpty()
        val current = call.getInt("current") ?: smbFail(ErrorCode.UNKNOWN, "current is required")
        val longSide = call.positiveInt("longSide", max = 8192)
        val requests = ids.map { cull.request(folder, it) }
        cull.core.prefetchFiles(
            requests,
            current,
            longSide,
            ahead = (call.getInt("ahead") ?: 2).coerceIn(0, 10),
            behind = (call.getInt("behind") ?: 1).coerceIn(0, 10),
            zoomAhead = (call.getInt("zoomAhead") ?: 0).coerceIn(0, 3),
            fastAhead = (call.getInt("fastAhead") ?: 6).coerceIn(0, 20),
        )
        null
    }

    @PluginMethod
    fun cullZoom(call: PluginCall) = run(call) {
        val folder = call.cullFolder()
        val shotId = call.requireShotId()
        val started = System.nanoTime()
        val info = await(folder, cull.core.zoomInfo(cull.request(folder, shotId)))
        CullService.log("zoom $shotId ${zoomSourceName(info.source)} ${info.width}x${info.height} in ${(System.nanoTime() - started) / 1_000_000} ms")
        JSObject().put("width", info.width).put("height", info.height).put("source", zoomSourceName(info.source))
    }

    @PluginMethod
    fun cullTile(call: PluginCall) = run(call) {
        val folder = call.cullFolder()
        val sample = call.getInt("sample") ?: 1
        if (sample !in setOf(1, 2, 4, 8, 16)) smbFail(ErrorCode.UNKNOWN, "sample must be 1, 2, 4, 8 or 16")
        val x = call.getInt("x") ?: smbFail(ErrorCode.UNKNOWN, "x is required")
        val y = call.getInt("y") ?: smbFail(ErrorCode.UNKNOWN, "y is required")
        val width = call.positiveInt("width", max = 32_768)
        val height = call.positiveInt("height", max = 32_768)
        val tile = await(folder, cull.core.tileFile(cull.request(folder, call.requireShotId()), x, y, width, height, sample))
        JSObject().put("src", fileSrc(tile.file)).put("width", tile.width).put("height", tile.height)
    }

    @PluginMethod
    fun cullSharpness(call: PluginCall) = run(call) {
        val folder = call.cullFolder()
        val ids = call.getArray("shotIds")?.toList<String>().orEmpty()
        if (ids.size > 50) smbFail(ErrorCode.UNKNOWN, "At most 50 shots per call")
        val scores = JSArray()
        for (id in ids) {
            val item = JSObject().put("id", id)
            try {
                val request = cull.request(folder, id)
                val step = withContext(Dispatchers.IO) { cull.core.screenPlan(request, SHARPNESS_SIDE).firstOrNull() }
                    ?: smbFail(ErrorCode.UNKNOWN, "Nothing displayable")
                val decoded = await(folder, cull.core.screen(request, SHARPNESS_SIDE, priority = 20, step = step))
                val score = withContext(Dispatchers.Default) { cull.core.sharpness(decoded.bitmap) }
                item.put("global", score.global).put("best", score.best).put("bestTile", score.bestIndex)
            } catch (e: Throwable) {
                val error = SmbErrors.map(e)
                item.put("error", JSObject().put("code", error.code.name).put("message", error.message ?: error.code.name))
            }
            scores.put(item)
        }
        JSObject().put("scores", scores)
    }

    /**
     * Assisted culling measurements (ADR 0009) for a few shots, one after another on the image
     * core's queue behind the viewer's prefetch. The app sends small batches in its own priority
     * order (the shot on screen first, then around it) and caches results in its database.
     */
    @PluginMethod
    fun cullAnalyze(call: PluginCall) = run(call) {
        val folder = call.cullFolder()
        val ids = call.getArray("shotIds")?.toList<String>().orEmpty()
        if (ids.size > 20) smbFail(ErrorCode.UNKNOWN, "At most 20 shots per call")
        val urgent = call.getBoolean("urgent", false) == true
        val results = JSArray()
        for (id in ids) {
            val item = JSObject().put("id", id)
            try {
                val result = await(folder, cull.analyze(folder, id, if (urgent) ANALYZE_URGENT_PRIORITY else ImageCore.ANALYSIS_PRIORITY))
                item.put("analysis", CullAnalysisJson.of(result))
            } catch (e: Throwable) {
                val error = SmbErrors.map(e)
                if (error.code == ErrorCode.CANCELLED) throw error
                item.put("error", JSObject().put("code", error.code.name).put("message", error.message ?: error.code.name))
            }
            results.put(item)
        }
        JSObject().put("results", results).put("faces", cull.facesAvailable)
    }

    /**
     * Edge swipes inside these bands (CSS px from the WebView's top) go to the app, not to the back
     * gesture: the culling viewer pages by swiping (contract `setGestureExclusion`). Android honours
     * 200 dp per edge, so the bands are cut to that, top first.
     */
    @PluginMethod
    fun setGestureExclusion(call: PluginCall) {
        val bands = try {
            call.getArray("bands")?.toList<JSONObject>().orEmpty().map { it.getDouble("top") to it.getDouble("height") }
        } catch (e: Exception) {
            null
        }
        if (bands == null || bands.any { (top, height) -> !top.isFinite() || !height.isFinite() || top < 0 || height < 0 }) {
            reject(call, HoardSmbException(ErrorCode.UNKNOWN, "Invalid gesture exclusion band"))
            return
        }
        val webView = bridge?.webView
        if (webView == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            call.resolve()
            return
        }
        webView.post {
            val density = webView.resources.displayMetrics.density
            val edge = (EXCLUSION_EDGE_DP * density).toInt()
            var budget = EXCLUSION_MAX_DP * density
            val rects = mutableListOf<android.graphics.Rect>()
            for ((top, height) in bands.sortedBy { it.first }) {
                val h = minOf(height * density, budget.toDouble())
                if (h <= 0) break
                budget -= h.toFloat()
                val y0 = (top * density).toInt()
                val y1 = (top * density + h).toInt()
                rects += android.graphics.Rect(0, y0, edge, y1)
                rects += android.graphics.Rect(webView.width - edge, y0, webView.width, y1)
            }
            webView.systemGestureExclusionRects = rects
            call.resolve()
        }
    }

    @PluginMethod
    fun cullClose(call: PluginCall) = run(call) {
        cull.close(call.requireString("cullId"))
        null
    }

    companion object {
        /** Width of the excluded strip at each screen edge (the back gesture's zone is narrower). */
        private const val EXCLUSION_EDGE_DP = 48
        /** Android honours at most this much excluded height per edge. */
        private const val EXCLUSION_MAX_DP = 200f
        /** Long side the sharpness score is computed at (the RAW preview size; ADR 0006). */
        private const val SHARPNESS_SIDE = 1600
        /** Priority of `cullAnalyze(urgent: true)` (the shot on screen): after its own prefetch window. */
        private const val ANALYZE_URGENT_PRIORITY = 30
        /** Priority of `cullImage(background: true)`: right after the prefetch window's fast images. */
        private const val BACKGROUND_PRIORITY = 2
        const val NOTIFICATIONS = "notifications"
        const val STORAGE = "storage"
        private const val TAG = "HoardSmb"
    }
}
