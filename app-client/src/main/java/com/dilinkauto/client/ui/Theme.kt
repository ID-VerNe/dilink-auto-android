package com.dilinkauto.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Phone-app theme. Material3 dark scheme tuned for the DiLink Auto palette.
 */
@Composable
fun DiLinkAutoTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF4FC3F7),
        onPrimary = Color.Black,
        secondary = Color(0xFF1A73E8),
        background = Color(0xFF0D1117),
        surface = Color(0xFF161B22),
        onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}
