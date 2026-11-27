package dev.hfps.hoard.app.trash

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.api.TrashEntryView
import dev.hfps.hoard.server.api.TrashPlaceView
import dev.hfps.hoard.server.api.TrashUnitView
import dev.hfps.hoard.server.paths.PathMapping
import dev.hfps.hoard.server.trash.ServerTrashIndex
import dev.hfps.hoard.server.trash.ServerTrashListing
import org.junit.Test

/**
 * ADR 0023 Amendment (2.10.0): a file Hoard trashed over SMB in a server folder is the file the server lists: shown
 * once (the server's), restored and deleted through the server; one not listed yet waits without actions; Hoard's own
 * trash (other places, plain files) is unchanged.
 */
class ServerTrashSplitTest {
    private val nas = TrashPlace("m1", "photos")
    private val usb = TrashPlace("vol-1")
    private val mapping = PathMapping("m1", "photos", "Imagens", "srv", "f1", "Imagens", "", null, 0, 0)

    private val rows = listOf(
        TrashSamples.row(nas, "Imagens", "2026-11-14", "Imagens/2024/Trip/DSC01001.ARW", 24),
        TrashSamples.row(nas, "Imagens", "2026-11-14", "Imagens/2024/Trip/DSC01001.JPG", 6),
        TrashSamples.row(nas, "Imagens", "2026-11-14", "Imagens/2024/Trip/DSC01001.xmp", 1),
        TrashSamples.row(nas, "Imagens", "2026-11-14", "Imagens/2024/Trip/DSC01002.ARW", 24), // not walked yet
        TrashSamples.row(nas, "Imagens", "2026-11-14", "Imagens/2024/Trip/notes.txt", 1), // not a photo
        TrashSamples.row(nas, "", "2026-11-02", "Docs/old-report.pdf", 810), // the share root: not a server folder
        TrashSamples.row(usb, "", "2026-11-14", "DCIM/DSC00007.JPG", 5),
    )

    private val listing = ServerTrashListing(
        listOf(
            TrashEntryView(
                "item-1", "photo", "DSC01001.ARW",
                places = listOf(TrashPlaceView("nas", "Imagens", "2024/Trip/DSC01001.ARW", ".hoard-trash/2026-11-14/2024/Trip/DSC01001.ARW")),
                units = listOf(TrashUnitView("nas:f1:.hoard-trash/2026-11-14", "nas", "2026-11-14", "f1", "Imagens", "2024/Trip", group = "hoard")),
            ),
        ),
        emptyMap(),
    )

    private fun root(row: dev.hfps.hoard.data.TrashEntryEntity) = ServerTrashSplit.serverRootOf(row, listOf(mapping)) { _, _ -> null }

    @Test
    fun `a shot the server lists is shown once, as the server's`() {
        val s = ServerTrashSplit.of(rows, ServerTrashIndex(listing), ::root)
        assertThat(s.covered.keys).containsExactly("item-1")
        assertThat(s.covered.getValue("item-1").map { it.originalPath.substringAfterLast('/') }).containsExactly("DSC01001.ARW", "DSC01001.JPG", "DSC01001.xmp")
        assertThat(s.waiting.map { it.originalPath.substringAfterLast('/') }).containsExactly("DSC01002.ARW")
        assertThat(s.local.map { it.originalPath.substringAfterLast('/') }).containsExactly("notes.txt", "old-report.pdf", "DSC00007.JPG")
    }

    @Test
    fun `the server unreachable - its photos wait, never get SMB actions`() {
        val s = ServerTrashSplit.of(rows, index = null, serverRoot = ::root)
        assertThat(s.covered).isEmpty()
        assertThat(s.waiting.map { it.originalPath.substringAfterLast('/') }).containsExactly("DSC01001.ARW", "DSC01001.JPG", "DSC01001.xmp", "DSC01002.ARW")
        assertThat(s.local.map { it.originalPath.substringAfterLast('/') }).containsExactly("notes.txt", "old-report.pdf", "DSC00007.JPG")
    }

    @Test
    fun `a recorded server root counts without a mapping`() {
        val s = ServerTrashSplit.of(rows, ServerTrashIndex(listing)) { r -> ServerTrashSplit.serverRootOf(r, emptyList()) { p, root -> if (p == nas && root == "Imagens") "f1" else null } }
        assertThat(s.covered.keys).containsExactly("item-1")
    }

    @Test
    fun `without trash-actions every row is Hoard's own`() {
        assertThat(ServerTrashSplit.allLocal(rows).local).isEqualTo(rows)
    }
}
