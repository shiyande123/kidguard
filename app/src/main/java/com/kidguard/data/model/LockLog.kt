package com.kidguard.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lock_logs")
data class LockLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val childId: Long,
    val childName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val lockDuration: Int,
    val reason: String
)
