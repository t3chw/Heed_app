package com.bol.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bol.app.R

/**
 * Inter for everything you read (headings SemiBold / Bold, body Regular, titles Medium), JetBrains Mono for the small
 * caps labels (dates, "BUCKETS", tab labels). Both ship in res/font as variable fonts (SIL OFL), because the app has
 * no internet to fetch them. No italic display text anywhere.
 */
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: FontWeight) =
    Font(res, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

private val weights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)

object BolFonts {
    val Inter: FontFamily = FontFamily(weights.map { variable(R.font.inter_variable, it) })
    val JetBrainsMono: FontFamily = FontFamily(weights.map { variable(R.font.jetbrains_mono_variable, it) })

    /** Headings. */
    val Display: FontFamily = Inter
    /** Body and controls. */
    val UI: FontFamily = Inter
    /** Small caps labels. */
    val Mono: FontFamily = JetBrainsMono
}

/** Headings use about −2 % letter-spacing. */
private val Tight: TextUnit = (-0.02).em

val DisplayLarge = TextStyle(
    fontFamily = BolFonts.Display,
    fontWeight = FontWeight.Bold,
    fontSize = 44.sp,
    lineHeight = 48.sp,
    letterSpacing = Tight,
)
val DisplayMedium = TextStyle(
    fontFamily = BolFonts.Display,
    fontWeight = FontWeight.Bold,
    fontSize = 34.sp,
    lineHeight = 38.sp,
    letterSpacing = Tight,
)
val DisplaySmall = TextStyle(
    fontFamily = BolFonts.Display,
    fontWeight = FontWeight.SemiBold,
    fontSize = 26.sp,
    lineHeight = 30.sp,
    letterSpacing = Tight,
)
/** Title of a tab screen (two lines: words, then the brand-green line). */
val PageTitle = TextStyle(
    fontFamily = BolFonts.Display,
    fontWeight = FontWeight.Bold,
    fontSize = 30.sp,
    lineHeight = 34.sp,
    letterSpacing = Tight,
)
val BolMono = TextStyle(
    fontFamily = BolFonts.Mono,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 1.2.sp,
)
val BolEyebrow = TextStyle(
    fontFamily = BolFonts.Mono,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 15.sp,
    letterSpacing = 1.4.sp,
)

val BolTypography = Typography(
    displayLarge = DisplayLarge,
    displayMedium = DisplayMedium,
    displaySmall = DisplaySmall,

    headlineLarge = TextStyle(
        fontFamily = BolFonts.Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = Tight,
    ),
    headlineMedium = TextStyle(
        fontFamily = BolFonts.Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = Tight,
    ),
    headlineSmall = TextStyle(
        fontFamily = BolFonts.Display,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = Tight,
    ),

    titleLarge = TextStyle(
        fontFamily = BolFonts.UI,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = BolFonts.UI,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = BolFonts.UI,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),

    bodyLarge = TextStyle(
        fontFamily = BolFonts.UI,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = BolFonts.UI,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = BolFonts.UI,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),

    labelLarge = TextStyle(
        fontFamily = BolFonts.UI,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = BolFonts.UI,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = BolMono,
)
