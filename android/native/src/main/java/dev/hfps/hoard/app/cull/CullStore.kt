package dev.hfps.hoard.app.cull

import dev.hfps.hoard.data.CullAnalysisEntity
import dev.hfps.hoard.data.CullDao
import dev.hfps.hoard.data.CullDecisionEntity
import dev.hfps.hoard.data.CullSessionEntity
import dev.hfps.hoard.data.CullSidecarEntity
import dev.hfps.hoard.data.CullSidecarReadEntity
import dev.hfps.hoard.smb.cull.SidecarStamp
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpFlag
import kotlinx.coroutines.flow.Flow

/**
 * Where a culling session keeps what it decides and caches (ADR 0008, 0009, 0011): a folder's rows in Hoard 1's tables
 * ([FolderCullStore]), or the Gallery's items (ADR 0021: the server's culling records for anything on the server, the
 * phone folder's rows for phone-only shots).
 */
interface CullStore {
    suspend fun decisions(): List<CullDecisionEntity>
    suspend fun session(): CullSessionEntity?
    suspend fun touchSession(shotCount: Int? = null, lastShotId: String? = null, setLastShot: Boolean = false)
    suspend fun sidecars(): List<CullSidecarEntity>
    /** [decidedAt]: when a listed shot's decision was made (Unix ms; a sidecar import's mtime), else now. */
    suspend fun saveDecisions(decisions: List<Triple<String, String?, Int>>, decidedAt: Map<String, Long> = emptyMap())
    suspend fun saveSidecars(rows: List<CullDao.SidecarUpdate>)
    suspend fun forgetShots(shotIds: List<String>)

    /** What Hoard last read from each shot's sidecar (shot id → read; ADR 0011 "First read of a sidecar"). */
    suspend fun sidecarReads(): Map<String, SidecarSeen> = emptyMap()

    /** Records the sidecars read ([put]) and forgets those that disappeared ([forget], shot ids). */
    suspend fun saveSidecarReads(put: Map<String, SidecarSeen>, forget: List<String>) = Unit

    /** Cached analyses, matched by `path` to the shot's analysed file. */
    suspend fun analyses(): List<CullAnalysisEntity>
    suspend fun putAnalyses(rows: List<CullAnalysisEntity>)

    /** Decisions changed elsewhere while the session is open (a sync); null when nothing else writes them. */
    val external: Flow<Map<String, Decision>>? get() = null

    /** Shots the paired server holds (ADR 0022: a mapped SMB folder), for the "on the server" mark; null = none. */
    val serverShots: Flow<Set<String>>? get() = null
}

/** A folder's culling, as Hoard 1 keeps it (`cull_decisions`, `cull_sidecars`, `cull_sessions`, `cull_analysis`). */
class FolderCullStore(private val dao: CullDao, private val key: CullKey) : CullStore {
    override suspend fun decisions() = dao.forFolder(key.locationId, key.share, key.path)
    override suspend fun session() = dao.session(key.locationId, key.share, key.path)
    override suspend fun touchSession(shotCount: Int?, lastShotId: String?, setLastShot: Boolean) =
        dao.touchSession(key.locationId, key.share, key.path, shotCount, lastShotId, setLastShot)
    override suspend fun sidecars() = dao.sidecars(key.locationId, key.share, key.path)
    override suspend fun saveDecisions(decisions: List<Triple<String, String?, Int>>, decidedAt: Map<String, Long>) =
        dao.saveDecisions(key.locationId, key.share, key.path, decisions, decidedAt)
    override suspend fun saveSidecars(rows: List<CullDao.SidecarUpdate>) = dao.saveSidecars(key.locationId, key.share, key.path, rows)
    override suspend fun forgetShots(shotIds: List<String>) = dao.forgetShots(key.locationId, key.share, key.path, shotIds)
    override suspend fun sidecarReads() = dao.sidecarReads(key.locationId, key.share, key.path).associate { it.shotId to it.toSeen() }
    override suspend fun saveSidecarReads(put: Map<String, SidecarSeen>, forget: List<String>) =
        dao.saveSidecarReads(key.locationId, key.share, key.path, put.map { (id, seen) -> seen.toEntity(key, id) }, forget)
    override suspend fun analyses() = dao.analyses(key.locationId, key.share, key.path)
    override suspend fun putAnalyses(rows: List<CullAnalysisEntity>) = dao.putAnalyses(rows)
}

fun CullSidecarReadEntity.toSeen() = SidecarSeen(
    SidecarStamp(name, size, modifiedMillis),
    XmpFields(rating.coerceIn(0, XmpFields.MAX_RATING), XmpFlag.parse(flag) ?: XmpFlag.NONE, label?.takeIf { it.isNotBlank() }),
)

fun SidecarSeen.toEntity(key: CullKey, shotId: String) = CullSidecarReadEntity(
    key.locationId, key.share, key.path, shotId, stamp.name, stamp.size, stamp.modifiedMillis, fields.rating, fields.flag.wire, fields.label,
)
