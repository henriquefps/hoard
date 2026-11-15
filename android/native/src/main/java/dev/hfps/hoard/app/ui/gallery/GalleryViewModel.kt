package dev.hfps.hoard.app.ui.gallery

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.gallery.GalleryCullBackend
import dev.hfps.hoard.app.gallery.GalleryEntry
import dev.hfps.hoard.app.gallery.GalleryFilter
import dev.hfps.hoard.app.gallery.GalleryLayout
import dev.hfps.hoard.app.gallery.GalleryPermissions
import dev.hfps.hoard.app.gallery.GalleryRepository
import dev.hfps.hoard.app.gallery.cameras
import dev.hfps.hoard.app.gallery.layout
import dev.hfps.hoard.server.db.ServerAccountEntity
import dev.hfps.hoard.server.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A viewer session: the entries it pages through (a snapshot of the grid) and where it starts. */
data class GalleryOpen(val id: Int, val entries: List<GalleryEntry>, val startKey: String)

/** The Gallery tab's state (ADR 0021): filters, density, the layout derived off the main thread, the server's state. */
class GalleryViewModel(private val repo: GalleryRepository, context: Context, private val saved: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val appContext = context.applicationContext
    val filter = MutableStateFlow(GalleryFilter())
    val columns = MutableStateFlow(DEFAULT_COLUMNS)
    val permission = MutableStateFlow(GalleryPermissions.canReadMedia(appContext))
    val open = MutableStateFlow<GalleryOpen?>(null)
    /** The banner the owner closed this session (by kind). */
    val dismissed = MutableStateFlow<Set<String>>(emptySet())

    val layout: StateFlow<GalleryLayout?> = combine(repo.entries, filter, columns.map { it >= MONTH_COLUMNS }) { entries, f, byMonth ->
        entries?.let { list -> layout(if (f.active || f.query.isNotBlank()) list.filter(f::matches) else list, byMonth) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val total: StateFlow<Int> = repo.entries.map { it?.size ?: 0 }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val cameras: StateFlow<List<Pair<String, Int>>> = repo.entries.map { cameras(it.orEmpty()) }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val lenses: StateFlow<List<Pair<String, Int>>> = repo.entries.map { dev.hfps.hoard.app.gallery.lenses(it.orEmpty()) }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val account: StateFlow<ServerAccountEntity?> = repo.account.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val status: StateFlow<SyncStatus> = repo.sync.status

    /** Backends of open viewer sessions (they hold the Edited / Original choice). */
    val backends = HashMap<Int, GalleryCullBackend>()
    private var nextOpen = 1

    /**
     * The grid's scroll state, here and not in the screen (2.2.2): it outlives the viewer, the tab bar hiding and the
     * tabs; [anchor] (saved state) brings the same shot back after a reload or a new process.
     */
    val grid = LazyGridState()

    /** The first visible shot (by key) and its row's offset; saved across process death. */
    var anchor: GridAnchor?
        get() = saved.get<String>(KEY_ANCHOR)?.let { GridAnchor(it, saved.get<Int>(KEY_OFFSET) ?: 0) }
        set(value) {
            saved[KEY_ANCHOR] = value?.key
            saved[KEY_OFFSET] = value?.offset ?: 0
        }

    /** The shot the viewer closed on, to bring into view (once) back in the grid. */
    val lastViewed = MutableStateFlow<String?>(null)

    fun setColumns(n: Int) = columns.update { n.coerceIn(MIN_COLUMNS, MAX_COLUMNS) }

    fun setFilter(f: GalleryFilter) = filter.update { f }

    fun setQuery(q: String) = filter.update { it.copy(query = q) }

    fun refreshPermission() {
        val now = GalleryPermissions.canReadMedia(appContext)
        if (now && !permission.value) viewModelScope.launch { repo.deviceIndex.scan() }
        permission.value = now
    }

    fun openViewer(key: String) {
        val entries = layout.value?.entries ?: return
        open.value = GalleryOpen(nextOpen++, entries, key)
    }

    fun closeViewer(last: String? = null) {
        open.value?.let { backends.remove(it.id)?.close() }
        lastViewed.value = last ?: open.value?.startKey
        open.value = null
    }

    fun syncNow() {
        viewModelScope.launch { repo.syncNow() }
    }

    fun dismiss(kind: String) = dismissed.update { it + kind }

    companion object {
        const val DEFAULT_COLUMNS = 4
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 9
        /** From this density on, headers are per month (a year fits in a few screens). */
        const val MONTH_COLUMNS = 7
        private const val KEY_ANCHOR = "gallery.anchor"
        private const val KEY_OFFSET = "gallery.anchorOffset"
    }
}

class GalleryViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
        GalleryViewModel(container.gallery, container.appContext, extras.createSavedStateHandle()) as T
}

/** Shell state the Gallery drives: its viewer hides the tab bar. */
object GalleryChrome {
    var viewerOpen by androidx.compose.runtime.mutableStateOf(false)
}
