package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val StudioDarkColorScheme = darkColorScheme(
    primary = RazorVermilion,
    onPrimary = Color.White,
    primaryContainer = RazorVermilionContainer,
    onPrimaryContainer = OnRazorVermilionContainer,
    secondary = TimecodeAmber,
    onSecondary = Color(0xFF1F1600),
    secondaryContainer = TimecodeAmberContainer,
    onSecondaryContainer = OnTimecodeAmberContainer,
    tertiary = LosslessMint,
    onTertiary = Color(0xFF00241A),
    tertiaryContainer = LosslessMintContainer,
    onTertiaryContainer = OnLosslessMintContainer,
    background = StudioObsidian,
    onBackground = StudioInk,
    surface = StudioCarbon,
    onSurface = StudioInk,
    surfaceVariant = StudioSlate,
    onSurfaceVariant = StudioInkMuted,
    surfaceContainerLowest = StudioObsidian,
    surfaceContainerLow = StudioCarbon,
    surfaceContainer = StudioSlate,
    surfaceContainerHigh = StudioGraphite,
    surfaceContainerHighest = Color(0xFF2D364F),
    outline = StudioBorder,
    outlineVariant = Color(0xFF1F2638),
    error = StudioError,
    onError = Color(0xFF2D050A),
    errorContainer = StudioErrorContainer,
    onErrorContainer = OnStudioErrorContainer
)

private val StudioLightColorScheme = lightColorScheme(
    primary = Color(0xFFD93B1F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD2),
    onPrimaryContainer = Color(0xFF410A00),
    secondary = Color(0xFFB37400),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE5B8),
    onSecondaryContainer = Color(0xFF331E00),
    tertiary = Color(0xFF0D8F6B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC4F7E6),
    onTertiaryContainer = Color(0xFF002117),
    background = DaylightCanvas,
    onBackground = DaylightInk,
    surface = DaylightCard,
    onSurface = DaylightInk,
    surfaceVariant = DaylightSurfaceHigh,
    onSurfaceVariant = DaylightInkMuted,
    outline = DaylightBorder
)

val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ClipSliceTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) StudioDarkColorScheme else StudioLightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = StudioShapes,
        content = content
    )
}
