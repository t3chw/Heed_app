package com.bol.app.domain.usecase

import android.content.Context
import android.util.Log
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import com.bol.app.ai.audio.AudioChunker
import com.bol.app.ai.audio.TranscriptJoiner
import com.bol.app.ai.audio.WavConverter
import com.bol.app.ai.model.EngineInitTimeoutException
import com.bol.app.ai.model.InferencePreemptedException
import com.bol.app.ai.model.InferenceTimeoutException
import com.bol.app.ai.model.ModelManager
import com.bol.app.ai.model.ModelState
import com.bol.app.ai.stt.NonSpeechDetector
import com.bol.app.ai.parser.IntentParser
import com.bol.app.ai.parser.RuleBasedIntentParser
import com.bol.app.data.local.entity.VoiceQueueEntity
import com.bol.app.data.repository.ItemRepository
import com.bol.app.data.repository.LlmUpgradeRepository
import com.bol.app.data.repository.RecordingRepository
import com.bol.app.data.repository.VoiceQueueRepository
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File

data class ProcessingResult(
    val itemCount: Int,
    val categorySummary: String
)

class QueueProcessingUseCase(
    private val intentParser: IntentParser,
    private val ruleBasedParser: RuleBasedIntentParser,
    private val processVoiceUseCase: ProcessVoiceUseCase,
    private val voiceQueueRepository: VoiceQueueRepository,
    private val modelManager: ModelManager,
    private val recordingRepository: RecordingRepository,
    private val llmUpgradeRepository: LlmUpgradeRepository,
    private val itemRepository: ItemRepository,
    private val context: Context
) {
    /**
     * Re-categorize a recording with a user-edited transcription. Used by
     * the "Edit transcription" affordance on the Recording Detail screen —
     * when Whisper mis-heard, the user can fix the text and we'll wipe the
     * old items and re-run categorization on the corrected wording.
     *
     * No queue row involved (this isn't queue-driven), no Whisper step
     * (text is already final), and old items are deleted before parse so
     * the result fully replaces them.
     *
     * Returns the result so the caller can show "Re-categorized: N items"
     * in the snackbar.
     */
    suspend fun reprocessText(recordingId: String, newText: String): ProcessingResult {
        val trimmed = newText.trim()
        if (trimmed.isBlank()) return ProcessingResult(0, "")

        BolLogger.log(
            LogLevel.INFO, LogCategory.QUEUE, "reprocess_start",
            "Re-categorizing recording ${recordingId.take(8)} with edited transcription (${trimmed.length} chars)",
        )

        // Persist the corrected text before anything else so a crash
        // during categorization doesn't leave the recording row showing
        // the old (wrong) Whisper output.
        recordingRepository.updateRawText(recordingId, trimmed)
        // An upgrade queued for the old text would later add tasks from it next to the new ones.
        llmUpgradeRepository.deleteByRecordingId(recordingId)

        // Parser selection mirrors [processEntry]: LLM if the model is
        // available and not in a permanent error state, else rule-based
        // (provisional → queued for LLM upgrade).
        val modelState = modelManager.state.value
        val modelAvailable = modelState !is ModelState.NotDownloaded &&
            !(modelState is ModelState.Error && !modelState.isRetryable)
        val canUseLlm = modelAvailable
        val parser: IntentParser = if (canUseLlm) intentParser else ruleBasedParser
        val isProvisional = !canUseLlm

        // Track whether we ended up using rule-based as a fallback so the
        // saved items are correctly flagged provisional and queued for a
        // later LLM upgrade — matches the failure handling in [processEntry].
        var llmFellBack = false
        val result = try {
            parser.parse(trimmed)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            BolLogger.logWithMetrics(
                LogLevel.ERROR, LogCategory.QUEUE, "reprocess_llm_fallback",
                "LLM threw during reprocess (${e.javaClass.simpleName}: ${e.message?.take(120)}) — falling back to rule-based",
            )
            llmFellBack = true
            ruleBasedParser.parse(trimmed)
        }

        val effectiveProvisional = isProvisional || llmFellBack
        // Wipe the items derived from the wrong transcription only now, right before their replacement is saved:
        // deleting them before the parse lost them for good if the app was killed during it.
        itemRepository.deleteByRecordingId(recordingId)
        val savedItems = processVoiceUseCase.saveResults(
            result, existingRecordingId = recordingId,
            isProvisional = effectiveProvisional,
        )

        if (effectiveProvisional) {
            // Re-sorted by IndexingCoordinator while the app is open (Home banner, or a search).
            llmUpgradeRepository.enqueue(recordingId, trimmed)
        }

        val categories = result.items
            .mapNotNull { it.categoryName ?: it.categoryId }
            .distinct()
            .joinToString(", ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

        BolLogger.log(
            LogLevel.INFO, LogCategory.QUEUE, "reprocess_complete",
            "Re-categorized recording ${recordingId.take(8)}: ${result.items.size} items, [$categories]",
        )

        return ProcessingResult(result.items.size, categories)
    }

    /** True if the user tapped Stop on the processing pill — the pipeline
     *  polls this at every long-running boundary and bails out cleanly. */
    private suspend fun isCancelled(entryId: String): Boolean {
        val fresh = voiceQueueRepository.getById(entryId) ?: return true
        return fresh.cancelRequested
    }

    /** Clean up anything we managed to persist before the cancel was
     *  observed, then return an empty result so the caller won't try to
     *  mark the queue row completed or enqueue an LLM upgrade. The
     *  recording row itself stays (cancelled=true was set by
     *  [VoiceQueueRepository.cancelAllActive]) so History can show it as
     *  retryable. The WAV file is intentionally NOT deleted — Retry
     *  re-runs Whisper on it. */
    private suspend fun abortDueToCancel(entry: VoiceQueueEntity): ProcessingResult {
        entry.recordingId?.let { itemRepository.deleteByRecordingId(it) }
        BolLogger.log(LogLevel.INFO, LogCategory.QUEUE, "queue_cancelled",
            "User cancelled voice entry ${entry.id.take(8)} — discarded partial work. Recording kept in History for retry.")
        return ProcessingResult(0, "")
    }

    /**
     * Phase B1: Transcribe every audio entry in [entries] via the task model's
     * native audio modality, sharing the SAME loaded engine that Phase B2
     * will use for categorization. No Whisper, no model swap.
     *
     * Per-entry behaviour mirrors the old [resolveText]:
     *  - `bypassStt=true` entries are skipped (text already final)
     *  - Entries with non-`[audio]` rawText are skipped (crash recovery: a
     *    previous run already transcribed them)
     *  - Missing WAV but stored transcript → reuse stored transcript
     *  - Missing WAV and no transcript → log + leave PENDING, will be picked
     *    up by retry-from-history
     *  - Successful transcript is written to BOTH `voice_queue.rawText` and
     *    `recordings.rawText` so a crash before the model's text pass is recoverable
     *  - Non-speech / empty transcript → recording deleted, entry marked
     *    FAILED so the drain loop skips it
     *
     * Returns the count of entries that ended up with usable text (used by
     * the service for notification text and metrics).
     */
    suspend fun transcribeAudioBatch(entries: List<VoiceQueueEntity>): Int {
        // Filter to entries that actually need transcription. Avoids any
        // engine work on a queue full of share-to-Bol text-only entries.
        val needsTranscription = entries.filter { entry ->
            if (entry.bypassStt) return@filter false
            if (entry.rawText != "[audio]" && entry.rawText.isNotBlank()) return@filter false
            val recordingId = entry.recordingId ?: return@filter false
            File(context.filesDir, "audio/$recordingId.wav").exists()
        }
        if (needsTranscription.isEmpty()) {
            // Crash-recovery cases (WAV missing but transcript stored) —
            // handle without engine work.
            for (entry in entries) {
                if (entry.bypassStt) continue
                if (entry.rawText != "[audio]") continue
                val recordingId = entry.recordingId ?: continue
                val wavFile = File(context.filesDir, "audio/$recordingId.wav")
                if (wavFile.exists()) continue
                val recording = recordingRepository.getById(recordingId)
                if (recording != null && recording.rawText.isNotBlank() && recording.rawText != "[audio]") {
                    BolLogger.log(LogLevel.INFO, LogCategory.QUEUE, "crash_recovery_text_restored",
                        "Crash recovery: WAV gone but recording ${recordingId.take(8)} has stored transcript — reusing")
                    voiceQueueRepository.updateRawText(entry.id, recording.rawText)
                }
            }
            return 0
        }

        // The models unpack from the APK on first use. If that failed (disk full), keep the
        // entries PENDING with their WAVs so the next service start can transcribe them.
        modelManager.checkModelStatus()
        if (!modelManager.isModelOnDisk()) {
            BolLogger.log(LogLevel.INFO, LogCategory.STT, "transcription_waiting_for_model",
                "Models not ready — ${needsTranscription.size} audio entry/entries stay queued")
            return 0
        }

        val batchStart = System.currentTimeMillis()
        BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.STT, "speech_batch_start",
            "Granite transcription for ${needsTranscription.size} entry/entries",
            extras = mapOf("batchSize" to needsTranscription.size.toString()))

        var transcribedCount = 0
        run {
            for (entry in needsTranscription) {
                if (isCancelled(entry.id)) {
                    BolLogger.log(LogLevel.INFO, LogCategory.STT, "speech_batch_skip_cancelled",
                        "Skipping entry ${entry.id.take(8)} in speech batch — user cancelled")
                    continue
                }

                val recordingId = entry.recordingId ?: continue
                val wavFile = File(context.filesDir, "audio/$recordingId.wav")
                if (!wavFile.exists()) continue  // pre-filtered, but defensive

                // NOTE: do NOT markProcessing here. The entry stays PENDING
                // until parseAndSaveEntry runs in Phase B2 — see the
                // historical comment for the full rationale; same invariants
                // hold under the the model-audio path.

                try {
                    val sttStart = System.currentTimeMillis()
                    val samples = WavConverter.wavToFloatArray(wavFile)
                    // Granite takes at most 30 s at a time, so long notes are split on silence.
                    val audioChunks = AudioChunker.split(samples, sampleRate = SAMPLE_RATE)
                    if (audioChunks.size > 1) {
                        BolLogger.log(LogLevel.INFO, LogCategory.STT, "audio_chunked",
                            "Recording ${recordingId.take(8)} split into ${audioChunks.size} chunks on silence",
                            extras = mapOf("chunkCount" to audioChunks.size.toString()))
                    }

                    val texts = modelManager.transcribe(audioChunks.map { it.samples })
                    val joined = TranscriptJoiner.join(texts, audioChunks.map { it.overlapsPrevious })
                    val sttDuration = System.currentTimeMillis() - sttStart

                    if (joined.isBlank() || NonSpeechDetector.isNonSpeech(joined)) {
                        val soundMs = AudioChunker.soundMs(samples, SAMPLE_RATE)
                        if (soundMs < SILENT_CLIP_MAX_SOUND_MS) {
                            // Genuinely silent (pocket / accidental tap): nothing to keep.
                            BolLogger.log(LogLevel.WARN, LogCategory.STT, "speech_non_speech_result",
                                "Silent recording ${recordingId.take(8)} (${soundMs}ms of sound) — deleting and marking entry FAILED")
                            recordingRepository.deleteById(recordingId)
                            wavFile.delete()
                            voiceQueueRepository.markFailed(entry.id, "No speech detected")
                        } else {
                            // Audible but no words came back (quiet speaker, noise, or a
                            // backend problem): never delete what the user said — keep
                            // the WAV and offer Retry.
                            BolLogger.log(LogLevel.WARN, LogCategory.STT, "speech_empty_for_audible_clip",
                                "No words for recording ${recordingId.take(8)} although it has ${soundMs}ms of sound ('${BolLogger.noteText(joined, 50)}') — keeping audio, offering Retry")
                            voiceQueueRepository.markFailed(entry.id, "No words recognised")
                            recordingRepository.setCancelled(recordingId, true)
                        }
                        continue
                    }

                    voiceQueueRepository.updateRawText(entry.id, joined)
                    recordingRepository.updateRawText(recordingId, joined)
                    transcribedCount++

                    BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.STT, "speech_transcription_complete",
                        "Granite transcription finished in ${sttDuration}ms — ${joined.length} chars across ${texts.size} chunk(s) for recording ${recordingId.take(8)}",
                        durationMs = sttDuration,
                        extras = mapOf("textLength" to joined.length.toString(),
                            "chunkCount" to texts.size.toString(),
                            "textPreview" to BolLogger.noteText(joined, 80)))
                    Log.d(TAG, "Transcription complete: '${BolLogger.noteText(joined, 60)}'")
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) {
                        // Don't markFailed on cancel — let cancelAllActive's CANCELLED status stand
                        throw e
                    }
                    if (!modelManager.isModelOnDisk()) {
                        // The model went away mid-batch (a model update started):
                        // not this note's fault. Leave it and the rest PENDING.
                        BolLogger.log(LogLevel.INFO, LogCategory.STT, "transcription_waiting_for_model",
                            "Model removed during the batch — leaving remaining audio entries queued")
                        break
                    }
                    // Timeouts, preemption by an unload and a hung engine init are
                    // transient: keep the note PENDING and retry on the next run.
                    // Only after MAX_TRANSCRIBE_ATTEMPTS (or a hard error) does it
                    // become FAILED — with the WAV kept and Retry offered.
                    val transient = e is InferenceTimeoutException ||
                        e is InferencePreemptedException ||
                        e is EngineInitTimeoutException
                    val attempt = entry.retryCount + 1
                    if (transient && attempt < MAX_TRANSCRIBE_ATTEMPTS) {
                        BolLogger.logWithMetrics(LogLevel.WARN, LogCategory.STT, "model_transcription_retry",
                            "Transcription attempt $attempt/$MAX_TRANSCRIBE_ATTEMPTS for recording ${recordingId.take(8)} failed " +
                                "(${e.javaClass.simpleName}) — keeping it queued for the next run",
                            extras = mapOf("recordingId" to recordingId.take(8)))
                        voiceQueueRepository.recordRetry(entry.id, "Transcription ${e.javaClass.simpleName}")
                    } else {
                        BolLogger.logWithMetrics(LogLevel.ERROR, LogCategory.STT, "model_transcription_failed",
                            "Speech transcription failed for recording ${recordingId.take(8)} (attempt $attempt) — " +
                                "${e.javaClass.simpleName}: ${e.message?.take(200)}. WAV kept; Retry offered.",
                            extras = mapOf("recordingId" to recordingId.take(8)))
                        voiceQueueRepository.markFailed(entry.id, "Speech transcription failed: ${e.javaClass.simpleName}")
                        recordingRepository.setCancelled(recordingId, true)
                    }
                }
            }
        }

        val batchDuration = System.currentTimeMillis() - batchStart
        BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.STT, "speech_batch_done",
            "speech batch finished in ${batchDuration}ms — transcribed $transcribedCount/${needsTranscription.size} entry/entries on a single shared engine",
            durationMs = batchDuration,
            extras = mapOf("transcribed" to transcribedCount.toString(),
                "attempted" to needsTranscription.size.toString()))

        return transcribedCount
    }

    /**
     * Phase B2: Parse one entry that already has its rawText set (either via
     * Whisper batch or because it was a `bypassStt` text-only entry). Caller
     * must have the model loaded — this method does NOT load/unload models.
     *
     * Differs from the legacy [processEntry] in two ways:
     *  - No Whisper step (transcription is now batched into Phase B1 by the
     *    service, so this entry's text is already in the DB by the time we
     *    get here)
     *  - No tag/enrich step — those are deferred to the end-of-drain
     *    backfill phase so all entries' items can be tagged + enriched in
     *    one the model session instead of N
     *
     * The voice_queue row is marked COMPLETED before this method returns,
     * matching the critical invariant from [processEntry] that any post-save
     * the model work must run only AFTER the row is in a terminal state — so a
     * SIGSEGV during tag/enrich can never duplicate items on restart.
     */
    suspend fun parseAndSaveEntry(entry: VoiceQueueEntity): ProcessingResult {
        if (isCancelled(entry.id)) return abortDueToCancel(entry)

        // Re-fetch to pick up rawText changes from Phase B1 (the in-memory
        // [entry] is a snapshot from before Whisper ran).
        val fresh = voiceQueueRepository.getById(entry.id) ?: return ProcessingResult(0, "")
        if (fresh.cancelRequested) return abortDueToCancel(fresh)
        if (fresh.status == "FAILED" || fresh.status == "COMPLETED" || fresh.status == "CANCELLED") {
            // Whisper batch already terminated this entry (non-speech, etc.)
            return ProcessingResult(0, "")
        }

        // Sanity-check the text BEFORE flipping to PROCESSING. An entry that
        // sneaked into the queue between this outer pass's B1 snapshot and
        // here will still have rawText="[audio]" — leave it PENDING so the
        // next outer pass's B1 transcribes it. Marking it PROCESSING would
        // hide it from the next B1's getPending() lookup.
        val joinedText = fresh.rawText.trim()
        if (joinedText == "[audio]") {
            BolLogger.log(LogLevel.INFO, LogCategory.QUEUE, "queue_audio_pending_transcription",
                "Entry ${fresh.id.take(8)} arrived after current Whisper batch — leaving PENDING for next outer pass")
            return ProcessingResult(0, "")
        }

        // A run killed between saving and markCompleted leaves the entry PENDING with its tasks already saved, and
        // the user may have ticked or edited them since. Close it rather than parse again, which added a second set.
        // (Retry clears a recording's tasks before it queues the note again, so this never skips a real retry.)
        fresh.recordingId?.let { rid ->
            val alreadySaved = itemRepository.countByRecordingId(rid)
            if (alreadySaved > 0) {
                BolLogger.log(LogLevel.INFO, LogCategory.QUEUE, "queue_entry_already_saved",
                    "Entry ${fresh.id.take(8)} already has its $alreadySaved item(s) — closing it without parsing again")
                voiceQueueRepository.markCompleted(fresh.id, alreadySaved)
                return ProcessingResult(alreadySaved, "")
            }
        }

        voiceQueueRepository.markProcessing(fresh.id)
        BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.QUEUE, "queue_entry_start",
            "Started processing voice queue entry ${fresh.id.take(8)} — input is ${fresh.rawText.length} characters (already transcribed)",
            extras = mapOf("textLength" to fresh.rawText.length.toString(),
                "hasAudio" to "false",
                "retryCount" to fresh.retryCount.toString()))

        var inferenceStarted = false
        /** Items saved by this run; -1 until the save has returned. */
        var savedCount = -1
        var savedProvisional = false

        try {
            // The non-speech check is for transcripts; typed or shared text such as "(call mom)" is kept.
            if (joinedText.isBlank() || (!fresh.bypassStt && NonSpeechDetector.isNonSpeech(joinedText))) {
                BolLogger.log(LogLevel.WARN, LogCategory.QUEUE, "queue_no_speech",
                    "No usable text for entry ${fresh.id.take(8)} — Whisper failed earlier or input was non-speech. Marking failed.")
                voiceQueueRepository.markFailed(fresh.id, "No speech detected")
                return ProcessingResult(0, "")
            }

            val modelState = modelManager.state.value
            val modelAvailable = modelState !is ModelState.NotDownloaded &&
                !(modelState is ModelState.Error && !modelState.isRetryable)
            val canUseLlm = modelAvailable
            val parser: IntentParser = if (canUseLlm) intentParser else ruleBasedParser
            val isProvisional = !canUseLlm

            BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.QUEUE, "parser_selection",
                if (canUseLlm)
                    "Using on-device LLM for intent parsing — model available, will attempt load"
                else
                    "Using rule-based parser (provisional) — model is not available (state: $modelState). Items will be re-processed by LLM later.",
                extras = mapOf(
                    "canUseLlm" to canUseLlm.toString(),
                    "isProvisional" to isProvisional.toString(),
                    "modelAvailable" to modelAvailable.toString()
                ))

            if (canUseLlm) inferenceStarted = true
            val parseStart = System.currentTimeMillis()

            // LLM parses can hang silently inside LiteRT-LM — sendMessageAsync
            // is called and onDone never fires. Stress-tested at ~15% rate on
            // Oppo CPH2717 / ColorOS (3 of 20 cold-path iterations, run on
            // 2026-04-29). Without a timeout the queue entry would sit in
            // PROCESSING forever and the user's voice note would be silently
            // lost. On timeout: force-unload the (likely stuck) engine and
            // fall back to the rule-based parser inline so the user still
            // gets a categorization. The item saves provisional and the
            // upgrade pipeline (LlmUpgradeUseCase) re-attempts later when
            // the engine is in a healthier state — same flow used when the
            // model is genuinely unavailable.
            val (result, hungFallback) = if (canUseLlm) {
                try {
                    Pair(withTimeout(LLM_PARSE_TIMEOUT_MS) { parser.parse(joinedText) }, false)
                } catch (e: TimeoutCancellationException) {
                    BolLogger.logWithMetrics(LogLevel.WARN, LogCategory.PARSER, "llm_parse_hung",
                        "LLM parse hung past ${LLM_PARSE_TIMEOUT_MS / 1000}s for entry ${fresh.id.take(8)} — " +
                            "force-unloading engine and falling back to rule-based parser. " +
                            "Item saves provisional; upgrade pipeline will retry.",
                        extras = mapOf(
                            "textLength" to joinedText.length.toString(),
                            "timeoutMs" to LLM_PARSE_TIMEOUT_MS.toString(),
                        ))
                    runCatching { modelManager.unloadModel() }
                    Pair(ruleBasedParser.parse(joinedText), true)
                }
            } else {
                Pair(parser.parse(joinedText), false)
            }
            if (isCancelled(fresh.id)) return abortDueToCancel(fresh)
            val parseDuration = System.currentTimeMillis() - parseStart
            val effectiveProvisional = isProvisional || hungFallback
            BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.QUEUE, "queue_parse_complete",
                "Parsing complete — extracted ${result.items.size} item(s) from voice input in ${parseDuration}ms",
                durationMs = parseDuration,
                extras = mapOf(
                    "itemCount" to result.items.size.toString(),
                    "parserType" to if (canUseLlm && !hungFallback) "LLM" else "rule-based",
                    "hungFallback" to hungFallback.toString(),
                ))

            val savedItems = processVoiceUseCase.saveResults(
                result, existingRecordingId = fresh.recordingId, isProvisional = effectiveProvisional,
            )
            savedCount = savedItems.size
            savedProvisional = effectiveProvisional
            if (isCancelled(fresh.id)) {
                fresh.recordingId?.let { itemRepository.deleteByRecordingId(it) }
                return abortDueToCancel(fresh)
            }
            voiceQueueRepository.markCompleted(fresh.id, savedItems.size)

            // Tag + enrich are NOT called here — they're batched at the end
            // of drain so all entries' items get tagged in one the model session
            // instead of N. Items are immediately searchable via FTS so the
            // user sees them in Home right away; semantic tags appear when
            // the backfill phase runs ~30-60 s later.

            if (effectiveProvisional && fresh.recordingId != null) {
                llmUpgradeRepository.enqueue(fresh.recordingId, joinedText)
                BolLogger.log(LogLevel.INFO, LogCategory.QUEUE, "provisional_items_queued_upgrade",
                    "Items saved as provisional (rule-based) — queued recording ${fresh.recordingId?.take(8)} for LLM re-processing later")
            }

            val categories = result.items
                .mapNotNull { it.categoryName ?: it.categoryId }
                .distinct()
                .joinToString(", ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

            return ProcessingResult(
                itemCount = result.items.size,
                categorySummary = categories,
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e

            // A preemption (another component unloaded the engine) is not a
            // backend fault — don't report it as one.
            if (inferenceStarted && e !is InferencePreemptedException) {
            }
            // The task model's items were already saved and something after it threw: keep them, don't add the
            // rule-based set next to them.
            if (savedCount >= 0) {
                BolLogger.log(LogLevel.WARN, LogCategory.QUEUE, "queue_after_save_exception",
                    "Entry ${fresh.id.take(8)}: ${e.javaClass.simpleName} after its $savedCount item(s) were saved — keeping them")
                runCatching { voiceQueueRepository.markCompleted(fresh.id, savedCount) }
                if (savedProvisional && fresh.recordingId != null) {
                    runCatching { llmUpgradeRepository.enqueue(fresh.recordingId, joinedText) }
                }
                return ProcessingResult(savedCount, "")
            }
            BolLogger.logWithMetrics(LogLevel.ERROR, LogCategory.QUEUE, "queue_llm_exception_fallback",
                "LLM threw an exception (${e.javaClass.simpleName}: ${e.message?.take(150)}) — falling back to rule-based parser to avoid data loss")

            // The save threw after inserting the items (tags, alarms): they are in place, so keep them.
            val partlySaved = fresh.recordingId?.let { runCatching { itemRepository.countByRecordingId(it) }.getOrDefault(0) } ?: 0
            if (partlySaved > 0) {
                runCatching { voiceQueueRepository.markCompleted(fresh.id, partlySaved) }
                return ProcessingResult(partlySaved, "")
            }

            val textForFallback = fresh.rawText.trim().takeIf { it.isNotBlank() } ?: ""
            return try {
                val fallbackResult = ruleBasedParser.parse(textForFallback)
                val fallbackItems = processVoiceUseCase.saveResults(
                    fallbackResult, existingRecordingId = fresh.recordingId, isProvisional = true,
                )
                voiceQueueRepository.markCompleted(fresh.id, fallbackItems.size)

                if (fresh.recordingId != null) {
                    llmUpgradeRepository.enqueue(fresh.recordingId, textForFallback)
                }

                val categories = fallbackResult.items
                    .mapNotNull { it.categoryName ?: it.categoryId }
                    .distinct()
                    .joinToString(", ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

                ProcessingResult(fallbackResult.items.size, categories)
            } catch (fallbackError: Exception) {
                if (fallbackError is kotlinx.coroutines.CancellationException) throw fallbackError
                BolLogger.logWithMetrics(LogLevel.ERROR, LogCategory.QUEUE, "queue_total_failure",
                    "Both LLM and rule-based parsers failed for entry ${fresh.id.take(8)} — ${fallbackError.javaClass.simpleName}: ${fallbackError.message?.take(150)}. Marking failed.")
                voiceQueueRepository.markFailed(fresh.id, fallbackError.message ?: "Unknown error")
                // Like a failed transcription: the note stays in History with Retry.
                fresh.recordingId?.let { recordingRepository.setCancelled(it, true) }
                ProcessingResult(0, "")
            }
        }
    }

    companion object {
        private const val TAG = "QueueProcessing"
        private const val SAMPLE_RATE = 16000
        /** Transient transcription failures before a note becomes FAILED (with Retry). */
        private const val MAX_TRANSCRIBE_ATTEMPTS = 3
        /** Below this much non-silent audio a clip counts as silent and is discarded. */
        private const val SILENT_CLIP_MAX_SOUND_MS = 300L

        /**
         * Hard ceiling on a single LLM parse call. Generous: typical
         * production parses are 30-50 s end-to-end (tool mode + JSON mode
         * + Pass 2 review), and the slowest measured on a tier-3 device is
         * ~50 s. Set to 120 s so a legitimately slow inference still
         * completes, but a hung [LiteRT-LM sendMessageAsync] (observed at
         * ~15% rate during stress testing on Oppo CPH2717) doesn't pin the
         * voice queue indefinitely. On timeout we fall back to the
         * rule-based parser inline so the user gets a categorization;
         * upgrade pipeline retries the LLM later.
         */
        private const val LLM_PARSE_TIMEOUT_MS = 120_000L
    }
}
