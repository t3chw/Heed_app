package com.bol.app.domain.usecase

import android.util.Log
import com.bol.app.ai.model.ModelManager
import com.bol.app.ai.parser.IntentParser
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import com.bol.app.data.local.entity.LlmUpgradeEntity
import com.bol.app.data.repository.ItemRepository
import com.bol.app.data.repository.LlmUpgradeRepository

/**
 * Processes LLM upgrade entries: deletes provisional rule-based items and re-categorizes
 * the same raw text using the LLM for higher-quality results.
 *
 * Memory management is intentionally NOT done here — the caller (BolProcessingService)
 * checks available RAM before invoking this class, since it runs in the :ai process
 * where the model may already be loaded from voice processing.
 */
class LlmUpgradeUseCase(
    private val intentParser: IntentParser,
    private val processVoiceUseCase: ProcessVoiceUseCase,
    private val itemRepository: ItemRepository,
    private val llmUpgradeRepository: LlmUpgradeRepository,
    private val modelManager: ModelManager
) {
    suspend fun processEntry(entry: LlmUpgradeEntity) {
        llmUpgradeRepository.markProcessing(entry.id)
        BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.UPGRADE, "upgrade_start",
            "Starting LLM upgrade for recording ${entry.recordingId.take(8)} — will delete provisional rule-based items and re-categorize with LLM for better accuracy",
            extras = mapOf("entryId" to entry.id.take(8), "textLength" to entry.rawText.length.toString()))
        try {
            // The note was processed again (Retry, edited transcript) and already has the model's tasks: this
            // upgrade is stale, and running it would add a second set.
            if (itemRepository.countFinalByRecordingId(entry.recordingId) > 0) {
                llmUpgradeRepository.markCompleted(entry.id)
                BolLogger.log(LogLevel.INFO, LogCategory.UPGRADE, "upgrade_stale",
                    "LLM upgrade for recording ${entry.recordingId.take(8)} skipped — it already has the model's tasks")
                return
            }

            // Parse FIRST — if LLM crashes, provisional items remain intact
            val parseStart = System.currentTimeMillis()
            val result = intentParser.parse(entry.rawText)
            val parseDuration = System.currentTimeMillis() - parseStart

            // A model that finds no tasks would otherwise replace the saved items with nothing. Decoding is
            // greedy, so a retry gives the same answer: keep the provisional items and close the entry.
            if (result.items.isEmpty()) {
                llmUpgradeRepository.markCompleted(entry.id)
                BolLogger.log(LogLevel.INFO, LogCategory.UPGRADE, "upgrade_kept_provisional",
                    "LLM upgrade for recording ${entry.recordingId.take(8)} found no tasks — kept the provisional items",
                    durationMs = parseDuration)
                return
            }

            // Only delete old provisional items after LLM succeeds
            itemRepository.deleteProvisionalByRecordingId(entry.recordingId)
            val savedItems = processVoiceUseCase.saveResults(result, existingRecordingId = entry.recordingId)

            // Mark the upgrade COMPLETED before running the model enhancements
            // so a crash in tag/enrich can't cause the entry to retry and
            // re-save items.
            llmUpgradeRepository.markCompleted(entry.id)
            BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.UPGRADE, "upgrade_success",
                "LLM upgrade complete for recording ${entry.recordingId.take(8)} — replaced provisional items with ${result.items.size} LLM-categorized item(s) in ${parseDuration}ms",
                durationMs = parseDuration,
                extras = mapOf("itemCount" to result.items.size.toString()))
            Log.d(TAG, "LLM upgrade complete for ${entry.recordingId}: ${result.items.size} items")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Unload the crashed engine and flag backend for flip — without this the engine stays
            // in a bad state and every remaining upgrade entry in this batch will also fail.
            val nextRetryCount = entry.retryCount + 1
            val willExpire = nextRetryCount >= MAX_RETRIES
            BolLogger.logWithMetrics(LogLevel.WARN, LogCategory.UPGRADE, "upgrade_fail_retrying",
                "LLM upgrade failed for recording ${entry.recordingId.take(8)} — ${e.javaClass.simpleName}: ${e.message?.take(150)}. " +
                    if (willExpire) "This was attempt $nextRetryCount of $MAX_RETRIES — entry will be expired on next cleanup. Provisional items will remain as-is permanently."
                    else "Retry $nextRetryCount of $MAX_RETRIES — will try again next time the app opens.",
                extras = mapOf(
                    "recordingId" to entry.recordingId.take(8),
                    "retryCount" to nextRetryCount.toString(),
                    "willExpire" to willExpire.toString()
                ))
            Log.w(TAG, "LLM upgrade failed for ${entry.recordingId} (retry $nextRetryCount/$MAX_RETRIES)", e)
            llmUpgradeRepository.incrementRetryAndReset(entry.id)
        }
    }

    suspend fun resetStuck() = llmUpgradeRepository.resetStuckProcessing()

    suspend fun expireStaleEntries() = llmUpgradeRepository.expireStaleEntries()

    suspend fun cleanupOld() = llmUpgradeRepository.cleanupOld()

    companion object {
        private const val TAG = "LlmUpgrade"
        // After this many failed attempts the entry is expired by expireStaleEntries().
        // Must match the maxRetries default in LlmUpgradeRepository.expireStaleEntries().
        const val MAX_RETRIES = 8
    }
}
