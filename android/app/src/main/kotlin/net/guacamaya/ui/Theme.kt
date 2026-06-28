package net.guacamaya.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Build

/**
 * Tiny dark-first Material3 theme. The app is dark-only — fits the radio-beacon
 * aesthetic and matches the parent Activity theme (`Theme.Guacamaya`).
 */
private val GuacamayaColors = darkColorScheme(
    primary = Color(0xFF7C5CFF),
    onPrimary = Color.White,
    secondary = Color(0xFFFF5C7C),
    error = Color(0xFFFF3B3B),
    background = Color(0xFF0E1320),
    surface = Color(0xFF161C2C),
    onSurface = Color(0xFFE6E8F0),
)

@Composable
fun GuacamayaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GuacamayaColors,
        content = content,
    )
}
