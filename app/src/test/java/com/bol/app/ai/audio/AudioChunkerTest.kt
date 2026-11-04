package com.bol.app.ai.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioChunkerTest {

    private val rate = 16_000

    /** Speech-level tone, with optional silent gaps given as (startMs, endMs). */
    private fun audio(totalMs: Long, silences: List<LongRange> = emptyList()): FloatArray {
        val n = (totalMs * rate / 1000).toInt()
        return FloatArray(n) { i ->
            val ms = i * 1000L / rate
            if (silences.any { ms in it }) 0f else (0.3 * sin(2 * PI * 220 * i / rate)).toFloat()
        }
    }

    @Test
    fun shortRecording_isOneChunk() {
        val chunks = AudioChunker.split(audio(20_000), rate)
        assertEquals(1, chunks.size)
        assertFalse(chunks[0].overlapsPrevious)
    }

    @Test
    fun silenceCut_hasNoOverlap() {
        // 1 s pause centred at 27 s — inside the 25–30 s cut window.
        val chunks = AudioChunker.split(audio(45_000, listOf(26_500L..27_500L)), rate)
        assertEquals(2, chunks.size)
        val cut = chunks[0].endMs
        assertTrue("cut at the pause, was $cut", cut in 26_500L..27_500L)
        assertEquals(cut, chunks[1].startMs)
        assertFalse(chunks[1].overlapsPrevious)
    }

    @Test
    fun continuousSpeech_hardCutsAt30sWithOneSecondOverlap() {
        val chunks = AudioChunker.split(audio(50_000), rate)
        assertEquals(2, chunks.size)
        assertEquals(30_000L, chunks[0].endMs)
        assertEquals(29_000L, chunks[1].startMs)
        assertTrue(chunks[1].overlapsPrevious)
    }

    @Test
    fun noChunkExceedsGemmaAudioLimit() {
        val chunks = AudioChunker.split(audio(60_000), rate)
        assertTrue(chunks.all { it.durationMs <= 30_000L })
        assertEquals(60_000L, chunks.last().endMs)
    }

    @Test
    fun fitsInOneClip_isNotSplitEvenWithAPause() {
        // 28 s with a pause at 26 s used to become 26 s + 2 s: an extra
        // Gemma pass on a sliver that transcribes badly.
        val chunks = AudioChunker.split(audio(28_000, listOf(25_500L..26_500L)), rate)
        assertEquals(1, chunks.size)
    }

    @Test
    fun remainderThatFits_isTakenWhole() {
        // 45 s continuous: 0–30 hard cut, then the remaining 16 s in one chunk.
        val chunks = AudioChunker.split(audio(45_000), rate)
        assertEquals(2, chunks.size)
        assertEquals(45_000L, chunks[1].endMs)
    }

    @Test
    fun soundMs_isZeroForSilence() {
        assertEquals(0L, AudioChunker.soundMs(FloatArray(rate * 5), rate))
    }

    @Test
    fun soundMs_countsOnlyTheAudiblePart() {
        // 10 s total, silent except 1 s of tone.
        val samples = audio(10_000, listOf(0L..3_999L, 5_000L..10_000L))
        val ms = AudioChunker.soundMs(samples, rate)
        assertTrue("expected ~1000 ms, was $ms", ms in 900L..1_100L)
    }
}
