package com.email.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.email.app.R
import com.email.app.data.Palette
import com.email.app.data.ThemeMode

/**
 * The app's look, shared with the other apps (SuperAwesomeMagicalMusic, Weather, Fitness): Lexend
 * throughout, cool slate neutrals with rounded cards over a soft glow of the theme, light and dark.
 * The themes (Settings › Appearance): Aurora (purple and green, the family's colours), Violet,
 * Ocean and Ember; or the phone's wallpaper colours.
 */

/** Whether the app is drawn dark (the theme mode, or the phone's). */
val LocalIsDark = staticCompositionLocalOf { false }

/** Behind every screen: the background, with two soft glows of the theme at the top. */
data class BackdropColors(val base: Color, val glowA: Color, val glowB: Color)

val LocalBackdrop = staticCompositionLocalOf { BackdropColors(Color(0xFFF5F7FB), Color(0x55B9A6FF), Color(0x448FE6C8)) }

/** The theme's own colours, the same whatever the phone's wallpaper: for what should look alike on every device. */
data class SiteColors(val primary: Color, val onPrimary: Color, val accent: Color)

val LocalSiteColors = staticCompositionLocalOf { SiteColors(Color(0xFF6A3FD6), Color.White, Color(0xFF00805E)) }

/** The theme's main colour (the web player's --primary): for another device being in control. */
val SitePurple: Color
    @Composable @ReadOnlyComposable get() = LocalSiteColors.current.primary

/** The theme's accent (the web player's --accent): the favourite heart. */
val SiteRose: Color
    @Composable @ReadOnlyComposable get() = LocalSiteColors.current.accent

/** For content on [SitePurple]. */
val OnSitePurple: Color
    @Composable @ReadOnlyComposable get() = LocalSiteColors.current.onPrimary

/** A theme's colours over the slate neutrals: its main colour, a quieter one, and its accent. */
private class Tones(
    val primary: Long, val onPrimary: Long, val primaryContainer: Long, val onPrimaryContainer: Long,
    val secondary: Long, val onSecondary: Long, val secondaryContainer: Long, val onSecondaryContainer: Long,
    val tertiary: Long, val onTertiary: Long, val tertiaryContainer: Long, val onTertiaryContainer: Long,
    val glowA: Long, val glowB: Long,
)

private fun tones(palette: Palette, dark: Boolean): Tones = when (palette) {
    Palette.AURORA -> if (dark) {
        Tones(0xFFCDBDFF, 0xFF37197F, 0xFF4F2FB8, 0xFFE8DEFF, 0xFFC5C1F5, 0xFF2D2960, 0xFF3D3A72, 0xFFE3E0FA, 0xFF62E0B3, 0xFF003828, 0xFF00513B, 0xFF7EFACC, 0xFF5B3FD1, 0xFF12A37C)
    } else {
        Tones(0xFF6A3FD6, 0xFFFFFFFF, 0xFFE8DEFF, 0xFF22005D, 0xFF5C5791, 0xFFFFFFFF, 0xFFE3E0FA, 0xFF1B1748, 0xFF00805E, 0xFFFFFFFF, 0xFFB5F2D9, 0xFF002117, 0xFFB9A6FF, 0xFF8FE6C8)
    }
    Palette.VIOLET -> if (dark) {
        Tones(0xFFCFBDFF, 0xFF341A86, 0xFF4B33A8, 0xFFE9DDFF, 0xFFC5C1F5, 0xFF2D2960, 0xFF3D3A72, 0xFFE3E0FA, 0xFFFFB1C8, 0xFF5E1133, 0xFF7B2949, 0xFFFFD9E2, 0xFF5B3FD1, 0xFF8E3F5C)
    } else {
        Tones(0xFF5B3FD1, 0xFFFFFFFF, 0xFFE9DDFF, 0xFF1D0060, 0xFF5C5791, 0xFFFFFFFF, 0xFFE3E0FA, 0xFF1B1748, 0xFF8E3F5C, 0xFFFFFFFF, 0xFFFFD9E2, 0xFF3B0719, 0xFFC8B8FF, 0xFFFFC2D4)
    }
    Palette.OCEAN -> if (dark) {
        Tones(0xFFA6C8FF, 0xFF00315E, 0xFF1F4F8F, 0xFFD5E3FF, 0xFFBBC7DB, 0xFF253140, 0xFF3B4858, 0xFFD7E3F7, 0xFF5DD5E8, 0xFF00363E, 0xFF004F58, 0xFFA8EEFC, 0xFF1D5FC9, 0xFF0E8FA3)
    } else {
        Tones(0xFF1D5FC9, 0xFFFFFFFF, 0xFFD8E6FF, 0xFF001B3F, 0xFF535F70, 0xFFFFFFFF, 0xFFD7E3F7, 0xFF101C2B, 0xFF00788A, 0xFFFFFFFF, 0xFFA8EEFC, 0xFF001F25, 0xFFA9C7FF, 0xFF93E3EF)
    }
    Palette.EMBER -> if (dark) {
        Tones(0xFFFFB59A, 0xFF5A1C00, 0xFF8A3410, 0xFFFFDBCF, 0xFFE7BDB0, 0xFF442A20, 0xFF5D4036, 0xFFFFDBCF, 0xFFFFB1C8, 0xFF5E1133, 0xFF7B2949, 0xFFFFD9E2, 0xFFC2410C, 0xFF9C3F60)
    } else {
        Tones(0xFFB4471A, 0xFFFFFFFF, 0xFFFFDBCF, 0xFF3A0B00, 0xFF77574C, 0xFFFFFFFF, 0xFFFFDBCF, 0xFF2C160D, 0xFF9C3F60, 0xFFFFFFFF, 0xFFFFD9E2, 0xFF3E001D, 0xFFFFC4AB, 0xFFFFC2D4)
    }
}

/** A theme's colours over the same cool slate neutrals as the other apps (navy in the dark). */
private fun colorsOf(palette: Palette, dark: Boolean): ColorScheme {
    val t = tones(palette, dark)
    val c = { v: Long -> Color(v) }
    return if (dark) {
        darkColorScheme(
            primary = c(t.primary), onPrimary = c(t.onPrimary), primaryContainer = c(t.primaryContainer), onPrimaryContainer = c(t.onPrimaryContainer),
            secondary = c(t.secondary), onSecondary = c(t.onSecondary), secondaryContainer = c(t.secondaryContainer), onSecondaryContainer = c(t.onSecondaryContainer),
            tertiary = c(t.tertiary), onTertiary = c(t.onTertiary), tertiaryContainer = c(t.tertiaryContainer), onTertiaryContainer = c(t.onTertiaryContainer),
            background = Color(0xFF0B1220), onBackground = Color(0xFFE5E9F0),
            surface = Color(0xFF0B1220), onSurface = Color(0xFFE5E9F0),
            surfaceVariant = Color(0xFF1E293B), onSurfaceVariant = Color(0xFFA9B4C6),
            surfaceContainerLowest = Color(0xFF070C16), surfaceContainerLow = Color(0xFF0F1828), surfaceContainer = Color(0xFF131D30),
            surfaceContainerHigh = Color(0xFF1A2539), surfaceContainerHighest = Color(0xFF223047),
            outline = Color(0xFF3B4A63), outlineVariant = Color(0xFF263349),
            error = Color(0xFFFF8A80), onError = Color(0xFF5C0000), errorContainer = Color(0xFF6B1515), onErrorContainer = Color(0xFFFFDAD6),
        )
    } else {
        lightColorScheme(
            primary = c(t.primary), onPrimary = c(t.onPrimary), primaryContainer = c(t.primaryContainer), onPrimaryContainer = c(t.onPrimaryContainer),
            secondary = c(t.secondary), onSecondary = c(t.onSecondary), secondaryContainer = c(t.secondaryContainer), onSecondaryContainer = c(t.onSecondaryContainer),
            tertiary = c(t.tertiary), onTertiary = c(t.onTertiary), tertiaryContainer = c(t.tertiaryContainer), onTertiaryContainer = c(t.onTertiaryContainer),
            background = Color(0xFFF5F7FB), onBackground = Color(0xFF111827),
            surface = Color(0xFFF5F7FB), onSurface = Color(0xFF111827),
            surfaceVariant = Color(0xFFE4E9F2), onSurfaceVariant = Color(0xFF4B5565),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFCFE), surfaceContainer = Color.White,
            surfaceContainerHigh = Color(0xFFEEF2F8), surfaceContainerHighest = Color(0xFFE6EBF3),
            outline = Color(0xFFB8C1CF), outlineVariant = Color(0xFFDDE3EC),
            error = Color(0xFFC62828), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        )
    }
}

/** Softer, rounder corners throughout, as in the other apps. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Lexend (SIL Open Font License, see licenses/Lexend-OFL.txt), built for easy reading. */
val Lexend = FontFamily(
    Font(R.font.lexend_regular, FontWeight.Normal),
    Font(R.font.lexend_medium, FontWeight.Medium),
    Font(R.font.lexend_semibold, FontWeight.SemiBold),
    Font(R.font.lexend_bold, FontWeight.Bold),
)

/**
 * Lexend Bold alone, for Settings › Bold text: as the only face, it draws every text in Bold
 * whatever weight it asks for, the way the phone's own Bold text setting draws the app.
 */
private val LexendBold = FontFamily(Font(R.font.lexend_bold, FontWeight.Bold))

/** Material's scale with the same weights as the other apps, all in [family]. */
private fun appTypography(family: FontFamily) = Typography().run {
    fun TextStyle.lexend() = copy(fontFamily = family)
    Typography(
        displayLarge = displayLarge.lexend(),
        displayMedium = displayMedium.lexend(),
        displaySmall = displaySmall.lexend(),
        headlineLarge = headlineLarge.lexend(),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp).lexend(),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp).lexend(),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold).lexend(),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold).lexend(),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold).lexend(),
        bodyLarge = bodyLarge.lexend(),
        bodyMedium = bodyMedium.lexend(),
        bodySmall = bodySmall.lexend(),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold).lexend(),
        labelMedium = labelMedium.lexend(),
        labelSmall = labelSmall.lexend(),
    )
}

private val AppTypography = appTypography(Lexend)
private val BoldTypography = appTypography(LexendBold)

@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * The app's theme: [palette] over slate (Aurora unless another was chosen), or the phone's wallpaper
 * colours (Android 12 and later) when [wallpaper] is on; all text in Lexend Bold when [bold] is on.
 */
@Composable
fun EmailTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    wallpaper: Boolean = false,
    bold: Boolean = false,
    palette: Palette = Palette.AURORA,
    content: @Composable () -> Unit,
) {
    val dark = isAppInDarkTheme(mode)
    val useWallpaper = wallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    val own = colorsOf(palette, dark)
    val colors = when {
        useWallpaper && dark -> dynamicDarkColorScheme(context)
        useWallpaper -> dynamicLightColorScheme(context)
        else -> own
    }
    val glows = tones(palette, dark)
    val backdrop = if (useWallpaper) {
        BackdropColors(colors.background, colors.primaryContainer.copy(alpha = 0.55f), colors.tertiaryContainer.copy(alpha = 0.45f))
    } else {
        BackdropColors(colors.background, Color(glows.glowA).copy(alpha = if (dark) 0.30f else 0.55f), Color(glows.glowB).copy(alpha = if (dark) 0.22f else 0.45f))
    }
    CompositionLocalProvider(
        LocalIsDark provides dark,
        LocalBackdrop provides backdrop,
        LocalSiteColors provides SiteColors(own.primary, own.onPrimary, own.tertiary),
    ) {
        MaterialTheme(colorScheme = colors, shapes = AppShapes, typography = if (bold) BoldTypography else AppTypography, content = content)
    }
}
