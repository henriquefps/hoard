package dev.hfps.hoard.xmp

import java.util.Locale

/**
 * The names of a shot's XMP sidecar (ADR 0011 "Naming"), the same rules as Reveal's `SidecarNames`:
 *
 * - **Reading**, in order of preference: `<stem>.xmp` (Lightroom, Reveal, Hoard), `<file name>.xmp` of each of the
 *   shot's files (darktable: `DSC1.ARW.xmp`), then the same names hidden (`.<stem>.xmp`, `.<file name>.xmp`). A
 *   normal name always wins over a hidden one. Names compare case-insensitively (SMB, FAT and Android's shared
 *   storage are case-insensitive).
 * - **Writing**: the sidecar the reader picked, else `<stem>.xmp`, or `.<stem>.xmp` with the hidden option. A second
 *   sidecar is never created for a shot that has one.
 */
object SidecarNames {
    const val SUFFIX = ".xmp"

    /** Lower-case candidate names for a shot of [stem] whose files are [fileNames] (main file first). */
    fun candidates(stem: String, fileNames: List<String>): List<String> {
        val normal = buildList {
            add(lower("$stem$SUFFIX"))
            fileNames.forEach { add(lower("$it$SUFFIX")) }
        }.distinct()
        return normal + normal.map { ".$it" }
    }

    /** A sidecar name: ends in `.xmp`, and is not a macOS AppleDouble file (`._name.xmp`). */
    fun isSidecar(name: String): Boolean = name.endsWith(SUFFIX, ignoreCase = true) && !name.startsWith("._")

    /**
     * The existing sidecar of a shot among a folder's [names] (as listed, any case), or null. [names] may hold
     * anything; only sidecar names are considered.
     */
    fun find(stem: String, fileNames: List<String>, names: Collection<String>): String? {
        val byLower = HashMap<String, String>()
        for (name in names) if (isSidecar(name)) byLower.putIfAbsent(lower(name), name)
        return find(stem, fileNames) { byLower[it] }
    }

    /** Like [find], with a lookup from a lower-case name to the existing name (or null). */
    fun find(stem: String, fileNames: List<String>, lookup: (String) -> String?): String? =
        candidates(stem, fileNames).firstNotNullOfOrNull(lookup)

    /** The name to write: [existing] when the shot has a sidecar, else `<stem>.xmp` (`.<stem>.xmp` when [hidden]). */
    fun target(stem: String, existing: String?, hidden: Boolean): String =
        existing ?: if (hidden) ".$stem$SUFFIX" else "$stem$SUFFIX"

    private fun lower(name: String) = name.lowercase(Locale.ROOT)
}
