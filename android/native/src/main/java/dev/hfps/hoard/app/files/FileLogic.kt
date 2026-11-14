package dev.hfps.hoard.app.files

import dev.hfps.hoard.smb.SmbEntry
import java.text.Collator
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/*
 * The browser's pure rules, ported from src/lib/files.ts, src/lib/photos.ts and src/features/reveal/handoff.ts
 * (FileLogicTest keeps them in step with the TypeScript tests).
 */

/** What a file is, from its extension: drives the icon, the filter and thumbnails. */
enum class FileKind { Folder, Image, Video, Audio, Document, Spreadsheet, Pdf, Archive, Code, Text, Other }

private val KINDS: Map<FileKind, List<String>> = mapOf(
    FileKind.Image to listOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "tif", "tiff", "svg", "raw", "dng", "avif"),
    FileKind.Video to listOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "wmv", "ts", "m2ts", "mpg", "mpeg", "3gp"),
    FileKind.Audio to listOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "alac", "aiff"),
    FileKind.Pdf to listOf("pdf"),
    FileKind.Document to listOf("doc", "docx", "odt", "rtf", "pages", "epub", "ppt", "pptx", "odp", "key"),
    FileKind.Spreadsheet to listOf("xls", "xlsx", "ods", "csv", "numbers"),
    FileKind.Archive to listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso", "dmg", "apk"),
    FileKind.Code to listOf(
        "js", "ts", "tsx", "jsx", "json", "py", "kt", "java", "sh", "c", "cpp", "h", "rs", "go", "html", "css", "yml",
        "yaml", "toml", "xml", "sql",
    ),
    FileKind.Text to listOf("txt", "md", "log", "srt", "ini", "conf", "nfo", "bashrc"),
)

private val BY_EXTENSION: Map<String, FileKind> = KINDS.flatMap { (kind, exts) -> exts.map { it to kind } }.toMap()

fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot >= 0 && dot < name.length - 1) name.substring(dot + 1).lowercase(Locale.ROOT) else ""
}

fun fileKind(name: String, isDirectory: Boolean): FileKind =
    if (isDirectory) FileKind.Folder else BY_EXTENSION[extensionOf(name)] ?: FileKind.Other

fun fileKind(entry: SmbEntry) = fileKind(entry.name, entry.isDirectory)

/** Types the native Thumbnailer handles (keep in step with `Thumbnailer.kt`). */
val THUMBNAIL_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "mp4", "m4v", "mov", "3gp", "mkv", "webm")

fun hasThumbnail(entry: SmbEntry) = !entry.isDirectory && extensionOf(entry.name) in THUMBNAIL_EXTENSIONS

/** Images the built-in viewer decodes (Android's own decoders). */
val VIEWER_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

// --- Opening -------------------------------------------------------------------------------------------------------

/** Files from this size on are streamed rather than downloaded to the cache before opening. */
const val STREAM_FROM_BYTES = 32L * 1024 * 1024

/**
 * How a tap opens a file (src/lib/files.ts `openStrategy`), plus the native app's own viewers:
 *  - [Image]: the built-in zoomable viewer;
 *  - [Text]: the built-in text viewer (up to its size limit);
 *  - [Web]: `.html` / `.htm`, served with its folder by the local web server;
 *  - [Stream]: video, audio and anything from [STREAM_FROM_BYTES], read on demand by the other app;
 *  - [Cache]: small files, downloaded to the cache then opened.
 * With the chooser ("Open with…") a file always goes to another app.
 */
enum class OpenStrategy { Image, Text, Web, Stream, Cache }

fun openStrategy(entry: SmbEntry, withChooser: Boolean = false): OpenStrategy {
    val ext = extensionOf(entry.name)
    if (!withChooser) {
        if (ext in VIEWER_IMAGE_EXTENSIONS) return OpenStrategy.Image
        if (opensInViewer(entry)) return OpenStrategy.Text
        if (ext == "html" || ext == "htm") return OpenStrategy.Web
    }
    val kind = fileKind(entry)
    if (kind == FileKind.Video || kind == FileKind.Audio) return OpenStrategy.Stream
    return if (entry.size >= STREAM_FROM_BYTES) OpenStrategy.Stream else OpenStrategy.Cache
}

/** Text files the built-in viewer opens (src/lib/text/textFiles.ts, the same lists). */
private val TEXT_EXTENSIONS = setOf(
    "txt", "text", "md", "markdown", "mdown", "mkd", "log", "out", "nfo", "srt", "vtt",
    "json", "jsonc", "json5", "ndjson", "geojson", "yaml", "yml", "toml", "xml", "plist",
    "csv", "tsv", "ini", "cfg", "conf", "config", "properties", "env", "editorconfig",
    "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "py", "rb", "pl", "php", "lua", "r",
    "js", "mjs", "cjs", "jsx", "ts", "tsx", "mts", "cts", "vue", "svelte", "astro",
    "kt", "kts", "java", "gradle", "groovy", "scala", "swift", "go", "rs", "c", "h", "cpp",
    "hpp", "cc", "cs", "m", "mm", "dart", "zig", "nim", "ex", "exs", "erl", "hs", "clj",
    "html", "htm", "css", "scss", "sass", "less", "svg", "sql", "graphql", "gql", "proto",
    "tex", "bib", "rst", "adoc", "org", "diff", "patch", "gitignore", "gitattributes",
    "dockerfile", "makefile", "cmake", "nix", "tf", "hcl", "lock", "service", "rules",
    "bashrc", "zshrc", "profile", "vimrc", "npmrc", "nvmrc", "htaccess", "m3u", "m3u8", "cue",
)

private val TEXT_NAMES = setOf(
    "readme", "license", "licence", "copying", "authors", "changelog", "notice", "todo",
    "makefile", "dockerfile", "containerfile", "vagrantfile", "gemfile", "procfile",
    "hosts", "fstab", "crontab", "passwd", "group", "exports", "smb.conf",
)

/** Opened by a tap elsewhere (the web viewer, an image app), "Open in viewer" from the menu. */
private val TAP_ELSEWHERE = setOf("html", "htm", "svg")

/** The text viewer's limit (contract `TEXT_FILE_MAX_BYTES`). */
const val TEXT_VIEWER_MAX_BYTES = 5L * 1024 * 1024

fun isTextFile(name: String): Boolean {
    val ext = extensionOf(name)
    if (ext.isNotEmpty() && ext in TEXT_EXTENSIONS) return true
    val lower = name.lowercase(Locale.ROOT)
    return lower in TEXT_NAMES || lower.removePrefix(".") in TEXT_NAMES
}

fun isMarkdown(name: String) = extensionOf(name) in setOf("md", "markdown", "mdown", "mkd")

fun opensInViewer(entry: SmbEntry) =
    !entry.isDirectory && isTextFile(entry.name) && extensionOf(entry.name) !in TAP_ELSEWHERE && entry.size <= TEXT_VIEWER_MAX_BYTES

// --- Filter, search, sort ------------------------------------------------------------------------------------------

enum class FileFilter(val id: String) {
    All("all"), Folders("folders"), Images("images"), Videos("videos"), Audio("audio"), Documents("documents"), Archives("archives");

    val kinds: Set<FileKind>?
        get() = when (this) {
            All -> null
            Folders -> setOf(FileKind.Folder)
            Images -> setOf(FileKind.Image)
            Videos -> setOf(FileKind.Video)
            Audio -> setOf(FileKind.Audio)
            Documents -> setOf(FileKind.Document, FileKind.Pdf, FileKind.Spreadsheet, FileKind.Text)
            Archives -> setOf(FileKind.Archive)
        }
}

enum class SortKey(val id: String) { Name("name"), Size("size"), Modified("modified") }

data class SortOrder(val key: SortKey = SortKey.Name, val ascending: Boolean = true) {
    /** The settings JSON Hoard 1 stores (`{"key":"name","direction":"asc"}`). */
    fun toJson(): String = """{"key":"${key.id}","direction":"${if (ascending) "asc" else "desc"}"}"""

    companion object {
        val DEFAULT = SortOrder()
    }
}

/** Case-insensitive, accent-insensitive "contains" (search inside the folder). */
fun normalizeForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

data class ViewOptions(
    val filter: FileFilter = FileFilter.All,
    val query: String = "",
    val showHidden: Boolean = false,
    val sort: SortOrder = SortOrder.DEFAULT,
    /** Folders before files whatever the sort. */
    val foldersFirst: Boolean = true,
)

fun modifiedMillis(entry: SmbEntry): Long =
    entry.modifiedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

/** Filter, search and sort a folder listing (what the browser shows). */
fun viewEntries(entries: List<SmbEntry>, options: ViewOptions, locale: Locale = Locale.getDefault()): List<SmbEntry> {
    val kinds = options.filter.kinds
    val query = normalizeForSearch(options.query.trim())
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    val names = NaturalComparator(collator)
    val direction = if (options.sort.ascending) 1 else -1
    return entries
        .asSequence()
        .filter { options.showHidden || !it.isHidden }
        .filter { kinds == null || fileKind(it) in kinds }
        .filter { query.isEmpty() || normalizeForSearch(it.name).contains(query) }
        .sortedWith { a, b ->
            if (options.foldersFirst && a.isDirectory != b.isDirectory) {
                if (a.isDirectory) -1 else 1
            } else {
                val order = when (options.sort.key) {
                    SortKey.Size -> a.size.compareTo(b.size)
                    SortKey.Modified -> modifiedMillis(a).compareTo(modifiedMillis(b))
                    SortKey.Name -> 0
                }
                direction * (if (order != 0) order else names.compare(a.name, b.name))
            }
        }
        .toList()
}

/** Natural order with the locale's collation (`DSC2` before `DSC10`), like `Intl.Collator({ numeric: true })`. */
class NaturalComparator(private val collator: Collator) : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val x = chunk.findAll(a).map { it.value }.toList()
        val y = chunk.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val order = if (p[0].isDigit() && q[0].isDigit()) {
                val n = p.trimStart('0').length.compareTo(q.trimStart('0').length)
                if (n != 0) n else p.trimStart('0').compareTo(q.trimStart('0'))
            } else {
                collator.compare(p, q)
            }
            if (order != 0) return order
        }
        return x.size.compareTo(y.size)
    }
}

// --- Paths ---------------------------------------------------------------------------------------------------------

fun splitPath(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

fun joinPath(folder: String, name: String): String = (splitPath(folder) + name).filter { it.isNotEmpty() }.joinToString("/")

/** `a/b/c` → `a/b`; `a` → `""`; `""` → null (already at the root). */
fun parentPath(path: String): String? {
    val segments = splitPath(path)
    if (segments.isEmpty()) return null
    return segments.dropLast(1).joinToString("/")
}

/** A UNC-style path for "Copy path": `\\host\share\folder\file`. */
fun uncPath(host: String, share: String, path: String): String = "\\\\$host\\" + (listOf(share) + splitPath(path)).joinToString("\\")

/** A file or folder name the server accepts (no separators, not empty, not `.` / `..`). */
fun isValidName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() && trimmed.length <= 255 && trimmed != "." && trimmed != ".." &&
        !Regex("[\\\\/:*?\"<>|]").containsMatchIn(trimmed)
}

/** A share name typed in "Open share by name". */
fun isValidShareName(value: String): Boolean {
    val name = value.trim()
    return name.isNotEmpty() && name.length <= 80 && name.none { it == '\\' || it == '/' || it.code < 32 }
}

/** Share names compare case-insensitively (SMB servers treat them so). */
fun sameShare(a: String?, b: String?) = (a ?: "").equals(b ?: "", ignoreCase = true)

// --- Photos (culling's pairing rules, used by "Edit in Reveal" and the cull hint) ---------------------------------

private val RAW_EXTENSIONS = setOf("arw", "srf", "sr2", "cr2", "cr3", "nef", "nrw", "raf", "dng", "orf", "rw2", "pef", "srw")

enum class PhotoKind(val rank: Int) { Raw(0), Jpeg(1), Heif(2), Png(3), Webp(3) }

fun photoExtension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1).lowercase(Locale.ROOT)
}

fun photoKind(name: String): PhotoKind? {
    if (name.startsWith(".")) return null
    return when (photoExtension(name)) {
        "jpg", "jpeg", "jpe", "jfif" -> PhotoKind.Jpeg
        "heic", "heif", "hif", "avif" -> PhotoKind.Heif
        "png" -> PhotoKind.Png
        "webp" -> PhotoKind.Webp
        in RAW_EXTENSIONS -> PhotoKind.Raw
        else -> null
    }
}

fun photoStem(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) name else name.substring(0, dot)
}

/** At least this many photos make the browser offer culling (phase 2c brings the culling screen). */
const val CULL_MIN_PHOTOS = 2

fun countPhotos(entries: List<SmbEntry>) = entries.count { !it.isDirectory && photoKind(it.name) != null }

fun hasPhotos(entries: List<SmbEntry>) = entries.any { !it.isDirectory && photoKind(it.name) != null }

private fun parentOfPath(path: String) = path.substringBeforeLast('/', "")
private fun nameOfPath(path: String) = path.substringAfterLast('/')
private fun shotKey(path: String) = parentOfPath(path) + "/" + photoStem(nameOfPath(path)).lowercase(Locale.ROOT)

/** Photos only, duplicates dropped, the files of one shot together, RAW first (handoff.ts `revealOrder`). */
fun revealOrder(paths: List<String>): List<String> {
    val groups = LinkedHashMap<String, MutableList<String>>()
    val seen = HashSet<String>()
    for (path in paths) {
        if (photoKind(nameOfPath(path)) == null || !seen.add(path)) continue
        groups.getOrPut(shotKey(path)) { ArrayList() }.add(path)
    }
    return groups.values.flatMap { files ->
        files.withIndex().sortedWith(compareBy({ photoKind(nameOfPath(it.value))!!.rank }, { it.index })).map { it.value }
    }
}

/** A selection as a Reveal hand-off: each selected photo with every photo of the folder with the same stem. */
fun revealSelection(selected: List<SmbEntry>, folder: List<SmbEntry>): List<String> {
    val byKey = HashMap<String, MutableList<String>>()
    for (entry in folder) {
        if (entry.isDirectory || photoKind(entry.name) == null) continue
        byKey.getOrPut(shotKey(entry.path)) { ArrayList() }.add(entry.path)
    }
    val paths = selected.filter { !it.isDirectory && photoKind(it.name) != null }
        .flatMap { listOf(it.path) + (byKey[shotKey(it.path)] ?: emptyList()) }
    return revealOrder(paths)
}

// --- Formatting ----------------------------------------------------------------------------------------------------

private val UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

/** 1024-based sizes with the user's number format: `0 B`, `512 B`, `1,2 MB` (src/lib/format.ts). */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < 0) return "—"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < UNITS.size - 1) {
        value /= 1024
        unit++
    }
    val digits = if (unit == 0 || value >= 100) 0 else 1
    val format = java.text.NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = digits
        minimumFractionDigits = 0
    }
    return "${format.format(value)} ${UNITS[unit]}"
}

/** Compact duration, the same in both languages: `45 s`, `3 min`, `1 h 4 min`. */
fun formatDuration(totalSeconds: Double): String {
    if (totalSeconds.isNaN() || totalSeconds < 0) return "—"
    val seconds = kotlin.math.ceil(totalSeconds).toLong()
    if (seconds < 60) return "$seconds s"
    val minutes = Math.round(seconds / 60.0)
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val rest = minutes % 60
    if (hours >= 48) return "${Math.round(hours / 24.0)} d"
    return if (rest > 0) "$hours h $rest min" else "$hours h"
}
