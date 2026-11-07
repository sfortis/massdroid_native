package net.asksakis.massdroidv2.ui.screens.party

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import net.asksakis.massdroidv2.domain.party.PartyIdleTimer
import net.asksakis.massdroidv2.domain.party.PartyPlayerName
import net.asksakis.massdroidv2.domain.party.PartyTarget
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.QrCode
import net.asksakis.massdroidv2.ui.components.dropShadow
import net.asksakis.massdroidv2.ui.screens.nowplaying.components.KeepScreenOn

/**
 * The join QR of Music Assistant's Party plugin, for guests to scan and add songs from their
 * own phones, and which player their songs go to. Opened from the Now Playing options.
 *
 * The content sits over [PartyBackdrop], which reaches behind the app bar. The QR card stays
 * opaque, still and white, so the lights behind it never get in a scanner's way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartyScreen(
    onBack: () -> Unit,
    viewModel: PartyViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val artworkUrl by viewModel.artworkUrl.collectAsStateWithLifecycle()
    val available by viewModel.available.collectAsStateWithLifecycle()
    // From the app's own theme, which can differ from the system's.
    val isDark = MaterialTheme.colorScheme.surface.luminance() < DARK_SURFACE_LUMINANCE
    // A screen too short for the ball above the QR code, such as a phone in landscape, leaves
    // the ball out and starts the content right under the app bar.
    val roomForBall = LocalConfiguration.current.screenHeightDp >= MIN_HEIGHT_FOR_BALL_DP
    val idleTimer = remember { PartyIdleTimer(now = SystemClock::uptimeMillis) }
    val close by rememberUpdatedState(onBack)

    // The plugin went away, or the app reached a server without it: the link no longer works.
    LaunchedEffect(available) {
        if (!available) onBack()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.followTarget() }
    }
    // Closes the screen after a minute without a touch, the same way Back does, so a QR left
    // standing does not keep the display on and the lights running. Each resume starts the
    // minute again.
    LaunchedEffect(lifecycle, idleTimer) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            idleTimer.awaitIdle()
            close()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // Sees every touch before the content does, without taking it.
            .pointerInput(idleTimer) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        idleTimer.touch()
                    }
                }
            }
    ) {
        PartyBackdrop(artworkUrl = artworkUrl, isDark = isDark, showBall = roomForBall)
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Party") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent
                    ),
                    navigationIcon = {
                        MdIconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        MdIconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (val current = state) {
                    PartyUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    PartyUiState.GuestAccessOff -> PartyNotice(
                        title = "Guest access is off",
                        text = "Turn it on in Music Assistant, under Settings, Plugins, Party. Then come back here.",
                        top = topClearance(roomForBall),
                        onRefresh = viewModel::refresh
                    )
                    is PartyUiState.Failed -> PartyNotice(
                        title = "Party unavailable",
                        text = current.message,
                        top = topClearance(roomForBall),
                        onRefresh = viewModel::refresh
                    )
                    is PartyUiState.Ready -> {
                        // Guests scan from across the room, so the screen must not go dark under them.
                        KeepScreenOn()
                        PartyReady(current, isDark, topClearance(roomForBall))
                    }
                }
            }
        }
    }
}

@Composable
private fun PartyReady(state: PartyUiState.Ready, isDark: Boolean, top: Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = top, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        QrCard(url = state.link.url, title = state.title, isDark = isDark)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = state.qrText ?: "Scan to add songs",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        PartyPlayerCard(state.target, isDark)
        if (state.link.homeNetworkOnly) {
            PartyCard(isDark) {
                Text(
                    text = "This link works only on your home network. Set an External URL or Remote " +
                        "Access in Music Assistant so guests on mobile data can join.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Text(
            text = "Guests open the link on their own phone. No app needed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QrCard(url: String, title: String, isDark: Boolean) {
    val shape = MaterialTheme.shapes.extraLarge
    // Opaque, still and white in both themes, because a scanner needs the contrast. The
    // shadow is drawn, so the card itself never needs an alpha below one.
    Surface(
        color = Color.White,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = QR_CARD_MAX_WIDTH)
            .aspectRatio(1f)
            .dropShadow(
                shape = shape,
                color = Color.Black.copy(alpha = if (isDark) 0.55f else 0.2f),
                blurRadius = if (isDark) 48.dp else 40.dp,
                offsetY = 16.dp
            )
    ) {
        QrCode(content = url, description = "QR code to join $title", modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun PartyPlayerCard(target: PartyTarget, isDark: Boolean) {
    val name = when (target) {
        PartyTarget.NoPlayer -> "None right now"
        PartyTarget.GuestSession -> "Each guest's own phone"
        PartyTarget.GuestSessionUnavailable -> "Not available"
        is PartyTarget.SelectedPlayer -> target.name.label()
        is PartyTarget.OtherPlayer -> target.name.label()
    }
    val onPhone = target == PartyTarget.GuestSession || target == PartyTarget.GuestSessionUnavailable ||
        (target as? PartyTarget.SelectedPlayer)?.name == PartyPlayerName.ThisPhone ||
        (target as? PartyTarget.OtherPlayer)?.name == PartyPlayerName.ThisPhone
    val warning = when (target) {
        is PartyTarget.OtherPlayer -> "This is not the player you selected. Guest songs will play on " +
            "${target.name.inSentence()}."
        PartyTarget.NoPlayer -> "Music Assistant sends guest songs to the player that is playing. " +
            "Start playing, then refresh."
        PartyTarget.GuestSessionUnavailable -> "Music Assistant could not start the silent disco player. " +
            "Check the Party settings in Music Assistant, then refresh."
        else -> null
    }
    PartyCard(isDark) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.12f else 0.08f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (onPhone) Icons.Default.PhoneAndroid else Icons.Default.Speaker,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Party Player",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(text = name, style = MaterialTheme.typography.titleMedium)
            }
        }
        warning?.let {
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun PartyPlayerName.label(): String = when (this) {
    PartyPlayerName.ThisPhone -> "This phone"
    is PartyPlayerName.Named -> name
    PartyPlayerName.Unknown -> "Another player"
}

private fun PartyPlayerName.inSentence(): String = when (this) {
    PartyPlayerName.ThisPhone -> "this phone"
    is PartyPlayerName.Named -> name
    PartyPlayerName.Unknown -> "another player"
}

/** A translucent panel over the lights, so its text stays readable. */
@Composable
private fun PartyCard(isDark: Boolean, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.6f else 0.75f),
                MaterialTheme.shapes.medium
            )
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun PartyNotice(title: String, text: String, top: Dp, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 32.dp, end = 32.dp, top = top, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Celebration, contentDescription = null, modifier = Modifier.size(36.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        FilledTonalButton(onClick = onRefresh) { Text("Refresh") }
    }
}

/** Room under the app bar for the mirror ball the background hangs there, when it shows. */
private fun topClearance(roomForBall: Boolean): Dp = if (roomForBall) BALL_CLEARANCE else COMPACT_TOP

private val BALL_CLEARANCE = 184.dp
private val COMPACT_TOP = 16.dp
private val QR_CARD_MAX_WIDTH = 260.dp

/**
 * The shortest screen that fits the ball and the QR code below it: the status bar, the app
 * bar, 184 dp for the ball and the 260 dp card, with a little room left.
 */
private const val MIN_HEIGHT_FOR_BALL_DP = 560
private const val DARK_SURFACE_LUMINANCE = 0.5f
