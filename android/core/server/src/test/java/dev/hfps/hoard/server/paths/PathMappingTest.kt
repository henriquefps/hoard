package dev.hfps.hoard.server.paths

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.api.FileLocation
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ItemFile
import org.junit.Test

/** ADR 0022: deriving, verifying and invalidating SMB ↔ NAS mappings, and matching a folder's files (pure). */
class PathMappingTest {
    private val folder = "8b0c6f3e-1111-4222-8333-944455556666"
    private val other = "8b0c6f3e-1111-4222-8333-000000000000"

    private fun nas(path: String, mtime: Long = 1000, folderId: String = folder) = FileLocation("nas", folderId, "Imagens", path, mtime)

    private fun item(id: String, vararg files: ItemFile) = Item(id = id, rev = 1, kind = if (files.size > 1) "pair" else "photo", mainFingerprint = files.first().fingerprint, files = files.toList())

    private fun file(fp: String, name: String, size: Long, vararg locs: FileLocation, role: String = "rendered") = ItemFile(fp, role, name, null, size, locs.toList())

    private fun mapping(smb: String, server: String) = PathMapping("m1", "photos", smb, "srv", folder, "Imagens", server, "guid-1", 1, 1)

    private fun smb(path: String, size: Long = 100, mtime: Long = 1000) = MappingDiscovery.SmbFile(path, size, mtime)

    @Test
    fun derivesThePrefixesFromTheCommonTail() {
        // Share "photos" holds the NAS folder under "Imagens"; the server's folder is /mnt/crucial/Imagens.
        assertThat(MappingDiscovery.derive("Imagens/2024/05/DSC1.ARW", nas("2024/05/DSC1.ARW")))
            .isEqualTo(DerivedPrefix("Imagens", folder, "Imagens", ""))
        // The share is the folder itself: both prefixes empty, the whole share is mapped.
        assertThat(MappingDiscovery.derive("2024/05/DSC1.ARW", nas("2024/05/DSC1.ARW"))).isEqualTo(DerivedPrefix("", folder, "Imagens", ""))
        // The share is a sub-directory of the folder.
        assertThat(MappingDiscovery.derive("05/DSC1.ARW", nas("2024/05/DSC1.ARW"))).isEqualTo(DerivedPrefix("", folder, "Imagens", "2024"))
        // SMB is case-insensitive: the tail is compared ignoring case.
        assertThat(MappingDiscovery.derive("imagens/2024/dsc1.arw", nas("2024/DSC1.ARW"))!!.serverPrefix).isEqualTo("")
        // Another name, an upload (no path) or another source: nothing.
        assertThat(MappingDiscovery.derive("a/DSC2.ARW", nas("a/DSC1.ARW"))).isNull()
        assertThat(MappingDiscovery.derive("a/DSC1.ARW", FileLocation("backup"))).isNull()
    }

    @Test
    fun twoSamplesMustAgreeOnTheFolderAndPrefixes() {
        val a = file("v1:a", "A.JPG", 100, nas("2024/A.JPG"))
        val b = file("v1:b", "B.JPG", 100, nas("2024/B.JPG"))
        val c1 = MappingDiscovery.candidates("Imagens/2024/A.JPG", "v1:a", listOf(item("i1", a)))
        val c2 = MappingDiscovery.candidates("Imagens/2024/B.JPG", "v1:b", listOf(item("i2", b)))
        assertThat(MappingDiscovery.agree(c1, c2)).isEqualTo(DerivedPrefix("Imagens", folder, "Imagens", ""))
        // The second file sits elsewhere on the server (a copy of the folder, or another tree): no mapping.
        val moved = file("v1:b", "B.JPG", 100, nas("old/2024/B.JPG", folderId = other))
        val c3 = MappingDiscovery.candidates("Imagens/2024/B.JPG", "v1:b", listOf(item("i2", moved)))
        assertThat(MappingDiscovery.agree(c1, c3)).isNull()
        // Copies in two folders give two candidates, the most specific first; agreement picks the shared one.
        val copies = file("v1:a", "A.JPG", 100, nas("backup/2024/A.JPG", folderId = other), nas("2024/A.JPG"))
        val both = MappingDiscovery.candidates("Imagens/2024/A.JPG", "v1:a", listOf(item("i1", copies)))
        assertThat(both).hasSize(2)
        assertThat(MappingDiscovery.agree(both, c2)!!.folderId).isEqualTo(folder)
    }

    @Test
    fun samplesAreTwoPhotosNeverOne() {
        val files = listOf(smb("x/notes.txt", 50), smb("x/B.JPG", 3_000_000), smb("x/a.ARW", 20_000_000), smb("x/.hidden.JPG", 1_000_000), smb("x/c.JPG", 10))
        assertThat(MappingDiscovery.samples(files).map { it.path }).containsExactly("x/a.ARW", "x/B.JPG").inOrder()
        assertThat(MappingDiscovery.samples(listOf(smb("x/a.JPG"), smb("x/readme.md")))).isEmpty()
    }

    @Test
    fun aMappingCoversItsSubTreeAndMapsPaths() {
        val m = mapping("Imagens", "")
        assertThat(m.serverPathOf("Imagens/2024/05/DSC1.ARW")).isEqualTo("2024/05/DSC1.ARW")
        assertThat(m.serverPathOf("imagens/2024")).isEqualTo("2024")
        assertThat(m.serverPathOf("Imagens")).isEqualTo("")
        assertThat(m.serverPathOf("Other/DSC1.ARW")).isNull()
        assertThat(mapping("", "Imagens").serverPathOf("2024/A.JPG")).isEqualTo("Imagens/2024/A.JPG")
        val deeper = mapping("Imagens/2024", "2024")
        assertThat(MappingDiscovery.best(listOf(m, deeper), "m1", "PHOTOS", "Imagens/2024/05")).isEqualTo(deeper)
        assertThat(MappingDiscovery.best(listOf(m, deeper), "m1", "photos", "Imagens/2023")).isEqualTo(m)
        assertThat(MappingDiscovery.best(listOf(m), "m2", "photos", "Imagens")).isNull()
    }

    @Test
    fun invalidatedByAnotherServerOrSmbGuid() {
        val m = mapping("", "")
        assertThat(MappingDiscovery.valid(m, "srv", "GUID-1")).isTrue()
        assertThat(MappingDiscovery.valid(m, "srv", null)).isTrue()
        assertThat(MappingDiscovery.valid(m, "other-server", "guid-1")).isFalse()
        assertThat(MappingDiscovery.valid(m, "srv", "guid-2")).isFalse()
    }

    @Test
    fun anOnDemandLookupOutsideTheMappingIsAMismatch() {
        val m = mapping("Imagens", "")
        val inside = item("i1", file("v1:a", "A.JPG", 100, nas("2024/A_renamed.JPG")))
        assertThat(MappingDiscovery.consistent(m, "v1:a", inside)).isTrue()
        val elsewhere = item("i1", file("v1:a", "A.JPG", 100, nas("2024/A.JPG", folderId = other)))
        assertThat(MappingDiscovery.consistent(m, "v1:a", elsewhere)).isFalse()
        assertThat(MappingDiscovery.consistent(mapping("", "2024"), "v1:a", item("i1", file("v1:a", "A.JPG", 100, nas("2023/A.JPG"))))).isFalse()
    }

    @Test
    fun matchesByPathSizeAndMtime() {
        val m = mapping("Imagens", "")
        val raw = file("v1:r", "DSC1.ARW", 2000, nas("2024/DSC1.ARW", 5000), role = "raw")
        val jpg = file("v1:j", "DSC1.JPG", 300, nas("2024/DSC1.JPG", 5000))
        val other = file("v1:o", "DSC2.JPG", 300, nas("2024/DSC2.JPG", 7000))
        val index = FolderMatch.indexOf(listOf(item("pair", raw, jpg), item("single", other)), folder)
        val out = FolderMatch.match(
            m,
            listOf(
                smb("Imagens/2024/DSC1.ARW", 2000, 5000),
                smb("Imagens/2024/dsc1.jpg", 300, 6500), // case differs; mtime within 2 s
                smb("Imagens/2024/DSC2.JPG", 301, 7000), // size changed since the walk
                smb("Imagens/2024/DSC3.JPG", 300, 9000), // not walked yet
            ),
            index,
        )
        assertThat(out["Imagens/2024/DSC1.ARW"]).isEqualTo(FileMatch.Mapped("pair", "v1:r", "raw"))
        assertThat(out["Imagens/2024/dsc1.jpg"]).isEqualTo(FileMatch.Mapped("pair", "v1:j", "rendered"))
        assertThat(out["Imagens/2024/DSC2.JPG"]).isEqualTo(FileMatch.Changed("single", "v1:o"))
        assertThat(out["Imagens/2024/DSC3.JPG"]).isEqualTo(FileMatch.Unknown)
        // mtime beyond the tolerance: changed.
        val late = FolderMatch.match(m, listOf(smb("Imagens/2024/DSC1.ARW", 2000, 5000 + 2001)), index)
        assertThat(late.values.single()).isInstanceOf(FileMatch.Changed::class.java)
    }

    @Test
    fun aRenameOnTheShareIsOnlyACandidateToLookUp() {
        val m = mapping("", "")
        val a = file("v1:a", "IMG_1.JPG", 300, nas("trip/IMG_1.JPG", 5000))
        val b = file("v1:b", "IMG_2.JPG", 400, nas("trip/IMG_2.JPG", 6000))
        val index = FolderMatch.indexOf(listOf(item("ia", a), item("ib", b)), folder)
        // IMG_1 renamed to Beach.JPG since the walk; IMG_2 still there.
        val out = FolderMatch.match(m, listOf(smb("trip/Beach.JPG", 300, 5000), smb("trip/IMG_2.JPG", 400, 6000)), index)
        // A guess (same extension, size and mtime): looked up by fingerprint at a hand-off, never trusted as mapped.
        assertThat(out["trip/Beach.JPG"]).isEqualTo(FileMatch.Changed("ia", "v1:a", renamed = true))
        // An indexed file still present on the share is never taken by a rename.
        val kept = FolderMatch.match(m, listOf(smb("trip/Copy.JPG", 400, 6000), smb("trip/IMG_2.JPG", 400, 6000)), index)
        assertThat(kept["trip/Copy.JPG"]).isEqualTo(FileMatch.Unknown)
        // Two orphans with the same size and mtime: ambiguous, not matched.
        val twins = FolderMatch.indexOf(listOf(item("ia", a), item("ic", file("v1:c", "IMG_3.JPG", 300, nas("trip/IMG_3.JPG", 5000)))), folder)
        assertThat(FolderMatch.match(m, listOf(smb("trip/Beach.JPG", 300, 5000)), twins).values.single()).isEqualTo(FileMatch.Unknown)
    }

    @Test
    fun aNewBurstFrameNextToADeletedOneIsNotMappedToIt() {
        val m = mapping("", "")
        // Uncompressed RAWs: every frame has the same size; a burst shares the second.
        val gone = file("v1:gone", "DSC00010.ARW", 24_000_000, nas("burst/DSC00010.ARW", 7000), role = "raw")
        val kept = file("v1:kept", "DSC00011.ARW", 24_000_000, nas("burst/DSC00011.ARW", 7000), role = "raw")
        val index = FolderMatch.indexOf(listOf(item("i-gone", gone), item("i-kept", kept)), folder)
        // DSC00010 deleted on the share since the walk, a new DSC00012 taken in the same second.
        val out = FolderMatch.match(m, listOf(smb("burst/DSC00011.ARW", 24_000_000, 7000), smb("burst/DSC00012.ARW", 24_000_000, 7500)), index)
        assertThat(out["burst/DSC00011.ARW"]).isInstanceOf(FileMatch.Mapped::class.java)
        assertThat(out["burst/DSC00012.ARW"]).isNotInstanceOf(FileMatch.Mapped::class.java)
        assertThat(out.values.filterIsInstance<FileMatch.Mapped>().map { it.itemId }).containsExactly("i-kept")
    }

    @Test
    fun followsReplacedItems() {
        val m = mapping("", "")
        val jpg = file("v1:j", "DSC1.JPG", 300, nas("DSC1.JPG", 5000))
        val index = FolderMatch.indexOf(listOf(item("jpeg-only", jpg)), folder)
        // The RAW appeared: the JPEG-only item is a tombstone replaced by the pair (ADR 0014).
        val out = FolderMatch.match(m, listOf(smb("DSC1.JPG", 300, 5000)), index, mapOf("jpeg-only" to "pair", "pair" to "pair-2"))
        assertThat((out.values.single() as FileMatch.Mapped).itemId).isEqualTo("pair-2")
    }

    @Test
    fun filesOutsideTheMappingAreUnknownAndTheQueryKnowsWhenItIsComplete() {
        val m = mapping("Imagens", "")
        val index = FolderMatch.indexOf(listOf(item("i", file("v1:a", "A.JPG", 1, nas("A.JPG")))), folder)
        assertThat(FolderMatch.match(m, listOf(smb("Other/A.JPG", 1, 1000)), index).values.single()).isEqualTo(FileMatch.Unknown)
        assertThat(FolderMatch.complete(m, listOf(smb("Imagens/A.JPG")), index)).isTrue()
        assertThat(FolderMatch.complete(m, listOf(smb("Imagens/A.JPG"), smb("Imagens/B.JPG")), index)).isFalse()
        assertThat(MappingDiscovery.indexed("clip.MOV")).isTrue()
        assertThat(MappingDiscovery.indexed("DSC1.xmp")).isFalse()
        assertThat(MappingDiscovery.indexed("notes.txt")).isFalse()
    }
}
