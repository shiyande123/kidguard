package com.kidguard.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.kidguard.util.DebugLog
import com.seeta.sdk.SeetaPointF
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ONNX Runtime 人脸识别 (buffalo_s / ArcFace).
 *
 * 模型: buffalo_s.onnx (InsightFace Buffalo_S, 512-dim ArcFace)
 * 输入: [1, 3, 112, 112] float32, RGB, 归一化 [-1, 1]
 * 输出: [1, 512] float32, L2 归一化特征向量
 */
@Singleton
class OnnxFaceModel @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var env: OrtEnvironment? = null
    @Volatile
    private var session: OrtSession? = null

    val embeddingDim: Int = 512
    val inputSize: Int = 192  // buffalo_s.onnx 需要 192x192 输入

    var modelName: String = "None"
        private set

    val isLoaded: Boolean
        get() = session != null

    fun isUsingOnnx(): Boolean = isLoaded
    fun isUsingTflite(): Boolean = false

    fun init() {
        // Double-check: skip if already loaded (idempotent)
        if (session != null) {
            DebugLog.log(TAG, "=== OnnxFaceModel.init() skipped — already loaded ===")
            return
        }
        synchronized(this) {
            if (session != null) {
                DebugLog.log(TAG, "=== OnnxFaceModel.init() skipped — already loaded ===")
                return
            }
            try {
                DebugLog.log(TAG, "=== OnnxFaceModel.init() starting ===")

                val modelFile = ensureModelFile()
                if (modelFile == null || !modelFile.exists() || modelFile.length() < 100_000) {
                    DebugLog.log(TAG, "buffalo_s.onnx not found")
                    return
                }

                DebugLog.log(TAG, "Loading buffalo_s.onnx from: ${modelFile.absolutePath}")
                // Reuse existing env if available (env is process-wide singleton, never null after first init)
                if (env == null) {
                    env = OrtEnvironment.getEnvironment()
                }
                session = env!!.createSession(modelFile.absolutePath)

                val inNames = session!!.inputNames
                val outNames = session!!.outputNames
                DebugLog.log(TAG, "ONNX inputs: $inNames, outputs: $outNames")
                modelName = "buffalo_s"
                DebugLog.log(TAG, "OnnxFaceModel loaded OK")
            } catch (e: Throwable) {
                DebugLog.log(TAG, "OnnxFaceModel init FAILED: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "ONNX init FAILED", e)
                try { close() } catch (_: Throwable) {}
            }
        }
    }

    private fun ensureModelFile(): File? {
        val modelDir = File(context.filesDir, "onnx_models")
        if (!modelDir.exists()) modelDir.mkdirs()
        val destFile = File(modelDir, "buffalo_s.onnx")
        if (destFile.exists() && destFile.length() > 100_000) return destFile
        try {
            context.assets.open("onnx_models/buffalo_s.onnx").use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            DebugLog.log(TAG, "Copied buffalo_s.onnx (${destFile.length()} bytes)")
        } catch (e: Throwable) {
            DebugLog.log(TAG, "Failed to copy buffalo_s.onnx: ${e.message}")
        }
        return if (destFile.exists() && destFile.length() > 100_000) destFile else null
    }

    /**
     * 提取人脸特征向量（同步）.
     *
     * @param bitmap 人脸位图
     * @param landmarks 人脸5点关键点（可选，用于对齐）
     * @return 512维归一化向量，或 null
     */
    fun extractEmbedding(bitmap: Bitmap, landmarks: Array<LandmarkPoint>? = null): FloatArray? {
        val sess = session ?: return null
        val envRef = env ?: return null

        return try {
            val inputName = sess.inputNames.first()
            val outputName = sess.outputNames.first()

            // 对齐 + 裁剪
            val processed = if (landmarks != null && landmarks.size >= 2) {
                val aligned = alignFace(bitmap, landmarks[0], landmarks[1])
                val cropped = cropCentered(aligned, inputSize)
                if (aligned !== bitmap) aligned.recycle()
                cropped
            } else {
                cropCentered(bitmap, inputSize)
            }

            // 预处理: RGBA→RGB, 归一化到 [-1,1]
            val inputData = preprocess(processed)
            processed.recycle()
            if (inputData == null) return null

            // ONNX 推理
            val floatBuffer = ByteBuffer.allocateDirect(inputData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(inputData)
            floatBuffer.rewind()

            val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
            DebugLog.log(TAG, "Input shape: [1, 3, $inputSize, $inputSize], inputData sample: [${inputData[0]},${inputData[1]},${inputData[2]}...]")
            val inputOnnx = OnnxTensor.createTensor(envRef, floatBuffer, shape)

            val inputs = mapOf(inputName to inputOnnx)
            val result = sess.run(inputs)
            val outOpt = result.get(outputName)
            if (!outOpt.isPresent) throw RuntimeException("No output: $outputName")
            val outTensor = outOpt.get() as OnnxTensor
            val outBuf = outTensor.floatBuffer

            val embedding = FloatArray(embeddingDim)
            outBuf.get(embedding, 0, embeddingDim)
            DebugLog.log(TAG, "ONNX raw output sample: [${embedding[0]},${embedding[1]},${embedding[2]},${embedding[3]},${embedding[4]}...]")
            normalize(embedding)

            outTensor.close()
            inputOnnx.close()
            result.close()
            embedding
        } catch (e: Exception) {
            DebugLog.log(TAG, "extractEmbedding FAILED: ${e.message}")
            Log.e(TAG, "extractEmbedding", e)
            null
        }
    }

    /**
     * Extract embedding from an already aligned 192×192 face bitmap.
     * Skips alignFace + cropCentered since the input is already preprocessed.
     * Used by FaceEnrollScreen during enrollment and registerAllChildren during reload.
     */
    fun extractEmbeddingDirect(alignedBitmap: Bitmap): FloatArray? {
        val sess = session ?: return null
        val envRef = env ?: return null

        return try {
            val inputName = sess.inputNames.first()
            val outputName = sess.outputNames.first()

            // Preprocess only (skip alignFace + cropCentered)
            val inputData = preprocess(alignedBitmap) ?: return null

            // ONNX inference
            val floatBuffer = ByteBuffer.allocateDirect(inputData.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(inputData)
            floatBuffer.rewind()

            val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
            val inputOnnx = OnnxTensor.createTensor(envRef, floatBuffer, shape)

            val inputs = mapOf(inputName to inputOnnx)
            val result = sess.run(inputs)
            val outOpt = result.get(outputName)
            if (!outOpt.isPresent) throw RuntimeException("No output: $outputName")
            val outTensor = outOpt.get() as OnnxTensor
            val outBuf = outTensor.floatBuffer

            val embedding = FloatArray(embeddingDim)
            outBuf.get(embedding, 0, embeddingDim)
            normalize(embedding)

            outTensor.close()
            inputOnnx.close()
            result.close()
            embedding
        } catch (e: Exception) {
            DebugLog.log(TAG, "extractEmbeddingDirect FAILED: ${e.message}")
            Log.e(TAG, "extractEmbeddingDirect", e)
            null
        }
    }

    /**
     * 计算两个向量的余弦相似度.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        var dot = 0f; var nA = 0f; var nB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; nA += a[i] * a[i]; nB += b[i] * b[i] }
        return dot / (kotlin.math.sqrt(nA) * kotlin.math.sqrt(nB) + 1e-8f)
    }

    // ─── 对齐 & 裁剪 ────────────────────────────────────────────

    fun alignAndCrop(bitmap: Bitmap, leftEye: LandmarkPoint, rightEye: LandmarkPoint): Bitmap {
        return alignAndCropInternal(bitmap, leftEye, rightEye)
    }

    fun alignAndCrop(bitmap: Bitmap, leftEye: SeetaPointF, rightEye: SeetaPointF): Bitmap {
        return alignAndCropInternal(bitmap,
            LandmarkPoint(leftEye.x, leftEye.y),
            LandmarkPoint(rightEye.x, rightEye.y))
    }

    private fun alignAndCropInternal(bitmap: Bitmap, left: LandmarkPoint, right: LandmarkPoint): Bitmap {
        val aligned = alignFace(bitmap, left, right)
        val cropped = cropCentered(aligned, inputSize)
        if (aligned !== bitmap) aligned.recycle()
        return cropped
    }

    private fun alignFace(bitmap: Bitmap, left: LandmarkPoint, right: LandmarkPoint): Bitmap {
        val dx = (right.x - left.x).toFloat()
        val dy = (right.y - left.y).toFloat()
        val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val midX = ((left.x + right.x) / 2.0).toFloat()
        val midY = ((left.y + right.y) / 2.0).toFloat()
        val matrix = Matrix().apply { postRotate(-angle, midX, midY) }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) { bitmap }
    }

    private fun cropCentered(bitmap: Bitmap, targetSize: Int): Bitmap {
        val bw = bitmap.width; val bh = bitmap.height
        val cropSize = minOf(bw, bh).coerceAtLeast(1)
        val x = ((bw - cropSize) / 2f).toInt().coerceAtLeast(0)
        val y = ((bh - cropSize) / 2f).toInt().coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(bitmap, x, y, cropSize, cropSize)
        if (cropped === bitmap) return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    /**
     * 预处理: RGBA→RGB, 归一化到 [-1, 1].
     * ArcFace: (pixel / 255 - 0.5) / 0.5 = pixel * 2 / 255 - 1
     */
    private fun preprocess(bitmap: Bitmap): FloatArray? {
        return try {
            val w = bitmap.width; val h = bitmap.height
            require(w == inputSize && h == inputSize) { "Expected ${inputSize}x${inputSize}, got ${w}x${h}" }
            val channelSize = inputSize * inputSize
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val out = FloatArray(3 * channelSize)
            for (i in pixels.indices) {
                val r = ((pixels[i] shr 16) and 0xFF) / 255f
                val g = ((pixels[i] shr 8) and 0xFF) / 255f
                val b = (pixels[i] and 0xFF) / 255f
                out[i]                  = r * 2f - 1f
                out[i + channelSize]    = g * 2f - 1f
                out[i + channelSize * 2]= b * 2f - 1f
            }
            out
        } catch (e: Exception) { null }
    }

    /** L2 归一化（原地操作） */
    private fun normalize(v: FloatArray) {
        var norm = 0f
        for (f in v) norm += f * f
        norm = kotlin.math.sqrt(norm) + 1e-8f
        for (i in v.indices) v[i] /= norm
    }

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        // NOTE: do NOT null out env — OrtEnvironment.getEnvironment() is a process-wide singleton.
        // Nulling it causes "Environment has been released" exceptions on next init().
        // The env stays alive for the process lifetime; only session needs cleanup.
        modelName = "None"
    }

    companion object { private const val TAG = "OnnxFaceModel" }
}
