package com.bol.app.ui.theme

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.bol.app.data.preferences.ThemeMode

private val LocalHeedColors = staticCompositionLocalOf { LightHeedColors }

/** The active [HeedColors]; the way composables read a colour that Material's scheme has no slot for. */
object HeedTheme {
    val colors: HeedColors
        @Composable @ReadOnlyComposable get() = LocalHeedColors.current
}

/**
 * Material's scheme built from the tokens, so stock components (dialogs, pickers, sheets, text fields) follow the
 * palette too. Surfaces carry no tint: elevation shows through borders and [HeedColors.surfaceSoft], not colour.
 */
private fun HeedColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = brand,
        onPrimary = onBrand,
        primaryContainer = brandSoft,
        onPrimaryContainer = brandPressed,
        inversePrimary = brand,
        secondary = brand,
        onSecondary = onBrand,
        secondaryContainer = brandSoft,
        onSecondaryContainer = brandPressed,
        tertiary = brand,
        onTertiary = onBrand,
        tertiaryContainer = brandSoft,
        onTertiaryContainer = brandPressed,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceSoft,
        onSurfaceVariant = textMuted,
        surfaceTint = Color.Transparent,
        surfaceBright = surface,
        surfaceDim = surfaceSoft,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surfaceSofter,
        inverseSurface = textPrimary,
        inverseOnSurface = background,
        outline = line,
        outlineVariant = line,
        error = danger,
        onError = if (isDark) background else Color.White,
        errorContainer = dangerSoft,
        onErrorContainer = danger,
        scrim = scrim,
    )
}

/** Corners for stock components: dialogs and sheets 20 dp, menus 12 dp, text fields 10 dp. */
private val HeedShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** The most Heed adds to every weight when the phone's "Bold text" setting is on (Android adds 300). */
private const val MAX_BOLD_TEXT_BOOST = 100

/**
 * Android 12+'s "Bold text" setting adds 300 to every font weight, and Compose applies it through the font
 * resolver: regular text turns bold, headings hit the font's 900 cap, and every line looks the same weight.
 * Heed keeps the setting but caps the boost at [MAX_BOLD_TEXT_BOOST], so text is still bolder for people who turn
 * it on while titles, body and labels keep their difference. With the setting off this is the platform resolver.
 */
@Composable
private fun rememberLighterBoldResolver(): FontFamily.Resolver {
    val platform = LocalFontFamilyResolver.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(configuration, platform) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && configuration.fontWeightAdjustment > MAX_BOLD_TEXT_BOOST) {
            val capped = Configuration(configuration).apply { fontWeightAdjustment = MAX_BOLD_TEXT_BOOST }
            createFontFamilyResolver(context.createConfigurationContext(capped))
        } else {
            platform
        }
    }
}

private val LightColorScheme = LightHeedColors.toColorScheme()
private val DarkColorScheme = DarkHeedColors.toColorScheme()

@Composable
fun BolTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val heedColors = if (darkTheme) DarkHeedColors else LightHeedColors
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // The activity uses enableEdgeToEdge(), so the app draws under the
    // status / navigation bars. We need to tell the system whether to
    // paint its system-bar icons (clock, wifi, battery, gesture pill)
    // in light or dark — otherwise white icons sit on a white background
    // and disappear. Light theme → dark icons. Dark theme → light icons.
    // Re-runs on every theme switch so the user can toggle without
    // restarting the app.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalHeedColors provides heedColors,
        LocalFontFamilyResolver provides rememberLighterBoldResolver(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BolTypography,
            shapes = HeedShapes,
            content = content
        )
    }
}
