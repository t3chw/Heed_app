package com.bol.app.ai.indexing

import com.bol.app.ai.retrieval.SearchIndexer
import com.bol.app.data.repository.LlmUpgradeRepository
import com.bol.app.data.repository.VoiceQueueRepository
import com.bol.app.domain.usecase.LlmUpgradeUseCase
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Orchestrates deferred indexing — tag backfill, item enrichment, LLM
 * upgrades, and embedding catch-up — that used to run inside
 * [com.bol.app.service.BolProcessingService] Phase C.
 *
 * Why this is split out: per the new UX contract, the only AI work allowed in
 * the background (locked phone, foreground service) is voice transcription +
 * categorization (B1 + B2). Everything else — tagging, enrichment, upgrades,
 * embeddings — only enhances *search* and runs **only while the user is in
 * the app**. The coordinator owns its own [scope] which gets cancelled when
 * the user backgrounds the app, so any in-flight the model call drops at the
 * next suspend point and the unloaded model frees RAM cleanly.
 *
 * Voice processing always preempts indexing: each batch checks
 * [voiceQueueRepository] for pending entries and yields if any are present.
 * The actual indexer methods ([TagIndexer.backfill] etc.) also have their
 * own internal yield-on-urgent-work hooks, so the bail-out is layered.
 *
 * [progress] is a single source of truth that the Home banner and Search
 * progress UI both observe.
 */
class IndexingCoordinator(
    private val llmUpgradeUseCase: LlmUpgradeUseCase,
    private val llmUpgradeRepository: LlmUpgradeRepository,
    private val voiceQueueRepository: VoiceQueueRepository,
    private val searchIndexer: SearchIndexer,
) {
    enum class Phase { Idle, Upgrades, Embeddings, Done }

    /**
     * Live progress for the UI. [totalAtStart] is captured when [start] runs,
     * so the user sees a stable "X of Y" denominator even if new items appear
     * mid-run (those just extend the current run instead of resetting the
     * percentage).
     */
    data class Progress(
        val isIndexing: Boolean = false,
        val phase: Phase = Phase.Idle,
        val totalAtStart: Int = 0,
        val pending: Int = 0,
    ) {
        val completed: Int get() = (totalAtStart - pending).coerceAtLeast(0)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var indexingJob: Job? = null

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * Combined "items needing work" count — sum of (items missing tags) +
     * (items missing enrichments) + (pending LLM upgrades), plus missing
     * embeddings only when [embeddingService] is wired (not stubbed).
     *
     * Reactive: combine() over Room-backed Flows means the count updates
     * automatically the moment a voice note saves, a tag inserts, an
     * upgrade completes, etc. The banner ticks down in real time without
     * any explicit `refreshPendingCount()` call from caller code.
     */
    /** Provisional items waiting for the task model. Search waits for these; it never waits for vectors. */
    val pendingUpgrades: StateFlow<Int> = llmUpgradeRepository.pendingCountFlow()
        .catch { e -> emit(0)
            BolLogger.log(LogLevel.WARN, LogCategory.SEARCH, "pending_flow_err_upgrades",
                "llm_upgrade_queue.pendingCount Flow threw: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /**
     * Upgrades plus a vector backlog bigger than one batch (the Home and Search banner). A few waiting docs are not
     * counted: a saved note's vectors follow within seconds and the next search embeds an edit's, so counting them
     * flashed "1 item waiting to be indexed · Start" after every note.
     */
    val pendingTotal: StateFlow<Int> = pendingUpgrades
        .combine(searchIndexer.pending) { upgrades, vectors -> upgrades + if (vectors > SearchIndexer.BATCH) vectors else 0 }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /**
     * Synchronous pending-count read. Queries the DAOs directly instead of
     * relying on [pendingTotal] (which is a stateIn'd Flow whose initial
     * value is 0 until the first upstream emission lands). Use this from
     * code paths where the Flow may not have warmed up yet — notably
     * [com.bol.app.service.indexing.IndexingNotificationWorker] which can
     * cold-start the app process and read before Room has fired.
     */
    suspend fun getPendingNow(): Int = withContext(Dispatchers.IO) {
        runCatching { llmUpgradeRepository.getPending().size }.getOrDefault(0) + pendingVectorsNow()
    }

    private suspend fun pendingVectorsNow(): Int = try {
        searchIndexer.sync()
        searchIndexer.pending.value
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        0
    }

    /**
     * Suspend variant kept for backwards compatibility — no longer needed
     * because [pendingTotal] is reactive. Forwards to [getPendingNow] for
     * the rare caller that needs a forced synchronous recompute.
     */
    suspend fun refreshPendingCount() {
        // No-op — reactive Flow handles UI updates. Worker should call
        // getPendingNow() directly for a synchronous read.
    }

    /**
     * Kick off a deferred-indexing pass. Idempotent — a second call while a
     * run is in flight is a no-op. Caller is the Home banner Start button or
     * the Search-screen "search-with-pending" auto-trigger.
     */
    fun start() {
        if (indexingJob?.isActive == true) return
        indexingJob = scope.launch {
            BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.SEARCH, "indexing_start",
                "Deferred indexing starting — user is in the app, will run until done or until app backgrounded")
            // Use a direct DAO read instead of pendingTotal.value — the
            // StateFlow lags behind a just-committed transaction (Room's
            // invalidation tracker fires async, then combine() + stateIn()
            // propagate). Right after a backup restore that commit-then-
            // start sequence raced and the indexer would early-exit on a
            // stale 0. getPendingNow() bypasses the flow entirely; the forced
            // sync reads Room before its own change notice has arrived.
            try { searchIndexer.sync(force = true) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
            val total = getPendingNow()
            if (total == 0) {
                BolLogger.log(LogLevel.INFO, LogCategory.SEARCH, "indexing_no_work",
                    "No items pending indexing — nothing to do")
                return@launch
            }

            _progress.value = Progress(
                isIndexing = true,
                phase = Phase.Upgrades,
                totalAtStart = total,
                pending = total,
            )

            try {
                runLoop()
                _progress.update { it.copy(isIndexing = false, phase = Phase.Done) }
                BolLogger.logWithMetrics(LogLevel.INFO, LogCategory.SEARCH, "indexing_complete",
                    "Deferred indexing finished — all pending items processed")
            } catch (e: kotlinx.coroutines.CancellationException) {
                BolLogger.log(LogLevel.INFO, LogCategory.SEARCH, "indexing_cancelled",
                    "Deferred indexing cancelled — likely because app moved to background. Remaining items will resume on next foreground.")
                _progress.update { it.copy(isIndexing = false, phase = Phase.Idle) }
                throw e
            } catch (e: Exception) {
                BolLogger.log(LogLevel.WARN, LogCategory.SEARCH, "indexing_error",
                    "Deferred indexing crashed — ${e.javaClass.simpleName}: ${e.message?.take(150)}. Items will retry on next run.")
                _progress.update { it.copy(isIndexing = false, phase = Phase.Idle) }
            } finally {
                // Idle timer, not an immediate unload: when voice processing
                // pre-empts indexing it needs the very same engine, and an
                // immediate unload forced a full reload right after. The
                // timer never interrupts work (see ModelManager.unloadIfIdle).
                refreshPendingCount()
            }
        }
    }

    /**
     * User-initiated stop (Home banner Stop button, or app backgrounded).
     * Cancels the running job; the [Progress] flips to idle when the
     * coroutine's catch block runs.
     */
    fun stop() {
        indexingJob?.cancel()
        indexingJob = null
    }

    /**
     * Cancel any running indexing job AND wait for the coroutine to fully
     * finish, so its in-flight the model call has been cancelled natively and
     * the engine lease released. Used by
     * [com.bol.app.service.BolProcessingService] so voice work never queues
     * behind a tag/enrich batch. The engine stays loaded for the service.
     */
    suspend fun stopAndAwait() {
        val job = indexingJob ?: return
        if (!job.isActive) {
            indexingJob = null
            return
        }
        BolLogger.log(LogLevel.INFO, LogCategory.SEARCH, "indexing_yield_to_voice",
            "Voice processing taking over — cancelling indexing and waiting for the model to release")
        job.cancel()
        runCatching { job.join() }
        indexingJob = null
    }

    /**
     * Pump for the indexing loop. Picks the highest-priority phase with
     * pending work, runs one batch, refreshes counts, and repeats until
     * everything is done or cancellation is observed.
     */
    private suspend fun runLoop() {
        // Tracks consecutive batches where pending count didn't drop —
        // protects against an indexer (e.g. tagIndexer) that runCatching-
        // swallows a recurring failure on the same batch and returns
        // without making progress. Without this, runLoop would spin
        // forever burning battery while the user thinks indexing is
        // working. Reset to 0 on any actual progress.
        var consecutiveNoProgress = 0
        while (true) {
            yield()  // throws CancellationException if cancelled
            // Voice processing always wins. If the user records a new note
            // while we're indexing, yield the model so BolProcessingService can
            // grab it. Each indexer also has its own internal yield, but
            // the outer guard avoids paying a model load just to find out
            // we should yield mid-batch.
            if (voiceQueueRepository.getPending().isNotEmpty()) {
                BolLogger.log(LogLevel.DEBUG, LogCategory.SEARCH, "indexing_yield_to_voice",
                    "Voice queue has pending entries — indexing yielding for 1s and re-checking")
                delay(1_000)
                continue
            }

            val phase = pickNextPhase()
            if (phase == null) break
            _progress.update { it.copy(phase = phase) }

            // Capture pending BEFORE the batch so we can detect a stuck
            // indexer afterward. Use the synchronous DAO-backed read,
            // not the reactive Flow's .value — Flow may lag.
            val pendingBefore = getPendingNow()

            when (phase) {
                Phase.Upgrades -> runOneUpgradeBatch()
                Phase.Embeddings -> searchIndexer.embedBacklog()
                else -> {}
            }

            val pendingAfter = getPendingNow()
            // Voice arriving mid-batch causes a clean yield with no
            // progress — that's expected, not a failure. Don't count it
            // toward the no-progress break threshold.
            val voiceArrived = voiceQueueRepository.getPending().isNotEmpty()
            if (pendingAfter < pendingBefore) {
                consecutiveNoProgress = 0
            } else if (!voiceArrived) {
                consecutiveNoProgress++
                if (consecutiveNoProgress >= MAX_CONSECUTIVE_NO_PROGRESS) {
                    BolLogger.log(LogLevel.WARN, LogCategory.SEARCH, "indexing_no_progress_abort",
                        "Indexing made no progress on $phase for $consecutiveNoProgress consecutive batches " +
                        "(pending stuck at $pendingAfter) — aborting to avoid spin loop. User can re-run via Start button.")
                    break
                }
            }

            _progress.update { it.copy(pending = pendingAfter) }
        }
    }

    private suspend fun pickNextPhase(): Phase? = withContext(Dispatchers.IO) {
        when {
            llmUpgradeRepository.getPending().isNotEmpty() -> Phase.Upgrades
            pendingVectorsNow() > 0 -> Phase.Embeddings
            else -> null
        }
    }

    private suspend fun runOneUpgradeBatch() {
        val pending = llmUpgradeRepository.getPending().take(BATCH_SIZE)
        for (entry in pending) {
            yield()
            llmUpgradeUseCase.processEntry(entry)
        }
        llmUpgradeUseCase.expireStaleEntries()
    }

    companion object {
        private const val BATCH_SIZE = 30

        /** After this many consecutive batches with no drop in pending
         *  count, abort the run. Protects against persistent failures that
         *  a runCatching internally swallows so the loop doesn't spin
         *  forever. 2 covers transient hiccups (one bad batch followed by
         *  a good one resets the counter). */
        private const val MAX_CONSECUTIVE_NO_PROGRESS = 2
    }
}
