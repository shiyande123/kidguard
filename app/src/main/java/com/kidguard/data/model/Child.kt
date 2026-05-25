package com.kidguard.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "children")
data class Child(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val age: Int,
    val faceEmbedding: FloatArray? = null,  // Kept for compatibility during migration
    // SeetaFace2 reference: path to aligned face PNG in filesDir
    val referencePath: String? = null,
    // SeetaFace2 landmark file path (adjacent to referencePath, same name but .txt)
    val landmarksPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Child) return false
        return id == other.id &&
                name == other.name &&
                age == other.age &&
                faceEmbedding.contentEquals(other.faceEmbedding) &&
                referencePath == other.referencePath &&
                landmarksPath == other.landmarksPath &&
                createdAt == other.createdAt &&
                isActive == other.isActive
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + age
        result = 31 * result + (faceEmbedding?.contentHashCode() ?: 0)
        result = 31 * result + (referencePath?.hashCode() ?: 0)
        result = 31 * result + (landmarksPath?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + isActive.hashCode()
        return result
    }
}
