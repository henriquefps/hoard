package dev.hfps.hoard.app.cull

import android.content.Context
import android.util.Log
import dev.hfps.hoard.app.gallery.GalleryRepository
import dev.hfps.hoard.data.CullAnalysisEntity
import dev.hfps.hoard.data.CullDao
import dev.hfps.hoard.data.CullDecisionEntity
import dev.hfps.hoard.data.legacy.isoOf
import dev.hfps.hoard.server.db.CullingEntity
import dev.hfps.hoard.server.db.PendingCullingEntity
import dev.hfps.hoard.server.sync.CullValues
import dev.hfps.hoard.server.sync.CullingRules
import dev.hfps.hoard.server.sync.GallerySyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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

    /** The server's side of an item: its values and when the newest of rating / flag was written; null = nothing to show. */
    fun serverSide(record: CullingEntity?, pending: List<PendingCullingEntity>): Pair<CullValues, Long>? {
        // A change of ours still on its way: the folder row already has it.
        if (record == null || pending.isNotEmpty()) return null
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

    private suspend fun itemsNow(): Map<String, String> = runCatching { items() }.getOrDefault(emptyMap()).also { mapped.value = it }

    private suspend fun serverSides(items: Map<String, String>): Map<String, Pair<CullValues, Long>> {
        if (items.isEmpty()) return emptyMap()
        val records = items.values.distinct().chunked(500).flatMap { gallery.db.culling().getAll(it) }.associateBy { it.itemId }
        return items.values.distinct().mapNotNull { id ->
            MappedCulling.serverSide(records[id], gallery.db.culling().pendingFor(id))?.let { id to it }
        }.toMap()
    }

    private fun template() = CullDecisionEntity(key.locationId, key.share, key.path, "", null, 0, isoOf(now()))

    override suspend fun decisions(): List<CullDecisionEntity> {
        val rows = delegate.decisions()
        val items = itemsNow()
        if (items.isEmpty()) return rows
        return MappedCulling.merge(rows, items, serverSides(items), template())
    }

    override suspend fun session() = delegate.session()
    override suspend fun touchSession(shotCount: Int?, lastShotId: String?, setLastShot: Boolean) = delegate.touchSession(shotCount, lastShotId, setLastShot)
    override suspend fun sidecars() = delegate.sidecars()

    override suspend fun saveDecisions(decisions: List<Triple<String, String?, Int>>, decidedAt: Map<String, Long>) {
        delegate.saveDecisions(decisions, decidedAt)
        for ((shot, flag, rating) in decisions) saved[shot] = CullValues(rating, flag)
        val changes = MappedCulling.serverDecisions(decisions, mapped.value.ifEmpty { itemsNow() }, decidedAt, now())
        if (changes.isEmpty()) return
        for (c in changes) {
            val before = CullingRules.effective(gallery.db.culling().get(c.itemId), gallery.db.culling().pendingFor(c.itemId))
            // The label is not culling's (Hoard has none): it stays as the server has it.
            gallery.sync.decide(c.itemId, before, before.copy(rating = c.values.rating, flag = c.values.flag), c.at)
        }
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

    /** A sync that brings another device's decision on a mapped shot shows it while the folder is open. */
    override val external: Flow<Map<String, Decision>>
        get() = combine(gallery.db.culling().observeAll(), gallery.db.culling().observePending(), mapped) { _, _, items -> items }
            .map { items ->
                if (items.isEmpty()) return@map null
                decisions().associate { it.shotId to Decision(Flag.of(it.flag), it.rating) }.filterValues { !it.unmarked }
            }
            .distinctUntilChanged()
            .let { flow -> kotlinx.coroutines.flow.flow { flow.collect { if (it != null) emit(it) } } }

    companion object {
        const val TAG = "HoardCull"
    }
}
