package dev.hfps.hoard.smb.location

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocationsTest {

    private fun code(block: () -> Unit) = assertThrows(HoardSmbException::class.java) { block() }.code

    @Test
    fun parsesEveryLocationKind() {
        assertEquals(Location.Device, Location.parse(JSONObject("""{"kind":"device"}""")))
        assertEquals(Location.Volume("1A2B-3C4D"), Location.parse(JSONObject("""{"kind":"volume","uuid":"1A2B-3C4D"}""")))
        assertEquals(Location.Smb("s1", "media"), Location.parse(JSONObject("""{"kind":"smb","sessionId":"s1","share":"media"}""")))
        assertEquals(ErrorCode.UNKNOWN, code { Location.parse(null) })
        assertEquals(ErrorCode.UNKNOWN, code { Location.parse(JSONObject("""{"kind":"ftp"}""")) })
        assertEquals(ErrorCode.UNKNOWN, code { Location.parse(JSONObject("""{"kind":"smb","share":"x"}""")) })
    }

    @Test
    fun volumeUuidsCannotEscapeStorage() {
        listOf("../x", "a/b", "", "-x", "1A2B 3C4D", "1A2B-3C4D/..").forEach {
            assertEquals(it, ErrorCode.UNKNOWN, code { Location.requireUuid(it) })
        }
        assertEquals("8285-1702", Location.requireUuid("8285-1702"))
        assertEquals("0123456789abcdef", Location.requireUuid("0123456789abcdef"))
    }

    @Test
    fun pathsStayBelowTheRoot() {
        val root = File("/storage/emulated/0")
        assertEquals(root, LocalPaths.resolve(root, ""))
        assertEquals(File(root, "DCIM/Camera"), LocalPaths.resolve(root, "/DCIM//./Camera/"))
        assertEquals(ErrorCode.UNKNOWN, code { LocalPaths.resolve(root, "DCIM/../../..") })
        assertEquals(ErrorCode.UNKNOWN, code { LocalPaths.resolve(root, "a\u0000b") })
        assertEquals(ErrorCode.UNKNOWN, code { LocalPaths.requireValidName("a/b") })
        assertEquals(ErrorCode.UNKNOWN, code { LocalPaths.requireValidName("..") })
        assertEquals("Lisboa ☀️ 日本語.jpg", LocalPaths.requireValidName("Lisboa ☀️ 日本語.jpg"))
        assertTrue(LocalPaths.isInside("/storage/1A2B", "/storage/1A2B/DCIM"))
        assertFalse(LocalPaths.isInside("/storage/1A2B", "/storage/1A2BC"))
    }

    @Test
    fun androidDataAndObbAreRestrictedFromAndroid11() {
        assertTrue(LocalPaths.isRestricted("Android/data", 30))
        assertTrue(LocalPaths.isRestricted("android/OBB/com.game", 34))
        assertFalse(LocalPaths.isRestricted("Android/media", 34))
        assertFalse(LocalPaths.isRestricted("Android", 34))
        assertFalse(LocalPaths.isRestricted("DCIM/Android/data", 34))
        assertFalse(LocalPaths.isRestricted("Android/data", 29))
    }

    @Test
    fun mapsVolumeStatesAndKinds() {
        assertEquals("mounted", VolumeMapping.state("mounted"))
        assertEquals("mountedReadOnly", VolumeMapping.state("mounted_ro"))
        assertEquals("checking", VolumeMapping.state("checking"))
        assertEquals("unmounted", VolumeMapping.state("nofs"))
        assertNull(VolumeMapping.state("removed"))
        assertNull(VolumeMapping.state("bad_removal"))
        assertEquals("internal", VolumeMapping.kind(true, false, "Internal shared storage", false))
        assertEquals("usb", VolumeMapping.kind(false, true, "SanDisk USB drive", false))
        assertEquals("sd", VolumeMapping.kind(false, true, "Virtual SD card", false))
        assertEquals("sd", VolumeMapping.kind(false, true, "Cartão SD", false))
        assertEquals("usb", VolumeMapping.kind(false, true, "Kingston", true))
        assertEquals("other", VolumeMapping.kind(false, true, "Kingston", false))
    }

    @Test
    fun picksPathThenSafThenNothing() {
        assertEquals("path", VolumeMapping.access(readable = true, pathReadable = true, allFiles = true, safGranted = true))
        assertEquals("saf", VolumeMapping.access(readable = true, pathReadable = true, allFiles = false, safGranted = true))
        assertEquals("saf", VolumeMapping.access(readable = true, pathReadable = false, allFiles = true, safGranted = true))
        assertEquals("none", VolumeMapping.access(readable = true, pathReadable = true, allFiles = false, safGranted = false))
        assertEquals("none", VolumeMapping.access(readable = false, pathReadable = true, allFiles = true, safGranted = true))
    }

    @Test
    fun readsTheRealFileSystemNotTheFuseView() {
        val mounts = """
            /dev/block/vold/public:7,433 /mnt/media_rw/8285-1702 vfat rw,dirsync 0 0
            /dev/fuse /storage/8285-1702 fuse rw,lazytime 0 0
            /dev/fuse /storage/My\040Drive fuse rw 0 0
            /dev/block/sda1 /mnt/media_rw/ABCD-1234 exfat rw 0 0
        """.trimIndent()
        assertEquals("vfat", VolumeMapping.volumeFileSystem(mounts, "8285-1702", "/storage/8285-1702"))
        assertEquals("exfat", VolumeMapping.volumeFileSystem(mounts, "ABCD-1234", null))
        assertNull(VolumeMapping.volumeFileSystem(mounts, "FFFF-0000", "/storage/My Drive"))
        assertEquals("fuse", VolumeMapping.fileSystem(mounts, "/storage/My Drive"))
    }

    @Test
    fun safDocumentIdsAndVolumeGrants() {
        assertEquals("8285-1702:DCIM/100MSDCF", SafVolumes.documentId("8285-1702", "/DCIM/100MSDCF/"))
        assertEquals("8285-1702:", SafVolumes.documentId("8285-1702", ""))
        assertEquals("8285-1702" to "", SafVolumes.grantOf("8285-1702:"))
        assertEquals("8285-1702" to "DCIM/100MSDCF", SafVolumes.grantOf("8285-1702:DCIM/100MSDCF"))
        assertNull(SafVolumes.grantOf("primary:DCIM"))
        assertNull(SafVolumes.grantOf("8285-1702:DCIM/../.."))
        assertNull(SafVolumes.grantOf(null))
    }

    @Test
    fun safFolderGrantsCoverTheirSubtreesAndLeadToThem() {
        // Android 11+ refuses a volume root in the picker: folder grants only.
        val grants = listOf("DCIM/100MSDCF", "Music", "DCIM/101MSDCF")
        assertEquals("DCIM/100MSDCF", SafVolumes.grantFor(grants, "DCIM/100MSDCF/a.jpg"))
        assertEquals("Music", SafVolumes.grantFor(grants, "Music"))
        assertNull(SafVolumes.grantFor(grants, "DCIM"))
        assertNull(SafVolumes.grantFor(grants, "Musical"))
        assertEquals("", SafVolumes.grantFor(listOf(""), "anything/below"))
        assertEquals(listOf("DCIM", "Music"), SafVolumes.childrenTowardGrants(grants, ""))
        assertEquals(listOf("100MSDCF", "101MSDCF"), SafVolumes.childrenTowardGrants(grants, "DCIM"))
        assertEquals(emptyList<String>(), SafVolumes.childrenTowardGrants(grants, "Movies"))
    }
}
