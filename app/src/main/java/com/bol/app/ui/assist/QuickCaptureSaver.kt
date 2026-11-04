package com.bol.app.ui.assist

import android.content.Context
import com.bol.app.ai.audio.WavConverter
import com.bol.app.data.local.entity.RecordingEntity
import com.bol.app.data.repository.RecordingRepository
import com.bol.app.data.repository.VoiceQueueRepository
import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import com.bol.app.service.BolProcessingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Application-scoped persistor for Quick Capture audio.
 *
 * This exists because the obvious place — a `viewModelScope.launch` inside
 * [QuickCaptureViewModel] — gets cancelled the moment the activity dies.
 * That's a real problem on the power-button path: the user presses power,
 * onPause calls `finish()`, the ViewModel's scope cancels in
 * [androidx.lifecycle.ViewModel.onCleared] before the AudioRecorder has
 * had time to emit its trailing buffer. The audio is captured in RAM but
 * never written to disk — the user thinks it saved; nothing did.
 *
 * Wiring this through a Koin `single` (one instance for the whole app
 * process) gives the save its own [CoroutineScope] that outlives any
 * Activity / ViewModel. [save] returns immediately; the WAV write + DB
 * inserts happen in the background, fully decoupled from whoever called
 * us. Even if the activity is already finishing while we're called, the
 * save completes and the queue picks the recording up later.
 */
class QuickCaptureSaver(
    private val context: Context,
    private val recordingRepository: RecordingRepository,
    private val voiceQueueRepository: VoiceQueueRepository,
) {
    // SupervisorJob so a single failed save doesn't cancel the scope and
    // strand future saves. NonCancellable inside each [save] guards the
    // critical path against any external cancellation attempt — the scope
    // itself is never cancelled by anyone, but defence in depth is cheap.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Fire-and-forget persist. Returns immediately; caller doesn't need
     * to await. The bytes are queued for the existing voice_queue
     * pipeline (Whisper transcription → the model categorisation), same path
     * as the in-app MicPill audio.
     */
    fun save(pcmBytes: ByteArray) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    val recordingId = UUID.randomUUID().toString()
                    val wavFile = File(context.filesDir, "audio/$recordingId.wav")
                    val saved = WavConverter.pcmToWav(pcmBytes, wavFile)
                    if (!saved) {
                        BolLogger.log(
                            LogLevel.ERROR,
                            LogCategory.QUEUE,
                            "quick_capture_wav_failed",
                            "Quick Capture: WAV write failed for $recordingId — audio lost.",
                        )
                        return@withContext
                    }
                    recordingRepository.insert(
                        RecordingEntity(
                            id = recordingId,
                            rawText = "[audio]",
                            parsedItemCount = 0,
                        )
                    )
                    voiceQueueRepository.enqueue(
                        rawText = "[audio]",
                        recordingId = recordingId,
                    )
                    BolProcessingService.startProcessing(context)
                    BolLogger.log(
                        LogLevel.INFO,
                        LogCategory.QUEUE,
                        "quick_capture_enqueued",
                        "Quick Capture saved $recordingId (${pcmBytes.size} PCM bytes) — service kicked.",
                    )
                } catch (e: Exception) {
                    BolLogger.log(
                        LogLevel.ERROR,
                        LogCategory.QUEUE,
                        "quick_capture_save_threw",
                        "Quick Capture save crashed: ${e.javaClass.simpleName}: ${e.message?.take(150)}",
                    )
                }
            }
        }
    }
}
