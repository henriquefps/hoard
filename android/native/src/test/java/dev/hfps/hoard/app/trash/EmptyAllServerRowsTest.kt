package dev.hfps.hoard.app.trash

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.Samples2b
import dev.hfps.hoard.data.TrashRootEntity
import dev.hfps.hoard.server.db.ServerAccountEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Review of #88, B1: with a server listing `trash-actions`, Empty trash never deletes a server-folder row over SMB,
 * whatever the Trash screen's state: tapped before the server's listing came back (2.9's Empty, `localOnly` false) or
 * with the server unreachable (no listing at all). Only Hoard's own rows are given to the SMB purge.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EmptyAllServerRowsTest {
    private val container get() = Samples2b.container
    private val nas = TrashPlace("m1", "photos")
    private val usb = TrashPlace("vol-1")

    // Hoard moved these over SMB before 2.10.0: one row knows its NAS folder, one only through its recorded root.
    private val known = TrashSamples.row(nas, "Photos", "2026-11-20", "Photos/2026/Old/OLD_0001.JPG", 10).copy(serverFolderId = "f1")
    private val byRoot = TrashSamples.row(nas, "Photos", "2026-11-20", "Photos/2026/Old/OLD_0002.JPG", 10)
    private val shareRoot = TrashSamples.row(nas, "", "2026-11-20", "Docs/report.pdf", 10) // the share root: no server folder
    private val drive = TrashSamples.row(usb, "", "2026-11-20", "DCIM/DSC1.JPG", 10)

    // Review of #88: a folder trashed whole before 2.10.0 inside the server folder (photos inside it), one row.
    private val folder = TrashSamples.row(nas, "Photos", "2026-11-10", "Photos/2023/Wedding", 0).copy(isDirectory = true)
    private val driveFolder = TrashSamples.row(usb, "", "2026-11-10", "DCIM/Old", 0).copy(isDirectory = true)

    private fun seed(features: String) = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val db = container.database.trash()
        db.delete(db.all().map { it.id })
        db.put(listOf(known, byRoot, shareRoot, drive, folder, driveFolder))
        db.putRoot(TrashRootEntity(nas.locationId, nas.share, "Photos", null, "f1"))
        // Paired with a server that is not reachable (nothing listens on port 1).
        container.gallery.db.account().put(
            ServerAccountEntity(
                url = "http://127.0.0.1:1", serverId = "srv", serverName = "Test", serverVersion = "0.15.0", deviceId = "dev",
                physicalDeviceId = null, app = "hoard-debug", features = features, cursor = null, pairedAt = 0,
            ),
        )
    }

    @After
    fun tearDown() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        container.gallery.db.clearAllTables()
        container.database.trash().delete(container.database.trash().all().map { it.id })
    }

    @Test
    fun emptyBeforeTheListingAndWithTheServerUnreachableKeepsServerRows() = runBlocking<Unit>(kotlinx.coroutines.Dispatchers.IO) {
        seed("items,trash,trash-actions")
        // 2.9's Empty (the screen before it knew the server's features) and the local-only Empty, server unreachable.
        for (localOnly in listOf(false, true)) {
            val rows = container.trash.rowsForEmptyAll(localOnly)
            assertThat(rows.map { it.id }).containsExactly(shareRoot.id, drive.id, driveFolder.id)
        }
        // Delete permanently and Restore of rows (TrashService.empty / restore) take only Hoard's own rows: never the
        // server folder's trashed folder (its delete is recursive) nor its photos.
        val all = container.database.trash().all()
        assertThat(container.trash.ownRows(all).map { it.id }).containsExactly(shareRoot.id, drive.id, driveFolder.id)
        assertThat(container.trash.ownRows(listOf(folder))).isEmpty()
    }

    @Test
    fun withoutTheFeatureEveryRowIsHoardsOwn() = runBlocking<Unit>(kotlinx.coroutines.Dispatchers.IO) {
        seed("items,trash")
        assertThat(container.trash.rowsForEmptyAll(false).map { it.id }).containsExactly(known.id, byRoot.id, shareRoot.id, drive.id, folder.id, driveFolder.id)
    }
}
