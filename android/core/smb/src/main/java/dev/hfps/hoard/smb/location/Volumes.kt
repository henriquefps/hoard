package dev.hfps.hoard.smb.location

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.smbFail
import java.io.File
import java.util.Locale

/** Contract `StorageVolumeInfo`. */
data class VolumeInfo(
    val uuid: String?,
    val label: String,
    val primary: Boolean,
    val removable: Boolean,
    val kind: String,
    val state: String,
    val path: File?,
    val totalBytes: Long?,
    val freeBytes: Long?,
    val fileSystem: String?,
    val access: String,
)

/** Pure mapping rules (JVM-tested): Android's states and descriptions → contract values. */
object VolumeMapping {

    /** `Environment.MEDIA_*` → contract `VolumeState`; null for "removed" (the volume is gone). */
    fun state(mediaState: String?): String? = when (mediaState) {
        "mounted" -> "mounted"
        "mounted_ro" -> "mountedReadOnly"
        "checking" -> "checking"
        "ejecting" -> "ejecting"
        "unmountable" -> "unmountable"
        "removed", "bad_removal" -> null
        else -> "unmounted" // unmounted, nofs, shared, unknown
    }

    fun isReadable(state: String?) = state == "mounted" || state == "mountedReadOnly"

    /**
     * Android does not say whether a removable volume is a USB drive or an SD card in public API;
     * its own description does ("SanDisk USB drive", "SD card"), in English on most phones, and a
     * USB mass-storage device attached right now settles the rest.
     */
    fun kind(primary: Boolean, removable: Boolean, description: String?, usbMassStorageAttached: Boolean): String {
        if (primary || !removable) return "internal"
        val text = description.orEmpty().lowercase(Locale.ROOT)
        return when {
            "usb" in text -> "usb"
            Regex("\\bsd\\b|sd card|microsd|cartão sd").containsMatchIn(text) -> "sd"
            usbMassStorageAttached -> "usb"
            else -> "other"
        }
    }

    /** Filesystem of the mount at [path] from `/proc/mounts` text (the last, most specific match). */
    fun fileSystem(procMounts: String, path: String): String? = procMounts.lineSequence()
        .map { it.split(' ') }
        .filter { it.size >= 3 && decodeMountPath(it[1]) == path }
        .map { it[2] }
        .lastOrNull()
        ?.takeIf { it.isNotBlank() }

    /**
     * The real filesystem of a volume: Android mounts it under `/mnt/media_rw/<uuid>` and shows apps
     * a FUSE (or sdcardfs) view at `/storage/<uuid>`, so the view's type says nothing useful.
     */
    fun volumeFileSystem(procMounts: String, uuid: String?, path: String?): String? {
        val raw = uuid?.let { fileSystem(procMounts, "/mnt/media_rw/$it") }
            ?: path?.let { fileSystem(procMounts, it) }
        return raw?.takeUnless { it == "fuse" || it == "sdcardfs" || it.startsWith("fuse.") }
    }

    /** `/proc/mounts` escapes spaces and tabs as octal (`\040`). */
    private fun decodeMountPath(field: String) = field.replace("\\040", " ").replace("\\011", "\t")

    /** How Hoard reads a volume now: by path, through its SAF tree, or not at all. */
    fun access(readable: Boolean, pathReadable: Boolean, allFiles: Boolean, safGranted: Boolean): String = when {
        !readable -> "none"
        allFiles && pathReadable -> "path"
        safGranted -> "saf"
        else -> "none"
    }
}

/** All files access: `MANAGE_EXTERNAL_STORAGE` on Android 11+, the storage permissions before. */
object StorageAccess {
    fun granted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        read && write
    }

    fun mechanism(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "allFiles" else "runtime"
}

/**
 * The phone's storage volumes and the [LocalFs] for a location. The primary volume is the `device`
 * location; removable ones are keyed by UUID.
 */
class VolumeRegistry(context: Context) {

    private val context = context.applicationContext
    private val storage = context.getSystemService(StorageManager::class.java)
    private val resolver: ContentResolver = context.contentResolver

    private fun usbMassStorageAttached(): Boolean = runCatching {
        val usb = context.getSystemService(UsbManager::class.java) ?: return false
        usb.deviceList.values.any { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
        }
    }.getOrDefault(false)

    private fun directoryOf(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.directory
        if (volume.isPrimary) return Environment.getExternalStorageDirectory()
        // StorageVolume.getPath() is hidden before Android 11 but present on every version.
        val path = runCatching { volume.javaClass.getMethod("getPath").invoke(volume) as? String }.getOrNull()
        return path?.let(::File) ?: volume.uuid?.let { File("/storage/$it") }
    }

    /** Persisted folder grants per volume uuid (paths inside the volume), and whether all are writable. */
    private fun safGrants(): Map<String, Pair<List<String>, Boolean>> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) && it.uri.authority == SafVolumes.AUTHORITY }
        .mapNotNull { permission ->
            SafVolumes.grantOf(runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull())
                ?.let { (uuid, sub) -> Triple(uuid.uppercase(Locale.ROOT), sub, permission.isWritePermission) }
        }
        .groupBy({ it.first }) { it.second to it.third }
        .mapValues { (_, list) -> list.map { it.first }.distinct() to list.all { it.second } }

    private fun readMounts(): String = runCatching { File("/proc/mounts").readText() }.getOrDefault("")

    fun list(): List<VolumeInfo> {
        val allFiles = StorageAccess.granted(context)
        val grants = safGrants()
        val usb = usbMassStorageAttached()
        val mounts = readMounts()
        val volumes = storage.storageVolumes.mapNotNull { volume ->
            val state = VolumeMapping.state(volume.state) ?: return@mapNotNull null
            val dir = directoryOf(volume)
            val readable = VolumeMapping.isReadable(state)
            val stats = if (readable && dir != null) runCatching { StatFs(dir.path) }.getOrNull() else null
            val uuid = if (volume.isPrimary) null else volume.uuid ?: return@mapNotNull null
            VolumeInfo(
                uuid = uuid,
                label = volume.getDescription(context) ?: if (volume.isPrimary) "Internal storage" else "USB drive",
                primary = volume.isPrimary,
                removable = volume.isRemovable,
                kind = VolumeMapping.kind(volume.isPrimary, volume.isRemovable, volume.getDescription(context), usb),
                state = state,
                path = dir,
                totalBytes = stats?.totalBytes,
                freeBytes = stats?.availableBytes,
                fileSystem = VolumeMapping.volumeFileSystem(mounts, uuid, dir?.path),
                access = VolumeMapping.access(
                    readable = readable,
                    pathReadable = dir?.canRead() == true,
                    allFiles = allFiles,
                    safGranted = uuid != null && uuid.uppercase(Locale.ROOT) in grants,
                ),
            )
        }
        return volumes.sortedByDescending { it.primary }
    }

    fun find(uuid: String): VolumeInfo? = list().firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }

    fun storageVolume(uuid: String): StorageVolume? = storage.storageVolumes.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }

    /** The [LocalFs] for a device or volume location, or the reason it cannot be read now. */
    fun fs(location: Location.Local): LocalFs = when (location) {
        Location.Device -> {
            if (!StorageAccess.granted(context)) {
                smbFail(ErrorCode.STORAGE_PERMISSION, "All files access is needed to browse the phone's storage")
            }
            val root = Environment.getExternalStorageDirectory()
            if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
                smbFail(ErrorCode.UNAVAILABLE, "The phone's storage is not available", mapOf("state" to VolumeMapping.state(Environment.getExternalStorageState())))
            }
            PathFs(context, root, "device", "Internal storage", readOnly = false)
        }
        is Location.Volume -> {
            val info = find(location.uuid) ?: smbFail(ErrorCode.UNAVAILABLE, "The volume is not connected", mapOf("state" to "removed"))
            if (!VolumeMapping.isReadable(info.state)) {
                smbFail(ErrorCode.UNAVAILABLE, "${info.label} is not mounted", mapOf("state" to info.state))
            }
            val readOnly = info.state == "mountedReadOnly"
            when (info.access) {
                // Before Android 11 apps cannot write removable volumes by path (only through SAF).
                "path" -> PathFs(context, info.path!!, "volume:${info.uuid}", info.label, readOnly || Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                "saf" -> {
                    val (folders, writable) = safGrants()[info.uuid!!.uppercase(Locale.ROOT)] ?: (emptyList<String>() to false)
                    SafFs(context, info.uuid, folders, info.label, readOnly || !writable)
                }
                else -> smbFail(ErrorCode.STORAGE_PERMISSION, "Hoard has no access to ${info.label}", mapOf("saf" to (Build.VERSION.SDK_INT >= 29)))
            }
        }
    }
}
