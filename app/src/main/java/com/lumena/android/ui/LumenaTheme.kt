package com.lumena.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LumenaDarkColors = darkColorScheme(
    primary = Color(0xFFD9B7FF),
    onPrimary = Color(0xFF2E1744),
    primaryContainer = Color(0xFF3D2455),
    onPrimaryContainer = Color(0xFFF0E3FF),
    secondary = Color(0xFFAAC7FF),
    onSecondary = Color(0xFF102A43),
    background = Color(0xFF0D0F14),
    onBackground = Color(0xFFE7E9EF),
    surface = Color(0xFF13161D),
    onSurface = Color(0xFFE7E9EF),
    surfaceVariant = Color(0xFF20242E),
    onSurfaceVariant = Color(0xFFC6CAD4),
    outline = Color(0xFF737886),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun LumenaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LumenaDarkColors,
        content = content
    )
}
