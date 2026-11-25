package app.smartdisplay.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BlackColorScheme = darkColorScheme(
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
)

@Composable
fun SmartDisplayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BlackColorScheme,
        content = content,
    )
}
