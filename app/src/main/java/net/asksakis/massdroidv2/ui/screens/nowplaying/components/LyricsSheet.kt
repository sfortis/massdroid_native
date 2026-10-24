package net.asksakis.massdroidv2.ui.screens.nowplaying.components

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.data.lyrics.LyricsProvider
import net.asksakis.massdroidv2.ui.components.SheetDefaults
import net.asksakis.massdroidv2.ui.shared.SyncedLyricLine
import net.asksakis.massdroidv2.ui.shared.SyncedLyrics
import net.asksakis.massdroidv2.ui.shared.SyncedLyricsStyle

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
    elapsedTime: () -> Double,
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
        sheetMaxWidth = SheetDefaults.maxWidth(),
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
                    val haptic = LocalHapticFeedback.current
                    val syncedLines = remember(lyrics.parsedLines) {
                        lyrics.parsedLines.map { SyncedLyricLine(it.timeMs, it.text) }
                    }
                    // The drawing is shared with the TV player (:ui-shared); the phone keeps
                    // tap to seek, with a tick of haptic feedback.
                    SyncedLyrics(
                        parsedLines = syncedLines,
                        elapsedTimeMs = (elapsedTime() * 1000).toLong(),
                        timingOffsetMs = lyricsTimingOffsetMs,
                        style = SyncedLyricsStyle(
                            currentLine = MaterialTheme.typography.headlineSmall,
                            otherLine = MaterialTheme.typography.titleMedium,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            mutedNoteColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            noteIcon = Icons.Default.MusicNote
                        ),
                        onLineClick = { seconds ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSeekToLyricsPosition(seconds)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp)
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
