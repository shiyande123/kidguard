package com.kidguard.ui.children

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidguard.data.db.ChildDao
import com.kidguard.data.model.Child
import com.kidguard.face.FaceEmbeddingModel
import com.kidguard.face.FaceRegistry
import com.kidguard.util.DebugLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChildrenViewModel @Inject constructor(
    private val childDao: ChildDao,
    private val faceRegistry: FaceRegistry,
    private val faceEmbeddingModel: FaceEmbeddingModel,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val children: StateFlow<List<Child>> = childDao.getAllActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addChild(name: String, age: Int) {
        viewModelScope.launch {
            val id = childDao.insert(Child(name = name, age = age))
            DebugLog.log("ChildVM", "Added child: $name, id=$id")
        }
    }

    fun deleteChild(child: Child) {
        viewModelScope.launch {
            // 清理人脸相关数据：内存中的 embedding、注册信息，以及磁盘上的参考图片和 landmarks 文件
            faceEmbeddingModel.deleteReferenceData(child.id)
            faceEmbeddingModel.unregisterChild(child.id)
            faceRegistry.unregisterFace(child.id)
            DebugLog.log("ChildVM", "Deleted face data for childId=${child.id}")

            // 通知监控服务重新加载人脸
            try {
                val broadcastIntent = android.content.Intent(com.kidguard.service.MonitoringService.ACTION_FACE_ENROLLED)
                context.sendBroadcast(broadcastIntent)
            } catch (_: Exception) {}

            childDao.delete(child)
            DebugLog.log("ChildVM", "Deleted child from DB: id=${child.id}, name=${child.name}")
        }
    }

    fun updateChild(child: Child, newName: String, newAge: Int) {
        viewModelScope.launch {
            childDao.update(child.copy(name = newName, age = newAge))
            DebugLog.log("ChildVM", "Updated child: id=${child.id}, name=$newName, age=$newAge")

            // 如果有人脸数据，更新注册表中的名字
            if (child.faceEmbedding != null) {
                // SeetaFace2: name update only (embedding-based registry no longer used)
                try {
                    val broadcastIntent = android.content.Intent(com.kidguard.service.MonitoringService.ACTION_FACE_ENROLLED)
                    context.sendBroadcast(broadcastIntent)
                } catch (_: Exception) {}
            }
        }
    }

    fun saveFaceEmbedding(childId: Long, embedding: FloatArray) {
        viewModelScope.launch {
            try {
                DebugLog.log("ChildVM", ">>> saveFaceEmbedding START: childId=$childId, features=${embedding.size}")
                DebugLog.log("ChildVM", "  embedding[0..4]=${embedding.take(5)}")

                // Convert FloatArray to comma-separated string ourselves
                // Use the same format as Converters.fromFloatArray for consistency
                val embeddingStr = embedding.joinToString(",") { String.format(java.util.Locale.US, "%.6f", it) }
                DebugLog.log("ChildVM", "  embeddingStr length=${embeddingStr.length}, first100=${embeddingStr.take(100)}")

                // Call the SQL-based update directly
                childDao.updateFaceEmbeddingStr(childId, embeddingStr)
                DebugLog.log("ChildVM", "  DB update done via updateFaceEmbeddingStr")

                // Verify by reading back
                val child = childDao.getById(childId)
                val hasData = child?.faceEmbedding != null
                val dbEmbeddingSize = child?.faceEmbedding?.size ?: 0
                DebugLog.log("ChildVM", "  Verify read-back: name=${child?.name}, hasEmbedding=$hasData, dbFeatures=$dbEmbeddingSize")
                if (child?.faceEmbedding != null) {
                    DebugLog.log("ChildVM", "  dbEmbedding[0..4]=${child.faceEmbedding!!.take(5)}")
                }

                if (child != null && hasData) {
                    // SeetaFace2: face embedding stored in DB, registry updated via saveFaceReference
                    DebugLog.log("ChildVM", "  Registry updated via SeetaFace2 path, total=${faceRegistry.getRegisteredChildIds().size}")

                    // 通知监控服务重新加载人脸
                    try {
                        val broadcastIntent = android.content.Intent(com.kidguard.service.MonitoringService.ACTION_FACE_ENROLLED)
                        context.sendBroadcast(broadcastIntent)
                        DebugLog.log("ChildVM", "  Face enrolled broadcast sent")
                    } catch (e: Exception) {
                        DebugLog.log("ChildVM", "  Broadcast failed: ${e.message}")
                    }
                } else {
                    DebugLog.log("ChildVM", "  WARNING: child is null or has no embedding after save!")
                }
                DebugLog.log("ChildVM", "<<< saveFaceEmbedding END SUCCESS")
            } catch (e: Exception) {
                DebugLog.log("ChildVM", "!!! SAVE FAILED: ${e.javaClass.simpleName}: ${e.message}")
                DebugLog.log("ChildVM", "  Stack: ${e.stackTrace.take(3).joinToString { it.toString() }}")
            }
        }
    }

    /**
     * SeetaFace2 人脸录入：保存 reference bitmap 和 landmarks 路径到 Room DB，
     * 并通知 FaceRegistry 重新加载注册表。
     */
    fun saveFaceReference(childId: Long, refPath: String, landmarksPath: String) {
        viewModelScope.launch {
            try {
                DebugLog.log("ChildVM", ">>> saveFaceReference START: childId=$childId, ref=$refPath, lm=$landmarksPath")

                childDao.updateFaceReferencePath(childId, refPath, landmarksPath)
                DebugLog.log("ChildVM", "  DB update done via updateFaceReferencePath")

                // 通知 FaceRegistry 重新加载
                faceRegistry.saveReferenceData(childId, "", refPath, landmarksPath)

                // 通知监控服务重新加载人脸
                try {
                    val broadcastIntent = android.content.Intent(com.kidguard.service.MonitoringService.ACTION_FACE_ENROLLED)
                    context.sendBroadcast(broadcastIntent)
                    DebugLog.log("ChildVM", "  Face enrolled broadcast sent")
                } catch (e: Exception) {
                    DebugLog.log("ChildVM", "  Broadcast failed: ${e.message}")
                }

                DebugLog.log("ChildVM", "<<< saveFaceReference END SUCCESS")
            } catch (e: Exception) {
                DebugLog.log("ChildVM", "!!! saveFaceReference FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
