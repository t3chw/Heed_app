package com.bol.app.di

import androidx.room.Room
import com.bol.app.ai.search.SearchEngine
import com.bol.app.ai.model.ModelManager
import com.bol.app.ai.parser.IntentParser
import com.bol.app.ai.parser.OnDeviceIntentParser
import com.bol.app.ai.parser.RuleBasedIntentParser
import com.bol.app.data.local.BolDatabase
import com.bol.app.data.local.DatabaseCallback
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.repository.CategoryRepository
import com.bol.app.data.repository.ItemRepository
import com.bol.app.data.repository.LlmUpgradeRepository
import com.bol.app.data.repository.RecordingRepository
import com.bol.app.data.repository.VoiceQueueRepository
import com.bol.app.domain.usecase.ExportDataUseCase
import com.bol.app.domain.usecase.ImportDataUseCase
import com.bol.app.domain.usecase.LlmUpgradeUseCase
import com.bol.app.domain.usecase.ProcessVoiceUseCase
import com.bol.app.domain.usecase.QueueProcessingUseCase
import com.bol.app.ui.screens.detail.CategoryDetailViewModel
import com.bol.app.ui.screens.history.HistoryViewModel
import com.bol.app.ui.screens.home.HomeViewModel
import com.bol.app.ui.assist.QuickCaptureSaver
import com.bol.app.ui.assist.QuickCaptureViewModel
import com.bol.app.ui.screens.recording.RecordingViewModel
import com.bol.app.ui.screens.search.SearchViewModel
import com.bol.app.ui.screens.logs.LogViewerViewModel
import com.bol.app.ui.screens.settings.SettingsViewModel
import com.bol.app.domain.usecase.SearchProcessingUseCase
import com.bol.app.data.repository.SearchQueryRepository
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    // Database
    single {
        val scope = CoroutineScope(SupervisorJob())
        lateinit var database: BolDatabase
        database = Room.databaseBuilder(
            androidContext(),
            BolDatabase::class.java,
            "bol_database"
        ).addCallback(
            DatabaseCallback(scope) { database.categoryDao() }
        ).addMigrations(BolDatabase.MIGRATION_1_2, BolDatabase.MIGRATION_2_3, BolDatabase.MIGRATION_3_4, BolDatabase.MIGRATION_4_5, BolDatabase.MIGRATION_5_6, BolDatabase.MIGRATION_6_7, BolDatabase.MIGRATION_7_8, BolDatabase.MIGRATION_8_9, BolDatabase.MIGRATION_9_10, BolDatabase.MIGRATION_10_11, BolDatabase.MIGRATION_11_12, BolDatabase.MIGRATION_12_13, BolDatabase.MIGRATION_13_14, BolDatabase.MIGRATION_14_15, BolDatabase.MIGRATION_15_16, BolDatabase.MIGRATION_16_17, BolDatabase.MIGRATION_17_18, BolDatabase.MIGRATION_18_19, BolDatabase.MIGRATION_19_20, BolDatabase.MIGRATION_20_21)
        .enableMultiInstanceInvalidation()
        .build()
        database
    }

    // DAOs
    single { get<BolDatabase>().categoryDao() }
    single { get<BolDatabase>().itemDao() }
    single { get<BolDatabase>().recordingDao() }
    single { get<BolDatabase>().recordingTagDao() }
    single { get<BolDatabase>().userContextDao() }
    single { get<BolDatabase>().llmUpgradeDao() }
    single { get<BolDatabase>().voiceQueueDao() }
    single { get<BolDatabase>().searchQueryDao() }
    single { get<BolDatabase>().performanceLogDao() }
    single { get<BolDatabase>().itemEmbeddingDao() }
    single { get<BolDatabase>().itemTagDao() }
    single { get<BolDatabase>().itemEnrichmentDao() }
    single { get<BolDatabase>().evalQueryDao() }

    // Performance monitoring
    single { PerformanceMonitor(androidContext()) }

    // Repositories
    single { CategoryRepository(get()) }
    single { ItemRepository(get(), androidContext()) }
    single { LlmUpgradeRepository(get()) }
    single { RecordingRepository(get(), get(), androidContext(), get(), get(), get()) }
    single { VoiceQueueRepository(get(), get(), get()) }
    single { SearchQueryRepository(get()) }

    // Preferences
    single { PreferencesManager(androidContext()) }

    // Model managers
    single { ModelManager(androidContext()) }


    // Foreground-service performance levers (wake lock + perf hint session).
    // Singleton so AgentIntentParser can report inference durations into the
    // same hint session that BolProcessingService creates on acquire.
    single { com.bol.app.service.LockScreenPerf(androidContext()) }

    // Live pipeline-stage tracker. BolProcessingService writes; HomeViewModel
    // reads. Drives the stage-aware Home processing pill ("Listening back…"
    // → "Finding homes for it…") so users see motion instead of a static
    // "Sorting 1 recording" that reads as stuck.
    single { com.bol.app.ai.processing.ProcessingStageTracker() }

    // Deferred indexing — tag/enrich/upgrade/embedding catch-up that runs
    // ONLY while the app is foregrounded. Replaces Phase C of the old
    // BolProcessingService.processQueue() drain. Cancelled by
    // BolApplication's ProcessLifecycleOwner observer when app backgrounded.
    single {
        com.bol.app.ai.indexing.IndexingCoordinator(
            llmUpgradeUseCase = get(),
            llmUpgradeRepository = get(),
            voiceQueueRepository = get(),
            searchIndexer = get(),
        )
    }

    // (SpeechToTextEngine factory removed — voice capture now uses
    //  AudioRecorder directly across RecordingViewModel, SearchViewModel,
    //  SearchDetailViewModel. speech replaced Whisper transcription
    //  in Phase 4 of the migration.)

    // Search: router v2 — FTS5 BM25 + EmbeddingGemma, weighted RRF, date rule.
    // The index lives in its own SQLite file, kept in step with Room by SearchIndexer.
    single { com.bol.app.ai.retrieval.SearchIndexer(androidContext(), get(), get(), get(), get()) }
    single { SearchEngine(get(), get(), get(), get()) }

    // AI — Intent parsers (100% local)
    single<IntentParser> { OnDeviceIntentParser(get(), get()) }
    single { RuleBasedIntentParser() }


    // ViewModel for Settings → Scheduled reminders screen.
    viewModel { com.bol.app.ui.screens.reminders.ScheduledRemindersViewModel(get(), get(), get()) }

    // ViewModel for Settings → Restore from backup screen. Takes
    // androidApplication() so it can use the app's ContentResolver to
    // open the user-picked SAF URI.
    viewModel {
        com.bol.app.ui.screens.restore.RestoreViewModel(
            context = androidContext(),
            importDataUseCase = get(),
            indexingCoordinator = get(),
        )
    }

    // Use Cases
    single { ProcessVoiceUseCase(get(), get(), get(), get(), androidContext()) }
    // QueueProcessingUseCase no longer takes whisperModelManager — the model
    // audio replaced Whisper transcription in Phase 4 of the migration.
    single { QueueProcessingUseCase(get(), get(), get(), get(), get(), get(), get(), get(), androidContext()) }
    single { LlmUpgradeUseCase(get(), get(), get(), get(), get()) }
    single {
        ExportDataUseCase(
            database = get(),
            categoryDao = get(),
            itemDao = get(),
            recordingDao = get(),
            itemTagDao = get(),
            recordingTagDao = get(),
            itemEnrichmentDao = get(),
            searchQueryDao = get(),
            userContextDao = get(),
            preferencesManager = get(),
        )
    }
    single {
        ImportDataUseCase(
            database = get(),
            categoryDao = get(),
            itemDao = get(),
            recordingDao = get(),
            itemTagDao = get(),
            recordingTagDao = get(),
            itemEnrichmentDao = get(),
            searchQueryDao = get(),
            userContextDao = get(),
            preferencesManager = get(),
            itemRepository = get(),
            appContext = androidContext(),
        )
    }
    // SearchProcessingUseCase no longer takes whisperModelManager — the model
    // audio replaced Whisper for voice-search transcription in Phase 4.
    single { SearchProcessingUseCase(get(), get(), get(), androidContext()) }
    single {
        com.bol.app.domain.usecase.ClearDataUseCase(
            database = get(),
            categoryDao = get(),
            recordingDao = get(),
            voiceQueueDao = get(),
            llmUpgradeDao = get(),
            searchQueryDao = get(),
            userContextDao = get(),
            performanceLogDao = get(),
            itemRepository = get(),
            appContext = androidContext(),
        )
    }

    // ViewModels
    viewModel { HomeViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { com.bol.app.ui.screens.recent.AllRecentViewModel(get(), get()) }
    viewModel { params ->
        com.bol.app.ui.screens.task.TaskDetailViewModel(
            itemId = params.get(),
            itemRepository = get(),
            categoryRepository = get(),
            recordingRepository = get(),
            tagDao = get(),
            enrichmentDao = get(),
            preferencesManager = get(),
        )
    }
    viewModel { params -> CategoryDetailViewModel(params.get(), get(), get(), get()) }
    // RecordingSignal is a single so RecordingViewModel (Home scope) and
    // MainActivity (Activity scope) share the same instance. Lets the
    // nav bar / scrim react to recording state without callback plumbing.
    single { com.bol.app.service.RecordingSignal() }
    viewModel {
        RecordingViewModel(
            voiceQueueRepository = get(),
            recordingRepository = get(),
            applicationContext = androidContext(),
            recordingSignal = get(),
            preferencesManager = get(),
            modelManager = get(),
            captureSaver = get(),
        )
    }
    // Application-scoped persistor for captured audio (Quick Capture and the
    // in-app recorder). Survives any single activity / ViewModel lifecycle so
    // power-button stops, quick re-recordings and closed sheets still save.
    single { QuickCaptureSaver(androidContext(), get(), get()) }
    viewModel {
        QuickCaptureViewModel(saver = get(), modelManager = get(), context = androidContext())
    }
    viewModel { SettingsViewModel(get(), get(), get(), get(), androidContext()) }
    viewModel { HistoryViewModel(get(), get(), get(), get(), get(), get(), androidContext()) }
    viewModel { params ->
        com.bol.app.ui.screens.recording.detail.RecordingDetailViewModel(
            recordingId = params.get(),
            recordingRepository = get(),
            itemRepository = get(),
            categoryRepository = get(),
            voiceQueueRepository = get(),
            queueProcessingUseCase = get(),
            preferencesManager = get(),
            appContext = androidContext(),
        )
    }
    viewModel { LogViewerViewModel(get(), get()) }

    // Search — captures voice queries via AudioRecorder, transcribed by
    // speech in SearchProcessingUseCase. No more Whisper / Google STT.
    viewModel {
        SearchViewModel(
            searchQueryRepository = get(),
            applicationContext = androidContext(),
            preferencesManager = get(),
            recordingSignal = get(),
            indexingCoordinator = get(),
            modelManager = get(),
        )
    }
    viewModel { params ->
        com.bol.app.ui.screens.search.SearchDetailViewModel(
            searchId = params.get(),
            searchRepo = get(),
            itemRepo = get(),
            appContext = androidContext(),
            preferencesManager = get(),
            categoryRepository = get(),
            recordingSignal = get(),
            modelManager = get(),
        )
    }
}
