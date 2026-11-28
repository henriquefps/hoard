package dev.hfps.hoard.app.trash

import android.util.Log
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.server.api.Features
import dev.hfps.hoard.server.api.TrashUnitRef
import dev.hfps.hoard.server.trash.ApiTrashRoutes
import dev.hfps.hoard.server.trash.EmptyResult
import dev.hfps.hoard.server.trash.MovePreview
import dev.hfps.hoard.server.trash.MoveResult
import dev.hfps.hoard.server.trash.ServerTrash
import dev.hfps.hoard.server.trash.ServerTrashLine
import dev.hfps.hoard.server.trash.ServerTrashListing
import dev.hfps.hoard.server.trash.TrashRoutes
import dev.hfps.hoard.server.trash.UnitsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Server photos in the trash through the server (hoard-server 0.15.0, feature `trash-actions`; ADR 0023 Amendment
 * (2.10.0)): the Gallery's and Files' Move to trash of server photos, the Trash screen's server part (Restore, Delete
 * permanently, Empty by group) and auto-empty's deletes of Hoard's older SMB moves. No SMB share or mapping is needed;
 * a server without the feature keeps 2.9's SMB trash ([enabled] false).
 */
class ServerTrashService(private val container: AppContainer, private val changed: () -> Unit) {
    /** Whether the paired server lists `trash-actions` (as of the last sync). */
    suspend fun enabled(): Boolean = withContext(Dispatchers.IO) {
        runCatching { container.gallery.db.account().get()?.has(Features.TRASH_ACTIONS) == true }.getOrDefault(false)
    }

    private suspend fun routes(): TrashRoutes? {
        if (!enabled()) return null
        val api = runCatching { container.gallery.api() }.getOrNull() ?: return null
        return ApiTrashRoutes(api)
    }

    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)

    /** The server move's progress (items done, total), null when none runs. */
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()
    private val cancelRequested = AtomicBoolean(false)

    fun cancel() = cancelRequested.set(true)

    /** The dry run (throws when the server cannot be reached). */
    suspend fun dryRun(itemIds: List<String>): MovePreview {
        val r = routes() ?: throw java.io.IOException("no server with trash-actions")
        return ServerTrash.dryRun(r, itemIds)
    }

    /** Moves [itemIds]; the moved ones leave the Gallery at once (until the feed says so) and a sync follows. */
    suspend fun move(itemIds: List<String>): MoveResult {
        cancelRequested.set(false)
        val r = routes() ?: return MoveResult(emptyList(), emptyMap(), itemIds.associateWith { dev.hfps.hoard.server.trash.TrashReasons.UNREACHABLE })
        try {
            val result = ServerTrash.move(r, itemIds, { cancelRequested.get() }, { d, t -> _progress.value = d to t })
            Log.i(TAG, "move: ${result.moved.size} moved, skipped ${result.skippedBy}, failed ${result.failedBy}")
            hide(result.moved)
            return result
        } finally {
            _progress.value = null
            changed()
            syncSoon()
        }
    }

    /** The trashed shots' rows get `trashed_since` now, so the Gallery hides them before the feed confirms. */
    private suspend fun hide(itemIds: List<String>) = withContext(Dispatchers.IO) {
        if (itemIds.isEmpty()) return@withContext
        val db = container.gallery.db
        val now = System.currentTimeMillis()
        val rows = itemIds.mapNotNull { db.items().get(it) }.map { it.copy(trashedSince = it.trashedSince ?: now) }
        if (rows.isNotEmpty()) db.items().putItems(rows)
    }

    /** The server's trash, every page; null when it cannot be read now. */
    suspend fun listing(): ServerTrashListing? {
        val r = routes() ?: return null
        return try {
            ServerTrash.list(r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "listing failed: ${ServerTrash.reasonOf(e)}")
            null
        }
    }

    suspend fun restore(units: List<TrashUnitRef>): UnitsResult {
        val r = routes() ?: return UnitsResult(emptyList(), units.associate { it.refKey to dev.hfps.hoard.server.trash.TrashReasons.UNREACHABLE }, emptyMap(), 0)
        return ServerTrash.restore(r, units).also { done("restore", it) }
    }

    suspend fun delete(lines: List<ServerTrashLine>): UnitsResult {
        val r = routes() ?: return UnitsResult(emptyList(), lines.associate { it.ref.refKey to dev.hfps.hoard.server.trash.TrashReasons.UNREACHABLE }, emptyMap(), 0)
        return ServerTrash.delete(r, lines).also { done("delete", it) }
    }

    suspend fun empty(shown: Map<String, dev.hfps.hoard.server.api.TrashFigures>, chosen: Collection<String>): EmptyResult {
        val r = routes() ?: return EmptyResult.Failed(dev.hfps.hoard.server.trash.TrashReasons.UNREACHABLE)
        return ServerTrash.empty(r, shown, chosen).also {
            Log.i(TAG, "empty $chosen: ${it::class.simpleName}")
            if (it is EmptyResult.Done) done("empty", it.result)
        }
    }

    private fun done(what: String, r: UnitsResult) {
        Log.i(TAG, "$what: ${r.done.size} done, refused ${r.refusedBy}")
        changed()
        syncSoon()
    }

    /** NAS folders whose photos the server has not indexed yet: a walk is asked for (best effort). */
    fun requestWalk(folderIds: Collection<String>) {
        if (folderIds.isEmpty()) return
        container.scope.launch(Dispatchers.IO) {
            runCatching {
                val api = container.gallery.api() ?: return@runCatching
                dev.hfps.hoard.server.sync.NasWalks.request(api, folderIds.toList())
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    private fun syncSoon() {
        runCatching { dev.hfps.hoard.server.sync.GallerySyncWorker.soon(container.appContext, 1) }
    }

    /** `POST /v1/items/by-path` for the server paths of a plan's photos, per NAS folder (1000 a call). */
    suspend fun lookup(paths: Map<String, List<String>>): ServerRouting.Lookup = withContext(Dispatchers.IO) {
        if (paths.values.all { it.isEmpty() }) return@withContext ServerRouting.Lookup.Found(emptyMap())
        val api = runCatching { container.gallery.api() }.getOrNull() ?: return@withContext ServerRouting.Lookup.Failed
        val found = HashMap<String, String>()
        try {
            for ((folder, list) in paths) {
                for (chunk in list.chunked(1000)) {
                    val answer = try {
                        api.itemsByPath(folder, chunk)
                    } catch (e: dev.hfps.hoard.server.api.ServerException) {
                        if (e.status == 404) continue else throw e
                    }
                    answer.found.forEach { found[it.path] = it.itemId }
                }
            }
            ServerRouting.Lookup.Found(found)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "by-path failed", e)
            ServerRouting.Lookup.Failed
        }
    }

    companion object {
        private const val TAG = "HoardServerTrash"
    }
}
