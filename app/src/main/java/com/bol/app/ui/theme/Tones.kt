package com.bol.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Bucket (category) colours: a soft tile background and a text / icon colour, for light and dark. The six default
 * buckets have their own; buckets the user makes cycle through green, teal and red. These are the only colours
 * besides the brand green, and they only ever mark a bucket.
 */
enum class Tone(
    private val lightBg: Color,
    private val lightText: Color,
    private val darkBg: Color,
    private val darkText: Color,
) {
    General(Color(0xFFEEEEEC), Color(0xFF55534E), Color(0xFF373737), Color(0xFFD4D4D2)),
    Buy(Color(0xFFFBECDD), Color(0xFFB4561B), Color(0xFF3D2A1C), Color(0xFFF0A060)),
    Watch(Color(0xFFEFE7F7), Color(0xFF6940A5), Color(0xFF2F2541), Color(0xFFC3A6F0)),
    Ideas(Color(0xFFFBF3DB), Color(0xFF8A6A0E), Color(0xFF3A321A), Color(0xFFE6C35C)),
    Work(Color(0xFFE7F0FB), Color(0xFF1F5DAE), Color(0xFF1C2C40), Color(0xFF7FB2F0)),
    Journal(Color(0xFFFBE9F1), Color(0xFFA63D73), Color(0xFF3D2230), Color(0xFFF09AC0)),
    Green(Color(0xFFEAF5EC), Color(0xFF2F8A4C), Color(0xFF1E3326), Color(0xFF6CCB8A)),
    Teal(Color(0xFFE3F4F4), Color(0xFF1F7A7A), Color(0xFF173434), Color(0xFF5FC9C9)),
    Red(Color(0xFFFDEBEC), Color(0xFFC4332F), Color(0xFF3E1F1F), Color(0xFFF08080));

    fun bg(dark: Boolean): Color = if (dark) darkBg else lightBg
    fun fg(dark: Boolean): Color = if (dark) darkText else lightText

    companion object {
        /** User-made buckets. */
        private val rotation = listOf(Green, Teal, Red)

        fun forSeed(seed: String): Tone {
            if (seed.isEmpty()) return General
            val idx = (seed.hashCode().toLong() and 0x7fffffffL).toInt() % rotation.size
            return rotation[idx]
        }

        /** The default buckets by id (or name); anything else is a user-made bucket. */
        fun forCategory(id: String?, name: String? = null): Tone {
            val key = (id ?: name ?: "").lowercase()
            return when {
                key == "tasks" || key.contains("general") -> General
                key.contains("buy") || key.contains("shop") -> Buy
                key.contains("watch") || key.contains("movie") -> Watch
                key.contains("idea") -> Ideas
                key.contains("work") -> Work
                key.contains("journal") -> Journal
                key.contains("remind") -> Green
                else -> forSeed(key.ifEmpty { name.orEmpty() })
            }
        }
    }
}

/** Text and icon colour of a bucket in the active theme. */
@Composable
@ReadOnlyComposable
fun Tone.text(): Color = fg(HeedTheme.colors.isDark)

/** Tile / chip background of a bucket in the active theme. */
@Composable
@ReadOnlyComposable
fun Tone.surfaceWash(): Color = bg(HeedTheme.colors.isDark)

// ── Neutral helpers ──
// Short names used across the screens; each is one token.

/** Titles and task text. */
@Composable
@ReadOnlyComposable
fun ink(): Color = HeedTheme.colors.textPrimary

/** Body text. */
@Composable
@ReadOnlyComposable
fun ink2(): Color = HeedTheme.colors.textSecondary

/** Timestamps, meta, hints, idle icons. */
@Composable
@ReadOnlyComposable
fun ink3(): Color = HeedTheme.colors.textMuted

/** Dividers and card borders. */
@Composable
@ReadOnlyComposable
fun line(): Color = HeedTheme.colors.line

/** Subtle dividers inside a card (same token as [line]; the new palette has one line colour). */
@Composable
@ReadOnlyComposable
fun lineSoft(): Color = HeedTheme.colors.line

/** Grouped rows, settings blocks, the transcript box. */
@Composable
@ReadOnlyComposable
fun surfaceSoft(): Color = HeedTheme.colors.surfaceSoft

/** Segmented-control track, switch track when off, pressed state. */
@Composable
@ReadOnlyComposable
fun surfaceSofter(): Color = HeedTheme.colors.surfaceSofter

/** The brand green. */
@Composable
@ReadOnlyComposable
fun brand(): Color = HeedTheme.colors.brand

/** Soft brand background (active tab, selected chip, done check, reminder chip). */
@Composable
@ReadOnlyComposable
fun brandSoft(): Color = HeedTheme.colors.brandSoft
