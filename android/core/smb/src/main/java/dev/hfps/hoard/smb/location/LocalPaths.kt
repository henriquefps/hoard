package dev.hfps.hoard.smb.location

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.smbFail
import java.io.File
import java.util.Locale

/**
 * Path safety for local locations (pure, JVM-tested). A contract path never leaves its root:
 * `..` is rejected, empty and `.` segments are dropped, and NUL or control characters are refused
 * (the kernel would cut a path at NUL). Names follow the Windows rules (`SmbPaths.requireValidName`)
 * because removable volumes are FAT/exFAT, which refuse the same characters.
 */
object LocalPaths {

    /** The file for contract [path] below [root]. */
    fun resolve(root: File, path: String): File {
        val clean = normalize(path)
        return if (clean.isEmpty()) root else File(root, clean)
    }

    fun normalize(path: String?): String {
        if (path != null && path.any { it.code < 32 }) smbFail(ErrorCode.UNKNOWN, "Invalid character in path")
        return SmbPaths.normalize(path)
    }

    fun requireValidName(name: String?, what: String = "name"): String = SmbPaths.requireValidName(name, what)

    /**
     * Folders Android keeps to apps on Android 11+ (API 30): `Android/data` and `Android/obb` and
     * everything below them, on the primary storage and on every volume. All files access does not
     * open them; listing returns nothing, so Hoard shows them as not accessible instead.
     */
    fun isRestricted(path: String, sdkInt: Int): Boolean {
        if (sdkInt < 30) return false
        val segments = normalize(path).split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return false
        return segments[0].equals("Android", ignoreCase = true) &&
            segments[1].lowercase(Locale.ROOT) in RESTRICTED
    }

    private val RESTRICTED = setOf("data", "obb")

    /** True when [child] is [parent] or below it (both canonical paths). */
    fun isInside(parent: String, child: String): Boolean =
        child == parent || child.startsWith(parent.trimEnd('/') + "/")
}
