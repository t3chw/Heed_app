package com.bol.app.ui.screens.history

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
import com.bol.app.data.repository.LlmUpgradeRepository
import com.bol.app.data.repository.RecordingRepository
import com.bol.app.data.repository.VoiceQueueRepository
import com.bol.app.service.BolProcessingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Snapshot of the History audio-playback layer. [playingRecordingId] is
 * the key UI consults ("is my row the one currently under playback?");
 * [isPlaying] distinguishes playing vs paused for the icon morph; the
 * fraction + duration drive the progress ring.
 */
data class PlaybackState(
    val playingRecordingId: String? = null,
    val isPlaying: Boolean = false,
    val progressFraction: Float = 0f,
    val totalDurationMs: Int = 0,
)

/** Retry on a note that only ever had audio, when that audio is gone (backups keep text only). */
const val NO_AUDIO_TO_RETRY = "This note's audio isn't on this phone, so it can't be processed again."

class HistoryViewModel(
    private val recordingRepository: RecordingRepository,
    private val itemRepository: ItemRepository,
    private val categoryRepository: CategoryRepository,
    llmUpgradeRepository: LlmUpgradeRepository,
    preferencesManager: PreferencesManager,
    private val voiceQueueRepository: VoiceQueueRepository,
    private val appContext: Context,
) : ViewModel() {

    // Lists that drive the LazyColumn are StateFlows so the ViewModel
    // keeps cached values across navigation. Collecting them cold would
    // flicker the screen through an empty frame on re-entry and clamp
    // rememberLazyListState's saved scroll position (same bug class we
    // fixed on Home). Eagerly keeps them live as long as the ViewModel
    // scope is alive.
    val recordings: StateFlow<List<RecordingEntity>> =
        recordingRepository.getAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository.getAllActive()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingUpgradeIds: StateFlow<List<String>> =
        llmUpgradeRepository.getPendingRecordingIds()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val timeFormat: Flow<String> = preferencesManager.timeFormat

    private val _expandedRecordingId = MutableStateFlow<String?>(null)
    val expandedRecordingId: StateFlow<String?> = _expandedRecordingId

    fun toggleExpanded(recordingId: String) {
        _expandedRecordingId.value =
            if (_expandedRecordingId.value == recordingId) null else recordingId
    }

    fun getItemsForRecording(recordingId: String): Flow<List<ItemEntity>> =
        itemRepository.getByRecordingId(recordingId)

    /** Re-enqueue a cancelled recording for processing. The WAV (or saved
     *  transcript) is preserved from the original run, so the pipeline can
     *  re-run from wherever it needs to — Whisper if rawText is "[audio]",
     *  straight to the LLM otherwise. Starts the foreground service so the
     *  entry is picked up immediately. */
    fun retryRecording(recordingId: String) {
        viewModelScope.launch {
            // A note with no transcript needs its audio, and a restored backup has none: the retry could only fail.
            val recording = recordingRepository.getById(recordingId) ?: return@launch
            if (recording.rawText == "[audio]" && !recordingRepository.hasAudio(recordingId)) {
                android.widget.Toast.makeText(appContext, NO_AUDIO_TO_RETRY, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            voiceQueueRepository.retryRecording(recordingId)
            BolProcessingService.startProcessing(appContext)
        }
    }

    // -------- Playback --------

    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    /** Single MediaPlayer for the whole screen — only one recording plays
     *  at a time. Tapping a different row stops the current instance. */
    private var mediaPlayer: MediaPlayer? = null
    /** Polls [MediaPlayer.getCurrentPosition] at ~10fps while playback is
     *  active so the progress ring animates smoothly without driving the
     *  UI from inside a Compose recomposition loop. */
    private var progressJob: Job? = null

    fun hasAudio(recordingId: String): Boolean =
        recordingRepository.hasAudio(recordingId)

    /**
     * Tap handler on the mic chip. Behavior:
     * - No audio on disk → no-op.
     * - Same recording already under playback → toggle pause/resume.
     * - Different recording → stop the current, start this one from 0.
     */
    fun togglePlayback(recordingId: String) {
        val path = recordingRepository.audioPath(recordingId) ?: return
        val current = _playback.value
        if (current.playingRecordingId == recordingId && mediaPlayer != null) {
            // Pause/resume the same track.
            val player = mediaPlayer ?: return
            if (current.isPlaying) {
                runCatching { player.pause() }
                progressJob?.cancel()
                progressJob = null
                _playback.value = current.copy(isPlaying = false)
            } else {
                runCatching { player.start() }
                _playback.value = current.copy(isPlaying = true)
                startProgressUpdates()
            }
        } else {
            // New recording — release any existing player and prepare this one.
            releasePlayer()
            startNewPlayback(recordingId, path)
        }
    }

    /** Long-press handler — stops playback entirely and resets the ring. */
    fun stopPlayback() {
        releasePlayer()
        _playback.value = PlaybackState()
    }

    private fun startNewPlayback(recordingId: String, wavPath: String) {
        val player = MediaPlayer()
        try {
            player.setDataSource(wavPath)
            player.setOnPreparedListener {
                runCatching { it.start() }
                val total = runCatching { it.duration.coerceAtLeast(1) }.getOrDefault(1)
                _playback.value = PlaybackState(
                    playingRecordingId = recordingId,
                    isPlaying = true,
                    progressFraction = 0f,
                    totalDurationMs = total,
                )
                startProgressUpdates()
            }
            player.setOnCompletionListener {
                // Audio finished on its own — reset cleanly.
                stopPlayback()
            }
            player.setOnErrorListener { _, _, _ ->
                stopPlayback()
                true
            }
            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            runCatching { player.release() }
            mediaPlayer = null
            _playback.value = PlaybackState()
        }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                val p = mediaPlayer
                if (p != null) {
                    val playing = runCatching { p.isPlaying }.getOrDefault(false)
                    if (playing) {
                        val pos = runCatching { p.currentPosition }.getOrDefault(0)
                        val total = _playback.value.totalDurationMs
                        val frac = if (total > 0) {
                            (pos.toFloat() / total).coerceIn(0f, 1f)
                        } else 0f
                        _playback.value = _playback.value.copy(progressFraction = frac)
                    }
                }
                delay(100)
            }
        }
    }

    private fun releasePlayer() {
        progressJob?.cancel()
        progressJob = null
        mediaPlayer?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        mediaPlayer = null
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }
}
