package net.asksakis.massdroidv2.ui.car

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import net.asksakis.massdroidv2.ui.screens.party.GUEST_ACCESS_OFF_NOTICE
import net.asksakis.massdroidv2.ui.screens.party.PartyDevice
import net.asksakis.massdroidv2.ui.screens.party.PartyJoinFooter
import net.asksakis.massdroidv2.ui.screens.party.PartyNoticeText
import net.asksakis.massdroidv2.ui.screens.party.PartyPlayerCard
import net.asksakis.massdroidv2.ui.screens.party.PartyUiState
import net.asksakis.massdroidv2.ui.screens.party.PartyViewModel
import net.asksakis.massdroidv2.ui.screens.party.QrCard
import net.asksakis.massdroidv2.ui.screens.party.notice
import net.asksakis.massdroidv2.ui.screens.party.rememberPartyViewModelStoreOwner
import net.asksakis.massdroidv2.ui.screens.party.subtitle

/**
 * The Guest access section: the join QR of Music Assistant's Party plugin, so passengers can scan
 * it and add songs. It is the phone's Party screen without the moving background, because the car
 * shows its settings only while parked and the AAOS settings guidance asks for a still page.
 *
 * The party is loaded only while the car is connected and the server has the plugin; otherwise
 * the section says why there is no code. The plugin list is not cleared on a sign-out or a
 * dropped connection, so [connected] is checked first.
 */
@Composable
internal fun CarGuestAccessPane(connecting: Boolean, connected: Boolean, partyAvailable: Boolean, viewportHeight: Dp) {
    Text("Guest access", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(HEADER_GAP))
    when {
        connecting -> CarGuestNotice(
            PartyNoticeText(
                title = "Connecting to your server...",
                text = "The party code appears once the car is connected.",
            )
        )
        !connected -> CarGuestNotice(
            PartyNoticeText(
                title = "Connect to your server first",
                text = "Guest access needs a connection to your Music Assistant server. Sign in under Server.",
            )
        )
        !partyAvailable -> CarGuestNotice(
            PartyNoticeText(
                title = "Party mode isn't available on this server",
                text = "Turn on the Party plugin in Music Assistant, under Settings, Plugins. Then come back here.",
            )
        )
        else -> CarPartyContent(viewportHeight)
    }
}

/**
 * The party itself. Its ViewModel lives only while this content is shown, as on the phone, so the
 * party is not read again on every reconnect after the section closes.
 */
@Composable
private fun CarPartyContent(viewportHeight: Dp) {
    // CarSignInActivity is the only host of this section.
    val activity = LocalActivity.current as ComponentActivity
    val viewModel: PartyViewModel = hiltViewModel(rememberPartyViewModelStoreOwner(activity))
    val state by viewModel.state.collectAsStateWithLifecycle()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.followTarget() }
    }

    when (val current = state) {
        PartyUiState.Loading -> CircularProgressIndicator(modifier = Modifier.padding(vertical = 24.dp))
        PartyUiState.GuestAccessOff -> CarGuestNotice(GUEST_ACCESS_OFF_NOTICE, viewModel::refresh)
        is PartyUiState.Failed -> CarGuestNotice(current.notice(), viewModel::refresh)
        is PartyUiState.Ready -> CarPartyReady(current, viewportHeight, viewModel::refresh)
    }
}

/**
 * The code beside what guests need to know when the section is wide enough, above it otherwise.
 * It is as large as the width allows and as the section's visible height leaves under the
 * section title, so a short display still shows the whole code without scrolling. Below
 * [MIN_QR] it stops shrinking, and on a display too short even for that the section scrolls.
 */
@Composable
private fun CarPartyReady(state: PartyUiState.Ready, viewportHeight: Dp, onRefresh: () -> Unit) {
    val heightLeft = viewportHeight - SECTION_PANE_VERTICAL_PADDING * 2 - READY_HEADER_HEIGHT
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val beside = maxWidth >= QR_BESIDE_MIN_WIDTH
        val qrSize = minOf(if (beside) (maxWidth - QR_GAP) / 2 else maxWidth, heightLeft).coerceIn(MIN_QR, MAX_QR)
        val qr: @Composable () -> Unit = {
            QrCard(url = state.link.url, title = state.title, isDark = CAR_IS_DARK, size = qrSize)
        }
        val details: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(state.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    state.subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PartyPlayerCard(state.target, CAR_IS_DARK, PartyDevice.Car)
                PartyJoinFooter(state.link, CAR_IS_DARK)
                OutlinedButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text("Refresh", fontSize = 18.sp)
                }
            }
        }
        if (beside) {
            Row(horizontalArrangement = Arrangement.spacedBy(QR_GAP), verticalAlignment = Alignment.Top) {
                qr()
                details()
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(QR_GAP)) {
                qr()
                details()
            }
        }
    }
}

/**
 * Why there is no code, after a line on what guest access is, with Refresh when a new read can
 * change the answer. The code itself goes without that line, to leave its height to the code.
 */
@Composable
private fun CarGuestNotice(notice: PartyNoticeText, onRefresh: (() -> Unit)? = null) {
    Text(
        "Party mode lets passengers scan a code with their own phones and add songs to the queue.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    Text(notice.title, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(
        notice.text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (onRefresh != null) {
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 56.dp)) {
            Text("Refresh", fontSize = 18.sp)
        }
    }
}

/** CarSignInActivity always draws in the dark theme, like the media center around it. */
private const val CAR_IS_DARK = true
private val HEADER_GAP: Dp = 24.dp

/** The section title's line height (headlineMedium) and the gap under it. */
private val READY_HEADER_HEIGHT: Dp = 36.dp + HEADER_GAP
private val QR_GAP: Dp = 32.dp
private val QR_BESIDE_MIN_WIDTH: Dp = 560.dp
private val MIN_QR: Dp = 200.dp
private val MAX_QR: Dp = 360.dp
