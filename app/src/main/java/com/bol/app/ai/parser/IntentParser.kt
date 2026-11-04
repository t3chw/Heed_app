package com.bol.app.ai.parser

import com.bol.app.domain.model.VoiceResult

interface IntentParser {
    suspend fun parse(rawText: String): VoiceResult

    /**
     * Parse a list of transcript chunks produced by [com.bol.app.ai.audio.AudioChunker].
     *
     * Default implementation joins the chunks and delegates to [parse] — fine for the
     * rule-based parser where overhead is negligible. [com.bol.app.ai.agent.AgentIntentParser]
     * overrides this so each chunk is sent to the LLM separately (keeps KV cache small)
     * and duplicate items are removed across chunks.
     */
    suspend fun parseChunks(chunks: List<String>): VoiceResult {
        val joined = chunks.asSequence().filter { it.isNotBlank() }.joinToString(" ").trim()
        return parse(joined)
    }
}
