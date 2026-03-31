package net.asksakis.massdroidv2.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFCCCCCC),
    onPrimary = Color(0xFF222222),
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Color(0xFFDDDDDD),
    secondary = Color(0xFFAAAAAA),
    onSecondary = Color(0xFF222222),
    secondaryContainer = Color(0xFF333333),
    onSecondaryContainer = Color(0xFFDDDDDD),
    tertiary = Color(0xFF999999),
    tertiaryContainer = Color(0xFF2E2E2E),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFFAAAAAA),
    surfaceVariant = Color(0xFF2A2A2A),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerLow = Color(0xFF1A1A1A),
    outline = Color(0xFF666666),
    outlineVariant = Color(0xFF444444)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF333333),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E0E0),
    onPrimaryContainer = Color(0xFF222222),
    secondary = Color(0xFF555555),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E0E0),
    onSecondaryContainer = Color(0xFF222222),
    tertiary = Color(0xFF666666),
    tertiaryContainer = Color(0xFFEEEEEE),
    onBackground = Color(0xFF111111),
    onSurface = Color(0xFF111111),
    onSurfaceVariant = Color(0xFF444444),
    background = Color(0xFFDDDDDD),
    surface = Color(0xFFDDDDDD),
    surfaceVariant = Color(0xFFCCCCCC),
    surfaceContainerHigh = Color(0xFFC8C8C8),
    surfaceContainer = Color(0xFFD5D5D5),
    surfaceContainerLow = Color(0xFFDADADA),
    outline = Color(0xFF888888),
    outlineVariant = Color(0xFFBBBBBB)
)

@Composable
fun MassDroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val base = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (!darkTheme) {
                // Strengthen light theme: slightly warmer surfaces, more contrast
                base.copy(
                    background = Color(0xFFDDDDDD),
                    surface = Color(0xFFDDDDDD),
                    surfaceVariant = Color(0xFFCCCCCC),
                    surfaceContainerHigh = Color(0xFFC8C8C8),
                    surfaceContainer = Color(0xFFD5D5D5),
                    surfaceContainerLow = Color(0xFFDADADA),
                    onBackground = Color(0xFF111111),
                    onSurface = Color(0xFF111111),
                    onSurfaceVariant = Color(0xFF333333)
                )
            } else base
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = shapes,
        content = content
    )
}
