package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player

private const val FOCUS_ATTACH_TRIES = 10
private const val FOCUS_ATTACH_RETRY_MS = 50L

// Explicit focus order along the bar: index 0 is the media surface, 1..5 the buttons.
private const val TRANSPORT_BUTTON_COUNT = 5
private const val BAR_LAST_INDEX = TRANSPORT_BUTTON_COUNT

// Settle window before the bar is disarmed after focus leaves it, so a focus move between two
// of its own elements that briefly reports no focus does not drop the bar out of the focus tree.
private const val DISARM_SETTLE_MS = 120L

private const val HINT_PREFS = "tv_hints"
private const val HINT_KEY_OPEN_PLAYER = "mini_player_open_hint_shown"
private const val HINT_VISIBLE_MS = 12_000L

private val BAR_ART = 44.dp
// The gap between the one-time hint and the bar's top edge.
private val HINT_LIFT = (-8).dp

/**
 * The mini player: a bar across the bottom of the browsing screens, always showing what the
 * selected player plays, with play/pause, next, volume and the player switcher. OK on the
 * artwork opens the full player.
 *
 * The bar is a separate component that must not interact with the content above it. It is
 * focusable only while "armed", which only a deliberate entry does: DOWN with nothing below in
 * the content, or holding BACK (both arrive through [entrySignal]). It is disarmed the moment
 * focus leaves it, so a click or a reload in the content can never drop the cursor onto it.
 * UP or BACK return the cursor to the card it came from.
 */
@Composable
fun TvMiniPlayer(
    onOpenPlayer: (String) -> Unit,
    modifier: Modifier = Modifier,
    entrySignal: kotlinx.coroutines.flow.SharedFlow<Unit>? = null,
    entryFocus: FocusRequester? = null,
    onExitToContent: (() -> Unit)? = null,
    onActiveChange: ((Boolean) -> Unit)? = null,
    viewModel: TvMiniPlayerViewModel = hiltViewModel()
) {
    val players by viewModel.players.collectAsStateWithLifecycle()
    val selected by viewModel.selectedPlayer.collectAsStateWithLifecycle()
    val localPlayerId by viewModel.localPlayerId.collectAsStateWithLifecycle()
    if (players.isEmpty()) return

    var showPicker by remember { mutableStateOf(false) }
    var hasFocusInside by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    val internalMediaFocus = remember { FocusRequester() }
    val mediaFocus = entryFocus ?: internalMediaFocus
    // Every horizontal move along the bar is an explicit requestFocus on these, in a fixed order,
    // rather than Compose's spatial search, so LEFT and RIGHT always step to the neighbour.
    val buttonFocus = remember { List(TRANSPORT_BUTTON_COUNT) { FocusRequester() } }
    var focusedIndex by remember { mutableIntStateOf(0) }
    fun barTarget(index: Int): FocusRequester = if (index == 0) mediaFocus else buttonFocus[index - 1]

    // Arming is a state write that makes the bar focusable, and canFocus takes effect on the
    // next composition; requesting focus from this effect, which runs after it, lands at once.
    LaunchedEffect(armed) {
        if (!armed) return@LaunchedEffect
        repeat(FOCUS_ATTACH_TRIES) {
            runCatching { mediaFocus.requestFocus() }
            if (hasFocusInside) return@LaunchedEffect
            delay(FOCUS_ATTACH_RETRY_MS)
        }
        // Focus never arrived: an armed bar nobody is on would be a target of the content's
        // focus search, the very thing arming exists to prevent.
        armed = false
    }

    DisposableEffect(Unit) { onDispose { onActiveChange?.invoke(false) } }

    // Once per install, the first time the bar is entered, point at OK-to-open.
    val context = androidx.compose.ui.platform.LocalContext.current
    var showOpenHint by remember { mutableStateOf(false) }
    if (entrySignal != null) {
        LaunchedEffect(entrySignal) {
            entrySignal.collect {
                val prefs = context.getSharedPreferences(HINT_PREFS, android.content.Context.MODE_PRIVATE)
                if (!prefs.getBoolean(HINT_KEY_OPEN_PLAYER, false)) {
                    prefs.edit().putBoolean(HINT_KEY_OPEN_PLAYER, true).apply()
                    showOpenHint = true
                }
                focusedIndex = 0
                // A bar still armed from an earlier entry does not re-run the armed effect, so
                // focus it directly; canFocus is already true then.
                if (armed) mediaFocus.requestFocusRetrying() else armed = true
            }
        }
    }
    LaunchedEffect(showOpenHint) {
        if (showOpenHint) {
            delay(HINT_VISIBLE_MS)
            showOpenHint = false
        }
    }

    val navScope = androidx.compose.runtime.rememberCoroutineScope()
    val disarmJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Leaving the bar: restore the content's saved focus when the host provides it (lands on the
    // exact card the user came from), else fall back to a spatial move.
    val exitToContent: () -> Unit = onExitToContent ?: {
        if (!focusManager.moveFocus(FocusDirection.Up)) {
            focusManager.clearFocus(force = true)
            focusManager.moveFocus(FocusDirection.Up)
        }
    }
    // A deliberate exit (UP or BACK) disarms as soon as focus is out, without the settle
    // window, so a quick DOWN right after cannot land on a still-armed button.
    val leaveBar: () -> Unit = {
        exitToContent()
        if (!hasFocusInside) armed = false
    }
    // BACK while the cursor is on the bar leaves the bar instead of reaching the screen's own
    // back handling (Home would ask to exit).
    androidx.activity.compose.BackHandler(enabled = hasFocusInside) { leaveBar() }
    Column(
        modifier = modifier
            .onFocusChanged {
                val has = it.hasFocus
                hasFocusInside = has
                // Told at once, not through an effect a frame later, so the screen's own BACK
                // handling is already out of the way when the first BACK arrives.
                onActiveChange?.invoke(has)
                if (has) {
                    disarmJob.value?.cancel()
                } else {
                    // Disarm only if focus is still outside after a short settle, i.e. the user
                    // really left for the content.
                    disarmJob.value?.cancel()
                    disarmJob.value = navScope.launch {
                        delay(DISARM_SETTLE_MS)
                        if (!hasFocusInside) armed = false
                    }
                }
            }
            // Every D-pad move inside the bar is handled here, at its root, before the focused
            // element gets the key:
            //   UP           -> back to the content
            //   LEFT / RIGHT -> step along the explicit order [media, ...buttons], stopping at
            //                   the ends (nothing else is beside the bar)
            //   DOWN         -> nothing below the bar; consumed so focus cannot wander
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        leaveBar()
                        true
                    }
                    Key.DirectionLeft -> {
                        if (focusedIndex > 0) runCatching { barTarget(focusedIndex - 1).requestFocus() }
                        true
                    }
                    Key.DirectionRight -> {
                        if (focusedIndex < BAR_LAST_INDEX) runCatching { barTarget(focusedIndex + 1).requestFocus() }
                        true
                    }
                    Key.DirectionDown -> true
                    else -> false
                }
            }
    ) {
        // A zero-height anchor on the bar's top edge: the hint overflows upward over the content
        // instead of taking room in the layout, so the screen above does not shift while it shows.
        if (showOpenHint && hasFocusInside) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.dp)
                    .wrapContentSize(Alignment.BottomStart, unbounded = true)
            ) {
                HintBalloon("Press OK to open the full player", Modifier.offset(y = HINT_LIFT))
            }
        }
        MiniPlayerBar(
            player = selected,
            mediaFocus = mediaFocus,
            canFocus = armed,
            buttonFocus = buttonFocus,
            onElementFocused = { focusedIndex = it },
            onOpen = {
                showOpenHint = false
                selected?.let { onOpenPlayer(it.playerId) }
            },
            onPlayPause = viewModel::playPause,
            onNext = viewModel::next,
            onVolumeDown = viewModel::volumeDown,
            onVolumeUp = viewModel::volumeUp,
            onSwitchPlayer = { showPicker = true }
        )
    }

    if (showPicker) {
        TvPlayerPickerDialog(
            players = players,
            selectedPlayerId = selected?.playerId,
            localPlayerId = localPlayerId,
            onSelect = { playerId ->
                viewModel.selectPlayer(playerId)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

/** The bar itself: the media surface on the left, the buttons on the right. */
@Composable
private fun MiniPlayerBar(
    player: Player?,
    mediaFocus: FocusRequester,
    canFocus: Boolean,
    buttonFocus: List<FocusRequester>,
    onElementFocused: (Int) -> Unit,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onSwitchPlayer: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // A lighter fill than the screen, so the bar reads as a bar over the content and
            // not as an empty band at the bottom.
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(6.dp)
    ) {
        // Pressed is pinned to the focused fill so OK never flashes the white inverseSurface
        // default; no magnify, so the focused surface stays inside the bar.
        Surface(
            onClick = onOpen,
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                // With a transparent container the default content colour would be black,
                // unreadable on the dark bar.
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            modifier = Modifier
                .weight(1f)
                .focusProperties { this.canFocus = canFocus }
                .focusRequester(mediaFocus)
                .pointerClick(pressScale = POINTER_PRESS_SCALE_SUBTLE, onClick = onOpen)
                .onFocusChanged { if (it.isFocused) onElementFocused(0) }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(6.dp)) {
                MiniArtwork(player, Modifier.size(BAR_ART))
                Spacer(Modifier.width(14.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (player?.state == PlaybackState.PLAYING) {
                            Icon(
                                Icons.Filled.GraphicEq,
                                contentDescription = "Playing",
                                modifier = Modifier.padding(end = 6.dp).size(14.dp)
                            )
                        }
                        Text(
                            player?.currentMedia?.title?.takeIf { it.isNotBlank() } ?: "Nothing playing",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val subtitle = listOfNotNull(
                        player?.currentMedia?.artist?.takeIf { it.isNotBlank() },
                        player?.displayName
                    ).joinToString(" \u00b7 ")
                    if (subtitle.isNotBlank()) {
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
        Spacer(Modifier.width(8.dp))
        val buttonModifier: (Int) -> Modifier = { index ->
            Modifier
                .focusProperties { this.canFocus = canFocus }
                .transportFocus(buttonFocus[index - 1], index, onElementFocused)
        }
        IconButton(onClick = onPlayPause, modifier = buttonModifier(1).pointerClick(onClick = onPlayPause)) {
            Icon(
                if (player?.state == PlaybackState.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (player?.state == PlaybackState.PLAYING) "Pause" else "Play"
            )
        }
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onNext, modifier = buttonModifier(2).pointerClick(onClick = onNext)) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next")
        }
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onVolumeDown, modifier = buttonModifier(3).pointerClick(onClick = onVolumeDown)) {
            Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "Volume down")
        }
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onVolumeUp, modifier = buttonModifier(4).pointerClick(onClick = onVolumeUp)) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Volume up")
        }
        Spacer(Modifier.width(6.dp))
        IconButton(onClick = onSwitchPlayer, modifier = buttonModifier(5).pointerClick(onClick = onSwitchPlayer)) {
            Icon(Icons.Filled.Speaker, contentDescription = "Switch player")
        }
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun HintBalloon(text: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp).size(16.dp)
            )
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MiniArtwork(player: Player?, modifier: Modifier) {
    val art = player?.currentMedia?.imageUrl
    if (art != null) {
        AsyncImage(
            model = art,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(12.dp))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null)
        }
    }
}

/** Player switcher: OK selects the player and closes; the current one is highlighted. */
@Composable
private fun TvPlayerPickerDialog(
    players: List<Player>,
    selectedPlayerId: String?,
    localPlayerId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val initialFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp).width(340.dp)) {
                Text("Players", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                val sortedPlayers = remember(players) { players.sortedBy { it.displayName.lowercase() } }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    items(sortedPlayers, key = { it.playerId }) { p ->
                        PlayerPickerCard(
                            player = p,
                            selected = p.playerId == selectedPlayerId,
                            local = p.playerId == localPlayerId,
                            modifier = if (p.playerId == (selectedPlayerId ?: sortedPlayers.first().playerId)) {
                                Modifier.focusRequester(initialFocus)
                            } else {
                                Modifier
                            },
                            onClick = { onSelect(p.playerId) }
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { initialFocus.requestFocus() } }
}

@Composable
private fun PlayerPickerCard(
    player: Player,
    selected: Boolean,
    local: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val subtitle = player.currentMedia?.title?.takeIf { it.isNotBlank() }
        ?: player.state.name.lowercase().replaceFirstChar { it.uppercase() }
    val selectedBorder = CardDefaults.border(
        border = Border(
            border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp)
        )
    )
    Card(
        onClick = onClick,
        border = if (selected) selectedBorder else CardDefaults.border(),
        // Color/border focus feedback only: the default zoom overflows the dialog insets.
        scale = CardDefaults.scale(focusedScale = 1f),
        modifier = modifier.fillMaxWidth().pointerClick(pressScale = POINTER_PRESS_SCALE_SUBTLE, onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (player.state == PlaybackState.PLAYING) {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = "Playing",
                            modifier = Modifier.padding(end = 6.dp).size(15.dp)
                        )
                    }
                    Text(
                        player.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (local) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFD32F2F))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        "Local Player",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
    }
}

/**
 * Attaches a transport button's focus requester and reports its island index on focus, so the
 * island root preview handler can step the explicit focus order. No key interception here.
 */
private fun Modifier.transportFocus(
    requester: FocusRequester,
    index: Int,
    onElementFocused: (Int) -> Unit
): Modifier = this
    .focusRequester(requester)
    .onFocusChanged { if (it.isFocused) onElementFocused(index) }

/** [FocusRequester.requestFocus] that tolerates the node attaching a few frames later. */
private suspend fun FocusRequester.requestFocusRetrying() {
    repeat(FOCUS_ATTACH_TRIES) {
        if (runCatching { requestFocus() }.isSuccess) return
        delay(FOCUS_ATTACH_RETRY_MS)
    }
}
