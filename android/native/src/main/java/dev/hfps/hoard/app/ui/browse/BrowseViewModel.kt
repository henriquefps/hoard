package dev.hfps.hoard.app.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.files.BrowseLocation
import dev.hfps.hoard.app.files.FileFilter
import dev.hfps.hoard.app.files.FileService
import dev.hfps.hoard.app.files.LocationKind
import dev.hfps.hoard.app.files.SortOrder
import dev.hfps.hoard.app.files.ViewOptions
import dev.hfps.hoard.app.files.countPhotos
import dev.hfps.hoard.app.files.engineMachine
import dev.hfps.hoard.app.files.joinPath
import dev.hfps.hoard.app.files.sameShare
import dev.hfps.hoard.app.files.splitPath
import dev.hfps.hoard.app.files.viewEntries
import dev.hfps.hoard.app.route.RouteException
import dev.hfps.hoard.app.route.RouteState
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.app.transfers.FolderPlace
import dev.hfps.hoard.app.transfers.PickedEntry
import dev.hfps.hoard.data.FavoriteEntity
import dev.hfps.hoard.data.ManualShareEntity
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SmbAccess
import dev.hfps.hoard.smb.SmbEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** A share as the shares list shows it: listed by the server, or added by name. */
data class ShareItem(val name: String, val comment: String?, val isHidden: Boolean, val access: SmbAccess?, val manual: Boolean)

/** The listing of the open folder (or the machine's shares). */
data class Listing(
    val entries: List<SmbEntry>? = null,
    val shares: List<ShareItem>? = null,
    val access: SmbAccess? = null,
    val error: ErrorCode? = null,
    /** The route behind a route failure (attempts per address). */
    val errorRoute: RouteState? = null,
    val loading: Boolean = true,
    /** Paths of the files the paired server holds (ADR 0022: an SMB folder mapped to a NAS folder). */
    val onServer: Set<String> = emptySet(),
)

data class BrowseUiState(
    /** Null while the machines and volumes load; [notFound] when the location does not exist. */
    val location: BrowseLocation? = null,
    val notFound: Boolean = false,
    val share: String? = null,
    val path: String = "",
    val route: RouteState = RouteState.IDLE,
    val listing: Listing = Listing(),
    val visible: List<SmbEntry> = emptyList(),
    val filter: FileFilter = FileFilter.All,
    val query: String = "",
    val sort: SortOrder = SortOrder.DEFAULT,
    val showHidden: Boolean = false,
    val selected: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val favorite: FavoriteEntity? = null,
    val opening: String? = null,
) {
    /** The machine's share list (an SMB location without a share). */
    val showsShares: Boolean get() = location?.isSmb == true && share == null
    val readOnly: Boolean get() = !showsShares && listing.access == SmbAccess.READ
    val inSelection: Boolean get() = selectionMode || selected.isNotEmpty()
    val selectedEntries: List<SmbEntry> get() = visible.filter { it.path in selected }
    val photos: Int get() = listing.entries?.let(::countPhotos) ?: 0
    val title: String get() = splitPath(path).lastOrNull() ?: share ?: location?.name.orEmpty()

    /** The folder on screen as a transfer place. */
    val folder: FolderPlace? get() = location?.takeUnless { showsShares }?.let { FolderPlace(it.place, path) }
}

/** The order the user picked lasts for the app run, across folders (the default is a setting), as in Hoard 1. */
private object SessionSort {
    var value: SortOrder? = null
}

/**
 * `/browse/$locationId?share=&path=` (Hoard 1's BrowsePage): any location (ADR 0005). A machine shows its shares,
 * then folders and files; the phone's storage and USB drives / SD cards show their folders from the root.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModel(
    private val container: AppContainer,
    val locationId: String,
    val share: String?,
    val path: String,
    phoneName: String,
    driveName: String,
) : ViewModel() {
    private val files = container.files

    private val location: StateFlow<BrowseLocation?> = combine(
        container.database.machines().observeAll(),
        container.volumes.observe(),
    ) { machines, volumes -> FileService.resolve(locationId, share, machines, volumes, phoneName, driveName) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val located = MutableStateFlow(false)
    private val listing = MutableStateFlow(Listing())
    private val view = MutableStateFlow(ViewState(sort = SessionSort.value))
    private var loadJob: Job? = null

    private data class ViewState(
        val filter: FileFilter = FileFilter.All,
        val query: String = "",
        val sort: SortOrder? = null,
        val selected: Set<String> = emptySet(),
        val selectionMode: Boolean = false,
        val opening: String? = null,
    )

    private val route: Flow<RouteState> = location.flatMapLatest { loc ->
        loc?.machine?.let { container.routes.observe(it.machine.id) } ?: flowOf(RouteState.IDLE)
    }

    private val favorite: Flow<FavoriteEntity?> =
        if (share == null && LocationKindOf(locationId) == LocationKind.Smb) flowOf(null)
        else container.database.favorites().observeAt(locationId, share.orEmpty(), path)

    private val manualShares: Flow<List<ManualShareEntity>> =
        if (share == null && LocationKindOf(locationId) == LocationKind.Smb) container.database.manualShares().observe(locationId) else flowOf(emptyList())

    val state: StateFlow<BrowseUiState> = combine(
        combine(location, located, listing, manualShares) { loc, done, list, manual -> Quad(loc, done, list, manual) },
        route,
        view,
        container.appSettings.files,
        favorite,
    ) { (loc, done, list, manual), route, v, settings, fav ->
        val sort = v.sort ?: settings.defaultSort
        val visible = list.entries?.let {
            viewEntries(it, ViewOptions(v.filter, v.query, settings.showHiddenFiles, sort), Locale.getDefault())
        } ?: emptyList()
        val shares = list.shares?.let { listed -> mergeShares(listed, manual, settings.showHiddenFiles) }
        BrowseUiState(
            location = loc,
            notFound = done && loc == null,
            share = share,
            path = path,
            route = route,
            listing = list.copy(shares = shares),
            visible = visible,
            filter = v.filter,
            query = v.query,
            sort = sort,
            showHidden = settings.showHiddenFiles,
            selected = v.selected.filter { p -> visible.any { it.path == p } }.toSet(),
            selectionMode = v.selectionMode,
            favorite = fav,
            opening = v.opening,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState(share = share, path = path))

    init {
        viewModelScope.launch {
            // The location exists once machines and volumes are known; a missing one is "not found".
            location.filterNotNull().first()
            located.value = true
        }
        viewModelScope.launch {
            delay(1500)
            located.value = true
        }
        viewModelScope.launch {
            location.filterNotNull().map { it.machine?.machine?.id to it.volume?.state }.distinctUntilChanged().collect { load() }
        }
        // Files landing here (or leaving with a move) show up without a manual refresh.
        viewModelScope.launch {
            container.transfers.transfers
                .map { list ->
                    list.filter { t ->
                        val into = t.row.destLocationId == locationId && sameShare(t.row.destShare, share) && t.row.destPath == path
                        val outOf = t.row.op == "move" && t.row.sourceLocationId == locationId && sameShare(t.row.sourceShare, share)
                        into || outOf
                    }.sumOf { it.itemsDone + it.itemsSkipped + if (it.active) 0 else 1000 }
                }
                .distinctUntilChanged()
                .collect { signal ->
                    if (signal > 0 && listing.value.entries != null) {
                        delay(400)
                        load(quiet = true)
                    }
                }
        }
    }

    /**
     * Lists the folder (or the shares); [quiet] keeps the current list on screen while it refreshes. [refresh] (pull to
     * refresh) also asks the server again which files it holds (ADR 0022), instead of its few-minute cache.
     */
    fun load(quiet: Boolean = false, refresh: Boolean = false) {
        val loc = location.value ?: return
        loadJob?.cancel()
        listing.update { if (quiet) it.copy(loading = true) else it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                if (loc.isSmb && share == null) {
                    val shares = files.listShares(loc.machine!!).map {
                        ShareItem(it.info.name, it.info.remark, it.info.isHidden, it.access, manual = false)
                    }
                    listing.value = Listing(shares = shares, loading = false)
                } else {
                    val folder = files.run(loc) { fs, cancel -> fs.list(path, cancel) }
                    val kept = listing.value.onServer
                    listing.value = Listing(entries = folder.entries, access = folder.access, loading = false, onServer = kept)
                    if (loc.isSmb) {
                        // Which files are server items: after the listing is on screen, never before.
                        val resolution = container.serverLinks.resolve(loc, path, folder.entries, refresh)
                        val mapped = resolution?.matches?.filterValues { it is dev.hfps.hoard.server.paths.FileMatch.Mapped }?.keys.orEmpty()
                        listing.update { it.copy(onServer = mapped) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                listing.update {
                    it.copy(error = errorCodeOf(e), errorRoute = (e as? RouteException)?.state, loading = false)
                }
            }
        }
    }

    /** Lists again quietly when a listing is already on screen (the screen came back). */
    fun refreshIfShown() {
        if (listing.value.entries != null || listing.value.shares != null) load(quiet = true)
    }

    /** "Try again" on an error: a route failure forgets the machine so it is checked from scratch. */
    fun retry() {
        val loc = location.value ?: return
        if (listing.value.errorRoute != null) loc.machine?.let { container.routes.forget(it.machine.id) }
        load()
    }

    fun setFilter(filter: FileFilter) = view.update { it.copy(filter = filter) }
    fun setQuery(query: String) = view.update { it.copy(query = query) }
    fun setSort(sort: SortOrder) {
        SessionSort.value = sort
        view.update { it.copy(sort = sort) }
    }

    fun toggle(entry: SmbEntry) = view.update {
        val next = if (entry.path in it.selected) it.selected - entry.path else it.selected + entry.path
        it.copy(selected = next, selectionMode = next.isNotEmpty())
    }

    fun startSelection(entry: SmbEntry) = view.update { it.copy(selectionMode = true, selected = it.selected + entry.path) }
    fun selectAll() = view.update { v -> v.copy(selectionMode = true, selected = state.value.visible.map { it.path }.toSet()) }
    fun clearSelection() = view.update { it.copy(selected = emptySet(), selectionMode = false) }
    fun setOpening(path: String?) = view.update { it.copy(opening = path) }

    // --- Writes -----------------------------------------------------------------------------------------------

    /** Null on success, the contract code otherwise. */
    private suspend fun write(block: suspend (BrowseLocation) -> Unit): ErrorCode? {
        val loc = location.value ?: return ErrorCode.NOT_FOUND
        return try {
            block(loc)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorCodeOf(e)
        }
    }

    suspend fun createFolder(name: String): ErrorCode? = write { loc ->
        files.run(loc) { fs, _ -> fs.createFolder(path, name) }
        load(quiet = true)
    }

    /** A new empty text file (`writeText` in create mode); returns its path, or the failure. */
    suspend fun createTextFile(name: String): Pair<String?, ErrorCode?> {
        var created: String? = null
        val error = write { loc ->
            created = files.run(loc) { fs, _ -> fs.writeText(joinPath(path, name), "", dev.hfps.hoard.smb.text.TextCodec.UTF8, "create", null) }.path
            load(quiet = true)
        }
        return created to error
    }

    suspend fun rename(entry: SmbEntry, newName: String): ErrorCode? = write { loc ->
        try {
            files.run(loc) { fs, _ -> fs.rename(entry.path, newName) }
        } finally {
            // ADR 0022: a file of this folder changed name; its cached server resolution is not trusted any more.
            container.serverLinks.invalidate(loc, path)
        }
        load(quiet = true)
    }

    /** Deletes each entry (folders recursively); returns how many failed. */
    suspend fun delete(entries: List<SmbEntry>): Int {
        var failed = 0
        for (entry in entries) {
            if (write { loc -> files.run(loc) { fs, cancel -> fs.delete(entry.path, entry.isDirectory, cancel) } } != null) failed++
        }
        location.value?.let { container.serverLinks.invalidate(it, path) }
        clearSelection()
        load(quiet = true)
        return failed
    }

    /** Pins the folder on screen, or unpins it. Returns true when it is now a favourite. */
    suspend fun toggleFavorite(label: String): Boolean {
        val current = state.value.favorite
        return if (current != null) {
            container.database.favorites().remove(current.id)
            false
        } else {
            addFavorite(path, label)
            true
        }
    }

    suspend fun addFavorite(folderPath: String, label: String, shareName: String? = share) {
        val loc = location.value ?: return
        container.database.favorites().add(
            FavoriteEntity(
                id = UUID.randomUUID().toString(),
                locationId = loc.id,
                machineId = loc.machine?.machine?.id,
                share = shareName.orEmpty(),
                path = folderPath,
                label = label,
                sortOrder = 0,
                createdAt = Instant.now().toString(),
            ),
        )
    }

    /** "Open share by name": opening the root proves the share exists for this account. */
    suspend fun openShareByName(name: String): ErrorCode? {
        val loc = location.value ?: return ErrorCode.NOT_FOUND
        val error = write { files.run(loc.copy(share = name)) { fs, cancel -> fs.list("", cancel) } }
        if (error != null) return error
        val listed = listing.value.shares.orEmpty()
        if (listed.none { sameShare(it.name, name) }) {
            container.database.manualShares().add(ManualShareEntity(loc.id, name, Instant.now().toString()))
        }
        return null
    }

    suspend fun removeManualShare(name: String) = container.database.manualShares().remove(locationId, name)

    /** The selection as transfer entries (restricted entries cannot be read). */
    fun picked(entries: List<SmbEntry>): List<PickedEntry> =
        entries.filter { !it.restricted }.map { PickedEntry(it.path, it.name, it.isDirectory, it.size) }

    /** The host shown in "Copy path" (the route in use, else the first address). */
    fun displayPath(entryPath: String): String {
        val loc = location.value ?: return entryPath
        return when (loc.kind) {
            LocationKind.Smb -> {
                val machine = loc.machine ?: return entryPath
                val host = container.routes.activeRoute(machine.machine.id)?.host ?: machine.ordered.firstOrNull()?.host.orEmpty()
                dev.hfps.hoard.app.files.uncPath(host, loc.share.orEmpty(), entryPath)
            }
            else -> {
                val root = (loc.volume?.path?.path ?: "/storage/emulated/0").trimEnd('/')
                if (entryPath.isEmpty()) root else "$root/$entryPath"
            }
        }
    }

    /** Ensures the engine checks the machine's route (the browse page shows it). */
    fun ensureRoute() {
        location.value?.machine?.let { container.routes.ensure(it.engineMachine()) }
    }

    companion object {
        fun factory(container: AppContainer, locationId: String, share: String?, path: String, phoneName: String, driveName: String): ViewModelProvider.Factory =
            viewModelFactory { initializer { BrowseViewModel(container, locationId, share, path, phoneName, driveName) } }

        /** Listed shares plus the ones added by name until the server lists them; hidden ones only when asked. */
        fun mergeShares(listed: List<ShareItem>, manual: List<ManualShareEntity>, showHidden: Boolean): List<ShareItem> =
            (listed + manual.filter { m -> listed.none { sameShare(it.name, m.name) } }.map { ShareItem(it.name, null, false, null, true) })
                .filter { showHidden || !it.isHidden }
                .sortedWith { a, b -> dev.hfps.hoard.app.files.NaturalComparator(java.text.Collator.getInstance()).compare(a.name, b.name) }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Suppress("FunctionName")
private fun LocationKindOf(id: String): LocationKind = when (dev.hfps.hoard.app.transfers.LocationId.parse(id)) {
    dev.hfps.hoard.app.transfers.LocationId.Device -> LocationKind.Device
    is dev.hfps.hoard.app.transfers.LocationId.Volume -> LocationKind.Volume
    is dev.hfps.hoard.app.transfers.LocationId.Machine -> LocationKind.Smb
}
