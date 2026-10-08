package com.dilinkauto.client.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.dilinkauto.protocol.UiPalette

/**
 * Phone-app theme. Material3 dark scheme tuned for the DiLink Auto palette.
 * Shared slots come from [UiPalette] so the phone and car apps cannot drift.
 */
@Composable
fun DiLinkAutoTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(UiPalette.PRIMARY),
        onPrimary = Color(UiPalette.ON_PRIMARY),
        secondary = Color(UiPalette.SECONDARY),
        background = Color(UiPalette.BACKGROUND),
        surface = Color(UiPalette.SURFACE),
        onBackground = Color(UiPalette.ON_BACKGROUND),
        onSurface = Color(UiPalette.ON_SURFACE)
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}
