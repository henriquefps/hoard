package dev.hfps.hoard.app.cull

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.hfps.hoard.app.data.CullSettings
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.data.CullAnalysisEntity
import dev.hfps.hoard.data.CullDao
import dev.hfps.hoard.data.CullSessionEntity
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SmbAccess
import dev.hfps.hoard.xmp.XmpFields
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a culled folder is (CullFolderKey): the location id, the SMB share ("" for local locations), the path. */
data class CullKey(val locationId: String, val share: String, val path: String)

enum class CullStatus { Loading, Ready, Error }

/** The folder's own state (useCullFolder): shots, EXIF, decisions, undo, sidecars, and the analyses (useCullAssist). */
data class CullCore(
    val status: CullStatus = CullStatus.Loading,
    val errorCode: ErrorCode? = null,
    /** Every shot, in capture order once metadata is in (name order until then). */
    val shots: List<Shot> = emptyList(),
    val metadata: Map<String, ShotMetadata?> = emptyMap(),
    val metadataDone: Boolean = false,
    val decisions: Map<String, Decision> = emptyMap(),
    val access: SmbAccess? = null,
    val libRaw: Boolean = true,
    val session: CullSessionEntity? = null,
    val undo: List<DecisionChange> = emptyList(),
    /** A sidecar write was refused this session (ADR 0011): decisions stay in Hoard. */
    val sidecarsRefused: Boolean = false,
    val analyses: Map<String, Analysis> = emptyMap(),
    val failed: Set<String> = emptySet(),
    val analysing: Boolean = false,
    val facesAvailable: Boolean = true,
)

/** What the grid shows: filters, sort, stacks, suggestions. */
data class CullControls(
    val filter: CullFilter = CullFilter(),
    val assistFilter: AssistFilter = AssistFilter.Any,
    val sort: CullSort = CullSort.Time,
    val stack: Boolean = true,
    val expanded: Set<String> = emptySet(),
    val suggesting: Boolean = false,
)

data class AnalysisProgress(val done: Int, val total: Int, val failed: Int, val running: Boolean) {
    val analysing: Boolean get() = done + failed < total
}

/** Everything the screens show, derived off the main thread from [CullCore], [CullControls] and the settings. */
data class CullView(
    val core: CullCore = CullCore(),
    val controls: CullControls = CullControls(),
    val settings: CullSettings = CullSettings(),
    val flags: Map<String, List<ShotFlag>> = emptyMap(),
    val bursts: Map<String, BurstPosition> = emptyMap(),
    val members: Map<String, List<String>> = emptyMap(),
    /** Burst id → its crowned frame. */
    val best: Map<String, String> = emptyMap(),
    val bestShots: Set<String> = emptySet(),
    /** Burst id → the frame its stack shows. */
    val covers: Map<String, String> = emptyMap(),
    val visible: List<Shot> = emptyList(),
    /** Bursts are one step / one cell (plain time view, no filter). */
    val collapse: Boolean = true,
    val items: List<GridItem> = emptyList(),
    val suggestions: Map<String, Suggestion> = emptyMap(),
    val progress: CullProgress = CullProgress(0, 0, 0, 0, 0, 0),
    val analysis: AnalysisProgress = AnalysisProgress(0, 0, 0, false),
    val assistCounts: Map<AssistFilter, Int> = emptyMap(),
) {
    val byId: Map<String, Shot> by lazy { core.shots.associateBy { it.id } }
    val readOnly: Boolean get() = core.access == SmbAccess.READ

    val picks: List<Shot> get() = core.shots.filter { core.decisions[it.id]?.flag == Flag.Pick }

    companion object {
        fun derive(core: CullCore, controls: CullControls, settings: CullSettings): CullView {
            val assist = settings.assist
            val flags = core.analyses.mapValues { (_, a) -> flagsOf(a, assist) }
            val bursts = if (core.metadataDone) groupBursts(core.shots, core.metadata, core.analyses, assist.burstGapMs) else emptyMap()
            val members = burstMembers(core.shots, bursts)
            val best = bestInBursts(core.shots, bursts, core.analyses, assist)
            val covers = members.mapValues { (burst, ids) -> burstCover(ids, core.decisions, best[burst]) }
            val byDecision = filterShots(core.shots, core.decisions, controls.filter)
            val byAnalysis = if (controls.assistFilter == AssistFilter.Any) byDecision else byDecision.filter { matchesAssist(flags[it.id], controls.assistFilter) }
            val visible = if (controls.sort == CullSort.Sharpness) sortBySharpness(byAnalysis, core.analyses) else byAnalysis
            val collapse = controls.stack && controls.sort == CullSort.Time && controls.filter.isAll && controls.assistFilter == AssistFilter.Any
            val items = gridItems(visible, bursts, covers, controls.expanded, collapse)
            val suggestions = if (controls.suggesting) suggest(core.shots, bursts, core.analyses, core.decisions, assist) else emptyMap()
            var done = 0
            var failed = 0
            for (shot in core.shots) if (shot.id in core.analyses) done++ else if (shot.id in core.failed) failed++
            val counts = AssistFilter.ALL.associateWith { f -> if (f == AssistFilter.Any) core.shots.size else core.shots.count { matchesAssist(flags[it.id], f) } }
            return CullView(
                core = core,
                controls = controls,
                settings = settings,
                flags = flags,
                bursts = bursts,
                members = members,
                best = best,
                bestShots = best.values.toSet(),
                covers = covers,
                visible = visible,
                collapse = collapse,
                items = items,
                suggestions = suggestions,
                progress = progressOf(core.shots, core.decisions),
                analysis = AnalysisProgress(done, core.shots.size, failed, core.analysing),
                assistCounts = counts,
            )
        }
    }
}

/** One-off messages for the screen (toasts). */
sealed interface CullEvent {
    data object SaveFailed : CullEvent
}

/**
 * A folder opened for culling (ADR 0008, 0009, 0011): the Kotlin side of `useCullFolder` + `useCullAssist`. Decisions
 * apply at once and are saved in the background (Room; since 2.2.0 never a sidecar: "Export XMP" writes those on
 * demand, ADR 0011 amendment); open and resume import
 * sidecars newer than Hoard's (precedence in [importAfter]); analyses come from the cache, then natively in small
 * batches around the shot on screen. Suggestions never decide: only [decide] writes.
 */
class CullViewModel(
    val key: CullKey,
    private val backend: CullBackend,
    private val store: CullStore,
    settings: Flow<CullSettings>,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = System::currentTimeMillis,
    /** The settings store (null in tests): the viewer's ⋯ toggles write through it. */
    private val appSettings: dev.hfps.hoard.app.data.AppSettings? = null,
    /** The Gallery (ADR 0021): newest first, like its grid; a folder keeps capture order. */
    private val newestFirst: Boolean = false,
    /** Analyse only this many shots around the one on screen (the Gallery: thousands of shots); null = all. */
    private val analysisWindow: Int? = null,
) : ViewModel() {
    /** A folder's culling (ADR 0008): decisions and caches in Hoard 1's tables for [key]. */
    constructor(
        key: CullKey,
        backend: CullBackend,
        dao: CullDao,
        settings: Flow<CullSettings>,
        compute: CoroutineDispatcher = Dispatchers.Default,
        now: () -> Long = System::currentTimeMillis,
        appSettings: dev.hfps.hoard.app.data.AppSettings? = null,
    ) : this(key, backend, FolderCullStore(dao, key), settings, compute, now, appSettings)

    /** Shots the paired server holds (ADR 0022), for the grid's "on the server" mark. */
    val serverShots: StateFlow<Set<String>> = (store.serverShots ?: kotlinx.coroutines.flow.flowOf(emptySet()))
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Shots decided here in the last seconds (their saved value may not have come back yet). Before `init`. */
    private val recent = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val core = MutableStateFlow(CullCore())
    private val controls = MutableStateFlow(CullControls())
    private val settingsState = settings.stateIn(viewModelScope, SharingStarted.Eagerly, CullSettings())

    val view: StateFlow<CullView> = combine(core, controls, settingsState) { c, k, s -> CullView.derive(c, k, s) }
        .flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CullView())

    private val events = Channel<CullEvent>(Channel.BUFFERED)
    val messages: Flow<CullEvent> = events.receiveAsFlow()

    /** Database writes, one after another (as the JS `serial`). */
    private val writes = Mutex()
    private var sidecarStates = HashMap<String, SidecarState>()
    private val touched = HashSet<String>()
    private val writing = HashMap<String, Int>()
    private var openJob: Job? = null
    private var analysisJob: Job? = null
    private var focus: String? = null
    private var urgentFocus = false

    private var imageLoader: CullImages? = null

    /** The folder's image loader (one per screen size), living as long as the folder is open. */
    fun images(longSide: Int): CullImages = imageLoader?.takeIf { it.longSide == longSide }
        ?: CullImages(backend, viewModelScope, longSide).also { imageLoader = it }

    init {
        load()
        store.external?.let { flow -> viewModelScope.launch { flow.collect(::mergeExternal) } }
    }

    /**
     * Decisions changed elsewhere while open (the Gallery: a sync brought another device's pick). Shots with a write
     * of ours on its way keep theirs.
     */
    private fun mergeExternal(external: Map<String, Decision>) {
        val c = core.value
        if (c.status != CullStatus.Ready) return
        var next: LinkedHashMap<String, Decision>? = null
        for (shot in c.shots) {
            if (shot.id in recent) continue
            val want = external[shot.id] ?: Decision.UNMARKED
            if (c.decisions.of(shot.id) == want) continue
            if (next == null) next = LinkedHashMap(c.decisions)
            if (want.unmarked) next.remove(shot.id) else next[shot.id] = want
        }
        if (next != null) core.update { it.copy(decisions = next) }
    }


    // --- Open ------------------------------------------------------------------------------------------------------

    fun reload() {
        core.value = CullCore()
        load()
    }

    private fun load() {
        openJob?.cancel()
        analysisJob?.cancel()
        openJob = viewModelScope.launch {
            try {
                val opened = backend.open()
                val rows = store.decisions()
                val session = store.session()
                val decisions = rows.associate { it.shotId to Decision(Flag.of(it.flag), it.rating) }.filterValues { !it.unmarked }
                core.update { it.copy(status = CullStatus.Ready, shots = opened.shots, access = opened.access, libRaw = opened.libRaw, decisions = decisions, session = session, errorCode = null) }
                serial { store.touchSession(shotCount = opened.shots.size) }
                // Sidecars (ADR 0011): what each shot's sidecar must be newer than, then import the newer ones.
                val sidecarRows = store.sidecars().associateBy { it.shotId }
                val decidedAt = rows.associate { it.shotId to it.updatedAt }
                sidecarStates = HashMap(opened.shots.associate { s ->
                    val r = sidecarRows[s.id]
                    s.id to sidecarStateOf(r?.decidedAt, r?.sidecarMtime, r?.synced == 1, decidedAt[s.id])
                })
                touched.clear()
                writing.clear()
                core.update { it.copy(sidecarsRefused = false) }
                startAnalysis()
                runCatching { readSidecars(opened.shots.map { it.id }, refresh = false) }
                    .onFailure { if (it is CancellationException) throw it }
                val ids = opened.shots.map { it.id }
                for (batch in ids.chunked(METADATA_BATCH)) {
                    val result = try {
                        backend.metadata(batch)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        batch.associateWith { null }
                    }
                    core.update { it.copy(metadata = it.metadata + result) }
                }
                core.update { it.copy(metadataDone = true, shots = sortByCapture(it.shots, it.metadata).let { s -> if (newestFirst) s.asReversed().toList() else s }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "open ${key.path} failed", e)
                core.update { it.copy(status = CullStatus.Error, errorCode = errorCodeOf(e), shots = emptyList()) }
            }
        }
    }

    /** Back from another app (Reveal, a file manager): sidecars changed there are read again (ADR 0011). */
    fun onResume() {
        val c = core.value
        if (c.status != CullStatus.Ready) return
        viewModelScope.launch { runCatching { readSidecars(c.shots.map { it.id }, refresh = true) } }
    }

    /** The app left the screen: pending sidecar writes go out now. */
    fun onPause() = backend.flush()

    private suspend fun readSidecars(ids: List<String>, refresh: Boolean) {
        val atOpen = !refresh
        ids.chunked(SIDECAR_BATCH).forEachIndexed { i, batch ->
            val after = batch.associateWith { importAfter(sidecarStates[it] ?: SidecarState.NONE) }
            val found = backend.readSidecars(after, refresh && i == 0)
            applyImports(found, atOpen)
        }
    }

    private fun applyImports(found: List<dev.hfps.hoard.smb.cull.SidecarFound>, atOpen: Boolean): Int {
        val next = LinkedHashMap(core.value.decisions)
        val changed = ArrayList<Triple<String, String?, Int>>()
        // ADR 0016 "Client rules" 4: an imported decision is dated by the sidecar's mtime, not the time of the import.
        val decidedAt = HashMap<String, Long>()
        val rows = ArrayList<CullDao.SidecarUpdate>()
        for (item in found) {
            val read = item.read ?: continue
            val mtime = item.modifiedMillis ?: continue
            if (item.shotId in writing || (atOpen && item.shotId in touched)) continue
            val current = next.of(item.shotId)
            val imported = importedDecision(current, read.fields, read.hasValues) ?: continue
            val state = sidecarStates[item.shotId]
            sidecarStates[item.shotId] = SidecarState(state?.decidedAt, mtime, true)
            rows += CullDao.SidecarUpdate(item.shotId, sidecarMtime = mtime, setSidecarMtime = true, synced = true)
            if (!importChanges(current, imported)) continue
            if (imported.unmarked) next.remove(item.shotId) else next[item.shotId] = imported
            changed += Triple(item.shotId, imported.flag?.wire, imported.rating)
            decidedAt[item.shotId] = mtime
        }
        if (changed.isNotEmpty()) core.update { it.copy(decisions = next) }
        if (changed.isNotEmpty() || rows.isNotEmpty()) {
            viewModelScope.launch {
                runCatching {
                    serial {
                        store.saveDecisions(changed, decidedAt)
                        store.saveSidecars(rows)
                    }
                }.onFailure { if (it !is CancellationException) events.trySend(CullEvent.SaveFailed) }
            }
        }
        return changed.size
    }

    // --- Decisions -------------------------------------------------------------------------------------------------

    /** Changes some shots' decisions as one undo step; null when nothing changed. */
    fun decide(shotIds: List<String>, next: (Decision, String) -> Decision): DecisionChange? {
        val c = core.value
        val change = changeOf(c.decisions, shotIds, next) ?: return null
        core.update { it.copy(decisions = applyChange(it.decisions, change), undo = pushUndo(it.undo, change)) }
        persist(change, undo = false)
        return change
    }

    /** Applies a precomputed change (keep this one, ✕ on a burst, accepted suggestions). */
    fun apply(change: DecisionChange?): DecisionChange? {
        change ?: return null
        val after = change.shotIds.zip(change.after).toMap()
        return decide(change.shotIds) { d, id -> after[id] ?: d }
    }

    fun undo(): DecisionChange? {
        val change = core.value.undo.lastOrNull() ?: return null
        core.update { it.copy(decisions = applyChange(it.decisions, change, undo = true), undo = it.undo.dropLast(1)) }
        persist(change, undo = true)
        return change
    }

    private fun persist(change: DecisionChange, undo: Boolean) {
        val values = if (undo) change.before else change.after
        val at = now()
        for (id in change.shotIds) {
            touched += id
            recent += id
            sidecarStates[id] = SidecarState(at, sidecarStates[id]?.sidecarMtime, false)
        }
        viewModelScope.launch {
            try {
                serial {
                    store.saveDecisions(change.shotIds.mapIndexed { i, id -> Triple(id, values[i].flag?.wire, values[i].rating) })
                    store.saveSidecars(change.shotIds.map { CullDao.SidecarUpdate(it, decidedAt = at, setDecidedAt = true, synced = false) })
                }
                delay(RECENT_MS)
                change.shotIds.forEach { recent -= it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "saving decisions failed", e)
                events.trySend(CullEvent.SaveFailed)
            }
        }
        // 2.2.0 (ADR 0011 amendment): no sidecar is written automatically; "Export XMP" does it on demand.
    }

    /** Deletes one file of the folder ("Delete rejects"). */
    suspend fun deleteFile(path: String) = backend.delete(path)

    /** Compare's ADR 0006 focus score for a shot the analysis has not reached ([CullBackend.focusScore]). */
    suspend fun focusScore(shotId: String): Double? = backend.focusScore(shotId)

    /** Shots that no longer exist (deleted rejects): dropped from the list and from the database. */
    suspend fun removeShots(shotIds: List<String>) {
        if (shotIds.isEmpty()) return
        val gone = shotIds.toSet()
        core.update { c ->
            c.copy(
                shots = c.shots.filter { it.id !in gone },
                decisions = c.decisions - gone,
                undo = c.undo.filter { change -> change.shotIds.none { it in gone } },
            )
        }
        serial { store.forgetShots(shotIds) }
        serial { store.touchSession(shotCount = core.value.shots.size) }
    }

    private var lastShotJob: Job? = null

    fun setLastShot(shotId: String) {
        lastShotJob?.cancel()
        lastShotJob = viewModelScope.launch {
            delay(800)
            runCatching { serial { store.touchSession(lastShotId = shotId, setLastShot = true) } }
            core.update { c -> c.copy(session = c.session?.copy(lastShotId = shotId)) }
        }
    }

    /** Waits for the database writes queued so far (before a hand-off that reads them, Edit in Reveal). */
    suspend fun settle() = writes.withLock { }

    private suspend fun <T> serial(block: suspend () -> T): T = writes.withLock { block() }

    fun setAutoAdvance(value: Boolean) {
        viewModelScope.launch { appSettings?.setCullAutoAdvance(value) }
    }

    fun setSwipeDecide(value: Boolean) {
        viewModelScope.launch { appSettings?.setCullSwipeDecide(value) }
    }

    // --- Controls --------------------------------------------------------------------------------------------------

    fun setFilter(filter: CullFilter) = controls.update { it.copy(filter = filter) }
    fun setAssistFilter(filter: AssistFilter) = controls.update { it.copy(assistFilter = filter) }
    fun setSort(sort: CullSort) = controls.update { it.copy(sort = sort) }
    fun setStack(stack: Boolean) = controls.update { it.copy(stack = stack, expanded = emptySet()) }
    fun toggleBurst(burst: String) = controls.update { it.copy(expanded = if (burst in it.expanded) it.expanded - burst else it.expanded + burst) }
    fun setSuggesting(on: Boolean) = controls.update { it.copy(suggesting = on) }

    /** Accepts suggestions (one undo step): `keep` picks, `reject` rejects. Returns how many shots changed. */
    fun accept(shotIds: List<String>, from: Collection<Suggestion>? = null): Int {
        val source = from?.associateBy { it.shotId } ?: view.value.suggestions
        val chosen = shotIds.mapNotNull { source[it] }
        if (chosen.isEmpty()) return 0
        val actions = chosen.associate { it.shotId to it.action }
        val change = decide(actions.keys.toList()) { d, id -> d.withFlag(if (actions[id] == SuggestionAction.Keep) Flag.Pick else Flag.Reject) }
        return change?.shotIds?.size ?: 0
    }

    // --- Assisted culling ------------------------------------------------------------------------------------------

    /** Analyse around this shot next (the one on screen); null = sweep from the top. */
    fun focus(shotId: String?) {
        focus = shotId
        urgentFocus = shotId != null
        if (shotId != null && shotId !in detailed) {
            detailed += shotId
            viewModelScope.launch {
                val more = runCatching { backend.details(shotId) }.getOrNull() ?: return@launch
                core.update { it.copy(metadata = it.metadata + (shotId to more)) }
            }
        }
    }

    private val detailed = HashSet<String>()

    private fun startAnalysis() {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            // 1. Cached analyses of this folder.
            val cached = HashMap<String, Analysis>()
            runCatching {
                val byPath = store.analyses().associateBy { it.path }
                for (shot in core.value.shots) {
                    val file = analysedFile(shot)
                    val row = byPath[file.path] ?: continue
                    if (row.size != file.size || row.modifiedAt != file.modifiedAt || row.version != ANALYSIS_VERSION) continue
                    AnalysisJson.read(row.analysis)?.let { cached[shot.id] = it }
                }
            }
            core.update { it.copy(analyses = cached, failed = emptySet(), analysing = true) }
            // 2. The rest, natively, nearest to the focus first.
            var attempts = 0
            try {
                while (true) {
                    val c = core.value
                    val focusIndex = focus?.let { id -> c.shots.indexOfFirst { it.id == id }.coerceAtLeast(0) } ?: 0
                    val window = analysisWindow
                    val pending = c.shots.withIndex().filter { (i, s) -> s.id !in c.analyses && s.id !in c.failed && (window == null || (focus != null && kotlin.math.abs(i - focusIndex) <= window)) }
                    if (pending.isEmpty()) {
                        if (window == null) break
                        // The Gallery: wait for the viewer to move on.
                        delay(400)
                        continue
                    }
                    fun distance(index: Int) = if (index >= focusIndex) (index - focusIndex).toDouble() else (focusIndex - index) * 1.5 + 0.5
                    val batch = pending.sortedBy { distance(it.index) }.take(ANALYSIS_BATCH).map { it.value }
                    val urgent = urgentFocus && batch.first().id == focus
                    try {
                        val result = backend.analyze(batch.map { it.id }, urgent)
                        attempts = 0
                        val rows = ArrayList<CullAnalysisEntity>()
                        val measured = HashMap<String, Analysis>()
                        val failedNow = HashSet<String>()
                        for (shot in batch) {
                            if (shot.id !in result.results) continue
                            val a = result.results[shot.id]
                            if (a == null) {
                                failedNow += shot.id
                                continue
                            }
                            measured[shot.id] = a
                            val file = analysedFile(shot)
                            rows += CullAnalysisEntity(key.locationId, key.share, file.path, key.path, file.size, file.modifiedAt, a.version, AnalysisJson.write(a), dev.hfps.hoard.data.legacy.isoNow())
                        }
                        core.update { it.copy(analyses = it.analyses + measured, failed = it.failed + failedNow, facesAvailable = result.faces) }
                        if (rows.isNotEmpty()) viewModelScope.launch { runCatching { serial { store.putAnalyses(rows) } } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (errorCodeOf(e) == ErrorCode.CANCELLED) {
                            delay(200)
                            continue
                        }
                        attempts++
                        if (attempts >= ANALYSIS_RETRIES) {
                            core.update { it.copy(failed = it.failed + batch.map { s -> s.id }) }
                            attempts = 0
                        } else {
                            delay(1500L * attempts)
                        }
                    }
                }
            } finally {
                core.update { it.copy(analysing = false) }
            }
        }
    }

    override fun onCleared() {
        backend.flush()
        backend.close()
    }

    companion object {
        const val TAG = "HoardCull"
        /** Metadata is read in batches of this many shots. */
        const val METADATA_BATCH = 60
        /** Sidecars are asked for in batches of this many shots. */
        const val SIDECAR_BATCH = 2000
        /** Shots per analysis call: small, so the order follows the user. */
        const val ANALYSIS_BATCH = 3
        const val ANALYSIS_RETRIES = 3
        /** How long a shot decided here ignores outside changes (its own write coming back). */
        const val RECENT_MS = 3000L
    }
}

