package com.kidguard.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kidguard.data.model.Child
import kotlinx.coroutines.flow.Flow

@Dao
interface ChildDao {

    @Query("SELECT * FROM children WHERE isActive = 1 ORDER BY name ASC")
    fun getAllActive(): Flow<List<Child>>

    @Query("SELECT * FROM children WHERE id = :id")
    suspend fun getById(id: Long): Child?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(child: Child): Long

    @Update
    suspend fun update(child: Child)

    @Delete
    suspend fun delete(child: Child)

    /**
     * 直接用 SQL 存储 embedding 字符串，不走 TypeConverter。
     * embedding 格式: "0.123,0.456,0.789,..."
     */
    @Query("UPDATE children SET faceEmbedding = :embeddingStr WHERE id = :childId")
    suspend fun updateFaceEmbeddingStr(childId: Long, embeddingStr: String?)

    /**
     * 更新 SeetaFace2 人脸参考图片和特征点文件路径。
     */
    @Query("UPDATE children SET referencePath = :refPath, landmarksPath = :lmPath WHERE id = :childId")
    suspend fun updateFaceReferencePath(childId: Long, refPath: String?, lmPath: String?)

}
