package net.asksakis.massdroidv2.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val MassDroidTvColors = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF0A0A14),
    background = Color(0xFF14141F),
    onBackground = Color(0xFFECECF4),
    surface = Color(0xFF22223A),
    onSurface = Color(0xFFECECF4),
    surfaceVariant = Color(0xFF2C2C46),
    onSurfaceVariant = Color(0xFFB9B9CC),
)

/** Dark, 10-foot color scheme so text reads clearly on the TV background. */
@Composable
fun MassDroidTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MassDroidTvColors, content = content)
}
