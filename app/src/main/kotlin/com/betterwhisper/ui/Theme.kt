package com.betterwhisper.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BetterWhisperColors = darkColorScheme(
    primary = Color(0xFF3B82F6),
    secondary = Color(0xFF10B981),
    surface = Color(0xFF161B22),
    background = Color(0xFF0F1219)
)

@Composable
fun BetterWhisperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BetterWhisperColors, content = content)
}
