package net.asksakis.massdroidv2.ui.screens.party

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import net.asksakis.massdroidv2.domain.model.PartyJoinLink
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.party.PartyFit
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
    // The room the content leaves for the mirror ball above it, set by the content's fit.
    val ballRoom = remember { mutableStateOf<Dp?>(PartyFit.BALL_TOP_DP.dp) }
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
        PartyBackdrop(artworkUrl = artworkUrl, isDark = isDark, ballRoom = ballRoom)
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
                        notice = GUEST_ACCESS_OFF_NOTICE,
                        isDark = isDark,
                        ballRoom = ballRoom,
                        onRefresh = viewModel::refresh
                    )
                    is PartyUiState.Failed -> PartyNotice(
                        notice = current.notice(),
                        isDark = isDark,
                        ballRoom = ballRoom,
                        onRefresh = viewModel::refresh
                    )
                    is PartyUiState.Ready -> {
                        // Guests scan from across the room, so the screen must not go dark under them.
                        KeepScreenOn()
                        PartyReady(current, isDark, ballRoom)
                    }
                }
            }
        }
    }
}

/**
 * The QR code with what guests need to know under it, fitted to the height there is: the code
 * shrinks first, then the room for the ball (see [PartyFit]), and spare height widens the gaps
 * under the code. The content under the code is measured once it is laid out, so the fit
 * follows the warning or the hint appearing. Only a very small screen or a large font still
 * scrolls.
 */
@Composable
private fun PartyReady(state: PartyUiState.Ready, isDark: Boolean, ballRoom: MutableState<Dp?>) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Seeded with a typical height, so the first frame is close to the measured fit.
        var below by remember { mutableStateOf(ESTIMATED_BELOW) }
        val fit = PartyFit.withQr(
            availableDp = maxHeight.value,
            belowDp = (below + QR_GAP + BOTTOM_PADDING).value,
            widthDp = (maxWidth - SIDE_PADDING * 2).value
        )
        SideEffect { ballRoom.value = if (fit.showBall) fit.topDp.dp else null }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = SIDE_PADDING, end = SIDE_PADDING, top = fit.topDp.dp, bottom = BOTTOM_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val extraGap = fit.gapDp.dp
            QrCard(url = state.link.url, title = state.title, isDark = isDark, size = fit.qrDp.dp)
            Spacer(Modifier.height(QR_GAP + extraGap))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Measured without the extra gaps inside it, so the fit never feeds itself.
                    .onSizeChanged { below = with(density) { it.height.toDp() } - extraGap * INNER_GAPS },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CONTENT_GAP + extraGap)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = state.subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                PartyPlayerCard(state.target, isDark)
                PartyJoinFooter(state.link, isDark)
            }
        }
    }
}

/**
 * How guests join. The hint replaces the footer, because a guest on mobile data cannot open the
 * link at all, and the two together do not fit a small phone.
 */
@Composable
internal fun PartyJoinFooter(link: PartyJoinLink, isDark: Boolean) {
    if (link.homeNetworkOnly) {
        PartyCard(isDark) {
            Text(
                text = "This link works only on your home network. Set an External URL or " +
                    "Remote Access in Music Assistant so guests on mobile data can join.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    } else {
        Text(
            text = "Guests open the link on their own phone. No app needed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun QrCard(url: String, title: String, isDark: Boolean, size: Dp) {
    val shape = MaterialTheme.shapes.extraLarge
    // Opaque, still and white in both themes, because a scanner needs the contrast. The
    // shadow is drawn, so the card itself never needs an alpha below one.
    Surface(
        color = Color.White,
        shape = shape,
        modifier = Modifier
            .size(size)
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

/**
 * The player guest songs go to. While nothing plays there is no player worth naming, so the
 * card says so instead, and the name comes back once the server names a player that plays.
 */
@Composable
internal fun PartyPlayerCard(target: PartyTarget, isDark: Boolean, device: PartyDevice = PartyDevice.Phone) {
    if (target == PartyTarget.Idle) {
        PartyCard(isDark) {
            PartyCardRow(icon = Icons.Default.Speaker) {
                Text(
                    text = "Nothing is playing yet. Start music on the speaker you want, and guest songs " +
                        "will play there.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }
    val name = when (target) {
        PartyTarget.GuestSession -> "Each guest's own phone"
        PartyTarget.GuestSessionUnavailable -> "Not available"
        is PartyTarget.SelectedPlayer -> target.name.label(device)
        is PartyTarget.OtherPlayer -> target.name.label(device)
        PartyTarget.Idle -> return
    }
    val onGuestPhones = target == PartyTarget.GuestSession || target == PartyTarget.GuestSessionUnavailable
    val onThisDevice = (target as? PartyTarget.SelectedPlayer)?.name == PartyPlayerName.ThisPhone ||
        (target as? PartyTarget.OtherPlayer)?.name == PartyPlayerName.ThisPhone
    val icon = when {
        onGuestPhones -> Icons.Default.PhoneAndroid
        onThisDevice -> device.icon
        else -> Icons.Default.Speaker
    }
    val warning = when (target) {
        is PartyTarget.OtherPlayer -> "This is not the player you selected. Guest songs will play on " +
            "${target.name.inSentence(device)}." +
            if (target.state == PlaybackState.IDLE) " The first guest song starts it." else ""
        PartyTarget.GuestSessionUnavailable -> "Music Assistant could not start the silent disco player. " +
            "Check the Party settings in Music Assistant, then refresh."
        else -> null
    }
    PartyCard(isDark) {
        PartyCardRow(icon = icon) {
            Text(
                text = "Party Player",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = name, style = MaterialTheme.typography.titleMedium)
        }
        warning?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PartyCardRow(icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Column(content = content)
    }
}

/**
 * The device the app runs on, as the player card names it when that device's own player is the
 * party player ([PartyPlayerName.ThisPhone]).
 */
internal enum class PartyDevice(val label: String, val inSentence: String, val icon: ImageVector) {
    Phone("This phone", "this phone", Icons.Default.PhoneAndroid),
    Car("This car", "this car", Icons.Default.DirectionsCar)
}

private fun PartyPlayerName.label(device: PartyDevice): String = when (this) {
    PartyPlayerName.ThisPhone -> device.label
    is PartyPlayerName.Named -> name
}

private fun PartyPlayerName.inSentence(device: PartyDevice): String = when (this) {
    PartyPlayerName.ThisPhone -> device.inSentence
    is PartyPlayerName.Named -> name
}

/** A translucent panel over the lights, so its text stays readable. */
@Composable
private fun PartyCard(isDark: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.6f else 0.75f),
                MaterialTheme.shapes.medium
            )
            .padding(12.dp)
    ) {
        content()
    }
}

/** The line under the party's name: the plugin's own QR text, or a plain default. */
internal val PartyUiState.Ready.subtitle: String get() = qrText ?: "Scan to add songs"

/** What a notice in place of the QR code says. */
internal data class PartyNoticeText(val title: String, val text: String)

internal val GUEST_ACCESS_OFF_NOTICE = PartyNoticeText(
    title = "Guest access is off",
    text = "Turn it on in Music Assistant, under Settings, Plugins, Party. Then come back here."
)

internal fun PartyUiState.Failed.notice() = PartyNoticeText(title = "Party unavailable", text = message)

/** A notice in place of the QR code, in one compact card under the ball when it fits. */
@Composable
private fun PartyNotice(
    notice: PartyNoticeText,
    isDark: Boolean,
    ballRoom: MutableState<Dp?>,
    onRefresh: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        var content by remember { mutableStateOf(ESTIMATED_NOTICE) }
        val fit = PartyFit.withoutQr(availableDp = maxHeight.value, contentDp = (content + BOTTOM_PADDING).value)
        SideEffect { ballRoom.value = if (fit.showBall) fit.topDp.dp else null }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = SIDE_PADDING, end = SIDE_PADDING, top = fit.topDp.dp, bottom = BOTTOM_PADDING)
        ) {
            Box(modifier = Modifier.onSizeChanged { content = with(density) { it.height.toDp() } }) {
                PartyCard(isDark) {
                    PartyCardRow(icon = Icons.Default.Celebration) {
                        Text(text = notice.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = notice.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(onClick = onRefresh, modifier = Modifier.align(Alignment.End)) {
                        Text("Refresh")
                    }
                }
            }
        }
    }
}

private val SIDE_PADDING = 24.dp
private val BOTTOM_PADDING = 16.dp
private val QR_GAP = 16.dp
private val CONTENT_GAP = 12.dp

/** The gaps between the title, the player card and the footer, which also share spare height. */
private const val INNER_GAPS = 2

/** The title, the player card and the footer under the QR code, with their gaps. */
private val ESTIMATED_BELOW = 170.dp

/** A notice card with its title, two lines of text and the Refresh button. */
private val ESTIMATED_NOTICE = 150.dp
private const val DARK_SURFACE_LUMINANCE = 0.5f
