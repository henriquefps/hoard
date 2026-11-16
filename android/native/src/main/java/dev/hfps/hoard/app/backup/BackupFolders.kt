package dev.hfps.hoard.app.backup

import dev.hfps.hoard.server.api.BackupRootStats
import dev.hfps.hoard.server.api.ServerJson
import dev.hfps.hoard.server.backup.BackupPaths
import dev.hfps.hoard.server.backup.BackupPlan
import dev.hfps.hoard.server.backup.FolderStatus
import dev.hfps.hoard.server.db.BackupCountRow
import dev.hfps.hoard.server.db.BackupRootEntity
import dev.hfps.hoard.server.db.DeviceDirRow

/** How Settings › Backup names a folder (ADR 0024 "Settings › Backup": Camera, Screenshots, WhatsApp, others). */
enum class FolderLabel { Camera, Screenshots, WhatsAppImages, WhatsAppVideos, Other }

/**
 * One row of Settings › Backup's folders: a folder backed up ([rootId] set, its [status] from its files' states) or
 * one of the phone's folders with photos or videos that is not.
 */
data class FolderRow(
    val locationId: String,
    val folder: String,
    val label: FolderLabel,
    val rootId: String?,
    /** Photos and videos the index has there (sub-folders included for a root). */
    val files: Int,
    val bytes: Long,
    val status: FolderStatus? = null,
    val lastPassAt: Long? = null,
    val serverStats: BackupRootStats? = null,
) {
    val on: Boolean get() = rootId != null
    val key: String get() = "$locationId/$folder"
}

/** The folder list of Settings › Backup (pure, tested). */
object BackupFolders {
    fun label(folder: String): FolderLabel {
        val f = folder.lowercase()
        return when {
            f == "dcim/camera" -> FolderLabel.Camera
            f.endsWith("screenshots") -> FolderLabel.Screenshots
            f.endsWith("whatsapp images") -> FolderLabel.WhatsAppImages
            f.endsWith("whatsapp video") -> FolderLabel.WhatsAppVideos
            else -> FolderLabel.Other
        }
    }

    /**
     * Roots first-class (on), then the index's folders no root covers (off); grouped Camera, Screenshots, WhatsApp
     * images and videos, then the others by path; the phone's storage before SD cards.
     */
    fun rows(dirs: List<DeviceDirRow>, roots: List<BackupRootEntity>, counts: List<BackupCountRow>): List<FolderRow> {
        val byRoot = counts.groupBy { it.rootId }
        val out = ArrayList<FolderRow>()
        for (root in roots) {
            val inside = dirs.filter { it.locationId == root.locationId && BackupPaths.within(root.folder, it.dir) }
            val status = byRoot[root.id]?.let { rows -> BackupPlan.status(rows.map { BackupPlan.Count(it.state, it.error, it.files, it.bytes) }) }
            out += FolderRow(
                locationId = root.locationId,
                folder = root.folder,
                label = label(root.folder),
                rootId = root.id,
                files = inside.sumOf { it.files },
                bytes = inside.sumOf { it.bytes },
                status = status ?: FolderStatus(),
                lastPassAt = root.lastPassAt,
                serverStats = root.serverStats?.let { runCatching { ServerJson.decodeFromString(BackupRootStats.serializer(), it) }.getOrNull() },
            )
        }
        for (dir in dirs) {
            if (roots.any { it.locationId == dir.locationId && BackupPaths.within(it.folder, dir.dir) }) continue
            if (dir.dir.isEmpty()) continue
            out += FolderRow(dir.locationId, dir.dir, label(dir.dir), null, dir.files, dir.bytes)
        }
        return out.sortedWith(compareBy<FolderRow>({ if (it.locationId == "device") 0 else 1 }, { it.label.ordinal }, { it.folder.lowercase() }))
    }

    /** Every root's numbers together (the Settings row and the screen's summary). */
    fun total(rows: List<FolderRow>): FolderStatus = rows.mapNotNull { it.status }.fold(FolderStatus()) { a, b ->
        FolderStatus(
            files = a.files + b.files,
            bytes = a.bytes + b.bytes,
            backedUp = a.backedUp + b.backedUp,
            backedUpBytes = a.backedUpBytes + b.backedUpBytes,
            onServer = a.onServer + b.onServer,
            waitingWifi = a.waitingWifi + b.waitingWifi,
            waitingCharging = a.waitingCharging + b.waitingCharging,
            queued = a.queued + b.queued,
            failed = a.failed + b.failed,
            refused = a.refused + b.refused,
        )
    }

    /**
     * Settings › Backup's "Reveal on this phone is not grouped with Hoard" (ADR 0024 "When only one app is installed, or
     * they are not grouped"): Reveal is installed here, and no live Reveal (seen in the last 90 days, ADR 0017) is in
     * this phone's `physicalDevice.devices`.
     */
    fun revealNotGrouped(installed: Boolean, devices: List<dev.hfps.hoard.server.api.CandidateDevice>, now: Long): Boolean =
        installed && devices.none { d -> dev.hfps.hoard.server.backup.FreeUpPlan.isReveal(d) && dev.hfps.hoard.server.backup.FreeUpPlan.isLive(d, now) }

    /**
     * A folder the owner typed ("Add a folder"): relative to the phone's storage (`Download/Photos`), or absolute under
     * it or an SD card (`/storage/1234-ABCD/DCIM`). Null when it is not a phone folder or names a hidden one.
     */
    fun parse(input: String, primaryRoot: String): Pair<String, String>? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val located = if (text.startsWith("/")) {
            dev.hfps.hoard.app.gallery.DeviceFolders.locate(text.trimEnd('/') + "/x", primaryRoot)?.let { (loc, rel) -> loc to rel.removeSuffix("x").trimEnd('/') }
        } else {
            "device" to text
        } ?: return null
        val folder = BackupPaths.clean(located.second)
        if (folder.split('/').any { it.startsWith(".") || it == ".." }) return null
        return located.first to folder
    }
}
