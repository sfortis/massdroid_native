package net.asksakis.massdroidv2.ui.car

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import net.asksakis.massdroidv2.BuildConfig
import net.asksakis.massdroidv2.data.signin.SavedSignIn
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.OAUTH_CALLBACK_SCHEME
import net.asksakis.massdroidv2.domain.AppLinks
import net.asksakis.massdroidv2.domain.connection.ServerCheck
import net.asksakis.massdroidv2.domain.connection.displayAddressOf
import net.asksakis.massdroidv2.domain.connection.sameServer
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import net.asksakis.massdroidv2.service.carMediaCenterIntent
import net.asksakis.massdroidv2.ui.components.ChosenServerCard
import net.asksakis.massdroidv2.ui.components.HomeAssistantSignIn
import net.asksakis.massdroidv2.ui.components.OtherServerSheet
import net.asksakis.massdroidv2.ui.components.ServerList
import net.asksakis.massdroidv2.ui.components.chooseClientCertificate
import net.asksakis.massdroidv2.ui.shared.HomeAssistantSignInPage
import net.asksakis.massdroidv2.ui.shared.isWebViewAvailable
import net.asksakis.massdroidv2.ui.theme.MassDroidTheme
import kotlin.math.roundToInt

/**
 * Car (AAOS) sign-in / server-config + MassDroid-player audio settings screen.
 * Reached from the media center's settings (gear, ACTION_APPLICATION_PREFERENCES)
 * and from the signed-out SessionError resolution intent. NOT a launcher activity,
 * so the car never exposes the full phone UI. It is parked-only: the automotive
 * manifest does not declare it distractionOptimized, so the system blocks it while
 * driving, as Google's AAOS media guidance requires for sign-in and settings.
 */
@AndroidEntryPoint
class CarSignInActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Force dark: the AAOS media center is always dark, but the car system can
            // report day mode (isSystemInDarkTheme() = false), which would render this
            // parked screen white and jarring next to the dark media UI (and at night).
            MassDroidTheme(darkTheme = true) {
                CarSignInScreen(onClose = ::closeToMediaCenter)
            }
        }
    }

    /**
     * Closes the screen into the media center showing MassDroid. The screen opens from the media
     * center's gear and from the signed-out session error, and an older recents entry can still
     * bring it up on its own; a plain finish() then left the user on the home screen instead of
     * the media center. Without a media center to open, the screen only closes.
     */
    private fun closeToMediaCenter() {
        try {
            startActivity(carMediaCenterIntent(this))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No media center to open: ${e.message}")
        }
        finish()
    }

    private companion object {
        const val TAG = "CarSignIn"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarSignInScreen(
    onClose: () -> Unit,
    viewModel: CarSignInViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val certAlias by viewModel.certAlias.collectAsStateWithLifecycle()
    val audioFormat by viewModel.audioFormat.collectAsStateWithLifecycle()
    val compressorLevel by viewModel.compressorLevel.collectAsStateWithLifecycle()
    val dither by viewModel.dither.collectAsStateWithLifecycle()
    val crossfade by viewModel.crossfade.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val picker = viewModel.serverPicker
    val rows by picker.rows.collectAsStateWithLifecycle()
    val searching by picker.searching.collectAsStateWithLifecycle()
    val chosen by picker.chosen.collectAsStateWithLifecycle()
    val typedCheck by picker.typedCheck.collectAsStateWithLifecycle()
    val attemptedUrl by viewModel.attemptedUrl.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val oauthInProgress by viewModel.oauthInProgress.collectAsStateWithLifecycle()
    val homeAssistantPage by viewModel.homeAssistantPage.collectAsStateWithLifecycle()
    val partyAvailable by viewModel.partyAvailable.collectAsStateWithLifecycle()

    // What was typed for the chosen server; null shows what is saved for that server. Keyed by
    // the server, so what was typed for one never reaches the next. The password is kept out of
    // saved instance state, which the system may write to disk.
    var typedUsername by rememberSaveable(chosen?.url) { mutableStateOf<String?>(null) }
    var typedPassword by remember(chosen?.url) { mutableStateOf<String?>(null) }
    var choosingOther by rememberSaveable { mutableStateOf(false) }
    var partyOpen by rememberSaveable { mutableStateOf(false) }
    // The server this screen last sent a sign-in to; only its success closes the screen, so
    // opening the gear while connected, to change server or sign out, does not close it.
    var sentUrl by rememberSaveable { mutableStateOf<String?>(null) }
    // Leaving the chosen server abandons its sign-in, which then can no longer close the screen.
    val leaveServer: () -> Unit = {
        viewModel.cancelAttempt()
        sentUrl = null
    }

    // BACK walks one step back, as on the phone and the TV: from Other server or from a chosen
    // server to the server list. From the list it closes the screen the way the arrow in the top
    // bar does, into the media center.
    BackHandler(enabled = !choosingOther && chosen == null, onBack = onClose)
    BackHandler(enabled = choosingOther || chosen != null) {
        if (choosingOther) {
            picker.clearTyped()
            choosingOther = false
        } else {
            leaveServer()
            picker.changeServer()
        }
    }

    // Reload any saved client certificate so a re-opened screen keeps mTLS active.
    LaunchedEffect(Unit) { viewModel.loadSavedCertificate(context) }

    // Closes on a sign-in from this screen that saved its server. Connected alone is not enough:
    // a failed switch connects to the previous server again. A sign-in that lands while the Party
    // page is open is taken without closing, so the page the user went on to stays up.
    val succeeded by viewModel.succeeded.collectAsStateWithLifecycle()
    LaunchedEffect(succeeded, sentUrl, partyOpen) {
        val done = succeeded ?: return@LaunchedEffect
        val sent = sentUrl ?: return@LaunchedEffect
        if (sameServer(done, sent)) {
            viewModel.consumeSuccess()
            if (!partyOpen) onClose()
        }
    }

    // Held above the pages below, so coming back from one keeps the place in the settings.
    val scrollState = rememberScrollState()

    // The Home Assistant page covers the screen while it is open; BACK and Cancel return here.
    homeAssistantPage?.let { url ->
        CarHomeAssistantPage(url, viewModel)
        return
    }
    // The Party page covers the screen too; its BACK and its arrow return here.
    if (partyOpen) {
        CarPartyPage(onBack = { partyOpen = false })
        return
    }

    val connecting = connection is ConnectionState.Connecting
    val connected = connection is ConnectionState.Connected
    // Only the error of a sign-in sent to the chosen server, not one left from another server.
    val connectionError = (connection as? ConnectionState.Error)?.takeIf { attemptedUrl == chosen?.url }
    val statusMessage = error
        ?: connectionError?.message
        ?: when {
            connecting -> "Connecting..."
            connected -> "Connected to ${displayAddressOf(serverUrl)}"
            else -> null
        }

    if (choosingOther) {
        OtherServerSheet(
            check = typedCheck,
            onCheck = picker::checkTyped,
            onUse = {
                if (picker.useTyped()) {
                    viewModel.clearError()
                    choosingOther = false
                }
            },
            onDismiss = {
                picker.clearTyped()
                choosingOther = false
            }
        )
    }

    Scaffold(
        topBar = {
            // Explicit back affordance: the AAOS media center has no guaranteed back
            // button on a parked settings activity, so the gear/SessionError entry would
            // otherwise be a one-way trip. The arrow closes into the media center.
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Surface(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    Text("Music Assistant", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sign in to your Music Assistant server.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))

                    val server = chosen
                    if (server == null) {
                        // Typing in a car is slow, so the servers found come first and an
                        // address of one's own is the last row.
                        ServerList(
                            rows = rows,
                            searching = searching,
                            onPick = { row ->
                                leaveServer()
                                picker.choose(row.url, row.name)
                            },
                            onSearchAgain = picker::searchAgain,
                            onForget = viewModel::forget,
                            largeTargets = true,
                            onOther = { choosingOther = true }
                        )
                    } else {
                        ChosenServerCard(
                            server = server,
                            onChange = {
                                leaveServer()
                                picker.changeServer()
                            },
                            onCheckAgain = picker::checkAgain
                        )
                        Spacer(Modifier.height(16.dp))
                        val saved by remember(server) { viewModel.savedSignIn(server) }
                            .collectAsStateWithLifecycle(SavedSignIn.NONE)
                        val username = typedUsername ?: saved.username
                        val password = typedPassword ?: saved.password
                        saved.continueAs?.let { name ->
                            Button(
                                onClick = {
                                    viewModel.continueAs(server, saved)
                                    sentUrl = server.url
                                },
                                enabled = !connecting,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            ) {
                                Text("Continue as $name", fontSize = 18.sp)
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Or sign in with another account:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        // The providers belong to the server checked last, which is this one
                        // once it answered.
                        val webView = remember { isWebViewAvailable() }
                        if (webView && server.check is ServerCheck.Found && providers.any { it.isHomeAssistant }) {
                            HomeAssistantSignIn(inProgress = oauthInProgress) {
                                viewModel.openHomeAssistantSignIn(server)
                                sentUrl = server.url
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        OutlinedTextField(
                            value = username,
                            onValueChange = { typedUsername = it },
                            label = { Text("Username") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { typedPassword = it },
                            label = { Text("Password") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                // Only a sign-in that was actually sent may close the screen on
                                // Connected; a rejected input while already connected keeps it open.
                                if (viewModel.login(server, username, password)) {
                                    sentUrl = server.url
                                }
                            },
                            enabled = !connecting,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            if (connecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text(if (connected) "Reconnect" else "Sign in", fontSize = 18.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    // Optional mTLS client certificate (must already be installed in the
                    // head unit's Android keystore; this only selects an existing one).
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Client certificate (optional)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                certAlias ?: "None selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (certAlias != null) {
                            TextButton(onClick = { viewModel.clearCertificate() }) { Text("Remove") }
                        }
                        OutlinedButton(onClick = {
                            val host = activity ?: return@OutlinedButton
                            chooseClientCertificate(host, certAlias) { alias ->
                                viewModel.onCertificateSelected(alias, context)
                            }
                        }) {
                            Icon(Icons.Default.Security, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (certAlias != null) "Change" else "Select")
                        }
                    }

                    if (connected) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { viewModel.signOut() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        ) {
                            Text("Sign out", fontSize = 18.sp)
                        }
                    }

                    if (statusMessage != null) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (error != null || connection is ConnectionState.Error) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }

                    // The plugin list is not cleared on a sign-out or a dropped connection, and the
                    // page can load the party only while connected.
                    if (partyAvailable && connected) {
                        Spacer(Modifier.height(28.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Spacer(Modifier.height(20.dp))
                        CarPartyRow(onOpen = { partyOpen = true })
                    }

                    Spacer(Modifier.height(28.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    CarPlayerSettings(
                        audioFormat = audioFormat,
                        onAudioFormat = viewModel::setAudioFormat,
                        crossfade = crossfade,
                        onCrossfade = viewModel::setCrossfade,
                        compressorLevel = compressorLevel,
                        onCompressorLevel = viewModel::setCompressorLevel,
                        dither = dither,
                        onDither = viewModel::setDither,
                    )

                    Spacer(Modifier.height(28.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    CarAbout()
                }
            }
        }
    }
}

/**
 * The Home Assistant sign-in page over the whole screen, which the car shows because it has no
 * browser. It is reached only from the parked sign-in screen. A page that failed to load
 * offers Retry and Cancel.
 */
@Composable
private fun CarHomeAssistantPage(url: String, viewModel: CarSignInViewModel) {
    HomeAssistantSignInPage(
        url = url,
        callbackScheme = OAUTH_CALLBACK_SCHEME,
        onCallback = viewModel::deliverHomeAssistantCallback,
        onCancel = viewModel::cancelHomeAssistantSignIn,
        modifier = Modifier.fillMaxSize()
    ) { message, retry ->
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(48.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    retry?.let { again -> Button(onClick = again) { Text("Retry", fontSize = 18.sp) } }
                    OutlinedButton(onClick = viewModel::cancelHomeAssistantSignIn) { Text("Cancel", fontSize = 18.sp) }
                }
            }
        }
    }
}

/**
 * Car-friendly MassDroid-player audio controls. The car IS the Sendspin player, so
 * these tune its own output. Large segmented buttons / slider / switch for at-a-glance,
 * low-distraction use. Audio format / compression / dithering are app-level; crossfade
 * is the car's per-player MA config and stays disabled until the player config loads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarPlayerSettings(
    audioFormat: SendspinAudioFormat,
    onAudioFormat: (SendspinAudioFormat) -> Unit,
    crossfade: CrossfadeMode?,
    onCrossfade: (CrossfadeMode) -> Unit,
    compressorLevel: Int,
    onCompressorLevel: (Int) -> Unit,
    dither: Boolean,
    onDither: (Boolean) -> Unit,
) {
    val compNames = listOf("Off", "Soft", "Medium", "Hard")
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("MassDroid player", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Audio output for this car. Compression and dithering apply live; " +
                "format and crossfade take effect on the next track.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))
        Text("Audio format", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SendspinAudioFormat.entries.forEachIndexed { index, format ->
                SegmentedButton(
                    selected = audioFormat == format,
                    onClick = { onAudioFormat(format) },
                    shape = SegmentedButtonDefaults.itemShape(index, SendspinAudioFormat.entries.size),
                    modifier = Modifier.heightIn(min = 52.dp),
                ) { Text(format.label) }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("Crossfade", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            CrossfadeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = crossfade == mode,
                    onClick = { onCrossfade(mode) },
                    enabled = crossfade != null,
                    shape = SegmentedButtonDefaults.itemShape(index, CrossfadeMode.entries.size),
                    modifier = Modifier.heightIn(min = 52.dp),
                ) { Text(mode.label) }
            }
        }
        if (crossfade == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Connect to adjust crossfade.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Compression", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                compNames[compressorLevel.coerceIn(0, 3)],
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        var compPos by rememberSaveable(compressorLevel) { mutableStateOf(compressorLevel.toFloat()) }
        Slider(
            value = compPos,
            onValueChange = { compPos = it },
            onValueChangeFinished = { onCompressorLevel(compPos.roundToInt()) },
            valueRange = 0f..3f,
            steps = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Output dithering", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Smoother quiet passages on the 16-bit output.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = dither, onCheckedChange = onDither)
        }
    }
}

/**
 * The app version and the privacy policy, as on the phone's About screen. Google Play requires
 * the policy to be reachable in the app. The address is shown as text, because a car usually has
 * no browser to open it in.
 */
@Composable
private fun CarAbout() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("About", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        CarAboutRow(
            icon = Icons.Default.Info,
            title = "Version",
            lines = listOf("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
        )
        Spacer(Modifier.height(12.dp))
        CarAboutRow(
            icon = Icons.Default.PrivacyTip,
            title = "Privacy policy",
            lines = listOf(
                "What the app sends, and to whom",
                AppLinks.PRIVACY_POLICY_URL.removePrefix("https://"),
            ),
        )
    }
}

@Composable
private fun CarAboutRow(icon: ImageVector, title: String, lines: List<String>) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            lines.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
