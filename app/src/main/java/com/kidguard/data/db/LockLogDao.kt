package com.kidguard.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidguard.data.model.LockLog
import kotlinx.coroutines.flow.Flow

@Dao
interface LockLogDao {

    @Query("SELECT * FROM lock_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<LockLog>>

    @Query("SELECT * FROM lock_logs WHERE childId = :childId AND timestamp >= :since ORDER BY timestamp DESC")
    fun getByChildSince(childId: Long, since: Long): Flow<List<LockLog>>

    @Query("SELECT COALESCE(SUM(lockDuration), 0) FROM lock_logs WHERE timestamp >= :since")
    suspend fun getTotalLockTime(since: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(lockLog: LockLog): Long

    @Query("DELETE FROM lock_logs WHERE timestamp < :threshold")
    suspend fun deleteOlderThan(threshold: Long)
}
