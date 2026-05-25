package com.kidguard.face

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.kidguard.util.DebugLog
import com.seeta.sdk.SeetaPointF
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ONNX Runtime 人脸识别引擎 ( buffalo_s / ArcFace 512-dim ).
 *
 * 架构: 内存 embedding 数据库
 * - On app init: load all children from Room → extract embeddings → store in map
 * - On enrollment: extract embedding, store in map + persist bitmap/landmarks
 * - On match: extract live embedding → cosine similarity against all stored embeddings
 * - Reference data (bitmap + landmarks) stored as files for persistence
 *
 * Pipeline:
 * 1. alignFace() - 旋转对齐两眼水平
 * 2. cropFace() - 中心裁剪到 112x112
 * 3. extractEmbedding() - ONNX forward pass → 512-dim normalized vector
 * 4. cosineSimilarity() - compare live vs reference
 */
@Singleton
class FaceEmbeddingModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val faceRegistry: FaceRegistry,
    private val onnxModel: OnnxFaceModel
) {

    /** 512 维 ArcFace 特征向量 */
    val EMBEDDING_SIZE: Int = 512
    val INPUT_SIZE: Int = 192  // Must match buffalo_s.onnx input size

    var modelName: String = "None"
        private set

    val isLoaded: Boolean
        get() = onnxModel.isLoaded

    fun isUsingOnnx(): Boolean = isLoaded
    fun isUsingTflite(): Boolean = false

    /** childId → 512-dim embedding 向量 */
    private val childEmbeddings = ConcurrentHashMap<Long, FloatArray>()

    /** childId → reference face info (bitmap path + landmarks) */
    private val childReferences = ConcurrentHashMap<Long, ReferenceFaceInfo>()

    fun init() {
        if (onnxModel.isLoaded) {
            DebugLog.log(TAG, "=== FaceEmbeddingModel.init() skipped — already loaded ===")
            return
        }
        try {
            DebugLog.log(TAG, "=== FaceEmbeddingModel.init() starting ===")
            onnxModel.init()
            if (onnxModel.isLoaded) {
                modelName = "buffalo_s_onnx"
                DebugLog.log(TAG, "FaceEmbeddingModel (ONNX) loaded OK: $modelName")
            } else {
                DebugLog.log(TAG, "ONNX model failed to load")
            }
        } catch (e: Throwable) {
            DebugLog.log(TAG, "FaceEmbeddingModel init FAILED: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "init FAILED", e)
            try { close() } catch (_: Throwable) {}
        }
    }

    /**
     * Load all enrolled children: extract embeddings from stored bitmaps.
     * Called on app startup.
     */
    suspend fun registerAllChildren(
        children: Map<Long, ReferenceFaceInfo>,
        onProgress: ((Int, Int) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        if (!onnxModel.isLoaded) {
            DebugLog.log(TAG, "registerAllChildren: ONNX not loaded")
            return@withContext
        }

        childEmbeddings.clear()
        childReferences.clear()

        val entries = children.entries.toList()
        entries.forEachIndexed { i, (childId, refInfo) ->
            try {
                val refBitmap = loadReferenceBitmap(refInfo.bitmapPath)
                if (refBitmap != null) {
                    // Crop + scale to 192×192 before inference (loadReferenceBitmap returns original size)
                    val scaled = cropCenteredToInputSize(refBitmap)
                    refBitmap.recycle()
                    if (scaled != null) {
                        val embedding = onnxModel.extractEmbeddingDirect(scaled)
                        scaled.recycle()
                        if (embedding != null) {
                            childEmbeddings[childId] = embedding
                            childReferences[childId] = refInfo
                            DebugLog.log(TAG, "Registered childId=$childId embedding=[${embedding[0]},${embedding[1]},${embedding[2]}...]")
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLog.log(TAG, "Error registering childId=$childId: ${e.message}")
            }
            onProgress?.invoke(i + 1, entries.size)
        }

        DebugLog.log(TAG, "registerAllChildren done: ${childEmbeddings.size} children registered")
    }

    /**
     * Enroll a new child's face: extract embedding and store.
     *
     * @return true if enrollment succeeded
     */
    suspend fun enrollChild(
        childId: Long,
        bitmap: Bitmap,
        landmarks: Array<LandmarkPoint>
    ): Boolean = withContext(Dispatchers.IO) {
        val rec = onnxModel
        if (!rec.isLoaded) {
            DebugLog.log(TAG, "enrollChild: ONNX not loaded")
            return@withContext false
        }

        DebugLog.log(TAG, "enrollChild: bitmap=${bitmap.width}x${bitmap.height}, landmarks=${landmarks.size}, childId=$childId")
        try {
            val embedding = rec.extractEmbedding(bitmap, landmarks)
            if (embedding != null) {
                childEmbeddings[childId] = embedding
                DebugLog.log(TAG, "enrollChild: childId=$childId enrolled OK, embedding=[${embedding[0]},${embedding[1]},${embedding[2]}...]")
                true
            } else {
                DebugLog.log(TAG, "enrollChild: childId=$childId embedding extraction failed")
                false
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "enrollChild FAILED: ${e.message}")
            false
        }
    }

    /**
     * Sync version for FaceEnrollScreen.
     */
    fun enrollChildSync(
        childId: Long,
        bitmap: Bitmap,
        landmarks: Array<LandmarkPoint>
    ): Int {
        val embedding = onnxModel.extractEmbedding(bitmap, landmarks)
        return if (embedding != null) {
            childEmbeddings[childId] = embedding
            DebugLog.log(TAG, "enrollChildSync: childId=$childId enrolled OK")
            childId.toInt()
        } else {
            DebugLog.log(TAG, "enrollChildSync: childId=$childId FAILED")
            -1
        }
    }

    /**
     * Sync version for FaceEnrollScreen — uses already aligned 192×192 face image.
     * Skips alignFace + cropCentered since the image is already aligned.
     */
    fun enrollFromAlignedSync(
        childId: Long,
        alignedBitmap: Bitmap
    ): Int {
        val embedding = onnxModel.extractEmbeddingDirect(alignedBitmap)
        return if (embedding != null) {
            childEmbeddings[childId] = embedding
            DebugLog.log(TAG, "enrollFromAlignedSync: childId=$childId enrolled OK")
            childId.toInt()
        } else {
            DebugLog.log(TAG, "enrollFromAlignedSync: childId=$childId FAILED")
            -1
        }
    }

    fun unregisterChild(childId: Long) {
        childEmbeddings.remove(childId)
        childReferences.remove(childId)
        DebugLog.log(TAG, "unregisterChild: childId=$childId removed")
    }

    suspend fun reloadAllChildren(
        children: Map<Long, ReferenceFaceInfo>,
        onProgress: ((Int, Int) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        registerAllChildren(children, onProgress)
    }

    /**
     * Match a live detected face against all registered children.
     *
     * @return childId with highest similarity (>= threshold), or null
     */
    fun matchFace(
        liveBitmap: Bitmap,
        liveLandmarks: Array<LandmarkPoint>
    ): Long? {
        val rec = onnxModel
        if (!rec.isLoaded || childEmbeddings.isEmpty()) {
            DebugLog.log(TAG, "matchFace: not ready (loaded=${rec.isLoaded}, count=${childEmbeddings.size})")
            return null
        }

        DebugLog.log(TAG, "matchFace: liveBitmap=${liveBitmap.width}x${liveBitmap.height}, landmarks=${liveLandmarks.size}")

        return try {
            val liveEmbedding = rec.extractEmbedding(liveBitmap, liveLandmarks)
                ?: run { DebugLog.log(TAG, "matchFace: extractEmbedding returned null"); return null }

            var bestChildId: Long? = null
            var bestSimilarity = -1f

            for ((childId, refEmbedding) in childEmbeddings) {
                val sim = rec.cosineSimilarity(liveEmbedding, refEmbedding)
                DebugLog.log(TAG, "  childId=$childId similarity=$sim live=[${liveEmbedding[0]},${liveEmbedding[1]},${liveEmbedding[2]}...] ref=[${refEmbedding[0]},${refEmbedding[1]},${refEmbedding[2]}...]")
                if (sim > bestSimilarity) {
                    bestSimilarity = sim
                    bestChildId = childId
                }
            }

            val threshold = faceRegistry.getMatchThreshold()
            DebugLog.log(TAG, "Best match: childId=$bestChildId, similarity=$bestSimilarity (threshold=$threshold)")

            if (bestChildId != null && bestSimilarity >= threshold) {
                bestChildId
            } else {
                null
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "matchFace FAILED: ${e.message}")
            null
        } finally {
            // Caller (FaceAnalyzer) passes the raw frame bitmap; we only hold processed copies.
            // The caller is responsible for recycling liveBitmap after this returns.
            // Log nothing here to avoid log spam on every frame.
        }
    }

    /**
     * Direct comparison between live face and a reference bitmap+landmarks.
     */
    fun compareDirect(
        liveBitmap: Bitmap,
        liveLandmarks: Array<LandmarkPoint>,
        refBitmap: Bitmap,
        refLandmarks: Array<LandmarkPoint>
    ): Float {
        val rec = onnxModel
        if (!rec.isLoaded) return -1f

        return try {
            val liveEmb = rec.extractEmbedding(liveBitmap, liveLandmarks) ?: return -1f
            val refEmb = rec.extractEmbedding(refBitmap, refLandmarks) ?: return -1f
            val sim = rec.cosineSimilarity(liveEmb, refEmb)
            sim.coerceIn(0f, 1f)
        } catch (e: Exception) {
            DebugLog.log(TAG, "compareDirect FAILED: ${e.message}")
            -1f
        }
    }

    fun getRegisteredChildIds(): Set<Long> = childEmbeddings.keys.toSet()

    fun isChildRegistered(childId: Long): Boolean = childEmbeddings.containsKey(childId)

    // ─── Bitmap processing (delegated to OnnxFaceModel) ─────────

    fun alignAndCropForEnrollment(
        bitmap: Bitmap,
        leftEye: LandmarkPoint,
        rightEye: LandmarkPoint
    ): Bitmap {
        return onnxModel.alignAndCrop(bitmap, leftEye, rightEye)
    }

    fun alignAndCropForEnrollment(
        bitmap: Bitmap,
        leftEye: SeetaPointF,
        rightEye: SeetaPointF
    ): Bitmap {
        return onnxModel.alignAndCrop(bitmap, leftEye, rightEye)
    }

    // ─── Reference bitmap storage ─────────────────────────────────────────

    suspend fun saveReferenceBitmap(
        childId: Long,
        bitmap: Bitmap,
        landmarks: Array<LandmarkPoint>
    ): String? = withContext(Dispatchers.IO) {
        try {
            val refDir = File(context.filesDir, REFERENCE_DIR)
            if (!refDir.exists()) refDir.mkdirs()
            val bitmapFile = File(refDir, "child_${childId}_ref.png")
            FileOutputStream(bitmapFile).use { fos -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos) }
            bitmapFile.absolutePath
        } catch (e: Exception) { null }
    }

    fun saveReferenceBitmapSync(childId: Long, bitmap: Bitmap): String? {
        return try {
            val refDir = File(context.filesDir, REFERENCE_DIR)
            if (!refDir.exists()) refDir.mkdirs()
            val bitmapFile = File(refDir, "child_${childId}_ref.png")
            FileOutputStream(bitmapFile).use { fos -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos) }
            DebugLog.log(TAG, "saveReferenceBitmapSync: childId=$childId")
            bitmapFile.absolutePath
        } catch (e: Exception) { null }
    }

    fun saveLandmarksSync(childId: Long, landmarks: Array<LandmarkPoint>): String? {
        return try {
            val refDir = File(context.filesDir, REFERENCE_DIR)
            if (!refDir.exists()) refDir.mkdirs()
            val file = File(refDir, "child_${childId}_landmarks.txt")
            file.writeText(landmarks.joinToString("\n") { "${it.x},${it.y}" })
            DebugLog.log(TAG, "saveLandmarksSync: childId=$childId")
            file.absolutePath
        } catch (e: Exception) { null }
    }

    fun loadReferenceBitmap(path: String): Bitmap? {
        return try { android.graphics.BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
    }

    /** Center-crop to INPUT_SIZE and scale. Returns null on failure. */
    private fun cropCenteredToInputSize(bitmap: Bitmap): Bitmap? {
        return try {
            val bw = bitmap.width
            val bh = bitmap.height
            val target = INPUT_SIZE
            val cropSize = minOf(bw, bh).coerceAtLeast(1)
            val x = ((bw - cropSize) / 2f).toInt().coerceAtLeast(0)
            val y = ((bh - cropSize) / 2f).toInt().coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(bitmap, x, y, cropSize, cropSize)
            val scaled = Bitmap.createScaledBitmap(cropped, target, target, true)
            if (scaled !== cropped) cropped.recycle()
            scaled
        } catch (e: Exception) {
            DebugLog.log(TAG, "cropCenteredToInputSize FAILED: ${e.message}")
            null
        }
    }

    fun loadLandmarks(path: String): Array<LandmarkPoint>? {
        return try {
            File(path).readLines().map { line ->
                val parts = line.split(",")
                LandmarkPoint(parts[0].toDouble(), parts[1].toDouble())
            }.toTypedArray()
        } catch (e: Exception) { null }
    }

    fun deleteReferenceData(childId: Long) {
        try {
            val refDir = File(context.filesDir, REFERENCE_DIR)
            File(refDir, "child_${childId}_ref.png").delete()
            File(refDir, "child_${childId}_landmarks.txt").delete()
        } catch (e: Exception) {}
    }

    fun close() {
        childEmbeddings.clear()
        childReferences.clear()
        try { onnxModel.close() } catch (_: Exception) {}
        modelName = "None"
    }

    companion object {
        private const val TAG = "OnnxEmbedding"
        private const val REFERENCE_DIR = "onnx_refs"
    }
}

/**
 * Simple 2D point for landmark storage.
 */
data class LandmarkPoint(val x: Double, val y: Double)

/**
 * Reference face information stored in Room DB.
 */
data class ReferenceFaceInfo(
    val bitmapPath: String,
    val landmarks: Array<LandmarkPoint>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ReferenceFaceInfo
        return bitmapPath == other.bitmapPath && landmarks.contentEquals(other.landmarks)
    }

    override fun hashCode(): Int {
        var result = bitmapPath.hashCode()
        result = 31 * result + landmarks.contentHashCode()
        return result
    }
}
