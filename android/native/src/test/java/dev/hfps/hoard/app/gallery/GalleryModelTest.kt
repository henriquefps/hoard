package dev.hfps.hoard.app.gallery

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.gallery.GallerySamples.media
import dev.hfps.hoard.server.ServerAddress
import org.junit.Test
import java.time.LocalDate

/** ADR 0021: device shots grouped as the server groups them, merged once, filtered, cut into sections. */
class GalleryModelTest {
    @Test
    fun groupsPhoneFilesIntoShotsByFolderAndStem() {
        val shots = DeviceShots.group(
            listOf(
                media("DCIM/Camera/DSC1.JPG", "2024-01-01T10:00:00"),
                media("DCIM/Camera/dsc1.arw", "2024-01-01T10:00:00"),
                media("DCIM/Other/DSC1.JPG", "2024-01-01T10:00:00"),
                media("DCIM/Camera/IMG_9.HEIC", "2024-01-01T10:00:00"),
                media("DCIM/Camera/IMG_9.MOV", "2024-01-01T10:00:00", video = true),
                media("Movies/clip.mp4", "2024-01-01T10:00:00", video = true),
            ),
        )
        val byKey = shots.associateBy { it.key }
        val pair = byKey.getValue("device/DCIM/Camera/dsc1")
        assertThat(pair.kind).isEqualTo(Kind.Pair)
        assertThat(pair.main.name).isEqualTo("dsc1.arw") // RAW first (ADR 0014)
        assertThat(byKey.getValue("device/DCIM/Other/dsc1").kind).isEqualTo(Kind.Photo)
        assertThat(byKey.getValue("device/DCIM/Camera/img_9").kind).isEqualTo(Kind.Motion) // Live Photo: still + video
        assertThat(byKey.getValue("device/DCIM/Camera/img_9").main.name).isEqualTo("IMG_9.HEIC")
        assertThat(byKey.getValue("device/Movies/clip").kind).isEqualTo(Kind.Video)
    }

    @Test
    fun aShotOnThePhoneAndTheServerShowsOnceBackedUp() {
        val entries = GallerySamples.entries()
        val pair = entries.single { it.itemId == "pair-1" }
        assertThat(pair.source).isEqualTo(Source.Both)
        assertThat(pair.device!!.main.name).isEqualTo("DSC01000.JPG")
        assertThat(pair.kind).isEqualTo(Kind.Pair)
        assertThat(pair.decision).isEqualTo(EntryDecision(4, "pick"))
        assertThat(entries.count { it.name.startsWith("DSC01000") }).isEqualTo(1)
        // Phone-only: their own decision rows; not fingerprinted yet: still phone-only.
        val local = entries.single { it.name == "PXL_20241110_0001.jpg" }
        assertThat(local.source).isEqualTo(Source.Device)
        assertThat(local.key).isEqualTo("d:device/DCIM/Camera/pxl_20241110_0001")
        assertThat(local.decision.rating).isEqualTo(3)
        assertThat(entries.single { it.name == "Screenshot_1.png" }.source).isEqualTo(Source.Device)
        assertThat(entries.single { it.itemId == "video-1" }.source).isEqualTo(Source.Server)
        assertThat(entries.single { it.name == "PXL_MOTION.MP.jpg" }.kind).isEqualTo(Kind.Motion)
    }

    @Test
    fun newestFirstWithTheShotsOwnDay() {
        val entries = GallerySamples.entries()
        assertThat(entries.map { it.time }).isInOrder(Comparator.reverseOrder<Long>())
        assertThat(entries.first().name).isEqualTo("Screenshot_1.png")
        // The camera's wall clock decides the day (a shot at 23:30 in its zone stays on its day).
        assertThat(GalleryModel.dayOf("2024-05-03T23:30:00.000", 0, java.time.ZoneOffset.UTC)).isEqualTo(LocalDate.of(2024, 5, 3))
        assertThat(GalleryModel.camera("SONY", "ILCE-7M4")).isEqualTo("SONY ILCE-7M4")
        assertThat(GalleryModel.camera("Canon", "Canon EOS R6")).isEqualTo("Canon EOS R6")
        assertThat(GalleryModel.camera(null, null)).isNull()
    }

    @Test
    fun filtersByKindSourceStarsFlagEditedCameraDateAndText() {
        val e = GallerySamples.entries()
        fun names(f: GalleryFilter) = e.filter(f::matches).map { it.name }
        assertThat(names(GalleryFilter(kind = KindFilter.Videos))).containsExactly("PXL_20241110_VID.mp4", "C0001.MP4", "PXL_MOTION.MP.jpg", "PXL_20231224_MOTION.MP.jpg")
        assertThat(names(GalleryFilter(kind = KindFilter.RawPairs))).containsExactly("DSC01006.ARW", "DSC01000.ARW")
        assertThat(names(GalleryFilter(source = SourceFilter.Both))).containsExactly("DSC01000.ARW")
        assertThat(names(GalleryFilter(source = SourceFilter.Server))).hasSize(6)
        assertThat(names(GalleryFilter(minRating = 4))).containsExactly("DSC01006.ARW", "DSC01000.ARW")
        assertThat(names(GalleryFilter(flag = FlagChoice.Rejected))).containsExactly("DSC01001.JPG")
        assertThat(names(GalleryFilter(editedOnly = true))).containsExactly("DSC01006.ARW")
        assertThat(names(GalleryFilter(camera = "Canon EOS R6"))).containsExactly("IMG_0300.JPG")
        assertThat(names(GalleryFilter(lens = "RF24-105mm F4 L IS USM"))).containsExactly("IMG_0300.JPG")
        assertThat(lenses(e).first()).isEqualTo("FE 35mm F1.8" to 5)
        assertThat(names(GalleryFilter(from = LocalDate.of(2023, 12, 1), to = LocalDate.of(2023, 12, 31)))).containsExactly("PXL_20231224_MOTION.MP.jpg")
        // Every word, names and folders.
        assertThat(names(GalleryFilter(query = "snow img"))).containsExactly("IMG_0300.JPG")
        assertThat(names(GalleryFilter(query = "screenshots"))).containsExactly("Screenshot_1.png")
        assertThat(GalleryFilter(minRating = 2, editedOnly = true).count).isEqualTo(2)
        assertThat(GalleryFilter(query = "x").active).isFalse()
        assertThat(cameras(e).first()).isEqualTo("SONY ILCE-7M4" to 4)
    }

    @Test
    fun sectionsPerDayOrMonthAndScrollerMarks() {
        val e = GallerySamples.entries()
        val days = layout(e, byMonth = false)
        val headers = days.rows.filterIsInstance<GridRow.Header>()
        assertThat(headers.map { it.day }).containsExactly(
            LocalDate.of(2024, 11, 11), LocalDate.of(2024, 11, 10), LocalDate.of(2024, 11, 9), LocalDate.of(2024, 5, 3), LocalDate.of(2023, 12, 24), LocalDate.of(2023, 1, 15),
        ).inOrder()
        assertThat(headers.first { it.day == LocalDate.of(2024, 5, 3) }.count).isEqualTo(4)
        assertThat(days.rows.count { it is GridRow.Cell }).isEqualTo(e.size)
        val months = layout(e, byMonth = true)
        assertThat(months.rows.filterIsInstance<GridRow.Header>().map { it.id }).containsExactly("m:2024-11", "m:2024-5", "m:2023-12", "m:2023-1").inOrder()
        assertThat(days.marks.map { it.year to it.month }).containsExactly(2024 to 11, 2024 to 5, 2023 to 12, 2023 to 1).inOrder()
        assertThat(days.marks.filter { it.yearStart }.map { it.year }).containsExactly(2024, 2023)
        val row = days.rowOf("s:old-1")
        assertThat(days.markAt(row)!!.year).isEqualTo(2023)
        val big = layout(GallerySamples.big(10_000), byMonth = false)
        assertThat(big.rows.size).isEqualTo(10_000 + 1667)
    }

    @Test
    fun phoneFoldersAndLocations() {
        assertThat(DeviceFolders.locate("/storage/emulated/0/DCIM/Camera/a.jpg", "/storage/emulated/0")).isEqualTo("device" to "DCIM/Camera/a.jpg")
        assertThat(DeviceFolders.locate("/storage/1A2B-3C4D/DCIM/a.jpg", "/storage/emulated/0")).isEqualTo("vol-1A2B-3C4D" to "DCIM/a.jpg")
        assertThat(DeviceFolders.locate("/storage/emulated/10/DCIM/a.jpg", "/storage/emulated/0")).isNull()
        assertThat(DeviceFolders.included("DCIM/Camera/a.jpg", "/x", emptyList())).isTrue()
        assertThat(DeviceFolders.included("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-1.jpg", "/x", emptyList())).isTrue()
        assertThat(DeviceFolders.included("DCIM/.thumbnails/a.jpg", "/x", emptyList())).isFalse()
        assertThat(DeviceFolders.included("Download/Shoot/a.jpg", "/storage/emulated/0/Download/Shoot/a.jpg", emptyList())).isFalse()
        assertThat(DeviceFolders.included("Download/Shoot/a.jpg", "/storage/emulated/0/Download/Shoot/a.jpg", listOf("/storage/emulated/0/Download/Shoot"))).isTrue()
    }

    @Test
    fun motionPhotosAreSeenCheaply() {
        val xmp = "<x:xmpmeta GCamera:MotionPhoto=\"1\"/>".toByteArray()
        assertThat(MotionPhotos.isMotion("PXL_1.jpg", xmp, ByteArray(16))).isTrue()
        assertThat(MotionPhotos.isMotion("PXL_1.MP.jpg", ByteArray(10), ByteArray(16))).isTrue()
        assertThat(MotionPhotos.isMotion("20240101.jpg", ByteArray(10), "xxxxxxxxxxxxSEFT".toByteArray())).isTrue()
        assertThat(MotionPhotos.isMotion("IMG_1.jpg", ByteArray(10), ByteArray(16))).isFalse()
    }

    @Test
    fun plainHttpOnlyToLocalAddresses() {
        assertThat(ServerAddress.allowed("http://10.0.2.2:3981")).isTrue()
        assertThat(ServerAddress.allowed("http://192.168.1.20:3071")).isTrue()
        assertThat(ServerAddress.allowed("http://100.87.163.92:3071")).isTrue()
        assertThat(ServerAddress.allowed("http://cachyos-fps:3071")).isTrue()
        assertThat(ServerAddress.allowed("http://nas.local:3071")).isTrue()
        assertThat(ServerAddress.allowed("http://box.tail1234.ts.net")).isTrue()
        assertThat(ServerAddress.allowed("https://photos.example.org")).isTrue()
        assertThat(ServerAddress.allowed("http://photos.example.org")).isFalse()
        assertThat(ServerAddress.allowed("http://8.8.8.8")).isFalse()
        assertThat(ServerAddress.allowed("ftp://10.0.0.1")).isFalse()
    }

    @Test
    fun revealGetsServerShotsByReferenceAndPhoneShotsByUri() {
        val e = GallerySamples.entries()
        val both = e.single { it.itemId == "pair-1" }
        val serverOnly = e.single { it.itemId == "edit-1" }
        val phoneOnly = e.single { it.name == "PXL_20241110_0001.jpg" }
        assertThat(GalleryReveal.route(both, paired = true, acceptsReferences = true)).isEqualTo(GalleryReveal.Route.Server)
        assertThat(GalleryReveal.route(serverOnly, paired = true, acceptsReferences = true)).isEqualTo(GalleryReveal.Route.Server)
        assertThat(GalleryReveal.route(phoneOnly, paired = true, acceptsReferences = true)).isEqualTo(GalleryReveal.Route.Uri)
        // An older Reveal: phone files as URIs; a server-only shot cannot go (no streaming links).
        assertThat(GalleryReveal.route(both, paired = true, acceptsReferences = false)).isEqualTo(GalleryReveal.Route.Uri)
        assertThat(GalleryReveal.route(serverOnly, paired = true, acceptsReferences = false)).isEqualTo(GalleryReveal.Route.Unavailable)
        val item = dev.hfps.hoard.server.api.Item(
            id = "pair-1", rev = 1, mainFingerprint = "v1:raw",
            files = listOf(
                dev.hfps.hoard.server.api.ItemFile("v1:raw", "raw", "DSC1.ARW"),
                dev.hfps.hoard.server.api.ItemFile("v1:jpg", "rendered", "DSC1.JPG"),
                dev.hfps.hoard.server.api.ItemFile("v1:xmp", "sidecar", "DSC1.xmp"),
            ),
        )
        assertThat(GalleryReveal.reference(item)).isEqualTo(dev.hfps.hoard.smb.share.RevealHandoff.ServerItem("pair-1", "v1:raw", listOf("v1:raw", "v1:jpg"), "DSC1.ARW"))
    }
}
