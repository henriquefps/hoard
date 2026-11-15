package dev.hfps.hoard.app.cull

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.hfps.hoard.image.ImageFileResult
import dev.hfps.hoard.image.ScreenFile
import dev.hfps.hoard.image.ScreenSource
import dev.hfps.hoard.image.ZoomInfo
import dev.hfps.hoard.image.ZoomSource
import dev.hfps.hoard.smb.SmbAccess
import dev.hfps.hoard.smb.cull.ListedName
import dev.hfps.hoard.smb.cull.SidecarFound
import dev.hfps.hoard.smb.cull.SidecarResult
import dev.hfps.hoard.smb.cull.SidecarScan
import dev.hfps.hoard.smb.cull.SidecarStamp
import dev.hfps.hoard.xmp.XmpFields
import dev.hfps.hoard.xmp.XmpRead
import java.io.File

/**
 * A culling folder in memory for the view-model and screen tests: [count] RAW+JPEG shots `DSC00001…` (10 s apart,
 * with bursts of 4 one second apart where [burstEvery] says), synthetic images drawn on the fly, analyses on demand,
 * and sidecars as a map (what native read and wrote).
 */
class FakeCullBackend(
    private val dir: File,
    val count: Int = 24,
    private val burstEvery: Int = 8,
    var access: SmbAccess? = SmbAccess.READ_WRITE,
    /** Shot id → analysis; absent ids are analysed as a clean frame. */
    val analyses: MutableMap<String, Analysis> = HashMap(),
) : CullBackend {
    val shots: List<Shot> = (1..count).map { n ->
        val stem = "DSC%05d".format(n)
        Shot(
            id = stem.lowercase(),
            stem = stem,
            raw = ShotFile("$stem.ARW", "shoot/$stem.ARW", 25_000_000, "2026-10-03T14:00:00.000Z", 0),
            rendered = ShotFile("$stem.JPG", "shoot/$stem.JPG", 6_000_000, "2026-10-03T14:00:00.000Z", 0),
            others = emptyList(),
        )
    }

    /** Sidecars "on disk": shot id → (fields, mtime). */
    val sidecars = HashMap<String, Pair<XmpFields, Long>>()
    val written = ArrayList<Pair<String, XmpFields>>()
    val deleted = ArrayList<String>()
    var analyzeCalls = 0
    var clock = 1_000_000L
    var closed = false

    /** Groups of 4 shots; every other group (with [burstEvery] = 8) is a burst: its frames 1 s apart, the rest 10 s. */
    private fun group(n: Int) = (n - 1) / 4

    private fun isBurst(g: Int) = burstEvery > 0 && g % (burstEvery / 4) == 1

    private fun time(n: Int): Long {
        var t = 1_790_000_000_000L
        for (i in 2..n) t += if (group(i) == group(i - 1) && isBurst(group(i))) 1000 else 10_000
        return t
    }

    override suspend fun open() = OpenedFolder(shots, access, libRaw = true)

    override suspend fun metadata(shotIds: List<String>): Map<String, ShotMetadata?> = shotIds.associateWith { id ->
        val n = id.removePrefix("dsc").toInt()
        ShotMetadata(captureTime = "2026-10-03T14:00:%02d".format(n % 60), captureMillis = time(n), make = "Sony", model = "ILCE-6000", exposure = "1/250", fNumber = 5.6, iso = 200, focalLength = 35.0, focalLength35 = 52, width = 6000, height = 4000)
    }

    /** Whether the folder lists nothing at all (an unmounted volume): scans are then not complete. */
    var listsNothing = false

    /** Whether listing the folder fails (a dropped session). */
    var failScans = false

    override suspend fun readSidecars(seen: Map<String, SidecarStamp?>, refresh: Boolean): SidecarScan {
        if (failScans) throw java.io.IOException("listing failed")
        if (listsNothing) return SidecarScan(emptyList(), seen.keys.toList(), complete = false)
        val absent = ArrayList<String>()
        val found = seen.mapNotNull { (id, stamp) ->
            val (fields, mtime) = sidecars[id] ?: return@mapNotNull null.also { absent += id }
            val name = id.uppercase() + ".xmp"
            if (stamp != null && stamp.matches(ListedName(name, mtime, SIZE))) return@mapNotNull null
            SidecarFound(id, name, mtime, XmpRead(fields, true), size = SIZE)
        }
        return SidecarScan(found, absent, complete = true)
    }

    override suspend fun writeSidecars(items: List<Pair<String, XmpFields>>, hidden: Boolean): SidecarWrites {
        if (access == SmbAccess.READ) return SidecarWrites(items.map { SidecarResult(it.first, "readOnly", it.second) }, true)
        val results = items.map { (id, fields) ->
            clock += 1000
            sidecars[id] = fields to clock
            written += id to fields
            SidecarResult(id, "written", fields, id.uppercase() + ".xmp", clock)
        }
        return SidecarWrites(results, false)
    }

    override suspend fun analyze(shotIds: List<String>, urgent: Boolean): AnalyzeResult {
        analyzeCalls++
        return AnalyzeResult(shotIds.associateWith { analyses[it] ?: cleanAnalysis() }, faces = true)
    }

    private fun image(name: String, width: Int, height: Int, shade: Int): File {
        val file = File(dir, name)
        if (!file.isFile) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(40 + shade % 120, 90, 60))
            val paint = Paint().apply { color = Color.rgb(220, 160, 60) }
            canvas.drawCircle(width * 0.62f, height * 0.4f, height * 0.22f, paint)
            paint.color = Color.rgb(30, 50, 40)
            canvas.drawRect(0f, height * 0.7f, width.toFloat(), height.toFloat(), paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        return file
    }

    override suspend fun thumbnail(shotId: String, maxSize: Int): File = image("$shotId-thumb.jpg", 240, 160, shotId.hashCode() and 0xff)

    override suspend fun screen(shotId: String, longSide: Int, fast: Boolean, background: Boolean) =
        ScreenFile(image("$shotId-screen.jpg", 900, 600, shotId.hashCode() and 0xff), 900, 600, if (fast) ScreenSource.RAW_PREVIEW else ScreenSource.RENDERED, final = !fast)

    override suspend fun zoomInfo(shotId: String) = ZoomInfo(6000, 4000, ZoomSource.RENDERED)

    override suspend fun tile(shotId: String, tile: Tile) = ImageFileResult(image("$shotId-tile.jpg", 64, 64, 10), 64, 64)

    override fun prefetch(shotIds: List<String>, current: Int, longSide: Int) = Unit

    override suspend fun delete(path: String) {
        deleted += path
    }

    override fun flush() = Unit

    override fun close() {
        closed = true
    }

    companion object {
        /** Every fake sidecar's size. */
        const val SIZE = 512L

        fun cleanAnalysis(subject: Double = 200.0, blur: Double = 0.35, mean: Double = 0.45, faces: List<Face> = emptyList()) = Analysis(
            version = ANALYSIS_VERSION,
            source = "rawPreview",
            width = 1536,
            height = 1024,
            millis = 50,
            sharpness = Sharpness(50.0, List(16) { 10.0 }, 5, subject, if (faces.isEmpty()) "tile" else "face", blur),
            facesDetected = true,
            faces = faces,
            exposure = ExposureStats(List(64) { 1.0 / 64 }, mean, 0.01, 0.001, 0.0, 0.0),
            hash = "a161f1e1e3e36025",
            colors = "80".repeat(48),
        )
    }
}
