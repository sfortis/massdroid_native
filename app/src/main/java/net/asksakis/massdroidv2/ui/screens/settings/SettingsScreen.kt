package net.asksakis.massdroidv2.ui.screens.settings

import android.app.Activity
import android.security.KeyChain
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.material3.ListItem
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

private enum class SettingsCategory { CONNECTION, RECOMMENDATIONS, PROXIMITY, ABOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenRecommendationInsights: () -> Unit,
    onOpenProximity: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val updateUiState by viewModel.updateUiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }

    LaunchedEffect(Unit) { viewModel.loadSavedCertificate(context) }

    LaunchedEffect(updateUiState.message) {
        if (updateUiState.message != null) {
            kotlinx.coroutines.delay(2500)
            viewModel.clearUpdateMessage()
        }
    }

    updateUiState.availableUpdate?.let { info ->
        UpdateAvailableDialog(
            updateInfo = info,
            busy = updateUiState.isChecking || updateUiState.isDownloading,
            onConfirm = viewModel::downloadAndInstallUpdate,
            onDismiss = viewModel::dismissUpdateDialog
        )
    }

    BackHandler(enabled = selectedCategory != null) { selectedCategory = null }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (selectedCategory) {
                            SettingsCategory.CONNECTION -> "Connection"
                            SettingsCategory.RECOMMENDATIONS -> "Recommendations"
                            SettingsCategory.PROXIMITY -> "Proximity Playback"
                            SettingsCategory.ABOUT -> "About"
                            null -> "Settings"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedCategory != null) selectedCategory = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        when (selectedCategory) {
            null -> CategoryList(
                viewModel = viewModel,
                modifier = Modifier.padding(paddingValues),
                onSelect = { selectedCategory = it },
                onOpenProximity = onOpenProximity
            )
            SettingsCategory.CONNECTION -> ConnectionScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(paddingValues)
            )
            SettingsCategory.PROXIMITY -> { /* handled via direct navigation */ }
            SettingsCategory.RECOMMENDATIONS -> RecommendationsScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(paddingValues),
                onOpenInsights = onOpenRecommendationInsights
            )
            SettingsCategory.ABOUT -> AboutScreen(
                viewModel = viewModel,
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

// region Category List

@Composable
private fun CategoryList(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onSelect: (SettingsCategory) -> Unit,
    onOpenProximity: () -> Unit = {}
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        ConnectionStatusCard(connectionState = connectionState)

        ListItem(
            headlineContent = { Text("Connection") },
            supportingContent = { Text("Server setup, authentication, and streaming") },
            leadingContent = {
                Icon(Icons.Default.Wifi, contentDescription = null)
            },
            modifier = Modifier.clickable { onSelect(SettingsCategory.CONNECTION) }
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Recommendations") },
            supportingContent = { Text("Personalized music discovery and genre enrichment") },
            leadingContent = {
                Icon(Icons.Default.MusicNote, contentDescription = null)
            },
            modifier = Modifier.clickable { onSelect(SettingsCategory.RECOMMENDATIONS) }
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Proximity Playback") },
                supportingContent = { Text("Auto-detect rooms via BLE and transfer playback") },
                leadingContent = {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                },
                modifier = Modifier.clickable { onOpenProximity() }
            )
        }
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("About") },
            supportingContent = { Text("App version and update management") },
            leadingContent = {
                Icon(Icons.Default.Info, contentDescription = null)
            },
            modifier = Modifier.clickable { onSelect(SettingsCategory.ABOUT) }
        )
    }
}

// endregion

// region Connection Screen

@Composable
private fun ConnectionScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isConnected = connectionState is ConnectionState.Connected

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ConnectionStatusCard(connectionState = connectionState)
        ServerConnectionCard(viewModel = viewModel, connectionState = connectionState)
        ClientCertCard(viewModel = viewModel)
        if (isConnected) {
            SendspinCard(viewModel = viewModel)
        }
    }
}

// endregion

// region Recommendations Screen

@Composable
private fun RecommendationsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onOpenInsights: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SmartListeningCard(viewModel = viewModel)
        InsightsCard(viewModel = viewModel, onOpen = onOpenInsights)
        LastFmCard(viewModel = viewModel)
    }
}

// endregion

// region About Screen

@Composable
private fun AboutScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val updateUiState by viewModel.updateUiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        UpdatesCard(
            state = updateUiState,
            onCheck = { viewModel.checkForUpdates(force = true) },
            onToggleIncludeBeta = viewModel::toggleIncludeBetaUpdates
        )
    }
}

// endregion

// region Card Components

@Composable
private fun ConnectionStatusCard(connectionState: ConnectionState) {
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
                    is ConnectionState.Error ->
                        "Error: ${(connectionState as ConnectionState.Error).message}"
                    is ConnectionState.Disconnected -> "Disconnected"
                }
            )
        }
    }
}

@Composable
private fun ServerConnectionCard(
    viewModel: SettingsViewModel,
    connectionState: ConnectionState
) {
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val authToken by viewModel.authToken.collectAsStateWithLifecycle()
    val loginError by viewModel.loginError.collectAsStateWithLifecycle()
    val savedUsername by viewModel.savedUsername.collectAsStateWithLifecycle()
    val savedPassword by viewModel.savedPassword.collectAsStateWithLifecycle()

    var editUrl by remember(serverUrl) { mutableStateOf(serverUrl) }
    var username by remember(savedUsername) { mutableStateOf(savedUsername) }
    var password by remember(savedPassword) { mutableStateOf(savedPassword) }
    var showPassword by remember { mutableStateOf(false) }

    val isConnected = connectionState is ConnectionState.Connected
    val isRetryingConnection =
        connectionState is ConnectionState.Connecting || connectionState is ConnectionState.Error
    val hasToken = authToken.isNotBlank()
    val primaryConnectionButtonLabel = when {
        isConnected -> "Disconnect"
        isRetryingConnection -> "Abort"
        else -> "Connect"
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

    if (hasToken) {
        Button(
            onClick = {
                if (isConnected || isRetryingConnection) {
                    viewModel.disconnect()
                } else {
                    viewModel.connectWithToken(editUrl)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                if (isConnected || isRetryingConnection) Icons.Default.CloudOff
                else Icons.Default.Cloud,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(primaryConnectionButtonLabel)
        }
    }

    if (!isConnected) {
        HorizontalDivider()

        if (hasToken) {
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
            visualTransformation = if (showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff
                        else Icons.Default.Visibility,
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
                CircularProgressIndicator(
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
    }
}

@Composable
private fun ClientCertCard(viewModel: SettingsViewModel) {
    val clientCertAlias by viewModel.clientCertAlias.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            Text("Client Certificate (mTLS)", style = MaterialTheme.typography.titleSmall)

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
                            null, null, null, -1, clientCertAlias
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
                        null, null, null, -1, null
                    )
                }) {
                    Icon(Icons.Default.Security, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Certificate")
                }
            }
        }
    }
}

@Composable
private fun SmartListeningCard(viewModel: SettingsViewModel) {
    val smartListeningEnabled by viewModel.smartListeningEnabled.collectAsStateWithLifecycle()

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
                Text("Smart Listening", style = MaterialTheme.typography.titleSmall)
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
}

@Composable
private fun InsightsCard(viewModel: SettingsViewModel, onOpen: () -> Unit) {
    val smartListeningEnabled by viewModel.smartListeningEnabled.collectAsStateWithLifecycle()

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
            Button(onClick = onOpen, enabled = smartListeningEnabled) {
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
}

@Composable
private fun LastFmCard(viewModel: SettingsViewModel) {
    val lastFmApiKey by viewModel.lastFmApiKey.collectAsStateWithLifecycle()
    val lastFmValidation by viewModel.lastFmValidation.collectAsStateWithLifecycle()

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
            Text("Last.fm Genre Enrichment", style = MaterialTheme.typography.titleSmall)
            Text(
                "Enrich genre data using Last.fm. Get your API key from last.fm/api",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var apiKeyInput by remember(lastFmApiKey) { mutableStateOf(lastFmApiKey) }
            var apiKeyVisible by remember { mutableStateOf(false) }
            val isValidating = lastFmValidation is LastFmValidation.Validating
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = {
                    apiKeyInput = it
                    viewModel.clearLastFmValidation()
                },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    Row {
                        if (apiKeyInput.isNotBlank()) {
                            IconButton(onClick = {
                                apiKeyInput = ""
                                viewModel.clearLastFmApiKey()
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                if (apiKeyVisible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                },
                isError = lastFmValidation is LastFmValidation.Invalid,
                supportingText = when (lastFmValidation) {
                    is LastFmValidation.Valid -> {
                        { Text("API key verified", color = MaterialTheme.colorScheme.primary) }
                    }
                    is LastFmValidation.Invalid -> {
                        { Text("Invalid API key") }
                    }
                    else -> null
                }
            )
            Button(
                onClick = { viewModel.setLastFmApiKey(apiKeyInput) },
                enabled = apiKeyInput.trim() != lastFmApiKey
                    && apiKeyInput.isNotBlank()
                    && !isValidating
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Validating...")
                } else {
                    Text("Save")
                }
            }

            val enrichProgress by viewModel.enrichmentProgress.collectAsStateWithLifecycle()
            if (enrichProgress.isRunning) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        "Enriching genres: ${enrichProgress.processed}/${enrichProgress.total} (${enrichProgress.enriched} new)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (enrichProgress.total > 0) {
                Text(
                    "Genre enrichment complete: ${enrichProgress.enriched} artists enriched",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SendspinCard(viewModel: SettingsViewModel) {
    val sendspinEnabled by viewModel.sendspinEnabled.collectAsStateWithLifecycle()
    val sendspinState by viewModel.sendspinState.collectAsStateWithLifecycle()

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
                            SendspinState.DISCONNECTED ->
                                if (sendspinEnabled) "Stopped" else "Disabled"
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

@Composable
private fun UpdatesCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onToggleIncludeBeta: (Boolean) -> Unit
) {
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
                "Current version: ${state.appVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onCheck,
                enabled = !state.isChecking && !state.isDownloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    state.isChecking -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Checking...")
                    }
                    state.isDownloading -> {
                        Text("Downloading... ${state.downloadProgress ?: 0}%")
                    }
                    else -> {
                        Text("Check for Updates")
                    }
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
                    checked = state.includeBetaUpdates,
                    onCheckedChange = onToggleIncludeBeta
                )
            }
            state.downloadProgress?.let { progress ->
                if (state.isDownloading) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// endregion

// region Dialogs

@Composable
private fun UpdateAvailableDialog(
    updateInfo: net.asksakis.massdroidv2.data.update.AppUpdateChecker.UpdateInfo,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val fileSizeMb = updateInfo.fileSizeBytes / (1024 * 1024)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version ${updateInfo.version} is available${if (fileSizeMb > 0) " (${fileSizeMb}MB)" else ""}.")
                Text(
                    updateInfo.releaseNotes.take(500).ifBlank { "No release notes." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) {
                Text("Download & Install")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !busy) {
                Text("Later")
            }
        }
    )
}

// endregion
