package hu.apkforge

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF1F5C63), onPrimary = Color.White,
    primaryContainer = Color(0xFFBDEBF0), onPrimaryContainer = Color(0xFF00363B),
    tertiary = Color(0xFF7A5900), tertiaryContainer = Color(0xFFFFDEA6),
    background = Color(0xFFF6FAFA), surface = Color(0xFFF6FAFA),
    surfaceContainerHigh = Color(0xFFE6EEEF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD3DC), onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF12454B), onPrimaryContainer = Color(0xFFBDEBF0),
    tertiary = Color(0xFFF2C57C), tertiaryContainer = Color(0xFF5D4200),
    background = Color(0xFF0F1718), surface = Color(0xFF0F1718),
    surfaceContainerHigh = Color(0xFF1B2627),
)

@Composable
fun ApkForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
