package dev.hfps.hoard.app.files

import android.content.Context
import android.net.Uri
import dev.hfps.hoard.app.transfers.TransferPlace
import dev.hfps.hoard.app.cull.CullKey
import dev.hfps.hoard.app.cull.SidecarSeen
import dev.hfps.hoard.app.cull.toEntity
import dev.hfps.hoard.data.CullDao
import dev.hfps.hoard.data.CullSidecarReadEntity
import dev.hfps.hoard.smb.cull.SidecarStamp
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
                // The folder's culling knows these sidecars hold its decisions, and records them as read (ADR 0011 "First
                // read of a sidecar"): opening it neither imports them back nor takes them for another tool's sidecar.
                val key = CullKey(place.locationId, place.share.orEmpty(), folderPath)
                val rows = result.names.map { (stem, written) ->
                    CullDao.SidecarUpdate(stem.lowercase(), sidecarMtime = written.modifiedMillis, setSidecarMtime = written.modifiedMillis != null, synced = true)
                }
                val fields = shots.associate { it.stem to it.fields }
                val reads = exportedReads(key, result.names, fields)
                if (rows.isNotEmpty()) {
                    runCatching {
                        dao.saveSidecars(key.locationId, key.share, key.path, rows)
                        dao.saveSidecarReads(key.locationId, key.share, key.path, reads, emptyList())
                    }
                }
                coroutineContext.ensureActive()
                result
            }
        }
    }

    companion object {
        /**
         * The `cull_sidecar_reads` rows of an export (pure, JVM-tested): each sidecar written, with its size and mtime
         * after the write and the values written (the label is not Hoard's: the sidecar keeps whatever it had).
         */
        fun exportedReads(key: CullKey, written: Map<String, XmpExport.Written>, fields: Map<String, XmpFields>): List<CullSidecarReadEntity> =
            written.mapNotNull { (stem, w) ->
                val values = fields[stem] ?: return@mapNotNull null
                // As the reader will see it: a reject's stars are not in the file.
                val read = values.copy(rating = if (values.flag == XmpFlag.REJECT) 0 else values.rating, label = null)
                SidecarSeen(SidecarStamp(w.name, w.size, w.modifiedMillis), read).toEntity(key, stem.lowercase())
            }

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
