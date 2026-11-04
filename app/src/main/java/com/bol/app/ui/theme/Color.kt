package com.bol.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Heed's colour tokens: clean white (dark: near-black) with one brand colour, the leaf green of the mascot.
 * The website (heed-website) uses the same palette. Composables read them through [HeedTheme.colors] (or the
 * Material colour scheme, which [BolTheme] builds from them); no screen hard-codes a colour.
 *
 * The mascot keeps its own colours (BolMascot.kt) and is not part of this palette.
 */
@Immutable
data class HeedColors(
    /** Screen background. */
    val background: Color,
    /** Cards, sheets, dialogs. */
    val surface: Color,
    /** Grouped rows, settings blocks, the transcript box. */
    val surfaceSoft: Color,
    /** Segmented-control track, switch track when off, pressed state. */
    val surfaceSofter: Color,
    /** Dividers and card borders. */
    val line: Color,
    /** Titles and task text. */
    val textPrimary: Color,
    /** Body text. */
    val textSecondary: Color,
    /** Timestamps, meta, hints. */
    val textMuted: Color,
    /** Mic button, active tab, switches, links, checkmarks. */
    val brand: Color,
    val brandPressed: Color,
    /** Active tab pill, selected chip, done-check background, reminder chip. */
    val brandSoft: Color,
    /** Icon or text on [brand]. */
    val onBrand: Color,
    /** Recording dot and stop button. */
    val recording: Color,
    /** The stop square and label on [recording] (white in both themes: the dark knob colour was 2.9:1 on it). */
    val onRecording: Color,
    /** Destructive actions ("Clear everything", delete). */
    val danger: Color,
    /** Background of an error or warning banner, with [danger] text. */
    val dangerSoft: Color,
    /** Optional marker highlight behind key words. */
    val highlight: Color,
    /** Behind the listening overlay. */
    val scrim: Color,
    /** Text and rings drawn on [scrim]. */
    val onScrim: Color,
    /** The round knob of a switch. */
    val knob: Color,
    /** Soft glow under the mic pill (brand at 25 %). */
    val brandShadow: Color,
    val isDark: Boolean,
)

val LightHeedColors = HeedColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceSoft = Color(0xFFF7F7F5),
    surfaceSofter = Color(0xFFF1F1EF),
    line = Color(0xFFE9E9E7),
    textPrimary = Color(0xFF191919),
    textSecondary = Color(0xFF37352F),
    textMuted = Color(0xFF787774),
    brand = Color(0xFF2F8A4C),
    brandPressed = Color(0xFF23703C),
    brandSoft = Color(0xFFEAF5EC),
    onBrand = Color(0xFFFFFFFF),
    recording = Color(0xFFE5484D),
    onRecording = Color(0xFFFFFFFF),
    danger = Color(0xFFD93B3B),
    dangerSoft = Color(0xFFFDEBEC),
    highlight = Color(0xFFFFF0A8),
    scrim = Color(0x73000000),        // black at 45 %
    onScrim = Color(0xFFFFFFFF),
    knob = Color(0xFFFFFFFF),
    brandShadow = Color(0x402F8A4C),  // brand at 25 %
    isDark = false,
)

val DarkHeedColors = HeedColors(
    background = Color(0xFF191919),
    surface = Color(0xFF202020),
    surfaceSoft = Color(0xFF252525),
    surfaceSofter = Color(0xFF2C2C2C),
    line = Color(0xFF2F2F2F),
    textPrimary = Color(0xFFEDEDEC),
    textSecondary = Color(0xFFC7C6C2),
    textMuted = Color(0xFF9B9A97),
    brand = Color(0xFF4CAF6E),
    brandPressed = Color(0xFF62C283),
    brandSoft = Color(0xFF1E3326),
    onBrand = Color(0xFF0F1A12),
    recording = Color(0xFFF2555A),
    onRecording = Color(0xFFFFFFFF),
    danger = Color(0xFFF06363),
    dangerSoft = Color(0xFF3E1F1F),
    highlight = Color(0xFF4A4320),
    scrim = Color(0x99000000),        // black at 60 %
    onScrim = Color(0xFFEDEDEC),
    knob = Color(0xFFEDEDEC),
    brandShadow = Color(0x404CAF6E),
    isDark = true,
)
