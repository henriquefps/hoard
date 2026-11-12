package dev.hfps.hoard.image.format

/** Broad family of an image file. */
enum class ImageKind { JPEG, HEIF, PNG, WEBP, RAW, OTHER }

/** Camera RAW container formats the core knows. */
enum class RawFormat(val extensions: Set<String>) {
    ARW(setOf("arw", "srf", "sr2")),
    CR2(setOf("cr2")),
    CR3(setOf("cr3")),
    NEF(setOf("nef", "nrw")),
    RAF(setOf("raf")),
    DNG(setOf("dng")),
    ORF(setOf("orf")),
    RW2(setOf("rw2")),
    PEF(setOf("pef")),
    SRW(setOf("srw")),
}

/** What a file is, from its name and (when known) its first bytes. */
data class ImageType(val kind: ImageKind, val raw: RawFormat? = null) {
    val isRaw: Boolean get() = kind == ImageKind.RAW
    val isDisplayable: Boolean get() = kind != ImageKind.OTHER

    companion object {
        val OTHER = ImageType(ImageKind.OTHER)
    }
}

/** Container signature found in the first bytes of a file. */
enum class Magic { JPEG, PNG, WEBP, HEIF, TIFF_LE, TIFF_BE, ORF, RW2, RAF, CR3, UNKNOWN }

object ImageFormats {

    /** Bytes [detectMagic] needs. */
    const val MAGIC_BYTES = 16

    private val byExtension: Map<String, ImageType> = buildMap {
        listOf("jpg", "jpeg", "jpe", "jfif").forEach { put(it, ImageType(ImageKind.JPEG)) }
        listOf("heic", "heif", "hif", "avif").forEach { put(it, ImageType(ImageKind.HEIF)) }
        put("png", ImageType(ImageKind.PNG))
        put("webp", ImageType(ImageKind.WEBP))
        RawFormat.entries.forEach { format -> format.extensions.forEach { put(it, ImageType(ImageKind.RAW, format)) } }
    }

    fun extension(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        return if (dot <= 0 || dot == base.length - 1) "" else base.substring(dot + 1).lowercase()
    }

    /** File name without folder and extension ("DSC07668.ARW" -> "DSC07668"). */
    fun stem(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        return if (dot <= 0) base else base.substring(0, dot)
    }

    /**
     * Hidden file: its name (without folders) starts with a dot. macOS AppleDouble copies (`._DSC1.ARW`),
     * `.DS_Store` and Hoard's own hidden sidecars (`.DSC1.xmp`) are never photos of a folder.
     */
    fun isHidden(name: String): Boolean = name.substringAfterLast('/').substringAfterLast('\\').startsWith(".")

    /** A photo of a folder to cull or hand off: a displayable image that is not hidden. */
    fun isPhoto(name: String): Boolean = !isHidden(name) && classify(name).isDisplayable

    /** By extension only (listing a folder: no reads). */
    fun classify(name: String): ImageType = byExtension[extension(name)] ?: ImageType.OTHER

    /**
     * By extension and magic bytes. The magic wins when the two disagree in kind (a JPEG named
     * `.png`); a RAW extension is trusted when its container matches (most RAWs are TIFF inside), and
     * a TIFF-based file with an unknown extension is not called RAW (it may be a plain TIFF).
     */
    fun classify(name: String, head: ByteArray): ImageType {
        val byName = classify(name)
        return when (detectMagic(head)) {
            Magic.JPEG -> ImageType(ImageKind.JPEG)
            Magic.PNG -> ImageType(ImageKind.PNG)
            Magic.WEBP -> ImageType(ImageKind.WEBP)
            Magic.HEIF -> ImageType(ImageKind.HEIF)
            Magic.CR3 -> ImageType(ImageKind.RAW, RawFormat.CR3)
            Magic.RAF -> ImageType(ImageKind.RAW, RawFormat.RAF)
            Magic.ORF -> ImageType(ImageKind.RAW, RawFormat.ORF)
            Magic.RW2 -> ImageType(ImageKind.RAW, RawFormat.RW2)
            Magic.TIFF_LE, Magic.TIFF_BE -> when {
                byName.isRaw && byName.raw !in setOf(RawFormat.CR3, RawFormat.RAF) -> byName
                isCr2(head) -> ImageType(ImageKind.RAW, RawFormat.CR2)
                else -> ImageType.OTHER
            }
            Magic.UNKNOWN -> if (head.size < 4) byName else ImageType.OTHER
        }
    }

    fun detectMagic(head: ByteArray): Magic {
        fun at(i: Int, vararg bytes: Int) = head.size >= i + bytes.size && bytes.indices.all { head[i + it].toInt() and 0xFF == bytes[it] }
        fun ascii(i: Int, s: String) = at(i, *s.map { it.code }.toIntArray())
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> Magic.JPEG
            at(0, 0x89, 0x50, 0x4E, 0x47) -> Magic.PNG
            ascii(0, "RIFF") && ascii(8, "WEBP") -> Magic.WEBP
            ascii(0, "FUJIFILMCCD-RAW") -> Magic.RAF
            ascii(4, "ftyp") && ascii(8, "crx ") -> Magic.CR3
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "msf1") || ascii(8, "avif") || ascii(8, "hevc")) -> Magic.HEIF
            at(0, 0x49, 0x49, 0x52, 0x4F) || at(0, 0x49, 0x49, 0x52, 0x53) || at(0, 0x4D, 0x4D, 0x4F, 0x52) -> Magic.ORF
            at(0, 0x49, 0x49, 0x55, 0x00) -> Magic.RW2
            at(0, 0x49, 0x49, 0x2A, 0x00) -> Magic.TIFF_LE
            at(0, 0x4D, 0x4D, 0x00, 0x2A) -> Magic.TIFF_BE
            else -> Magic.UNKNOWN
        }
    }

    /** CR2: TIFF header followed by "CR" at offset 8. */
    private fun isCr2(head: ByteArray) = head.size >= 10 && head[8] == 'C'.code.toByte() && head[9] == 'R'.code.toByte()
}
