package com.bol.app.domain.usecase

import android.content.Context
import android.util.Log
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import com.bol.app.ai.audio.AudioChunker
import com.bol.app.ai.audio.TranscriptJoiner
import com.bol.app.ai.audio.WavConverter
import com.bol.app.ai.model.ModelManager
import com.bol.app.ai.search.SearchEngine
import com.bol.app.ai.stt.NonSpeechDetector
import com.bol.app.data.local.entity.SearchQueryEntity
import com.bol.app.data.local.entity.SearchStage
import com.bol.app.data.repository.SearchQueryRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class SearchResult(
    val answer: String,
    val sourcesJson: String
)

/**
 * Thrown from inside the pipeline when the user taps Stop. Walked out to
 * the top-level try/catch in [SearchProcessingUseCase.processQuery] so it
 * can be turned into a CANCELLED DB row. Not a kotlinx CancellationException
 * because callers use `runCatching` which would swallow that silently.
 */
private class UserStoppedException : Exception("User stopped search")

class SearchProcessingUseCase(
    private val modelManager: ModelManager,
    private val searchEngine: SearchEngine,
    private val searchQueryRepository: SearchQueryRepository,
    private val context: Context
) {
    suspend fun processQuery(entry: SearchQueryEntity): SearchResult? {
        searchQueryRepository.markProcessing(entry.id)

        try {
            // Step 1: Resolve query text (transcribe if audio).
            // TRANSCRIBE stage is emitted from inside resolveQuery only when
            // we actually need to hit Whisper — text queries skip straight
            // to RETRIEVE and never show a "Transcribe" row on the checklist.
            val queryText = resolveQuery(entry)

            if (queryText.isBlank() || NonSpeechDetector.isNonSpeech(queryText)) {
                BolLogger.log(LogLevel.WARN, LogCategory.SEARCH, "search_no_speech",
                    "Search query contained no recognizable speech — cannot perform search")
                searchQueryRepository.markFailed(entry.id, "No speech detected")
                return null
            }

            Log.d(TAG, "Searching for: $queryText")

            bailIfCancelled(entry.id)
            searchQueryRepository.updateStage(entry.id, SearchStage.RETRIEVE, "Searching notes")
            val searchStart = System.currentTimeMillis()
            val searchContext = searchEngine.search(queryText)
            val searchDuration = System.currentTimeMillis() - searchStart
            bailIfCancelled(entry.id)
            searchQueryRepository.updateStage(
                entry.id, SearchStage.RETRIEVE,
                "${searchContext.items.size} items, ${searchContext.recordings.size} recordings",
            )
            BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.SEARCH, "search_results",
                "Search completed in ${searchDuration}ms — ${searchContext.items.size} item(s) and ${searchContext.recordings.size} recording(s), best first",
                durationMs = searchDuration,
                extras = mapOf("items" to searchContext.items.size.toString(),
                    "recordings" to searchContext.recordings.size.toString(),
                    "query" to queryText.take(80)))

            Log.d(TAG, "Found ${searchContext.items.size} items, ${searchContext.recordings.size} recordings")

            if (searchContext.isEmpty()) {
                BolLogger.log(LogLevel.INFO, LogCategory.SEARCH, "search_no_results",
                    "No matching items or recordings found for query '${BolLogger.noteText(queryText, 60)}'")
                val noResultAnswer = NO_RESULTS
                searchQueryRepository.markCompleted(entry.id, noResultAnswer, "[]", rankedItemIdsJson = null)
                return SearchResult(noResultAnswer, "[]")
            }

            // Written answers are parked with the the model removal; the result is the ranked notes.
            val count = minOf(searchContext.items.size, MAX_ITEMS) + minOf(searchContext.recordings.size, MAX_SOURCES)
            val answer = "$count ${if (count == 1) "result" else "results"}, best match first."
            val sources = searchContext.recordings.take(MAX_SOURCES).map { rec ->
                mapOf(
                    "id" to rec.id,
                    "snippet" to rec.rawText.take(150),
                    "createdAt" to rec.createdAt.toString()
                )
            }
            val sourcesJson = Json.encodeToString(sources)
            // Persist the ranked item IDs so SearchDetailScreen can rebuild the list.
            val rankedIds = searchContext.items.take(MAX_ITEMS).map { it.id }
            val rankedJson = Json.encodeToString(rankedIds)

            searchQueryRepository.markCompleted(entry.id, answer, sourcesJson, rankedJson)

            return SearchResult(answer, sourcesJson)
        } catch (stop: UserStoppedException) {
            // User tapped Stop — distinct from a failure so we can show a
            // different UI state (CANCELLED) and offer Retry without the
            // error framing. Don't flip the GPU↔CPU backend.
            BolLogger.log(LogLevel.INFO, LogCategory.SEARCH, "search_cancelled",
                "Search cancelled by user at stage ${runCatching { searchQueryRepository.getById(entry.id)?.stage }.getOrNull()}")
            searchQueryRepository.markCancelled(entry.id)
            return null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Search processing failed", e)
            BolLogger.logWithMetrics(LogLevel.ERROR, LogCategory.SEARCH, "search_fail",
                "Search processing failed — ${e.javaClass.simpleName}: ${e.message?.take(150)}")
            searchQueryRepository.markFailed(entry.id, e.message ?: "Search failed")
            return null
        }
    }

    /**
     * Poll the cancel flag at a stage boundary. Throws [UserStoppedException]
     * when the user has requested a stop, caught by the top-level handler
     * which writes a CANCELLED row. Call this before / after any expensive
     * step so Stop is acknowledged within at most one step's worth of work.
     */
    private suspend fun bailIfCancelled(id: String) {
        if (searchQueryRepository.isCancelRequested(id)) throw UserStoppedException()
    }

    private suspend fun resolveQuery(entry: SearchQueryEntity): String {
        if (entry.query != "[audio]") return entry.query

        searchQueryRepository.updateStage(entry.id, SearchStage.TRANSCRIBE, "Listening")
        BolLogger.log(LogLevel.INFO, LogCategory.SEARCH, "search_audio_resolve",
            "Search query is audio — transcribing before searching")

        val wavFile = File(context.filesDir, "search_audio/${entry.id}.wav")
        if (!wavFile.exists()) {
            BolLogger.log(LogLevel.ERROR, LogCategory.SEARCH, "search_audio_missing",
                "Search audio file not found at expected path — cannot process this search query")
            searchQueryRepository.markFailed(entry.id, "Audio file not found")
            return ""
        }

        return try {
            val samples = WavConverter.wavToFloatArray(wavFile)
            val chunks = AudioChunker.split(samples, sampleRate = 16_000)
            val text = TranscriptJoiner.join(modelManager.transcribe(chunks.map { it.samples }), chunks.map { it.overlapsPrevious })
            if (text.isNotBlank() && !NonSpeechDetector.isNonSpeech(text)) {
                searchQueryRepository.updateQuery(entry.id, text)
            }
            // The database first, then the audio (the app's rule): deleting first left an "[audio]" row with no file
            // if the app was killed in between.
            wavFile.delete()
            text
        } catch (e: Exception) {
            // Cancelled (service stopped): keep the WAV so the row can be
            // picked up again; only real failures discard it.
            if (e is kotlinx.coroutines.CancellationException) throw e
            BolLogger.logWithMetrics(LogLevel.ERROR, LogCategory.STT, "search_transcription_failed",
                "Transcription failed for search query — ${e.javaClass.simpleName}: ${e.message?.take(150)}")
            wavFile.delete()
            ""
        }
    }

    companion object {
        private const val TAG = "SearchProcessing"
        /** Tasks and voice notes the result screen shows. */
        private const val MAX_ITEMS = 20
        private const val MAX_SOURCES = 5
        const val NO_RESULTS = "Nothing in your notes or tasks matched that."
        private val RESULT_COUNT = Regex("""\d+ results?, best match first\.""")
        /** The two lines a search stores now (a count, or no results): a summary for the list, not a written answer. */
        fun isResultSummary(answer: String): Boolean =
            answer == NO_RESULTS || answer == OLD_NO_RESULTS || RESULT_COUNT.matches(answer)
        /** The no-results line stored by builds before 4 Nov 2026. */
        private const val OLD_NO_RESULTS = "I don't have any information about that in your recordings or items."
    }
}
