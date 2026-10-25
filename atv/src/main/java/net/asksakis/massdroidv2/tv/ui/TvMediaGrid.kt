package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Card
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import net.asksakis.massdroidv2.domain.model.SortOption

/**
 * The shared frame, grid and card of the TV grid screens (Browse, an artist's albums, Folders),
 * so moving from one to another keeps the same layout: the title at the top left, Sort at the
 * top right where a screen offers one, an optional row under the title, then the grid.
 */

/** Overscan-safe inset of the grid screens, the 56 dp the other TV screens use. */
val TV_GRID_EDGE = 56.dp
private const val GRID_COLUMNS = 6
private val GRID_SPACING = 20.dp

/**
 * The title row is as tall as the Sort button whether or not the screen has one, so the grid
 * starts at the same height on every grid screen.
 */
private val TITLE_ROW_MIN_HEIGHT = 40.dp

@Composable
fun TvGridScreen(
    title: String,
    onSort: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 40.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TITLE_ROW_MIN_HEIGHT)
                    .padding(horizontal = TV_GRID_EDGE),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (onSort != null) {
                    IconButton(onClick = onSort, modifier = Modifier.pointerClick(onClick = onSort)) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                    }
                }
            }
            if (header != null) {
                Spacer(Modifier.height(16.dp))
                header()
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
fun TvMediaGrid(state: LazyGridState, content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        state = state,
        // Fixed columns + fill-width cards keep the art aligned across the grid; the padding
        // and spacing leave room for the focus scale so the selected card is not clipped.
        columns = GridCells.Fixed(GRID_COLUMNS),
        contentPadding = PaddingValues(horizontal = TV_GRID_EDGE, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(GRID_SPACING),
        content = content
    )
}

/**
 * A square-art card of the grid screens. [circular] crops the art to a circle (artists).
 * Without an image, [placeholder] is drawn in its place when given (folders).
 */
@Composable
fun TvMediaCard(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    circular: Boolean = false,
    placeholder: ImageVector? = null,
    titleMaxLines: Int = 1,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth().pointerClick(onClick = onClick)) {
        Column {
            val imageModifier = Modifier.fillMaxWidth().aspectRatio(1f)
            if (imageUrl == null && placeholder != null) {
                Box(modifier = imageModifier, contentAlignment = Alignment.Center) {
                    Icon(
                        placeholder,
                        contentDescription = null,
                        tint = LocalContentColor.current.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                }
            } else {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = if (circular) imageModifier.clip(CircleShape) else imageModifier
                )
            }
            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    // A short name keeps the same height, so the cards of a row line up.
                    minLines = titleMaxLines,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** The sort picker of the grid screens; the screen's view model keeps the choice. */
@Composable
fun TvSortDialog(
    current: SortOption,
    sortOptions: List<SortOption>,
    descending: Boolean,
    onSelect: (SortOption) -> Unit,
    onSetDescending: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val initialFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp).width(260.dp)) {
                Text("Sort by", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                sortOptions.forEach { option ->
                    val selected = option == current
                    Surface(
                        onClick = { onSelect(option) },
                        colors = if (selected) {
                            ClickableSurfaceDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            ClickableSurfaceDefaults.colors()
                        },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        modifier = (if (selected) Modifier.fillMaxWidth().focusRequester(initialFocus)
                                    else Modifier.fillMaxWidth()).pointerClick { onSelect(option) }
                    ) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // Random has no reverse on the server, so a direction would mean nothing.
                if (current.reversible) {
                    Spacer(Modifier.height(8.dp))
                    Text("Direction", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DirectionChip("Ascending", selected = !descending) { onSetDescending(false) }
                        DirectionChip("Descending", selected = descending) { onSetDescending(true) }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }
}

@Composable
private fun DirectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        colors = if (selected) {
            ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            ClickableSurfaceDefaults.colors()
        },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        modifier = Modifier.pointerClick(onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
