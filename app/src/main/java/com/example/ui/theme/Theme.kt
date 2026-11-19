package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val WinampDarkColorScheme = darkColorScheme(
    primary = WinampColors.EqGoldBright,
    onPrimary = Color(0xFF111319),
    primaryContainer = Color(0xFF394054),
    onPrimaryContainer = WinampColors.TitleGold,
    secondary = WinampColors.LcdGreen,
    onSecondary = Color(0xFF040608),
    secondaryContainer = WinampColors.PlaylistSelectBg,
    onSecondaryContainer = WinampColors.LcdGreen,
    tertiary = WinampColors.TitleSilver,
    background = WinampColors.ChassisBg,
    onBackground = WinampColors.TextPrimary,
    surface = WinampColors.ChassisSurface,
    onSurface = WinampColors.TextPrimary,
    surfaceVariant = WinampColors.ChassisSurfaceBottom,
    onSurfaceVariant = WinampColors.TextSecondary,
    surfaceContainerLow = WinampColors.ChassisSurfaceBottom,
    surfaceContainer = WinampColors.ChassisSurface,
    surfaceContainerHigh = WinampColors.ChassisSurfaceTop,
    outline = WinampColors.BevelHighlight,
    outlineVariant = WinampColors.BevelShadow,
    error = WinampColors.LcdPeakRed,
    onError = Color.White
)

private val WinampShapes = Shapes(
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(3.dp),
    large = RoundedCornerShape(4.dp)
)

@Composable
fun SonoraTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = WinampDarkColorScheme,
        typography = SonoraTypography,
        shapes = WinampShapes,
        content = content
    )
}
