package dev.hfps.hoard.app.files

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import dev.hfps.hoard.app.transfers.FolderPlace
import dev.hfps.hoard.app.transfers.LiveTransfer
import dev.hfps.hoard.app.transfers.MEDIASTORE_LOCATION_ID
import dev.hfps.hoard.app.transfers.TransferPlace
import dev.hfps.hoard.app.transfers.TransferRequest
import dev.hfps.hoard.app.transfers.TransferService
import dev.hfps.hoard.data.RevealCopyDao
import dev.hfps.hoard.data.RevealCopyEntity
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.transfer.FileStat
import dev.hfps.hoard.smb.transfer.ItemKinds
import dev.hfps.hoard.smb.transfer.MediaStoreCopies
import dev.hfps.hoard.smb.transfer.MediaStoreTarget
import dev.hfps.hoard.smb.transfer.TransferItem
import dev.hfps.hoard.smb.transfer.TransferPaths
import dev.hfps.hoard.smb.transfer.TransferStates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Which way each photo of a hand-off goes to Reveal (ADR 0010, amendment "2.2.0: local copies, no streaming"; pure,
 * JVM-tested): a **server reference** when the paired server holds the shot (decided before, by [SmbReveal]); its
 * **MediaStore URI** when it is on the phone and MediaStore has it; otherwise a **copy** into MediaStore, reused while
 * the source keeps the size and mtime it had when copied.
 */
object RevealRoutes {
    data class Plan(
        /** Photos sent by an existing MediaStore URI: on the phone, or a copy made before (path → URI). */
        val uris: Map<String, String>,
        /** Photos to copy first, in hand-off order. */
        val copy: List<String>,
    )

    /**
     * [paths]: the photos not sent by reference, in hand-off order. [onPhone]: MediaStore's URI of each photo of the
     * phone's own storage (empty for other locations). [stats]: each source's size and mtime now (null when it could
     * not be read: it is copied, and the copy fails with the source's error). [copies]: the copies recorded for these
     * paths (several per path when the source changed between copies, newest first); [exists] says whether MediaStore
     * still has that copy, of that size, in Hoard's copy folders ([isCopy]).
     */
    fun plan(
        paths: List<String>,
        onPhone: Map<String, String>,
        stats: Map<String, FileStat?>,
        copies: Map<String, List<RevealCopyEntity>>,
        exists: (uri: String, size: Long) -> Boolean,
    ): Plan {
        val uris = LinkedHashMap<String, String>()
        val copy = ArrayList<String>()
        for (path in paths) {
            onPhone[path]?.let { uris[path] = it; continue }
            val stat = stats[path]
            val row = stat?.let { s -> copies[path].orEmpty().firstOrNull { reusable(it, s) && exists(it.uri, it.size) } }
            if (row != null) uris[path] = row.uri else copy += path
        }
        return Plan(uris, copy)
    }

    /** Hoard's copy folders (exact case, as MediaStore stores `RELATIVE_PATH`): `Pictures/Hoard/…`, `Download/Hoard/…`. */
    val COPY_FOLDERS = listOf("Pictures/Hoard/", "Download/Hoard/")

    /** What MediaStore has now at a recorded copy's URI. */
    data class Current(val relativePath: String, val size: Long)

    /**
     * Whether the row MediaStore has at a recorded URI is still that copy: in one of Hoard's copy folders (exact case;
     * a copy moved out, or an id MediaStore reused for another file after a rebuild, is not) with the recorded size.
     */
    fun isCopy(row: RevealCopyEntity, current: Current?): Boolean =
        current != null && current.size == row.size && COPY_FOLDERS.any { current.relativePath.startsWith(it) }

    /** "Delete copies": the recorded copies still in place ([delete]), and the records that no longer stand for one ([forget]). */
    data class Deletion(val delete: List<RevealCopyEntity>, val forget: List<RevealCopyEntity>)

    /**
     * Which recorded copies "Delete copies" deletes (pure, JVM-tested): only rows of `reveal_copies` (never a file Hoard
     * owns but did not record: it may be one the user copied there with Hoard's file manager), and only while [current]
     * says they are still copies ([isCopy]). Superseded copies of a changed source are rows too, so they are counted
     * and deleted. Records whose file is gone or moved are forgotten, the file left alone.
     */
    fun deletion(recorded: List<RevealCopyEntity>, current: (String) -> Current?): Deletion {
        val (delete, forget) = recorded.partition { isCopy(it, current(it.uri)) }
        return Deletion(delete, forget)
    }

    /**
     * The `reveal_copies` rows for a copy job's completed items (pure, JVM-tested): [asked] maps the engine's normalized
     * source paths back to the hand-off's; [find] is the published row's URI by destination folder and name. Saved
     * whatever the job's outcome, so a job that fails or is cancelled midway leaves its published copies recorded and
     * a retry reuses them instead of making `name (1).ext` duplicates.
     */
    fun records(items: List<TransferItem>, asked: Map<String, String>, sourceKey: String, now: String, find: (folder: String, name: String) -> String?): List<RevealCopyEntity> =
        copied(items).mapNotNull { item ->
            val path = asked[item.sourcePath] ?: return@mapNotNull null
            val uri = find(TransferPaths.parent(item.destPath), TransferPaths.name(item.destPath)) ?: return@mapNotNull null
            RevealCopyEntity(uri = uri, sourceKey = sourceKey, sourcePath = path, size = item.size, modifiedMillis = item.modifiedMillis, copiedAt = now)
        }

    /** A copy stands for its source while the source has the size and mtime it had when copied. */
    fun reusable(row: RevealCopyEntity, stat: FileStat): Boolean =
        !stat.isDirectory && row.size == stat.size && row.modifiedMillis != null && row.modifiedMillis == stat.modifiedMillis

    /** The batch's folder under `Pictures/Hoard/`: its title, else the day (`yyyy-MM-dd`). */
    fun folder(title: String?, today: LocalDate): String = MediaStoreTarget.folderName(title, today.toString())

    /**
     * After the copy job: each copied source path → its destination name in [folder] (items of other kinds, failed
     * or skipped are left out), with the source's size and mtime the runner saw.
     */
    fun copied(items: List<TransferItem>): List<TransferItem> =
        items.filter { it.kind == ItemKinds.FILE && !it.superseded && it.state == TransferStates.COMPLETED }

    /** The first failure of a job that did not copy everything, for the hand-off's error. */
    fun failure(state: String, items: List<TransferItem>, wanted: Int): HoardSmbException? {
        if (state == TransferStates.CANCELLED) return HoardSmbException(ErrorCode.CANCELLED, "Cancelled")
        if (copied(items).size >= wanted) return null
        val failed = items.firstOrNull { it.state == TransferStates.FAILED }
        return HoardSmbException(failed?.errorCode ?: ErrorCode.UNKNOWN, failed?.errorMessage ?: "Some photos were not copied")
    }
}

/**
 * The copies Hoard makes for Reveal (ADR 0010): finds what can be reused, copies the rest through the transfer engine
 * into MediaStore (`Endpoint.MediaStore`, one job per hand-off, visible in Transfers), records each copy in
 * `reveal_copies`, and deletes them all on request (Settings › "Photos copied for Reveal").
 */
class RevealCopies(
    private val context: Context,
    private val dao: RevealCopyDao,
    private val transfers: () -> TransferService,
    private val statSources: suspend (BrowseLocation, List<String>) -> Map<String, FileStat?>,
) {
    /** What the copies take: how many and how many bytes, as MediaStore has them now. */
    data class Usage(val count: Int, val bytes: Long)

    /**
     * The `content://media/…` URI of each photo of the phone's storage that MediaStore indexes (by folder and name).
     * Other locations: none.
     */
    suspend fun onPhone(location: BrowseLocation, paths: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (location.kind != LocationKind.Device || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext emptyMap()
        val out = HashMap<String, String>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        for ((folder, inFolder) in paths.groupBy { TransferPaths.parent(it.trim('/')) }) {
            val names = inFolder.associateBy { TransferPaths.name(it) }
            runCatching {
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf(if (folder.isEmpty()) "" else "$folder/"),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val path = names[c.getString(1) ?: continue] ?: continue
                        out[path] = android.content.ContentUris.withAppendedId(collection, c.getLong(0)).toString()
                    }
                }
            }.onFailure { Log.w(TAG, "MediaStore lookup failed in $folder", it) }
        }
        out
    }

    /**
     * The URI of every photo in [paths] (hand-off order) not on the phone: reused copies, then a copy job for the
     * rest, reporting its progress to [onProgress] until it ends. Cancelling the calling coroutine cancels the job
     * (its partial copies are deleted). Throws `CANCELLED` when the job was cancelled, the job's error when a photo
     * could not be copied.
     */
    suspend fun ensure(location: BrowseLocation, paths: List<String>, onPhone: Map<String, String>, title: String?, onProgress: (LiveTransfer?) -> Unit): Map<String, String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ensureQ(location, paths, onPhone, title, onProgress) else smbFail(ErrorCode.NO_APP, "Reveal needs Android 10")

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun ensureQ(location: BrowseLocation, paths: List<String>, onPhone: Map<String, String>, title: String?, onProgress: (LiveTransfer?) -> Unit): Map<String, String> {
        val toCheck = paths.filter { it !in onPhone }
        val stats = if (toCheck.isEmpty()) emptyMap() else statSources(location, toCheck)
        val key = location.cacheKey
        val rows = withContext(Dispatchers.IO) { toCheck.chunked(500).flatMap { dao.find(key, it) } }.groupBy { it.sourcePath }
        val plan = withContext(Dispatchers.IO) {
            RevealRoutes.plan(paths, onPhone, stats, rows) { uri, size ->
                RevealRoutes.isCopy(RevealCopyEntity(uri = uri, sourceKey = key, sourcePath = "", size = size, modifiedMillis = null, copiedAt = ""), current(uri))
            }
        }
        Log.i(TAG, "Reveal copies: ${plan.uris.size} by URI (${plan.uris.size - onPhone.size} reused copies), ${plan.copy.size} to copy")
        if (plan.copy.isEmpty()) return plan.uris
        val copied = copy(location, plan.copy, stats, title, onProgress)
        return paths.mapNotNull { p -> (plan.uris[p] ?: copied[p])?.let { p to it } }.toMap(LinkedHashMap())
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun copy(location: BrowseLocation, paths: List<String>, stats: Map<String, FileStat?>, title: String?, onProgress: (LiveTransfer?) -> Unit): Map<String, String> {
        val folder = RevealRoutes.folder(title, LocalDate.now())
        val service = transfers()
        val id = service.transfer(
            TransferRequest(
                op = "copy",
                source = location.place,
                paths = paths,
                name = TransferPaths.name(paths.first()),
                size = paths.sumOf { stats[it]?.size ?: 0L },
                itemsTotal = paths.size,
                destination = FolderPlace(TransferPlace(MEDIASTORE_LOCATION_ID), folder),
                conflict = "rename",
            ),
        )
        // The engine normalizes source paths; give them back as they were asked for.
        val asked = paths.associateBy { SmbPaths.normalize(it) }
        val end = try {
            service.live.map { it[id] }.onEach { onProgress(it) }.first { it != null && it.isTerminal }!!
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runCatching { service.cancel(id) }
                // What was published before the cancel stays recorded (and reusable).
                runCatching { record(location, asked, service.job(id)) }
            }
            throw e
        }
        val items = service.job(id)?.let { synchronized(it) { it.items.toList() } }.orEmpty()
        // Recorded before any failure is reported: a retry reuses what this job published (no `name (1).ext` copies).
        val out = record(location, asked, service.job(id))
        RevealRoutes.failure(end.state, items, paths.size)?.let { throw it }
        if (out.size < paths.size) smbFail(ErrorCode.UNKNOWN, "${paths.size - out.size} copies were not found in MediaStore")
        return out
    }

    /** Saves the copies [job] published (its completed items) in `reveal_copies`; source path → URI. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun record(location: BrowseLocation, asked: Map<String, String>, job: dev.hfps.hoard.smb.transfer.TransferJob?): Map<String, String> = withContext(Dispatchers.IO) {
        val items = job?.let { synchronized(it) { it.items.toList() } }.orEmpty()
        val rows = RevealRoutes.records(items, asked, location.cacheKey, IsoTime.now()) { folder, name ->
            MediaStoreCopies.find(context.contentResolver, folder, name)?.uri?.toString()
        }
        if (rows.isNotEmpty()) dao.save(rows)
        rows.associate { it.sourcePath to it.uri }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun current(uri: String): RevealRoutes.Current? =
        MediaStoreCopies.get(context.contentResolver, Uri.parse(uri))?.let { RevealRoutes.Current(it.relativePath, it.size) }

    /** What the copies take now: the recorded copies MediaStore still has in Hoard's copy folders. */
    suspend fun usage(): Usage = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rows = RevealRoutes.deletion(dao.all(), ::current).delete
            Usage(rows.size, rows.sumOf { it.size })
        } else {
            Usage(0, 0)
        }
    }

    /**
     * Deletes the recorded copies still in Hoard's copy folders (superseded ones included) and forgets records whose
     * file is gone or moved. Never a file Hoard did not record ([RevealRoutes.deletion]).
     */
    suspend fun deleteAll(): Usage = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) deleteAllQ() else Usage(0, 0)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun deleteAllQ(): Usage {
        val plan = RevealRoutes.deletion(dao.all(), ::current)
        val gone = ArrayList<String>()
        var bytes = 0L
        for (row in plan.delete) {
            if (MediaStoreCopies.delete(context.contentResolver, Uri.parse(row.uri))) {
                gone += row.uri
                bytes += row.size
            }
        }
        (gone + plan.forget.map { it.uri }).chunked(500).forEach { dao.deleteUris(it) }
        return Usage(gone.size, bytes)
    }

    companion object {
        const val TAG = "HoardReveal"
    }
}
