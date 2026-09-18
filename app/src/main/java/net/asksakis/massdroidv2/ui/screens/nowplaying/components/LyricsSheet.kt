package net.asksakis.massdroidv2.ui.screens.nowplaying.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.data.lyrics.LyricsProvider
import net.asksakis.massdroidv2.ui.components.SheetDefaults

/**
 * Bottom-sheet host for lyrics. Renders four mutually-exclusive states:
 * loading, no lyrics, plain text, or synchronised LRC. Synced lyrics get
 * auto-scroll, line highlighting, tap-to-seek, musical-break note rows,
 * and an inline timing adjuster.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsSheet(
    lyrics: LyricsProvider.LyricsContent,
    isLoading: Boolean,
    elapsedTime: Double,
    title: String,
    artist: String,
    lyricsTimingOffsetMs: Int,
    onLyricsTimingOffsetChanged: (Int) -> Unit,
    onLyricsTimingOffsetDelta: (Int) -> Unit,
    onSeekToLyricsPosition: (Double) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = SheetDefaults.sheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetDefaults.containerColor()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Lyrics",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = buildString {
                        append(title)
                        if (artist.isNotBlank()) {
                            append(" - ")
                            append(artist)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(18.dp))

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                lyrics == LyricsProvider.LyricsContent.None -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No lyrics available",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                lyrics is LyricsProvider.LyricsContent.Synced -> {
                    SyncedLyricsContent(
                        parsedLines = lyrics.parsedLines,
                        elapsedTimeMs = (elapsedTime * 1000).toLong(),
                        lyricsTimingOffsetMs = lyricsTimingOffsetMs,
                        onSeekToPosition = onSeekToLyricsPosition,
                        modifier = Modifier.weight(1f)
                    )
                    LyricsTimingAdjuster(
                        offsetMs = lyricsTimingOffsetMs,
                        onOffsetChanged = onLyricsTimingOffsetChanged,
                        onOffsetDelta = onLyricsTimingOffsetDelta
                    )
                }
                lyrics is LyricsProvider.LyricsContent.Plain -> {
                    PlainLyricsContent(text = lyrics.text)
                }
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No lyrics available",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainLyricsContent(text: String) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.4,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SyncedLyricsContent(
    parsedLines: List<LyricsProvider.LrcLine>,
    elapsedTimeMs: Long,
    lyricsTimingOffsetMs: Int,
    onSeekToPosition: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    data class DisplayLine(
        val timeMs: Long,
        val text: String,
        val isBreak: Boolean,
        val breakEndMs: Long? = null
    )

    val introBreakLeadMs = 5_000L
    val musicalBreakThresholdMs = 5_000L
    val noteCount = 7
    val highlightLeadMs = 500L
    val lines = remember(parsedLines) {
        val breakMarkers = parsedLines
            .filter { it.timeMs > 0L && it.text.isBlank() }
            .map { it.timeMs }
            .toSet()
        val contentLines = parsedLines.filter { it.text.isNotBlank() }
        buildList {
            if (contentLines.isNotEmpty()) {
                val firstTime = contentLines.first().timeMs
                if (firstTime >= introBreakLeadMs) {
                    add(
                        DisplayLine(
                            timeMs = firstTime - introBreakLeadMs,
                            text = "",
                            isBreak = true,
                            breakEndMs = firstTime
                        )
                    )
                }
            }

            contentLines.forEachIndexed { index, line ->
                add(
                    DisplayLine(
                        timeMs = line.timeMs,
                        text = line.text,
                        isBreak = false
                    )
                )

                val nextLine = contentLines.getOrNull(index + 1) ?: return@forEachIndexed
                val markerTime = breakMarkers.firstOrNull { it > line.timeMs && it < nextLine.timeMs }
                if (markerTime != null && nextLine.timeMs - markerTime >= musicalBreakThresholdMs) {
                    add(
                        DisplayLine(
                            timeMs = markerTime,
                            text = "",
                            isBreak = true,
                            breakEndMs = nextLine.timeMs
                        )
                    )
                }
            }
        }
    }
    var currentIndex by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var containerHeight by remember { mutableFloatStateOf(0f) }
    val topPaddingDp = remember(containerHeight, density) {
        with(density) { (containerHeight * 0.16f).toDp() }
    }
    val bottomPaddingDp = remember(containerHeight, density) {
        with(density) { (containerHeight * 0.38f).toDp() }
    }

    LaunchedEffect(elapsedTimeMs, lines, lyricsTimingOffsetMs) {
        if (lines.isEmpty()) return@LaunchedEffect
        val effectiveElapsedMs = elapsedTimeMs + highlightLeadMs + lyricsTimingOffsetMs
        val idx = lines.indexOfLast { it.timeMs <= effectiveElapsedMs }
        val newIdx = if (idx < 0) -1 else idx
        if (newIdx != currentIndex) {
            currentIndex = newIdx
            if (newIdx >= 0) {
                val anchorPx = containerHeight * 0.35f
                val targetVisibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == newIdx }
                if (targetVisibleItem != null) {
                    val targetDelta = (targetVisibleItem.offset + targetVisibleItem.size / 2f) - anchorPx
                    listState.animateScrollBy(
                        value = targetDelta,
                        animationSpec = tween(
                            durationMillis = 520,
                            easing = FastOutSlowInEasing
                        )
                    )
                } else {
                    listState.animateScrollToItem(index = newIdx)
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .onGloballyPositioned { containerHeight = it.size.height.toFloat() }
            .clipToBounds(),
        contentPadding = PaddingValues(
            top = topPaddingDp,
            bottom = bottomPaddingDp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(lines) { index, line ->
            val distance = if (currentIndex >= 0) kotlin.math.abs(index - currentIndex) else index
            val targetAlpha = when {
                index == currentIndex -> 1f
                distance <= 1 -> 0.62f
                distance <= 2 -> 0.40f
                distance <= 3 -> 0.24f
                else -> 0.14f
            }
            val alpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = tween(500),
                label = "alpha$index"
            )
            if (line.isBreak) {
                val durationMs = ((line.breakEndMs ?: line.timeMs) - highlightLeadMs - line.timeMs).coerceAtLeast(1L)
                val elapsedInBreakMs = (elapsedTimeMs + lyricsTimingOffsetMs - line.timeMs).coerceAtLeast(0L)
                val noteProgress = (elapsedInBreakMs.toFloat() / durationMs.toFloat()) * noteCount
                val filledNotes = noteProgress.toInt().coerceIn(0, noteCount)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(noteCount) { noteIndex ->
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = if (index == currentIndex && noteIndex < filledNotes) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha.coerceAtLeast(0.22f))
                                },
                                modifier = Modifier.size(if (index == currentIndex) 18.dp else 16.dp)
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = line.text,
                    style = if (index == currentIndex) MaterialTheme.typography.headlineSmall
                        else MaterialTheme.typography.titleMedium,
                    fontWeight = if (index == currentIndex) FontWeight.Bold else null,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSeekToPosition(line.timeMs / 1000.0)
                        }
                        .padding(vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun LyricsTimingAdjuster(
    offsetMs: Int,
    onOffsetChanged: (Int) -> Unit,
    onOffsetDelta: (Int) -> Unit
) {
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f)
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
    val stepMs = 500
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
            shape = RoundedCornerShape(999.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                HoldRepeatIconButton(
                    contentDescription = "Lyrics earlier",
                    tint = iconTint,
                    icon = Icons.Default.Remove,
                    onStep = { onOffsetDelta(-stepMs) }
                )
                Text(
                    text = "Timing ${formatLyricsOffset(offsetMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .clickable { onOffsetChanged(0) }
                )
                HoldRepeatIconButton(
                    contentDescription = "Lyrics later",
                    tint = iconTint,
                    icon = Icons.Default.Add,
                    onStep = { onOffsetDelta(stepMs) }
                )
            }
        }
    }
}

private fun formatLyricsOffset(offsetMs: Int): String {
    val seconds = offsetMs / 1000f
    return String.format(
        java.util.Locale.US,
        "%+.1fs",
        seconds
    )
}
