package net.asksakis.massdroidv2.ui.screens.nowplaying.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Icon button that fires [onStep] once on press, then repeats every 220 ms
 * while held. First repeat is gated by 420 ms so a quick tap does not
 * accidentally generate a second event. Used for lyrics timing fine-tune
 * (+/- 100 ms) and other "press-and-hold to step" affordances.
 */
@Composable
internal fun HoldRepeatIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onStep: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .pointerInput(onStep) {
                detectTapGestures(
                    onPress = {
                        onStep()
                        val releasedEarly = withTimeoutOrNull(420) { tryAwaitRelease() } == true
                        if (releasedEarly) return@detectTapGestures
                        while (true) {
                            onStep()
                            val released = withTimeoutOrNull(220) { tryAwaitRelease() } == true
                            if (released) break
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
    }
}
