package com.kidguard.face

import com.kidguard.data.db.ChildDao
import com.kidguard.data.model.Child
import com.kidguard.util.DebugLog
import kotlinx.coroutines.flow.firstOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages enrolled children and their reference face data.
 *
 * With SeetaFace2, the actual face matching is done by FaceEmbeddingModel
 * using FaceRecognizer2's in-memory database. FaceRegistry provides:
 * - Child name lookup by ID
 * - Loading reference face data from Room DB
 * - Persistence for reference bitmap paths
 *
 * NOTE: FaceRegistry no longer does cosine-similarity matching.
 * That logic moved to FaceEmbeddingModel.matchFace().
 */
@Singleton
class FaceRegistry @Inject constructor(
    private val childDao: ChildDao
) {
    /** childId → child name */
    private val childNames = ConcurrentHashMap<Long, String>()

    /** childId → reference face info (bitmap path + landmarks) */
    private val referenceFaces = ConcurrentHashMap<Long, ReferenceFaceInfo>()

    @Volatile private var matchThreshold = DEFAULT_MATCH_THRESHOLD

    /**
     * Load all active children from DB.
     * After loading, the caller should register these with FaceEmbeddingModel.
     */
    suspend fun loadFaces() {
        DebugLog.log(TAG, ">>> loadFaces() START")
        val newNames = mutableMapOf<Long, String>()
        val newRefs = mutableMapOf<Long, ReferenceFaceInfo>()
        val children = childDao.getAllActive().firstOrNull() ?: emptyList()
        DebugLog.log(TAG, "  loadFaces: got ${children.size} active children from DB")

        children.forEach { child ->
            newNames[child.id] = child.name
            if (child.referencePath != null && child.landmarksPath != null) {
                newRefs[child.id] = ReferenceFaceInfo(
                    bitmapPath = child.referencePath,
                    landmarks = loadLandmarksFromString(child.landmarksPath)
                )
                DebugLog.log(TAG, "    -> childId=${child.id}, name='${child.name}', ref=${child.referencePath}")
            }
        }

        childNames.clear()
        childNames.putAll(newNames)
        referenceFaces.clear()
        referenceFaces.putAll(newRefs)

        DebugLog.log(TAG, "<<< loadFaces() END: ${childNames.size} children, ${referenceFaces.size} with reference data")
    }

    private fun loadLandmarksFromString(path: String): Array<LandmarkPoint> {
        return try {
            java.io.File(path).readLines().map { line ->
                val parts = line.split(",")
                LandmarkPoint(parts[0].toDouble(), parts[1].toDouble())
            }.toTypedArray()
        } catch (e: Exception) {
            emptyArray()
        }
    }

    fun saveReferenceData(
        childId: Long,
        name: String,
        refBitmapPath: String,
        landmarksPath: String
    ) {
        childNames[childId] = name
        referenceFaces[childId] = ReferenceFaceInfo(
            bitmapPath = refBitmapPath,
            landmarks = loadLandmarksFromString(landmarksPath)
        )
        // Also persist to FaceEmbeddingModel's memory map if ONNX is loaded
        // This ensures the embedding is picked up before the next periodic reload
        DebugLog.log(TAG, "Saved reference data for childId=$childId, name=$name, ref=$refBitmapPath")
    }

    fun unregisterFace(childId: Long) {
        childNames.remove(childId)
        referenceFaces.remove(childId)
        DebugLog.log(TAG, "Unregistered childId=$childId")
    }

    fun getChildName(childId: Long): String? = childNames[childId]

    fun getAllReferenceFaces(): Map<Long, ReferenceFaceInfo> = HashMap(referenceFaces)

    fun getReferenceFace(childId: Long): ReferenceFaceInfo? = referenceFaces[childId]

    fun getRegisteredChildIds(): Set<Long> = childNames.keys.toSet()

    fun isAnyChildEnrolled(): Boolean = referenceFaces.isNotEmpty()

    fun setSensitivity(sensitivity: Float) {
        // sensitivity: 0.0 (loose) → 1.0 (strict)
        // threshold: 0.8 (easy match) → 0.5 (hard match)
        // Higher sensitivity = lower threshold = harder to trigger lock
        matchThreshold = 0.8f - (sensitivity * 0.3f)
    }

    fun getMatchThreshold(): Float = matchThreshold

    companion object {
        private const val TAG = "FaceRegistry"
        private const val DEFAULT_MATCH_THRESHOLD = 0.6f
    }
}
