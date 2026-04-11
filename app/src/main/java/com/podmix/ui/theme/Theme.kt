package com.podmix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PodMixColorScheme = darkColorScheme(
    primary = AccentPrimary,
    secondary = AccentSecondary,
    background = Background,
    surface = SurfaceCard,
    surfaceVariant = SurfaceSecondary,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = StatusError
)

@Composable
fun PodmIxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PodMixColorScheme,
        typography = Typography,
        content = content
    )
}
