package dev.hfps.hoard.app.cull

import android.content.Context
import android.util.Log
import dev.hfps.hoard.app.gallery.GalleryRepository
import dev.hfps.hoard.data.CullAnalysisEntity
import dev.hfps.hoard.data.CullDao
import dev.hfps.hoard.data.CullDecisionEntity
import dev.hfps.hoard.data.legacy.isoOf
import dev.hfps.hoard.server.db.CullingEntity
import dev.hfps.hoard.server.db.DevicePushedEntity
import dev.hfps.hoard.server.db.PendingCullingEntity
import dev.hfps.hoard.server.sync.CullValues
import dev.hfps.hoard.server.sync.CullingRules
import dev.hfps.hoard.server.sync.GallerySyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/**
 * The dual write of ADR 0022, pure and JVM-tested (`MappedCullingTest`): a decision on a shot of a mapped SMB folder
 * goes to its folder rows and sidecar (ADR 0011, unchanged) **and** to the server's culling of the same item (ADR 0016);
 * a value pulled from the server shows on the shot when it is newer than the folder's row (ADR 0016 rule 4).
 */
object MappedCulling {
    /** One server change: the shot's new values on [itemId], dated [at]. */
    data class ServerDecision(val shotId: String, val itemId: String, val values: CullValues, val at: Long)

    /** The server changes for saved decisions (shot, flag, rating) of mapped shots ([items]: shot id → item id). */
    fun serverDecisions(decisions: List<Triple<String, String?, Int>>, items: Map<String, String>, decidedAt: Map<String, Long>, now: Long): List<ServerDecision> =
        decisions.mapNotNull { (shot, flag, rating) ->
            val item = items[shot] ?: return@mapNotNull null
            ServerDecision(shot, item, CullValues(rating.coerceIn(0, 5), flag?.takeIf { it == "pick" || it == "reject" }), decidedAt[shot] ?: now)
        }

    /**
     * The server's side of every item of [ids] in a few queries ([CHUNK] ids each: records and pending changes loaded
     * with `IN (...)`, never one query per item): item id → values and time, for the items with something to show.
     */
    suspend fun serverSides(ids: Collection<String>, records: suspend (List<String>) -> List<CullingEntity>, pending: suspend (List<String>) -> List<PendingCullingEntity>): Map<String, Pair<CullValues, Long>> {
        if (ids.isEmpty()) return emptyMap()
        val distinct = ids.distinct()
        val byId = HashMap<String, CullingEntity>()
        val queued = HashSet<String>()
        for (chunk in distinct.chunked(CHUNK)) {
            records(chunk).forEach { byId[it.itemId] = it }
            pending(chunk).forEach { queued += it.itemId }
        }
        val out = HashMap<String, Pair<CullValues, Long>>()
        for (id in distinct) serverSide(byId[id], queued = id in queued)?.let { out[id] = it }
        return out
    }

    /** `IN (...)` chunk size (below SQLite's 999 bound variables of older Android versions). */
    const val CHUNK = 500

    /** The server's side of an item: its values and when the newest of rating / flag was written; null = nothing to show. */
    fun serverSide(record: CullingEntity?, pending: List<PendingCullingEntity>): Pair<CullValues, Long>? = serverSide(record, pending.isNotEmpty())

    fun serverSide(record: CullingEntity?, queued: Boolean): Pair<CullValues, Long>? {
        // A change of ours still on its way: the folder row already has it.
        if (record == null || queued) return null
        val at = maxOf(record.ratingAt ?: 0L, record.flagAt ?: 0L)
        if (at <= 0L) return null
        return CullingRules.serverValues(record).copy(label = "") to at
    }

    /**
     * The folder's rows with the server's newer values over them: a mapped shot whose server record is newer than its
     * row (or has no row) shows the server's rating and flag, dated by the server's `modifiedAt` (so an older sidecar
     * does not win over it when the folder opens, ADR 0016 rule 4). Unmapped shots: the rows as they are.
     */
    fun merge(rows: List<CullDecisionEntity>, items: Map<String, String>, server: Map<String, Pair<CullValues, Long>>, template: CullDecisionEntity): List<CullDecisionEntity> {
        val byShot = rows.associateBy { it.shotId }.toMutableMap()
        for ((shot, item) in items) {
            val (values, at) = server[item] ?: continue
            val row = byShot[shot]
            val rowAt = row?.updatedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
            if (row != null && rowAt >= at) continue
            if (row == null && values.rating == 0 && values.flag == null) continue
            if (row != null && row.rating == values.rating && row.flag == values.flag) continue
            byShot[shot] = (row ?: template.copy(shotId = shot)).copy(flag = values.flag, rating = values.rating, updatedAt = isoOf(at))
        }
        return byShot.values.filter { it.flag != null || it.rating > 0 }
    }
}

/**
 * A folder's culling on a mapped SMB share (ADR 0022): [FolderCullStore] as it is, plus the server's culling for the
 * mapped shots. [items] gives the folder's mapped shots (shot id → item id) once the folder is resolved; until then
 * (and for unmapped shots) it behaves exactly as [FolderCullStore].
 */
class MappedCullStore(
    private val context: Context,
    private val delegate: FolderCullStore,
    private val dao: CullDao,
    private val key: CullKey,
    private val gallery: GalleryRepository,
    private val items: suspend () -> Map<String, String>,
    private val now: () -> Long = System::currentTimeMillis,
) : CullStore {
    /** The shots' last saved values, to re-date their server changes by the sidecar's mtime ([saveSidecars]). */
    private val saved = HashMap<String, CullValues>()
    private val mapped = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Shot ids the server holds: the "on the server" mark of the grid. */
    override val serverShots: Flow<Set<String>> = mapped.map { it.keys }

    private var itemsAt = Long.MIN_VALUE / 2

    /**
     * The folder's mapped shots, asked again at most once a minute (an unmapped folder must not be listed again for
     * every decision or sync; the resolution itself is cached for 5 minutes, ADR 0022).
     */
    private suspend fun itemsNow(): Map<String, String> {
        if (now() - itemsAt < ITEMS_TTL_MS) return mapped.value
        val found = try {
            items()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyMap()
        }
        itemsAt = now()
        mapped.value = found
        return found
    }

    /** The server's side of the mapped items: a few chunked queries, whatever the folder's size. */
    private suspend fun serverSides(items: Map<String, String>): Map<String, Pair<CullValues, Long>> =
        MappedCulling.serverSides(items.values, { gallery.db.culling().getAll(it) }, { gallery.db.culling().pendingForItems(it) })

    private fun template() = CullDecisionEntity(key.locationId, key.share, key.path, "", null, 0, isoOf(now()))

    override suspend fun decisions(): List<CullDecisionEntity> {
        val rows = delegate.decisions()
        val items = itemsNow()
        if (items.isEmpty()) return rows
        if (!handedOver) {
            handedOver = true
            runCatching { handOver(rows, items) }.onFailure { Log.w(TAG, "hand-over of ${key.path} failed", it) }
        }
        val sides = serverSides(items)
        lastSides = items to sides
        return MappedCulling.merge(rows, items, sides, template())
    }

    /** The server sides last shown (with the items they were read for): a change that leaves them as they are is skipped. */
    @Volatile
    private var lastSides: Pair<Map<String, String>, Map<String, Pair<CullValues, Long>>>? = null

    private var handedOver = false

    /** `device_pushed` key of a shot of this folder (the Gallery's phone shots use `<location>/<dir>/<stem>`). */
    private fun pushedKey(shot: String) = "smb:${key.locationId}/${key.share.lowercase()}/${key.path}/$shot"

    /**
     * Decisions made in this folder before it was mapped (Hoard 1's culling of the share, a sidecar import) are handed
     * to the server once, with their own times and `baseRev` 0, like the Gallery's phone shots (ADR 0021 `Handover`):
     * the server keeps whichever is newer. A shot whose server value is already newer is only marked.
     */
    private suspend fun handOver(rows: List<CullDecisionEntity>, items: Map<String, String>) {
        val account = gallery.db.account().get() ?: return
        if (!account.has("culling")) return
        val pushed = gallery.db.device().pushed().associateBy { it.shotKey }
        val sidecars = delegate.sidecars().associateBy { it.shotId }
        val byShot = rows.associateBy { it.shotId }
        val at = now()
        val done = ArrayList<DevicePushedEntity>()
        var queued = 0
        for ((shot, item) in items) {
            val k = pushedKey(shot)
            val step = dev.hfps.hoard.app.gallery.Handover.step(item, byShot[shot], sidecars[shot], pushed[k], at) ?: continue
            step.promotion?.let { p ->
                val record = gallery.db.culling().get(item)
                if (maxOf(record?.ratingAt ?: 0L, record?.flagAt ?: 0L) < p.at) {
                    gallery.sync.promote(item, p.values, p.at, clears = p.clears)
                    queued++
                }
            }
            done += DevicePushedEntity(k, item, step.pushedAt)
        }
        if (done.isNotEmpty()) gallery.db.device().putPushed(done)
        if (queued > 0) {
            Log.i(TAG, "handed $queued earlier decisions of ${key.path} to the server")
            GallerySyncWorker.soon(context)
        }
    }

    override suspend fun session() = delegate.session()
    override suspend fun touchSession(shotCount: Int?, lastShotId: String?, setLastShot: Boolean) = delegate.touchSession(shotCount, lastShotId, setLastShot)
    override suspend fun sidecars() = delegate.sidecars()

    override suspend fun saveDecisions(decisions: List<Triple<String, String?, Int>>, decidedAt: Map<String, Long>) {
        delegate.saveDecisions(decisions, decidedAt)
        for ((shot, flag, rating) in decisions) saved[shot] = CullValues(rating, flag)
        val changes = MappedCulling.serverDecisions(decisions, itemsNow(), decidedAt, now())
        if (changes.isEmpty()) return
        // Records and queues of every changed item in a few queries, not two per decision.
        val ids = changes.map { it.itemId }.distinct()
        val records = ids.chunked(MappedCulling.CHUNK).flatMap { gallery.db.culling().getAll(it) }.associateBy { it.itemId }
        val queued = ids.chunked(MappedCulling.CHUNK).flatMap { gallery.db.culling().pendingForItems(it) }.groupBy { it.itemId }
        for (c in changes) {
            val before = CullingRules.effective(records[c.itemId], queued[c.itemId].orEmpty())
            // The label is not culling's (Hoard has none): it stays as the server has it.
            gallery.sync.decide(c.itemId, before, before.copy(rating = c.values.rating, flag = c.values.flag), c.at)
        }
        // Queued here already: the row's write is not an earlier decision to hand over.
        gallery.db.device().putPushed(changes.map { DevicePushedEntity(pushedKey(it.shotId), it.itemId, now() + 1000) })
        Log.i(TAG, "queued ${changes.size} decisions of ${key.path} for the server")
        GallerySyncWorker.soon(context)
    }

    /** A sidecar written with a shot's latest decision: its pending server changes take the sidecar's mtime. */
    override suspend fun saveSidecars(rows: List<CullDao.SidecarUpdate>) {
        delegate.saveSidecars(rows)
        val items = mapped.value
        for (r in rows) {
            val mtime = r.sidecarMtime ?: continue
            if (!r.setSidecarMtime || r.synced != true) continue
            val item = items[r.shotId] ?: continue
            val values = saved[r.shotId] ?: continue
            gallery.sync.redate(item, values, mtime)
        }
    }

    override suspend fun forgetShots(shotIds: List<String>) = delegate.forgetShots(shotIds)
    override suspend fun analyses(): List<CullAnalysisEntity> = delegate.analyses()
    override suspend fun putAnalyses(rows: List<CullAnalysisEntity>) = delegate.putAnalyses(rows)

    /**
     * A sync that brings another device's decision on a mapped shot shows it while the folder is open. Driven by a
     * change signal of the culling tables (no rows read), coalesced; each signal re-reads only the mapped items' server
     * sides (a few chunked queries), and the folder's rows are merged again only when those sides changed (our own
     * decisions change the rows the view model already has).
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    override val external: Flow<Map<String, Decision>>
        get() = combine(gallery.db.cullingChanges(), mapped) { _, items -> items }
            .debounce(EXTERNAL_DEBOUNCE_MS)
            .conflate()
            .mapNotNull { items ->
                if (items.isEmpty()) return@mapNotNull null
                val sides = serverSides(items)
                if (lastSides == (items to sides)) return@mapNotNull null
                lastSides = items to sides
                MappedCulling.merge(delegate.decisions(), items, sides, template())
                    .associate { it.shotId to Decision(Flag.of(it.flag), it.rating) }.filterValues { !it.unmarked }
            }
            .distinctUntilChanged()

    companion object {
        const val TAG = "HoardCull"
        const val ITEMS_TTL_MS = 60_000L
        /** A burst of changes (a sync page, a run of decisions) is one re-read. */
        const val EXTERNAL_DEBOUNCE_MS = 300L
    }
}
