package dev.hfps.hoard.app.cull

import dev.hfps.hoard.image.CullModel
import dev.hfps.hoard.image.ScreenSource
import dev.hfps.hoard.image.ShotAnalysisResult
import dev.hfps.hoard.image.format.PhotoMetadata
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.roundToLong

/** A shot's EXIF essentials (contract `CullMetadata`). */
data class ShotMetadata(
    /** `2026-08-07T15:04:05.733` from the camera clock. */
    val captureTime: String? = null,
    val captureMillis: Long? = null,
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    /** `1/250`. */
    val exposure: String? = null,
    val fNumber: Double? = null,
    val iso: Int? = null,
    val focalLength: Double? = null,
    val focalLength35: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int = 1,
    val rating: Int? = null,
) {
    companion object {
        /** What the plugin's `cullMetadata` sends, from the image core's reading. */
        fun of(m: PhotoMetadata): ShotMetadata {
            val size = CullModel.uprightSize(m)
            return ShotMetadata(
                captureTime = CullModel.captureTime(m),
                captureMillis = m.captureSortMillis,
                make = m.make,
                model = m.model,
                lens = m.lens,
                exposure = m.exposureLabel,
                fNumber = m.fNumber,
                iso = m.iso,
                focalLength = m.focalLength,
                focalLength35 = m.focalLength35,
                width = size?.first,
                height = size?.second,
                orientation = m.orientation,
                rating = m.rating,
            )
        }
    }
}

/**
 * The analysis as the contract's `CullAnalysis` JSON, with the rounding of `CullAnalysisJson` in `:app`: the format of
 * the `cull_analysis` cache, so rows written by Hoard 1 are read here and the other way round.
 */
object AnalysisJson {
    private fun r(value: Double, digits: Int): Double {
        val f = 10.0.pow(digits)
        return (value * f).roundToLong() / f
    }

    fun of(result: ShotAnalysisResult): Analysis {
        val a = result.analysis
        return Analysis(
            version = a.version,
            source = if (result.source == ScreenSource.RENDERED) "rendered" else "rawPreview",
            width = a.width,
            height = a.height,
            millis = result.millis,
            sharpness = Sharpness(r(a.global, 2), a.tiles.map { r(it, 2) }, a.bestTile, r(a.subject, 2), a.subjectKind, r(a.subjectBlur, 4)),
            facesDetected = a.facesDetected,
            faces = a.faces.map { f ->
                Face(
                    r(f.x, 4), r(f.y, 4), r(f.width, 4), r(f.height, 4),
                    f.leftEyeOpen?.let { r(it, 3) }, f.rightEyeOpen?.let { r(it, 3) }, f.smiling?.let { r(it, 3) },
                    f.prominent, r(f.sharpness, 2), r(f.blur, 4),
                )
            },
            exposure = a.exposure.let { e ->
                ExposureStats(e.histogram.map { r(it, 5) }, r(e.mean, 4), r(e.highlights, 5), r(e.shadows, 5), r(e.blownArea, 5), r(e.faceHighlights, 5))
            },
            hash = a.hash,
            colors = a.colors,
        )
    }

    fun write(a: Analysis): String = JSONObject().apply {
        put("version", a.version)
        put("source", a.source)
        put("width", a.width)
        put("height", a.height)
        put("millis", a.millis)
        put("sharpness", JSONObject().apply {
            put("global", a.sharpness.global)
            put("tiles", JSONArray(a.sharpness.tiles))
            put("bestTile", a.sharpness.bestTile)
            put("subject", a.sharpness.subject)
            put("subjectKind", a.sharpness.subjectKind)
            put("subjectBlur", a.sharpness.subjectBlur)
        })
        put("facesDetected", a.facesDetected)
        put("faces", JSONArray().apply {
            a.faces.forEach { f ->
                put(JSONObject().apply {
                    put("x", f.x); put("y", f.y); put("width", f.width); put("height", f.height)
                    f.leftEyeOpen?.let { put("leftEyeOpen", it) }
                    f.rightEyeOpen?.let { put("rightEyeOpen", it) }
                    f.smiling?.let { put("smiling", it) }
                    put("prominent", f.prominent)
                    put("sharpness", f.sharpness)
                    put("blur", f.blur)
                })
            }
        })
        put("exposure", JSONObject().apply {
            put("histogram", JSONArray(a.exposure.histogram))
            put("mean", a.exposure.mean)
            put("highlights", a.exposure.highlights)
            put("shadows", a.exposure.shadows)
            put("blownArea", a.exposure.blownArea)
            put("faceHighlights", a.exposure.faceHighlights)
        })
        put("hash", a.hash)
        put("colors", a.colors)
    }.toString()

    /** Null when the row is not a readable analysis (it is measured again). */
    fun read(json: String): Analysis? = runCatching {
        val o = JSONObject(json)
        val s = o.getJSONObject("sharpness")
        val e = o.getJSONObject("exposure")
        fun doubles(array: JSONArray) = (0 until array.length()).map { array.getDouble(it) }
        fun optDouble(obj: JSONObject, key: String) = if (obj.has(key) && !obj.isNull(key)) obj.getDouble(key) else null
        val faces = o.optJSONArray("faces") ?: JSONArray()
        Analysis(
            version = o.getInt("version"),
            source = o.optString("source", "rawPreview"),
            width = o.optInt("width"),
            height = o.optInt("height"),
            millis = o.optLong("millis"),
            sharpness = Sharpness(
                s.optDouble("global", 0.0), doubles(s.optJSONArray("tiles") ?: JSONArray()), s.optInt("bestTile"),
                s.getDouble("subject"), s.optString("subjectKind", "tile"), s.getDouble("subjectBlur"),
            ),
            facesDetected = o.optBoolean("facesDetected", false),
            faces = (0 until faces.length()).map { i ->
                val f = faces.getJSONObject(i)
                Face(
                    f.getDouble("x"), f.getDouble("y"), f.getDouble("width"), f.getDouble("height"),
                    optDouble(f, "leftEyeOpen"), optDouble(f, "rightEyeOpen"), optDouble(f, "smiling"),
                    f.optBoolean("prominent", false), f.optDouble("sharpness", 0.0), f.optDouble("blur", 0.0),
                )
            },
            exposure = ExposureStats(
                doubles(e.optJSONArray("histogram") ?: JSONArray()), e.getDouble("mean"), e.optDouble("highlights", 0.0),
                e.optDouble("shadows", 0.0), e.optDouble("blownArea", 0.0), e.optDouble("faceHighlights", 0.0),
            ),
            hash = o.optString("hash"),
            colors = o.optString("colors"),
        )
    }.getOrNull()
}
