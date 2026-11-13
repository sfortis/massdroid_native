package net.asksakis.massdroidv2.ui.car

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

/** The Party row of the car settings screen, shown while the server has the Party plugin. */
@Composable
internal fun CarPartyRow(onOpen: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Party", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Passengers scan a code with their own phones and add songs to the queue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Default.Celebration, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Show the party code", fontSize = 18.sp)
        }
    }
}

/**
 * The join QR of Music Assistant's Party plugin, over the car settings screen. It is the phone's
 * Party screen without the moving background: the car shows its settings only while parked, and
 * the AAOS settings guidance asks for a still page. Its ViewModel lives only while the page is
 * open, as on the phone, so the party is not read again on every reconnect after it closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarPartyPage(onBack: () -> Unit) {
    // CarSignInActivity is the only host of this page.
    val activity = LocalActivity.current as ComponentActivity
    val viewModel: PartyViewModel = hiltViewModel(rememberPartyViewModelStoreOwner(activity))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val available by viewModel.available.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)
    // The plugin went away, or the car reached a server without it: the link no longer works.
    LaunchedEffect(available) {
        if (!available) onBack()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.followTarget() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Party") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { innerPadding ->
        Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val current = state) {
                PartyUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                PartyUiState.GuestAccessOff -> CarPartyNotice(GUEST_ACCESS_OFF_NOTICE, viewModel::refresh)
                is PartyUiState.Failed -> CarPartyNotice(current.notice(), viewModel::refresh)
                is PartyUiState.Ready -> CarPartyReady(current)
            }
        }
    }
}

/**
 * The code as large as the screen allows, beside what guests need to know on a wide display and
 * above it on a tall one.
 */
@Composable
private fun CarPartyReady(state: PartyUiState.Ready) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        val wide = maxWidth > maxHeight
        val qrSize = if (wide) minOf(maxHeight, maxWidth / 2) else minOf(maxWidth, maxHeight / 2)
        val qr: @Composable () -> Unit = {
            QrCard(
                url = state.link.url,
                title = state.title,
                isDark = CAR_IS_DARK,
                size = qrSize.coerceIn(MIN_QR, MAX_QR)
            )
        }
        val details: @Composable () -> Unit = {
            Column(
                modifier = Modifier.widthIn(max = DETAILS_MAX_WIDTH).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(state.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    state.subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PartyPlayerCard(state.target, CAR_IS_DARK, PartyDevice.Car)
                PartyJoinFooter(state.link, CAR_IS_DARK)
            }
        }
        if (wide) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                qr()
                details()
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                qr()
                details()
            }
        }
    }
}

@Composable
private fun CarPartyNotice(notice: PartyNoticeText, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = DETAILS_MAX_WIDTH)) {
            Text(notice.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                notice.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRefresh, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("Refresh", fontSize = 18.sp)
            }
        }
    }
}

/** CarSignInActivity always draws in the dark theme, like the media center around it. */
private const val CAR_IS_DARK = true
private val MIN_QR: Dp = 200.dp
private val MAX_QR: Dp = 420.dp
private val DETAILS_MAX_WIDTH: Dp = 520.dp
