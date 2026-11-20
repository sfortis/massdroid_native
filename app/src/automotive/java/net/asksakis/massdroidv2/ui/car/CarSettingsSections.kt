package net.asksakis.massdroidv2.ui.car

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The sections of the car settings screen, in the order the list shows them. */
internal enum class CarSection(val title: String, val subtitle: String, val icon: ImageVector) {
    Server("Server", "Music Assistant", Icons.Default.Dns),
    GuestAccess("Guest access", "Party mode", Icons.Default.Celebration),
    Player("Player", "Audio output", Icons.Default.Speaker),
    About("About", "Version and privacy", Icons.Default.Info),
}

/** The window width from which the section list and the open section are shown side by side. */
internal const val TWO_PANE_MIN_WIDTH_DP = 840

/** The width of the section list beside the open section. */
internal val SECTION_LIST_WIDTH: Dp = 300.dp

/**
 * The section list. [selected] is highlighted, and is null on a narrow window, where the list is
 * shown alone and a tap opens the section over it: there the rows are buttons, beside an open
 * section they are a group of tabs.
 */
@Composable
internal fun CarSectionList(selected: CarSection?, onSelect: (CarSection) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .then(if (selected != null) Modifier.selectableGroup() else Modifier)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CarSection.entries.forEach { section ->
            CarSectionRow(
                section = section,
                selected = section == selected,
                asTab = selected != null,
                onClick = { onSelect(section) },
            )
        }
    }
}

@Composable
private fun CarSectionRow(section: CarSection, selected: Boolean, asTab: Boolean, onClick: () -> Unit) {
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The car's minimum touch target.
            .heightIn(min = SECTION_ROW_MIN_HEIGHT)
            .clip(MaterialTheme.shapes.large)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .then(
                if (asTab) {
                    Modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
                } else {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                }
            )
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(section.icon, contentDescription = null, tint = content)
        Spacer(Modifier.width(20.dp))
        Column {
            Text(section.title, style = MaterialTheme.typography.titleLarge, color = content)
            Text(
                section.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One section's content, scrolling on its own with [scrollState]. The content gets the height of
 * the visible area, which a content that should fit without scrolling can size itself to.
 */
@Composable
internal fun CarSectionPane(scrollState: ScrollState, content: @Composable ColumnScope.(viewportHeight: Dp) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 40.dp, vertical = SECTION_PANE_VERTICAL_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = SECTION_CONTENT_MAX_WIDTH).fillMaxWidth()) {
                content(viewportHeight)
            }
        }
    }
}

/** The space above and below a section's content. */
internal val SECTION_PANE_VERTICAL_PADDING: Dp = 32.dp

private val SECTION_ROW_MIN_HEIGHT: Dp = 76.dp
private val SECTION_CONTENT_MAX_WIDTH: Dp = 640.dp
