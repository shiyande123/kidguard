package com.kidguard.face

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.kidguard.util.DebugLog
import com.seeta.sdk.FaceDetector2
import com.seeta.sdk.PointDetector2
import com.seeta.sdk.SeetaImageData
import com.seeta.sdk.SeetaPointF
import com.seeta.sdk.SeetaRect
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SeetaFace2 face detector and landmark detector.
 * Falls back to ML Kit (bundled) when SeetaFace2 native libs are unavailable.
 *
 * Uses SeetaFace2's FaceDetector2 and PointDetector2 JNI wrappers.
 * Models must be in assets under seetaface2_models/:
 *   - face_detector.face2 (FaceDetector2 model, ~4MB)
 *   - point_detector.dat (PointDetector2 model, ~6MB)
 *
 * Pipeline: detect → align → crop → recognition
 */
@Singleton
class FaceDetectorManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var detector: FaceDetector2? = null
    private var pointDetector: PointDetector2? = null

    /** ML Kit fallback when SeetaFace2 fails */
    private var mlkDetector: MlkFaceDetector? = null

    /** Whether at least one detector (SeetaFace2 or ML Kit) is available */
    val isInitialized: Boolean
        get() = (detector != null && pointDetector != null) || mlkDetector != null

    /** Human-readable name of the active detector */
    val activeDetectorName: String
        get() = when {
            detector != null && pointDetector != null -> "SeetaFace2"
            mlkDetector != null -> "MLKit"
            else -> "None"
        }

    /** Human-readable model name */
    var modelName: String = "None"
        private set

    fun init() {
        close()

        try {
            DebugLog.log(TAG, "=== FaceDetectorManager.init() starting ===")

            val faceDetectorModel = ensureModelFile(FACE_DETECTOR_MODEL, "seetaface2_models")
            val pointDetectorModel = ensureModelFile(POINT_DETECTOR_MODEL, "seetaface2_models")

            if (faceDetectorModel == null || !faceDetectorModel.exists()) {
                DebugLog.log(TAG, "FaceDetector2 model not found: $faceDetectorModel")
                return
            }
            if (pointDetectorModel == null || !pointDetectorModel.exists()) {
                DebugLog.log(TAG, "PointDetector2 model not found: $pointDetectorModel")
                return
            }

            DebugLog.log(TAG, "Initializing FaceDetector2 with: ${faceDetectorModel.absolutePath}")
            detector = FaceDetector2(faceDetectorModel.absolutePath)
            DebugLog.log(TAG, "FaceDetector2 created: ${detector != null}")

            DebugLog.log(TAG, "Initializing PointDetector2 with: ${pointDetectorModel.absolutePath}")
            pointDetector = PointDetector2(pointDetectorModel.absolutePath)
            DebugLog.log(TAG, "PointDetector2 created: ${pointDetector != null}")

            if (detector != null && pointDetector != null) {
                modelName = "SeetaFace2"
                DebugLog.log(TAG, "SeetaFace2 detectors initialized OK")
            } else {
                // Fallback: try ML Kit (bundled, no GMS required)
                DebugLog.log(TAG, "SeetaFace2 not available, falling back to ML Kit")
                try {
                    mlkDetector = MlkFaceDetector()
                    modelName = "MLKit"
                    DebugLog.log(TAG, "ML Kit detector initialized OK")
                } catch (e: Throwable) {
                    DebugLog.log(TAG, "ML Kit fallback also FAILED: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            DebugLog.log(TAG, "SeetaFace2 init FAILED: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "SeetaFace2 init FAILED", e)
            // Fallback: try ML Kit
            try {
                mlkDetector = MlkFaceDetector()
                modelName = "MLKit"
                DebugLog.log(TAG, "ML Kit fallback initialized OK after SeetaFace2 failure")
            } catch (e2: Throwable) {
                DebugLog.log(TAG, "ML Kit fallback FAILED: ${e2.message}")
                try { close() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Detect faces and landmarks in a bitmap.
     * Uses SeetaFace2 if available, otherwise ML Kit fallback.
     * Returns a list of detected face results with bounding box and landmarks.
     */
    fun detect(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        onResult: (List<FaceDetectResult>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        // Use SeetaFace2 if available
        if (detector != null && pointDetector != null) {
            detectWithSeetaFace2(bitmap, onResult, onError)
            return
        }

        // Use ML Kit fallback
        val mlk = mlkDetector
        if (mlk != null) {
            mlk.detect(bitmap, rotationDegrees, onResult, onError)
            return
        }

        onError(IllegalStateException("No face detector available"))
    }

    /**
     * SeetaFace2 detection path.
     */
    private fun detectWithSeetaFace2(
        bitmap: Bitmap,
        onResult: (List<FaceDetectResult>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            // Convert bitmap to BGR SeetaImageData
            val imageData = bitmapToSeetaImageData(bitmap)
            if (imageData == null) {
                onError(IllegalStateException("Failed to convert bitmap to SeetaImageData"))
                return
            }

            // Detect face rectangles
            val seetaRects = detector!!.Detect(imageData)
            DebugLog.log(TAG, "SeetaFace2 detected ${seetaRects.size} faces")

            if (seetaRects.isEmpty()) {
                onResult(emptyList())
                return
            }

            // For each face, detect landmarks
            val results = mutableListOf<FaceDetectResult>()
            for (seetaRect in seetaRects) {
                val landmarks = pointDetector!!.Detect(imageData, seetaRect)
                if (landmarks != null && landmarks.isNotEmpty()) {
                    results.add(FaceDetectResult(seetaRect, landmarks))
                }
            }

            DebugLog.log(TAG, "Landmarks detected for ${results.size} faces")
            onResult(results)

        } catch (e: Exception) {
            DebugLog.log(TAG, "SeetaFace2 detect FAILED: ${e.message}")
            onError(e)
        }
    }

    /**
     * Synchronous version: detect faces and return results directly.
     * @param rotationDegrees Rotation of the image in degrees (0, 90, 180, 270).
     *   Should always be 0 since FaceAnalyzer pre-rotates the bitmap to upright
     *   before calling this method. Kept as parameter for API compatibility.
     */
    fun detectSync(bitmap: Bitmap, rotationDegrees: Int = 0): List<FaceDetectResult> {
        // Use SeetaFace2 if available
        if (detector != null && pointDetector != null) {
            return detectSyncWithSeetaFace2(bitmap)
        }

        // Use ML Kit fallback (synchronous wrapper)
        val mlk = mlkDetector
        if (mlk != null) {
            return runCatching { kotlinx.coroutines.runBlocking { mlk.detectSuspend(bitmap, rotationDegrees) } }
                .getOrElse { emptyList() }
        }

        return emptyList()
    }

    /**
     * SeetaFace2 synchronous detection path.
     */
    private fun detectSyncWithSeetaFace2(bitmap: Bitmap): List<FaceDetectResult> {
        return try {
            val imageData = bitmapToSeetaImageData(bitmap) ?: return emptyList()
            val seetaRects = detector!!.Detect(imageData)
            if (seetaRects.isEmpty()) return emptyList()

            seetaRects.mapNotNull { seetaRect ->
                val landmarks = pointDetector!!.Detect(imageData, seetaRect)
                if (landmarks != null && landmarks.isNotEmpty()) {
                    FaceDetectResult(seetaRect, landmarks)
                } else null
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "SeetaFace2 detectSync FAILED: ${e.message}")
            emptyList()
        }
    }

    /**
     * Convert Android Bitmap to SeetaImageData (BGR format).
     */
    private fun bitmapToSeetaImageData(bitmap: Bitmap): SeetaImageData? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            // SeetaFace2 expects BGR, 3 channels
            val seetaImage = SeetaImageData(width, height, 3)

            // Get RGBA pixels
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            // Convert RGBA → BGR
            for (i in pixels.indices) {
                val pixel = pixels[i]
                seetaImage.data[i * 3 + 0] = (pixel shr 16 and 0xFF).toByte()     // B
                seetaImage.data[i * 3 + 1] = (pixel shr 8 and 0xFF).toByte()      // G
                seetaImage.data[i * 3 + 2] = (pixel and 0xFF).toByte()             // R
            }

            seetaImage
        } catch (e: Exception) {
            DebugLog.log(TAG, "bitmapToSeetaImageData FAILED: ${e.message}")
            null
        }
    }

    /**
     * Get the number of landmarks per face (from PointDetector2).
     */
    fun getLandmarkCount(): Int {
        return try {
            if (detector != null && pointDetector != null) {
                pointDetector?.LandmarkNum() ?: 5
            } else {
                5 // ML Kit provides 5-point landmarks minimum
            }
        } catch (e: Exception) {
            5
        }
    }

    private fun ensureModelFile(filename: String, subDir: String): File? {
        val modelDir = File(context.filesDir, subDir)
        if (!modelDir.exists()) modelDir.mkdirs()

        val destFile = File(modelDir, filename)
        if (destFile.exists() && destFile.length() > 1000) {
            return destFile
        }

        // Copy from assets
        try {
            context.assets.open("$subDir/$filename").use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            DebugLog.log(TAG, "Copied $filename from assets (${destFile.length()} bytes)")
            return destFile
        } catch (e: Exception) {
            DebugLog.log(TAG, "Failed to copy $filename from assets: ${e.message}")
        }

        return if (destFile.exists() && destFile.length() > 1000) destFile else null
    }

    fun close() {
        try { detector?.dispose() } catch (_: Exception) {}
        try { pointDetector?.dispose() } catch (_: Exception) {}
        try { mlkDetector?.close() } catch (_: Exception) {}
        detector = null
        pointDetector = null
        mlkDetector = null
        modelName = "None"
    }

    companion object {
        private const val TAG = "SeetaDetector"
        const val FACE_DETECTOR_MODEL = "fd_2_00.dat"
        const val POINT_DETECTOR_MODEL = "pd_2_00_pts5.dat"
    }
}

/**
 * Result of face detection with bounding box and landmarks.
 */
data class FaceDetectResult(
    val rect: SeetaRect,
    val landmarks: Array<SeetaPointF>
) {
    val boundingBox: android.graphics.Rect
        get() = android.graphics.Rect(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height)

    val leftEye: SeetaPointF? get() = landmarks.getOrNull(0)
    val rightEye: SeetaPointF? get() = landmarks.getOrNull(1)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FaceDetectResult
        return rect == other.rect && landmarks.contentEquals(other.landmarks)
    }

    override fun hashCode(): Int {
        var result = rect.hashCode()
        result = 31 * result + landmarks.contentHashCode()
        return result
    }
}
