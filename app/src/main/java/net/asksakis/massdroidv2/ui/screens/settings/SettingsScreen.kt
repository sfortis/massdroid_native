package net.asksakis.massdroidv2.ui.screens.settings

import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdFilledTonalButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdOutlinedButton
import net.asksakis.massdroidv2.ui.components.MdSwitch
import net.asksakis.massdroidv2.ui.components.MdTextButton

import android.app.Activity
import android.security.KeyChain
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speaker
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.data.websocket.ConnectionState

enum class SettingsCategory { CONNECTION, PHONE_AS_SPEAKER, RECOMMENDATIONS, PROXIMITY, ABOUT }

/**
 * Matches text the user could plausibly be typing as a leading "http" / "https"
 * scheme: any prefix of "https" optionally followed by ":", "/", or "//".
 * Used so the scheme suggestion chips REPLACE a half-typed scheme rather than
 * concatenate "https://" to "htt".
 */
private val PARTIAL_HTTP_SCHEME = Regex(
    "^h(t(t(p(s?(:(/(/)?)?)?)?)?)?)?$",
    RegexOption.IGNORE_CASE
)

/**
 * Apply a scheme chip to the current URL field text. Three cases:
 *  1. The text already has "://" somewhere: swap whatever was before it.
 *  2. The text is itself a partial scheme (e.g. "h", "htt", "https:"): drop
 *     it so the new scheme stands alone.
 *  3. Anything else (host-looking text): prepend.
 */
private fun applyUrlScheme(scheme: String, current: String): String {
    val trimmed = current.trim()
    val sep = trimmed.indexOf("://")
    if (sep >= 0) return scheme + trimmed.substring(sep + 3)
    val host = if (PARTIAL_HTTP_SCHEME.matches(trimmed)) "" else trimmed
    return scheme + host
}

private fun launchCustomTab(context: android.content.Context, url: String) {
    val intent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    intent.launchUrl(context, android.net.Uri.parse(url))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenRecommendationInsights: () -> Unit,
    onSetupRoom: (roomId: String?) -> Unit = {},
    initialCategory: SettingsCategory? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val updateUiState by viewModel.updateUiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // initialCategory only seeds the FIRST composition. Process death restores
    // whatever the user had drilled into (rememberSaveable wins on recreation).
    var selectedCategoryName by rememberSaveable { mutableStateOf(initialCategory?.name) }
    val selectedCategory = selectedCategoryName?.let { name -> SettingsCategory.entries.find { it.name == name } }

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

    BackHandler(enabled = selectedCategory != null) { selectedCategoryName = null }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (selectedCategory) {
                            SettingsCategory.CONNECTION -> "Connection"
                            SettingsCategory.PHONE_AS_SPEAKER -> "Phone as Speaker"
                            SettingsCategory.RECOMMENDATIONS -> "Recommendations"
                            SettingsCategory.PROXIMITY -> "Follow Me"
                            SettingsCategory.ABOUT -> "About"
                            null -> "Settings"
                        }
                    )
                },
                navigationIcon = {
                    MdIconButton(onClick = {
                        if (selectedCategory != null) selectedCategoryName = null else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        androidx.compose.animation.AnimatedContent(
            targetState = selectedCategory,
            label = "settings_nav"
        ) { category ->
            when (category) {
                null -> CategoryList(
                    viewModel = viewModel,
                    modifier = Modifier.padding(paddingValues),
                    onSelect = { selectedCategoryName = it.name }
                )
                SettingsCategory.PHONE_AS_SPEAKER -> PhoneAsSpeakerScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(paddingValues)
                )
                SettingsCategory.CONNECTION -> ConnectionScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(paddingValues)
                )
                SettingsCategory.PROXIMITY -> ProximitySettingsScreen(
                    onBack = { selectedCategoryName = null },
                    onSetupRoom = onSetupRoom,
                    modifier = Modifier.padding(paddingValues)
                )
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
}

// region Category List

@Composable
private fun CategoryList(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onSelect: (SettingsCategory) -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        ConnectionStatusCard(connectionState = connectionState, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        ListItem(
            headlineContent = { Text("Connection") },
            supportingContent = { Text("Server URL, authentication, and certificates") },
            leadingContent = {
                Icon(Icons.Default.Wifi, contentDescription = null)
            },
            modifier = Modifier.clickable { onSelect(SettingsCategory.CONNECTION) }
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Phone as Speaker") },
            supportingContent = { Text("Stream audio to this phone via Sendspin") },
            leadingContent = {
                Icon(Icons.Default.Speaker, contentDescription = null)
            },
            modifier = Modifier.clickable { onSelect(SettingsCategory.PHONE_AS_SPEAKER) }
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
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Follow Me") },
            supportingContent = { Text("Music follows you between rooms") },
            leadingContent = {
                Icon(Icons.Default.LocationOn, contentDescription = null)
            },
            modifier = Modifier.clickable { onSelect(SettingsCategory.PROXIMITY) }
        )
        HorizontalDivider()
        ThemeSelector(viewModel)
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

@Composable
private fun ThemeSelector(viewModel: SettingsViewModel) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle(initialValue = "auto")
    val options = listOf("auto" to "Auto", "dark" to "Dark", "light" to "Light")

    ListItem(
        headlineContent = { Text("Theme") },
        supportingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (value, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = themeMode == value,
                        onClick = { viewModel.setThemeMode(value) },
                        label = { Text(label) }
                    )
                }
            }
        },
        leadingContent = {
            Icon(Icons.Default.DarkMode, contentDescription = null)
        }
    )
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
    }
}

@Composable
private fun PhoneAsSpeakerScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isConnected = connectionState is ConnectionState.Connected

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (isConnected) {
            SendspinCard(viewModel = viewModel)
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Connect to your Music Assistant server first to enable Phone as Speaker.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
private fun ConnectionStatusCard(connectionState: ConnectionState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = when (connectionState) {
                is ConnectionState.Connected -> MaterialTheme.colorScheme.primaryContainer
                is ConnectionState.Error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
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

    var editUrl by remember(serverUrl) {
        mutableStateOf(TextFieldValue(serverUrl, TextRange(serverUrl.length)))
    }
    var username by remember(savedUsername) { mutableStateOf(savedUsername) }
    var password by remember(savedPassword) { mutableStateOf(savedPassword) }
    var showPassword by remember { mutableStateOf(false) }

    val reconnecting by viewModel.isReconnecting.collectAsStateWithLifecycle()
    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting
    val isRetryingConnection = isConnecting || (connectionState is ConnectionState.Error && reconnecting)
    val hasToken = authToken.isNotBlank()

    // Auth providers exposed by the server (only HA OAuth is treated specially;
    // the built-in credentials path always falls through).
    val providers by viewModel.availableAuthProviders.collectAsStateWithLifecycle()
    val oauthInProgress by viewModel.oauthInProgress.collectAsStateWithLifecycle()
    val haProviderAvailable = providers.any { it.isHomeAssistant }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Probe the server's providers as the URL stabilises (debounced).
    LaunchedEffect(editUrl.text, isConnected) {
        if (isConnected) return@LaunchedEffect
        if (editUrl.text.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        viewModel.probeAuthProviders(editUrl.text)
    }
    // Surface OAuth errors as login errors so the existing error row picks them up.
    LaunchedEffect(Unit) {
        viewModel.oauthErrors.collect { msg -> viewModel.setLoginError(msg) }
    }
    val primaryConnectionButtonLabel = when {
        isConnected -> "Disconnect"
        isRetryingConnection -> "Abort"
        connectionState is ConnectionState.Error -> "Retry"
        else -> "Connect"
    }

    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Server",
            style = MaterialTheme.typography.titleSmall
        )

        if (isConnected) {
            // Read-only URL display + signed-in identity + Sign out.
            Text(
                text = serverUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Signed in as ${currentUser?.username ?: "unknown"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    currentUser?.authMethod?.let {
                        Text(
                            text = "via $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            MdButton(
                onClick = { viewModel.signOut() },
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(Icons.Default.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sign out")
            }
            Text(
                text = "Sign out wipes your saved sign-in for this server. " +
                    "The server URL is kept so you can sign back in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Disconnected (or connecting / error). Show editable URL and the
            // sign-in options.
            val urlMissingScheme = editUrl.text.isNotBlank() && !editUrl.text.contains("://")
            OutlinedTextField(
                value = editUrl,
                onValueChange = { editUrl = it },
                label = { Text("Server URL") },
                placeholder = { Text("https://ma.example.com") },
                singleLine = true,
                isError = urlMissingScheme,
                supportingText = if (urlMissingScheme) {
                    { Text("Add http:// or https:// to the URL") }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
            if (urlMissingScheme) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.SuggestionChip(
                        onClick = {
                            val next = applyUrlScheme("http://", editUrl.text)
                            editUrl = TextFieldValue(next, TextRange(next.length))
                        },
                        label = { Text("Use http://") }
                    )
                    androidx.compose.material3.SuggestionChip(
                        onClick = {
                            val next = applyUrlScheme("https://", editUrl.text)
                            editUrl = TextFieldValue(next, TextRange(next.length))
                        },
                        label = { Text("Use https://") }
                    )
                }
            }

            if (haProviderAvailable) {
                MdButton(
                    onClick = {
                        viewModel.clearLoginError()
                        coroutineScope.launch {
                            val authUrl = viewModel.startHomeAssistantOAuth(editUrl.text)
                            if (authUrl != null) launchCustomTab(context, authUrl)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !oauthInProgress
                ) {
                    if (oauthInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Waiting for sign in...")
                    } else {
                        Icon(Icons.Default.Home, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sign in with Home Assistant")
                    }
                }
                Text(
                    text = "or use credentials",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
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
                    .semantics { contentType = ContentType.Username }
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    MdIconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = "Toggle password"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentType = ContentType.Password }
            )

            val displayError = loginError
                ?: (connectionState as? ConnectionState.Error)?.message
            displayError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            MdButton(
                onClick = {
                    if (hasToken && username.isBlank() && password.isBlank()) {
                        viewModel.connectWithToken(editUrl.text)
                    } else {
                        viewModel.login(editUrl.text, username, password)
                    }
                },
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
                    Text("Sign in")
                }
            }
        }
    }
    }
}

@Composable
private fun ClientCertCard(viewModel: SettingsViewModel) {
    val clientCertAlias by viewModel.clientCertAlias.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
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
                    MdOutlinedButton(onClick = {
                        val activity = context as? Activity ?: return@MdOutlinedButton
                        KeyChain.choosePrivateKeyAlias(
                            activity,
                            { alias -> viewModel.onCertificateSelected(alias, context) },
                            null, null, null, -1, clientCertAlias
                        )
                    }) {
                        Text("Change")
                    }
                    MdOutlinedButton(onClick = { viewModel.clearCertificate() }) {
                        Text("Remove")
                    }
                }
            } else {
                Text(
                    "No certificate selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MdOutlinedButton(onClick = {
                    val activity = context as? Activity ?: return@MdOutlinedButton
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
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        ListItem(
            headlineContent = { Text("Smart Listening") },
            supportingContent = { Text("Learns from skip/like/listen actions and improves recommendations.") },
            trailingContent = {
                MdSwitch(checked = smartListeningEnabled, onCheckedChange = { viewModel.toggleSmartListening(it) })
            }
        )
    }
}

@Composable
private fun InsightsCard(viewModel: SettingsViewModel, onOpen: () -> Unit) {
    val smartListeningEnabled by viewModel.smartListeningEnabled.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        ListItem(
            headlineContent = { Text("Recommendation Insights") },
            supportingContent = {
                Column {
                    Text("Score stats, blocked artists, and recommendation DB actions.")
                    if (!smartListeningEnabled) {
                        Text("Enable Smart Listening first.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            trailingContent = {
                MdButton(onClick = onOpen, enabled = smartListeningEnabled) {
                    Text("Open")
                }
            }
        )
    }
}

@Composable
private fun LastFmCard(viewModel: SettingsViewModel) {
    val lastFmApiKey by viewModel.lastFmApiKey.collectAsStateWithLifecycle()
    val lastFmValidation by viewModel.lastFmValidation.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentType = ContentType.Password },
                visualTransformation = if (apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    Row {
                        if (apiKeyInput.isNotBlank()) {
                            MdIconButton(onClick = {
                                apiKeyInput = ""
                                viewModel.clearLastFmValidation()
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                        MdIconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
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
                        { Text("Validation failed", color = MaterialTheme.colorScheme.error) }
                    }
                    else -> null
                }
            )
            if (lastFmValidation is LastFmValidation.Invalid) {
                val reason = (lastFmValidation as LastFmValidation.Invalid).reason
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        reason,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            MdButton(
                onClick = { viewModel.setLastFmApiKey(apiKeyInput) },
                enabled = apiKeyInput.trim() != lastFmApiKey && !isValidating
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
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        ListItem(
            headlineContent = { Text("Sendspin (Phone as Speaker)") },
            supportingContent = {
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
                    color = when (sendspinState) {
                        SendspinState.STREAMING -> MaterialTheme.colorScheme.primary
                        SendspinState.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            },
            trailingContent = {
                MdSwitch(checked = sendspinEnabled, onCheckedChange = { viewModel.toggleSendspin(it) })
            }
        )
    }
}

@Composable
private fun UpdatesCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onToggleIncludeBeta: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
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
            MdButton(
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
                Text("Include beta updates", style = MaterialTheme.typography.bodyMedium)
                MdSwitch(checked = state.includeBetaUpdates, onCheckedChange = onToggleIncludeBeta)
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
            MdButton(onClick = onConfirm, enabled = !busy) {
                Text("Download & Install")
            }
        },
        dismissButton = {
            MdOutlinedButton(onClick = onDismiss, enabled = !busy) {
                Text("Later")
            }
        }
    )
}

// endregion
