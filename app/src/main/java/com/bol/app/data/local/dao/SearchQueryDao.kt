package com.bol.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bol.app.data.local.entity.SearchQueryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchQueryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(query: SearchQueryEntity)

    /** Bulk-insert used by backup restore. IGNORE so an id collision
     *  (user re-importing a backup that includes a query they ran
     *  again locally) preserves the local — fresher — row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(queries: List<SearchQueryEntity>)

    @Query("SELECT * FROM search_queries ORDER BY createdAt DESC")
    fun getAll(): Flow<List<SearchQueryEntity>>

    @Query("SELECT * FROM search_queries WHERE id = :id")
    suspend fun getById(id: String): SearchQueryEntity?

    @Query("SELECT * FROM search_queries WHERE id = :id")
    fun observeById(id: String): Flow<SearchQueryEntity?>

    @Query("SELECT * FROM search_queries WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPending(): List<SearchQueryEntity>

    @Query("UPDATE search_queries SET status = 'PROCESSING' WHERE id = :id")
    suspend fun markProcessing(id: String)

    @Query("UPDATE search_queries SET status = 'COMPLETED', answer = :answer, sourcesJson = :sourcesJson, rankedItemIdsJson = :rankedItemIdsJson, llmPickedCount = :llmPickedCount, stage = NULL, stageDetail = NULL, streamingAnswer = NULL, completedAt = :completedAt WHERE id = :id")
    suspend fun markCompleted(
        id: String,
        answer: String,
        sourcesJson: String,
        rankedItemIdsJson: String?,
        llmPickedCount: Int?,
        completedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE search_queries SET status = 'FAILED', errorMessage = :error WHERE id = :id")
    suspend fun markFailed(id: String, error: String)

    @Query("UPDATE search_queries SET cancelRequested = 1 WHERE id = :id")
    suspend fun requestCancel(id: String)

    @Query("UPDATE search_queries SET status = 'CANCELLED', stage = NULL, stageDetail = NULL, streamingAnswer = NULL, completedAt = :completedAt WHERE id = :id")
    suspend fun markCancelled(id: String, completedAt: Long = System.currentTimeMillis())

    @Query("SELECT cancelRequested FROM search_queries WHERE id = :id")
    suspend fun isCancelRequested(id: String): Boolean?

    @Query("UPDATE search_queries SET query = :query WHERE id = :id")
    suspend fun updateQuery(id: String, query: String)

    @Query("UPDATE search_queries SET stage = :stage, stageDetail = :stageDetail WHERE id = :id")
    suspend fun updateStage(id: String, stage: String?, stageDetail: String?)

    @Query("UPDATE search_queries SET streamingAnswer = :partial WHERE id = :id")
    suspend fun updateStreamingAnswer(id: String, partial: String?)

    @Query("DELETE FROM search_queries WHERE status = 'COMPLETED' AND createdAt < :before")
    suspend fun cleanupOld(before: Long = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)

    /** Hard-delete a single search row. Used by the long-press-to-delete
     *  flow on the Search history list. The undo path re-inserts the
     *  original entity via [insert]. */
    @Query("DELETE FROM search_queries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM search_queries")
    suspend fun deleteAll()

    /** Every search-history row — used by backup. */
    @Query("SELECT * FROM search_queries ORDER BY createdAt DESC")
    suspend fun getAllSnapshot(): List<SearchQueryEntity>
}
