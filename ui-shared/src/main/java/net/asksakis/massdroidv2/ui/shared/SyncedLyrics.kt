package net.asksakis.massdroidv2.ui.shared

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** One timed line of synced lyrics. */
data class SyncedLyricLine(val timeMs: Long, val text: String)

/** How the phone and the TV draw synced lyrics; each passes its own theme's styles and colours. */
data class SyncedLyricsStyle(
    /** The line being sung. */
    val currentLine: TextStyle,
    /** Every other line. */
    val otherLine: TextStyle,
    val textColor: Color,
    /** The notes of a musical break that are still to come. */
    val mutedNoteColor: Color,
    /** The note drawn for a musical break, passed in so this module needs no icon set. */
    val noteIcon: ImageVector,
)

/**
 * Synced lyrics, the same on the phone and the TV: the line being sung is larger and bold
 * and sits about a third of the way down, the others fade with their distance from it, and
 * a long musical break shows a row of notes that fill as the break runs.
 *
 * [elapsedTimeMs] is the playback position; [timingOffsetMs] moves the lyrics against it.
 * [onLineClick], when given, makes each line seek to its time (the phone's tap to seek; the
 * TV passes none).
 */
@Composable
fun SyncedLyrics(
    parsedLines: List<SyncedLyricLine>,
    elapsedTimeMs: Long,
    timingOffsetMs: Int,
    style: SyncedLyricsStyle,
    modifier: Modifier = Modifier,
    onLineClick: ((Double) -> Unit)? = null,
) {
    val lines = remember(parsedLines) { displayLines(parsedLines) }
    var currentIndex by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var containerHeight by remember { mutableFloatStateOf(0f) }
    val topPaddingDp = remember(containerHeight, density) {
        with(density) { (containerHeight * TOP_PADDING_FRACTION).toDp() }
    }
    val bottomPaddingDp = remember(containerHeight, density) {
        with(density) { (containerHeight * BOTTOM_PADDING_FRACTION).toDp() }
    }

    LaunchedEffect(elapsedTimeMs, lines, timingOffsetMs) {
        if (lines.isEmpty()) return@LaunchedEffect
        val effectiveElapsedMs = elapsedTimeMs + HIGHLIGHT_LEAD_MS + timingOffsetMs
        val idx = lines.indexOfLast { it.timeMs <= effectiveElapsedMs }
        val newIdx = if (idx < 0) -1 else idx
        if (newIdx != currentIndex) {
            currentIndex = newIdx
            if (newIdx >= 0) {
                val anchorPx = containerHeight * ANCHOR_FRACTION
                val targetVisibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == newIdx }
                if (targetVisibleItem != null) {
                    val targetDelta = (targetVisibleItem.offset + targetVisibleItem.size / 2f) - anchorPx
                    listState.animateScrollBy(
                        value = targetDelta,
                        animationSpec = tween(durationMillis = SCROLL_MS, easing = FastOutSlowInEasing)
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
            .onGloballyPositioned { containerHeight = it.size.height.toFloat() }
            .clipToBounds(),
        contentPadding = PaddingValues(top = topPaddingDp, bottom = bottomPaddingDp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(lines) { index, line ->
            val distance = if (currentIndex >= 0) abs(index - currentIndex) else index
            val targetAlpha = when {
                index == currentIndex -> 1f
                distance <= 1 -> 0.62f
                distance <= 2 -> 0.40f
                distance <= 3 -> 0.24f
                else -> 0.14f
            }
            val alpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = tween(ALPHA_MS),
                label = "alpha$index"
            )
            if (line.isBreak) {
                val durationMs = ((line.breakEndMs ?: line.timeMs) - HIGHLIGHT_LEAD_MS - line.timeMs).coerceAtLeast(1L)
                val elapsedInBreakMs = (elapsedTimeMs + timingOffsetMs - line.timeMs).coerceAtLeast(0L)
                val noteProgress = (elapsedInBreakMs.toFloat() / durationMs.toFloat()) * NOTE_COUNT
                val filledNotes = noteProgress.toInt().coerceIn(0, NOTE_COUNT)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(NOTE_COUNT) { noteIndex ->
                            val tint = if (index == currentIndex && noteIndex < filledNotes) {
                                style.textColor.copy(alpha = alpha)
                            } else {
                                style.mutedNoteColor.copy(alpha = alpha.coerceAtLeast(0.22f))
                            }
                            Image(
                                imageVector = style.noteIcon,
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(tint),
                                modifier = Modifier.size(if (index == currentIndex) 18.dp else 16.dp)
                            )
                        }
                    }
                }
            } else {
                val lineStyle = if (index == currentIndex) {
                    style.currentLine.copy(fontWeight = FontWeight.Bold)
                } else {
                    style.otherLine
                }
                BasicText(
                    text = line.text,
                    style = lineStyle.copy(color = style.textColor.copy(alpha = alpha), textAlign = TextAlign.Center),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onLineClick != null) {
                                Modifier.clickable { onLineClick(line.timeMs / MILLIS_PER_SECOND) }
                            } else {
                                Modifier
                            }
                        )
                        .padding(vertical = 6.dp)
                )
            }
        }
    }
}

private data class DisplayLine(
    val timeMs: Long,
    val text: String,
    val isBreak: Boolean,
    val breakEndMs: Long? = null
)

/**
 * The lines to show: the sung lines, a break row before a late first line, and a break row
 * wherever the lyrics mark a pause of [MUSICAL_BREAK_THRESHOLD_MS] or longer.
 */
private fun displayLines(parsedLines: List<SyncedLyricLine>): List<DisplayLine> {
    val breakMarkers = parsedLines
        .filter { it.timeMs > 0L && it.text.isBlank() }
        .map { it.timeMs }
        .toSet()
    val contentLines = parsedLines.filter { it.text.isNotBlank() }
    return buildList {
        if (contentLines.isNotEmpty()) {
            val firstTime = contentLines.first().timeMs
            if (firstTime >= INTRO_BREAK_LEAD_MS) {
                add(DisplayLine(firstTime - INTRO_BREAK_LEAD_MS, "", isBreak = true, breakEndMs = firstTime))
            }
        }
        contentLines.forEachIndexed { index, line ->
            add(DisplayLine(line.timeMs, line.text, isBreak = false))
            val nextLine = contentLines.getOrNull(index + 1) ?: return@forEachIndexed
            val markerTime = breakMarkers.firstOrNull { it > line.timeMs && it < nextLine.timeMs }
            if (markerTime != null && nextLine.timeMs - markerTime >= MUSICAL_BREAK_THRESHOLD_MS) {
                add(DisplayLine(markerTime, "", isBreak = true, breakEndMs = nextLine.timeMs))
            }
        }
    }
}

private const val INTRO_BREAK_LEAD_MS = 5_000L
private const val MUSICAL_BREAK_THRESHOLD_MS = 5_000L
private const val NOTE_COUNT = 7
private const val HIGHLIGHT_LEAD_MS = 500L
private const val TOP_PADDING_FRACTION = 0.16f
private const val BOTTOM_PADDING_FRACTION = 0.38f
private const val ANCHOR_FRACTION = 0.35f
private const val SCROLL_MS = 520
private const val ALPHA_MS = 500
private const val MILLIS_PER_SECOND = 1000.0
