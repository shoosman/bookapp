package com.shoosman.bookapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF2E6B5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7EBDC),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF8A5A2B),
    secondaryContainer = Color(0xFFFFDCC0),
    background = Color(0xFFFFFBF7),
    surface = Color(0xFFFFFBF7),
    surfaceVariant = Color(0xFFEDE6DE),
    onBackground = Color(0xFF1F1B16),
    onSurface = Color(0xFF1F1B16),
)

private val Sepia = lightColorScheme(
    primary = Color(0xFF8B5E3C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBD3B8),
    onPrimaryContainer = Color(0xFF2E1500),
    secondary = Color(0xFF6E5B45),
    secondaryContainer = Color(0xFFE7D7BC),
    background = Color(0xFFF4ECD8),
    surface = Color(0xFFF4ECD8),
    surfaceVariant = Color(0xFFE6DAC0),
    surfaceContainer = Color(0xFFEDE2CA),
    surfaceContainerHigh = Color(0xFFE9DDC4),
    surfaceContainerLow = Color(0xFFF1E7D1),
    onBackground = Color(0xFF4A3A2A),
    onSurface = Color(0xFF4A3A2A),
    onSurfaceVariant = Color(0xFF6B5A47),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FD3C1),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF1F5145),
    onPrimaryContainer = Color(0xFFB7EBDC),
    secondary = Color(0xFFE8BE95),
    secondaryContainer = Color(0xFF5C3F20),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    onBackground = Color(0xFFD6D2CC),
    onSurface = Color(0xFFD6D2CC),
)

@Composable
fun AppTheme(theme: String, content: @Composable () -> Unit) {
    val scheme = when (theme) {
        "light" -> Light
        "sepia" -> Sepia
        "dark" -> Dark
        else -> if (isSystemInDarkTheme()) Dark else Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun isDarkTheme(theme: String, systemDark: Boolean) = theme == "dark" || (theme == "system" && systemDark)
