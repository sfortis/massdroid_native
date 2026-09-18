package net.asksakis.massdroidv2.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object SheetDefaults {
    /**
     * The state every bottom sheet in the app uses.
     *
     * Material settles a sheet taller than half the screen at half height and leaves the
     * rest behind a drag, so a sheet that does not pass this opens with its content cut
     * off. Every sheet here is meant to be read at once, so none of them use the partial
     * state. Sheets that need to animate themselves away before dismissing keep the
     * returned state and call `hide()` on it.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun sheetState(): SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    @Composable
    fun containerColor(): Color = MaterialTheme.colorScheme.surfaceContainer

    @Composable
    fun listItemColors(): ListItemColors = ListItemDefaults.colors(
        containerColor = Color.Transparent
    )

    @Composable
    fun HeaderTitle(text: String, modifier: Modifier = Modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
        )
    }

    val HeaderHorizontalPadding = 16.dp
    val HeaderVerticalPadding = 2.dp
}
