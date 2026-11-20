package dev.hfps.hoard.app.gallery

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.gallery.GallerySamples.fp
import dev.hfps.hoard.app.gallery.GallerySamples.media
import dev.hfps.hoard.app.gallery.GallerySamples.server
import dev.hfps.hoard.server.api.BackupRoot
import dev.hfps.hoard.server.db.ServerItemMirrorEntity
import org.junit.Test

/** Other devices see backups (ADR 0024 A2): Devices, the "Where" filter, the badge, this phone's files shown once. */
class DevicesTest {
    private val pixel = "phys-pixel"
    private val tablet = "phys-tab"

    private fun mirror(item: String, fp: Int, physical: String, path: String, onDevice: Boolean = true, gone: String? = null, size: Long = 1000, volume: String = "primary", name: String = if (physical == pixel) "Pixel 8" else "Tab S9") =
        ServerItemMirrorEntity(item, fp(fp), physical, name, if (volume == "primary") "root-$physical" else "root-$physical-sd", volume, path, size, 1, onDevice, gone)

    private val rows = listOf(
        server("p1", "PXL_1.jpg", "2026-11-01T10:00:00.000", folder = "Pixel 8/DCIM/Camera", main = 1),
        server("p2", "PXL_2.dng", "2026-11-01T10:01:00.000", kind = "pair", folder = "Pixel 8/DCIM/Camera", main = 2),
        server("p3", "IMG-WA0001.jpg", "2026-11-02T10:00:00.000", folder = "Pixel 8/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", main = 3),
        server("p4", "DSC_9.jpg", "2026-11-03T10:00:00.000", folder = "Pixel 8/DCIM/Sub", main = 4),
        server("t1", "Shot.png", "2026-11-04T10:00:00.000", folder = "Tab S9/Pictures/Screenshots", main = 5),
        server("nas", "DSC01000.ARW", "2024-05-03T10:00:00.250", main = 6),
    )

    private val mirrors = listOf(
        mirror("p1", 1, pixel, "DCIM/Camera/PXL_1.jpg"),
        // A pair: the RAW deleted from the phone, the JPEG still there: the shot is still on the phone.
        mirror("p2", 2, pixel, "DCIM/Camera/PXL_2.dng", onDevice = false, gone = "deleted"),
        mirror("p2", 22, pixel, "DCIM/Camera/PXL_2.jpg"),
        mirror("p3", 3, pixel, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-WA0001.jpg", onDevice = false, gone = "deleted"),
        mirror("p4", 4, pixel, "DCIM/Sub/DSC_9.jpg", onDevice = false, gone = "freed", volume = "1234-abcd"),
        mirror("t1", 5, tablet, "Pictures/Screenshots/Shot.png"),
    ).groupBy { it.itemId }

    private val roots = listOf(
        BackupRoot("root-$pixel", pixel, "Pixel 8", volume = "PRIMARY", path = "DCIM", active = true, lastReportAt = 9),
        BackupRoot("root-$pixel-wa", pixel, "Pixel 8", volume = "PRIMARY", path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", active = true),
        BackupRoot("root-$pixel-sd", pixel, "Pixel 8", volume = "1234-ABCD", path = "", active = true),
        // A root with nothing on the server yet still shows.
        BackupRoot("root-$tablet", tablet, "Tab S9", mine = true, volume = "PRIMARY", path = "Pictures", active = false),
        BackupRoot("root-$tablet-dl", tablet, "Tab S9", mine = true, volume = "PRIMARY", path = "Download", active = false),
    )

    private fun entries(device: List<dev.hfps.hoard.server.db.DeviceMediaEntity> = emptyList(), files: Map<String, String> = emptyMap(), mine: String? = tablet) =
        GalleryModel.entries(GalleryInput(rows, DeviceShots.group(device), files, emptyMap(), emptyMap(), mirrors = mirrors, physicalId = mine))

    @Test
    fun itemsCarryWhereTheyAreInThePhonesBackups() {
        val list = entries()
        val p2 = list.single { it.itemId == "p2" }
        assertThat(p2.mirrors).hasSize(1)
        assertThat(p2.mirrors.single().onDevice).isTrue()
        assertThat(p2.mirrors.single().dir).isEqualTo("DCIM/Camera")
        assertThat(list.single { it.itemId == "p4" }.mirrors.single().volume).isEqualTo("1234-ABCD")
        assertThat(list.single { it.itemId == "nas" }.mirrors).isEmpty()
    }

    @Test
    fun theBadgeIsForShotsThePhoneNoLongerHas() {
        val list = entries().associateBy { it.itemId }
        assertThat(Mirrors.offPhone(list.getValue("p1").mirrors)).isNull()
        // One file of a pair still on the phone: not deleted.
        assertThat(Mirrors.offPhone(list.getValue("p2").mirrors)).isNull()
        assertThat(Mirrors.offPhone(list.getValue("p3").mirrors)).isEqualTo(OffPhone.Deleted)
        assertThat(Mirrors.offPhone(list.getValue("p4").mirrors)).isEqualTo(OffPhone.Freed)
        assertThat(Mirrors.offPhone(list.getValue("nas").mirrors)).isNull()
        // Seen from another phone's backup, a shot is not "deleted from" a phone that never had it.
        assertThat(Mirrors.offPhone(list.getValue("p3").mirrors, tablet)).isNull()
    }

    @Test
    fun whereFiltersByPhoneVolumeAndFolderWithSubFolders() {
        val list = entries()
        fun keys(where: Where) = list.filter(GalleryFilter(where = where)::matches).map { it.itemId }
        assertThat(keys(Where(pixel, "Pixel 8"))).containsExactly("p1", "p2", "p3", "p4")
        assertThat(keys(Where(pixel, "Pixel 8", "PRIMARY"))).containsExactly("p1", "p2", "p3")
        assertThat(keys(Where(pixel, "Pixel 8", "PRIMARY", "DCIM"))).containsExactly("p1", "p2")
        assertThat(keys(Where(pixel, "Pixel 8", "PRIMARY", "DCIM/Camera"))).containsExactly("p1", "p2")
        // A prefix that is not a folder boundary does not match.
        assertThat(keys(Where(pixel, "Pixel 8", "PRIMARY", "DCIM/Cam"))).isEmpty()
        assertThat(keys(Where(tablet, "Tab S9"))).containsExactly("t1")
        assertThat(GalleryFilter(where = Where(pixel, "Pixel 8")).count).isEqualTo(1)
        assertThat(GalleryFilter(where = Where(pixel, "Pixel 8")).active).isTrue()
    }

    @Test
    fun theTreeListsEveryPhoneVolumeAndFolderWithCounts() {
        val tree = DeviceTree.build(roots, entries(), tablet)
        // This phone first, then by name.
        assertThat(tree.map { it.name }).containsExactly("Tab S9", "Pixel 8").inOrder()
        val tab = tree[0]
        assertThat(tab.mine).isTrue()
        assertThat(tab.active).isFalse()
        assertThat(tab.volumes.single().folders.map { it.dir }).containsExactly("Download", "Pictures", "Pictures/Screenshots").inOrder()
        assertThat(tab.volumes.single().folders.first { it.dir == "Download" }.shots).isEqualTo(0)
        assertThat(tab.volumes.single().folders.first { it.dir == "Pictures" }.shots).isEqualTo(1)

        val px = tree[1]
        assertThat(px.shots).isEqualTo(4)
        assertThat(px.offPhone).isEqualTo(2)
        assertThat(px.active).isTrue()
        assertThat(px.lastReportAt).isEqualTo(9)
        // Internal storage first, then the SD card.
        assertThat(px.volumes.map { it.volume }).containsExactly("PRIMARY", "1234-ABCD").inOrder()
        val internal = px.volumes[0]
        assertThat(internal.shots).isEqualTo(3)
        val dcim = internal.folders.first { it.dir == "DCIM" }
        assertThat(dcim.root).isTrue()
        assertThat(dcim.shots).isEqualTo(2)
        assertThat(internal.folders.first { it.dir == "DCIM/Camera" }.root).isFalse()
        assertThat(internal.folders.first { it.dir.endsWith("WhatsApp Images") }.offPhone).isEqualTo(1)
        val sd = px.volumes[1]
        // The whole SD card is a root (path ""): it counts everything on it.
        assertThat(sd.folders.first { it.dir == "" }.shots).isEqualTo(1)
        assertThat(sd.folders.first { it.dir == "" }.offPhone).isEqualTo(1)
    }

    @Test
    fun withoutRootsTheTreeComesFromTheItemsAlone() {
        val tree = DeviceTree.build(emptyList(), entries(), null)
        assertThat(tree.map { it.name }).containsExactly("Pixel 8", "Tab S9").inOrder()
        assertThat(tree.none { it.mine }).isTrue()
        assertThat(tree[0].active).isNull()
        assertThat(tree[0].volumes[0].folders.map { it.dir }).contains("DCIM/Camera")
    }

    @Test
    fun thisPhonesBackedUpFileShowsOnceEvenBeforeItIsFingerprinted() {
        // The tablet's own screenshot, backed up (its mirror location), not fingerprinted by the index yet.
        val shot = media("Pictures/Screenshots/Shot.png", "2026-11-04T10:00:00.000", size = 1000)
        val list = entries(listOf(shot))
        assertThat(list.count { it.name == "Shot.png" }).isEqualTo(1)
        val e = list.single { it.name == "Shot.png" }
        assertThat(e.source).isEqualTo(Source.Both)
        assertThat(e.itemId).isEqualTo("t1")

        // Another size at that path (rewritten since) is not that item; nor is another phone's place.
        val changed = entries(listOf(media("Pictures/Screenshots/Shot.png", "2026-11-04T10:00:00.000", size = 999)))
        assertThat(changed.count { it.name == "Shot.png" }).isEqualTo(2)
        val elsewhere = entries(listOf(media("DCIM/Camera/PXL_1.jpg", "2026-11-01T10:00:00.000", size = 1000)))
        assertThat(elsewhere.count { it.name == "PXL_1.jpg" }).isEqualTo(2)
        // Unknown phone: no place merge.
        assertThat(entries(listOf(shot), mine = null).count { it.name == "Shot.png" }).isEqualTo(2)
    }

    @Test
    fun aFingerprintedCopyStillMergesByFingerprint() {
        val saved = media("Pictures/From Pixel 8/PXL_1.jpg", "2026-11-01T10:00:00.000", fingerprint = fp(1))
        val list = entries(listOf(saved), files = mapOf(fp(1) to "p1"))
        assertThat(list.count { it.name == "PXL_1.jpg" }).isEqualTo(1)
        assertThat(list.single { it.name == "PXL_1.jpg" }.source).isEqualTo(Source.Both)
    }

    @Test
    fun volumesMapToHoardsLocations() {
        assertThat(Mirrors.volumeOf("device")).isEqualTo("PRIMARY")
        assertThat(Mirrors.volumeOf("vol-1234-abcd")).isEqualTo("1234-ABCD")
        assertThat(Mirrors.volumeOf("machine-1")).isNull()
        assertThat(Mirrors.volume("primary")).isEqualTo("PRIMARY")
    }

    // --- ADR 0025 Amendment (2.8.1): a video this phone replaced in place shows once -------------------------------

    private val original = fp(70)
    private val output = fp(71)
    private val video = server("tv", "VID_1.mp4", "2026-11-05T10:00:00.000", kind = "video", folder = "Tab S9/DCIM/Camera", main = 70)

    private fun journal(direction: String, source: String, target: String, sourceSize: Long, targetSize: Long, state: String = dev.hfps.hoard.server.compress.PhoneState.REPORTED, started: Boolean = true) =
        dev.hfps.hoard.server.db.CompressPhoneEntity(
            id = "7", volume = "primary", path = "DCIM/Camera/VID_1.mp4", absolute = "/storage/emulated/0/DCIM/Camera/VID_1.mp4", direction = direction,
            sourceFingerprint = source, sourceSha256 = "s", sourceSize = sourceSize, targetFingerprint = target, targetSha256 = "t", targetSize = targetSize,
            state = state, writeStarted = started, report = if (direction == "compress") "replaced" else "restored",
        )

    private fun cells(device: dev.hfps.hoard.server.db.DeviceMediaEntity, files: Map<String, String>, mirrorSize: Long, aliases: Map<String, String>) =
        GalleryModel.entries(
            GalleryInput(
                listOf(video), DeviceShots.group(listOf(device)), files, emptyMap(), emptyMap(),
                mirrors = mapOf("tv" to listOf(mirror("tv", 70, tablet, "DCIM/Camera/VID_1.mp4", size = mirrorSize))),
                physicalId = tablet, aliases = aliases,
            ),
        )

    @Test
    fun aVideoThisPhoneReplacedShowsOnceBeforeAndAfterTheSync() {
        // Right after the replace: the index has the output (its fingerprint, its size); the cached item still has the
        // original's fingerprint, and its mirror the old size at the same place.
        val rewritten = media("DCIM/Camera/VID_1.mp4", "2026-11-05T10:00:00.000", size = 700_000, fingerprint = output, video = true)
        val cached = mapOf(original to "tv")
        // Without the journal it shows twice (the report of 2.8.0): neither the fingerprint nor the place (size) match.
        assertThat(cells(rewritten, cached, 3_000_000, emptyMap())).hasSize(2)
        val aliases = CompressAliases.of(listOf(journal("compress", original, output, 3_000_000, 700_000)))
        assertThat(aliases).containsExactly(output, original)
        val before = cells(rewritten, cached, 3_000_000, aliases)
        assertThat(before).hasSize(1)
        assertThat(before.single().source).isEqualTo(Source.Both)
        assertThat(before.single().itemId).isEqualTo("tv")
        // `written` (not reported yet) too.
        assertThat(cells(rewritten, cached, 3_000_000, CompressAliases.of(listOf(journal("compress", original, output, 3_000_000, 700_000, state = "written"))))).hasSize(1)
        // After the sync: the cache has the swapped item (the output's fingerprint, the new size); the direct match wins.
        val after = cells(rewritten, mapOf(output to "tv"), 700_000, aliases)
        assertThat(after).hasSize(1)
        assertThat(after.single().source).isEqualTo(Source.Both)
        assertThat(after.single().itemId).isEqualTo("tv")
    }

    @Test
    fun aVideoThisPhoneRestoredShowsOnceBeforeTheSync() {
        // Restored: the phone has the original again; the cached item still has the output (and the small size).
        val restored = media("DCIM/Camera/VID_1.mp4", "2026-11-05T10:00:00.000", size = 3_000_000, fingerprint = original, video = true)
        val cached = mapOf(output to "tv")
        assertThat(cells(restored, cached, 700_000, emptyMap())).hasSize(2)
        val aliases = CompressAliases.of(listOf(journal("restore", output, original, 700_000, 3_000_000)))
        val list = cells(restored, cached, 700_000, aliases)
        assertThat(list).hasSize(1)
        assertThat(list.single().source).isEqualTo(Source.Both)
        // After the sync the item has the original's fingerprint again.
        assertThat(cells(restored, mapOf(original to "tv"), 3_000_000, aliases)).hasSize(1)
    }

    @Test
    fun onlyRowsThatWroteTheFileAlias() {
        assertThat(CompressAliases.of(listOf(journal("compress", original, output, 3, 1, state = "verified", started = false)))).isEmpty()
        assertThat(CompressAliases.of(listOf(journal("compress", original, output, 3, 1, state = "writing")))).isEmpty()
        assertThat(CompressAliases.of(listOf(journal("compress", original, output, 3, 1, state = "abandoned")))).isEmpty()
        // Two files' rows: each maps its own new fingerprint (one hop, never a chain).
        val a = journal("compress", original, output, 3, 1)
        val b = journal("compress", fp(80), fp(81), 3, 1).copy(id = "8")
        assertThat(CompressAliases.of(listOf(a, b))).containsExactly(output, original, fp(81), fp(80))
    }
}
