package com.bol.app.ai.parser

import com.bol.app.domain.model.ParsedItem
import com.bol.app.domain.model.VoiceResult

class RuleBasedIntentParser : IntentParser {

    override suspend fun parse(rawText: String): VoiceResult {
        val items = splitAndClassify(rawText)
        return VoiceResult(rawText = rawText, items = items, language = "en")
    }

    /** Segment + classify a rawText into parsed items. Reminders are only ever set by the user. */
    fun splitAndClassify(rawText: String): List<ParsedItem> {
        val segments = rawText
            .split(Regex(
                "\\b(and|also|plus|oh and|oh also|wait also|I also need to|I also need|I also want to)\\b|,",
                RegexOption.IGNORE_CASE
            ))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 2 }

        if (segments.isEmpty()) {
            return listOf(ParsedItem(text = rawText.cleanUp(), categoryId = "tasks"))
        }

        return segments.map { segment ->
            val lower = segment.lowercase()

            // Pick content category FIRST. Reminder is orthogonal —
            // "reminders" is no longer a category, so shopping + tomorrow
            // still routes to "buy" with a reminder attached.
            val category = when {
                lower.hasAny("buy", "purchase", "shop", "grocery", "groceries",
                    "pick up", "grab", "order", "store", "market", "amazon",
                    "milk", "eggs", "bread", "shampoo", "soap", "charger",
                    "toilet paper", "toothpaste", "detergent") -> "buy"

                lower.hasAny("watch", "movie", "film", "show", "series", "episode",
                    "netflix", "youtube", "hbo", "disney", "prime video", "anime",
                    "trailer", "documentary", "season", "stream") -> "watch"

                lower.hasAny("work", "office", "meeting", "presentation", "deadline",
                    "client", "project", "email", "boss", "coworker", "report",
                    "review", "sprint", "standup", "slides", "quarterly",
                    "proposal", "invoice") -> "work"

                lower.hasAny("idea", "think about", "thought", "concept", "maybe",
                    "what if", "could try", "brainstorm", "interesting",
                    "side project", "start a", "try") -> "ideas"

                lower.hasAny("feeling", "felt", "stressed", "happy", "sad", "tired",
                    "anxious", "grateful", "excited", "frustrated", "good day",
                    "bad day", "mood") -> "journal"

                else -> "tasks"
            }

            // Reminder intent is ORTHOGONAL to category. Set reminderTime
            // when the segment contains any of:
            //  - explicit trigger verbs ("remind me", "reminder", "alarm")
            //  - a clock time ("at 3pm", "9am", "14:30")
            //  - a relative offset ("in 2 hours", "in 30 min")
            //  - a future day ("tomorrow", "tonight", "Friday", "next week")
            //  - a time-of-day word ("this evening", "this morning")
            ParsedItem(
                text = segment.cleanUp(),
                categoryId = category,
            )
        }
    }

    private fun String.hasAny(vararg patterns: String): Boolean =
        patterns.any { pattern ->
            if (pattern.contains("\\")) {
                Regex(pattern).containsMatchIn(this)
            } else {
                this.contains(pattern)
            }
        }

    private fun String.cleanUp(): String =
        this.replace(Regex(
            "^(i need to|i want to|i have to|i should|need to|want to|have to|please|remind me to|remind me|i gotta|gotta|um|uh|like|so|basically)\\s*",
            RegexOption.IGNORE_CASE
        ), "")
            .trim()
            .replaceFirstChar { it.uppercaseChar() }
}
