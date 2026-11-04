package com.bol.app.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import androidx.core.content.ContextCompat
import com.bol.app.ai.model.ModelManager
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.repository.SearchQueryRepository
import com.bol.app.data.repository.VoiceQueueRepository
import com.bol.app.domain.usecase.QueueProcessingUseCase
import com.bol.app.domain.usecase.SearchProcessingUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.inject

class BolProcessingService : Service() {

    private val voiceQueueRepository: VoiceQueueRepository by inject()
    private val recordingRepository: com.bol.app.data.repository.RecordingRepository by inject()
    private val queueProcessingUseCase: QueueProcessingUseCase by inject()
    private val searchQueryRepository: SearchQueryRepository by inject()
    private val searchProcessingUseCase: SearchProcessingUseCase by inject()
    private val modelManager: ModelManager by inject()
    private val preferencesManager: PreferencesManager by inject()
    private val indexingCoordinator: com.bol.app.ai.indexing.IndexingCoordinator by inject()
    private val searchIndexer: com.bol.app.ai.retrieval.SearchIndexer by inject()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    /** Set from the main thread (onStartCommand) and from a finishing run (stopOrRunAgain). */
    @Volatile private var processingJob: Job? = null
    private val lockScreenPerf: LockScreenPerf by inject()
    private val processingStage: com.bol.app.ai.processing.ProcessingStageTracker by inject()

    // Whether to render the foreground-service notification on the silent
    // IMPORTANCE_MIN channel. Mirrors the user's "Notify when work is done"
    // setting (off → quiet). Read once in onStartCommand; a Settings change
    // takes effect on the next service run, same as the completion-ping path.
    @Volatile private var quietMode: Boolean = false

    // De-duplicates updateNotification() / updateSearchNotification() calls.
    // Without this, every B2 iteration re-issues notify() with the same title
    // and the user sees the spinner re-animate / banner re-appear repeatedly.
    @Volatile private var lastNotificationTitle: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** Latest start id seen, for [stopOrRunAgain]'s stopSelfResult. */
    private val latestStartId = java.util.concurrent.atomic.AtomicInteger(0)
    /** A start request arrived while a drain was active. */
    private val rerunRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId.set(startId)
        if (intent?.action == ACTION_CANCEL) {
            cancelProcessing()
            return START_NOT_STICKY
        }

        // Backend selection is read directly from DataStore in ModelManager.loadEngine() —
        // there's no override channel and no per-Intent backend extra. DataStore reads are
        // cheap (~ms) and always reflect the latest user preference, so the older Intent-
        // override workaround for stale :ai-process caches isn't needed.

        // Resolve quiet vs verbose BEFORE startForeground — the channel choice
        // is baked into the notification builder. runBlocking is OK here:
        // DataStore reads are sub-ms once warm, and onStartCommand has a hard
        // 5 s window before the system kills the service for not calling
        // startForeground in time, which we comfortably stay inside.
        quietMode = !runBlocking { preferencesManager.notifyOnWorkDone.first() }
        lastNotificationTitle = null

        // Start foreground immediately (must pass service type on Android 14+)
        val notification = NotificationHelper.buildProcessingNotification(this, 0, quiet = quietMode)
        ServiceCompat.startForeground(
            this,
            NotificationHelper.PROCESSING_NOTIFICATION_ID,
            notification.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )

        // Start processing if not already running. The wake lock + perf hints
        // are acquired INSIDE the launched coroutine (not here) so that
        // PerformanceHintManager's TID list captures the Default-pool worker
        // thread that actually runs the model — not the main thread that runs
        // onStartCommand. Without this, the hint targets the wrong thread
        // and the kernel never bumps the right CPU. The wake lock part has
        // microsecond-level latency either way, so the trade is free.
        // Releases are in the launch's finally + onDestroy + cancelProcessing.
        if (processingJob?.isActive != true) {
            launchProcessing()
        } else {
            // A drain is running. It may already be past its last "anything
            // new?" check — remember to run again instead of stopping.
            rerunRequested.set(true)
        }

        return START_STICKY
    }

    /** Run [processQueue] under the wake lock + perf hints (see onStartCommand). */
    private fun launchProcessing() {
        processingJob = serviceScope.launch {
            lockScreenPerf.acquire()
            try {
                processQueue()
            } finally {
                // processQueue's own finally may have launched the next run (stopOrRunAgain), which found the lock
                // held and kept it. Releasing it here then left that run, often a locked-phone note, without the
                // wake lock or perf hint. Only the last run lets go.
                if (processingJob === coroutineContext[Job]) lockScreenPerf.release()
            }
        }
    }

    /**
     * Stop the service unless work arrived while we were finishing. A start
     * request that lands after the drain's last pending-check used to be
     * ignored (job still active) and then lost when we stopped — the note
     * waited until the app was next opened. [stopSelfResult] with the last
     * seen start id also refuses to stop if an even newer start came in.
     */
    private fun stopOrRunAgain(reason: String) {
        if (rerunRequested.getAndSet(false) || !stopSelfResult(latestStartId.get())) {
            BolLogger.log(LogLevel.INFO, LogCategory.QUEUE, "service_rerun",
                "New work arrived while finishing ($reason) — running the drain again instead of stopping")
            launchProcessing()
            return
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    private suspend fun processQueue() {
        val serviceStartTime = System.currentTimeMillis()

        // CRITICAL: yield the model cleanly from any in-flight deferred indexing
        // before we touch ModelManager. The indexer's job runs in a separate
        // scope (IndexingCoordinator) and may be mid-the model-call. We need
        // exclusive ownership of the engine for the voice drain (audio
        // transcription + categorization both run against it). stopAndAwait
        // cancels the indexing job and joins it — by the time we return,
        // any in-flight tag/enrich call has unwound and the engine is free
        // for the drain to take over. A run with only searches lets the
        // indexing go on: it uses the same embedding model, holds it for at
        // most one 16-doc batch, and yields as soon as a voice note arrives.
        if (voiceQueueRepository.getPending().isNotEmpty()) indexingCoordinator.stopAndAwait()

        modelManager.checkModelStatus()

        BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.QUEUE, "service_start",
            "Background processing service started — will drain voice queue, process search queries, then attempt LLM upgrades")

        voiceQueueRepository.resetStuckProcessing()

        var totalItems = 0
        val allCategories = mutableSetOf<String>()

        // Outer try/finally guarantees stopForeground + stopSelf run even if
        // the worker throws. Without this, a the model decode failure / OOM /
        // SIGSEGV in B2 left the service alive with the stale "Sorting…"
        // notification persisting forever — and because no fresh
        // onStartCommand fires when the user opens the app, the queue
        // stayed stuck until a process restart.
        try {
        try {
            // Outer service loop. Each pass runs the full pipeline:
            //   B1 — Speech transcription for every audio entry
            //   B2 — the model text categorization for every entry (same engine)
            //   B3 — search query processing (same engine)
            // All three phases run on a SINGLE loaded the model engine — audio
            // enabled by default per Phase 2 of the Whisper-removal
            // migration. No model swap between modalities; no per-entry
            // model thrashing. Items remain immediately searchable via FTS
            // as soon as B2 saves them; semantic tags + enrichment fill in
            // later via the IndexingCoordinator (foreground-only).
            //
            // Then we re-check for new work — a recording the user kicked
            // off while we were in B2 / B3 would otherwise be stranded,
            // since onStartCommand ignores fresh start intents while
            // processingJob is active. Capping the outer loop at
            // MAX_OUTER_PASSES bounds a pathological "every drain races a
            // new recording" loop; whatever we leave behind gets picked up
            // by the next service start.
            var outerPasses = 0
            outer@ while (outerPasses < MAX_OUTER_PASSES) {
                outerPasses++

                // ── Phase B1: BATCH WHISPER ──────────────────────────────
                // Snapshot pending entries, drop any over the retry ceiling,
                // run them all through ONE Whisper session, then release.
                val pending = voiceQueueRepository.getPending()
                    .filter { entry ->
                        if (entry.retryCount >= MAX_RETRIES) {
                            BolLogger.log(LogLevel.WARN, LogCategory.QUEUE, "queue_entry_max_retries",
                                "Skipping entry ${entry.id.take(8)} — already failed $MAX_RETRIES times, giving up")
                            false
                        } else true
                    }

                if (pending.isNotEmpty()) {
                    processingStage.set(com.bol.app.ai.processing.ProcessingStage.TRANSCRIBING)
                    updateNotification(pending.size)
                    try {
                        // Notes recorded while Granite was transcribing join this batch, so the speech model is
                        // loaded once instead of again after the task model (one model is in memory at a time).
                        val tried = HashSet<String>()
                        var batch = pending
                        var rounds = 0
                        while (batch.isNotEmpty() && rounds < MAX_B1_ROUNDS) {
                            tried += batch.map { it.id }
                            queueProcessingUseCase.transcribeAudioBatch(batch)
                            rounds++
                            batch = voiceQueueRepository.getPending()
                                .filter { it.rawText == "[audio]" && it.id !in tried && it.retryCount < MAX_RETRIES }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        BolLogger.logWithMetrics(LogLevel.ERROR, LogCategory.STT, "whisper_batch_failed",
                            "Whisper batch threw — ${e.javaClass.simpleName}: ${e.message?.take(150)}. Entries with no transcript will be retried on next service run.")
                    }
                }

                // ── Phase B2: BATCH GEMMA PARSE ──────────────────────────
                // Re-fetch pending — Phase B1 may have updated rawText, marked
                // some FAILED (non-speech), or the user may have added new
                // entries while Whisper was running. the model loads ONCE here
                // and stays loaded through B3 + C.
                var b2Pending = voiceQueueRepository.getPending()
                var savedNotes = false
                while (b2Pending.isNotEmpty()) {
                    processingStage.set(com.bol.app.ai.processing.ProcessingStage.CATEGORIZING)
                    updateNotification(b2Pending.size)
                    for (entry in b2Pending) {
                        if (entry.retryCount >= MAX_RETRIES) continue
                        // Wrap each entry so a single bad row (the model decode
                        // failure, OOM, native SIGSEGV-recovery) doesn't
                        // tear down the whole drain. parseAndSaveEntry's
                        // own catch handles most paths, but stack overflows
                        // / OOMs / coroutine-dispatcher corruption can still
                        // leak out. Mark the entry FAILED so it gets out of
                        // the drain queue; the WAV is preserved on disk so
                        // the user can retry from History.
                        try {
                            val result = queueProcessingUseCase.parseAndSaveEntry(entry)
                            if (voiceQueueRepository.getById(entry.id)?.status == "COMPLETED") {
                                savedNotes = true
                                // A note that gave no tasks is easy to mistake for a lost one: say where it went.
                                if (result.itemCount == 0) processingStage.notice("No tasks found in that note. It's saved in History.")
                            }
                            totalItems += result.itemCount
                            if (result.categorySummary.isNotBlank()) {
                                allCategories.addAll(result.categorySummary.split(", "))
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            BolLogger.logWithMetrics(LogLevel.ERROR, LogCategory.QUEUE, "queue_entry_crashed",
                                "Entry ${entry.id.take(8)} parseAndSaveEntry threw — ${e.javaClass.simpleName}: ${e.message?.take(150)}. Marking FAILED to unblock drain.")
                            try {
                                voiceQueueRepository.markFailed(entry.id, "Pipeline crashed: ${e.javaClass.simpleName}")
                                // Shown in History with Retry, like a failed transcription.
                                entry.recordingId?.let { recordingRepository.setCancelled(it, true) }
                            } catch (_: Exception) {
                                // If even markFailed fails, the row stays PENDING — but the
                                // drain is unblocked because retryCount eventually hits MAX_RETRIES.
                            }
                        }
                    }
                    // Refetch — a brand-new recording that landed during the
                    // for-loop's parse passes won't have been transcribed
                    // yet (rawText="[audio]"). It'd fail parseAndSaveEntry's
                    // "no usable text" gate. Better to break out and let
                    // the outer loop's next pass run B1 on it.
                    val refetched = voiceQueueRepository.getPending()
                    val newAudioArrived = refetched.any { it.rawText == "[audio]" }
                    if (newAudioArrived) break
                    b2Pending = refetched
                }

                // Search vectors for the notes just saved (their rows are already in Room), once the queue has no
                // audio waiting: loading the embedder before the next note would only delay it and reload Granite.
                // Never allowed to break voice processing: failures only log.
                if (savedNotes && voiceQueueRepository.getPending().none { it.rawText == "[audio]" }) try {
                    searchIndexer.embedNewest()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    BolLogger.log(LogLevel.WARN, LogCategory.SEARCH, "search_index_after_note_failed",
                        "Could not index the new notes for search — ${e.javaClass.simpleName}: ${e.message?.take(150)}. The backlog will retry.")
                }

                // ── Phase B3: SEARCH QUERIES ─────────────────────────────
                try {
                    processSearchQueries()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    BolLogger.logWithMetrics(LogLevel.ERROR, LogCategory.SEARCH, "search_processing_failed",
                        "Search query processing threw an exception — ${e.javaClass.simpleName}: ${e.message?.take(150)}. Continuing with other work.")
                }

                // If the queue still has audio entries (user recorded during
                // B2 / B3), loop back to B1 instead of running C — Phase C
                // is expensive and will get re-run on the next outer pass.
                val pendingAfterDrain = voiceQueueRepository.getPending()
                val hasFreshAudio = pendingAfterDrain.any { it.rawText == "[audio]" }
                if (hasFreshAudio && !modelManager.isModelOnDisk()) {
                    // Audio can't be transcribed until the model is back
                    // (download / update in progress). Stop here; calibration
                    // resumes the queue when the model is ready.
                    BolLogger.log(LogLevel.INFO, LogCategory.QUEUE, "drain_waiting_for_model",
                        "Audio queued but no model on disk — ending this drain")
                    break@outer
                }
                if (hasFreshAudio) {
                    if (outerPasses >= MAX_OUTER_PASSES) {
                        BolLogger.logWithMetrics(LogLevel.WARN, LogCategory.QUEUE, "post_drain_max_passes",
                            "Hit outer pass cap ($MAX_OUTER_PASSES) with audio still pending — leaving for next service start")
                        break@outer
                    }
                    BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.QUEUE, "post_drain_new_work",
                        "Fresh audio detected during drain — looping back to Whisper batch (pass ${outerPasses + 1}/$MAX_OUTER_PASSES)")
                    continue@outer
                }

                // Voice + search queries are drained. NO Phase C here anymore —
                // tag backfill, enrichment, LLM upgrades, and embedding catch-up
                // moved to [com.bol.app.ai.indexing.IndexingCoordinator] which
                // runs only while the user is in the app. The foreground
                // service exits as soon as the user-visible work (B1 + B2 + B3)
                // is done, freeing the model and dismissing the notification.
                voiceQueueRepository.cleanupOld()
                BolLogger.log(LogLevel.INFO, LogCategory.QUEUE, "service_drain_done",
                    "Voice queue + search drained — exiting. Indexing (tag/enrich/upgrade/embed) will run next time the user opens the app.")
                break@outer
            }

        } finally {
            // Idle timer instead of an immediate unload: the user often
            // records again or searches right after a note, and the engine
            // (text + audio) is exactly what those need. The timer never
            // interrupts work and frees RAM after a minute of idleness.
        }

        val serviceDuration = System.currentTimeMillis() - serviceStartTime
        BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.QUEUE, "service_complete",
            "Background processing service finished — processed $totalItems item(s) total across ${allCategories.size} categories in ${serviceDuration / 1000}s. Service will now stop.",
            durationMs = serviceDuration,
            extras = mapOf("totalItems" to totalItems.toString(),
                "categories" to allCategories.joinToString(","),
                "durationSeconds" to (serviceDuration / 1000).toString()))

        // "Categorization done" notification is opt-in (off by default). Reads
        // the live pref each time so a Settings change takes effect on the next
        // service run without any restart.
        if (totalItems > 0 && preferencesManager.notifyOnWorkDone.first()) {
            val summary = allCategories.joinToString(", ")
            showResultsNotification(totalItems, summary)
        }
        } finally {
            // Always release the foreground notification + stop the service,
            // even if the inner try blew up. Without this, a worker exception
            // leaves the service alive with a stale "Sorting…" notification
            // and no path to recovery (onStartCommand short-circuits while
            // processingJob is in some half-dead state).
            // Reset the stage so the Home pill hides cleanly even on the
            // exception path — without this a crash mid-parse leaves the
            // pill showing "Finding homes for it…" forever.
            processingStage.reset()
            try { stopOrRunAgain("drain finished") } catch (_: Exception) {}
        }
    }

    private suspend fun processSearchQueries() {
        val pendingSearches = searchQueryRepository.getPending()
        if (pendingSearches.isNotEmpty()) {
            processingStage.set(com.bol.app.ai.processing.ProcessingStage.SEARCHING)
        }
        // No "search done" ping: a search takes under a second, on the screen the user is looking at.
        for (query in pendingSearches) {
            updateSearchNotification()
            searchProcessingUseCase.processQuery(query)
        }
        searchQueryRepository.cleanupOld()
    }

    private fun updateSearchNotification() {
        // Quiet mode: a single static "Bol" notification was set in
        // onStartCommand and that's all the user should see. No phase
        // transitions, no flicker.
        if (quietMode) return
        val newTitle = "Searching your notes..."
        if (lastNotificationTitle == newTitle) return
        lastNotificationTitle = newTitle
        val notification = NotificationHelper.buildProcessingNotification(this, 0, newTitle, quiet = false)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NotificationHelper.PROCESSING_NOTIFICATION_ID, notification.build())
    }

    private fun updateNotification(queueSize: Int) {
        // Quiet mode: skip — the initial "Bol" notification already covers the
        // entire run. No further notify() calls = no flicker.
        if (quietMode) return
        val newTitle = when {
            queueSize == 1 -> "Organizing your thoughts..."
            queueSize > 1 -> "Organizing $queueSize items..."
            else -> "Processing in background"
        }
        // Skip the redraw if the title hasn't actually changed — without this
        // the B2 inner loop would call notify() on every iteration with the
        // same content, restarting the spinner and visibly re-animating the
        // banner. Only re-issue when the user-visible state actually shifts.
        if (lastNotificationTitle == newTitle) return
        lastNotificationTitle = newTitle
        val notification = NotificationHelper.buildProcessingNotification(this, queueSize, quiet = false)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NotificationHelper.PROCESSING_NOTIFICATION_ID, notification.build())
    }

    private fun showResultsNotification(totalItems: Int, categories: String) {
        val notification = NotificationHelper.buildResultsNotification(
            this, categories, totalItems
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NotificationHelper.RESULTS_NOTIFICATION_ID, notification.build())
    }

    private fun cancelProcessing() {
        BolLogger.logWithMetrics(LogLevel.WARN, LogCategory.QUEUE, "service_cancelled",
            "Processing service was cancelled by user — aborting current work, unloading model, and stopping service")
        processingJob?.cancel()
        // The same as the Home pill's ✕: the notes in flight become "Cancelled" with Retry in History. Before, the
        // note being parsed stayed PROCESSING (counted as active, never picked up) until the next process start, and
        // the waiting ones were processed anyway at the next app open. Not in serviceScope: stopSelf below cancels it.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { voiceQueueRepository.cancelAllActive() }
        }
        modelManager.unloadModel()
        processingStage.reset()
        lockScreenPerf.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.QUEUE, "service_destroyed",
            "Processing service destroyed — releasing resources; the model unloads after the idle timer")
        serviceScope.cancel()
        // Not unloadModel(): that would cancel any other in-flight the model
        // owner (search, indexing). The idle timer frees the engine.
        processingStage.reset()
        lockScreenPerf.release()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.bol.app.CANCEL_PROCESSING"
        private const val TAG = "BolProcessing"
        private const val MAX_RETRIES = 3

        /**
         * Maximum outer drain passes per service run. Absorbs a recording
         * the user fires in the same instant the previous pass finished —
         * the new entry shows up in the queue and the outer loop loops back
         * to drain it without needing a fresh service start. Cap bounds a
         * pathological "queue rows breeding queue rows" bug.
         */
        private const val MAX_OUTER_PASSES = 5

        /** Cap on re-reading the queue for notes recorded during transcription, so tasks still come out if the user keeps recording. */
        private const val MAX_B1_ROUNDS = 10

        /** Starts the foreground processing service. */
        fun startProcessing(context: Context) {
            val intent = Intent(context, BolProcessingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

    }
}
