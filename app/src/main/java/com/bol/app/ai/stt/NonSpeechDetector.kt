package com.bol.app.ai.stt

/**
 * Detects Whisper non-speech annotations like (static), (thunder), [BLANK_AUDIO], etc.
 * These indicate no real voice was captured and should be discarded — no recording, no LLM processing.
 *
 * Key insight: Whisper is the ONLY STT engine that outputs text in parentheses/brackets.
 * Real speech is NEVER wrapped in () or []. So ANY text that is entirely bracketed is non-speech.
 */
object NonSpeechDetector {

    fun isNonSpeech(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return true

        // Only punctuation/ellipsis — Whisper outputs "...", "…", "." on silence
        if (trimmed.replace(Regex("[.…,!?;:\\-–—\\s]+"), "").isBlank()) return true

        // Remove ALL bracketed/parenthesized content from the text.
        // If nothing remains, the entire text was non-speech annotations.
        // Catches: (thunder), (airplane engine roaring), [BLANK_AUDIO], [music],
        //          (static) (music), (dramatic music) (dramatic music), etc.
        val withoutAnnotations = trimmed
            .replace(Regex("\\([^)]*\\)"), "")
            .replace(Regex("\\[[^]]*]"), "")
            .trim()
        if (withoutAnnotations.isBlank() && trimmed.length > 1) return true

        return false
    }
}
