package com.symbioza.daymind.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.Typography

private val darkColorScheme = darkColorScheme(
    primary = DayMindPalette.accent,
    onPrimary = DayMindPalette.textPrimary,
    secondary = DayMindPalette.accentSoft,
    onSecondary = DayMindPalette.textPrimary,
    background = DayMindPalette.background,
    onBackground = DayMindPalette.textPrimary,
    surface = DayMindPalette.surface,
    onSurface = DayMindPalette.textPrimary,
    surfaceVariant = DayMindPalette.surfaceAlt,
    onSurfaceVariant = DayMindPalette.textSecondary,
)

@Composable
fun DayMindTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = darkColorScheme,
        typography = Typography,
        content = content
    )
}
