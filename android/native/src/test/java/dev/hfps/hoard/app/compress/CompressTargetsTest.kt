package dev.hfps.hoard.app.compress

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.gallery.Where
import dev.hfps.hoard.server.api.BackupRoot
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.api.CompressJob
import dev.hfps.hoard.server.api.CompressRootSelect
import dev.hfps.hoard.server.api.FileLocation
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ItemFile
import dev.hfps.hoard.server.api.ItemTrash
import dev.hfps.hoard.server.compress.EstimateSummary
import kotlinx.serialization.json.Json
import org.junit.Test

/** ADR 0025 A1: which target a Gallery video goes to, a Devices place as backup roots, and merged estimates. */
class CompressTargetsTest {
    private fun video(vararg locations: FileLocation, kind: String = "video", trashed: Boolean = false) =
        Item("i", 1, kind = kind, files = listOf(ItemFile("v1:a", "video", "a.mp4", size = 1, locations = locations.toList())), trashed = if (trashed) ItemTrash(1) else null)

    private val nas = FileLocation(source = "nas", folderId = "f", path = "2024/a.mp4")
    private val backup = FileLocation(source = "backup", sha256 = "x")
    private fun mirror(onDevice: Boolean?, gone: String? = null) = FileLocation(source = "mirror", rootId = "r", path = "DCIM/Camera/a.mp4", onDevice = onDevice, gone = gone)

    @Test
    fun aLibraryCopyMakesItANasJobAndABackupOffThePhoneABackupJob() {
        assertThat(CompressTargets.of(video(nas))).isEqualTo(Compress.TARGET_NAS)
        assertThat(CompressTargets.of(video(nas, mirror(true), backup))).isEqualTo(Compress.TARGET_NAS)
        assertThat(CompressTargets.of(video(backup, mirror(false, "freed")))).isEqualTo(Compress.TARGET_BACKUP)
        // Still on the phone: the phone's own replace (Hoard 2.8.0), not offered.
        assertThat(CompressTargets.of(video(backup, mirror(null)))).isNull()
        assertThat(CompressTargets.of(video(backup, mirror(true)))).isNull()
        // An upload, a photo, a trashed video, a NAS copy that is only in a trash.
        assertThat(CompressTargets.of(video(backup))).isNull()
        assertThat(CompressTargets.of(video(nas, kind = "photo"))).isNull()
        assertThat(CompressTargets.of(video(nas, trashed = true))).isNull()
        assertThat(CompressTargets.of(video(nas.copy(trash = ".hoard-trash/2026-11-19/2024/a.mp4")))).isNull()
    }

    private val roots = listOf(
        BackupRoot("r1", physicalDeviceId = "p1", volume = "primary", path = "DCIM/Camera"),
        BackupRoot("r2", physicalDeviceId = "p1", volume = "primary", path = "Movies"),
        BackupRoot("r3", physicalDeviceId = "p1", volume = "1234-ABCD", path = "DCIM"),
        BackupRoot("r4", physicalDeviceId = "p2", volume = "primary", path = "DCIM/Camera"),
    )

    @Test
    fun aDevicesPlaceBecomesBackupRoots() {
        assertThat(CompressTargets.backupRoots(roots, Where("p1", "Pixel"))).containsExactly(
            CompressRootSelect("r1", ""), CompressRootSelect("r2", ""), CompressRootSelect("r3", ""),
        )
        assertThat(CompressTargets.backupRoots(roots, Where("p1", "Pixel", volume = "PRIMARY"))).containsExactly(CompressRootSelect("r1", ""), CompressRootSelect("r2", ""))
        // A folder inside a root: that root, from the folder down; a folder holding roots: those roots whole.
        assertThat(CompressTargets.backupRoots(roots, Where("p1", "Pixel", "primary", "DCIM/Camera/2024"))).containsExactly(CompressRootSelect("r1", "DCIM/Camera/2024"))
        assertThat(CompressTargets.backupRoots(roots, Where("p1", "Pixel", "primary", "DCIM"))).containsExactly(CompressRootSelect("r1", ""))
        assertThat(CompressTargets.backupRoots(roots, Where("p1", "Pixel", "primary", "Pictures"))).isNull()
        assertThat(CompressTargets.backupRoots(roots, Where("p9", "Other"))).isNull()
    }

    @Test
    fun aSelectionsPartsAreEstimatedAsOne() {
        val a = EstimateSummary(10, 4, 1000, 200, 60_000, listOf("efficient" to 3, "older_compression" to 3), setOf("hdr10plus"), 0)
        val b = EstimateSummary(5, 2, 500, 100, 30_000, listOf("older_compression" to 2), setOf("per_frame_metadata"), 1)
        val m = CompressTargets.merge(listOf(a, b))
        assertThat(m.videos).isEqualTo(15)
        assertThat(m.encode).isEqualTo(6)
        assertThat(m.estSize).isEqualTo(300)
        assertThat(m.skipped).containsExactly("older_compression" to 5, "efficient" to 3).inOrder()
        assertThat(m.warnings).containsExactly("hdr10plus", "per_frame_metadata")
        assertThat(m.pending).isEqualTo(1)
        assertThat(CompressTargets.merge(listOf(a))).isSameInstanceAs(a)
    }

    @Test
    fun anAutomaticJobIsMatchedToItsFolder() {
        val job = CompressJob("j", auto = true, select = Json.parseToJsonElement("""{"folders":[{"folderId":"f","path":"Drone/2024/","recursive":true}]}"""))
        assertThat(CompressTargets.sameFolder(job, CompressFolder("f", "Drone/2024", "videos/Drone/2024"))).isTrue()
        assertThat(CompressTargets.sameFolder(job, CompressFolder("f", "Drone", "videos/Drone"))).isFalse()
        assertThat(CompressTargets.sameFolder(job, CompressFolder("g", "Drone/2024", "x"))).isFalse()
        assertThat(CompressTargets.folderLabel(job)).isEqualTo("Drone/2024/")
        assertThat(CompressTargets.sameFolder(CompressJob("k"), CompressFolder("f", "", ""))).isFalse()
    }
}
