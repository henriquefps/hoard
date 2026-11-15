package dev.hfps.hoard.app.files

import android.content.Context
import android.net.Uri
import dev.hfps.hoard.app.transfers.TransferPlace
import dev.hfps.hoard.data.CullDao
import dev.hfps.hoard.data.CullDecisionEntity
import dev.hfps.hoard.image.format.ImageFormats
import dev.hfps.hoard.smb.share.RevealHandoff
import dev.hfps.hoard.smb.transfer.DocumentTransferFs
import dev.hfps.hoard.smb.transfer.Endpoint
import dev.hfps.hoard.smb.transfer.TransferManager
import dev.hfps.hoard.smb.transfer.TransferPaths
import dev.hfps.hoard.xmp.XmpExport
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * "Export XMP" (ADR 0011, amendment "2.2.0: export on demand") for a selection, a folder or a culling batch: Hoard's
 * decisions of those shots (the folder's `cull_decisions`) written as sidecars next to the photos, or, when the
 * location refuses (read-only share, drive, `ACCESS_DENIED`), into a folder the user picks (SAF). Shots without a
 * decision are skipped. The rules are the writer's ([XmpExport]): merge into the shot's sidecar, never touch a
 * malformed one.
 */
class XmpExporter(
    private val context: Context,
    private val dao: CullDao,
    private val endpointOf: suspend (TransferPlace) -> Endpoint,
) {
    /**
     * Exports the shots of [paths] (null: every photo of [folderPath]) of [place]. With [treeUri] the sidecars go into
     * that picked folder instead (`XmpExport.Result.readOnly` from the first call is the cue to ask for one).
     */
    suspend fun export(place: TransferPlace, folderPath: String, paths: List<String>?, hidden: Boolean, treeUri: String? = null): XmpExport.Result = withContext(Dispatchers.IO) {
        val source = TransferManager.get(context).endpoints.open(endpointOf(place))
        source.use { fs ->
            val photos = paths ?: fs.list(folderPath).filter { !it.isDirectory }.map { TransferPaths.join(folderPath, it.name) }
            val decisions = dao.forFolder(place.locationId, place.share.orEmpty(), folderPath)
            val shots = shotsToExport(photos, decisions)
            if (shots.isEmpty()) return@withContext XmpExport.Result()
            val check = coroutineContext
            if (treeUri != null) {
                DocumentTransferFs.forTree(context, Uri.parse(treeUri)).use { tree ->
                    XmpExport.export(tree, "", shots, hidden, isCancelled = { !check.isActive })
                }
            } else {
                val result = XmpExport.export(fs, folderPath, shots, hidden, isCancelled = { !check.isActive })
                // The folder's culling knows these sidecars hold its decisions: opening it does not import them back.
                val rows = result.names.map { (stem, written) ->
                    CullDao.SidecarUpdate(stem.lowercase(), sidecarMtime = written.second, setSidecarMtime = written.second != null, synced = true)
                }
                if (rows.isNotEmpty()) runCatching { dao.saveSidecars(place.locationId, place.share.orEmpty(), folderPath, rows) }
                coroutineContext.ensureActive()
                result
            }
        }
    }

    companion object {
        /**
         * The shots of [paths] that have a decision in [decisions] (by the lower-case stem, the culling shot id), with
         * their fields (pure, JVM-tested): photos only, the files of a shot together, undecided shots left out.
         */
        fun shotsToExport(paths: List<String>, decisions: List<CullDecisionEntity>): List<XmpExport.Shot> {
            val photos = paths.filter { ImageFormats.isPhoto(it) }
            if (photos.isEmpty()) return emptyList()
            val byShot = decisions.associateBy { it.shotId }
            return RevealHandoff.groups(RevealHandoff.order(photos)).mapNotNull { group ->
                val decision = byShot[group.stem.lowercase()] ?: return@mapNotNull null
                val flag = XmpFlag.parse(decision.flag) ?: XmpFlag.NONE
                if (flag == XmpFlag.NONE && decision.rating <= 0) return@mapNotNull null
                XmpExport.Shot(group.stem, group.files.map { it.substringAfterLast('/') }, XmpFields(decision.rating.coerceIn(0, XmpFields.MAX_RATING), flag))
            }
        }
    }
}
