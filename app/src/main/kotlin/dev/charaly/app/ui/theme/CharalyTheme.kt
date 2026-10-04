package dev.charaly.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF1B1B1F)
private val Parchment = Color(0xFFF6F1E7)
private val Lamp = Color(0xFFE0A458)
private val Brass = Color(0xFFB98A44)
private val Moss = Color(0xFF4F6B52)
private val Dusk = Color(0xFF17161A)

private val LightColors = lightColorScheme(
    primary = Brass,
    onPrimary = Color.White,
    secondary = Moss,
    background = Parchment,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDE5D6),
)

private val DarkColors = darkColorScheme(
    primary = Lamp,
    onPrimary = Dusk,
    secondary = Color(0xFF9DBB9F),
    background = Dusk,
    onBackground = Color(0xFFE8E2D6),
    surface = Color(0xFF211F26),
    onSurface = Color(0xFFE8E2D6),
    surfaceVariant = Color(0xFF2E2B34),
)

@Composable
fun CharalyTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
