package com.bol.app.ai.processing

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase the in-process voice/search pipeline is currently in. Drives the
 * Home processing-pill copy so the user sees real motion ("Listening
 * back…" → "Finding homes for it…") instead of a static "Sorting 1
 * recording" — which reads as *stuck* and was driving the perception that
 * the app was slow.
 *
 * Set from [com.bol.app.service.BolProcessingService] at each B-phase
 * boundary; reset to [IDLE] in the service's exit `finally`.
 */
enum class ProcessingStage {
    IDLE,
    TRANSCRIBING,
    CATEGORIZING,
    SEARCHING,
}

/**
 * Process-singleton holding the live pipeline stage. A Koin singleton (NOT
 * a top-level object) so unit tests can swap it; both writers
 * ([BolProcessingService]) and readers ([HomeViewModel]) inject the same
 * instance.
 *
 * Indexing work that runs while the app is foregrounded
 * ([com.bol.app.ai.indexing.IndexingCoordinator]) intentionally does NOT
 * update this — it has its own [com.bol.app.ui.components.IndexingBanner]
 * and conflating the two would muddy the user-facing message.
 */
class ProcessingStageTracker {
    private val _stage = MutableStateFlow(ProcessingStage.IDLE)
    val stage: StateFlow<ProcessingStage> = _stage.asStateFlow()

    fun set(stage: ProcessingStage) { _stage.value = stage }
    fun reset() { _stage.value = ProcessingStage.IDLE }

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** One-off messages for Home, such as a note that gave no tasks. Not replayed: Home shows only what arrives while it is open. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    fun notice(message: String) { _notices.tryEmit(message) }
}
