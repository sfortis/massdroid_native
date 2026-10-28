package net.asksakis.massdroidv2.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ThumbDown
import net.asksakis.massdroidv2.data.lyrics.LyricsProvider
import net.asksakis.massdroidv2.domain.feedback.DislikeUndo
import net.asksakis.massdroidv2.domain.info.AboutText
import net.asksakis.massdroidv2.ui.shared.SyncedLyricLine
import net.asksakis.massdroidv2.ui.shared.SyncedLyrics
import net.asksakis.massdroidv2.ui.shared.SyncedLyricsStyle
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import net.asksakis.massdroidv2.domain.player.shownVolume
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import coil.compose.AsyncImage
import net.asksakis.massdroidv2.ui.shared.AlbumArtBackdrop
import net.asksakis.massdroidv2.ui.shared.FadingAutoScrollText
import net.asksakis.massdroidv2.ui.shared.fadingAutoScrollTextHeight
import net.asksakis.massdroidv2.ui.shared.rememberSettledArtwork
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.RepeatMode
import net.asksakis.massdroidv2.domain.model.skipAvailability

@Composable
fun TvNowPlayingScreen(
    onOpenQueue: () -> Unit = {},
    viewModel: TvNowPlayingViewModel = hiltViewModel(),
    settingsViewModel: TvSettingsViewModel = hiltViewModel()
) {
    val player by viewModel.player.collectAsStateWithLifecycle()
    // The position is read only by the seek bar, so the 500 ms ticker recomposes that and not
    // the whole screen, as on the phone's Now Playing.
    val elapsedState = viewModel.elapsed.collectAsStateWithLifecycle()
    val elapsed: () -> Double = remember(elapsedState) { { elapsedState.value } }
    val queue by viewModel.queueState.collectAsStateWithLifecycle()
    val skip = queue.skipAvailability()
    val outputDelay by settingsViewModel.outputDelay.collectAsStateWithLifecycle()
    var showOptions by remember { mutableStateOf(false) }
    val media = player?.currentMedia
    val duration = media?.duration ?: 0.0
    val playing = player?.state == PlaybackState.PLAYING
    val shuffleOn = queue?.shuffleEnabled == true
    val repeatMode = queue?.repeatMode ?: RepeatMode.OFF

    // The cover, blurred, behind the player, the same backdrop as the phone's. It follows the
    // settled cover, so skipping through tracks does not repaint the screen for each one.
    val settledArtwork = rememberSettledArtwork(media?.imageUrl)
    val about by viewModel.about.collectAsStateWithLifecycle()
    // The layout follows the last settled answer: while the next album is looked up, the
    // cover keeps its size instead of growing and shrinking back on every album change.
    var withAbout by remember { mutableStateOf(false) }
    LaunchedEffect(about) {
        when (about) {
            is AboutText.Shown -> withAbout = true
            AboutText.Absent -> withAbout = false
            AboutText.Loading -> Unit
        }
    }
    val coverSize by animateDpAsState(
        targetValue = if (withAbout) COVER_SIZE_WITH_ABOUT else COVER_SIZE,
        animationSpec = tween(ABOUT_RESIZE_MS, easing = FastOutSlowInEasing),
        label = "cover"
    )
    val lyricsShown by viewModel.lyricsShown.collectAsStateWithLifecycle()
    val volumeShown by viewModel.volumeShown.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val playFocus = remember { FocusRequester() }

    // "Not for me" buries the track and skips past it, so the bar offers the way back, as the
    // phone's snackbar does. The Undo takes the cursor while the bar shows; when the bar goes,
    // the cursor goes back to Play rather than nowhere.
    var pendingUndo by remember { mutableStateOf<DislikeUndo?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var undoFocused by remember { mutableStateOf(false) }
    val undoFocus = remember { FocusRequester() }
    val dismissUndo = {
        val hadFocus = undoFocused
        pendingUndo = null
        if (hadFocus) runCatching { playFocus.requestFocus() }
    }
    LaunchedEffect(Unit) {
        viewModel.dislikeUndo.collectLatest { undo ->
            notice = null
            pendingUndo = undo
            delay(UNDO_WINDOW_MS)
            dismissUndo()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.notice.collectLatest { text ->
            notice = text
            delay(NOTICE_MS)
            notice = null
        }
    }
    LaunchedEffect(pendingUndo) {
        if (pendingUndo != null) undoFocus.requestFocusWhenAttached()
    }
    BackHandler(enabled = pendingUndo != null) { dismissUndo() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!settledArtwork.isNullOrBlank()) {
                AlbumArtBackdrop(
                    imageUrl = settledArtwork,
                    isDark = true,
                    surfaceColor = MaterialTheme.colorScheme.surface
                )
            }
            Row(
                modifier = Modifier.fillMaxSize().padding(64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(48.dp)
            ) {
                // The left side shows the cover, or the lyrics in its place when they are on.
                Crossfade(targetState = lyricsShown, animationSpec = tween(ABOUT_RESIZE_MS), label = "left") { showLyrics ->
                    if (showLyrics) {
                        LyricsPanel(
                            lyrics = lyrics,
                            elapsed = elapsed,
                            modifier = Modifier.width(COVER_SIZE).fillMaxHeight()
                        )
                    } else {
                    // The cover, with a few words about the album, or the artist, rolling slowly under
                    // it, as wide as the cover. With text the cover makes room for four lines (cover,
                    // gap and lines fill the 412 dp between the paddings); without, it takes the
                    // height back. Both moves run together, so the change reads as one resize.
                    Column(modifier = Modifier.width(coverSize)) {
                        AsyncImage(
                            model = media?.imageUrl,
                            contentDescription = media?.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(coverSize)
                        )
                        val aboutStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AnimatedVisibility(
                            visible = withAbout,
                            enter = expandVertically(tween(ABOUT_RESIZE_MS, easing = FastOutSlowInEasing)) +
                                fadeIn(tween(ABOUT_RESIZE_MS)),
                            exit = shrinkVertically(tween(ABOUT_RESIZE_MS, easing = FastOutSlowInEasing)) +
                                fadeOut(tween(ABOUT_RESIZE_MS))
                        ) {
                            Column {
                                Spacer(Modifier.height(16.dp))
                                // The old album's text fades out while the next one is looked up,
                                // and the new one fades in, inside a box that keeps its size.
                                Crossfade(
                                    targetState = (about as? AboutText.Shown)?.text,
                                    animationSpec = tween(ABOUT_RESIZE_MS),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(fadingAutoScrollTextHeight(aboutStyle, ABOUT_TEXT_LINES)),
                                    label = "about"
                                ) { text ->
                                    if (text != null) {
                                        FadingAutoScrollText(text = text, style = aboutStyle, visibleLines = ABOUT_TEXT_LINES)
                                    }
                                }
                            }
                        }
                    }
                    }
                }

                Column(modifier = Modifier.width(720.dp)) {
                    Text(
                        player?.displayName ?: "Player",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        media?.title?.takeIf { it.isNotBlank() } ?: "Nothing playing",
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        media?.artist.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(28.dp))
                    SeekBar(
                        elapsed = elapsed,
                        duration = duration,
                        enabled = duration > 0.0,
                        onSeekBy = viewModel::seekBy
                    )

                    Spacer(Modifier.height(24.dp))
                    // Row 1: transport (centered) with shuffle + repeat
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TransportIcon(
                            Icons.Filled.Shuffle,
                            "Shuffle",
                            active = shuffleOn
                        ) { viewModel.toggleShuffle() }
                        TransportIcon(Icons.Filled.SkipPrevious, "Previous", enabled = skip.previous) {
                            viewModel.previous()
                        }
                        TransportIcon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "Pause" else "Play",
                            modifier = Modifier.focusRequester(playFocus)
                        ) { viewModel.playPause() }
                        TransportIcon(Icons.Filled.SkipNext, "Next", enabled = skip.next) { viewModel.next() }
                        TransportIcon(
                            if (repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            "Repeat",
                            active = repeatMode != RepeatMode.OFF
                        ) { viewModel.cycleRepeat() }
                    }
                    Spacer(Modifier.height(16.dp))
                    // Row 2: "Not for me" and the lyrics, as on the phone's action row, then the
                    // volume of a player elsewhere (see TvNowPlayingViewModel.volumeShown).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TransportIcon(Icons.Filled.ThumbDown, "Not for me") { viewModel.dislikeCurrentTrack() }
                        TransportIcon(Icons.Filled.MusicNote, "Lyrics", active = lyricsShown) { viewModel.toggleLyrics() }
                        if (volumeShown) {
                            // A thin rule between the track's actions and the player's volume.
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            )
                            TransportIcon(Icons.Filled.Remove, "Volume down") { viewModel.volumeDown() }
                            Text(
                                "${player?.let(::shownVolume) ?: 0}%",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TransportIcon(Icons.Filled.Add, "Volume up") { viewModel.volumeUp() }
                        }
                    }

                    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
                }
            }

            val barText = pendingUndo?.let { "Won't play \"${it.trackName}\" again" } ?: notice
            // The last text stays while the bar fades out.
            var lastBarText by remember { mutableStateOf("") }
            LaunchedEffect(barText) { if (barText != null) lastBarText = barText }
            AnimatedVisibility(
                visible = barText != null,
                enter = fadeIn(tween(BAR_FADE_MS)),
                exit = fadeOut(tween(BAR_FADE_MS)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
            ) {
                TvNoticeBar(
                    text = lastBarText,
                    onUndo = pendingUndo?.let { undo ->
                        {
                            viewModel.undoDislike(undo)
                            dismissUndo()
                        }
                    },
                    undoFocus = undoFocus,
                    onUndoFocusChanged = { undoFocused = it }
                )
            }

            // Top-right actions: the active queue, then audio options (3-dots),
            // like the phone's player settings entry point.
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(28.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onOpenQueue, modifier = Modifier.pointerClick(onClick = onOpenQueue)) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue")
                }
                val openOptions = { showOptions = true }
                IconButton(onClick = openOptions, modifier = Modifier.pointerClick(onClick = openOptions)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Audio options")
                }
            }
        }
    }
    // A dialog and not a section under the controls: the column beside the artwork does not
    // scroll, so a section added below the volume row ran off the bottom of the screen and
    // the slider could not be reached.
    if (showOptions) {
        OutputDelayDialog(
            outputDelay = outputDelay,
            onChange = settingsViewModel::setOutputDelay,
            onDismiss = { showOptions = false }
        )
    }
}

@Composable
private fun OutputDelayDialog(
    outputDelay: TvOutputDelay?,
    onChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sliderFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            OutputDelayContent(
                outputDelay = outputDelay,
                onChange = onChange,
                sliderFocus = sliderFocus,
                modifier = Modifier.padding(24.dp).width(OPTIONS_DIALOG_WIDTH)
            ) {
                Button(onClick = onDismiss, modifier = Modifier.pointerClick(onClick = onDismiss)) { Text("Done") }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { sliderFocus.requestFocus() } }
}

private val OPTIONS_DIALOG_WIDTH = 600.dp

@Composable
private fun TransportIcon(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    // Active toggles (shuffle on / repeat) get a filled primary container; the
    // icon inherits the IconButton's focus-aware content color (turns dark when
    // the button is focused), so nothing is hardcoded to a fixed brightness.
    val colors = if (active) {
        IconButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    } else {
        IconButtonDefaults.colors()
    }
    // A disabled tv-material button keeps its focus, so the D-pad still steps across the row.
    IconButton(
        onClick = onClick,
        modifier = modifier.pointerClick(enabled = enabled, onClick = onClick),
        enabled = enabled,
        colors = colors
    ) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun SeekBar(
    elapsed: () -> Double,
    duration: Double,
    enabled: Boolean,
    onSeekBy: (Double) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val position = elapsed()
    val fraction = if (duration > 0.0) (position / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (focused) 10.dp else 6.dp)
                .onFocusChanged { focused = it.isFocused }
                .focusable(enabled)
                .onKeyEvent { e ->
                    if (!enabled || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { onSeekBy(-SEEK_STEP_S); true }
                        Key.DirectionRight -> { onSeekBy(SEEK_STEP_S); true }
                        else -> false
                    }
                }
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(if (focused) 10.dp else 6.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val SEEK_STEP_S = 10.0

private fun formatTime(seconds: Double): String {
    val t = seconds.toInt().coerceAtLeast(0)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The cover's edge with nothing under it. */
private val COVER_SIZE = 360.dp

/** The cover's edge with the about text under it: 300 + 16 + four lines fill the column. */
private val COVER_SIZE_WITH_ABOUT = 300.dp

/** One resize, cover and text together; slow enough to read as a move, not a jump. */
private const val ABOUT_RESIZE_MS = 600

/** Lines of the about text in view at once under the cover. */
private const val ABOUT_TEXT_LINES = 4

/** Lines of plain (unsynced) lyrics in view at once; they roll like the about text. */
private const val PLAIN_LYRICS_LINES = 15

/** How long the Undo stays offered after "Not for me"; the phone's short snackbar is about as long. */
private const val UNDO_WINDOW_MS = 6_000L
private const val NOTICE_MS = 3_000L
private const val BAR_FADE_MS = 250

/**
 * The lyrics in the cover's place: synced lyrics drawn like the phone's (shared from
 * :ui-shared), or plain lyrics rolling slowly. Not focusable, so the D-pad stays on the
 * controls; the lines follow the playback position by themselves.
 */
@Composable
private fun LyricsPanel(lyrics: TvLyrics, elapsed: () -> Double, modifier: Modifier = Modifier) {
    val messageStyle = MaterialTheme.typography.bodyLarge
    val messageColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (lyrics) {
            TvLyrics.Loading -> Text("Loading lyrics\u2026", style = messageStyle, color = messageColor)
            TvLyrics.None -> Text("No lyrics available", style = messageStyle, color = messageColor)
            TvLyrics.Failed -> Text("Couldn't load the lyrics", style = messageStyle, color = messageColor)
            is TvLyrics.Ready -> when (val content = lyrics.content) {
                is LyricsProvider.LyricsContent.Synced -> {
                    val lines = remember(content) { content.parsedLines.map { SyncedLyricLine(it.timeMs, it.text) } }
                    SyncedLyrics(
                        parsedLines = lines,
                        elapsedTimeMs = (elapsed() * MILLIS_PER_SECOND).toLong(),
                        timingOffsetMs = 0,
                        style = SyncedLyricsStyle(
                            currentLine = MaterialTheme.typography.headlineSmall,
                            otherLine = MaterialTheme.typography.titleMedium,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            mutedNoteColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            noteIcon = Icons.Filled.MusicNote
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is LyricsProvider.LyricsContent.Plain -> FadingAutoScrollText(
                    text = content.text,
                    style = messageStyle.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                    visibleLines = PLAIN_LYRICS_LINES
                )
                LyricsProvider.LyricsContent.None ->
                    Text("No lyrics available", style = messageStyle, color = messageColor)
            }
        }
    }
}

private const val MILLIS_PER_SECOND = 1000

/** A short message at the bottom of the player, with Undo when [onUndo] is given. */
@Composable
private fun TvNoticeBar(
    text: String,
    onUndo: (() -> Unit)?,
    undoFocus: FocusRequester,
    onUndoFocusChanged: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = NOTICE_TEXT_MAX_WIDTH)
        )
        if (onUndo != null) {
            Spacer(Modifier.width(16.dp))
            Button(
                onClick = onUndo,
                modifier = Modifier
                    .focusRequester(undoFocus)
                    .onFocusChanged { onUndoFocusChanged(it.isFocused) }
                    .pointerClick(onClick = onUndo)
            ) {
                Text("Undo")
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

private val NOTICE_TEXT_MAX_WIDTH = 520.dp
