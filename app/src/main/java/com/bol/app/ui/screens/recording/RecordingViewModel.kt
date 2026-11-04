package com.bol.app.ui.screens.recording

import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bol.app.ai.audio.AudioRecorder
import com.bol.app.data.local.entity.RecordingEntity
import com.bol.app.data.preferences.PreferencesManager
import com.bol.app.data.repository.RecordingRepository
import com.bol.app.data.repository.VoiceQueueRepository
import com.bol.app.service.BolProcessingService
import com.bol.app.service.RecordingSignal
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed class RecordingUiState {
    data object Idle : RecordingUiState()
    /** [partialText] is kept for binary-compat with existing UI but is
     *  always empty now that we no longer have a live-partial STT engine
     *  (Google STT was the only producer). With speech everything
     *  goes through capture → save WAV → background transcribe. */
    data class Listening(val partialText: String = "") : RecordingUiState()
    data class Queued(val text: String) : RecordingUiState()
    data class Error(val message: String) : RecordingUiState()
}

/**
 * Drives the in-app recording sheet (MicPill on Home).
 *
 * Post-Whisper-removal architecture: capture happens via [AudioRecorder]
 * directly (raw PCM on a background scope); the captured WAV lands in the
 * voice_queue and the foreground service transcribes it with speech
 * + categorizes it on the same loaded engine.
 *
 * No more `SpeechToTextEngine` indirection — that abstraction existed to
 * swap between Google STT (live partials) and Whisper (raw capture).
 * With speech we always capture and transcribe in the background, so
 * the abstraction is dead weight and was removed.
 */
class RecordingViewModel(
    private val voiceQueueRepository: VoiceQueueRepository,
    private val recordingRepository: RecordingRepository,
    private val applicationContext: Context,
    private val recordingSignal: RecordingSignal,
    private val preferencesManager: PreferencesManager,
    private val modelManager: com.bol.app.ai.model.ModelManager,
    captureSaver: com.bol.app.ui.assist.QuickCaptureSaver,
) : ViewModel() {

    private val _uiState = MutableStateFlow<RecordingUiState>(RecordingUiState.Idle)
    val uiState: StateFlow<RecordingUiState> = _uiState

    private val _secondsLeft = MutableStateFlow(RecordingCountdown.secondsLeft(0L))
    /** Whole seconds left before the recording limit. */
    val secondsLeft: StateFlow<Int> = _secondsLeft

    // The audio is saved from the recorder's own scope by the app-scoped saver, not from this ViewModel: a state
    // flow keeps only its latest value, so a quick stop-then-record, or this ViewModel being cleared, could
    // otherwise drop a finished recording before it was written.
    private val recorder = AudioRecorder(onCaptured = { pcm, _ -> captureSaver.save(pcm) })
    private val countdown = RecordingCountdown(applicationContext)

    init {
        // Mirror the "is currently listening?" state into the shared signal
        // so anything outside this VM's scope (bottom nav in MainActivity,
        // scrim overlay) can react without a callback chain.
        viewModelScope.launch {
            uiState.collect { state ->
                recordingSignal.set(state is RecordingUiState.Listening)
            }
        }
        // Pipe AudioRecorder state into UI state.
        viewModelScope.launch {
            recorder.state.collect { state ->
                when (state) {
                    is AudioRecorder.State.Recording -> {
                        if (_uiState.value !is RecordingUiState.Listening) {
                            _uiState.value = RecordingUiState.Listening()
                        }
                        _secondsLeft.value = countdown.onProgress(state.durationMs)
                    }
                    is AudioRecorder.State.Captured -> {
                        countdown.onCaptured(state.durationMs)
                        onAudioCaptured()
                    }
                    is AudioRecorder.State.Error -> {
                        _uiState.value = RecordingUiState.Error(state.message)
                    }
                    AudioRecorder.State.Idle -> { /* no-op */ }
                }
            }
        }
    }

    fun startRecording() {
        if (_uiState.value is RecordingUiState.Listening) return
        _uiState.value = RecordingUiState.Listening()
        countdown.reset()
        _secondsLeft.value = RecordingCountdown.secondsLeft(0L)
        recorder.start()
    }

    fun stopRecording(reason: String) {
        BolLogger.log(LogLevel.INFO, LogCategory.STT, "recording_stop_requested", "Recording stop requested: $reason")
        recorder.stop()
    }

    /**
     * Drop a typed entry into the same processing pipeline as a voice
     * recording. Long-press on the MicPill opens a text sheet; submitting
     * lands here. Skips speech (no audio file), saves a recording row
     * with the typed text as rawText, enqueues for LLM categorisation.
     * Doesn't increment voiceEntryCount — that counter only ticks on
     * voice usage to time the long-press hint correctly.
     */
    fun submitTextEntry(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        _uiState.value = RecordingUiState.Queued(trimmed)
        viewModelScope.launch {
            withContext(NonCancellable) {
                val recordingId = UUID.randomUUID().toString()
                recordingRepository.insert(
                    RecordingEntity(id = recordingId, rawText = trimmed, parsedItemCount = 0)
                )
                voiceQueueRepository.enqueue(trimmed, recordingId = recordingId, bypassStt = true)
                BolProcessingService.startProcessing(applicationContext)
            }
        }
    }

    /** The WAV, recording row and queue entry are written by the saver ([recorder]'s callback); this only updates the UI. */
    private fun onAudioCaptured() {
        _uiState.value = RecordingUiState.Queued("[audio]")
        viewModelScope.launch { preferencesManager.incrementVoiceEntryCount() }
    }

    fun reset() {
        recorder.release()
        _uiState.value = RecordingUiState.Idle
    }

    override fun onCleared() {
        if (_uiState.value is RecordingUiState.Listening) {
            BolLogger.log(LogLevel.WARN, LogCategory.STT, "recording_stop_requested", "Recording stop requested: view model cleared")
        }
        recorder.release()
        countdown.release()
        super.onCleared()
    }
}
