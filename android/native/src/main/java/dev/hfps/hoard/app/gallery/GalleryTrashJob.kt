package dev.hfps.hoard.app.gallery

import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.files.FileService
import dev.hfps.hoard.app.ui.trash.TrashDone
import dev.hfps.hoard.app.ui.trash.TrashRequest
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ServerJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Gallery's "Move to trash" as the trash dialog takes it: one request per folder of each place, and what is left out. */
data class GalleryTrashJob(val requests: List<TrashRequest>, val selection: GalleryTrashSelection)

object GalleryTrashJobs {
    /** Builds the job for [entries]: their server items (gallery.db), the paired server's mappings, the places known now. */
    suspend fun prepare(container: AppContainer, entries: List<GalleryEntry>, phoneName: String, driveName: String): GalleryTrashJob = withContext(Dispatchers.IO) {
        val db = container.gallery.db
        val serverId = db.account().get()?.serverId
        val mappings = runCatching { container.serverLinks.paths.mappings() }.getOrDefault(emptyList()).filter { it.serverId == serverId }
        val items = entries.mapNotNull { it.itemId }.distinct().associateWith { id ->
            db.items().json(id)?.let { runCatching { ServerJson.decodeFromString(Item.serializer(), it) }.getOrNull() }
        }
        val selection = GalleryTrash.plan(entries, { items[it] }, mappings)
        val machines = container.database.machines().all()
        val volumes = runCatching { container.volumeRegistry.list() }.getOrDefault(emptyList())
        val requests = selection.groups.mapNotNull { g ->
            FileService.resolve(g.locationId, g.share, machines, volumes, phoneName, driveName)?.let { TrashRequest(it, g.folder, g.paths) }
        }
        GalleryTrashJob(requests, selection)
    }

    /** After a run: the server shots whose share files all went are hidden at once; the feed confirms (`include=trash`). */
    suspend fun hide(container: AppContainer, job: GalleryTrashJob, done: TrashDone) = withContext(Dispatchers.IO) {
        val trashed = done.gone.flatMap { u -> u.all.map { it.path } }.toSet()
        val now = System.currentTimeMillis()
        val db = container.gallery.db
        val rows = hiddenItems(job.selection.itemPaths, trashed).mapNotNull { db.items().get(it) }.map { it.copy(trashedSince = it.trashedSince ?: now) }
        if (rows.isNotEmpty()) db.items().putItems(rows)
    }
}
