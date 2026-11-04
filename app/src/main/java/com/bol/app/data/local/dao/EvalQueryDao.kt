package com.bol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bol.app.data.local.entity.EvalQueryEntity

@Dao
interface EvalQueryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<EvalQueryEntity>)

    @Query("SELECT * FROM eval_queries WHERE generatorVersion = :version")
    suspend fun allForVersion(version: Int): List<EvalQueryEntity>

    @Query("SELECT COUNT(DISTINCT itemId) FROM eval_queries WHERE generatorVersion = :version")
    suspend fun distinctItemsForVersion(version: Int): Int

    @Query("SELECT COUNT(*) FROM eval_queries WHERE generatorVersion = :version")
    suspend fun countForVersion(version: Int): Int

    @Query("DELETE FROM eval_queries WHERE generatorVersion = :version")
    suspend fun deleteForVersion(version: Int)

    @Query("DELETE FROM eval_queries")
    suspend fun deleteAll()
}
