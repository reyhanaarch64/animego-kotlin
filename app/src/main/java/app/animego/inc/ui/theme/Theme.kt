package app.animego.inc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC5A7FF),
    onPrimary = Color(0xFF251044),
    secondary = Color(0xFFFF8BAA),
    onSecondary = Color(0xFF3E1024),
    tertiary = Color(0xFF7ED9CB),
    background = Color(0xFF0B0B12),
    onBackground = Color(0xFFF4F1FA),
    surface = Color(0xFF15141E),
    onSurface = Color(0xFFF4F1FA),
    surfaceVariant = Color(0xFF252331),
    onSurfaceVariant = Color(0xFFB9B4C8),
    outline = Color(0xFF4B4659)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6945A8),
    onPrimary = Color.White,
    secondary = Color(0xFFB34268),
    onSecondary = Color.White,
    tertiary = Color(0xFF267E72),
    background = Color(0xFFF8F6FC),
    onBackground = Color(0xFF191620),
    surface = Color.White,
    onSurface = Color(0xFF191620),
    surfaceVariant = Color(0xFFECE8F3),
    onSurfaceVariant = Color(0xFF5F596B)
)

@Composable
fun AnimeGoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
