package dev.hfps.hoard.app.files

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.data.AppSettings
import dev.hfps.hoard.app.transfers.FolderPlace
import dev.hfps.hoard.app.transfers.PasteProblem
import dev.hfps.hoard.app.transfers.PickedEntry
import dev.hfps.hoard.app.transfers.RecentDestination
import dev.hfps.hoard.app.transfers.TransferPlace
import dev.hfps.hoard.app.transfers.pasteProblem
import dev.hfps.hoard.app.transfers.rememberDestination
import dev.hfps.hoard.app.transfers.transferRequest
import dev.hfps.hoard.app.ui.browse.BrowseViewModel
import dev.hfps.hoard.app.ui.browse.ShareItem
import dev.hfps.hoard.data.ManualShareEntity
import dev.hfps.hoard.smb.SmbEntry
import org.junit.Test
import java.util.Locale

/** The browser's pure rules (ports of src/lib/files.test.ts, photos, handoff and places tests). */
class FileLogicTest {
    private fun file(name: String, size: Long = 1, modified: String? = null, hidden: Boolean = false, dir: Boolean = false, folder: String = "") =
        SmbEntry(name, if (folder.isEmpty()) name else "$folder/$name", dir, size, modified, hidden)

    private val listing = listOf(
        file("DSC10.JPG", 300, "2026-01-03T00:00:00Z"),
        file("dsc2.jpg", 100, "2026-01-01T00:00:00Z"),
        file("Notes.md", 50, "2026-01-02T00:00:00Z"),
        file("Ágora", dir = true),
        file("archive.zip", 900),
        file(".DS_Store", 6, hidden = true),
        file("clip.MP4", 5000),
    )

    @Test fun kindsAndThumbnails() {
        assertThat(fileKind("x.JPG", false)).isEqualTo(FileKind.Image)
        assertThat(fileKind("x.mkv", false)).isEqualTo(FileKind.Video)
        assertThat(fileKind("x.tar", false)).isEqualTo(FileKind.Archive)
        assertThat(fileKind("noext", false)).isEqualTo(FileKind.Other)
        assertThat(fileKind("photos", true)).isEqualTo(FileKind.Folder)
        assertThat(hasThumbnail(file("a.heic"))).isTrue()
        assertThat(hasThumbnail(file("a.mov"))).isTrue()
        assertThat(hasThumbnail(file("a.pdf"))).isFalse()
        assertThat(extensionOf("archive.tar.gz")).isEqualTo("gz")
        assertThat(extensionOf("trailing.")).isEqualTo("")
    }

    @Test fun foldersFirstNaturalOrderAndHiddenFiles() {
        val names = viewEntries(listing, ViewOptions(), Locale.ENGLISH).map { it.name }
        assertThat(names).containsExactly("Ágora", "archive.zip", "clip.MP4", "dsc2.jpg", "DSC10.JPG", "Notes.md").inOrder()
        assertThat(viewEntries(listing, ViewOptions(showHidden = true), Locale.ENGLISH).map { it.name }).contains(".DS_Store")
    }

    @Test fun sortsBySizeAndDateBothWays() {
        val bySize = viewEntries(listing, ViewOptions(sort = SortOrder(SortKey.Size, ascending = false)), Locale.ENGLISH).map { it.name }
        assertThat(bySize.first()).isEqualTo("Ágora")
        assertThat(bySize.drop(1)).containsExactly("clip.MP4", "archive.zip", "DSC10.JPG", "dsc2.jpg", "Notes.md").inOrder()
        val byDate = viewEntries(listing.filter { it.modifiedAt != null }, ViewOptions(sort = SortOrder(SortKey.Modified)), Locale.ENGLISH)
        assertThat(byDate.map { it.name }).containsExactly("dsc2.jpg", "Notes.md", "DSC10.JPG").inOrder()
    }

    @Test fun filterAndAccentInsensitiveSearch() {
        assertThat(viewEntries(listing, ViewOptions(filter = FileFilter.Images), Locale.ENGLISH).map { it.name }).containsExactly("dsc2.jpg", "DSC10.JPG").inOrder()
        assertThat(viewEntries(listing, ViewOptions(filter = FileFilter.Documents), Locale.ENGLISH).map { it.name }).containsExactly("Notes.md")
        assertThat(viewEntries(listing, ViewOptions(query = "agor"), Locale.ENGLISH).map { it.name }).containsExactly("Ágora")
        assertThat(viewEntries(listing, ViewOptions(query = " DSC "), Locale.ENGLISH)).hasSize(2)
    }

    @Test fun openStrategies() {
        assertThat(openStrategy(file("a.jpg"))).isEqualTo(OpenStrategy.Image)
        assertThat(openStrategy(file("a.md"))).isEqualTo(OpenStrategy.Text)
        assertThat(openStrategy(file("index.html"))).isEqualTo(OpenStrategy.Web)
        assertThat(openStrategy(file("index.html"), withChooser = true)).isEqualTo(OpenStrategy.Cache)
        assertThat(openStrategy(file("a.mp4"))).isEqualTo(OpenStrategy.Stream)
        assertThat(openStrategy(file("a.pdf", size = 1000))).isEqualTo(OpenStrategy.Cache)
        assertThat(openStrategy(file("a.pdf", size = STREAM_FROM_BYTES))).isEqualTo(OpenStrategy.Stream)
        assertThat(openStrategy(file("a.jpg"), withChooser = true)).isEqualTo(OpenStrategy.Cache)
        // A big log is past the viewer's limit: it goes to another app.
        assertThat(openStrategy(file("big.log", size = TEXT_VIEWER_MAX_BYTES + 1))).isEqualTo(OpenStrategy.Cache)
        assertThat(isTextFile("Makefile")).isTrue()
        assertThat(isTextFile(".gitignore")).isTrue()
        assertThat(isTextFile("photo.jpg")).isFalse()
    }

    @Test fun pathsAndNames() {
        assertThat(splitPath("/a//b/")).containsExactly("a", "b").inOrder()
        assertThat(joinPath("a/b", "c")).isEqualTo("a/b/c")
        assertThat(joinPath("", "c")).isEqualTo("c")
        assertThat(parentPath("a/b/c")).isEqualTo("a/b")
        assertThat(parentPath("a")).isEqualTo("")
        assertThat(parentPath("")).isNull()
        assertThat(uncPath("nas", "photos", "2026/Lisboa")).isEqualTo("\\\\nas\\photos\\2026\\Lisboa")
        assertThat(isValidName("ok name.txt")).isTrue()
        listOf("", " ", ".", "..", "a/b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a|b").forEach { assertThat(isValidName(it)).isFalse() }
        assertThat(isValidShareName("Public")).isTrue()
        assertThat(isValidShareName("a\\b")).isFalse()
        assertThat(sameShare("Photos", "photos")).isTrue()
    }

    @Test fun revealSendsEachShotWithItsPairRawFirst() {
        val folder = listOf(file("DSC1.JPG", folder = "f"), file("DSC1.ARW", folder = "f"), file("DSC2.JPG", folder = "f"), file("notes.txt", folder = "f"), file(".DSC1.xmp", folder = "f"))
        assertThat(revealSelection(listOf(folder[0], folder[3]), folder)).containsExactly("f/DSC1.ARW", "f/DSC1.JPG").inOrder()
        assertThat(revealSelection(listOf(folder[2], folder[0]), folder)).containsExactly("f/DSC2.JPG", "f/DSC1.ARW", "f/DSC1.JPG").inOrder()
        assertThat(hasPhotos(listOf(folder[3]))).isFalse()
        assertThat(countPhotos(folder)).isEqualTo(3)
    }

    @Test fun formatting() {
        assertThat(formatBytes(0, Locale.ENGLISH)).isEqualTo("0 B")
        assertThat(formatBytes(1536, Locale.ENGLISH)).isEqualTo("1.5 KB")
        assertThat(formatBytes(1536, Locale.forLanguageTag("pt-BR"))).isEqualTo("1,5 KB")
        assertThat(formatBytes(200L * 1024 * 1024, Locale.ENGLISH)).isEqualTo("200 MB")
        assertThat(formatDuration(45.0)).isEqualTo("45 s")
        assertThat(formatDuration(180.0)).isEqualTo("3 min")
        assertThat(formatDuration(3840.0)).isEqualTo("1 h 4 min")
    }

    // --- places (src/features/transfers/places.test.ts) ----------------------------------------------------------

    private val nas = TransferPlace("m1", "photos")

    @Test fun pasteProblems() {
        val folder = PickedEntry("2026", "2026", true, 0)
        val photo = PickedEntry("2026/a.jpg", "a.jpg", false, 10)
        assertThat(pasteProblem("copy", nas, listOf(folder), FolderPlace(nas, "2026/Lisboa"))).isEqualTo(PasteProblem.IntoItself)
        assertThat(pasteProblem("copy", nas, listOf(folder), FolderPlace(TransferPlace("m1", "PHOTOS"), "2026"))).isEqualTo(PasteProblem.IntoItself)
        assertThat(pasteProblem("move", nas, listOf(photo), FolderPlace(nas, "2026"))).isEqualTo(PasteProblem.SameFolder)
        assertThat(pasteProblem("copy", nas, listOf(photo), FolderPlace(nas, "2026"))).isNull()
        assertThat(pasteProblem("move", nas, listOf(folder), FolderPlace(TransferPlace("device"), "2026"))).isNull()
    }

    @Test fun recentDestinationsKeepOrderWithoutDuplicates() {
        val a = RecentDestination("m1", "photos", "2026", "2026")
        val b = RecentDestination("device", null, "Download", "Download")
        val list = rememberDestination(rememberDestination(emptyList(), a), b)
        assertThat(rememberDestination(list, a.copy(share = "PHOTOS", path = "/2026/")).map { it.locationId }).containsExactly("m1", "device").inOrder()
        assertThat((1..10).fold(emptyList<RecentDestination>()) { acc, i -> rememberDestination(acc, b.copy(path = "p$i")) }).hasSize(6)
        val json = RecentDestination.listToJson(list)
        assertThat(RecentDestination.listFromJson(json)).isEqualTo(list)
        assertThat(RecentDestination.listFromJson("not json")).isEmpty()
    }

    @Test fun transferRequestCountsFilesOnly() {
        val request = transferRequest("copy", nas, listOf(PickedEntry("a", "a", true, 0), PickedEntry("b.jpg", "b.jpg", false, 7)), FolderPlace(TransferPlace("device"), "Download"), "ask")
        assertThat(request.size).isEqualTo(7)
        assertThat(request.itemsTotal).isEqualTo(2)
        assertThat(request.name).isEqualTo("a")
    }

    // --- settings (src/features/settings/settings.ts validators) --------------------------------------------------

    @Test fun settingsReadHoard1sJsonAndFallBackOnBadValues() {
        val s = AppSettings.parse(listOf("true", """{"key":"size","direction":"desc"}""", "\"skip\"", null, "3000", "false", null, "false", "true"))
        assertThat(s.showHiddenFiles).isTrue()
        assertThat(s.defaultSort).isEqualTo(SortOrder(SortKey.Size, ascending = false))
        assertThat(s.defaultConflict).isEqualTo("skip")
        assertThat(s.probeTimeoutMs).isEqualTo(3000)
        assertThat(s.cullSaveXmp).isFalse()
        assertThat(s.cullXmpHidden).isTrue()
        val bad = AppSettings.parse(listOf("\"yes\"", """{"key":"color"}""", "\"maybe\"", "{}", "99999", null, null, null, null))
        assertThat(bad).isEqualTo(dev.hfps.hoard.app.data.FilesSettings())
        assertThat(SortOrder(SortKey.Modified, false).toJson()).isEqualTo("""{"key":"modified","direction":"desc"}""")
    }

    // --- shares --------------------------------------------------------------------------------------------------

    @Test fun sharesAddedByNameJoinTheListedOnesUntilListed() {
        val listed = listOf(ShareItem("media", "Media files", false, null, false), ShareItem("admin$", null, true, null, false))
        val manual = listOf(ManualShareEntity("m1", "MEDIA", "t"), ManualShareEntity("m1", "hidden-one", "t"))
        val merged = BrowseViewModel.mergeShares(listed, manual, showHidden = false)
        assertThat(merged.map { it.name to it.manual }).containsExactly("hidden-one" to true, "media" to false).inOrder()
        assertThat(BrowseViewModel.mergeShares(listed, manual, showHidden = true).map { it.name }).contains("admin$")
    }
}
