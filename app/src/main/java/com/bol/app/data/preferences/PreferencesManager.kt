package com.bol.app.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bol.app.data.backup.BackupPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bol_preferences")

class PreferencesManager(private val context: Context) {

    private object Keys {
        val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        // STT_ENGINE key removed in Phase 6 of Whisper-removal migration.
        // speech now handles all transcription; no engine choice exists.
        val LLM_BACKEND = stringPreferencesKey("llm_backend")
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        val TIME_FORMAT = stringPreferencesKey("time_format")
        val DIGEST_ENABLED = booleanPreferencesKey("digest_enabled")
        val DIGEST_TIME = stringPreferencesKey("digest_time")
        val NOTIFY_ON_WORK_DONE = booleanPreferencesKey("notify_on_work_done")
        val PROCESSING_HINT_SHOWN_COUNT = intPreferencesKey("processing_hint_shown_count")
        val LLM_TOP_K = intPreferencesKey("llm_top_k")
        val LLM_TOP_P = floatPreferencesKey("llm_top_p")
        val LLM_TEMPERATURE = floatPreferencesKey("llm_temperature")
        val LLM_MAX_TOKENS = intPreferencesKey("llm_max_tokens")
        val DEFAULT_REMINDER_HOUR = intPreferencesKey("default_reminder_hour")
        val HAS_SHOWN_MIC_RATIONALE = booleanPreferencesKey("has_shown_mic_rationale")
        val HAS_SHOWN_NOTIF_RATIONALE = booleanPreferencesKey("has_shown_notif_rationale")
        val HIDE_COMPLETED_ITEMS = booleanPreferencesKey("hide_completed_items")
        val VOICE_ENTRY_COUNT = intPreferencesKey("voice_entry_count")
        val HAS_SHOWN_TYPE_HINT = booleanPreferencesKey("has_shown_type_hint")
        val HAS_SHOWN_ASSISTANT_HINT = booleanPreferencesKey("has_shown_assistant_hint")
        val HAS_SHOWN_BATTERY_HINT = booleanPreferencesKey("has_shown_battery_hint")
        val BACKEND_BENCHMARK_STATUS = stringPreferencesKey("backend_benchmark_status")
        val AUDIO_NEEDS_CPU_BACKEND = booleanPreferencesKey("audio_needs_cpu_backend")
        val LLM_SPECULATIVE_DECODING = booleanPreferencesKey("llm_speculative_decoding")
        val BACKEND_BENCHMARK_PHASE = stringPreferencesKey("backend_benchmark_phase")
        val BACKEND_BENCHMARK_GPU_MS = intPreferencesKey("backend_benchmark_gpu_ms")
        val BACKEND_BENCHMARK_CPU_MS = intPreferencesKey("backend_benchmark_cpu_ms")
        val BACKEND_BENCHMARK_WINNER = stringPreferencesKey("backend_benchmark_winner")
        val BACKEND_BENCHMARK_TIER = stringPreferencesKey("backend_benchmark_tier")

        // Indexing-backlog notification scheduling. Tracks when we last
        // nudged the user about pending indexing work and which step in
        // the cadence we're at, so we don't spam them.
        val INDEXING_NOTIF_LAST_AT = androidx.datastore.preferences.core.longPreferencesKey("indexing_notif_last_at")
        val INDEXING_NOTIF_FIRST_SEEN_AT = androidx.datastore.preferences.core.longPreferencesKey("indexing_notif_first_seen_at")
        val INDEXING_NOTIF_STEP = intPreferencesKey("indexing_notif_step")

        val DEV_MODE_ENABLED = booleanPreferencesKey("dev_mode_enabled")

        // Last device timezone we observed. Used by TimeZoneChangedReceiver
        // to rebase pending floating reminders to the new local-clock time
        // when the user crosses a timezone (e.g. travel). Empty string = not
        // yet initialised; first launch of the receiver records current TZ
        // as the baseline without rebasing anything.
        val LAST_KNOWN_TZ_ID = stringPreferencesKey("last_known_tz_id")
    }

    val hasCompletedOnboarding: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HAS_COMPLETED_ONBOARDING] ?: false
    }

    val themeMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.key
    }

    val language: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.LANGUAGE] ?: "en"
    }

    suspend fun setOnboardingCompleted() {
        context.dataStore.edit { prefs ->
            prefs[Keys.HAS_COMPLETED_ONBOARDING] = true
        }
    }

    /** True once the Bol-voiced mic-permission rationale has been shown
     *  to the user. We show our explainer dialog before the OS prompt
     *  on the first ask so the user understands why we want the mic;
     *  on subsequent asks (re-prompts after deny) the OS dialog handles
     *  itself. */
    val hasShownMicRationale: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HAS_SHOWN_MIC_RATIONALE] ?: false
    }

    suspend fun setMicRationaleShown() {
        context.dataStore.edit { prefs ->
            prefs[Keys.HAS_SHOWN_MIC_RATIONALE] = true
        }
    }

    /** Same pattern for POST_NOTIFICATIONS — shown once before the first
     *  OS notification-permission prompt during the model-download flow. */
    val hasShownNotifRationale: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HAS_SHOWN_NOTIF_RATIONALE] ?: false
    }

    suspend fun setNotifRationaleShown() {
        context.dataStore.edit { prefs ->
            prefs[Keys.HAS_SHOWN_NOTIF_RATIONALE] = true
        }
    }

    /** Global "hide completed items" toggle. Affects every CategoryDetail
     *  list — done items disappear when on, keeping the list focused on
     *  open work. Default: true (most users want to see what's left). The
     *  header counts ("X open · Y done") still reflect both totals so the
     *  user knows how much is hidden. */
    val hideCompletedItems: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HIDE_COMPLETED_ITEMS] ?: true
    }

    suspend fun setHideCompletedItems(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HIDE_COMPLETED_ITEMS] = value
        }
    }

    /** Number of voice/text entries the user has submitted from the Home
     *  MicPill. Used to time the one-shot "long-press to type" hint —
     *  shown only after the user has invested enough in the app to have
     *  formed muscle memory around tapping the pill. */
    val voiceEntryCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.VOICE_ENTRY_COUNT] ?: 0
    }

    suspend fun incrementVoiceEntryCount() {
        context.dataStore.edit { prefs ->
            prefs[Keys.VOICE_ENTRY_COUNT] = (prefs[Keys.VOICE_ENTRY_COUNT] ?: 0) + 1
        }
    }

    /** True once the long-press-to-type hint has been shown (and dismissed).
     *  After this flips, the tooltip never reappears. */
    val hasShownTypeHint: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HAS_SHOWN_TYPE_HINT] ?: false
    }

    suspend fun setTypeHintShown() {
        context.dataStore.edit { prefs ->
            prefs[Keys.HAS_SHOWN_TYPE_HINT] = true
        }
    }

    /** True once the "make Bol your assistant" hint has been shown — either
     *  because the user tapped Set up or Maybe later. We don't try to
     *  detect whether they actually completed the system flow; the goal of
     *  the hint is to plant the idea once. After this flag flips, the
     *  Settings → About row remains as a permanent reminder. */
    val hasShownAssistantHint: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HAS_SHOWN_ASSISTANT_HINT] ?: false
    }

    suspend fun setAssistantHintShown() {
        context.dataStore.edit { prefs ->
            prefs[Keys.HAS_SHOWN_ASSISTANT_HINT] = true
        }
    }

    /** True once the "allow background processing" hint has been shown on
     *  Home — either because the user tapped Allow now (which opens the
     *  system whitelist) or Maybe later. Only ever surfaced on heavy-OEM
     *  phones (Oppo / Xiaomi / Vivo etc.) where battery throttling makes
     *  on-device AI 10× slower on lock screen. After this flips, the
     *  Settings → About row remains as a permanent route. */
    val hasShownBatteryHint: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HAS_SHOWN_BATTERY_HINT] ?: false
    }

    suspend fun setBatteryHintShown() {
        context.dataStore.edit { prefs ->
            prefs[Keys.HAS_SHOWN_BATTERY_HINT] = true
        }
    }

    /** Hidden developer mode. Off by default; flipped on by tapping the
     *  About → version row 7 times (the same gesture Android uses for its
     *  own developer options). When on, Settings reveals AI tuning, the
     *  log viewer, and the Spike/Eval/Bench/Stress/Seed panel; the
     *  LogViewer destination also becomes navigable. Users can flip it
     *  back off from the disable row inside the Developer section. */
    val devModeEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEV_MODE_ENABLED] ?: false
    }

    suspend fun setDevModeEnabled(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEV_MODE_ENABLED] = value
        }
    }

    /** ID of the timezone the device was last observed in (e.g. "America/New_York").
     *  Empty string means we haven't recorded one yet; the receiver treats that
     *  as a no-op baseline write rather than rebasing reminders. */
    val lastKnownTimeZoneId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_KNOWN_TZ_ID] ?: ""
    }

    suspend fun setLastKnownTimeZoneId(zoneId: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_KNOWN_TZ_ID] = zoneId
        }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode
        }
    }

    suspend fun setLanguage(language: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LANGUAGE] = language
        }
    }

    // (sttEngine flow + setter removed in Phase 6 of Whisper-removal
    //  migration. Voice transcription is now done by speech on the
    //  same engine that runs categorization — no choice to expose.)

    // "gpu" (default, faster on flagship) or "cpu" (stable on mid-range)
    val llmBackend: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.LLM_BACKEND] ?: LLM_BACKEND_GPU
    }

    suspend fun setLlmBackend(backend: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LLM_BACKEND] = backend
        }
    }

    /**
     * Device-specific: set when the model returned an empty transcript for clearly
     * audible speech while the text decoder ran on GPU (the 2026-05 LiteRT-LM
     * 0.10 failure). From then on audio inferences force the CPU backend.
     * Cleared by re-calibration so a newer runtime gets re-tested. Not part
     * of backups — it describes this phone, not the user.
     */
    val audioNeedsCpuBackend: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.AUDIO_NEEDS_CPU_BACKEND] ?: false
    }

    suspend fun setAudioNeedsCpuBackend(value: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AUDIO_NEEDS_CPU_BACKEND] = value
        }
    }

    /**
     * Speculative decoding (the model 4 MTP drafter) on the GPU backend. Default
     * on — Google recommends MTP for all GPU workloads — and calibration
     * turns it off on phones where it measured slower or produced wrong
     * output (e.g. the PowerVR regression, LiteRT-LM #2227). Never used on
     * CPU. Device-specific, not part of backups.
     */
    val llmSpeculativeDecoding: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.LLM_SPECULATIVE_DECODING] ?: true
    }

    suspend fun setLlmSpeculativeDecoding(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LLM_SPECULATIVE_DECODING] = enabled
        }
    }

    val selectedModelId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SELECTED_MODEL_ID] ?: ""
    }

    suspend fun setSelectedModelId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SELECTED_MODEL_ID] = id
        }
    }

    /** "12h" (default, e.g. "2:15 pm") or "24h" (e.g. "14:15"). */
    val timeFormat: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.TIME_FORMAT] ?: TIME_FORMAT_12H
    }

    suspend fun setTimeFormat(format: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TIME_FORMAT] = format
        }
    }

    /** Whether the morning-digest notification fires. Default: true. */
    val digestEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        // Off by default — Bol is built quiet. Reminders are the canonical
        // "Bol reaches out to you" surface (user explicitly set a time on a
        // specific item); a daily 8 AM summary fires unprompted and trains
        // users to ignore Bol notifications, including the explicit ones.
        // Users who want a morning glance can toggle it on in Settings.
        prefs[Keys.DIGEST_ENABLED] ?: false
    }

    suspend fun setDigestEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DIGEST_ENABLED] = enabled
        }
    }

    /**
     * Whether to post a "Done — categorized X items" / "Search ready" toast
     * after background AI work finishes. Default: false — Bol is built to be
     * quiet, and the foreground-service notification disappearing is already a
     * sufficient "done" signal for most users. Reminders fire regardless of
     * this flag — they have their own channel and their own life-or-death UX.
     */
    val notifyOnWorkDone: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.NOTIFY_ON_WORK_DONE] ?: false
    }

    suspend fun setNotifyOnWorkDone(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.NOTIFY_ON_WORK_DONE] = enabled
        }
    }

    /**
     * How many times the user has seen the "safe to close the app — we'll
     * keep working" caption under the Home processing pill. After
     * [PROCESSING_HINT_MAX_SHOWS] occurrences we hide it — by then the user
     * has internalised that voice notes finish in the background and the
     * extra copy is just noise.
     */
    val processingHintShownCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.PROCESSING_HINT_SHOWN_COUNT] ?: 0
    }

    suspend fun incrementProcessingHintShown() {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.PROCESSING_HINT_SHOWN_COUNT] ?: 0
            prefs[Keys.PROCESSING_HINT_SHOWN_COUNT] = current + 1
        }
    }

    /** Digest fire time as "HH:mm" in device-local. Default: "08:00". */
    val digestTime: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.DIGEST_TIME] ?: "08:00"
    }

    suspend fun setDigestTime(hhmm: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DIGEST_TIME] = hhmm
        }
    }

    // ── LLM sampler tuning ──
    // These control how the model picks each next token. Changes take effect on
    // the next Conversation creation (sampler params) or the next engine
    // load (maxTokens — requires unloadModel() to rebuild the KV cache).

    val llmTopK: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.LLM_TOP_K] ?: DEFAULT_TOP_K
    }

    suspend fun setLlmTopK(value: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.LLM_TOP_K] = value }
    }

    val llmTopP: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[Keys.LLM_TOP_P] ?: DEFAULT_TOP_P
    }

    suspend fun setLlmTopP(value: Float) {
        context.dataStore.edit { prefs -> prefs[Keys.LLM_TOP_P] = value }
    }

    val llmTemperature: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[Keys.LLM_TEMPERATURE] ?: DEFAULT_TEMPERATURE
    }

    suspend fun setLlmTemperature(value: Float) {
        context.dataStore.edit { prefs -> prefs[Keys.LLM_TEMPERATURE] = value }
    }

    val llmMaxTokens: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.LLM_MAX_TOKENS] ?: DEFAULT_MAX_TOKENS
    }

    suspend fun setLlmMaxTokens(value: Int) {
        context.dataStore.edit { prefs -> prefs[Keys.LLM_MAX_TOKENS] = value }
    }

    /**
     * Hour-of-day (0-23) to use for timeless reminders — i.e. when the user
     * says "remind me to X" without stating a time. Same hour also used as
     * the default when a date was given without a clock ("remind me tomorrow"
     * → tomorrow at this hour).
     *
     * Default: 9 (9 AM) — typical "start of day" reminder time. Matches what
     * Apple Reminders and Google Tasks use for all-day reminders.
     */
    val defaultReminderHour: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_REMINDER_HOUR] ?: DEFAULT_REMINDER_HOUR_VALUE
    }

    suspend fun setDefaultReminderHour(hour: Int) {
        val clamped = hour.coerceIn(0, 23)
        context.dataStore.edit { prefs -> prefs[Keys.DEFAULT_REMINDER_HOUR] = clamped }
    }

    // ── Backend benchmark (one-time onboarding device-fit test) ──
    // Runs the model on both GPU and CPU during onboarding to pick the backend
    // that actually works on the user's device. The state machine is exposed
    // as multiple Flows so the ModelDownloadScreen can render progress in
    // real time while BackendBenchmark runs in the :ai process. Cleared on
    // app reinstall (DataStore lives in app's data dir).

    /** "idle" before run, "running" mid-test, "complete" on success,
     *  "failed" if both backends crashed. */
    val backendBenchmarkStatus: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.BACKEND_BENCHMARK_STATUS] ?: BENCH_STATUS_IDLE
    }

    /** Human-readable phase for UI: "gpu_load", "gpu_warmup", "gpu_measure",
     *  "cpu_load", "cpu_warmup", "cpu_measure", or "" (empty when idle). */
    val backendBenchmarkPhase: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.BACKEND_BENCHMARK_PHASE] ?: ""
    }

    /** Measurement-run inference time in ms. -1 = backend failed (init or
     *  inference crash). 0 = not yet measured. */
    val backendBenchmarkGpuMs: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.BACKEND_BENCHMARK_GPU_MS] ?: 0
    }

    val backendBenchmarkCpuMs: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.BACKEND_BENCHMARK_CPU_MS] ?: 0
    }

    /** "gpu" / "cpu" / "none" (both failed) / "" (not yet run). */
    val backendBenchmarkWinner: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.BACKEND_BENCHMARK_WINNER] ?: ""
    }

    /** "GREEN" / "YELLOW" / "RED" / "BLOCKED" / "" (not yet run).
     *  Computed from the winning backend's measurement time at result time. */
    val backendBenchmarkTier: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.BACKEND_BENCHMARK_TIER] ?: ""
    }

    suspend fun setBackendBenchmarkStatus(status: String, phase: String = "") {
        context.dataStore.edit { prefs ->
            prefs[Keys.BACKEND_BENCHMARK_STATUS] = status
            prefs[Keys.BACKEND_BENCHMARK_PHASE] = phase
        }
    }

    /**
     * Persist a partial result mid-run so the UI can show "GPU done" before
     * CPU finishes. Pass only the field that has new data; the other stays
     * untouched. Status and winner are NOT changed — that happens only on
     * final [setBackendBenchmarkResult].
     */
    suspend fun setBackendBenchmarkPartial(gpuMs: Int? = null, cpuMs: Int? = null) {
        context.dataStore.edit { prefs ->
            if (gpuMs != null) prefs[Keys.BACKEND_BENCHMARK_GPU_MS] = gpuMs
            if (cpuMs != null) prefs[Keys.BACKEND_BENCHMARK_CPU_MS] = cpuMs
        }
    }

    suspend fun setBackendBenchmarkResult(gpuMs: Int, cpuMs: Int, winner: String, tier: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.BACKEND_BENCHMARK_GPU_MS] = gpuMs
            prefs[Keys.BACKEND_BENCHMARK_CPU_MS] = cpuMs
            prefs[Keys.BACKEND_BENCHMARK_WINNER] = winner
            prefs[Keys.BACKEND_BENCHMARK_TIER] = tier
            prefs[Keys.BACKEND_BENCHMARK_STATUS] = if (winner == BENCH_WINNER_NONE) BENCH_STATUS_FAILED else BENCH_STATUS_COMPLETE
            prefs[Keys.BACKEND_BENCHMARK_PHASE] = ""
        }
    }

    // ── Indexing-backlog notification cadence ──
    // Cadence between notifications when pending count stays > 100:
    // first notification fires 1 day after the threshold is crossed,
    // then 3 days, then 7, then 14, then capped at 30. Reset to step 0
    // and clear the timestamps when pending drops back at or below 100.

    val indexingNotifLastAt: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.INDEXING_NOTIF_LAST_AT] ?: 0L
    }
    val indexingNotifFirstSeenAt: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[Keys.INDEXING_NOTIF_FIRST_SEEN_AT] ?: 0L
    }
    val indexingNotifStep: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.INDEXING_NOTIF_STEP] ?: 0
    }

    suspend fun setIndexingNotifFirstSeenAt(value: Long) {
        context.dataStore.edit { it[Keys.INDEXING_NOTIF_FIRST_SEEN_AT] = value }
    }

    suspend fun recordIndexingNotifFired(now: Long, nextStep: Int) {
        context.dataStore.edit {
            it[Keys.INDEXING_NOTIF_LAST_AT] = now
            it[Keys.INDEXING_NOTIF_STEP] = nextStep
        }
    }

    suspend fun resetIndexingNotifState() {
        context.dataStore.edit {
            it[Keys.INDEXING_NOTIF_LAST_AT] = 0L
            it[Keys.INDEXING_NOTIF_FIRST_SEEN_AT] = 0L
            it[Keys.INDEXING_NOTIF_STEP] = 0
        }
    }

    suspend fun resetLlmSamplerDefaults() {
        context.dataStore.edit { prefs ->
            prefs[Keys.LLM_TOP_K] = DEFAULT_TOP_K
            prefs[Keys.LLM_TOP_P] = DEFAULT_TOP_P
            prefs[Keys.LLM_TEMPERATURE] = DEFAULT_TEMPERATURE
            prefs[Keys.LLM_MAX_TOKENS] = DEFAULT_MAX_TOKENS
        }
    }

    // ── Backup / restore ────────────────────────────────────────
    //
    // The backup whitelist is intentional, not a `prefs.data.first()` dump.
    // Per-device benchmark results and indexing-notification cadence state
    // are NOT included — those are device-local concerns that should be
    // recomputed on the destination, not carried across.

    /**
     * Snapshot every preference we want to round-trip across export/import.
     * `null` for any key the user never explicitly set — restore then
     * leaves the local default in place rather than overwriting with
     * something the user didn't actually choose.
     */
    suspend fun snapshot(): BackupPrefs {
        val data = context.dataStore.data.first()
        return BackupPrefs(
            hasCompletedOnboarding = data[Keys.HAS_COMPLETED_ONBOARDING],
            themeMode = data[Keys.THEME_MODE],
            language = data[Keys.LANGUAGE],
            sttEngine = null, // legacy field, no longer set (Whisper removal Phase 6)
            llmBackend = data[Keys.LLM_BACKEND],
            selectedModelId = data[Keys.SELECTED_MODEL_ID],
            timeFormat = data[Keys.TIME_FORMAT],
            digestEnabled = data[Keys.DIGEST_ENABLED],
            digestTime = data[Keys.DIGEST_TIME],
            notifyOnWorkDone = data[Keys.NOTIFY_ON_WORK_DONE],
            processingHintShownCount = data[Keys.PROCESSING_HINT_SHOWN_COUNT],
            llmTopK = data[Keys.LLM_TOP_K],
            llmTopP = data[Keys.LLM_TOP_P],
            llmTemperature = data[Keys.LLM_TEMPERATURE],
            llmMaxTokens = data[Keys.LLM_MAX_TOKENS],
            defaultReminderHour = data[Keys.DEFAULT_REMINDER_HOUR],
            hasShownMicRationale = data[Keys.HAS_SHOWN_MIC_RATIONALE],
            hasShownNotifRationale = data[Keys.HAS_SHOWN_NOTIF_RATIONALE],
            hideCompletedItems = data[Keys.HIDE_COMPLETED_ITEMS],
            voiceEntryCount = data[Keys.VOICE_ENTRY_COUNT],
            hasShownTypeHint = data[Keys.HAS_SHOWN_TYPE_HINT],
            hasShownAssistantHint = data[Keys.HAS_SHOWN_ASSISTANT_HINT],
            hasShownBatteryHint = data[Keys.HAS_SHOWN_BATTERY_HINT],
            devModeEnabled = data[Keys.DEV_MODE_ENABLED],
            lastKnownTimeZoneId = data[Keys.LAST_KNOWN_TZ_ID],
        )
    }

    /**
     * Restore preferences from a [BackupPrefs] snapshot. Only non-null
     * fields are written, so a sparse snapshot (older format that didn't
     * include some key) leaves the user's current local value alone.
     *
     * Runs inside a single DataStore edit transaction — either every
     * field lands or none do.
     */
    suspend fun restore(snapshot: BackupPrefs) {
        context.dataStore.edit { prefs ->
            snapshot.hasCompletedOnboarding?.let { prefs[Keys.HAS_COMPLETED_ONBOARDING] = it }
            snapshot.themeMode?.let { prefs[Keys.THEME_MODE] = it }
            snapshot.language?.let { prefs[Keys.LANGUAGE] = it }
            // snapshot.sttEngine ignored on restore — STT_ENGINE key gone in Phase 6.
            snapshot.llmBackend?.let { prefs[Keys.LLM_BACKEND] = it }
            snapshot.selectedModelId?.let { prefs[Keys.SELECTED_MODEL_ID] = it }
            snapshot.timeFormat?.let { prefs[Keys.TIME_FORMAT] = it }
            snapshot.digestEnabled?.let { prefs[Keys.DIGEST_ENABLED] = it }
            snapshot.digestTime?.let { prefs[Keys.DIGEST_TIME] = it }
            snapshot.notifyOnWorkDone?.let { prefs[Keys.NOTIFY_ON_WORK_DONE] = it }
            snapshot.processingHintShownCount?.let { prefs[Keys.PROCESSING_HINT_SHOWN_COUNT] = it }
            snapshot.llmTopK?.let { prefs[Keys.LLM_TOP_K] = it }
            snapshot.llmTopP?.let { prefs[Keys.LLM_TOP_P] = it }
            snapshot.llmTemperature?.let { prefs[Keys.LLM_TEMPERATURE] = it }
            snapshot.llmMaxTokens?.let { prefs[Keys.LLM_MAX_TOKENS] = it }
            snapshot.defaultReminderHour?.let { prefs[Keys.DEFAULT_REMINDER_HOUR] = it.coerceIn(0, 23) }
            snapshot.hasShownMicRationale?.let { prefs[Keys.HAS_SHOWN_MIC_RATIONALE] = it }
            snapshot.hasShownNotifRationale?.let { prefs[Keys.HAS_SHOWN_NOTIF_RATIONALE] = it }
            snapshot.hideCompletedItems?.let { prefs[Keys.HIDE_COMPLETED_ITEMS] = it }
            snapshot.voiceEntryCount?.let { prefs[Keys.VOICE_ENTRY_COUNT] = it }
            snapshot.hasShownTypeHint?.let { prefs[Keys.HAS_SHOWN_TYPE_HINT] = it }
            snapshot.hasShownAssistantHint?.let { prefs[Keys.HAS_SHOWN_ASSISTANT_HINT] = it }
            snapshot.hasShownBatteryHint?.let { prefs[Keys.HAS_SHOWN_BATTERY_HINT] = it }
            snapshot.devModeEnabled?.let { prefs[Keys.DEV_MODE_ENABLED] = it }
            snapshot.lastKnownTimeZoneId?.let { prefs[Keys.LAST_KNOWN_TZ_ID] = it }
        }
    }

    companion object {
        // STT_GOOGLE / STT_WHISPER_SMALL constants removed in Phase 6 of
        // the Whisper-removal migration. speech handles all transcription.
        const val LLM_BACKEND_GPU = "gpu"
        const val LLM_BACKEND_CPU = "cpu"
        const val TIME_FORMAT_12H = "12h"
        const val TIME_FORMAT_24H = "24h"

        // Bol's defaults: greedy + deterministic, optimized for JSON / tool-call
        // structured outputs. Users can tune via Settings for experimentation.
        //
        // DEFAULT_MAX_TOKENS = 4000 matches AI Edge Gallery's per-model
        // allowlist entry for the task model
        // (gallery/model_allowlists/1_0_12.json — "maxTokens": 4000), which
        // is proven to fit on 8 GB+ phones running the same weights. Users
        // can lower it via Settings if they hit memory pressure on a
        // smaller device.
        const val DEFAULT_TOP_K = 1
        const val DEFAULT_TOP_P = 1.0f
        const val DEFAULT_TEMPERATURE = 0.3f
        const val DEFAULT_MAX_TOKENS = 4000

        /** Default hour of day for timeless reminders (0-23). 9 AM. */
        const val DEFAULT_REMINDER_HOUR_VALUE = 9

        /**
         * Show the "safe to close the app" caption under the processing pill
         * for the user's first N voice-processing sessions, then drop it.
         * 3 is enough repetitions to teach the background-work model without
         * becoming permanent visual clutter.
         */
        const val PROCESSING_HINT_MAX_SHOWS = 3

        // Backend benchmark status values.
        const val BENCH_STATUS_IDLE = "idle"
        const val BENCH_STATUS_RUNNING = "running"
        const val BENCH_STATUS_COMPLETE = "complete"
        const val BENCH_STATUS_FAILED = "failed"

        // Backend benchmark winner values.
        const val BENCH_WINNER_GPU = "gpu"
        const val BENCH_WINNER_CPU = "cpu"
        const val BENCH_WINNER_NONE = "none"

        // Phase strings the UI will show during onboarding.
        const val BENCH_PHASE_GPU_LOAD = "gpu_load"
        const val BENCH_PHASE_GPU_WARMUP = "gpu_warmup"
        const val BENCH_PHASE_GPU_MEASURE = "gpu_measure"
        // Second GPU pass with speculative decoding off (see BackendBenchmark).
        const val BENCH_PHASE_GPU_PLAIN_LOAD = "gpu_plain_load"
        const val BENCH_PHASE_GPU_PLAIN_WARMUP = "gpu_plain_warmup"
        const val BENCH_PHASE_GPU_PLAIN_MEASURE = "gpu_plain_measure"
        const val BENCH_PHASE_CPU_LOAD = "cpu_load"
        const val BENCH_PHASE_CPU_WARMUP = "cpu_warmup"
        const val BENCH_PHASE_CPU_MEASURE = "cpu_measure"
    }
}
