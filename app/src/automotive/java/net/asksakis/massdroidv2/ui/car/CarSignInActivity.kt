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
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import net.asksakis.massdroidv2.ui.theme.MassDroidTheme

/**
 * Parked-only car (AAOS) sign-in / server-config screen. Reached from the media
 * center's settings (gear, ACTION_APPLICATION_PREFERENCES) and from the signed-out
 * SessionError resolution intent. NOT a launcher activity and intentionally NOT
 * distractionOptimized: it is for a parked, one-time setup task, so the car drops
 * its launcher icon and never exposes the full phone UI while driving.
 */
@AndroidEntryPoint
class CarSignInActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MassDroidTheme {
                CarSignInScreen(onClose = { finish() })
            }
        }
    }
}

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

    Surface(modifier = Modifier.fillMaxSize()) {
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
            }
        }
    }
}
