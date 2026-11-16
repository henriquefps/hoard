package dev.hfps.hoard.app.backup

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.gallery.BackupBadge
import dev.hfps.hoard.server.db.BackupCountRow
import dev.hfps.hoard.server.db.BackupRootEntity
import dev.hfps.hoard.server.db.DeviceDirRow
import org.junit.Test

/** Settings › Backup's folder list and the Gallery's backup badge (ADR 0024, phase A1). */
class BackupFoldersTest {
    private val dirs = listOf(
        DeviceDirRow("device", "DCIM/Camera", 120, 480_000_000),
        DeviceDirRow("device", "DCIM/Camera/Burst", 10, 40_000_000),
        DeviceDirRow("device", "Pictures/Screenshots", 40, 20_000_000),
        DeviceDirRow("device", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", 300, 90_000_000),
        DeviceDirRow("device", "Pictures/Hoard", 2, 1),
        DeviceDirRow("vol-1234-ABCD", "DCIM/100CANON", 50, 900_000_000),
    )
    private val camera = BackupRootEntity("r1", "primary", "DCIM/Camera", "device", "DCIM/Camera", "image,video", 1, lastPassAt = 5)

    @Test
    fun rootsAreOnTheirSubFoldersFoldInAndTheRestIsOff() {
        val counts = listOf(BackupCountRow("r1", "stored", null, 100, 400), BackupCountRow("r1", "nas", null, 20, 80), BackupCountRow("r1", "paused", "wifi", 10, 40))
        val rows = BackupFolders.rows(dirs, listOf(camera), counts)
        assertThat(rows.map { it.folder }).containsExactly(
            "DCIM/Camera",
            "Pictures/Screenshots",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            "Pictures/Hoard",
            "DCIM/100CANON",
        ).inOrder()
        val cam = rows.first()
        assertThat(cam.on).isTrue()
        assertThat(cam.files).isEqualTo(130)
        assertThat(cam.status!!.backedUp).isEqualTo(100)
        assertThat(cam.status!!.onServer).isEqualTo(20)
        assertThat(cam.status!!.waitingWifi).isEqualTo(10)
        assertThat(rows.drop(1).none { it.on }).isTrue()
        assertThat(BackupFolders.total(rows).done).isEqualTo(120)
    }

    @Test
    fun labelsNameTheKnownFolders() {
        assertThat(BackupFolders.label("DCIM/Camera")).isEqualTo(FolderLabel.Camera)
        assertThat(BackupFolders.label("DCIM/Screenshots")).isEqualTo(FolderLabel.Screenshots)
        assertThat(BackupFolders.label("WhatsApp/Media/WhatsApp Video")).isEqualTo(FolderLabel.WhatsAppVideos)
        assertThat(BackupFolders.label("Download")).isEqualTo(FolderLabel.Other)
    }

    @Test
    fun aTypedFolderIsRelativeToTheStorageOrAbsoluteAndNeverHidden() {
        val root = "/storage/emulated/0"
        assertThat(BackupFolders.parse(" Download/Photos/ ", root)).isEqualTo("device" to "Download/Photos")
        assertThat(BackupFolders.parse("/storage/emulated/0/DCIM", root)).isEqualTo("device" to "DCIM")
        assertThat(BackupFolders.parse("/storage/1234-ABCD/DCIM/", root)).isEqualTo("vol-1234-ABCD" to "DCIM")
        assertThat(BackupFolders.parse("Pictures/.thumbnails", root)).isNull()
        assertThat(BackupFolders.parse("/data/local", root)).isNull()
        assertThat(BackupFolders.parse("", root)).isNull()
    }

    @Test
    fun theBadgeShowsWhatMattersMost() {
        assertThat(BackupBadge.of(emptyList())).isNull()
        assertThat(BackupBadge.of(listOf("queued", "failed"))).isEqualTo(BackupBadge.Failed)
        assertThat(BackupBadge.of(listOf("queued", "uploading"))).isEqualTo(BackupBadge.Uploading)
        assertThat(BackupBadge.of(listOf("paused", "waiting"))).isEqualTo(BackupBadge.Paused)
        assertThat(BackupBadge.of(listOf("waiting"))).isEqualTo(BackupBadge.Waiting)
    }
}
