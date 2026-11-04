package com.bol.app.ai.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptJoinerTest {

    @Test
    fun singleChunk_isReturnedTrimmed() {
        assertEquals("Buy milk and eggs.", TranscriptJoiner.join(listOf("  Buy milk and eggs. "), listOf(false)))
    }

    @Test
    fun hardCutOverlap_dropsWordsHeardTwice() {
        val joined = TranscriptJoiner.join(
            listOf("I need to buy milk and", "milk and eggs tomorrow"),
            listOf(false, true),
        )
        assertEquals("I need to buy milk and eggs tomorrow", joined)
    }

    @Test
    fun overlapMatch_ignoresCaseAndPunctuation() {
        val joined = TranscriptJoiner.join(
            listOf("Remind me to call Mom.", "call mom tomorrow at 7 pm"),
            listOf(false, true),
        )
        assertEquals("Remind me to call Mom. tomorrow at 7 pm", joined)
    }

    @Test
    fun silenceCut_neverDropsRepeatedWords() {
        val joined = TranscriptJoiner.join(listOf("No,", "no, the other one"), listOf(false, false))
        assertEquals("No, no, the other one", joined)
    }

    @Test
    fun overlapWithoutMatchingWords_keepsEverything() {
        val joined = TranscriptJoiner.join(listOf("watch Inception", "then buy bread"), listOf(false, true))
        assertEquals("watch Inception then buy bread", joined)
    }

    @Test
    fun blankChunks_areSkipped() {
        val joined = TranscriptJoiner.join(listOf("buy milk", "", "call mom"), listOf(false, false, false))
        assertEquals("buy milk call mom", joined)
    }
}
