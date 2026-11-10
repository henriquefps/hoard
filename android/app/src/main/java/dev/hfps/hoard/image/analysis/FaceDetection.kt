package dev.hfps.hoard.image.analysis

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit

/** Finds faces and eye states in an upright analysis image. Null = no detector (faces unknown). */
fun interface Faces {
    fun detect(bitmap: Bitmap): List<DetectedFace>?
}

/**
 * ML Kit face detection with the **bundled** model (`com.google.mlkit:face-detection`, no Play
 * services, works offline on any device), classification on (eye-open and smile probabilities),
 * no landmarks or contours (not needed, and slower). ADR 0009.
 *
 * Created lazily on the first analysis; if the native part cannot load (an ABI ML Kit does not
 * ship, a broken install) detection is switched off for the process and results say
 * `facesDetected: false` instead of failing the whole analysis.
 */
class MlKitFaces : Faces {
    @Volatile private var detector: FaceDetector? = null
    @Volatile private var broken = false
    @Volatile private var failures = 0

    private fun detector(): FaceDetector? {
        if (broken) return null
        detector?.let { return it }
        return synchronized(this) {
            detector ?: try {
                FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                        .setMinFaceSize(MIN_FACE_SIZE)
                        .build(),
                ).also { detector = it }
            } catch (e: Throwable) {
                Log.w(TAG, "Face detection unavailable: $e")
                broken = true
                null
            }
        }
    }

    override fun detect(bitmap: Bitmap): List<DetectedFace>? {
        val client = detector() ?: return null
        return try {
            val faces = Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)), TIMEOUT_S, TimeUnit.SECONDS)
            failures = 0
            faces.map { face ->
                val box = face.boundingBox
                DetectedFace(
                    left = box.left.toFloat(),
                    top = box.top.toFloat(),
                    right = box.right.toFloat(),
                    bottom = box.bottom.toFloat(),
                    leftEyeOpen = face.leftEyeOpenProbability,
                    rightEyeOpen = face.rightEyeOpenProbability,
                    smiling = face.smilingProbability,
                )
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Throwable) {
            // No native library for this ABI (excluded on 32-bit / x86) or the model failed to
            // load: faces are unknown for this shot, and after a few failures for the process.
            Log.w(TAG, "Face detection failed: $e")
            if (e is UnsatisfiedLinkError || e.cause is UnsatisfiedLinkError || ++failures >= MAX_FAILURES) broken = true
            null
        }
    }

    /** False once ML Kit failed to load in this process. */
    val available: Boolean get() = !broken

    fun close() {
        synchronized(this) {
            detector?.close()
            detector = null
        }
    }

    companion object {
        private const val TAG = "HoardAnalysis"
        /** ML Kit's minimum face width relative to the image width (smaller faces are not reported). */
        const val MIN_FACE_SIZE = 0.04f
        private const val TIMEOUT_S = 20L
        private const val MAX_FAILURES = 3
    }
}
