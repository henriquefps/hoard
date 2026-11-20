package dev.hfps.hoard.app.compress

import dev.hfps.hoard.app.gallery.Mirrors
import dev.hfps.hoard.app.gallery.Where
import dev.hfps.hoard.server.api.BackupRoot
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.api.CompressJob
import dev.hfps.hoard.server.api.CompressRootSelect
import dev.hfps.hoard.server.api.CompressSelect
import dev.hfps.hoard.server.api.FileLocation
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.compress.EstimateSummary
import dev.hfps.hoard.server.paths.SharePaths

/** Which compression target a server item belongs to, and the other pure helpers of the compression screens. */
object CompressTargets {
    /**
     * `nas` when a live NAS file of the item exists (the library's copy is replaced); `backup` when its only copies are a
     * phone backup's and that phone no longer has the file; null otherwise (on a phone: Hoard 2.8.0; an upload).
     */
    fun of(item: Item): String? {
        if (item.kind != "video" || item.trashed != null) return null
        val locations = item.files.flatMap { it.locations }
        if (locations.any { it.source == "nas" && it.trash == null }) return Compress.TARGET_NAS
        val mirrors = locations.filter { it.source == FileLocation.SOURCE_MIRROR }
        if (mirrors.isNotEmpty() && mirrors.none { it.onDevice != false } && locations.any { it.source == "backup" }) return Compress.TARGET_BACKUP
        return null
    }

    /**
     * A Gallery › Devices place as backup roots: every root of that phone on that volume that the folder is in (the
     * folder as the root's `path`), or that is inside the folder (the whole root). Null when none.
     */
    fun backupRoots(roots: List<BackupRoot>, where: Where): List<CompressRootSelect>? {
        val out = ArrayList<CompressRootSelect>()
        for (r in roots.filter { it.physicalDeviceId == where.physicalId && (where.volume == null || Mirrors.volume(it.volume) == Mirrors.volume(where.volume)) }) {
            val dir = where.dir.orEmpty()
            when {
                dir.isEmpty() || SharePaths.rest(r.path, dir) != null -> out += CompressRootSelect(r.id, "")
                SharePaths.rest(dir, r.path) != null -> out += CompressRootSelect(r.id, dir)
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** Summaries of several parts as one (the sheet shows a Gallery selection's NAS and backup videos together). */
    fun merge(parts: List<EstimateSummary>): EstimateSummary {
        if (parts.size == 1) return parts.single()
        val skipped = HashMap<String, Int>()
        parts.forEach { p -> p.skipped.forEach { (r, n) -> skipped[r] = (skipped[r] ?: 0) + n } }
        return EstimateSummary(
            videos = parts.sumOf { it.videos },
            encode = parts.sumOf { it.encode },
            size = parts.sumOf { it.size },
            estSize = parts.sumOf { it.estSize },
            estMs = parts.sumOf { it.estMs },
            skipped = skipped.toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }),
            warnings = parts.flatMap { it.warnings }.toSet(),
            pending = parts.sumOf { it.pending },
            subfolders = parts.sumOf { it.subfolders },
        )
    }

    /** The job's selection is exactly [folder] (an automatic folder's standing job). */
    fun sameFolder(job: CompressJob, folder: CompressFolder): Boolean {
        val select = job.select?.let { runCatching { ServerJson.decodeFromJsonElement(CompressSelect.serializer(), it) }.getOrNull() } ?: return false
        val f = select.folders?.singleOrNull() ?: return false
        return f.folderId == folder.folderId && SharePaths.normalize(f.path) == SharePaths.normalize(folder.path)
    }

    /** What an automatic job covers, as the server named it (Settings' automatic folders). */
    fun folderLabel(job: CompressJob): String? {
        val select = job.select?.let { runCatching { ServerJson.decodeFromJsonElement(CompressSelect.serializer(), it) }.getOrNull() } ?: return null
        select.folders?.singleOrNull()?.let { return it.path.ifEmpty { "/" } }
        select.roots?.singleOrNull()?.let { return it.path.ifEmpty { "/" } }
        return null
    }
}
