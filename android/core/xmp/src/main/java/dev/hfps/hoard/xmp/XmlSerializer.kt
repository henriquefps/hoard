package dev.hfps.hoard.xmp

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * A small, deterministic DOM serializer for sidecars (the platform `Transformer` differs between the JVM and
 * Android in namespace fix-up and escaping). Writes the XML declaration, then the document's nodes as they are:
 * elements with their attributes (namespace declarations first), text, CDATA, comments and processing
 * instructions (`<?xpacket ...?>` survives).
 *
 * Ported from Reveal (`core/data/.../xmp/XmlSerializer.kt`, github.com/henriquefps/reveal, same owner and
 * licence, AGPL-3.0-or-later) so both apps write sidecars byte for byte the same way (ADR 0011).
 */
internal object XmlSerializer {
    fun serialize(document: Document): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        val nodes = document.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.DOCUMENT_TYPE_NODE) continue
            write(node, this)
            append('\n')
        }
    }

    private fun write(node: Node, out: StringBuilder) {
        when (node.nodeType) {
            Node.ELEMENT_NODE -> element(node as Element, out)

            Node.TEXT_NODE -> out.append(escapeText(node.nodeValue))

            Node.CDATA_SECTION_NODE -> out.append("<![CDATA[").append(node.nodeValue).append("]]>")

            Node.COMMENT_NODE -> out.append("<!--").append(node.nodeValue).append("-->")

            Node.PROCESSING_INSTRUCTION_NODE -> out.append("<?").append(node.nodeName).append(' ')
                .append(node.nodeValue).append("?>")

            Node.ENTITY_REFERENCE_NODE -> out.append('&').append(node.nodeName).append(';')

            else -> Unit
        }
    }

    private fun element(element: Element, out: StringBuilder) {
        out.append('<').append(element.tagName)
        for (attr in XmpDom.attributes(element)) {
            out.append(' ').append(attr.name).append("=\"").append(escapeAttribute(attr.value)).append('"')
        }
        val children = element.childNodes
        if (children.length == 0) {
            out.append("/>")
            return
        }
        out.append('>')
        for (i in 0 until children.length) write(children.item(i), out)
        out.append("</").append(element.tagName).append('>')
    }

    /** Text content: `&`, `<`, `>` escaped, characters XML 1.0 forbids dropped, `\r` kept as a reference. */
    fun escapeText(value: String): String = buildString(value.length) {
        for (c in validXml(value)) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\r' -> append("&#xD;")
                else -> append(c)
            }
        }
    }

    /**
     * A double-quoted attribute value (same rules Hoard used before 1.9.0): `& < > " '` escaped, tab, newline
     * and carriage return as character references (so they survive attribute-value normalisation), and the
     * characters XML 1.0 forbids dropped.
     */
    fun escapeAttribute(value: String): String = buildString(value.length) {
        for (c in validXml(value)) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                '\t' -> append("&#x9;")
                '\n' -> append("&#xA;")
                '\r' -> append("&#xD;")
                else -> append(c)
            }
        }
    }

    /** Removes control characters other than tab/newline/CR, lone surrogates, U+FFFE and U+FFFF. */
    fun validXml(value: String): String = buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                    append(c).append(value[i + 1])
                    i++
                }

                c.isSurrogate() -> Unit

                c == '\t' || c == '\n' || c == '\r' -> append(c)

                c < ' ' || c == '￾' || c == '￿' -> Unit

                else -> append(c)
            }
            i++
        }
    }
}
