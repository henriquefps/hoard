package dev.hfps.hoard.image.format

/** A file as listed (no reads). */
data class ListedFile(val name: String, val size: Long = 0, val modifiedMillis: Long = 0)

/**
 * One shot as the culling view sees it: a RAW, a JPEG/HEIF (the camera's rendering), or both.
 * Extra renderings with the same stem (e.g. a HEIF next to a JPEG) go to [others].
 */
data class Shot(
    val stem: String,
    val raw: ListedFile?,
    val rendered: ListedFile?,
    val others: List<ListedFile> = emptyList(),
) {
    val isPair: Boolean get() = raw != null && rendered != null
    val isRawOnly: Boolean get() = raw != null && rendered == null
    val isRenderedOnly: Boolean get() = raw == null && rendered != null

    /** Every file of the shot (for a delete or move of the whole shot). */
    val files: List<ListedFile> get() = listOfNotNull(raw, rendered) + others
}

object ShotPairing {

    /**
     * Groups a folder listing into shots by case-insensitive stem (`DSC07668.ARW` + `dsc07668.jpg`).
     * Non-image and hidden files (`._X.ARW`, `.DS_Store`) are ignored. When a stem has several RAWs or renderings, the first by
     * preference (JPEG over HEIF over PNG/WebP; then name) wins and the rest go to [Shot.others].
     * Shots are returned sorted by stem (natural order); callers re-sort by capture time once EXIF is
     * known.
     */
    fun pair(files: List<ListedFile>): List<Shot> {
        val groups = LinkedHashMap<String, MutableList<Pair<ListedFile, ImageType>>>()
        for (file in files) {
            if (!ImageFormats.isPhoto(file.name)) continue
            val type = ImageFormats.classify(file.name)
            groups.getOrPut(ImageFormats.stem(file.name).lowercase()) { mutableListOf() }.add(file to type)
        }
        return groups.values.map { members ->
            val raws = members.filter { it.second.isRaw }.sortedBy { it.first.name }
            val rendered = members.filter { !it.second.isRaw }.sortedWith(compareBy({ renderedRank(it.second.kind) }, { it.first.name }))
            val raw = raws.firstOrNull()?.first
            val main = rendered.firstOrNull()?.first
            Shot(
                stem = ImageFormats.stem((raw ?: main)!!.name),
                raw = raw,
                rendered = main,
                others = raws.drop(1).map { it.first } + rendered.drop(1).map { it.first },
            )
        }.sortedWith { a, b -> NaturalOrder.compare(a.stem, b.stem) }
    }

    private fun renderedRank(kind: ImageKind) = when (kind) {
        ImageKind.JPEG -> 0
        ImageKind.HEIF -> 1
        else -> 2
    }
}

/** "DSC2" < "DSC10"; case-insensitive. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
