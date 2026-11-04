package com.bol.app.data.repository

import com.bol.app.data.local.dao.SearchQueryDao
import com.bol.app.data.local.entity.SearchQueryEntity
import kotlinx.coroutines.flow.Flow

class SearchQueryRepository(private val dao: SearchQueryDao) {
    fun getAll(): Flow<List<SearchQueryEntity>> = dao.getAll()

    fun observeById(id: String): Flow<SearchQueryEntity?> = dao.observeById(id)

    suspend fun insert(query: SearchQueryEntity) = dao.insert(query)

    suspend fun getPending(): List<SearchQueryEntity> = dao.getPending()

    suspend fun markProcessing(id: String) = dao.markProcessing(id)

    suspend fun markCompleted(
        id: String,
        answer: String,
        sourcesJson: String,
        rankedItemIdsJson: String? = null,
        llmPickedCount: Int? = null,
    ) = dao.markCompleted(id, answer, sourcesJson, rankedItemIdsJson, llmPickedCount)

    suspend fun getById(id: String) = dao.getById(id)

    suspend fun markFailed(id: String, error: String) = dao.markFailed(id, error)

    suspend fun requestCancel(id: String) = dao.requestCancel(id)

    suspend fun markCancelled(id: String) = dao.markCancelled(id)

    suspend fun isCancelRequested(id: String): Boolean =
        dao.isCancelRequested(id) == true

    suspend fun updateQuery(id: String, query: String) = dao.updateQuery(id, query)

    suspend fun updateStage(id: String, stage: String?, stageDetail: String? = null) =
        dao.updateStage(id, stage, stageDetail)

    suspend fun updateStreamingAnswer(id: String, partial: String?) =
        dao.updateStreamingAnswer(id, partial)

    suspend fun cleanupOld() = dao.cleanupOld()

    /** Delete a single saved search row by id. */
    suspend fun deleteById(id: String) = dao.deleteById(id)
}
