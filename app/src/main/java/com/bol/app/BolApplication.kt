package com.bol.app

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Process
import com.bol.app.ai.model.ModelManager
import com.bol.app.data.repository.LlmUpgradeRepository
import com.bol.app.data.repository.VoiceQueueRepository
import com.bol.app.data.local.dao.PerformanceLogDao
import com.bol.app.di.appModule
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import com.bol.app.monitoring.PerformanceMonitor
import com.bol.app.service.BolProcessingService
import com.bol.app.service.NotificationHelper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class BolApplication : Application(), ComponentCallbacks2 {

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isAiProcess = false

    override fun onCreate() {
        val appStartTime = System.currentTimeMillis()
        super.onCreate()
        isAiProcess = isAiProcess()

        startKoin {
            androidContext(this@BolApplication)
            modules(appModule)
        }

        // Initialize BolLogger for both processes
        val logDao: PerformanceLogDao by inject()
        val perfMonitor: PerformanceMonitor by inject()
        BolLogger.init(logDao, perfMonitor, this)

        if (isAiProcess) {
            BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.APP, "ai_process_init",
                "Background AI process started — this process handles on-device model inference")
            val modelManager: ModelManager by inject()
            // Channel creation + model-status checks moved off the main
            // thread so Application.onCreate returns immediately. Nothing
            // in the foreground UI path needs these to be ready
            // synchronously.
            appScope.launch {
                NotificationHelper.createChannels(this@BolApplication)
                modelManager.checkModelStatus()
            }

            // NOTE: background tag backfill is currently DISABLED here.
            // Concurrent the model use from tagging + user search caused
            // repeated SIGSEGV in liblitertlm_jni.so — Whisper's unload
            // race with a mid-flight the model inference crashes LiteRT-LM.
            // Tagging now runs only inline on save and via the explicit
            // spike-button backfill. Safe to re-enable once ModelManager
            // has a proper inference mutex that unloadModel() respects.
            return
        }

        // ── Main process: full initialization ──
        val modelManager: ModelManager by inject()
        val voiceQueueRepository: VoiceQueueRepository by inject()
        val llmUpgradeRepository: LlmUpgradeRepository by inject()
        val searchQueryRepository: com.bol.app.data.repository.SearchQueryRepository by inject()

        // Heavy startup work (model file IO, notification channels) moved
        // off the main thread so MainActivity can start composing without
        // waiting. Anything reading model state through Koin will see the
        // post-check value once the background work completes; UI
        // collectAsState bindings pick it up reactively.
        appScope.launch {
            modelManager.checkModelStatus()
            NotificationHelper.createChannels(this@BolApplication)
        }

        // One-time cleanup: delete the orphaned Whisper model directory left
        // over from pre-the model-audio installs (~375 MB for whisper_small).
        // Nothing references filesDir/whisper-models/ any more. Idempotent —
        // deleting an absent directory is a no-op, so no "done" flag needed.
        appScope.launch {
            runCatching {
                val whisperDir = java.io.File(filesDir, "whisper-models")
                if (whisperDir.exists()) {
                    val freedBytes = whisperDir.walkBottomUp()
                        .filter { it.isFile }
                        .sumOf { it.length() }
                    if (whisperDir.deleteRecursively()) {
                        BolLogger.log(LogLevel.INFO, LogCategory.APP, "whisper_dir_cleanup",
                            "Deleted orphaned Whisper model directory — freed ${freedBytes / (1024 * 1024)} MB",
                            extras = mapOf("freedBytes" to freedBytes.toString()))
                    }
                }
            }
        }

        // Book the daily digest if the user has it on and none is waiting. KEEP never moves or cancels a booked
        // (or running) digest; changes of time or zone replace it where they happen.
        val prefs: com.bol.app.data.preferences.PreferencesManager by inject()
        appScope.launch {
            if (prefs.digestEnabled.first()) {
                com.bol.app.service.digest.DailyDigestScheduler.ensureScheduled(
                    this@BolApplication, prefs.digestTime.first(),
                )
            } else {
                com.bol.app.service.digest.DailyDigestScheduler.cancel(this@BolApplication)
            }
        }

        // A force stop, or Android withdrawing exact alarms, cancels every alarm and nothing set them again until a
        // reboot. Setting each future reminder again on every start is one query and one AlarmManager call per
        // reminder; the same request code replaces an alarm that is still there.
        val itemRepository: com.bol.app.data.repository.ItemRepository by inject()
        val categoryRepository: com.bol.app.data.repository.CategoryRepository by inject()
        appScope.launch {
            runCatching { categoryRepository.seedDefaultsIfEmpty() }
            runCatching { itemRepository.rescheduleReminders() }
                .onSuccess { n -> BolLogger.log(LogLevel.INFO, LogCategory.APP, "reminders_rearmed", "Set $n reminder alarm(s) at start") }
                .onFailure { e -> BolLogger.log(LogLevel.WARN, LogCategory.APP, "reminders_rearm_failed", "${e.javaClass.simpleName}: ${e.message?.take(150)}") }
        }

        // Audio no row points to any more: a "replace" restore drops recordings but not their files, and a deleted
        // or failed search can leave its clip. Only files over an hour old, because Quick Capture writes the file
        // before its row and may be saving right now (a power-button capture starts the app).
        val recordingRepository: com.bol.app.data.repository.RecordingRepository by inject()
        appScope.launch {
            runCatching {
                val recordingIds = recordingRepository.getAllSnapshot().mapTo(HashSet()) { it.id }
                deleteOrphanAudio(java.io.File(filesDir, "audio")) { id -> id in recordingIds }
                deleteOrphanAudio(java.io.File(filesDir, "search_audio")) { id ->
                    searchQueryRepository.getById(id)?.status.let {
                        it == com.bol.app.data.local.entity.SearchQueryStatus.PENDING ||
                            it == com.bol.app.data.local.entity.SearchQueryStatus.PROCESSING
                    }
                }
            }.onFailure { e ->
                BolLogger.log(LogLevel.WARN, LogCategory.APP, "orphan_audio_sweep_failed", "${e.javaClass.simpleName}: ${e.message?.take(150)}")
            }
        }

        // Seed the timezone baseline. TimeZoneChangedReceiver compares this
        // against the new TZ to figure out how to rebase floating reminders;
        // an empty value means "first run" — record current TZ and skip the
        // rebase pass, since there's nothing to compare against yet.
        appScope.launch {
            if (prefs.lastKnownTimeZoneId.first().isEmpty()) {
                prefs.setLastKnownTimeZoneId(java.util.TimeZone.getDefault().id)
            }
        }

        val startupDuration = System.currentTimeMillis() - appStartTime
        BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.APP, "app_cold_start",
            "App launched — main process initialization complete in ${startupDuration}ms",
            durationMs = startupDuration,
            extras = mapOf("llmModelState" to modelManager.state.value.toString()))

        // Embedding backfill is no longer triggered at startup. Per the new
        // deferred-indexing UX contract, all index-catch-up work (tags,
        // enrichments, LLM upgrades, embeddings) runs through
        // [com.bol.app.ai.indexing.IndexingCoordinator] only when the user
        // is in the app and explicitly taps "Start indexing" on the Home
        // banner (or when a search auto-triggers it). Indexing cancels the
        // moment the app moves to background — see the
        // ProcessLifecycleOwner observer below.

        val indexingCoordinator: com.bol.app.ai.indexing.IndexingCoordinator by inject()
        // Refresh the pending count once at startup so the Home banner has
        // an accurate number to render on first composition.
        appScope.launch { indexingCoordinator.refreshPendingCount() }

        // Periodic worker that nudges the user with a notification when
        // the indexing backlog grows past 100 items, with an escalating
        // cadence (1d → 3d → 7d → 14d → 30d). Idempotent — re-scheduling
        // refreshes the schedule without losing run history.
        // Off the main thread because WorkManager.getInstance() does
        // significant first-time init (loads its SQLite DB) and we
        // don't want that blocking the cold-start UI.
        appScope.launch {
            com.bol.app.service.indexing.IndexingNotificationWorker.schedule(this@BolApplication)
        }

        // Cancel deferred indexing the instant the app moves to background.
        // ON_STOP fires when the last visible activity is hidden — Home
        // button, recents picker, screen lock, switching apps. Any running
        // the model call inside the indexer drops at the next suspend point;
        // unfinished items stay in the LEFT JOIN backlog and resume on the
        // next foreground when the user taps Start again.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                appScope.launch { indexingCoordinator.refreshPendingCount() }
                // Voice queue resurrection: if a previous service run died
                // mid-drain (the model decode hang, OOM, native SIGSEGV under
                // memory/thermal pressure), the row stays PENDING and the
                // foreground notification persists with no live worker.
                // onCreate's recovery only fires on PROCESS start — but the
                // process can stay alive across captures, so the dead worker
                // never gets replaced. Re-kick the service on every app
                // foreground; if processingJob is healthy, onStartCommand's
                // active-job gate makes this a no-op.
                appScope.launch {
                    val pending = voiceQueueRepository.getPending()
                    val pendingSearches = searchQueryRepository.getPending()
                    if (pending.isNotEmpty() || pendingSearches.isNotEmpty()) {
                        BolLogger.log(LogLevel.INFO, LogCategory.APP, "queue_resurrect_on_foreground",
                            "App foregrounded with ${pending.size} voice / ${pendingSearches.size} search row(s) still pending — kicking service in case the prior worker died silently",
                            extras = mapOf(
                                "pendingVoice" to pending.size.toString(),
                                "pendingSearches" to pendingSearches.size.toString(),
                            ))
                        BolProcessingService.startProcessing(this@BolApplication)
                    }
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                indexingCoordinator.stop()
                appScope.launch { indexingCoordinator.refreshPendingCount() }
            }
        })

        appScope.launch {
            voiceQueueRepository.resetStuckProcessing()
            llmUpgradeRepository.resetStuckProcessing()
            BolLogger.log(LogLevel.INFO, LogCategory.APP, "queue_recovery_check",
                "Checked for entries stuck in 'processing' state from a prior crash and reset them to pending")

            val pendingVoice = voiceQueueRepository.getPending()
            val pendingUpgrades = llmUpgradeRepository.getPending()
            // Stranded-search recovery: if the previous session was killed
            // (process death, force-close) while a search was waiting on
            // indexing, the row stays PENDING in search_queries with no
            // service to drain it. SearchViewModel's wait-collector died
            // with viewModelScope, so it can't restart the service either.
            // Pick those up here so the search eventually returns an answer
            // instead of sitting forever in the user's history with a
            // pending spinner.
            val pendingSearches = searchQueryRepository.getPending()

            if (pendingVoice.isNotEmpty() || pendingSearches.isNotEmpty()) {
                BolLogger.log(LogLevel.INFO, LogCategory.APP, "service_auto_start",
                    "Resuming background processing — ${pendingVoice.size} voice / ${pendingSearches.size} search row(s) pending from prior session",
                    extras = mapOf(
                        "pendingVoice" to pendingVoice.size.toString(),
                        "pendingSearches" to pendingSearches.size.toString(),
                    ))
                BolProcessingService.startProcessing(this@BolApplication)
            } else if (pendingUpgrades.isNotEmpty()) {
                BolLogger.log(LogLevel.INFO, LogCategory.APP, "upgrade_pending",
                    "Found ${pendingUpgrades.size} provisional item(s) waiting for the task model — they run with indexing while the app is open",
                    extras = mapOf("pendingCount" to pendingUpgrades.size.toString()))
            }
            // The old background upgrade worker only started the processing service, which no longer upgrades.
            runCatching { androidx.work.WorkManager.getInstance(this@BolApplication).cancelUniqueWork("llm_upgrade") }
        }
    }

    /** Deletes the `<id>.wav` files in [dir] older than an hour whose id [keep] rejects. */
    private suspend fun deleteOrphanAudio(dir: java.io.File, keep: suspend (String) -> Boolean) {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        val old = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") && it.lastModified() < cutoff } ?: return
        val deleted = old.count { f -> !keep(f.name.removeSuffix(".wav")) && f.delete() }
        if (deleted > 0) {
            BolLogger.log(LogLevel.INFO, LogCategory.APP, "orphan_audio_deleted", "Deleted $deleted unreferenced audio file(s) in ${dir.name}/")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Real pressure only: RUNNING_LOW / RUNNING_CRITICAL (Android 13 and older, app in front) or BACKGROUND and up
        // (app cached). UI_HIDDEN just means the app left the screen; the idle timer handles that.
        @Suppress("DEPRECATION")
        val pressure = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (pressure) {
            val processLabel = if (isAiProcess) ":ai (background)" else "main (UI)"
            val severity = when {
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "CRITICAL — system is about to kill this process"
                level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE — system is reclaiming memory aggressively"
                else -> "LOW — system is running low on memory"
            }
            BolLogger.logWithMetrics(LogLevel.WARN, LogCategory.MEMORY, "system_memory_pressure",
                "Android reported memory pressure on $processLabel process: $severity",
                extras = mapOf("trimLevel" to level.toString(), "process" to processLabel))
            // Frees the loaded model once the current call (if any) ends; never under a running generation.
            val modelManager: ModelManager by inject()
            modelManager.unloadModel()
        }
    }

    private fun isAiProcess(): Boolean {
        val pid = Process.myPid()
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.any {
            it.pid == pid && it.processName.endsWith(":ai")
        } == true
    }
}
