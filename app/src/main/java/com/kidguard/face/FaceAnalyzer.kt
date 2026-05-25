package com.kidguard.face

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.kidguard.util.DebugLog
import com.seeta.sdk.SeetaPointF
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Camera frame analyzer using CameraX ImageAnalysis with a dedicated analysis thread.
 * 
 * All processing is synchronous within the analyzer callback — no async ML Kit calls.
 * This avoids ImageProxy lifecycle issues on Huawei/non-GMS devices.
 * 
 * Pipeline (all sync, same thread):
 * 1. ImageProxy → Bitmap (YUV→NV21→JPEG→Bitmap, quality 95%)
 * 2. FaceDetectorManager.detectSync() → SeetaFace2 or ML Kit fallback
 * 3. FaceEmbeddingModel.matchFace() → compare against registered children
 * 4. On match: trigger lock
 */
@Singleton
class FaceAnalyzer @Inject constructor(
    private val detectorManager: FaceDetectorManager,
    private val embeddingModel: FaceEmbeddingModel,
    private val faceRegistry: FaceRegistry
) : ImageAnalysis.Analyzer {

    private var onFaceDetected: ((Long?, String?) -> Unit)? = null
    private val isProcessing = AtomicBoolean(false)
    private var lastProcessStartTime = 0L

    fun setOnFaceDetectedListener(listener: (childId: Long?, childName: String?) -> Unit) {
        onFaceDetected = listener
    }

    override fun analyze(imageProxy: ImageProxy) {
        // One frame at a time — skip if still processing previous frame
        // Timeout protection: if isProcessing stuck for >5s, force reset
        if (isProcessing.get()) {
            if (System.currentTimeMillis() - lastProcessStartTime > 5000L) {
                DebugLog.log(TAG, "isProcessing stuck for >5s, force reset")
                isProcessing.set(false)
            } else {
                try { imageProxy.close() } catch (_: Exception) {}
                return
            }
        }
        lastProcessStartTime = System.currentTimeMillis()
        if (!isProcessing.compareAndSet(false, true)) {
            try { imageProxy.close() } catch (_: Exception) {}
            return
        }

        try {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                isProcessing.set(false)
                try { imageProxy.close() } catch (_: Exception) {}
                return
            }

            if (!detectorManager.isInitialized || !embeddingModel.isLoaded) {
                DebugLog.log(TAG, "Detector not ready: detector=${detectorManager.isInitialized}, recognizer=${embeddingModel.isLoaded}")
                isProcessing.set(false)
                try { imageProxy.close() } catch (_: Exception) {}
                return
            }

            // Convert ImageProxy → Bitmap synchronously
            // JPEG quality 95% — much better than the 80% that was failing before
            val rawBitmap = imageProxyToBitmap(imageProxy)
            if (rawBitmap == null) {
                isProcessing.set(false)
                try { imageProxy.close() } catch (_: Exception) {}
                return
            }

            // Rotate bitmap to correct orientation BEFORE detection.
            // This ensures all detectors (SeetaFace2 & ML Kit) operate on the same
            // upright bitmap, so landmarks are always in the same coordinate system
            // as the bitmap — no rotation mismatch between detector output and bitmap.
            val rotation = getRotationDegrees(imageProxy)
            val frameBitmap = rotateBitmap(rawBitmap, rotation)
            if (frameBitmap !== rawBitmap) rawBitmap.recycle()

            // Run detection synchronously (blocking the analysis thread)
            // rotation=0 because the bitmap is already rotated to upright
            val faces = detectorManager.detectSync(frameBitmap, 0)
            val detectorName = detectorManager.activeDetectorName
            DebugLog.log(TAG, "$detectorName detected ${faces.size} faces in frame (bitmap=${frameBitmap.width}x${frameBitmap.height}, rotation=$rotation)")

            if (faces.isEmpty()) {
                frameBitmap.recycle()
                isProcessing.set(false)
                try { imageProxy.close() } catch (_: Exception) {}
                return
            }

            // Take the largest face
            val bestFace = faces.maxByOrNull { it.rect.width * it.rect.height }
            if (bestFace == null) {
                frameBitmap.recycle()
                isProcessing.set(false)
                try { imageProxy.close() } catch (_: Exception) {}
                return
            }

            // Convert landmarks to LandmarkPoint[]
            // Both SeetaFace2 and ML Kit return landmarks in the bitmap coordinate system.
            // Since we pre-rotate the bitmap to upright before detection, landmarks are
            // always consistent with the bitmap — no coordinate mismatch.
            val lmPoints = bestFace.landmarks.map {
                LandmarkPoint(it.x.toDouble(), it.y.toDouble())
            }.toTypedArray()

            // Match against registered children
            // Landmarks and bitmap are in the same (upright) coordinate system
            val childId = embeddingModel.matchFace(frameBitmap, lmPoints)

            if (childId != null) {
                val childName = faceRegistry.getChildName(childId)
                DebugLog.log(TAG, "FACE MATCH: childId=$childId, name=$childName")
                onFaceDetected?.invoke(childId, childName)
            } else {
                DebugLog.log(TAG, "No match for face")
                onFaceDetected?.invoke(null, null)
            }

            frameBitmap.recycle()

        } catch (e: Exception) {
            DebugLog.log(TAG, "EXCEPTION in analyze: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            isProcessing.set(false)
            try { imageProxy.close() } catch (_: Exception) {}
        }
    }

    /**
     * Convert ImageProxy to Bitmap via YUV→NV21→JPEG→Bitmap.
     * JPEG quality 95% preserves sufficient detail for face detection/recognition.
     * Must be called synchronously before imageProxy.close().
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        try {
            val nv21 = yuv420888ToNv21(imageProxy)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 95, out)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
            if (bitmap == null) {
                DebugLog.log(TAG, "imageProxyToBitmap: BitmapFactory returned null")
            }
            return bitmap
        } catch (e: Exception) {
            DebugLog.log(TAG, "imageProxyToBitmap FAILED: ${e.message}")
            return null
        }
    }

    /**
     * Get rotation degrees from ImageProxy for logging.
     * ImageProxy reports rotation in degrees (0, 90, 180, 270).
     */
    private fun getRotationDegrees(imageProxy: ImageProxy): Int {
        val degrees = imageProxy.imageInfo.rotationDegrees
        DebugLog.log(TAG, "ImageProxy rotationDegrees=$degrees")
        return degrees
    }

    /**
     * Rotate a bitmap by the given degrees.
     * Used to convert the raw sensor-oriented bitmap from ImageProxy
     * to an upright bitmap before face detection, ensuring that
     * landmarks from all detectors (SeetaFace2, ML Kit) are in the
     * same coordinate system as the bitmap.
     *
     * Note: after rotation, width and height may be swapped
     * (e.g. 1920x1080 → 1080x1920 for 90°/270° rotation).
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Convert YUV_420_888 ImageProxy planes to NV21 format.
     * Handles both interleaved (pixelStride=2) and planar (pixelStride>2) UV layouts.
     */
    private fun yuv420888ToNv21(imageProxy: ImageProxy): ByteArray {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Copy Y plane
        yBuffer.get(nv21, 0, ySize)

        val uvPixelStride = imageProxy.planes[1].pixelStride
        val uvRowStride = imageProxy.planes[1].rowStride
        val uvWidth = imageProxy.width / 2
        val uvHeight = imageProxy.height / 2

        if (uvPixelStride == 2) {
            // Interleaved UV — V is at ySize, U follows V
            // Actual layout: YYYYY... VUVU... (NV21)
            // vBuffer comes after ySize, but it's interleaved with U
            // We need to de-interleave: take V and interleave with U
            val uvData = ByteArray(uSize + vSize)
            uBuffer.get(uvData, 0, uSize)
            vBuffer.get(uvData, uSize, vSize)

            // Convert interleaved VU to NV21 (VU → UV with V first)
            var pos = ySize
            for (i in 0 until uSize step 2) {
                nv21[pos++] = uvData[i + 1]   // V
                nv21[pos++] = uvData[i]       // U
            }
        } else {
            // Planar UV — convert to NV21
            val uRow = ByteArray(uvWidth)
            val vRow = ByteArray(uvWidth)
            var pos = ySize
            for (row in 0 until uvHeight) {
                vBuffer.position(row * uvRowStride)
                vBuffer.get(vRow, 0, uvWidth)
                uBuffer.position(row * uvRowStride)
                uBuffer.get(uRow, 0, uvWidth)
                for (col in 0 until uvWidth) {
                    nv21[pos++] = vRow[col]
                    nv21[pos++] = uRow[col]
                }
            }
        }

        return nv21
    }

    companion object {
        private const val TAG = "FaceAnalyzer"
    }
}
