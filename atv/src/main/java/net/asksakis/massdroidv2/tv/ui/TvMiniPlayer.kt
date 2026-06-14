package net.asksakis.massdroidv2.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.scale
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

/** Idle time before an expanded mini player folds back into the corner pill. */
private const val AUTO_COLLAPSE_MS = 8_000L

private const val EXPAND_ANIM_MS = 500
private const val FOCUS_ATTACH_TRIES = 10
private const val FOCUS_ATTACH_RETRY_MS = 50L

// Explicit island focus order: index 0 is the media/pill, 1..5 the transport buttons.
private const val TRANSPORT_BUTTON_COUNT = 5
private const val ISLAND_LAST_INDEX = TRANSPORT_BUTTON_COUNT

// Settle window before the pill is disarmed after focus leaves it. An intra-island move across
// the transport row's AnimatedVisibility boundary briefly reports no-focus; this debounce lets
// that resolve so the gate does not flip the media surface non-focusable mid-navigation.
private const val DISARM_SETTLE_MS = 120L

private const val HINT_PREFS = "tv_hints"
private const val HINT_KEY_MINI_PLAYER = "mini_player_back_hint_shown"
private const val HINT_KEY_OPEN_PLAYER = "mini_player_open_hint_shown"
private const val HINT_VISIBLE_MS = 12_000L

private val PILL_ART = 44.dp
private val EXPANDED_ART = 64.dp

// Self-drawn focus visuals (see CollapsedPill): a subtle magnify + a rounded accent border,
// driven purely by focus. We do NOT use the tv-material3 Surface focus border/scale because
// its hidden focused<->pressed transitions flash the default white square on OK.
private const val PILL_FOCUS_SCALE = 1.05f

/**
 * Floating mini player, bottom-right: collapsed it is a small artwork pill (with a
 * playing glyph); OK expands it into track info + transport + the player-switch
 * dialog entry point; it folds back on its own after [AUTO_COLLAPSE_MS] of no
 * D-pad activity, returning focus to the pill so navigation never gets lost.
 */
@Composable
fun TvMiniPlayer(
    onOpenPlayer: (String) -> Unit,
    modifier: Modifier = Modifier,
    expandSignal: kotlinx.coroutines.flow.SharedFlow<Unit>? = null,
    entryFocus: FocusRequester? = null,
    onExitToContent: (() -> Unit)? = null,
    onActiveChange: ((Boolean) -> Unit)? = null,
    viewModel: TvMiniPlayerViewModel = hiltViewModel()
) {
    val players by viewModel.players.collectAsStateWithLifecycle()
    val selected by viewModel.selectedPlayer.collectAsStateWithLifecycle()
    val localPlayerId by viewModel.localPlayerId.collectAsStateWithLifecycle()
    if (players.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    // Bumped on every focus move/press inside the component; restarts the idle timer.
    var interactionTick by remember { mutableIntStateOf(0) }
    var hasFocusInside by remember { mutableStateOf(false) }
    // The pill is a SEPARATE component that must not interact with the main view: it is
    // focusable ONLY while "armed" (set on the deliberate hold-BACK entry), and disarmed the
    // moment focus leaves it. So content focus loss (a click/reload on a screen) can never
    // escape onto the pill: it is simply not a focus-search candidate unless the user
    // explicitly went there.
    var armed by remember { mutableStateOf(false) }
    // True only between a BACK-to-content press and focus actually leaving the pill, so the
    // auto-collapse refocus is suppressed and we do NOT disarm while still focused (disarming
    // a focused pill makes Compose auto-move focus to a default content item — the row start —
    // racing the exact-card restore).
    var exiting by remember { mutableStateOf(false) }
    val internalPillFocus = remember { FocusRequester() }
    // Focus requester for the pill; used only by the deliberate hold-BACK entry (the pill is
    // not reachable by navigating/scrolling the content).
    val pillFocus = entryFocus ?: internalPillFocus
    // Explicit, index-based island focus order: [0] = media/pill, [1..5] = the five transport
    // buttons. ALL horizontal D-pad navigation is driven by requestFocus on these (see the
    // Column onPreviewKeyEvent below), NOT by Compose's 2D spatial search: the transport row's
    // AnimatedVisibility is a focus boundary the spatial search cannot cross leftward, and
    // tv-material3 IconButton overrides focusProperties internally. requestFocus to a specific
    // node is the one mechanism that crosses that boundary reliably.
    val buttonFocus = remember { List(TRANSPORT_BUTTON_COUNT) { FocusRequester() } }
    var focusedIndex by remember { mutableIntStateOf(0) }
    fun islandTarget(index: Int): FocusRequester = if (index == 0) pillFocus else buttonFocus[index - 1]

    // Deliberate entry focuses the pill AFTER the arm has been applied. Arming is a state
    // write that makes the pill focusable, but canFocus only takes effect on the next
    // recomposition; requesting focus from this effect (which runs post-composition) lands
    // on the FIRST try, so the expanded player does not flash unfocused for a few frames
    // first (the old "arm + immediately request in a loop" showed an unfocused window).
    LaunchedEffect(armed) {
        if (!armed) return@LaunchedEffect
        repeat(FOCUS_ATTACH_TRIES) {
            runCatching { pillFocus.requestFocus() }
            if (hasFocusInside) return@LaunchedEffect
            delay(FOCUS_ATTACH_RETRY_MS)
        }
    }

    LaunchedEffect(expanded, hasFocusInside) { onActiveChange?.invoke(expanded || hasFocusInside) }
    DisposableEffect(Unit) { onDispose { onActiveChange?.invoke(false) } }

    // First-launch hint: once per install, a small balloon above the pill points at the
    // long-press-BACK shortcut; it goes away on its own or as soon as the player is used.
    val context = androidx.compose.ui.platform.LocalContext.current
    var showHint by remember { mutableStateOf(false) }
    // Follow-up hint, once per install: after the shortcut is first used, point at OK-to-open.
    var showOpenHint by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(HINT_PREFS, android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean(HINT_KEY_MINI_PLAYER, false)) {
            prefs.edit().putBoolean(HINT_KEY_MINI_PLAYER, true).apply()
            showHint = true
            delay(HINT_VISIBLE_MS)
            showHint = false
        }
    }

    if (expandSignal != null) {
        LaunchedEffect(expandSignal) {
            expandSignal.collect {
                interactionTick++
                val prefs = context.getSharedPreferences(HINT_PREFS, android.content.Context.MODE_PRIVATE)
                if (!prefs.getBoolean(HINT_KEY_OPEN_PLAYER, false)) {
                    prefs.edit().putBoolean(HINT_KEY_OPEN_PLAYER, true).apply()
                    showOpenHint = true
                }
                expanded = true
                focusedIndex = 0
                // Cold entry: arm so the pill becomes focusable, then LaunchedEffect(armed)
                // focuses it post-composition (no unfocused flash). If the pill is ALREADY armed
                // (still in the focus tree from a prior entry), the armed transition won't
                // re-fire that effect, so request focus directly here — canFocus is already true,
                // so there is no flash, and the shortcut reliably returns the cursor to the media.
                if (armed) pillFocus.requestFocusRetrying() else armed = true
            }
        }
    }
    LaunchedEffect(expanded, interactionTick, showPicker) {
        if (!expanded || showPicker) return@LaunchedEffect
        delay(AUTO_COLLAPSE_MS)
        expanded = false
    }
    // AUTO-collapse only: when the idle timer folds the expanded player while the cursor is
    // still deliberately on it (armed), a transport button may have been removed, so pull
    // focus back to the collapsed pill. Gated on `armed` so it does NOT fire on a BACK exit
    // (which disarms first) — otherwise it raced exitToContent and sometimes yanked focus
    // back instead of letting the content restore run.
    LaunchedEffect(expanded) {
        if (!expanded) {
            // The transport buttons are gone; reset to the media index so a stray LEFT/RIGHT
            // during the collapse animation can't act on a removed button requester.
            focusedIndex = 0
            if (hasFocusInside && armed && !exiting) pillFocus.requestFocusRetrying()
        }
    }

    val navScope = androidx.compose.runtime.rememberCoroutineScope()
    // Pending disarm job: debounces the canFocus gate so a transient intra-island focus blip
    // (crossing the transport row's AnimatedVisibility boundary) does not flip the pill
    // non-focusable mid-navigation.
    val disarmJob = remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // Leaving the island: restore the content's saved focus when the host provides it
    // (lands on the exact card the user came from), else fall back to a spatial move.
    val exitToContent: () -> Unit = onExitToContent ?: {
        if (!focusManager.moveFocus(FocusDirection.Up)) {
            focusManager.clearFocus(force = true)
            focusManager.moveFocus(FocusDirection.Up)
        }
    }
    // BACK while the cursor is on the mini player = leave the player (collapse + focus back to
    // the content) instead of bubbling to the screen's back handling (home would prompt to exit).
    androidx.activity.compose.BackHandler(enabled = expanded || hasFocusInside) {
        val wasInside = hasFocusInside
        // Mark a deliberate exit: suppresses the auto-collapse refocus. Keep `armed` true for
        // now — disarming a still-focused pill makes Compose auto-move focus to a default
        // content item (the row start) before the restore lands on the exact card. The disarm
        // happens reactively in onFocusChanged once focus actually leaves the pill.
        exiting = true
        expanded = false
        if (wasInside) exitToContent()
    }
    val touch = Modifier.onFocusChanged { if (it.isFocused) interactionTick++ }
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier
            .onFocusChanged {
                val has = it.hasFocus
                hasFocusInside = has
                if (has) {
                    // Focus is (back) inside the island: cancel any pending disarm. A move
                    // between the media surface and a transport button crosses the transport
                    // row's AnimatedVisibility boundary and momentarily reports no-focus; that
                    // must NOT disarm, or the focus-gated media becomes non-focusable and LEFT
                    // can no longer return to it.
                    disarmJob.value?.cancel()
                } else {
                    // Debounce: only disarm if focus is STILL outside after a short settle, i.e.
                    // the user really left for the content (not a transient transition blip), so
                    // the pill drops out of the focus tree and content can never land on it
                    // without a deliberate entry.
                    disarmJob.value?.cancel()
                    disarmJob.value = navScope.launch {
                        delay(DISARM_SETTLE_MS)
                        if (!hasFocusInside) {
                            armed = false
                            exiting = false
                        }
                    }
                }
            }
            // ALL island D-pad navigation is handled HERE, at the island root. This preview
            // handler runs before the focused child gets the key (preview dispatches top-down),
            // and explicit requestFocus is the only thing that reliably crosses the transport
            // row's AnimatedVisibility focus boundary, so we never rely on the 2D spatial search
            // for intra-island moves.
            //   UP             -> leave the island, back to the content
            //   LEFT at media  -> leave the island (media is the leftmost element)
            //   LEFT / RIGHT   -> step along the explicit focus order [media, ...transport]
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        exitToContent()
                        true
                    }
                    Key.DirectionLeft -> {
                        when {
                            // Step left among the island's controls.
                            focusedIndex > 0 -> runCatching { islandTarget(focusedIndex - 1).requestFocus() }
                            // Collapsed pill: it is the leftmost thing on screen and content sits
                            // to its left, so LEFT leaves the island for the content.
                            !expanded -> exitToContent()
                            // Expanded, on the media control (leftmost): stay put. The media is a
                            // control (OK opens the full player), so LEFT must not escape to the
                            // content mid-interaction — leaving the expanded player is BACK / UP.
                        }
                        true
                    }
                    Key.DirectionRight -> {
                        if (expanded && focusedIndex < ISLAND_LAST_INDEX) {
                            runCatching { islandTarget(focusedIndex + 1).requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
    ) {
        if (showHint && !expanded) {
            HintBalloon("Hold BACK to open the mini player")
            Spacer(Modifier.height(8.dp))
        }
        if (showOpenHint && expanded) {
            HintBalloon("Press OK to open the full player")
            Spacer(Modifier.height(8.dp))
        }
        // Single island: artwork + title are always present (the "pill"); the transport row
        // slides in/out with a genuine horizontal size animation. No content swap, so the
        // expand/collapse is smooth both ways and focus never leaves the island.
        MiniPlayerIsland(
            player = selected,
            expanded = expanded,
            pillFocus = pillFocus,
            pillCanFocus = armed,
            buttonFocus = buttonFocus,
            onElementFocused = { focusedIndex = it },
            touch = touch,
            onPrimaryClick = {
                if (expanded) {
                    showOpenHint = false
                    selected?.let { onOpenPlayer(it.playerId) }
                } else {
                    expanded = true
                }
            },
            onPlayPause = viewModel::playPause,
            onNext = viewModel::next,
            onVolumeDown = viewModel::volumeDown,
            onVolumeUp = viewModel::volumeUp,
            onSwitchPlayer = { showPicker = true }
        )
    }
    LaunchedEffect(expanded) { if (expanded) showHint = false }
    LaunchedEffect(showOpenHint) {
        if (showOpenHint) {
            delay(HINT_VISIBLE_MS)
            showOpenHint = false
        }
    }

    if (showPicker) {
        TvPlayerPickerDialog(
            players = players,
            selectedPlayerId = selected?.playerId,
            localPlayerId = localPlayerId,
            onSelect = { playerId ->
                viewModel.selectPlayer(playerId)
                showPicker = false
                interactionTick++
            },
            onDismiss = { showPicker = false }
        )
    }
}

/**
 * One island for both states. Artwork + title are always present (the "pill"); pressing OK
 * expands when collapsed and opens the full player when expanded. The transport row is the
 * only thing that appears/disappears, via a genuine horizontal size animation, so there is no
 * content swap: the expand/collapse is smooth both ways and focus stays on the (always present)
 * primary surface — it never falls back to the content underneath.
 */
@Composable
private fun MiniPlayerIsland(
    player: Player?,
    expanded: Boolean,
    pillFocus: FocusRequester,
    pillCanFocus: Boolean,
    buttonFocus: List<FocusRequester>,
    onElementFocused: (Int) -> Unit,
    touch: Modifier,
    onPrimaryClick: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onSwitchPlayer: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val artSize by animateDpAsState(
        targetValue = if (expanded) EXPANDED_ART else PILL_ART,
        animationSpec = tween(EXPAND_ANIM_MS, easing = FastOutSlowInEasing),
        label = "miniArt"
    )
    val anim = tween<Float>(EXPAND_ANIM_MS, easing = FastOutSlowInEasing)
    // Bar background only when expanded. Drawn on the Row (background draws but does NOT clip
    // its children), not via a wrapping Surface: a Surface would clip the focused pill's
    // magnify and leave a dark rounded frame around the grey selection when collapsed.
    //
    // The bar color is INSTANT, never crossfaded: a translucent bar (mid alpha-animation) lets
    // the bright content grid behind it bleed through — a visible white flash during the expand.
    // Driven off the (synchronous, per-frame) artwork-size animation so there is no 1-frame gap
    // a separate state/LaunchedEffect would introduce: opaque the moment we expand, and opaque
    // for the whole collapse until the artwork has shrunk all the way back to the pill size.
    val barColor = if (expanded || artSize > PILL_ART) {
        MaterialTheme.colorScheme.surface
    } else {
        Color.Transparent
    }
    val barPadding by animateDpAsState(
        targetValue = if (expanded) 6.dp else 0.dp,
        animationSpec = tween(EXPAND_ANIM_MS, easing = FastOutSlowInEasing),
        label = "miniBarPad"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(barColor, RoundedCornerShape(16.dp))
            .padding(barPadding)
    ) {
        // Primary target: artwork + title. Its own fill is the visible pill when collapsed
        // (surfaceVariant) and transparent when expanded (it blends into the bar). Focus cue is
        // a brighter fill + a slight magnify (collapsed only, so it can't overflow the bar);
        // pressed pinned to the same fill so OK never flashes the white inverseSurface default.
        Surface(
            onClick = onPrimaryClick,
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = if (expanded) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
                // Explicit unfocused content color: with a transparent container the default
                // is contentColorFor(Transparent) = black, which makes the title unreadable on
                // the dark bar while a transport button (not the pill) holds focus.
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            modifier = touch
                // Only focusable on a deliberate entry (see `armed`): keeps the pill out of
                // the content's focus search so a click/reload never lands on it.
                .focusProperties { canFocus = pillCanFocus }
                .focusRequester(pillFocus)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onElementFocused(0)
                }
                .scale(if (focused && !expanded) PILL_FOCUS_SCALE else 1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(8.dp)
            ) {
                MiniArtwork(player, Modifier.size(artSize))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.widthIn(max = 220.dp).padding(end = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (player?.state == PlaybackState.PLAYING) {
                            Icon(
                                Icons.Filled.GraphicEq,
                                contentDescription = "Playing",
                                modifier = Modifier.padding(end = 5.dp).size(13.dp)
                            )
                        }
                        Text(
                            player?.currentMedia?.title?.takeIf { it.isNotBlank() } ?: "Nothing playing",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val subtitle = listOfNotNull(
                        player?.currentMedia?.artist?.takeIf { it.isNotBlank() },
                        player?.displayName
                    ).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        // Transport controls: slide in/out with a genuine horizontal size animation,
        // clipped while animating so nothing pops. Only composed while expanded, so they
        // are focusable only then.
        AnimatedVisibility(
            visible = expanded,
            enter = expandHorizontally(animationSpec = tween(EXPAND_ANIM_MS, easing = FastOutSlowInEasing)) +
                fadeIn(anim),
            exit = shrinkHorizontally(animationSpec = tween(EXPAND_ANIM_MS, easing = FastOutSlowInEasing)) +
                fadeOut(anim)
        ) {
            // The transport buttons carry the [1..5] focus requesters; LEFT/RIGHT between them
            // and back to the media surface is handled entirely by the island root preview
            // handler via requestFocus, so no per-button key interception is needed here.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onPlayPause, modifier = touch.transportFocus(buttonFocus[0], 1, onElementFocused)) {
                    Icon(
                        if (player?.state == PlaybackState.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause"
                    )
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onNext, modifier = touch.transportFocus(buttonFocus[1], 2, onElementFocused)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onVolumeDown, modifier = touch.transportFocus(buttonFocus[2], 3, onElementFocused)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "Volume down")
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onVolumeUp, modifier = touch.transportFocus(buttonFocus[3], 4, onElementFocused)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Volume up")
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onSwitchPlayer, modifier = touch.transportFocus(buttonFocus[4], 5, onElementFocused)) {
                    Icon(Icons.Filled.Speaker, contentDescription = "Switch player")
                }
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

@Composable
private fun HintBalloon(text: String) {
    Surface(shape = RoundedCornerShape(10.dp)) {
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
        modifier = modifier.fillMaxWidth()
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
