package dev.hfps.hoard.app.ui.gallery

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
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
class GalleryViewModel(private val repo: GalleryRepository, context: Context) : ViewModel() {
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

    fun closeViewer() {
        open.value?.let { backends.remove(it.id)?.close() }
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
    }
}

class GalleryViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = GalleryViewModel(container.gallery, container.appContext) as T
}

/** Shell state the Gallery drives: its viewer hides the tab bar. */
object GalleryChrome {
    var viewerOpen by androidx.compose.runtime.mutableStateOf(false)
}
