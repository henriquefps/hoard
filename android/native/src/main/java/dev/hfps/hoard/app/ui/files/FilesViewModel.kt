package dev.hfps.hoard.app.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.files.engineMachine
import dev.hfps.hoard.app.route.RouteState
import dev.hfps.hoard.app.transfers.DEVICE_LOCATION_ID
import dev.hfps.hoard.app.transfers.VOLUME_PREFIX
import dev.hfps.hoard.data.FavoriteEntity
import dev.hfps.hoard.data.MachineWithAddresses
import dev.hfps.hoard.smb.location.VolumeInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A saved machine as the Files tab lists it: its addresses in priority order. */
data class MachineItem(val id: String, val name: String, val icon: String?, val hosts: List<String>)

/** A storage volume: the phone's own (`kind` internal) or a USB drive / SD card. */
data class VolumeItem(
    val uuid: String?,
    val label: String,
    /** `internal`, `usb`, `sd` or `other` (VolumeMapping.kind). */
    val kind: String,
    /** `mounted`, `mountedReadOnly`, `checking`, … (VolumeMapping.state). */
    val state: String,
    val freeBytes: Long?,
    val totalBytes: Long?,
    /** How Hoard reaches it: `path`, `saf` or `none` (no access yet: grant a folder). */
    val access: String = "path",
) {
    val locationId: String get() = if (uuid == null) DEVICE_LOCATION_ID else VOLUME_PREFIX + uuid
}

/**
 * A favorite folder; [place] says where (machine name, this phone, a drive), [share] is the SMB share ('' on the phone
 * and drives), [path] is '' for the share's or the volume's root.
 */
data class FavoriteItem(
    val id: String,
    val label: String,
    val place: Place,
    val path: String,
    val share: String = "",
    val locationId: String = "",
    val machineId: String? = null,
)

sealed interface Place {
    data class Machine(val name: String) : Place
    data object Phone : Place
    data class Drive(val label: String?, val uuid: String) : Place
}

data class FilesUiState(
    val loading: Boolean = true,
    val machines: List<MachineItem> = emptyList(),
    val phone: VolumeItem? = null,
    val removable: List<VolumeItem> = emptyList(),
    val favorites: List<FavoriteItem> = emptyList(),
    /** Live route per machine id (ADR 0002). */
    val routes: Map<String, RouteState> = emptyMap(),
    /** All files access (ADR 0005); off shows the "Allow access" card. */
    val storageGranted: Boolean = true,
    val activeTransfers: Int = 0,
    /** The ring on the Transfers button (overall progress of the running jobs, a recent failure). */
    val transfers: dev.hfps.hoard.app.transfers.TransfersIndicator = dev.hfps.hoard.app.transfers.TransfersIndicator(),
)

/** The Files tab: machines with their live route, this phone, USB drives / SD cards attached now, favourites. */
class FilesViewModel(
    machines: Flow<List<MachineWithAddresses>>,
    favorites: Flow<List<FavoriteEntity>>,
    volumes: Flow<List<VolumeInfo>>,
    routes: Flow<Map<String, RouteState>> = flowOf(emptyMap()),
    transfers: Flow<dev.hfps.hoard.app.transfers.TransfersIndicator> = flowOf(dev.hfps.hoard.app.transfers.TransfersIndicator()),
    private val onMachines: (List<MachineWithAddresses>) -> Unit = {},
) : ViewModel() {
    val storageGranted = MutableStateFlow(true)

    private val base = combine(machines, favorites, volumes.onStart { emit(emptyList()) }) { m, f, v ->
        onMachines(m)
        filesState(m, f, v)
    }

    val state: StateFlow<FilesUiState> = combine(base, routes, storageGranted, transfers) { s, r, granted, indicator ->
        s.copy(routes = r, storageGranted = granted, activeTransfers = indicator.count, transfers = indicator)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilesUiState())

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FilesViewModel(
                    container.database.machines().observeAll(),
                    container.database.favorites().observeAll(),
                    container.volumes.observe(),
                    container.routes.states,
                    container.transfers.transfers.map { list -> dev.hfps.hoard.app.transfers.TransfersIndicator.of(list) },
                    // A machine shown is a machine whose route is checked (Hoard 1's useMachineRoute).
                    onMachines = { list -> container.scope.launch { list.forEach { container.routes.ensure(it.engineMachine()) } } },
                )
            }
        }

        /** Pure: the screen's state from the rows and the volumes (JVM-tested). */
        fun filesState(
            machines: List<MachineWithAddresses>,
            favorites: List<FavoriteEntity>,
            volumes: List<VolumeInfo>,
        ): FilesUiState {
            val names = machines.associate { it.machine.id to it.machine.name }
            val items = volumes.map {
                VolumeItem(it.uuid, it.label, it.kind, it.state, it.freeBytes, it.totalBytes, it.access)
            }
            return FilesUiState(
                loading = false,
                machines = machines.map { m ->
                    MachineItem(m.machine.id, m.machine.name, m.machine.icon, m.ordered.map { it.host })
                },
                phone = items.firstOrNull { it.kind == "internal" && it.uuid == null },
                removable = items.filter { it.uuid != null },
                favorites = favorites.map { f ->
                    val place = when {
                        f.machineId != null -> Place.Machine(names[f.machineId] ?: f.locationId)
                        f.locationId == DEVICE_LOCATION_ID -> Place.Phone
                        else -> {
                            val uuid = f.locationId.removePrefix(VOLUME_PREFIX)
                            Place.Drive(items.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }?.label, uuid)
                        }
                    }
                    FavoriteItem(f.id, f.label, place, f.path, f.share, f.locationId, f.machineId)
                },
            )
        }
    }
}
