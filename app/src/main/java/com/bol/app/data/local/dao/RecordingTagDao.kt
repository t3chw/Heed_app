package com.bol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bol.app.data.local.entity.RecordingEntity
import com.bol.app.data.local.entity.RecordingTagEntity

@Dao
interface RecordingTagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<RecordingTagEntity>)

    @Query("""
        SELECT DISTINCT r.* FROM recordings r
        INNER JOIN recording_tags rt ON r.id = rt.recordingId
        WHERE rt.tag LIKE '%' || :name || '%'
        ORDER BY r.createdAt DESC
        LIMIT :limit
    """)
    suspend fun searchRecordingsByTag(name: String, limit: Int = 10): List<RecordingEntity>

    @Query("SELECT DISTINCT tag FROM recording_tags WHERE tagType = :type ORDER BY tag ASC")
    suspend fun getAllTagsByType(type: String): List<String>

    /** Every recording-tag row — used by backup. */
    @Query("SELECT * FROM recording_tags ORDER BY id ASC")
    suspend fun getAllSnapshot(): List<RecordingTagEntity>
}
