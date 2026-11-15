package dev.hfps.hoard.app.cull

import dev.hfps.hoard.data.CullAnalysisEntity
import dev.hfps.hoard.data.CullDao
import dev.hfps.hoard.data.CullDecisionEntity
import dev.hfps.hoard.data.CullSessionEntity
import dev.hfps.hoard.data.CullSidecarEntity
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
    /** Cached analyses, matched by `path` to the shot's analysed file. */
    suspend fun analyses(): List<CullAnalysisEntity>
    suspend fun putAnalyses(rows: List<CullAnalysisEntity>)

    /** Decisions changed elsewhere while the session is open (a sync); null when nothing else writes them. */
    val external: Flow<Map<String, Decision>>? get() = null
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
    override suspend fun analyses() = dao.analyses(key.locationId, key.share, key.path)
    override suspend fun putAnalyses(rows: List<CullAnalysisEntity>) = dao.putAnalyses(rows)
}
