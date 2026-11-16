package dev.hfps.hoard.app.gallery

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.transfers.FolderPlace
import dev.hfps.hoard.app.transfers.SERVER_LOCATION_ID
import dev.hfps.hoard.app.transfers.TransferPlace
import dev.hfps.hoard.app.transfers.isMachine
import dev.hfps.hoard.server.api.FileLocation
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ItemFile
import org.junit.Test

/** "Save to this device" (ADR 0024 A2): whole shots, one transfer per folder, never Hoard's copies for Reveal. */
class SaveToDeviceTest {
    private fun loc(device: String?, path: String, modified: Long = 7) =
        if (device == null) FileLocation("nas", "f1", "photos", path, modified) else FileLocation("mirror", path = path, modifiedAt = modified, physicalDeviceId = "p-$device", deviceName = device, rootId = "r", volume = "primary")

    private fun shot(id: String, device: String?, vararg files: Pair<String, String>) = Item(
        id = id,
        rev = 1,
        kind = if (files.size > 1) "pair" else "photo",
        mainFingerprint = "v1:${files.first().first}",
        files = files.map { (fp, name) -> ItemFile("v1:$fp", if (name.endsWith(".xmp")) "sidecar" else "rendered", name, null, 100, listOf(loc(device, "DCIM/Camera/$name"))) },
    )

    @Test
    fun aWholeShotGoesToItsPhonesFolderUnderPictures() {
        val pair = shot("i1", "Pixel 8", "jpg" to "PXL_1.jpg", "dng" to "PXL_1.dng", "xmp" to "PXL_1.xmp")
        val request = SaveToDevice.requests(listOf(pair), null, "srv", "ask", "hoard").single()
        assertThat(request.source).isEqualTo(TransferPlace(SERVER_LOCATION_ID, share = "srv"))
        assertThat(request.paths).containsExactly("v1:jpg/PXL_1.jpg", "v1:dng/PXL_1.dng", "v1:xmp/PXL_1.xmp").inOrder()
        assertThat(request.destination).isEqualTo(FolderPlace(TransferPlace("device"), "Pictures/From Pixel 8"))
        assertThat(request.op).isEqualTo("copy")
        assertThat(request.size).isEqualTo(300)
        assertThat(request.itemsTotal).isEqualTo(3)
        assertThat(request.conflict).isEqualTo("ask")
        assertThat(SaveToDevice.modifiedOf(pair, "v1:dng")).isEqualTo(7)
    }

    @Test
    fun shotsOfTwoPhonesGoToTwoFoldersUnlessAFolderWasChosen() {
        val a = shot("a", "Pixel 8", "1" to "A.jpg")
        val b = shot("b", "Tab S9", "2" to "B.jpg")
        val nas = shot("n", null, "3" to "N.jpg")
        val byDefault = SaveToDevice.requests(listOf(a, b, nas), null, "srv", "rename", "Home server")
        assertThat(byDefault.map { it.destination.path }).containsExactly("Pictures/From Pixel 8", "Pictures/From Tab S9", "Pictures/From Home server").inOrder()
        val chosen = FolderPlace(TransferPlace("vol-1234-ABCD"), "Photos/Saved")
        val once = SaveToDevice.requests(listOf(a, b, nas), chosen, "srv", "rename", null)
        assertThat(once).hasSize(1)
        assertThat(once.single().destination).isEqualTo(chosen)
        assertThat(once.single().paths).hasSize(3)
        // The choice is kept as a setting and read back.
        assertThat(SaveToDevice.chosen(SaveToDevice.chosenJson(chosen, "Saved"))).isEqualTo(chosen)
        assertThat(SaveToDevice.chosen("")).isNull()
        assertThat(SaveToDevice.chosen(null)).isNull()
    }

    @Test
    fun neverUnderHoardsCopiesForReveal() {
        assertThat(SaveToDevice.refused("Pictures/Hoard")).isTrue()
        assertThat(SaveToDevice.refused("pictures/hoard/Trip")).isTrue()
        assertThat(SaveToDevice.refused("/Download/Hoard/")).isTrue()
        assertThat(SaveToDevice.refused("Pictures/Hoarding")).isFalse()
        assertThat(SaveToDevice.refused("Pictures/From Pixel 8")).isFalse()
        assertThat(SaveToDevice.defaultFolder("Pixel 8").path).isEqualTo("Pictures/From Pixel 8")
    }

    @Test
    fun folderAndFileNamesAreSafe() {
        assertThat(SaveToDevice.folderName("Ana's  \"Phone\"/2")).isEqualTo("From Ana's Phone 2")
        assertThat(SaveToDevice.folderName("  ")).isEqualTo("From Hoard server")
        assertThat(SaveToDevice.folderName(null)).isEqualTo("From Hoard server")
        assertThat(SaveToDevice.nameOf("v1:abcdef0123456789ff", "", "jpeg")).isEqualTo("abcdef0123456789.jpg")
        assertThat(SaveToDevice.nameOf("v1:ab", "x/PXL.jpg", null)).isEqualTo("PXL.jpg")
        assertThat(SaveToDevice.nameOf("v1:ab", "", null)).isEqualTo("ab.bin")
    }

    @Test
    fun theServerIsNotAMachine() {
        assertThat(isMachine(SERVER_LOCATION_ID)).isFalse()
        assertThat(SaveToDevice.requests(emptyList(), null, "srv", "ask", null)).isEmpty()
        // An unknown policy falls back to asking.
        assertThat(SaveToDevice.requests(listOf(shot("a", "P", "1" to "A.jpg")), null, "srv", "bogus", null).single().conflict).isEqualTo("ask")
    }
}
