package com.bol.app.ui.screens.recording.detail

import android.content.Context
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bol.app.data.local.entity.CategoryEntity
import com.bol.app.data.local.entity.ItemEntity
import com.bol.app.data.local.entity.RecordingEntity
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.repository.CategoryRepository
import com.bol.app.data.repository.ItemRepository
import com.bol.app.data.repository.RecordingRepository
import com.bol.app.data.repository.VoiceQueueRepository
import com.bol.app.domain.usecase.QueueProcessingUseCase
import com.bol.app.service.BolProcessingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI state for the audio playback layer of the Recording Detail screen.
 * Mirrors History's PlaybackState — duplicated here rather than shared
 * because each screen owns its own MediaPlayer (navigating between them
 * stops the old one, starts the new one if the user taps play again).
 */
data class RecordingDetailPlayback(
    val isPlaying: Boolean = false,
    val progressFraction: Float = 0f,
    val currentMs: Int = 0,
    val totalMs: Int = 0,
)

/**
 * ViewModel for the Recording Detail screen. Exposes everything the
 * screen needs — the recording itself, its items, all active
 * categories, playback state, and a pile of action methods that
 * mirror CategoryDetail's behavior (move / edit / set reminder /
 * delete per item) plus a top-level delete-recording action that
 * cascades through items + queue + WAV.
 */
class RecordingDetailViewModel(
    private val recordingId: String,
    private val recordingRepository: RecordingRepository,
    private val itemRepository: ItemRepository,
    private val categoryRepository: CategoryRepository,
    private val voiceQueueRepository: VoiceQueueRepository,
    private val queueProcessingUseCase: QueueProcessingUseCase,
    preferencesManager: PreferencesManager,
    private val appContext: Context,
) : ViewModel() {

    /** True while [editTranscription] is mid-flight. The screen renders a
     *  small pill in place of the transcript while this is true so the
     *  user knows the LLM is re-categorizing rather than guessing the
     *  edit got lost. */
    private val _isReprocessing = MutableStateFlow(false)
    val isReprocessing: StateFlow<Boolean> = _isReprocessing.asStateFlow()

    // Recording metadata. Re-queried on every screen focus so an
    // in-flight status change (e.g. retry completes) shows up live.
    private val _recording = MutableStateFlow<RecordingEntity?>(null)
    val recording: StateFlow<RecordingEntity?> = _recording.asStateFlow()

    /** Every item the pipeline extracted from this recording, live. */
    val items: StateFlow<List<ItemEntity>> =
        itemRepository.getByRecordingId(recordingId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** All active categories — used by the category picker + rendering
     *  the item's current category chip. */
    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository.getAllActive()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val timeFormat: Flow<String> = preferencesManager.timeFormat

    init {
        refreshRecording()
    }

    /** Re-read the recording row from DB. Called on init + after any
     *  mutation that could change its state (retry, flip cancelled). */
    fun refreshRecording() {
        viewModelScope.launch {
            _recording.value = recordingRepository.getById(recordingId)
        }
    }

    fun hasAudio(): Boolean = recordingRepository.hasAudio(recordingId)

    // -------- Playback --------

    private val _playback = MutableStateFlow(RecordingDetailPlayback())
    val playback: StateFlow<RecordingDetailPlayback> = _playback.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    fun togglePlayback() {
        val path = recordingRepository.audioPath(recordingId) ?: return
        val current = _playback.value
        val player = mediaPlayer
        if (player != null && player.isPlayingSafe()) {
            // Pause
            runCatching { player.pause() }
            progressJob?.cancel()
            progressJob = null
            _playback.value = current.copy(isPlaying = false)
            return
        }
        if (player != null) {
            // Paused → resume
            runCatching { player.start() }
            _playback.value = current.copy(isPlaying = true)
            startProgressUpdates()
            return
        }
        startNewPlayback(path)
    }

    fun stopPlayback() {
        releasePlayer()
        _playback.value = RecordingDetailPlayback()
    }

    private fun startNewPlayback(wavPath: String) {
        val player = MediaPlayer()
        try {
            player.setDataSource(wavPath)
            player.setOnPreparedListener {
                runCatching { it.start() }
                val total = runCatching { it.duration.coerceAtLeast(1) }.getOrDefault(1)
                _playback.value = RecordingDetailPlayback(
                    isPlaying = true,
                    progressFraction = 0f,
                    currentMs = 0,
                    totalMs = total,
                )
                startProgressUpdates()
            }
            player.setOnCompletionListener { stopPlayback() }
            player.setOnErrorListener { _, _, _ -> stopPlayback(); true }
            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            runCatching { player.release() }
            mediaPlayer = null
            _playback.value = RecordingDetailPlayback()
        }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                val p = mediaPlayer ?: break
                if (p.isPlayingSafe()) {
                    val pos = runCatching { p.currentPosition }.getOrDefault(0)
                    val total = _playback.value.totalMs
                    val frac = if (total > 0) (pos.toFloat() / total).coerceIn(0f, 1f) else 0f
                    _playback.value = _playback.value.copy(
                        currentMs = pos,
                        progressFraction = frac,
                    )
                }
                delay(100)
            }
        }
    }

    private fun releasePlayer() {
        progressJob?.cancel()
        progressJob = null
        mediaPlayer?.let { p ->
            runCatching { if (p.isPlayingSafe()) p.stop() }
            runCatching { p.release() }
        }
        mediaPlayer = null
    }

    private fun MediaPlayer.isPlayingSafe(): Boolean =
        runCatching { isPlaying }.getOrDefault(false)

    // -------- Per-item actions (mirror CategoryDetail) --------

    fun toggleItem(itemId: String) {
        viewModelScope.launch { itemRepository.toggleCompleted(itemId) }
    }

    fun setReminder(itemId: String, newTimeMs: Long) {
        viewModelScope.launch { itemRepository.setReminderTime(itemId, newTimeMs) }
    }

    fun clearReminder(itemId: String) {
        viewModelScope.launch { itemRepository.clearReminder(itemId) }
    }

    fun moveItem(itemId: String, newCategoryId: String) {
        viewModelScope.launch { itemRepository.moveItem(itemId, newCategoryId) }
    }

    /** In-place rename of an item's text. Bumps updatedAt so it sorts to
     *  the top of its category flow when that flow is ordered by modify
     *  time; text also feeds into FTS so it will reindex on the next
     *  search. */
    fun editItemText(itemId: String, newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val existing = itemRepository.getById(itemId) ?: return@launch
            if (existing.text == trimmed) return@launch
            itemRepository.update(
                existing.copy(text = trimmed, updatedAt = System.currentTimeMillis())
            )
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch { itemRepository.deleteById(itemId) }
    }

    // -------- Recording-level actions --------

    /**
     * Replace the recording's rawText with a user-edited version and
     * re-run categorization on it. Wipes the existing items (the ones
     * derived from the wrong Whisper transcription) and lets the LLM
     * (or rule-based parser) build a fresh set from the corrected text.
     *
     * Heavy: takes 5-15s on-device because the LLM has to re-parse. The
     * UI shows a "Re-categorizing…" state via [isReprocessing] until it
     * finishes. Wrapped in NonCancellable so a quick screen-leave doesn't
     * leave items half-deleted.
     */
    fun editTranscription(newText: String) {
        val trimmed = newText.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            _isReprocessing.value = true
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    queueProcessingUseCase.reprocessText(recordingId, trimmed)
                }
                refreshRecording()
            } finally {
                _isReprocessing.value = false
            }
        }
    }

    /**
     * Re-enqueue the recording. Mirrors HistoryViewModel.retryRecording.
     * Kicks the foreground service so the entry is picked up right away.
     */
    fun retryRecording() {
        viewModelScope.launch {
            val recording = recordingRepository.getById(recordingId) ?: return@launch
            if (recording.rawText == "[audio]" && !recordingRepository.hasAudio(recordingId)) {
                android.widget.Toast.makeText(appContext, com.bol.app.ui.screens.history.NO_AUDIO_TO_RETRY,
                    android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            voiceQueueRepository.retryRecording(recordingId)
            BolProcessingService.startProcessing(appContext)
            refreshRecording()
        }
    }

    /** Hard-delete this recording and everything derived from it. The
     *  screen is expected to navigate away immediately on user confirm;
     *  we also stop any in-flight playback so MediaPlayer isn't still
     *  holding the WAV we're about to remove. */
    fun deleteRecording(onDone: () -> Unit) {
        viewModelScope.launch {
            stopPlayback()
            recordingRepository.deleteRecordingAndArtifacts(recordingId)
            onDone()
        }
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }
}
