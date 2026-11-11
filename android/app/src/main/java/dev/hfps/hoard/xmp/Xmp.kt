package dev.hfps.hoard.xmp

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/*
 * XMP sidecars of culling decisions (ADR 0011). Read, merge and write logic ported from Reveal's
 * `core/data/src/main/kotlin/dev/hfps/reveal/data/xmp/Xmp.kt` (github.com/henriquefps/reveal; same owner and
 * licence, AGPL-3.0-or-later), so a sidecar Hoard writes is one Reveal reads the same way and vice versa. Hoard's
 * differences: a missing label means "leave the sidecar's label alone" (Hoard has no colour labels), and the
 * reader says whether the sidecar holds any culling value at all ([XmpRead.hasValues]).
 */

/** Pick / reject / none, as `digiKam:PickLabel` 3 / 1 / 0. */
enum class XmpFlag(val wire: String) {
    NONE("none"),
    PICK("pick"),
    REJECT("reject"),
    ;

    companion object {
        /** `pick` / `reject` / `none` (any case); null for anything else. */
        fun parse(value: String?): XmpFlag? = entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * The culling fields that travel through XMP:
 *
 * - [rating] 0..5: `xmp:Rating`, the Adobe property every editor reads.
 * - [flag] REJECT: `xmp:Rating="-1"` (Lightroom, darktable, Reveal and `PhotoMetadata.rating` agree on -1). A reject
 *   keeps its stars in Hoard's database only: XMP has one `xmp:Rating` and -1 wins.
 * - [flag] PICK / REJECT / NONE: `digiKam:PickLabel` 3 / 1 / 0.
 * - [flag] PICK, read only: a sidecar whose `xmp:CreatorTool` starts with `Hoard` and has no `digiKam:PickLabel`
 *   (sidecars of "Copy picks" before 1.9.0).
 * - [label]: `xmp:Label`, free text. Null when writing = leave the sidecar's label as it is.
 */
data class XmpFields(val rating: Int = 0, val flag: XmpFlag = XmpFlag.NONE, val label: String? = null) {
    init {
        require(rating in 0..MAX_RATING) { "rating $rating outside 0..$MAX_RATING" }
    }

    companion object {
        const val MAX_RATING = 5
    }
}

/** What a sidecar says: its fields, and whether it holds any culling value (rating, pick label or a Hoard pick). */
data class XmpRead(val fields: XmpFields, val hasValues: Boolean)

/** A sidecar or packet that is not well-formed XML, or not XMP: never overwritten. */
class XmpException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Namespaces read or written. */
object XmpNs {
    const val X = "adobe:ns:meta/"
    const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    const val XMP = "http://ns.adobe.com/xap/1.0/"
    const val DIGIKAM = "http://www.digikam.org/ns/1.0/"
    const val XMLNS = "http://www.w3.org/2000/xmlns/"

    /** Reveal's namespace (its recipe): left untouched like every other namespace. */
    const val REVEAL = "https://reveal.hfps.dev/ns/1.0/"

    /** Preferred prefixes when a namespace has to be declared. */
    val prefixes = mapOf(XMP to "xmp", DIGIKAM to "digiKam", REVEAL to "reveal")
}

/** Reads [XmpFields] from a sidecar. */
object XmpReader {
    private const val PICK_LABEL_REJECT = 1
    private const val PICK_LABEL_ACCEPT = 3

    /** Parses [bytes]; throws [XmpException] when they are not well-formed XML. */
    fun read(bytes: ByteArray): XmpRead = read(XmpDom.parse(bytes))

    /** Like [read], or null for anything unreadable. */
    fun readOrNull(bytes: ByteArray): XmpRead? = runCatching { read(bytes) }.getOrNull()

    fun read(document: Document): XmpRead {
        var rating: Double? = null
        var label: String? = null
        var pickLabel: Int? = null
        var creatorTool: String? = null
        for (description in XmpDom.descriptions(document)) {
            rating = rating ?: XmpDom.property(description, XmpNs.XMP, "Rating")?.trim()?.toDoubleOrNull()
            label = label ?: XmpDom.property(description, XmpNs.XMP, "Label")
            pickLabel = pickLabel ?: XmpDom.property(description, XmpNs.DIGIKAM, "PickLabel")?.trim()?.toIntOrNull()
            creatorTool = creatorTool ?: XmpDom.property(description, XmpNs.XMP, "CreatorTool")
        }
        val stars = rating?.toInt() ?: 0
        val hoard = creatorTool?.trim()?.startsWith("Hoard") == true
        val flag = when {
            stars < 0 -> XmpFlag.REJECT
            pickLabel == PICK_LABEL_REJECT -> XmpFlag.REJECT
            pickLabel == PICK_LABEL_ACCEPT -> XmpFlag.PICK
            pickLabel != null -> XmpFlag.NONE
            hoard -> XmpFlag.PICK
            else -> XmpFlag.NONE
        }
        val fields = XmpFields(
            rating = stars.coerceIn(0, XmpFields.MAX_RATING),
            flag = flag,
            label = label?.takeIf { it.isNotBlank() },
        )
        return XmpRead(fields, hasValues = rating != null || pickLabel != null || hoard)
    }
}

/**
 * Writes [XmpFields] into a sidecar. An existing sidecar is **merged**, not replaced: only `xmp:Rating`,
 * `digiKam:PickLabel`, `xmp:MetadataDate` (and `xmp:Label` when one is given) change; Lightroom's `crs:` develop
 * settings, darktable's history, Reveal's `reveal:` recipe and anything else stay as they were (same element order,
 * comments and processing instructions). A property found as a child element is removed and written as an
 * attribute. A sidecar that does not parse is refused ([XmpException]): the caller keeps the decision in its
 * database only.
 */
object XmpWriter {
    private const val PICK_LABEL_NONE = "0"
    private const val PICK_LABEL_REJECT = "1"
    private const val PICK_LABEL_ACCEPT = "3"

    /**
     * The new sidecar bytes (UTF-8, no BOM). [existing] = the current sidecar, or null (or blank) to create one;
     * [creatorTool] (`Hoard 1.9.0`) is written only into a new sidecar; [date] is ISO 8601 (`xmp:MetadataDate`).
     */
    fun update(existing: ByteArray?, fields: XmpFields, creatorTool: String, date: String): ByteArray {
        val document = if (existing == null || existing.all { it.toInt().toChar().isWhitespace() }) {
            XmpDom.parse(newSidecar(creatorTool).toByteArray(Charsets.UTF_8))
        } else {
            XmpDom.parse(existing)
        }
        val descriptions = XmpDom.descriptions(document)
        if (descriptions.isEmpty()) throw XmpException("No rdf:Description in the sidecar")
        // The culling fields live in one description: the one that already has them, else the first.
        val target = descriptions.firstOrNull { d ->
            XmpDom.property(d, XmpNs.XMP, "Rating") != null || XmpDom.property(d, XmpNs.XMP, "Label") != null
        } ?: descriptions.first()
        val label = fields.label
        for (d in descriptions) {
            XmpDom.removeProperty(d, XmpNs.XMP, "Rating")
            if (label != null) XmpDom.removeProperty(d, XmpNs.XMP, "Label")
            XmpDom.removeProperty(d, XmpNs.DIGIKAM, "PickLabel")
            XmpDom.removeProperty(d, XmpNs.XMP, "MetadataDate")
        }
        val rating = if (fields.flag == XmpFlag.REJECT) -1 else fields.rating
        XmpDom.setAttribute(target, XmpNs.XMP, "Rating", rating.toString())
        label?.takeIf { it.isNotBlank() }?.let { XmpDom.setAttribute(target, XmpNs.XMP, "Label", it) }
        val pick = when (fields.flag) {
            XmpFlag.NONE -> PICK_LABEL_NONE
            XmpFlag.PICK -> PICK_LABEL_ACCEPT
            XmpFlag.REJECT -> PICK_LABEL_REJECT
        }
        XmpDom.setAttribute(target, XmpNs.DIGIKAM, "PickLabel", pick)
        XmpDom.setAttribute(target, XmpNs.XMP, "MetadataDate", date)
        return XmlSerializer.serialize(document).toByteArray(Charsets.UTF_8)
    }

    private fun newSidecar(creatorTool: String): String {
        val tool = XmlSerializer.escapeAttribute(creatorTool)
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="$tool">
            | <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            |  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmp:CreatorTool="$tool"/>
            | </rdf:RDF>
            |</x:xmpmeta>
            |
        """.trimMargin()
    }
}

/** DOM helpers for XMP: parsing without DTDs or external entities, and RDF properties as attribute or element. */
internal object XmpDom {
    fun parse(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            isIgnoringComments = false
            // XXE hardening; not every platform parser knows every feature, so each one is best effort.
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = try {
            factory.newDocumentBuilder().apply {
                // The default handler prints parse errors to stderr; they are reported as XmpException instead.
                setErrorHandler(null)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw XmpException("No XML parser: ${e.message}", e)
        }
        val document = try {
            builder.parse(ByteArrayInputStream(stripBom(bytes)))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw XmpException("Not well-formed XMP: ${e.message}", e)
        }
        // Well-formed but not XMP (an HTML page, a stray file named .xmp): not ours to touch.
        val root = document.documentElement
        val isXmp = root != null &&
            ((root.namespaceURI == XmpNs.X && root.localName == "xmpmeta") || (root.namespaceURI == XmpNs.RDF && root.localName == "RDF"))
        if (!isXmp) throw XmpException("Not an XMP document")
        return document
    }

    private fun stripBom(bytes: ByteArray): ByteArray {
        val bom = bytes.size >= BOM.size && BOM.indices.all { bytes[it] == BOM[it] }
        return if (bom) bytes.copyOfRange(BOM.size, bytes.size) else bytes
    }

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun descriptions(document: Document): List<Element> {
        val list = document.getElementsByTagNameNS(XmpNs.RDF, "Description")
        return (0 until list.length).map { list.item(it) as Element }
    }

    /** A simple property: `ns:name="v"` on the description, or `<ns:name>v</ns:name>` inside it. */
    fun property(description: Element, ns: String, name: String): String? {
        description.getAttributeNodeNS(ns, name)?.let { return it.value }
        return children(description).firstOrNull { it.namespaceURI == ns && it.localName == name }?.textContent
    }

    fun removeProperty(description: Element, ns: String, name: String) {
        description.getAttributeNodeNS(ns, name)?.let { description.removeAttributeNode(it) }
        children(description).filter { it.namespaceURI == ns && it.localName == name }.forEach { child ->
            // Drop the indentation before the element too, so repeated writes do not accumulate blank lines.
            val before = child.previousSibling
            if (before != null && before.nodeType == Node.TEXT_NODE && before.nodeValue.isBlank()) {
                description.removeChild(before)
            }
            description.removeChild(child)
        }
    }

    fun setAttribute(element: Element, ns: String, name: String, value: String) {
        val prefix = element.lookupPrefix(ns) ?: declare(element, ns)
        element.setAttributeNS(ns, "$prefix:$name", value)
    }

    private fun declare(element: Element, ns: String): String {
        val wanted = XmpNs.prefixes[ns] ?: "ns"
        var prefix = wanted
        var n = 1
        while (element.lookupNamespaceURI(prefix) != null) prefix = "$wanted${n++}"
        element.setAttributeNS(XmpNs.XMLNS, "xmlns:$prefix", ns)
        return prefix
    }

    private fun children(element: Element): List<Element> {
        val nodes = element.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    /** Attributes in document order, namespace declarations first (as XMP tools write them). */
    fun attributes(element: Element): List<Attr> {
        val map = element.attributes
        val all = (0 until map.length).map { map.item(it) as Attr }
        return all.filter { it.isNamespaceDeclaration() } + all.filterNot { it.isNamespaceDeclaration() }
    }

    private fun Attr.isNamespaceDeclaration() =
        namespaceURI == XmpNs.XMLNS || name == "xmlns" || name.startsWith("xmlns:")
}
