package com.bol.app.ui.screens.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bol.app.ai.audio.AudioRecorder
import com.bol.app.ai.audio.WavConverter
import com.bol.app.data.local.entity.SearchQueryEntity
import com.bol.app.data.local.entity.SearchQueryStatus
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.repository.SearchQueryRepository
import com.bol.app.service.BolProcessingService
import com.bol.app.service.RecordingSignal
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class SearchResultItem(
    val id: String,
    val query: String,
    val answer: String?,
    val sources: List<SourceInfo>,
    val status: String,
    val createdAt: Long
)

data class SourceInfo(
    val id: String,
    val snippet: String,
    val createdAt: Long
)

/**
 * Search screen ViewModel.
 *
 * Post-Whisper-removal: voice search captures raw audio via [AudioRecorder],
 * writes the WAV under search_audio/, inserts a "[audio]" SearchQueryEntity,
 * and lets [com.bol.app.domain.usecase.SearchProcessingUseCase] transcribe
 * via speech on the same engine that runs the synthesis. No live
 * partial transcript during recording (the live-text path was Google STT
 * only and is gone).
 */
class SearchViewModel(
    private val searchQueryRepository: SearchQueryRepository,
    private val applicationContext: Context,
    preferencesManager: PreferencesManager,
    private val recordingSignal: RecordingSignal,
    private val indexingCoordinator: com.bol.app.ai.indexing.IndexingCoordinator,
    private val modelManager: com.bol.app.ai.model.ModelManager,
) : ViewModel() {

    // Voice input state
    private val _voiceState = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val voiceState: StateFlow<VoiceInputState> = _voiceState

    private val _queryText = MutableStateFlow("")
    val queryText: StateFlow<String> = _queryText

    /** User's 12h/24h clock preference — drives result card timestamp format. */
    val timeFormat: Flow<String> = preferencesManager.timeFormat

    /** Last query the user removed via long-press, kept around for the
     *  snackbar Undo action. */
    private val _lastDeleted = MutableStateFlow<SearchQueryEntity?>(null)
    val lastDeleted: StateFlow<SearchQueryEntity?> = _lastDeleted

    private val recorder = AudioRecorder()
    @Volatile private var voiceActive = false
    /** Stop was tapped: the audio is on its way (~256 ms) and is a search, even if the screen goes away meanwhile. */
    @Volatile private var stopRequested = false

    // One-shot stream of search IDs that the screen should navigate to as
    // soon as the user submits a query (text or voice). The detail screen
    // renders PENDING/PROCESSING states live.
    private val _openDetail = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val openDetail: SharedFlow<String> = _openDetail.asSharedFlow()

    val searchHistory: StateFlow<List<SearchResultItem>> = searchQueryRepository.getAll()
        .map { queries -> queries.map { it.toResultItem() } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        // Pipe AudioRecorder state into voice flow.
        viewModelScope.launch {
            recorder.state.collect { state ->
                if (!voiceActive) return@collect
                when (state) {
                    is AudioRecorder.State.Recording -> {
                        // Voice listening UI — no live partial text since
                        // we're capturing raw audio for background transcribe.
                        if (_voiceState.value !is VoiceInputState.Listening) {
                            _voiceState.value = VoiceInputState.Listening()
                        }
                    }
                    is AudioRecorder.State.Captured -> {
                        voiceActive = false
                        stopRequested = false
                        _voiceState.value = VoiceInputState.Idle
                        recordingSignal.set(false)
                        onAudioCaptured(state.pcmBytes)
                    }
                    is AudioRecorder.State.Error -> {
                        voiceActive = false
                        stopRequested = false
                        _voiceState.value = VoiceInputState.Idle
                        recordingSignal.set(false)
                    }
                    AudioRecorder.State.Idle -> { /* no-op */ }
                }
            }
        }
    }

    fun updateQuery(text: String) {
        _queryText.value = text
    }

    fun submitQuery() {
        val query = _queryText.value.trim()
        if (query.isBlank()) return
        stopVoiceIfActive()
        enqueueSearch(query)
    }

    /** Submit an arbitrary text query — used by the suggestion chips. */
    fun submitText(text: String) {
        val q = text.trim()
        if (q.isBlank()) return
        stopVoiceIfActive()
        enqueueSearch(q)
    }

    fun deleteSearch(id: String) {
        viewModelScope.launch {
            val full = searchQueryRepository.getById(id) ?: return@launch
            _lastDeleted.value = full
            searchQueryRepository.deleteById(id)
        }
    }

    fun undoDelete() {
        val deleted = _lastDeleted.value ?: return
        viewModelScope.launch {
            searchQueryRepository.insert(deleted)
            _lastDeleted.value = null
        }
    }

    fun clearLastDeleted() {
        _lastDeleted.value = null
    }

    fun startVoiceInput() {
        if (voiceActive) return
        if (_voiceState.value is VoiceInputState.Listening) return
        voiceActive = true
        stopRequested = false
        _voiceState.value = VoiceInputState.Listening()
        recordingSignal.set(true)
        recorder.start()
    }

    fun stopVoiceInput() {
        if (voiceActive) {
            stopRequested = true
            recorder.stop()
        }
    }

    /** Hard-cancel voice capture. Used by SearchScreen's DisposableEffect. */
    fun cancelVoiceInput() {
        stopVoiceIfActive()
    }

    private fun stopVoiceIfActive() {
        // After Stop, the capture is finishing as a search; discarding it here (leaving the screen) lost it.
        if (voiceActive && !stopRequested) {
            voiceActive = false
            recorder.release()
            _voiceState.value = VoiceInputState.Idle
            recordingSignal.set(false)
        }
    }

    private fun onAudioCaptured(pcmBytes: ByteArray) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    val queryEntity = SearchQueryEntity(query = "[audio]")
                    val audioDir = File(applicationContext.filesDir, "search_audio")
                    audioDir.mkdirs()
                    val wavFile = File(audioDir, "${queryEntity.id}.wav")
                    WavConverter.pcmToWav(pcmBytes, wavFile)
                    searchQueryRepository.insert(queryEntity)
                    _openDetail.tryEmit(queryEntity.id)
                    BolProcessingService.startProcessing(applicationContext)
                } catch (e: Exception) {
                    android.util.Log.e("SearchVM", "Audio capture failed", e)
                }
            }
        }
        // The query opens in its own screen; leaving text here made the search button search for that text.
        _queryText.value = ""
    }

    private fun enqueueSearch(query: String) {
        viewModelScope.launch {
            withContext(NonCancellable) {
                val queryEntity = SearchQueryEntity(query = query)
                searchQueryRepository.insert(queryEntity)
                _openDetail.tryEmit(queryEntity.id)

                // Provisional items waiting for the task model change what items say, so the search waits for
                // them (bounded by a 10 min cap). It never waits for search vectors: the router ranks the docs
                // that have one and BM25 covers the rest, and the backlog keeps running beside the search.
                if (indexingCoordinator.pendingTotal.value > 0) indexingCoordinator.start()
                if (indexingCoordinator.pendingUpgrades.value == 0) {
                    BolProcessingService.startProcessing(applicationContext)
                } else {
                    viewModelScope.launch {
                        kotlinx.coroutines.withTimeoutOrNull(SEARCH_INDEXING_WAIT_MS) {
                            indexingCoordinator.pendingUpgrades.first { it == 0 }
                        }
                        BolProcessingService.startProcessing(applicationContext)
                    }
                }
            }
        }
        _queryText.value = ""
    }

    private fun SearchQueryEntity.toResultItem(): SearchResultItem {
        val sources = sourcesJson?.let { json ->
            try {
                Json.parseToJsonElement(json).jsonArray.map { el ->
                    val obj = el.jsonObject
                    SourceInfo(
                        id = obj["id"]?.jsonPrimitive?.content ?: "",
                        snippet = obj["snippet"]?.jsonPrimitive?.content ?: "",
                        createdAt = obj["createdAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    )
                }
            } catch (_: Exception) { emptyList() }
        } ?: emptyList()

        return SearchResultItem(
            id = id,
            query = query,
            answer = answer,
            sources = sources,
            status = status,
            createdAt = createdAt
        )
    }

    override fun onCleared() {
        recorder.release()
        recordingSignal.set(false)
        super.onCleared()
    }

    companion object {
        /** Hard cap on how long a search submission will wait for indexing. */
        private const val SEARCH_INDEXING_WAIT_MS = 10L * 60 * 1000
    }
}

sealed class VoiceInputState {
    data object Idle : VoiceInputState()
    /** [partialText] kept for binary-compat with existing UI; always empty
     *  now (live partials were Google STT only). */
    data class Listening(val partialText: String = "") : VoiceInputState()
}
