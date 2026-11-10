package dev.hfps.hoard.smb.cull

import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import dev.hfps.hoard.image.ScreenSource
import dev.hfps.hoard.image.ShotAnalysisResult
import kotlin.math.roundToLong

/** Contract `CullAnalysis` (definitions.ts) from a native analysis (ADR 0009). */
object CullAnalysisJson {
    private fun r(value: Double, digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return (value * f).roundToLong() / f
    }

    fun of(result: ShotAnalysisResult): JSObject {
        val a = result.analysis
        return JSObject().apply {
            put("version", a.version)
            put("source", if (result.source == ScreenSource.RENDERED) "rendered" else "rawPreview")
            put("width", a.width)
            put("height", a.height)
            put("millis", result.millis)
            put("sharpness", JSObject().apply {
                put("global", r(a.global, 2))
                put("tiles", JSArray().apply { a.tiles.forEach { put(r(it, 2)) } })
                put("bestTile", a.bestTile)
                put("subject", r(a.subject, 2))
                put("subjectKind", a.subjectKind)
                put("subjectBlur", r(a.subjectBlur, 4))
            })
            put("facesDetected", a.facesDetected)
            put("faces", JSArray().apply {
                a.faces.forEach { f ->
                    put(JSObject().apply {
                        put("x", r(f.x, 4)); put("y", r(f.y, 4)); put("width", r(f.width, 4)); put("height", r(f.height, 4))
                        f.leftEyeOpen?.let { put("leftEyeOpen", r(it, 3)) }
                        f.rightEyeOpen?.let { put("rightEyeOpen", r(it, 3)) }
                        f.smiling?.let { put("smiling", r(it, 3)) }
                        put("prominent", f.prominent)
                        put("sharpness", r(f.sharpness, 2))
                        put("blur", r(f.blur, 4))
                    })
                }
            })
            put("exposure", JSObject().apply {
                put("histogram", JSArray().apply { a.exposure.histogram.forEach { put(r(it, 5)) } })
                put("mean", r(a.exposure.mean, 4))
                put("highlights", r(a.exposure.highlights, 5))
                put("shadows", r(a.exposure.shadows, 5))
            })
            put("hash", a.hash)
            put("colors", a.colors)
        }
    }
}
