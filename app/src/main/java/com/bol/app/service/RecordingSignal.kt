package com.bol.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App-level "is the user actively recording" signal.
 *
 * Needed because the recording UI lives inside HomeScreen (scoped to
 * the Home NavBackStackEntry's ViewModelStore), but MainActivity sits
 * outside that scope and also needs to react — it owns the bottom nav
 * bar, which should hide while recording is active.
 *
 * A single Koin-scoped instance is shared: [RecordingViewModel] mirrors
 * its own state here on every emission, and anything that needs to
 * know ("recording right now?") observes [isRecording] directly. No
 * callback plumbing through the navigation graph.
 */
class RecordingSignal {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    /**
     * One-shot "the user just asked to start recording" flag. Set by
     * out-of-process entry points (home-screen widget tap, future
     * notification action, etc.) and consumed once by HomeScreen,
     * which runs the same permission + startRecording path that the
     * MicPill onClick uses. Kept here (not in HomeScreen state) so
     * the signal survives the brief moment when MainActivity is
     * already alive but Home isn't yet the active back-stack entry.
     */
    private val _pendingStart = MutableStateFlow(false)
    val pendingStart: StateFlow<Boolean> = _pendingStart

    fun set(recording: Boolean) {
        _isRecording.value = recording
    }

    fun requestStart() {
        _pendingStart.value = true
    }

    fun consumeStart() {
        _pendingStart.value = false
    }
}
