package net.asksakis.massdroidv2.ui.car

import android.app.Activity
import android.os.Bundle
import android.security.KeyChain
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.domain.model.CrossfadeMode
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import net.asksakis.massdroidv2.ui.theme.MassDroidTheme
import kotlin.math.roundToInt

/**
 * Car (AAOS) sign-in / server-config + MassDroid-player audio settings screen.
 * Reached from the media center's settings (gear, ACTION_APPLICATION_PREFERENCES)
 * and from the signed-out SessionError resolution intent. NOT a launcher activity,
 * so the car never exposes the full phone UI. Declared distractionOptimized in the
 * automotive manifest (sideload-only app) so the player settings stay reachable
 * while driving; controls are large and low-distraction.
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
                CarSignInScreen(onClose = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarSignInScreen(
    onClose: () -> Unit,
    viewModel: CarSignInViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val certAlias by viewModel.certAlias.collectAsStateWithLifecycle()
    val prefill by viewModel.prefill.collectAsStateWithLifecycle()
    val audioFormat by viewModel.audioFormat.collectAsStateWithLifecycle()
    val compressorLevel by viewModel.compressorLevel.collectAsStateWithLifecycle()
    val dither by viewModel.dither.collectAsStateWithLifecycle()
    val crossfade by viewModel.crossfade.collectAsStateWithLifecycle()

    var url by rememberSaveable { mutableStateOf("https://") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var seeded by rememberSaveable { mutableStateOf(false) }
    // Only auto-close after a sign-in initiated from THIS screen, so opening the
    // gear while already connected (to change server / sign out) does not slam shut.
    var attempted by rememberSaveable { mutableStateOf(false) }

    // Pre-fill the form from saved settings once (without clobbering user typing).
    LaunchedEffect(prefill) {
        val p = prefill ?: return@LaunchedEffect
        if (!seeded) {
            if (p.url.isNotBlank()) url = p.url
            if (p.username.isNotBlank()) username = p.username
            seeded = true
        }
    }

    // Reload any saved client certificate so a re-opened screen keeps mTLS active.
    LaunchedEffect(Unit) { viewModel.loadSavedCertificate(context) }

    LaunchedEffect(connection, attempted) {
        if (attempted && connection is ConnectionState.Connected) onClose()
    }

    val connecting = connection is ConnectionState.Connecting
    val connected = connection is ConnectionState.Connected
    val statusMessage = error
        ?: (connection as? ConnectionState.Error)?.message
        ?: when {
            connecting -> "Connecting..."
            connected -> "Connected to ${url.removePrefix("https://").removePrefix("http://")}"
            else -> null
        }

    Scaffold(
        topBar = {
            // Explicit back affordance: the AAOS media center has no guaranteed back
            // button on a parked settings activity, so the gear/SessionError entry would
            // otherwise be a one-way trip. The arrow finish()es back to the media center.
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
                    .verticalScroll(rememberScrollState())
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

                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Server URL") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            attempted = true
                            viewModel.login(url, username, password)
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
                            val activity = context as? Activity ?: return@OutlinedButton
                            KeyChain.choosePrivateKeyAlias(
                                activity,
                                { alias -> viewModel.onCertificateSelected(alias, context) },
                                null, null, null, -1, null,
                            )
                        }) {
                            Icon(Icons.Default.Security, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (certAlias != null) "Change" else "Select")
                        }
                    }

                    if (connected) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.signOut() }) { Text("Sign out") }
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
