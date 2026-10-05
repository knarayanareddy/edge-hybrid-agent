package com.edgehybrid.agent.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Type scale following Apple's optical sizing: tight leading on large text,
 * generous on body copy, and a tight letter-spacing ramp so large type does not
 * look airy and small type does not look cramped.
 *
 * The previous theme passed **no** `Typography`, so every screen rendered in the
 * stock Material scale — which is why the app read as generic. Hierarchy now comes
 * from this scale instead of from coloured boxes.
 */
private val lineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: Double = 0.0
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    lineHeightStyle = lineHeightStyle
)

val AuraTypography = Typography(
    // Display — hero numerals and rare one-off moments.
    displayLarge = style(52, 60, FontWeight.Bold, (-0.5)),
    displayMedium = style(42, 50, FontWeight.Bold, (-0.4)),
    displaySmall = style(34, 42, FontWeight.SemiBold, (-0.3)),

    // Headline — screen titles.
    headlineLarge = style(28, 34, FontWeight.SemiBold, (-0.2)),
    headlineMedium = style(24, 30, FontWeight.SemiBold, (-0.2)),
    headlineSmall = style(20, 26, FontWeight.SemiBold, (-0.1)),

    // Title — section and card headers.
    titleLarge = style(18, 24, FontWeight.SemiBold, (-0.1)),
    titleMedium = style(16, 22, FontWeight.Medium, 0.0),
    titleSmall = style(14, 20, FontWeight.Medium, 0.0),

    // Body — the workhorse. 17sp/24 with 0.15 tracking mirrors Apple's body.
    bodyLarge = style(17, 24, FontWeight.Normal, 0.15),
    bodyMedium = style(15, 21, FontWeight.Normal, 0.1),
    bodySmall = style(13, 18, FontWeight.Normal, 0.1),

    // Label — buttons, tabs, metadata.
    labelLarge = style(15, 20, FontWeight.Medium, 0.1),
    labelMedium = style(13, 16, FontWeight.Medium, 0.1),
    labelSmall = style(11, 14, FontWeight.Medium, 0.2)
)

private val LightColors = lightColorScheme(
    primary = AuraTokens.Accent,
    onPrimary = AuraTokens.OnAccent,
    primaryContainer = AuraTokens.AccentSubtle,
    onPrimaryContainer = AuraTokens.AccentHover,
    inversePrimary = Color(0xFFB9B9F5),

    secondary = AuraTokens.TextSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = AuraTokens.SurfaceRaisedLight,
    onSecondaryContainer = AuraTokens.TextPrimaryLight,

    background = AuraTokens.CanvasLight,
    onBackground = AuraTokens.TextPrimaryLight,
    surface = AuraTokens.SurfaceLight,
    onSurface = AuraTokens.TextPrimaryLight,
    surfaceVariant = AuraTokens.SurfaceRaisedLight,
    onSurfaceVariant = AuraTokens.TextSecondaryLight,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = AuraTokens.SurfaceLight,
    surfaceContainer = AuraTokens.SurfaceRaisedLight,
    surfaceContainerHigh = AuraTokens.SurfaceSunkenLight,
    surfaceContainerHighest = AuraTokens.SurfaceSunkenLight,

    outline = AuraTokens.TextTertiaryLight,
    outlineVariant = AuraTokens.BorderLight,

    error = AuraTokens.Danger,
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF7A1A15),

    scrim = Color(0x66000000)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8E8EF0),
    onPrimary = Color(0xFF15154A),
    primaryContainer = AuraTokens.AccentSubtleDark,
    onPrimaryContainer = Color(0xFFC9C9FF),
    inversePrimary = AuraTokens.Accent,

    secondary = AuraTokens.TextSecondaryDark,
    onSecondary = Color(0xFF16161A),
    secondaryContainer = AuraTokens.SurfaceRaisedDark,
    onSecondaryContainer = AuraTokens.TextPrimaryDark,

    background = AuraTokens.CanvasDark,
    onBackground = AuraTokens.TextPrimaryDark,
    surface = AuraTokens.SurfaceDark,
    onSurface = AuraTokens.TextPrimaryDark,
    surfaceVariant = AuraTokens.SurfaceRaisedDark,
    onSurfaceVariant = AuraTokens.TextSecondaryDark,
    surfaceContainerLowest = AuraTokens.SurfaceSunkenDark,
    surfaceContainerLow = Color(0xFF131317),
    surfaceContainer = AuraTokens.SurfaceRaisedDark,
    surfaceContainerHigh = Color(0xFF232329),
    surfaceContainerHighest = Color(0xFF2A2A31),

    outline = AuraTokens.TextTertiaryDark,
    outlineVariant = AuraTokens.BorderDark,

    error = AuraTokens.DangerDark,
    onError = Color(0xFF3B0906),
    errorContainer = Color(0xFF3A1512),
    onErrorContainer = Color(0xFFFFB4AC),

    scrim = Color(0x99000000)
)

@Composable
fun EdgeHybridTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default: Material You repaints the whole app from the user's
    // wallpaper, which is what made the product look like a sample app. Our own
    // surfaces are the point; opt in per-build if you want it back.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    // Match status/navigation bar icons to the theme, or the system bar stays
    // light-on-light in dark mode — the classic "old looking" tell.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AuraTypography,
        content = content
    )
}