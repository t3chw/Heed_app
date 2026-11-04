package com.bol.app.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ParsedItem(
    val text: String,
    val categoryId: String,
    val categoryName: String? = null,
    val categoryEmoji: String? = null,
    val reminderTime: Long? = null,
    val confidence: Float = 1.0f
)

@Serializable
data class VoiceResult(
    val rawText: String,
    val items: List<ParsedItem>,
    val language: String = "en",
    val tags: List<String> = emptyList()
)
