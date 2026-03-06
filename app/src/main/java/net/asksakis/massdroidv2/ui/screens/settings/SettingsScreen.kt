package net.asksakis.massdroidv2.ui.screens.settings

import android.app.Activity
import android.security.KeyChain
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.data.websocket.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenRecommendationInsights: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val authToken by viewModel.authToken.collectAsStateWithLifecycle()
    val clientCertAlias by viewModel.clientCertAlias.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val loginError by viewModel.loginError.collectAsStateWithLifecycle()
    val sendspinState by viewModel.sendspinState.collectAsStateWithLifecycle()
    val sendspinEnabled by viewModel.sendspinEnabled.collectAsStateWithLifecycle()
    val smartListeningEnabled by viewModel.smartListeningEnabled.collectAsStateWithLifecycle()
    val includeBetaUpdates by viewModel.includeBetaUpdates.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val updateBusy by viewModel.updateBusy.collectAsStateWithLifecycle()
    val updateProgress by viewModel.updateProgress.collectAsStateWithLifecycle()
    val updateMessage by viewModel.updateMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val savedUsername by viewModel.savedUsername.collectAsStateWithLifecycle()
    val savedPassword by viewModel.savedPassword.collectAsStateWithLifecycle()

    var editUrl by remember(serverUrl) { mutableStateOf(serverUrl) }
    var username by remember(savedUsername) { mutableStateOf(savedUsername) }
    var password by remember(savedPassword) { mutableStateOf(savedPassword) }
    var showPassword by remember { mutableStateOf(false) }

    val isConnected = connectionState is ConnectionState.Connected
    val hasToken = authToken.isNotBlank()

    LaunchedEffect(Unit) {
        viewModel.loadSavedCertificate(context)
    }

    LaunchedEffect(updateMessage) {
        if (updateMessage != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.clearUpdateMessage()
        }
    }

    updateInfo?.let { info ->
        val fileSizeMb = info.fileSizeBytes / (1024 * 1024)
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdateDialog() },
            title = { Text("Update Available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version ${info.version} is available${if (fileSizeMb > 0) " (${fileSizeMb}MB)" else ""}.")
                    Text(
                        info.releaseNotes.take(500).ifBlank { "No release notes." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.downloadAndInstallUpdate() },
                    enabled = !updateBusy
                ) {
                    Text("Download & Install")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.dismissUpdateDialog() }) {
                    Text("Later")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when (connectionState) {
                        is ConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer
                        is ConnectionState.Error -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Icon(
                        when (connectionState) {
                            is ConnectionState.Connected -> Icons.Default.Cloud
                            is ConnectionState.Connecting -> Icons.Default.CloudSync
                            else -> Icons.Default.CloudOff
                        },
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        when (connectionState) {
                            is ConnectionState.Connected -> {
                                val info = (connectionState as ConnectionState.Connected).serverInfo
                                "Connected (v${info.serverVersion})"
                            }
                            is ConnectionState.Connecting -> "Connecting..."
                            is ConnectionState.Error -> "Error: ${(connectionState as ConnectionState.Error).message}"
                            is ConnectionState.Disconnected -> "Disconnected"
                        }
                    )
                }
            }

            OutlinedTextField(
                value = editUrl,
                onValueChange = { editUrl = it },
                label = { Text("Server URL") },
                placeholder = { Text("https://ma.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnected
            )

            if (!isConnected) {
                HorizontalDivider()

                if (hasToken) {
                    Button(
                        onClick = { viewModel.connectWithToken(editUrl) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reconnect")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "Or login with different credentials:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        viewModel.clearLoginError()
                    },
                    label = { Text("Username") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        viewModel.clearLoginError()
                    },
                    label = { Text("Password") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle password"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                loginError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = { viewModel.login(editUrl, username, password) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectionState !is ConnectionState.Connecting
                ) {
                    if (connectionState is ConnectionState.Connecting) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connecting...")
                    } else {
                        Icon(Icons.Default.Login, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Login")
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Disconnect")
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("App Updates", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Current version: ${viewModel.appVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { viewModel.checkForUpdates(force = true) },
                        enabled = !updateBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (updateBusy && updateProgress == null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Checking...")
                        } else if (updateBusy && updateProgress != null) {
                            Text("Downloading... ${updateProgress ?: 0}%")
                        } else {
                            Text("Check for Updates")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Include beta updates",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = includeBetaUpdates,
                            onCheckedChange = { viewModel.toggleIncludeBetaUpdates(it) }
                        )
                    }
                    if (updateProgress != null) {
                        LinearProgressIndicator(
                            progress = { (updateProgress ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    updateMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Client Certificate (mTLS)",
                        style = MaterialTheme.typography.titleSmall
                    )

                    if (clientCertAlias != null) {
                        Text(
                            "Certificate: $clientCertAlias",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val activity = context as? Activity ?: return@OutlinedButton
                                KeyChain.choosePrivateKeyAlias(
                                    activity,
                                    { alias -> viewModel.onCertificateSelected(alias, context) },
                                    null,
                                    null,
                                    null,
                                    -1,
                                    clientCertAlias
                                )
                            }) {
                                Text("Change")
                            }
                            OutlinedButton(onClick = { viewModel.clearCertificate() }) {
                                Text("Remove")
                            }
                        }
                    } else {
                        Text(
                            "No certificate selected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = {
                            val activity = context as? Activity ?: return@OutlinedButton
                            KeyChain.choosePrivateKeyAlias(
                                activity,
                                { alias -> viewModel.onCertificateSelected(alias, context) },
                                null,
                                null,
                                null,
                                -1,
                                null
                            )
                        }) {
                            Icon(Icons.Default.Security, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select Certificate")
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Smart Listening",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "Learns from skip/like/listen actions and improves recommendations.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = smartListeningEnabled,
                        onCheckedChange = { viewModel.toggleSmartListening(it) }
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Recommendation Insights", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Open detailed score stats, blocked artists, and recommendation DB actions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onOpenRecommendationInsights,
                        enabled = smartListeningEnabled
                    ) {
                        Text("Open Insights")
                    }
                    if (!smartListeningEnabled) {
                        Text(
                            "Enable Smart Listening first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isConnected) {
                HorizontalDivider()

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Sendspin (Phone as Speaker)",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    when (sendspinState) {
                                        SendspinState.STREAMING -> "Streaming"
                                        SendspinState.SYNCING -> "Ready"
                                        SendspinState.HANDSHAKING -> "Handshaking..."
                                        SendspinState.AUTHENTICATING -> "Authenticating..."
                                        SendspinState.CONNECTING -> "Connecting..."
                                        SendspinState.ERROR -> "Error"
                                        SendspinState.DISCONNECTED -> if (sendspinEnabled) "Stopped" else "Disabled"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (sendspinState) {
                                        SendspinState.STREAMING -> MaterialTheme.colorScheme.primary
                                        SendspinState.ERROR -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Switch(
                                checked = sendspinEnabled,
                                onCheckedChange = { viewModel.toggleSendspin(it) }
                            )
                        }
                    }
                }
            }
        }
    }
}
