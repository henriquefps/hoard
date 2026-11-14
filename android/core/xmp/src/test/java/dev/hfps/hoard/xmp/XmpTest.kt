package dev.hfps.hoard.xmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XMP module (ADR 0011): Reveal's reading rules and merge behaviour, plus Hoard's own differences. */
class XmpTest {
    private val date = "2026-11-11T10:00:00.000Z"

    private fun write(existing: String?, fields: XmpFields, tool: String = "Hoard 1.9.0") =
        String(XmpWriter.update(existing?.toByteArray(), fields, tool, date), Charsets.UTF_8)

    private fun read(text: String) = XmpReader.read(text.toByteArray()).fields

    private fun sidecar(rating: Int, pickLabel: Int? = null, creatorTool: String) =
        RDF_OPEN + """<rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" """ +
            (if (pickLabel != null) """xmlns:digiKam="http://www.digikam.org/ns/1.0/" digiKam:PickLabel="$pickLabel" """ else "") +
            """xmp:Rating="$rating" xmp:CreatorTool="$creatorTool"/></rdf:RDF></x:xmpmeta>"""

    @Test
    fun `a new sidecar round-trips every field`() {
        for (flag in XmpFlag.entries) {
            for (rating in 0..5) {
                val fields = XmpFields(rating = if (flag == XmpFlag.REJECT) 0 else rating, flag = flag)
                assertEquals(fields, read(write(null, fields)))
            }
        }
    }

    @Test
    fun `a new sidecar is the standard Adobe shape with Hoard as creator`() {
        val text = write(null, XmpFields(rating = 4, flag = XmpFlag.PICK))
        assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<x:xmpmeta xmlns:x=\"adobe:ns:meta/\""))
        assertTrue(text.contains("xmp:Rating=\"4\""))
        assertTrue(text.contains("xmp:CreatorTool=\"Hoard 1.9.0\""))
        assertTrue(text.contains("xmp:MetadataDate=\"$date\""))
        assertTrue(text.contains("digiKam:PickLabel=\"3\""))
        assertTrue(text.contains("xmlns:digiKam=\"http://www.digikam.org/ns/1.0/\""))
        assertFalse(text.contains("xmp:Label"))
    }

    @Test
    fun `reject is rating minus one and the stars stay out of the file`() {
        val text = write(null, XmpFields(rating = 3, flag = XmpFlag.REJECT))
        assertTrue(text.contains("xmp:Rating=\"-1\""))
        assertTrue(text.contains("digiKam:PickLabel=\"1\""))
        assertEquals(XmpFields(rating = 0, flag = XmpFlag.REJECT), read(text))
    }

    @Test
    fun `unmarking writes zero and none, never deletes`() {
        val picked = write(null, XmpFields(rating = 5, flag = XmpFlag.PICK))
        val cleared = write(picked, XmpFields())
        assertTrue(cleared.contains("xmp:Rating=\"0\""))
        assertTrue(cleared.contains("digiKam:PickLabel=\"0\""))
        assertEquals(XmpRead(XmpFields(), hasValues = true), XmpReader.read(cleared.toByteArray()))
    }

    @Test
    fun `old Hoard copy-picks sidecars are picks, until a pick label says otherwise`() {
        val old = sidecar(rating = 3, creatorTool = "Hoard 1.7.2")
        assertEquals(XmpFields(rating = 3, flag = XmpFlag.PICK), read(old))
        val unpicked = write(old, XmpFields(rating = 3, flag = XmpFlag.NONE))
        assertTrue(unpicked.contains("xmp:CreatorTool=\"Hoard 1.7.2\""))
        assertEquals(XmpFlag.NONE, read(unpicked).flag)
        assertEquals(XmpFlag.NONE, read(sidecar(rating = 2, pickLabel = 2, creatorTool = "Hoard")).flag)
    }

    @Test
    fun `the creator tool is written only into a new sidecar`() {
        val updated = write(sidecar(2, creatorTool = "Reveal 0.2.0"), XmpFields(rating = 4))
        assertTrue(updated.contains("xmp:CreatorTool=\"Reveal 0.2.0\""))
        assertFalse(updated.contains("Hoard"))
    }

    @Test
    fun `lightroom sidecar keeps its develop settings and label when the rating changes`() {
        val updated = write(LIGHTROOM, XmpFields(rating = 2, flag = XmpFlag.PICK))
        assertEquals(XmpFields(rating = 4, label = "Red"), read(LIGHTROOM))
        assertEquals(XmpFields(rating = 2, flag = XmpFlag.PICK, label = "Red"), read(updated))
        assertTrue(updated.contains("crs:Exposure2012=\"+0.50\""))
        assertTrue(updated.contains("<rdf:li>255, 255</rdf:li>"))
        assertTrue(updated.contains("xmlns:crs=\"http://ns.adobe.com/camera-raw-settings/1.0/\""))
        assertTrue(updated.contains("x:xmptk=\"Adobe XMP Core 7.0-c000 1.000000, 0000/00/00-00:00:00        \""))
        assertTrue(updated.contains("xmp:Label=\"Red\""))
        assertFalse(updated.contains("xmp:Rating=\"4\""))
        // Written twice: the document stays the same (no accumulating whitespace or duplicates).
        assertEquals(updated, write(updated, XmpFields(rating = 2, flag = XmpFlag.PICK)))
    }

    @Test
    fun `darktable sidecar keeps its history and reads reject as minus one`() {
        assertEquals(XmpFields(rating = 0, flag = XmpFlag.REJECT), read(DARKTABLE))
        val updated = write(DARKTABLE, XmpFields(rating = 5))
        assertEquals(XmpFields(rating = 5), read(updated))
        assertTrue(updated.contains("darktable:operation=\"exposure\""))
        assertTrue(updated.contains("darktable:history_end=\"2\""))
        assertTrue(updated.contains("<?xpacket end=\"w\"?>"))
    }

    @Test
    fun `digiKam sidecar with elements keeps its tags`() {
        val updated = write(DIGIKAM, XmpFields(rating = 1, flag = XmpFlag.REJECT))
        assertEquals(XmpFields(rating = 3, flag = XmpFlag.PICK), read(DIGIKAM))
        assertEquals(XmpFields(rating = 0, flag = XmpFlag.REJECT), read(updated))
        assertTrue(updated.contains("<rdf:li>Holiday</rdf:li>"))
        assertTrue(updated.contains("digiKam:ColorLabel=\"2\""))
        assertFalse(updated.contains("<digiKam:PickLabel>"))
        assertFalse(updated.contains("<xmp:Rating>"))
        // The namespace already declared on the document is reused, not declared twice.
        assertEquals(1, Regex("xmlns:digiKam=").findAll(updated).count())
    }

    @Test
    fun `properties written as elements are read and replaced by attributes`() {
        val elements = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
             <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/">
              <xmp:Rating>3</xmp:Rating>
              <xmp:Label>Blue</xmp:Label>
             </rdf:Description></rdf:RDF></x:xmpmeta>
        """.trimIndent()
        assertEquals(XmpFields(rating = 3, label = "Blue"), read(elements))
        val updated = write(elements, XmpFields(rating = 1))
        assertFalse(updated.contains("<xmp:Rating>"))
        assertTrue(updated.contains("xmp:Rating=\"1\""))
        // Hoard has no colour labels: an existing label is left as it is.
        assertTrue(updated.contains("<xmp:Label>Blue</xmp:Label>"))
        assertEquals(XmpFields(rating = 1, label = "Blue"), read(updated))
    }

    @Test
    fun `a different prefix for the xmp namespace is reused`() {
        val xap = RDF_OPEN + """<rdf:Description rdf:about="" xmlns:xap="http://ns.adobe.com/xap/1.0/" xap:Rating="2"/></rdf:RDF></x:xmpmeta>"""
        val updated = write(xap, XmpFields(rating = 5))
        assertTrue(updated.contains("xap:Rating=\"5\""))
        assertFalse(updated.contains("xmlns:xmp="))
    }

    @Test
    fun `values are escaped and survive the round trip`() {
        val nasty = "A & B < C > \"D\" 'E'\ttab\nline\u0001ctl📷"
        val text = write(null, XmpFields(rating = 1), tool = nasty)
        assertTrue(text.contains("A &amp; B &lt; C &gt; &quot;D&quot; &apos;E&apos;&#x9;tab&#xA;line"))
        assertFalse(text.contains("\u0001"))
        val labelled = write(null, XmpFields(rating = 1, label = nasty))
        assertEquals("A & B < C > \"D\" 'E'\ttab\nlinectl📷", read(labelled).label)
    }

    @Test
    fun `the reveal namespace is left as it was`() {
        val withReveal = RDF_OPEN + """<rdf:Description rdf:about="" xmlns:reveal="${XmpNs.REVEAL}" reveal:recipe="{&quot;v&quot;:1}"/></rdf:RDF></x:xmpmeta>"""
        val updated = write(withReveal, XmpFields(rating = 2))
        assertTrue(updated.contains("reveal:recipe=\"{&quot;v&quot;:1}\""))
        assertEquals(2, read(updated).rating)
    }

    @Test
    fun `malformed or foreign sidecars are refused, never overwritten`() {
        assertThrows(XmpException::class.java) { XmpWriter.update("<x:xmpmeta".toByteArray(), XmpFields(), "H", date) }
        assertThrows(XmpException::class.java) { XmpWriter.update("<html><body/></html>".toByteArray(), XmpFields(), "H", date) }
        assertThrows(XmpException::class.java) { XmpWriter.update((RDF_OPEN + "</rdf:RDF></x:xmpmeta>").toByteArray(), XmpFields(), "H", date) }
        assertNull(XmpReader.readOrNull("not xml".toByteArray()))
        // A document type declaration (XXE vector) is not processed.
        val xxe = """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x>&e;</x>"""
        assertNull(XmpReader.readOrNull(xxe.toByteArray())?.fields?.label)
    }

    @Test
    fun `a sidecar without culling values says so`() {
        val developOnly = RDF_OPEN + """<rdf:Description rdf:about="" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" crs:Exposure2012="+1.0"/></rdf:RDF></x:xmpmeta>"""
        assertEquals(XmpRead(XmpFields(), hasValues = false), XmpReader.read(developOnly.toByteArray()))
        assertTrue(XmpReader.read(sidecar(0, creatorTool = "X").toByteArray()).hasValues)
    }

    @Test
    fun `out of range ratings are clamped, fractional ones truncated, a BOM accepted`() {
        assertEquals(5, read(sidecar(rating = 9, creatorTool = "Other").replace("\"9\"", "\"7.0\"")).rating)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sidecar(2, creatorTool = "X").toByteArray()
        assertEquals(2, XmpReader.read(bom).fields.rating)
    }

    private companion object {
        // LIGHTROOM and DARKTABLE: Reveal's test fixtures (core/data/.../xmp/XmpTest.kt), copied as they are.
        const val RDF_OPEN =
            """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">"""

        val LIGHTROOM = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 7.0-c000 1.000000, 0000/00/00-00:00:00        ">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                xmlns:tiff="http://ns.adobe.com/tiff/1.0/"
                xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
               xmp:ModifyDate="2026-08-08T21:10:11-03:00"
               xmp:Rating="4"
               xmp:Label="Red"
               tiff:Make="SONY"
               crs:Version="15.0"
               crs:Exposure2012="+0.50"
               crs:WhiteBalance="As Shot">
               <crs:ToneCurvePV2012>
                <rdf:Seq>
                 <rdf:li>0, 0</rdf:li>
                 <rdf:li>255, 255</rdf:li>
                </rdf:Seq>
               </crs:ToneCurvePV2012>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val DARKTABLE = """
            <?xpacket begin="${"\uFEFF"}" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="XMP Core 4.4.0-Exiv2">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:exif="http://ns.adobe.com/exif/1.0/"
                xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                xmlns:xmpMM="http://ns.adobe.com/xap/1.0/mm/"
                xmlns:darktable="http://darktable.sf.net/"
               exif:DateTimeOriginal="2026:08:07 10:15:30.250"
               xmp:Rating="-1"
               xmpMM:DerivedFrom="DSC07668.ARW"
               darktable:xmp_version="5"
               darktable:raw_params="0"
               darktable:auto_presets_applied="1"
               darktable:history_end="2">
               <darktable:history>
                <rdf:Seq>
                 <rdf:li darktable:num="0" darktable:operation="exposure" darktable:enabled="1" darktable:params="0000000000000000"/>
                </rdf:Seq>
               </darktable:history>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()

        val DIGIKAM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="XMP Core 4.4.0-Exiv2">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:digiKam="http://www.digikam.org/ns/1.0/"
                xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                xmlns:dc="http://purl.org/dc/elements/1.1/"
               digiKam:ColorLabel="2">
               <digiKam:PickLabel>3</digiKam:PickLabel>
               <xmp:Rating>3</xmp:Rating>
               <dc:subject>
                <rdf:Bag>
                 <rdf:li>Holiday</rdf:li>
                </rdf:Bag>
               </dc:subject>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
    }
}
