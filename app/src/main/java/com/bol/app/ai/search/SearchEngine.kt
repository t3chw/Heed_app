package com.bol.app.ai.search

import com.bol.app.ai.model.ModelManager
import com.bol.app.ai.retrieval.GemmaEmbedder
import com.bol.app.ai.retrieval.Router
import com.bol.app.ai.retrieval.SearchIndex
import com.bol.app.ai.retrieval.SearchIndexer
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.local.entity.RecordingEntity
import com.bol.app.data.repository.ItemRepository
import com.bol.app.data.repository.RecordingRepository
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Search over tasks and voice notes with router v2 from the Heed study's search benchmark, as measured on a
 * phone: BM25 (FTS5) top 50 and EmbeddingGemma top 50, weighted reciprocal rank fusion (0.2 / 0.8, k = 10), then the
 * recorded-date rule ("yesterday", "last week", "in May" ...). When the embedding model is unavailable, BM25 and the
 * date rule alone.
 */
class SearchEngine(
    private val itemRepository: ItemRepository,
    private val recordingRepository: RecordingRepository,
    private val searchIndexer: SearchIndexer,
    private val modelManager: ModelManager,
) {
    /** Tasks and notes for [query], best first (up to [Router.TOP_N] in all). */
    suspend fun search(query: String): SearchContext {
        val tStart = System.nanoTime()
        val inlined = try {
            searchIndexer.embedIfFew()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BolLogger.log(LogLevel.WARN, LogCategory.SEARCH, "search_inline_embed_failed", "${e.javaClass.simpleName}: ${e.message?.take(150)}")
            0
        }
        val t0 = System.nanoTime()
        val index = searchIndexer.index
        val bm = withContext(Dispatchers.IO) { Router.ftsMatch(query)?.let { index.bm25(it) } ?: emptyList() }
        val t1 = System.nanoTime()
        val qVec = queryVector(query, index)
        val t2 = System.nanoTime()
        val (de, lib) = withContext(Dispatchers.IO) { (qVec?.let { index.dense(it, GemmaEmbedder.VERSION) } ?: emptyList()) to index.lib(GemmaEmbedder.VERSION) }
        val t3 = System.nanoTime()
        val ranked = Router.route(query, LocalDate.now(), bm, de, lib)
        val t4 = System.nanoTime()

        val taskIds = ranked.filter { it.startsWith(SearchIndexer.TASK_PREFIX) }.map { it.removePrefix(SearchIndexer.TASK_PREFIX) }
        val noteIds = ranked.filter { it.startsWith(SearchIndexer.NOTE_PREFIX) }.map { it.removePrefix(SearchIndexer.NOTE_PREFIX) }
        val items = itemRepository.getByIds(taskIds).associateBy { it.id }.let { byId -> taskIds.mapNotNull(byId::get) }
        val recordings = recordingRepository.getByIds(noteIds).associateBy { it.id }.let { byId -> noteIds.mapNotNull(byId::get) }

        fun ms(a: Long, b: Long) = "%.1f".format((b - a) / 1e6)
        BolLogger.log(LogLevel.INFO, LogCategory.SEARCH, "search_router",
            "Router v2: ${items.size} tasks + ${recordings.size} notes in ${ms(t0, t4)}ms " +
                "(bm25 ${ms(t0, t1)}, query embed ${ms(t1, t2)}, dense ${ms(t2, t3)}, fusion ${ms(t3, t4)}; ${bm.size} bm25, ${de.size} dense hits)" +
                (if (inlined > 0) "; first embedded $inlined waiting docs in ${ms(tStart, t0)}ms" else ""),
            durationMs = (t4 - t0) / 1_000_000,
            extras = mapOf("total_ms" to ms(t0, t4), "inline_ms" to ms(tStart, t0), "bm25_ms" to ms(t0, t1), "embed_ms" to ms(t1, t2), "dense_ms" to ms(t2, t3),
                "fusion_ms" to ms(t3, t4), "dense" to (qVec != null).toString(), "window" to (Router.window(query, LocalDate.now()) != null).toString()))
        return SearchContext(items, recordings)
    }

    /** The query's vector, or null for BM25 alone (no vectors stored yet, or the model failed). */
    private suspend fun queryVector(query: String, index: SearchIndex): FloatArray? {
        if (withContext(Dispatchers.IO) { index.countVectors(GemmaEmbedder.VERSION) } == 0) return null
        return try {
            modelManager.embed(listOf(query), isQuery = true).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BolLogger.log(LogLevel.WARN, LogCategory.SEARCH, "search_dense_unavailable",
                "Query embedding failed, searching with BM25 and the date rule only — ${e.javaClass.simpleName}: ${e.message?.take(150)}")
            null
        }
    }
}

data class SearchContext(
    val items: List<ItemEntity>,
    val recordings: List<RecordingEntity>,
) {
    fun isEmpty(): Boolean = items.isEmpty() && recordings.isEmpty()
}
