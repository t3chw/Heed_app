package com.bol.app.ui.screens.recording

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import com.bol.app.ai.audio.AudioRecorder
import com.bol.app.util.BolHaptic
import kotlin.math.ceil

/**
 * The end of the recording limit, felt and heard: a quiet beep and a light buzz for each of the last
 * [WARNING_SECONDS] seconds, then a longer beep when the recorder stops at the limit. The buzz covers silent mode.
 */
class RecordingCountdown(private val context: Context) {
    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME) }.getOrNull()
    private var lastWarned = Int.MAX_VALUE

    /** Call on every recorder update. Returns the whole seconds left. */
    fun onProgress(durationMs: Long): Int {
        val left = secondsLeft(durationMs)
        if (left in 1..WARNING_SECONDS && left < lastWarned) {
            lastWarned = left
            tones?.startTone(ToneGenerator.TONE_PROP_BEEP, TICK_MS)
            BolHaptic.tap(context)
        }
        return left
    }

    fun onCaptured(durationMs: Long) {
        if (secondsLeft(durationMs) <= 0) {
            tones?.startTone(ToneGenerator.TONE_PROP_PROMPT, FINISH_MS)
            BolHaptic.confirm(context)
        }
    }

    fun reset() {
        lastWarned = Int.MAX_VALUE
    }

    fun release() {
        tones?.release()
    }

    companion object {
        const val WARNING_SECONDS = 5
        private const val VOLUME = 35
        private const val TICK_MS = 90
        private const val FINISH_MS = 450

        fun secondsLeft(durationMs: Long): Int =
            ceil((AudioRecorder.MAX_DURATION_MS - durationMs).coerceAtLeast(0L) / 1000.0).toInt()
    }
}
