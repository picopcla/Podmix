package com.podmix.v2.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF5A623),
    onPrimary = Color(0xFF111111),
    secondary = Color(0xFF7CD6FF),
    background = Color(0xFF071018),
    onBackground = Color(0xFFF2F5F7),
    surface = Color(0xFF10151C),
    onSurface = Color(0xFFF2F5F7),
    error = Color(0xFFFF6B6B)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFCD7E00),
    onPrimary = Color.White,
    secondary = Color(0xFF006B8F),
    background = Color(0xFFF4F7FA),
    onBackground = Color(0xFF141A20),
    surface = Color.White,
    onSurface = Color(0xFF141A20),
    error = Color(0xFFB3261E)
)

@Composable
fun PodmixV2Theme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
