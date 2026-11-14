package dev.hfps.hoard.app

import android.content.Context
import dev.hfps.hoard.app.data.AppSettings
import dev.hfps.hoard.app.files.FileService
import dev.hfps.hoard.app.files.engineMachine
import dev.hfps.hoard.app.route.RouteEngine
import dev.hfps.hoard.app.smb.SmbGateway
import dev.hfps.hoard.app.transfers.Clipboard
import dev.hfps.hoard.app.transfers.INCOMING_LOCATION_ID
import dev.hfps.hoard.app.transfers.LocationId
import dev.hfps.hoard.app.transfers.NativeTransfers
import dev.hfps.hoard.app.transfers.TREE_LOCATION_ID
import dev.hfps.hoard.app.transfers.TransferPlace
import dev.hfps.hoard.app.transfers.TransferService
import dev.hfps.hoard.app.ui.files.VolumesSource
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.data.MachineWithAddresses
import dev.hfps.hoard.data.SettingsStore
import dev.hfps.hoard.data.legacy.CapacitorImporter
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.NetworkMonitor
import dev.hfps.hoard.smb.SafDestinations
import dev.hfps.hoard.smb.handoff.HandoffMachine
import dev.hfps.hoard.smb.handoff.HandoffServer
import dev.hfps.hoard.smb.location.VolumeRegistry
import dev.hfps.hoard.smb.share.IncomingShares
import dev.hfps.hoard.smb.share.RevealHandoff
import dev.hfps.hoard.smb.share.ShareOut
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.stream.StreamAddress
import dev.hfps.hoard.smb.stream.Streamer
import dev.hfps.hoard.smb.transfer.Endpoint
import dev.hfps.hoard.smb.transfer.StartTransferRequest
import dev.hfps.hoard.smb.transfer.TransferAddress
import dev.hfps.hoard.smb.transfer.TransferJob
import dev.hfps.hoard.smb.transfer.TransferManager
import dev.hfps.hoard.smb.transfer.TransferNotifications
import dev.hfps.hoard.smb.transfer.TransferProgress
import dev.hfps.hoard.smb.viewer.Opener
import dev.hfps.hoard.xmp.SidecarTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The app's object graph, built by hand (ADR 0019: a handful of singletons does not need Hilt yet). One per process,
 * in [HoardApplication]; screens reach it through their ViewModel factories.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /** Process-wide work that outlives screens (route checks, transfer bookkeeping), on the main thread. */
    val scope: CoroutineScope = MainScope()

    val database: HoardDatabase by lazy { HoardDatabase.open(appContext) }
    val settingsStore: SettingsStore by lazy { SettingsStore(database.settings()) }

    /** Phase 2a's name for [settingsStore] (appearance keys). */
    val settings: SettingsStore get() = settingsStore
    val appSettings: AppSettings by lazy { AppSettings(settingsStore) }
    val importer: CapacitorImporter by lazy { CapacitorImporter(appContext, database) }
    val volumes: VolumesSource by lazy { VolumesSource(appContext) }
    val volumeRegistry: VolumeRegistry by lazy { VolumeRegistry(appContext) }

    val gateway: SmbGateway by lazy { SmbGateway(appContext) }
    val opener: Opener by lazy { Opener(appContext) }
    val streamer: Streamer by lazy { Streamer(appContext, gateway.credentials) }
    val shareOut: ShareOut by lazy { ShareOut(appContext, opener) }
    val reveal: RevealHandoff by lazy { RevealHandoff(appContext, opener, gateway.credentials) }
    val destinations: SafDestinations by lazy { SafDestinations(appContext) }
    val clipboard = Clipboard()

    /** The probe timeout setting, read by the engine on every check. */
    private val probeTimeout: StateFlow<Long> by lazy {
        appSettings.probeTimeoutMs.stateIn(scope, SharingStarted.Eagerly, 1500L)
    }

    val routes: RouteEngine by lazy {
        RouteEngine(
            scope = scope,
            smb = gateway,
            saveGuid = { id, guid -> database.machines().setServerGuid(id, guid.lowercase()) },
            probeTimeoutMs = { probeTimeout.value },
        )
    }

    val files: FileService by lazy { FileService(appContext, routes, gateway, volumeRegistry) }

    val transfers: TransferService by lazy {
        TransferService(scope, NativeTransferManager(TransferManager.get(appContext)), database.transfers(), ::endpointOf)
    }

    /** A place in app terms → what the native engine needs; SMB carries the route that is current now. */
    suspend fun endpointOf(place: TransferPlace): Endpoint = when (place.locationId) {
        TREE_LOCATION_ID -> Endpoint.Tree(place.treeUri ?: smbFail(ErrorCode.UNKNOWN, "No folder"))
        INCOMING_LOCATION_ID -> Endpoint.Incoming(place.shareId ?: smbFail(ErrorCode.UNKNOWN, "No shared files"))
        else -> when (val parsed = LocationId.parse(place.locationId)) {
            LocationId.Device -> Endpoint.Device
            is LocationId.Volume -> Endpoint.Volume(parsed.uuid)
            is LocationId.Machine -> {
                val machine = database.machines().get(parsed.machineId) ?: smbFail(ErrorCode.NOT_FOUND, "Machine not found")
                val route = routes.nativeRoute(machine.engineMachine())
                Endpoint.Smb(
                    machineId = machine.machine.id,
                    addresses = route.addresses.map { TransferAddress(it.host, it.port ?: 445) },
                    expectedServerGuid = route.expectedServerGuid,
                    share = place.share.orEmpty(),
                )
            }
        }
    }

    private var started = false
    private var network: NetworkMonitor? = null

    /**
     * Process start (from [HoardApplication] once the import has settled): the native services the Capacitor plugin
     * started in `load()`: notification channels, transfer bookkeeping, the Reveal link store and the machine mirror,
     * share-in, network changes → route rechecks.
     */
    fun start() {
        if (started) return
        started = true
        TransferNotifications.ensureChannels(appContext)
        SidecarTool.version = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull().orEmpty()
        HandoffServer.init(appContext)
        IncomingShares.init(appContext)
        scope.launch(Dispatchers.IO) {
            runCatching { HandoffServer.prune(appContext) }
            runCatching { TransferManager.get(appContext).reconcile() }
        }
        transfers.start()
        mirrorMachines()
        network = NetworkMonitor(appContext) { scope.launch { routes.recheckAll() } }.also { it.start() }
    }

    /** The app came back to the foreground: routes and transfers are checked again (Hoard 1's `resume`). */
    fun onResume() {
        if (!started) return
        routes.recheckAll()
        scope.launch { runCatching { transfers.reconcile() } }
    }

    /**
     * Keeps the native machine mirror (ADR 0010 "Cold start") equal to the saved machines: addresses in priority order
     * and the pinned GUID, so the stream provider reaches a share when Reveal reopens a photo while the app is not
     * running. Never secrets.
     */
    private fun mirrorMachines() {
        database.machines().observeAll()
            .map { list -> list.map(::handoffMachine) }
            .distinctUntilChanged()
            .onEach { list -> scope.launch(Dispatchers.IO) { runCatching { HandoffServer.store(appContext).setMachines(list) } } }
            .launchIn(scope)
    }

    private fun handoffMachine(m: MachineWithAddresses) = HandoffMachine(
        id = m.machine.id,
        serverGuid = m.machine.serverGuid,
        addresses = m.ordered.map { StreamAddress(it.host, it.port) },
    )
}

/** [TransferManager] as [NativeTransfers]. */
private class NativeTransferManager(private val manager: TransferManager) : NativeTransfers {
    override fun start(request: StartTransferRequest) {
        manager.start(request)
    }

    override fun cancel(transferId: String) = manager.cancel(transferId)
    override fun resolveConflicts(transferId: String, all: String?, perItem: Map<Int, String>) = manager.resolveConflicts(transferId, all, perItem)
    override fun reported(): List<TransferJob> = manager.reported()
    override fun acknowledge(transferIds: Collection<String>) = manager.acknowledge(transferIds)
    override fun addListener(listener: (TransferProgress) -> Unit) {
        manager.addListener(listener)
    }

    override fun removeListener(listener: (TransferProgress) -> Unit) {
        manager.removeListener(listener)
    }

    override fun job(transferId: String): TransferJob? = manager.store.get(transferId)
}
