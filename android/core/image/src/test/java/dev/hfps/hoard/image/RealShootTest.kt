package dev.hfps.hoard.image

import dev.hfps.hoard.image.format.ImageInspector
import dev.hfps.hoard.image.format.ListedFile
import dev.hfps.hoard.image.format.ShotPairing
import dev.hfps.hoard.image.sharpness.Luma
import dev.hfps.hoard.image.sharpness.Sharpness
import dev.hfps.hoard.image.source.CountingSource
import dev.hfps.hoard.image.source.FileSource
import dev.hfps.hoard.image.source.readRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Opt-in, read-only checks on a real shoot (never committed): `HOARD_ARW_DIR=/path/to/folder`
 * with camera RAW + JPEG pairs. Prints what the camera embeds, bytes read per file and sharpness
 * scores (used for ADR 0006). Files are opened read-only and never written.
 */
class RealShootTest {

    private val dir: File? = System.getenv("HOARD_ARW_DIR")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }

    @Test
    fun `inspect a real shoot - previews, bytes read, EXIF`() {
        assumeTrue("HOARD_ARW_DIR not set", dir != null)
        val files = dir!!.listFiles()!!.filter { it.isFile }.sortedBy { it.name }
        val shots = ShotPairing.pair(files.map { ListedFile(it.name, it.length(), it.lastModified()) })
        println("shots: ${shots.size} (pairs ${shots.count { it.isPair }}, raw-only ${shots.count { it.isRawOnly }}, jpeg-only ${shots.count { it.isRenderedOnly }})")
        println("file | size | previews (w x h @ offset, bytes) | read bytes / calls | ms | EXIF")
        for (shot in shots) {
            for (listed in listOfNotNull(shot.raw, shot.rendered)) {
                val file = File(dir, listed.name)
                val counting = CountingSource(FileSource(file))
                val started = System.nanoTime()
                val inspection = counting.use { ImageInspector.inspect(it, file.name) }
                val ms = (System.nanoTime() - started) / 1e6
                val m = inspection.metadata
                println(
                    "${file.name} | ${file.length()} | " +
                        inspection.previews.joinToString { "${it.width}x${it.height} @${it.offset} ${it.length}B [${it.origin}]" } +
                        " | ${counting.bytesRead} / ${counting.readCalls} | ${"%.1f".format(ms)} | " +
                        "${m.model} ${m.captureTime}.${m.subSecond}${m.offsetTime.orEmpty()} ${m.exposureLabel}s f/${m.fNumber} ISO${m.iso} " +
                        "${m.focalLength}mm (${m.focalLength35}) \"${m.lens}\" o=${m.orientation} r=${m.rating} ${m.width}x${m.height}",
                )
                assertTrue(m.captureTime != null)
                if (inspection.type.isRaw) {
                    assertTrue("a RAW should embed a JPEG", inspection.largestPreview != null)
                    assertTrue("header only", counting.bytesRead < 512 * 1024)
                }
            }
            // RAW and JPEG of one shot agree on capture time and orientation.
            if (shot.isPair) {
                val a = FileSource(File(dir, shot.raw!!.name)).use { ImageInspector.inspect(it, shot.raw!!.name).metadata }
                val b = FileSource(File(dir, shot.rendered!!.name)).use { ImageInspector.inspect(it, shot.rendered!!.name).metadata }
                assertEquals(shot.stem, a.captureTime, b.captureTime)
                assertEquals(shot.stem, a.orientation, b.orientation)
            }
        }
    }

    @Test
    fun `sharpness of real photos - camera JPEG vs embedded preview`() {
        assumeTrue("HOARD_ARW_DIR not set", dir != null)
        val files = dir!!.listFiles()!!.filter { it.isFile }
        val shots = ShotPairing.pair(files.map { ListedFile(it.name, it.length()) })
        println("shot | JPEG global | JPEG best tile (#) | top quarter | preview global | preview best | ms")
        for (shot in shots) {
            val started = System.nanoTime()
            val jpeg = shot.rendered?.let { score(Desktop.read(File(dir, it.name).inputStream())) }
            val preview = shot.raw?.let { raw ->
                FileSource(File(dir, raw.name)).use { source ->
                    val p = ImageInspector.inspect(source, raw.name).largestPreview ?: return@use null
                    score(Desktop.read(ByteArrayInputStream(source.readRange(p.offset, p.length.toInt()))))
                }
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            fun f(v: Double?) = v?.let { "%.1f".format(it) } ?: "-"
            println("${shot.stem} | ${f(jpeg?.global)} | ${f(jpeg?.best)} (${jpeg?.bestIndex}) | ${f(jpeg?.topQuarter)} | ${f(preview?.global)} | ${f(preview?.best)} | $ms")
        }
    }

    private fun score(image: Luma) = Sharpness.score(Sharpness.downscale(image))
}

/**
 * javax.imageio through reflection: unit tests compile against android.jar (no java.desktop), but
 * run on a desktop JDK that has it.
 */
object Desktop {
    fun read(input: InputStream): Luma = input.use {
        val image = Class.forName("javax.imageio.ImageIO").getMethod("read", InputStream::class.java).invoke(null, it)
            ?: error("ImageIO could not decode")
        val cls = image.javaClass
        val w = cls.getMethod("getWidth").invoke(image) as Int
        val h = cls.getMethod("getHeight").invoke(image) as Int
        val argb = cls.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java)
            .invoke(image, 0, 0, w, h, null, 0, w) as IntArray
        Luma.fromArgb(w, h, argb)
    }
}
