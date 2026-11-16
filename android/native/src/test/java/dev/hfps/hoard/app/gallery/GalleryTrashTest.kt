package dev.hfps.hoard.app.gallery

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.api.FileLocation
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ItemFile
import dev.hfps.hoard.server.paths.PathMapping
import org.junit.Test

/** The Gallery's "Move to trash" (ADR 0023, A4): what each selected shot moves, what is left out, the selection. */
class GalleryTrashTest {
    private val mapping = PathMapping("m1", "photos", "Imagens", "srv", "f1", "Imagens", "", null, 0, 0)

    private fun nasItem(id: String, path: String, folder: String = "f1", trash: String? = null) = Item(
        id = id, rev = 1, mainFingerprint = "fp-$id",
        files = listOf(ItemFile("fp-$id", "rendered", path.substringAfterLast('/'), "jpeg", 10, listOf(FileLocation("nas", folder, "Imagens", path, 1, trash = trash)))),
    )

    private val items = mapOf(
        // On the NAS (mapped folder f1), on the phone too.
        "both" to nasItem("both", "2024/Trip/DSC1.JPG"),
        // On the NAS only.
        "nas" to nasItem("nas", "2024/Trip/DSC2.JPG"),
        // In a NAS folder no share maps.
        "unmapped" to nasItem("unmapped", "x/DSC3.JPG", folder = "f9"),
        // Only an upload.
        "upload" to Item(id = "upload", rev = 1, mainFingerprint = "fp-u", files = listOf(ItemFile("fp-u", "rendered", "IMG.JPG", "jpeg", 10, listOf(FileLocation("backup"))))),
        // Already in a trash.
        "trashed" to nasItem("trashed", "2024/Trip/DSC4.JPG", trash = "2024/.hoard-trash/2026-11-15/Trip/DSC4.JPG"),
    )

    private fun entry(itemId: String?, device: DeviceShot? = null) = GalleryModel.entries(
        GalleryInput(
            server = if (itemId != null) listOf(GallerySamples.server(itemId, "x.jpg", "2024-05-03T10:20:06")) else emptyList(),
            device = listOfNotNull(device),
            serverFiles = if (itemId != null && device != null) device.fingerprints.associateWith { itemId } else emptyMap(),
            serverDecisions = emptyMap(),
            deviceDecisions = emptyMap(),
        ),
    ).single()

    private fun phoneShot(name: String, fp: String? = null) =
        DeviceShots.group(listOf(GallerySamples.media("DCIM/Camera/$name", "2024-05-03T10:20:06", fingerprint = fp))).single()

    @Test
    fun `every reachable copy moves, unmapped and upload-only shots are counted`() {
        val both = entry("both", phoneShot("DSC1.JPG", GallerySamples.fp(1)).let { s -> s.copy(files = s.files.map { f -> f.copy(media = f.media.copy(fpSize = f.media.size, fpModified = f.media.modified)) }) })
        val entries = listOf(both, entry("nas"), entry("unmapped"), entry("upload"), entry("trashed"), entry(null, phoneShot("IMG_9.JPG")))
        val plan = GalleryTrash.plan(entries, { items[it] }, listOf(mapping))
        assertThat(plan.groups.map { Triple(it.locationId, it.share, it.paths) }).containsExactly(
            Triple("device", null, listOf("DCIM/Camera/DSC1.JPG", "DCIM/Camera/IMG_9.JPG")),
            Triple("m1", "photos", listOf("Imagens/2024/Trip/DSC1.JPG", "Imagens/2024/Trip/DSC2.JPG")),
        )
        assertThat(plan.noMapping).isEqualTo(1)
        assertThat(plan.uploadOnly).isEqualTo(1)
        assertThat(plan.files).isEqualTo(4)
        assertThat(plan.itemPaths).containsExactly("both", listOf("Imagens/2024/Trip/DSC1.JPG"), "nas", listOf("Imagens/2024/Trip/DSC2.JPG"))
    }

    @Test
    fun `the local hide takes items whose every share file went`() {
        val paths = mapOf("a" to listOf("p/1.JPG", "p/1.ARW"), "b" to listOf("p/2.JPG"))
        assertThat(hiddenItems(paths, setOf("p/1.JPG", "p/1.ARW", "p/3.JPG"))).containsExactly("a")
        assertThat(hiddenItems(paths, setOf("p/1.JPG"))).isEmpty()
    }

    @Test
    fun `a trashed NAS file's trash root on the share`() {
        val loc = FileLocation("nas", "f1", "Imagens", "2024/Trip/DSC4.JPG", 1, trash = "2024/.hoard-trash/2026-11-15/Trip/DSC4.JPG")
        val (m, root) = trashRootOf(loc, listOf(mapping))!!
        assertThat(m.machineId).isEqualTo("m1")
        assertThat(root).isEqualTo("Imagens/2024")
        assertThat(trashRootOf(loc.copy(trash = ".hoard-trash/2026-11-15/a.jpg"), listOf(mapping))!!.second).isEqualTo("Imagens")
        assertThat(trashRootOf(loc.copy(folderId = "f9"), listOf(mapping))).isNull()
        assertThat(trashRootOf(loc.copy(trash = null), listOf(mapping))).isNull()
    }

    @Test
    fun `selection - long press, a day header, Select all on the filtered list, pruning`() {
        var s = GallerySelection()
        assertThat(s.active).isFalse()
        s = s.toggle("a")
        assertThat(s.keys).containsExactly("a")
        // A day header selects its day; again clears it.
        s = s.day(listOf("b", "c"))
        assertThat(s.keys).containsExactly("a", "b", "c")
        s = s.day(listOf("b", "c"))
        assertThat(s.keys).containsExactly("a")
        // Select all = what the filter shows (e.g. Rejected), nothing else.
        val rejected = GallerySamples.entries().filter { it.decision.flag == "reject" }.map { it.key }
        assertThat(rejected).isNotEmpty()
        s = s.all(rejected)
        assertThat(s.keys).containsExactlyElementsIn(rejected)
        // Shots that leave the list (trashed, a filter change) leave the selection.
        assertThat(s.prune(emptyList()).active).isFalse()
        assertThat(s.toggle(rejected.first()).keys).doesNotContain(rejected.first())
    }
}
