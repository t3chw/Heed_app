package com.bol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bol.app.data.local.entity.PerformanceLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PerformanceLogDao {

    @Query("SELECT * FROM performance_logs ORDER BY timestamp DESC")
    fun getAll(): Flow<List<PerformanceLogEntity>>

    @Query("SELECT * FROM performance_logs WHERE category = :category ORDER BY timestamp DESC")
    fun getByCategory(category: String): Flow<List<PerformanceLogEntity>>

    @Query("SELECT * FROM performance_logs WHERE level = :level ORDER BY timestamp DESC")
    fun getByLevel(level: String): Flow<List<PerformanceLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PerformanceLogEntity)

    @Query("SELECT COUNT(*) FROM performance_logs")
    suspend fun getCount(): Int

    @Query("DELETE FROM performance_logs WHERE id NOT IN (SELECT id FROM performance_logs ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun deleteOldest(keepCount: Int)

    @Query("DELETE FROM performance_logs WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    @Query("DELETE FROM performance_logs")
    suspend fun deleteAll()

    @Query("SELECT * FROM performance_logs ORDER BY timestamp DESC")
    suspend fun getAllSnapshot(): List<PerformanceLogEntity>

    /**
     * Latest inference-failure event (if any) newer than [since]. Used by
     * MainActivity to surface a Switch-backend dialog across processes —
     * the :ai process logs `inference_failure_reported`, Room's
     * multi-instance invalidation pushes the row to the main process, and
     * this Flow fires so the UI can react without an IPC channel.
     */
    @Query("SELECT * FROM performance_logs WHERE event IN ('inference_failure_reported', 'model_load_backend_failed') AND timestamp > :since ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestInferenceFailure(since: Long): Flow<PerformanceLogEntity?>
}
