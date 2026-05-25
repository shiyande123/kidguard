package com.kidguard.face

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.kidguard.util.DebugLog
import com.seeta.sdk.SeetaPointF
import com.seeta.sdk.SeetaRect
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Face detector using ML Kit (SeetaFace2 native libs not available in CI-built APKs).
 */
@Singleton
class FaceDetectorManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var mlkDetector: MlkFaceDetector? = null

    val isInitialized: Boolean
        get() = mlkDetector != null

    val activeDetectorName: String
        get() = if (mlkDetector != null) "MLKit" else "None"

    var modelName: String = "None"
        private set

    fun init() {
        close()
        DebugLog.log(TAG, "=== FaceDetectorManager.init() starting ===")
        DebugLog.log(TAG, "Using ML Kit for face detection")
        try {
            mlkDetector = MlkFaceDetector()
            modelName = "MLKit"
            DebugLog.log(TAG, "ML Kit detector initialized OK")
        } catch (e: Throwable) {
            DebugLog.log(TAG, "ML Kit init FAILED: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "ML Kit init FAILED", e)
        }
    }

    fun detect(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        onResult: (List<FaceDetectResult>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        mlkDetector?.let {
            it.detect(bitmap, rotationDegrees, onResult, onError)
            return
        }
        onError(IllegalStateException("No face detector available"))
    }

    fun detectSync(bitmap: Bitmap, rotationDegrees: Int = 0): List<FaceDetectResult> {
        return mlkDetector?.let {
            runCatching {
                kotlinx.coroutines.runBlocking { it.detectSuspend(bitmap, rotationDegrees) }
            }.getOrElse { emptyList() }
        } ?: emptyList()
    }

    fun getLandmarkCount(): Int = 5  // ML Kit provides 5-point landmarks

    fun close() {
        try { mlkDetector?.close() } catch (_: Exception) {}
        mlkDetector = null
        modelName = "None"
    }

    companion object {
        private const val TAG = "KidGuard-FaceDetector"
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
