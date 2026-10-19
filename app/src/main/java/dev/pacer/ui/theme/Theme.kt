package dev.pacer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PacerGreen = Color(0xFF00E676)
val PacerAmber = Color(0xFFFFB300)
val PacerRed = Color(0xFFFF5252)

// True black backgrounds: OLED pixels are off, which is the cheapest thing a screen can show.
private val colors = darkColorScheme(
    primary = PacerGreen,
    onPrimary = Color.Black,
    secondary = Color(0xFF80CBC4),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFBDBDBD),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainerLowest = Color.Black,
    error = PacerRed,
)

@Composable
fun PacerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
