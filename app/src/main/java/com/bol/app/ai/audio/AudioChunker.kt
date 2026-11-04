package com.bol.app.ai.audio

import kotlin.math.sqrt

/**
 * Splits a long PCM float array into chunks at natural silence boundaries.
 *
 * Designed for the Bol "braindump" use case: a user may speak continuously for many
 * minutes. Feeding 10+ minutes of audio into the LLM in one shot blows the KV cache;
 * cutting on fixed time windows often slices mid-sentence and degrades both transcription
 * quality and downstream categorization. This chunker finds gaps of sustained silence
 * (>= [MIN_SILENCE_MS]) and uses the one closest to the [TARGET_CHUNK_MS] mark as a cut
 * point. Only when no silence exists and the chunk must be hard-cut mid-speech is a
 * small [OVERLAP_MS] carried into the next chunk, so a word sliced by the cut is heard
 * whole at least once; [TranscriptJoiner] then removes the words both chunks heard.
 * A silence cut needs no overlap — nobody is speaking there.
 *
 * (Before 2026-09 every cut, including silence cuts, stepped back 2 s and the chunk
 * texts were joined as-is, so notes longer than ~25 s repeated words at each boundary.)
 *
 * PCM input must be mono, normalized to [-1.0, 1.0] (matches [WavConverter.wavToFloatArray]).
 */
object AudioChunker {

    data class Chunk(
        val samples: FloatArray,
        val startMs: Long,
        val endMs: Long,
        /** True when this chunk re-covers the last [OVERLAP_MS] of the previous one. */
        val overlapsPrevious: Boolean = false,
    ) {
        val durationMs: Long get() = endMs - startMs
    }

    /**
     * Returns true iff the audio is long enough to warrant splitting.
     * Short recordings pass through as a single chunk to avoid unnecessary work.
     */
    fun shouldChunk(sampleCount: Int, sampleRate: Int): Boolean =
        sampleCount.toLong() * 1000L / sampleRate > TARGET_CHUNK_MS

    /**
     * Splits [samples] into chunks. Audio that fits the model's per-clip limit
     * ([MAX_CHUNK_MS]) is returned as one chunk — every extra chunk is one
     * more the model inference, and short slivers transcribe worst.
     */
    fun split(samples: FloatArray, sampleRate: Int): List<Chunk> {
        val totalDurationMs = samples.size.toLong() * 1000L / sampleRate
        if (totalDurationMs <= MAX_CHUNK_MS || samples.isEmpty()) {
            return listOf(Chunk(samples, 0L, totalDurationMs))
        }

        val silenceBoundaries = findSilenceBoundaries(samples, sampleRate)

        val chunks = mutableListOf<Chunk>()
        var cursorMs = 0L
        var overlapsPrevious = false
        var safetyIter = 0
        while (cursorMs < totalDurationMs && safetyIter++ < MAX_ITERATIONS) {
            val targetEndMs = cursorMs + TARGET_CHUNK_MS
            val hardEndMs = minOf(cursorMs + MAX_CHUNK_MS, totalDurationMs)

            // If the rest fits in one clip, take it all. Otherwise prefer a
            // silence boundary within [target, hard]; fall back to a hard cut.
            val silenceCutMs = if (hardEndMs == totalDurationMs) null
                else silenceBoundaries.firstOrNull { it in targetEndMs..hardEndMs }
            val cutMs = silenceCutMs ?: hardEndMs

            val startSample = msToSamples(cursorMs, sampleRate).coerceIn(0, samples.size)
            val endSample = msToSamples(cutMs, sampleRate).coerceIn(startSample, samples.size)
            // copyOfRange is O(n) but chunks are bounded by MAX_CHUNK_MS worth of samples
            // (~4.8M floats = ~19MB) so this is fine once per chunk.
            chunks += Chunk(
                samples = samples.copyOfRange(startSample, endSample),
                startMs = cursorMs,
                endMs = cutMs,
                overlapsPrevious = overlapsPrevious,
            )

            if (cutMs >= totalDurationMs) break
            if (silenceCutMs != null) {
                cursorMs = cutMs
                overlapsPrevious = false
            } else {
                // Hard cut mid-speech: step back so the sliced word is heard whole.
                cursorMs = (cutMs - OVERLAP_MS).coerceAtLeast(cursorMs + 1)
                overlapsPrevious = true
            }
        }
        return chunks
    }

    /**
     * Total duration (ms) of [SILENCE_WINDOW_MS] windows whose RMS reaches
     * [rmsThreshold]. Distinguishes "nothing was recorded" (≈0) from "there
     * was sound but no words came back", which must never be discarded.
     */
    fun soundMs(samples: FloatArray, sampleRate: Int, rmsThreshold: Float = SILENCE_RMS_THRESHOLD): Long {
        val windowSamples = (sampleRate * SILENCE_WINDOW_MS / 1000).coerceAtLeast(1)
        var loudWindows = 0L
        var start = 0
        while (start < samples.size) {
            val end = minOf(start + windowSamples, samples.size)
            var sumSq = 0.0
            for (i in start until end) sumSq += samples[i].toDouble() * samples[i]
            if (sqrt(sumSq / (end - start)) >= rmsThreshold) loudWindows++
            start = end
        }
        return loudWindows * SILENCE_WINDOW_MS
    }

    /**
     * Computes per-window RMS and returns the midpoint (in ms) of every run of
     * consecutive silent windows that is at least [MIN_SILENCE_MS] long.
     */
    private fun findSilenceBoundaries(samples: FloatArray, sampleRate: Int): List<Long> {
        val windowSamples = (sampleRate * SILENCE_WINDOW_MS / 1000).coerceAtLeast(1)
        val windowCount = (samples.size + windowSamples - 1) / windowSamples
        val minSilenceWindows = (MIN_SILENCE_MS / SILENCE_WINDOW_MS).toInt().coerceAtLeast(1)

        val boundaries = mutableListOf<Long>()
        var silentRunStart = -1

        var w = 0
        while (w < windowCount) {
            val start = w * windowSamples
            val end = minOf(start + windowSamples, samples.size)

            var sumSq = 0.0
            for (i in start until end) {
                val v = samples[i].toDouble()
                sumSq += v * v
            }
            val rms = sqrt(sumSq / (end - start))
            val isSilent = rms < SILENCE_RMS_THRESHOLD

            if (isSilent) {
                if (silentRunStart < 0) silentRunStart = w
            } else {
                if (silentRunStart >= 0 && (w - silentRunStart) >= minSilenceWindows) {
                    val midWindow = (silentRunStart + w) / 2
                    boundaries += midWindow.toLong() * SILENCE_WINDOW_MS.toLong()
                }
                silentRunStart = -1
            }
            w++
        }
        // Trailing silent run: ignore — we only cut between speech, not at the tail.
        return boundaries
    }

    private fun msToSamples(ms: Long, sampleRate: Int): Int =
        (ms * sampleRate / 1000L).toInt()

    // Tunables — speech era.
    //
    // the task model's audio encoder has a hard 30-second positional encoding
    // window (model architecture limit). Sherpa-ONNX Whisper handled long
    // inputs via its own internal chunking, so the old constants targeted
    // 4-min chunks. Now WE have to chunk to ≤30s and pay one speech
    // inference per chunk. Target slightly under the cap so silence-boundary
    // search has room to find a clean cut without ever being forced to the
    // hard cap. Smaller overlap (2s) because chunks are smaller.
    private const val SILENCE_RMS_THRESHOLD = 0.012f  // empirical: normalized float PCM
    private const val SILENCE_WINDOW_MS = 100         // RMS computed per 100ms window
    private const val MIN_SILENCE_MS = 800L           // 0.8s of silence = cut candidate (tightened from 1.5s)
    private const val TARGET_CHUNK_MS = 25_000L       // 25s target (Granite's 30s cap minus headroom)
    private const val MAX_CHUNK_MS = 30_000L          // 30s hard cap (speech encoder limit)
    private const val OVERLAP_MS = 1_000L             // overlap after a HARD cut only (~2–3 words)
    private const val MAX_ITERATIONS = 200            // safety against any infinite loop
}
