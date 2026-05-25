package com.kidguard.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class Settings(
    @PrimaryKey
    val id: Int = 1,
    val isEnabled: Boolean = true,
    val dailyTimeLimit: Int = 120,
    val lockDelay: Int = 3,
    val sensitivity: Float = 0.7f,
    val workingHoursStart: Int = 8,
    val workingHoursEnd: Int = 22,
    val notifyParent: Boolean = true,
    val soundEnabled: Boolean = true
)
