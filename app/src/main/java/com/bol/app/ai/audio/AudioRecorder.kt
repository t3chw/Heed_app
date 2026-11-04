package com.bol.app.ai.audio

import com.bol.app.monitoring.BolLogger
import com.bol.app.monitoring.LogCategory
import com.bol.app.monitoring.LogLevel
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Standalone raw-PCM audio recorder for the Quick Capture path.
 *
 * Differs from [com.bol.app.ai.stt.WhisperRecordingEngine] in three ways:
 *  • Not an STT engine — no [com.bol.app.ai.stt.SpeechToTextEngine] interface
 *    or transcript machinery. Only captures audio and emits bytes.
 *  • Exposes a live amplitude flow so a waveform UI can render mic levels
 *    in real time. Whisper's engine doesn't surface amplitude (Whisper
 *    doesn't need it for transcription).
 *  • Accepts an [onCaptured] callback that fires from the recorder's own
 *    scope when the trailing buffer is ready. This is critical for the
 *    Quick Capture activity — when the user presses power to lock the
 *    screen mid-thought, the activity finishes and the ViewModel's scope
 *    cancels before the StateFlow's Captured emission would reach a
 *    [kotlinx.coroutines.flow.StateFlow.collect] consumer in viewModelScope.
 *    The callback runs *inside* the recorder's scope so it survives.
 *
 * Format matches what [WavConverter.pcmToWav] expects: 16 kHz, mono, PCM
 * 16-bit. The captured bytes can be persisted as a WAV via WavConverter
 * and dropped into the existing voice_queue pipeline without any further
 * conversion — same path the on-device Whisper engine writes to.
 */
class AudioRecorder(
    /**
     * Invoked from the recorder's own scope after a successful capture
     * (i.e. enough audio was buffered to clear MIN_AUDIO_BYTES). Use this
     * for save logic that must not depend on any caller's lifecycle —
     * Quick Capture wires it to [com.bol.app.ui.assist.QuickCaptureSaver]
     * so the WAV write happens in an application-scoped coroutine.
     *
     * Errors / silence-only captures don't fire this — only successful
     * Captured states.
     */
    private val onCaptured: ((pcmBytes: ByteArray, durationMs: Long) -> Unit)? = null,
) {

    sealed class State {
        data object Idle : State()
        /** [amplitude] is 0..32767 — the mean abs sample value over the
         *  most recent buffer. Useful for waveform / VAD heuristics. */
        data class Recording(val amplitude: Int = 0, val durationMs: Long = 0L) : State()
        data class Captured(val pcmBytes: ByteArray, val durationMs: Long) : State() {
            // ByteArray equals/hashCode aren't structural by default; override
            // so two State instances with the same payload compare equal. Not
            // critical for correctness here but spares surprise.
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Captured) return false
                return durationMs == other.durationMs && pcmBytes.contentEquals(other.pcmBytes)
            }
            override fun hashCode(): Int = pcmBytes.contentHashCode() * 31 + durationMs.hashCode()
        }
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    /** One recording. Its own stop flag, so a new start can never revive or cut short a capture that is still finishing. */
    private class Session {
        @Volatile var stopRequested = false
    }
    private var session: Session? = null

    /**
     * Start capturing. Caller must hold RECORD_AUDIO; permission isn't
     * gated here so the pre-flight check stays at the UI layer.
     *
     * Pressing start again right after stop is never ignored: the new capture opens the mic as soon as the previous
     * one has written its last buffer and handed over its audio (within one buffer, ~256 ms).
     */
    @Synchronized
    fun start() {
        val current = session
        if (current != null && !current.stopRequested && captureJob?.isActive == true) return // already recording
        val next = Session()
        session = next
        val previous = captureJob
        captureJob = scope.launch {
            previous?.join()
            captureLoop(next)
        }
    }

    /** Stop capturing. The final PCM bytes will arrive via [State.Captured] and [onCaptured]. */
    @Synchronized
    fun stop() {
        session?.stopRequested = true
    }

    /**
     * Soft tear-down — signals stop and lets the captureLoop finish on
     * its own. **Does not cancel the scope or the captureJob.** Cancelling
     * either would kill the loop mid-buffer and the trailing audio (plus
     * the [onCaptured] callback that depends on it) would never emit —
     * which is the bug this avoids.
     *
     * The captureLoop exits within ~256 ms of [stop] being called (one
     * AudioRecord buffer's worth of latency), emits the final state, runs
     * the callback, and lets the coroutine return. Once it returns the
     * scope has no children and is GC-eligible.
     */
    fun release() {
        stop()
    }

    private suspend fun captureLoop(s: Session) {
        val pcm = ByteArrayOutputStream()
        var started = 0L
        var setupFailed = false

        try {
            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING),
                CHUNK_SIZE * 2,
            )

            @Suppress("MissingPermission")
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize,
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                _state.value = State.Error("Couldn't initialise mic.")
                setupFailed = true
                try { recorder.release() } catch (_: Exception) {}
                return
            }
            audioRecord = recorder
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                _state.value = State.Error("Couldn't start mic.")
                setupFailed = true
                return
            }

            val buffer = ShortArray(CHUNK_SIZE)
            started = System.currentTimeMillis()

            _state.value = State.Recording(amplitude = 0, durationMs = 0L)

            while (!s.stopRequested) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val bytes = ByteArray(read * 2)
                    var amp = 0L
                    for (i in 0 until read) {
                        val s = buffer[i].toInt()
                        bytes[i * 2] = (s and 0xFF).toByte()
                        bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
                        amp += abs(s)
                    }
                    pcm.write(bytes)
                    val now = System.currentTimeMillis()
                    val avg = (amp / read).toInt()
                    _state.value = State.Recording(amplitude = avg, durationMs = now - started)

                    if (now - started >= MAX_DURATION_MS) {
                        // Hard cap so a forgotten activity doesn't fill storage
                        BolLogger.log(LogLevel.INFO, LogCategory.STT, "recording_stop_requested", "Recording stopped at the ${MAX_DURATION_MS / 1000} s cap")
                        s.stopRequested = true
                    }
                } else if (read < 0) {
                    BolLogger.log(LogLevel.WARN, LogCategory.STT, "recording_mic_error", "AudioRecord.read returned $read")
                    s.stopRequested = true
                }
            }
        } catch (e: Exception) {
            BolLogger.log(LogLevel.WARN, LogCategory.STT, "recording_mic_error", "Capture failed: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            // Mid-stream failure (read() error, etc.). Fall through to the
            // byte-processing block below — whatever audio we accumulated
            // before the throw is still valid and worth saving.
        } finally {
            // Always release the AudioRecord, no matter how we got here.
            // try-each so a stop() that throws doesn't strand release().
            try { audioRecord?.stop() } catch (_: Exception) {}
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null
        }

        // setupFailed = true means we never reached recording, no bytes —
        // the Error state was already emitted above; nothing else to do.
        if (setupFailed) return

        val bytes = pcm.toByteArray()
        val duration = if (started > 0) System.currentTimeMillis() - started else 0L
        if (bytes.size < MIN_AUDIO_BYTES) {
            _state.value = State.Error("Didn't catch anything — try again.")
        } else {
            _state.value = State.Captured(bytes, duration)
            // Lifecycle-independent save hook. Fires inside *this* scope
            // so it survives the caller's ViewModel/Activity being torn
            // down before the StateFlow consumer would otherwise see
            // Captured. This is the part that actually persists audio
            // when the user presses power to dismiss — the StateFlow
            // collector in viewModelScope is racing teardown and may
            // never run, but this callback always does.
            try {
                onCaptured?.invoke(bytes, duration)
            } catch (_: Exception) {
                // Callback errors mustn't crash the recorder; the
                // saver does its own logging.
            }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 4096
        /** Hard cap so the activity can't run away if the user walks off. */
        const val MAX_DURATION_MS = 60_000L
        /** ~0.25s minimum — anything shorter is almost certainly an accident. */
        private const val MIN_AUDIO_BYTES = 8000

        /**
         * Threshold (mean abs sample) below which the buffer is considered
         * silence. Used by Quick Capture for auto-stop. ~600 corresponds
         * to a quiet room; speech amplitude usually crosses 2000+.
         */
        const val SILENCE_AMPLITUDE = 600

        /**
         * How long amplitude must stay below threshold before auto-stop.
         * Set to 3000 ms so people who pause to *think* mid-sentence don't
         * lose the second half of their thought. Most "I'm done" pauses
         * naturally exceed this; mid-thought hesitations rarely do.
         *
         * The user has two more aggressive ways to stop (power button,
         * Done button) — auto-stop should be the most forgiving option,
         * not the eagerest.
         */
        const val AUTO_STOP_SILENCE_MS = 3000L

        /** Minimum recording duration before auto-stop can fire. Stops the
         *  activity from killing itself before the user has even spoken. */
        const val AUTO_STOP_MIN_DURATION_MS = 1200L
    }
}
