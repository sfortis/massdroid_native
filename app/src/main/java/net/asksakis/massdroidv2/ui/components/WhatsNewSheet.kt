package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * What changed in the version just installed, shown once.
 *
 * A sheet rather than a screen because it is an interruption, and an interruption should
 * be dismissable by dragging it away. The animation leads, since the whole reason to stop
 * somebody is to show them a thing they would not have gone looking for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(release: WhatsNewRelease, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = SheetDefaults.sheetState(),
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column {
                SheetDefaults.HeaderTitle(
                    text = "New in ${release.versionName}",
                    modifier = Modifier.padding(
                        horizontal = SheetDefaults.HeaderHorizontalPadding,
                        vertical = SheetDefaults.HeaderVerticalPadding
                    )
                )
            }
            WhatsNewItems(
                release = release,
                modifier = Modifier.padding(horizontal = SheetDefaults.HeaderHorizontalPadding)
            )
            MdButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SheetDefaults.HeaderHorizontalPadding)
            ) { Text("Got it") }
        }
    }
}

/**
 * The entries themselves, without the sheet around them.
 *
 * The About screen shows the same list in place, so the two cannot drift apart: the sheet
 * is the interruption after an update and About is where somebody goes to read it again.
 */
@Composable
fun WhatsNewItems(release: WhatsNewRelease, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        release.items.forEach { item ->
            item.animation?.let { animation ->
                // Centred and well short of the full width: the instructions are the
                // point and a square running the width of the sheet pushed them off the
                // screen. Square because the source is; a ratio of its own would
                // letterbox it.
                AnimatedImage(
                    resId = animation,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(ANIMATION_SIZE)
                        .clip(MaterialTheme.shapes.medium)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "\u2022",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = item.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = item.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Small enough that the instructions beside it are what fills the sheet. */
private val ANIMATION_SIZE = 160.dp
