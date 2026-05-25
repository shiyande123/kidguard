package com.kidguard.face

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.kidguard.util.DebugLog
import com.seeta.sdk.SeetaPointF
import com.seeta.sdk.SeetaRect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ML Kit face detector (bundled, no GMS required).
 * Fallback when SeetaFace2 fails to load (missing 64-bit native libs).
 */
class MlkFaceDetector {

    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)     // 开启所有68个关键点检测
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)       // 开启轮廓模式
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)  // 开启表情分类
            .setMinFaceSize(0.15f)
            .build()
        FaceDetection.getClient(options)
    }

    /**
     * Detect faces in a bitmap.
     * @param rotationDegrees Should be 0 — FaceAnalyzer pre-rotates the bitmap
     *   to upright before detection, so no rotation correction is needed here.
     */
    fun detect(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        onResult: (List<FaceDetectResult>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { faces ->
                val results = faces.map { face -> toFaceDetectResult(face) }
                DebugLog.log(TAG, "ML Kit detected ${results.size} faces")
                onResult(results)
            }
            .addOnFailureListener { e ->
                DebugLog.log(TAG, "ML Kit detect FAILED: ${e.message}")
                onError(Exception(e))
            }
    }

    suspend fun detectSuspend(bitmap: Bitmap, rotationDegrees: Int = 0): List<FaceDetectResult> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, rotationDegrees)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    DebugLog.log(TAG, "ML Kit raw success: ${faces.size} faces detected, bitmap=${bitmap.width}x${bitmap.height}")
                    faces.forEachIndexed { i, f ->
                        DebugLog.log(TAG, "  Face[$i] bbox=${f.boundingBox}, conf=${f.headEulerAngleX},${f.headEulerAngleY},${f.headEulerAngleZ}")
                    }
                    val results = faces.map { face -> toFaceDetectResult(face) }
                    DebugLog.log(TAG, "ML Kit mapped to ${results.size} results")
                    cont.resume(results)
                }
                .addOnFailureListener { e ->
                    DebugLog.log(TAG, "ML Kit detect FAILED: ${e.message}")
                    cont.resume(emptyList())
                }
        }

    private fun toFaceDetectResult(face: Face): FaceDetectResult {
        val bbox = face.boundingBox
        val w = bbox.width()
        val h = bbox.height()

        // ML Kit landmark order: LEFT_EYE(0), RIGHT_EYE(1), NOSE_BASE(2), LEFT_EAR(3),
        //   RIGHT_EAR(4), LEFT_MOUTH(5), RIGHT_MOUTH(6), BOTTOM_MOUTH(7)
        val leftEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)
        val rightEye = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)
        val noseBase = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE)
        val leftMouth = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT)
        val rightMouth = face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT)

        // Log actual landmark availability
        DebugLog.log(TAG, "ML Kit landmarks: leftEye=${leftEye!=null}, rightEye=${rightEye!=null}, nose=${noseBase!=null}, leftMouth=${leftMouth!=null}, rightMouth=${rightMouth!=null}")

        // Use actual landmarks when available, otherwise fallback to bbox estimation
        // Eyes are the most critical for alignFace
        val lmk0 = SeetaPointF().apply {
            if (leftEye != null) {
                x = leftEye.position.x.toDouble()
                y = leftEye.position.y.toDouble()
            } else {
                x = (bbox.centerX() - w * 0.2f).toDouble()
                y = (bbox.centerY() - h * 0.1f).toDouble()
            }
        }
        val lmk1 = SeetaPointF().apply {
            if (rightEye != null) {
                x = rightEye.position.x.toDouble()
                y = rightEye.position.y.toDouble()
            } else {
                x = (bbox.centerX() + w * 0.2f).toDouble()
                y = (bbox.centerY() - h * 0.1f).toDouble()
            }
        }
        val lmk2 = SeetaPointF().apply {
            x = noseBase?.position?.x?.toDouble() ?: bbox.centerX().toDouble()
            y = noseBase?.position?.y?.toDouble() ?: (bbox.centerY() - h * 0.05f).toDouble()
        }
        val lmk3 = SeetaPointF().apply {
            x = leftMouth?.position?.x?.toDouble() ?: (bbox.centerX() - w * 0.15f).toDouble()
            y = leftMouth?.position?.y?.toDouble() ?: (bbox.centerY() + h * 0.2f).toDouble()
        }
        val lmk4 = SeetaPointF().apply {
            x = rightMouth?.position?.x?.toDouble() ?: (bbox.centerX() + w * 0.15f).toDouble()
            y = rightMouth?.position?.y?.toDouble() ?: (bbox.centerY() + h * 0.2f).toDouble()
        }

        val rect = SeetaRect().apply {
            x = bbox.left
            y = bbox.top
            width = w
            height = h
        }

        return FaceDetectResult(rect = rect, landmarks = arrayOf(lmk0, lmk1, lmk2, lmk3, lmk4))
    }

    fun close() {
        detector.close()
    }

    companion object {
        private const val TAG = "MlkFaceDetector"
    }
}
