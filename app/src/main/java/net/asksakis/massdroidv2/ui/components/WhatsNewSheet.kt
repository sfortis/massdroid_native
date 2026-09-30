package net.asksakis.massdroidv2.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.domain.whatsnew.WHATS_NEW_ASSET_DIR
import net.asksakis.massdroidv2.domain.whatsnew.WhatsNewEntry
import net.asksakis.massdroidv2.domain.whatsnew.WhatsNewRelease
import net.asksakis.massdroidv2.domain.whatsnew.WhatsNewSection

/**
 * What changed in the version just installed, shown once.
 *
 * A sheet rather than a screen because it is an interruption, and an interruption should
 * be dismissable by dragging it away.
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
            SheetDefaults.HeaderTitle(
                text = "New in ${release.versionName}",
                modifier = Modifier.padding(
                    horizontal = SheetDefaults.HeaderHorizontalPadding,
                    vertical = SheetDefaults.HeaderVerticalPadding
                )
            )
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
 * Both read the release notes that shipped with the app, so what is on screen is what the
 * release page says, without a second copy of the text in the source.
 */
@Composable
fun WhatsNewItems(release: WhatsNewRelease, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SECTION_SPACING)
    ) {
        release.sections.forEach { section -> WhatsNewSectionBlock(section) }
    }
}

/** One heading from the release notes and the entries written under it. */
@Composable
private fun WhatsNewSectionBlock(section: WhatsNewSection) {
    Column(verticalArrangement = Arrangement.spacedBy(ENTRY_SPACING)) {
        // The known spelling when the app recognises the heading, the file's own when it
        // does not, and nothing at all for entries written above the first heading.
        (section.change?.heading ?: section.heading)?.let { heading ->
            Text(
                text = heading,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        section.entries.forEach { entry -> WhatsNewEntryRow(entry) }
    }
}

/** A single line of the changelog, under the animation the notes gave it, if any. */
@Composable
private fun WhatsNewEntryRow(entry: WhatsNewEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(ENTRY_SPACING)) {
        entry.animation?.let { animation ->
            // Centred and well short of the full width: the words are the point, and a
            // square running the width of the sheet pushed them off the screen. Square
            // because the source is; a ratio of its own would letterbox it.
            AnimatedImage(
                assetPath = "$WHATS_NEW_ASSET_DIR/$animation",
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(ANIMATION_SIZE)
                    .clip(MaterialTheme.shapes.medium)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "•",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A section stands away from the next one; its own entries sit closer together. */
private val SECTION_SPACING = 20.dp
private val ENTRY_SPACING = 8.dp

/** Small enough that the words beside it are what fills the sheet. */
private val ANIMATION_SIZE = 160.dp
