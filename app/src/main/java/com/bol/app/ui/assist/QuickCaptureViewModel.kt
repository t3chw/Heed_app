package com.bol.app.ui.assist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bol.app.ai.audio.AudioRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Quick Capture activity. Owns one [AudioRecorder] for its
 * lifetime; the recorder is wired to push captured bytes straight into
 * [QuickCaptureSaver], a Koin singleton with its own application-level
 * scope. That decoupling matters: when the user presses power to lock
 * the screen, the activity finish()es immediately and the ViewModel's
 * scope cancels — but the save still completes via the saver's scope.
 *
 * UI flow (best-effort — only matters if the activity stays visible):
 *   IdlePermissionCheck → Recording → Saving → Done
 *                                          ↘
 *                                          Error
 */
class QuickCaptureViewModel(
    private val saver: QuickCaptureSaver,
    private val modelManager: com.bol.app.ai.model.ModelManager,
    context: android.content.Context,
) : ViewModel() {
    private val countdown = com.bol.app.ui.screens.recording.RecordingCountdown(context)

    // The recorder's onCaptured callback hands bytes to the saver from
    // *inside* the recorder's own scope, which survives the ViewModel's
    // onCleared() teardown. Without this, power-button stops would lose
    // the audio because the StateFlow consumer in viewModelScope would
    // be cancelled before the trailing buffer emits.
    private val recorder = AudioRecorder(
        onCaptured = { bytes, _ -> saver.save(bytes) },
    )

    private val _ui = MutableStateFlow<QuickCaptureUiState>(QuickCaptureUiState.Idle)
    val ui: StateFlow<QuickCaptureUiState> = _ui

    /** Mirrors AudioRecorder.state amplitude/duration so the waveform UI
     *  can render without needing direct access to the recorder. */
    val recorderState: StateFlow<AudioRecorder.State> = recorder.state

    private var lastBelowThresholdAt: Long = 0L

    init {
        // Pipe AudioRecorder state into UI state. Captured → save + enqueue.
        viewModelScope.launch {
            recorder.state.collect { s ->
                when (s) {
                    is AudioRecorder.State.Recording -> {
                        if (_ui.value !is QuickCaptureUiState.Recording) {
                            _ui.value = QuickCaptureUiState.Recording
                        }
                        countdown.onProgress(s.durationMs)
                        // Auto-stop after sustained silence — only after a
                        // minimum recording duration so we don't kill the
                        // session before the user has had a chance to speak.
                        if (s.durationMs >= AudioRecorder.AUTO_STOP_MIN_DURATION_MS) {
                            val now = System.currentTimeMillis()
                            if (s.amplitude < AudioRecorder.SILENCE_AMPLITUDE) {
                                if (lastBelowThresholdAt == 0L) lastBelowThresholdAt = now
                                else if (now - lastBelowThresholdAt >= AudioRecorder.AUTO_STOP_SILENCE_MS) {
                                    recorder.stop()
                                }
                            } else {
                                lastBelowThresholdAt = 0L
                            }
                        }
                    }
                    is AudioRecorder.State.Captured -> {
                        countdown.onCaptured(s.durationMs)
                        // The save is already in flight from the recorder's
                        // onCaptured callback. We only drive the UI flow
                        // here: hold Saving for a visible beat, then Done.
                        // If the activity is already finishing (power-
                        // button path), this collector may never run —
                        // doesn't matter, the save itself is independent.
                        _ui.value = QuickCaptureUiState.Saving
                        viewModelScope.launch {
                            delay(MIN_SAVING_DURATION_MS)
                            _ui.value = QuickCaptureUiState.Done
                        }
                    }
                    is AudioRecorder.State.Error -> {
                        _ui.value = QuickCaptureUiState.Error(s.message)
                    }
                    AudioRecorder.State.Idle -> Unit
                }
            }
        }
    }

    /** Caller has confirmed RECORD_AUDIO is granted. Begins capture. */
    fun startRecording() {
        if (_ui.value is QuickCaptureUiState.Recording ||
            _ui.value is QuickCaptureUiState.Saving) return
        lastBelowThresholdAt = 0L
        recorder.start()
    }

    /** User tapped "Done" / silence-detected — stop capture. */
    fun stopRecording() {
        recorder.stop()
    }

    /**
     * Closed by a tap outside the card (or Close on an error). Whatever was recorded is still saved: the recorder's
     * callback hands it to the saver, and a note the user spoke is never thrown away. A clip too short to use is
     * dropped by the recorder itself.
     */
    fun cancel() {
        recorder.release()
        _ui.value = QuickCaptureUiState.Done
    }

    override fun onCleared() {
        recorder.release()
        countdown.release()
        super.onCleared()
    }

    companion object {
        /** Visual floor for "Got it" / Saving state. Pairs with the
         *  Activity's Done-state hold to give a 2 s feedback window. */
        const val MIN_SAVING_DURATION_MS = 800L
    }
}

sealed class QuickCaptureUiState {
    /** Initial state — Activity hasn't started recording yet (e.g. waiting on permission). */
    data object Idle : QuickCaptureUiState()
    /** Mic is live. UI shows listening Bol + waveform + Done button. */
    data object Recording : QuickCaptureUiState()
    /** Capture stopped, WAV being saved + enqueued. Brief state — usually <100 ms. */
    data object Saving : QuickCaptureUiState()
    /** All done. Activity should finish. */
    data object Done : QuickCaptureUiState()
    /** Something went wrong. UI shows the message + a "Done" button. */
    data class Error(val message: String) : QuickCaptureUiState()
}
