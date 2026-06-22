package com.digitalsignage.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryLight,
    onPrimary = Color(0xFF00315F),
    primaryContainer = PrimaryBlue,
    onPrimaryContainer = Color.White,
    background = BlackCanvas,
    onBackground = OnSurface,
    surface = SurfaceDark,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    error = ErrorLight
)

@Composable
fun MsrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = MsrTypography,
        content = content
    )
}
