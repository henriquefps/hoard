package dev.hfps.hoard.smb

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Path helpers. Contract paths are `/`-separated, without a leading slash, relative to the share
 * root (`""` is the root). smbj wants `\`-separated paths.
 */
object SmbPaths {

    /** Characters Windows (and therefore most SMB servers) refuse in a file name. */
    private val FORBIDDEN_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /**
     * Canonical contract path: separators unified, empty and `.` segments dropped. `..` is rejected
     * (paths are always absolute within the share).
     */
    fun normalize(path: String?): String {
        if (path.isNullOrEmpty()) return ""
        val segments = path.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) smbFail(ErrorCode.UNKNOWN, "Path must not contain '..': $path")
        return segments.joinToString("/")
    }

    /** smbj path for a contract path (`Photos/2026` → `Photos\2026`). */
    fun toSmb(path: String): String = normalize(path).replace('/', '\\')

    fun join(folder: String, name: String): String {
        val cleanFolder = normalize(folder)
        return if (cleanFolder.isEmpty()) name else "$cleanFolder/$name"
    }

    /** Last segment; `""` for the root. */
    fun name(path: String): String = normalize(path).substringAfterLast('/')

    /** Parent folder; `""` for top-level entries and for the root. */
    fun parent(path: String): String {
        val clean = normalize(path)
        val slash = clean.lastIndexOf('/')
        return if (slash < 0) "" else clean.substring(0, slash)
    }

    /** Validates a single file/folder name (createFolder, rename, destName). */
    fun requireValidName(name: String?, what: String = "name"): String {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
            smbFail(ErrorCode.UNKNOWN, "Invalid $what: '${name.orEmpty()}'")
        }
        if (trimmed.any { it in FORBIDDEN_NAME_CHARS || it.code < 32 }) {
            smbFail(ErrorCode.UNKNOWN, "Invalid character in $what: '$trimmed'")
        }
        if (trimmed.endsWith('.') || trimmed.endsWith(' ')) {
            smbFail(ErrorCode.UNKNOWN, "A $what cannot end with a dot or a space: '$trimmed'")
        }
        return trimmed
    }

    /** Lower-case extension without the dot (`IMG.JPG` → `jpg`), `""` when there is none. */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
    }
}

/** ISO-8601 UTC timestamps (`2026-11-08T21:14:03.120Z`) without java.time (minSdk 24). */
object IsoTime {
    private val format = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun format(epochMillis: Long): String = format.get()!!.format(Date(epochMillis))

    fun now(): String = format(System.currentTimeMillis())

    fun parse(iso: String?): Long? = try {
        iso?.let { format.get()!!.parse(it)?.time }
    } catch (_: Exception) {
        null
    }

    /**
     * SMB FILETIME epoch millis → ISO, or null when the server sent 0 / a pre-1980 placeholder
     * ("does not report it").
     */
    fun fromSmbMillis(epochMillis: Long?): String? =
        if (epochMillis == null || epochMillis <= 315_532_800_000L) null else format(epochMillis)
}

/** File names for `conflict: "rename"`: `photo.jpg` → `photo (1).jpg` → `photo (2).jpg`. */
object ConflictNames {

    private val SUFFIX = Regex("""^(.*) \((\d+)\)$""")

    /** The first name not in [taken] (compared case-insensitively, like FAT/exFAT/sdcardfs). */
    fun firstFree(wanted: String, taken: Collection<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        if (wanted.lowercase(Locale.ROOT) !in lower) return wanted
        val (stem, ext) = split(wanted)
        // `photo (1).jpg` already taken → continue counting from its own number, not `photo (1) (1).jpg`.
        val match = SUFFIX.matchEntire(stem)
        val base = match?.groupValues?.get(1) ?: stem
        var n = match?.groupValues?.get(2)?.toIntOrNull()?.plus(1) ?: 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate.lowercase(Locale.ROOT) !in lower) return candidate
            n++
        }
    }

    /** `archive.tar.gz` keeps `.gz` as the extension (like Android's DocumentsProvider); dotfiles have none. */
    fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }
}
