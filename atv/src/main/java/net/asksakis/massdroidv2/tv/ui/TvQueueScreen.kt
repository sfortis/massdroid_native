package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import net.asksakis.massdroidv2.domain.model.displayName

/** Overscan-safe horizontal inset for 10-foot layout. */
private val EDGE = 56.dp

/** 10-foot view of the active queue. Current track highlighted; click plays that index. */
@Composable
fun TvQueueScreen(viewModel: TvQueueViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val currentId by viewModel.currentItemId.collectAsStateWithLifecycle()
    val isAudiobook by viewModel.isAudiobook.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val currentChapter by viewModel.currentChapterIndex.collectAsStateWithLifecycle()
    val load by viewModel.load.collectAsStateWithLifecycle()
    val showChapters = isAudiobook && chapters.isNotEmpty()

    // The playing row takes the cursor, scrolled into view, once the list first has rows: the
    // queue used to open at the top with nothing focused, and item 180 of a radio queue was
    // 180 presses away.
    val listState = rememberLazyListState()
    val rowFocus = remember { FocusRequester() }
    val focusIndex = if (showChapters) {
        currentChapter.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
    } else {
        items.indexOfFirst { it.queueItemId == currentId }.coerceAtLeast(0)
    }
    val hasRows = if (showChapters) chapters.isNotEmpty() else items.isNotEmpty()
    LaunchedEffect(hasRows, showChapters) {
        if (!hasRows) return@LaunchedEffect
        listState.scrollToItem(focusIndex)
        rowFocus.requestFocusWhenAttached()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 40.dp, bottom = 40.dp)) {
            Text(
                if (isAudiobook && chapters.isNotEmpty()) "Chapters" else "Queue",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = EDGE)
            )
            Spacer(Modifier.height(20.dp))
            when {
                showChapters -> {
                    // An audiobook is one queue item; show its chapters and seek on tap.
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = EDGE, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(chapters, key = { idx, ch -> "${ch.position}:${ch.start}:$idx" }) { idx, ch ->
                            ChapterRow(
                                index = idx,
                                name = ch.displayName(idx),
                                startSec = ch.start,
                                isCurrent = idx == currentChapter,
                                modifier = if (idx == focusIndex) Modifier.focusRequester(rowFocus) else Modifier,
                                onClick = { viewModel.seekToChapter(ch) }
                            )
                        }
                    }
                }
                load == TvLoad.LOADING -> TvContentMessage("Loading…", EDGE)
                load == TvLoad.FAILED ->
                    TvLoadFailed("Couldn't load the queue.", EDGE, onRetry = viewModel::retry)
                items.isEmpty() -> {
                    Text(
                        "Queue is empty",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = EDGE)
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = EDGE, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(items, key = { _, it -> it.queueItemId }) { index, item ->
                            QueueRow(
                                title = item.name.ifBlank { item.track?.name.orEmpty() },
                                subtitle = item.track?.artistNames?.takeIf { it.isNotBlank() }
                                    ?: item.track?.albumName.orEmpty(),
                                imageUrl = item.imageUrl,
                                isCurrent = item.queueItemId == currentId,
                                modifier = if (index == focusIndex) Modifier.focusRequester(rowFocus) else Modifier,
                                onClick = { viewModel.playIndex(index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    index: Int,
    name: String,
    startSec: Double,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().pointerClick(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.titleMedium,
                color = LocalContentColor.current.copy(alpha = 0.7f),
                modifier = Modifier.width(36.dp)
            )
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                formatChapterTime(startSec),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.7f)
            )
            if (isCurrent) {
                Icon(Icons.Filled.GraphicEq, contentDescription = "Playing")
            }
        }
    }
}

private fun formatChapterTime(seconds: Double): String {
    val t = seconds.toInt().coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun QueueRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().pointerClick(onClick = onClick)) {
        // Colors inherit the Surface's focus-aware content color (inverts on
        // focus) so the row stays readable focused or not. "Current" is shown by
        // a bold title + equalizer glyph, not a hardcoded bright color.
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                modifier = Modifier.size(56.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isCurrent) {
                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.GraphicEq, contentDescription = "Now playing")
                }
            }
        }
    }
}
