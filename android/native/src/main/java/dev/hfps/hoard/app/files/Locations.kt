package dev.hfps.hoard.app.files

import android.content.Context
import dev.hfps.hoard.app.route.AddressKind
import dev.hfps.hoard.app.route.EngineAddress
import dev.hfps.hoard.app.route.EngineMachine
import dev.hfps.hoard.app.route.RouteEngine
import dev.hfps.hoard.app.smb.SmbGateway
import dev.hfps.hoard.app.transfers.DEVICE_LOCATION_ID
import dev.hfps.hoard.app.transfers.LocationId
import dev.hfps.hoard.app.transfers.TransferPlace
import dev.hfps.hoard.app.transfers.VOLUME_PREFIX
import dev.hfps.hoard.data.MachineWithAddresses
import dev.hfps.hoard.smb.Cancellation
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.ShareListing
import dev.hfps.hoard.smb.SmbBrowser
import dev.hfps.hoard.smb.SmbErrors
import dev.hfps.hoard.smb.location.HoardFs
import dev.hfps.hoard.smb.location.LocalFs
import dev.hfps.hoard.smb.location.Location
import dev.hfps.hoard.smb.location.SmbFs
import dev.hfps.hoard.smb.location.VolumeInfo
import dev.hfps.hoard.smb.location.VolumeRegistry
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.viewer.Thumbnail
import dev.hfps.hoard.smb.viewer.Thumbnailer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class LocationKind { Smb, Device, Volume }

/**
 * A place the browser shows (ADR 0005): a machine (with [share] once one is open), the phone's storage or a USB
 * drive / SD card. The Kotlin side of `useBrowseLocation`.
 */
data class BrowseLocation(
    val id: String,
    val kind: LocationKind,
    val name: String,
    val machine: MachineWithAddresses? = null,
    val share: String? = null,
    val volume: VolumeInfo? = null,
) {
    val isSmb: Boolean get() = kind == LocationKind.Smb

    /** The transfer place of this location (machine + share, `device`, `vol-<uuid>`). */
    val place: TransferPlace get() = TransferPlace(id, share)

    /** `device` / `volume` reference for the native file calls (ADR 0005). */
    val localRef: Location.Local?
        get() = when (kind) {
            LocationKind.Device -> Location.Device
            LocationKind.Volume -> Location.Volume(id.removePrefix(VOLUME_PREFIX))
            LocationKind.Smb -> null
        }

    /** Key for caches (thumbnails, scroll positions): the location and the share. */
    val cacheKey: String get() = if (isSmb) "$id/${share.orEmpty().lowercase()}" else id
}

fun MachineWithAddresses.engineMachine(): EngineMachine = EngineMachine(
    id = machine.id,
    serverGuid = machine.serverGuid,
    addresses = ordered.map { EngineAddress(it.host, it.port, AddressKind.fromId(it.kind)) },
)

/**
 * File calls on any location, the native app's `BrowseLocation.run`: SMB through the route engine's session for the
 * machine (one reconnect on NOT_FOUND), local locations through :core:smb's `HoardFs` (PathFs / SafFs). Every
 * failure carries a contract [ErrorCode] (HoardSmbException, or a RouteException for an unusable route).
 */
class FileService(
    context: Context,
    private val engine: RouteEngine,
    private val gateway: SmbGateway,
    val volumes: VolumeRegistry,
) {
    private val thumbnailer = Thumbnailer(context.applicationContext)
    private val thumbnailSlots = Semaphore(3)

    /** Runs [block] on the location's file system, on an IO thread. */
    suspend fun <T> run(location: BrowseLocation, block: (HoardFs, Cancellation) -> T): T = mapped {
        when (location.kind) {
            LocationKind.Smb -> {
                val machine = location.machine ?: smbFail(ErrorCode.NOT_FOUND, "Machine not found")
                val share = location.share ?: smbFail(ErrorCode.UNKNOWN, "No share")
                engine.withSession(machine.engineMachine()) { sessionId ->
                    gateway.onSession(sessionId) { session, cancel -> block(SmbFs(session, share), cancel) }
                }
            }
            else -> withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()[Job]
                block(volumes.fs(location.localRef!!), Cancellation { job?.isActive == false })
            }
        }
    }

    /** The local file system of a phone / volume location. */
    suspend fun localFs(location: BrowseLocation): LocalFs = mapped {
        withContext(Dispatchers.IO) { volumes.fs(location.localRef ?: smbFail(ErrorCode.UNKNOWN, "Not a local location")) }
    }

    /** The machine's shares with this account's access (`listShares`). */
    suspend fun listShares(machine: MachineWithAddresses): List<ShareListing> = mapped {
        engine.withSession(machine.engineMachine()) { sessionId ->
            gateway.onSession(sessionId) { session, _ -> SmbBrowser.listSharesWithAccess(session) }
        }
    }

    /** A thumbnail JPEG in the cache (images and video frames); at most three at a time, as in Hoard 1. */
    suspend fun thumbnail(location: BrowseLocation, path: String, maxSize: Int): Thumbnail = thumbnailSlots.withPermit {
        mapped {
            when (location.kind) {
                LocationKind.Smb -> {
                    val machine = location.machine ?: smbFail(ErrorCode.NOT_FOUND, "Machine not found")
                    engine.withSession(machine.engineMachine()) { sessionId ->
                        gateway.onSession(sessionId) { session, cancel ->
                            thumbnailer.thumbnail(session, location.share.orEmpty(), path, maxSize, cancel)
                        }
                    }
                }
                else -> withContext(Dispatchers.IO) { thumbnailer.thumbnail(volumes.fs(location.localRef!!), path, maxSize) }
            }
        }
    }

    /** A raw session call on the machine's route (the open cache's download). */
    suspend fun <T> onSessionCancellable(machine: MachineWithAddresses, block: (dev.hfps.hoard.smb.SmbSession, Cancellation) -> T): T = mapped {
        engine.withSession(machine.engineMachine()) { sessionId -> gateway.onSession(sessionId, block) }
    }

    private suspend fun <T> mapped(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HoardSmbException) {
        throw e
    } catch (e: dev.hfps.hoard.app.route.RouteException) {
        throw e
    } catch (e: Throwable) {
        throw SmbErrors.map(e)
    }

    companion object {
        /** The location id of a volume (`device` for the primary storage). */
        fun locationIdOf(volume: VolumeInfo): String =
            if (volume.primary || volume.uuid == null) DEVICE_LOCATION_ID else VOLUME_PREFIX + volume.uuid

        /** Builds the location for an id from the machines and volumes known now; null when it does not exist. */
        fun resolve(
            id: String,
            share: String?,
            machines: List<MachineWithAddresses>,
            volumes: List<VolumeInfo>,
            phoneName: String,
            driveName: String,
        ): BrowseLocation? = when (val parsed = LocationId.parse(id)) {
            LocationId.Device -> BrowseLocation(id, LocationKind.Device, phoneName, volume = volumes.firstOrNull { it.primary })
            is LocationId.Volume -> {
                val volume = volumes.firstOrNull { it.uuid.equals(parsed.uuid, ignoreCase = true) }
                BrowseLocation(id, LocationKind.Volume, volume?.label ?: driveName, volume = volume)
            }
            is LocationId.Machine -> machines.firstOrNull { it.machine.id == parsed.machineId }?.let {
                BrowseLocation(id, LocationKind.Smb, it.machine.name, machine = it, share = share)
            }
        }
    }
}
