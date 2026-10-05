package net.asksakis.massdroidv2.ui.screens.settings

import net.asksakis.massdroidv2.ui.components.LabeledSlider
import net.asksakis.massdroidv2.ui.components.MdSlider
import kotlin.math.roundToInt
import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.SETTINGS_ROW_INSET
import net.asksakis.massdroidv2.ui.components.SETTINGS_SCREEN_BOTTOM_PADDING
import net.asksakis.massdroidv2.ui.components.SETTINGS_TEXT_INSET
import net.asksakis.massdroidv2.ui.components.SettingsChoiceRow
import net.asksakis.massdroidv2.ui.components.SettingsConfirmDialog
import net.asksakis.massdroidv2.ui.components.SettingsBadge
import net.asksakis.massdroidv2.ui.components.SettingsExpandableRow
import net.asksakis.massdroidv2.ui.components.SettingsNavigationRow
import net.asksakis.massdroidv2.ui.components.SettingsRow
import net.asksakis.massdroidv2.ui.components.SettingsSectionDivider
import net.asksakis.massdroidv2.ui.components.SettingsSectionHeader
import net.asksakis.massdroidv2.ui.components.SettingsSwitchRow
import net.asksakis.massdroidv2.ui.components.SettingsTone

import android.app.Activity
import android.security.KeyChain
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.SystemUpdate
import net.asksakis.massdroidv2.ui.components.WhatsNewItems
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import net.asksakis.massdroidv2.R
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.BuildConfig
import net.asksakis.massdroidv2.data.sendspin.SendspinState
import net.asksakis.massdroidv2.data.sendspin.SyncProbeOutcome
import net.asksakis.massdroidv2.ui.components.MdTextButton
import net.asksakis.massdroidv2.ui.permissions.AppPermissions
import net.asksakis.massdroidv2.domain.model.QueueConfigOption
import net.asksakis.massdroidv2.domain.model.SendspinAudioFormat
import net.asksakis.massdroidv2.domain.recommendation.smartMixTrackTargetFor
import net.asksakis.massdroidv2.data.websocket.ConnectionState

enum class SettingsCategory {
    CONNECTION, PHONE_AS_SPEAKER, RECOMMENDATIONS, PROXIMITY, NFC_TAGS, DIAGNOSTICS, ABOUT
}

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
                            SettingsCategory.PHONE_AS_SPEAKER -> "Phone as speaker"
                            SettingsCategory.RECOMMENDATIONS -> "Recommendations"
                            SettingsCategory.PROXIMITY -> "Follow Me"
                            SettingsCategory.NFC_TAGS -> "NFC tags"
                            SettingsCategory.DIAGNOSTICS -> "Diagnostics"
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
                SettingsCategory.NFC_TAGS -> NfcTagsScreen(
                    modifier = Modifier.padding(paddingValues)
                )
                SettingsCategory.DIAGNOSTICS -> DiagnosticsScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(paddingValues)
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

/**
 * The top of Settings: where the server stands, then one row per category.
 *
 * The rows are grouped in sections like every other settings surface, with no divider
 * between rows inside a group. The connection status used to sit in a coloured card
 * above the list; it is now the first row of the Server group. The list scrolls because
 * the section headers make it taller than a phone held sideways.
 */
@Composable
private fun CategoryList(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onSelect: (SettingsCategory) -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    SettingsScreenColumn(modifier = modifier) {
        SettingsSectionHeader("Server")
        ConnectionStatusRow(connectionState = connectionState)
        SettingsNavigationRow(
            title = "Connection",
            icon = Icons.Default.Dns,
            supporting = "Server URL, authentication, and certificates",
            onClick = { onSelect(SettingsCategory.CONNECTION) }
        )
        SettingsSectionDivider()
        SettingsSectionHeader("Playback")
        SettingsNavigationRow(
            title = "Phone as speaker",
            icon = Icons.Default.Speaker,
            supporting = "Stream audio to this phone via Sendspin",
            onClick = { onSelect(SettingsCategory.PHONE_AS_SPEAKER) }
        )
        SettingsNavigationRow(
            title = "Follow Me",
            icon = Icons.Default.Sensors,
            supporting = "Music follows you between rooms",
            onClick = { onSelect(SettingsCategory.PROXIMITY) }
        )
        // Offered only where there is a chip, because everything behind it is about
        // writing and reading tags.
        if (LocalContext.current.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)) {
            SettingsNavigationRow(
                title = "NFC tags",
                icon = Icons.Default.Nfc,
                supporting = "Tap a tag to start an album or playlist on a speaker",
                onClick = { onSelect(SettingsCategory.NFC_TAGS) }
            )
        }
        SettingsSectionDivider()
        SettingsSectionHeader("Discovery")
        SettingsNavigationRow(
            title = "Recommendations",
            icon = Icons.Default.Explore,
            supporting = "Personalized music discovery and genre enrichment",
            onClick = { onSelect(SettingsCategory.RECOMMENDATIONS) }
        )
        SettingsSectionDivider()
        SettingsSectionHeader("App")
        ThemeRow(viewModel)
        SettingsNavigationRow(
            title = "Diagnostics",
            icon = Icons.Default.BugReport,
            supporting = "Versions, logs, and battery optimization",
            onClick = { onSelect(SettingsCategory.DIAGNOSTICS) }
        )
        SettingsNavigationRow(
            title = "About",
            icon = Icons.Default.Info,
            supporting = "Version, updates, and what's new",
            onClick = { onSelect(SettingsCategory.ABOUT) }
        )
    }
}

@Composable
private fun ThemeRow(viewModel: SettingsViewModel) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle(initialValue = "auto")
    SettingsChoiceRow(
        title = "Theme",
        icon = Icons.Default.Palette,
        options = THEME_OPTIONS,
        selectedValue = themeMode,
        onSelect = viewModel::setThemeMode
    )
}

/** The stored theme values with their labels. */
private val THEME_OPTIONS = listOf(
    QueueConfigOption(value = "auto", title = "Auto", description = "Follows the system setting"),
    QueueConfigOption(value = "dark", title = "Dark"),
    QueueConfigOption(value = "light", title = "Light")
)

/**
 * The scrolling column every settings page here is drawn in. It adds no side padding,
 * because the rows carry `ListItem`'s own inset and anything that is not a row aligns
 * with their text through [SETTINGS_TEXT_INSET], or with their icons through
 * [SETTINGS_ROW_INSET].
 */
@Composable
internal fun SettingsScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = SETTINGS_SCREEN_BOTTOM_PADDING),
        content = content
    )
}

// endregion

// region Connection Screen

@Composable
private fun ConnectionScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    SettingsScreenColumn(modifier = modifier) {
        ServerSection(viewModel = viewModel, connectionState = connectionState)
        SettingsSectionDivider()
        ClientCertSection(viewModel = viewModel)
    }
}

@Composable
private fun PhoneAsSpeakerScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isConnected = connectionState is ConnectionState.Connected

    SettingsScreenColumn(modifier = modifier) {
        if (isConnected) {
            SendspinSection(viewModel = viewModel)
            // The sub-settings only affect the phone-as-speaker output, so show
            // them only while it is enabled (consistent gating for all of them).
            val sendspinEnabled by viewModel.sendspinEnabled.collectAsStateWithLifecycle()
            if (sendspinEnabled) {
                SettingsSectionDivider()
                AudioSection(viewModel = viewModel)
                SettingsSectionDivider()
                CarAudioSection(viewModel = viewModel)
                if (BuildConfig.DEBUG) {
                    SettingsSectionDivider()
                    SyncProbeSection(viewModel = viewModel)
                }
            }
        } else {
            Text(
                "Connect to your Music Assistant server first to enable Phone as speaker.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SETTINGS_ROW_INSET, vertical = 16.dp)
            )
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
    SettingsScreenColumn(modifier = modifier) {
        LearningSection(viewModel = viewModel, onOpenInsights = onOpenInsights)
        SettingsSectionDivider()
        SmartMixTuningSection(viewModel = viewModel)
    }
}

// endregion

// region About Screen

/**
 * Which app this is, which version, and the two things that change the version.
 *
 * Rows and dividers rather than cards, the same shape the settings category list has.
 * Everything here used to sit in its own grey card, and on a palette with no colour in it
 * those cards read as one grey block instead of as separate groups. Logs, versions and
 * battery optimization moved out to Diagnostics.
 */
@Composable
private fun AboutScreen(viewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val updateUiState by viewModel.updateUiState.collectAsStateWithLifecycle()
    val whatsNew by viewModel.whatsNew.collectAsStateWithLifecycle()

    SettingsScreenColumn(modifier = modifier) {
        AboutHero()
        // The in-app updater is github-flavor only; F-Droid handles updates itself.
        if (net.asksakis.massdroidv2.BuildConfig.ENABLE_UPDATE_CHECK) {
            // No row for beta updates: the setting is honoured by the checker, but the
            // only prerelease on the repo is dev-latest, whose tag is not a version the
            // comparison can read, so the switch had nothing to offer.
            UpdateCheckItem(
                state = updateUiState,
                onCheck = { viewModel.checkForUpdates(force = true) }
            )
            SettingsSectionDivider()
        }
        // The entries in place rather than behind a row. The sheet still interrupts once
        // after an update, and this is the same list, so there is nothing to tap to read
        // it again. Nothing is drawn until the notes have been read off the assets, which
        // is a file read and not a request, so the wait is not worth a placeholder.
        whatsNew?.let { news ->
            // A section header like every other group on the settings screens, so the
            // support button stays the only accented element on About.
            SettingsSectionHeader("New in ${news.versionName}")
            WhatsNewItems(
                release = news,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/**
 * The mark, the name, the version and the one ask, centred at the top of About.
 *
 * The same shape the Phylax about screen uses, and for the same reason: the first thing
 * on the screen should say which app this is and what version, and the support button is
 * the only thing here anybody is being asked for. It is also the only place the support
 * link appears, so it carries the Buy Me a Coffee orange rather than a theme colour.
 */
@Composable
private fun AboutHero() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.logo_md),
            contentDescription = null,
            modifier = Modifier.size(ABOUT_LOGO_SIZE)
        )
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            text = "${net.asksakis.massdroidv2.BuildConfig.VERSION_NAME} " +
                "(${net.asksakis.massdroidv2.BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MdButton(
            onClick = { launchCustomTab(context, SUPPORT_URL) },
            colors = ButtonDefaults.buttonColors(
                containerColor = SUPPORT_ORANGE,
                contentColor = SUPPORT_ON_ORANGE
            )
        ) {
            Icon(Icons.Filled.Coffee, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Buy me a coffee")
        }
    }
}

@Composable
private fun UpdateCheckItem(state: UpdateUiState, onCheck: () -> Unit) {
    val busy = state.isChecking || state.isDownloading
    Column {
        SettingsRow(
            title = when {
                state.isDownloading -> "Downloading ${state.downloadProgress ?: 0}%"
                state.isChecking -> "Checking for updates"
                else -> "Check for updates"
            },
            icon = Icons.Default.SystemUpdate,
            supporting = state.message ?: "Current version ${state.appVersion}",
            // No click while busy, but not dimmed either: the row is reporting progress,
            // not refusing.
            onClick = onCheck.takeUnless { busy },
            trailing = if (state.isChecking) {
                { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else {
                null
            }
        )
        if (state.isDownloading) {
            LinearProgressIndicator(
                progress = { (state.downloadProgress ?: 0) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    // Under the row's text rather than its icon, as the detail of a row is.
                    .padding(start = SETTINGS_TEXT_INSET, end = SETTINGS_ROW_INSET, top = 4.dp, bottom = 4.dp)
            )
        }
    }
}

/** Matches the mark size the Phylax about screen uses. */
private val ABOUT_LOGO_SIZE = 96.dp

/** One address, used by the single support button in the hero. */
private const val SUPPORT_URL = "https://www.buymeacoffee.com/sfortis"

/**
 * The Buy Me a Coffee brand orange, and a dark ink that reads on it.
 *
 * Fixed rather than taken from the theme, in both light and dark: the palette in Theme.kt
 * is grayscale from end to end, so a theme colour would make this button look like every
 * other control on the screen.
 */
private val SUPPORT_ORANGE = Color(0xFFFF813F)
private val SUPPORT_ON_ORANGE = Color(0xFF1F1F1F)

// endregion

// region Sections

/**
 * Where the connection to the server stands, as a row. An error names itself in the title
 * and carries the server's message underneath; otherwise [detail] fills that line. This is
 * the one place a connection error is shown: the sign-in form under it shows only its own
 * validation errors, so the server's message never appears twice.
 *
 * A live connection is said with a badge rather than a colour, because the palette has no
 * colour for a good state.
 */
@Composable
private fun ConnectionStatusRow(connectionState: ConnectionState, detail: String? = null) {
    SettingsRow(
        title = when (connectionState) {
            is ConnectionState.Connected -> "Music Assistant v${connectionState.serverInfo.serverVersion}"
            is ConnectionState.Connecting -> "Connecting"
            is ConnectionState.Error -> "Couldn't connect"
            is ConnectionState.Disconnected -> "Disconnected"
        },
        icon = when (connectionState) {
            is ConnectionState.Connected -> Icons.Default.Cloud
            is ConnectionState.Connecting -> Icons.Default.CloudSync
            else -> Icons.Default.CloudOff
        },
        supporting = (connectionState as? ConnectionState.Error)?.message ?: detail,
        tone = if (connectionState is ConnectionState.Error) SettingsTone.ERROR else SettingsTone.NORMAL,
        badge = SettingsBadge("Connected").takeIf { connectionState is ConnectionState.Connected }
    )
}

/**
 * The server this app talks to: its status, then either who is signed in or the form to
 * sign in with.
 */
@Composable
private fun ServerSection(
    viewModel: SettingsViewModel,
    connectionState: ConnectionState
) {
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val isConnected = connectionState is ConnectionState.Connected
    // Held here rather than in the form: the form leaves the composition while connected,
    // and what was typed into it must still be there when it comes back after a disconnect.
    val draft = rememberSignInDraft()

    // Surface OAuth errors as login errors so the existing error row picks them up.
    // Collected here rather than in the form, so it runs whatever the connection state.
    LaunchedEffect(Unit) {
        viewModel.oauthErrors.collect { msg -> viewModel.setLoginError(msg) }
    }

    SettingsSectionHeader("Server")
    // The URL cannot be edited while connected, so it is the status row's second line.
    ConnectionStatusRow(connectionState = connectionState, detail = serverUrl.takeIf { isConnected })
    if (isConnected) {
        // Saveable so the question is still open after a rotation. Signing out cannot be
        // undone from here, because the password has to be typed again.
        var confirmingSignOut by rememberSaveable { mutableStateOf(false) }
        SettingsRow(
            title = "Signed in as ${currentUser?.username ?: "unknown"}",
            icon = Icons.Default.Person,
            supporting = currentUser?.authMethod?.let { "via $it" }
        )
        SettingsRow(
            title = "Sign out",
            icon = Icons.AutoMirrored.Filled.Logout,
            supporting = "Forgets your sign-in for this server and keeps its URL",
            onClick = { confirmingSignOut = true },
            tone = SettingsTone.DESTRUCTIVE
        )
        if (confirmingSignOut) {
            SettingsConfirmDialog(
                title = "Sign out?",
                text = "You will need to sign in again to use the server.",
                confirmLabel = "Sign out",
                onConfirm = {
                    confirmingSignOut = false
                    viewModel.signOut()
                },
                onDismiss = { confirmingSignOut = false }
            )
        }
    } else {
        SignInForm(
            viewModel = viewModel,
            connectionState = connectionState,
            serverUrl = serverUrl,
            draft = draft
        )
    }
}

/**
 * The editable URL and the sign-in options, shown while disconnected (or connecting, or
 * failed). A form is not a row, so it sits in the section aligned with the rows through
 * [SETTINGS_ROW_INSET] rather than in a container of its own.
 */
@Composable
private fun SignInForm(
    viewModel: SettingsViewModel,
    connectionState: ConnectionState,
    serverUrl: String,
    draft: SignInDraft
) {
    val authToken by viewModel.authToken.collectAsStateWithLifecycle()
    val loginError by viewModel.loginError.collectAsStateWithLifecycle()
    val savedUsername by viewModel.savedUsername.collectAsStateWithLifecycle()
    val savedPassword by viewModel.savedPassword.collectAsStateWithLifecycle()

    // An untouched field shows the saved value, which also covers the saved values arriving
    // a frame after the first composition.
    val editUrl = draft.url ?: TextFieldValue(serverUrl, TextRange(serverUrl.length))
    val username = draft.username ?: savedUsername
    val password = draft.password ?: savedPassword
    val hasToken = authToken.isNotBlank()

    // Auth providers exposed by the server (only HA OAuth is treated specially;
    // the built-in credentials path always falls through).
    val providers by viewModel.availableAuthProviders.collectAsStateWithLifecycle()
    val oauthInProgress by viewModel.oauthInProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Probe the server's providers as the URL stabilises (debounced). The form is only
    // composed while disconnected, so there is no connected state to skip.
    LaunchedEffect(editUrl.text) {
        if (editUrl.text.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        viewModel.probeAuthProviders(editUrl.text)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SETTINGS_ROW_INSET, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ServerUrlField(value = editUrl, onValueChange = { draft.url = it })

        if (providers.any { it.isHomeAssistant }) {
            HomeAssistantSignIn(
                inProgress = oauthInProgress,
                onClick = {
                    viewModel.clearLoginError()
                    coroutineScope.launch {
                        val authUrl = viewModel.startHomeAssistantOAuth(editUrl.text)
                        if (authUrl != null) launchCustomTab(context, authUrl)
                    }
                }
            )
        }

        CredentialFields(
            username = username,
            onUsernameChange = {
                draft.username = it
                viewModel.clearLoginError()
            },
            password = password,
            onPasswordChange = {
                draft.password = it
                viewModel.clearLoginError()
            }
        )

        // Only the form's own errors (validation, Home Assistant sign-in). A connection
        // error is already in the status row above the form.
        loginError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        SignInButton(
            connecting = connectionState is ConnectionState.Connecting,
            onClick = {
                if (hasToken && username.isBlank() && password.isBlank()) {
                    viewModel.connectWithToken(editUrl.text)
                } else {
                    viewModel.login(editUrl.text, username, password)
                }
            }
        )
    }
}

/**
 * What has been typed into the sign-in form. Each value is null until its field is edited,
 * and an untouched field shows the saved value instead. Seeding the fields from the saved
 * values at the first composition would capture the empty values the flows start with.
 */
@Stable
private class SignInDraft(
    url: MutableState<TextFieldValue?>,
    username: MutableState<String?>,
    password: MutableState<String?>
) {
    var url by url
    var username by username
    var password by password
}

/**
 * A [SignInDraft] that survives a disconnect. The URL and the username also survive a
 * rotation and process recreation; the password does not, because saved instance state is
 * a Bundle the system may write to disk, and a typed password must not end up there.
 */
@Composable
private fun rememberSignInDraft(): SignInDraft {
    val url = rememberSaveable(stateSaver = OptionalTextFieldValueSaver) {
        mutableStateOf<TextFieldValue?>(null)
    }
    val username = rememberSaveable { mutableStateOf<String?>(null) }
    val password = remember { mutableStateOf<String?>(null) }
    return remember(url, username, password) { SignInDraft(url, username, password) }
}

/** [TextFieldValue.Saver] for a value that may be absent, which is saved as nothing. */
private val OptionalTextFieldValueSaver: Saver<TextFieldValue?, Any> = Saver(
    save = { value -> value?.let { with(TextFieldValue.Saver) { save(it) } } },
    restore = { TextFieldValue.Saver.restore(it) }
)

/** The server URL, with one-tap fixes when the scheme is missing. */
@Composable
private fun ServerUrlField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit) {
    val urlMissingScheme = value.text.isNotBlank() && !value.text.contains("://")
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
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
            listOf("http://", "https://").forEach { scheme ->
                androidx.compose.material3.SuggestionChip(
                    onClick = {
                        val next = applyUrlScheme(scheme, value.text)
                        onValueChange(TextFieldValue(next, TextRange(next.length)))
                    },
                    label = { Text("Use $scheme") }
                )
            }
        }
    }
}

@Composable
private fun HomeAssistantSignIn(inProgress: Boolean, onClick: () -> Unit) {
    MdButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = !inProgress
    ) {
        if (inProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Waiting for sign in")
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

@Composable
private fun CredentialFields(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit
) {
    var showPassword by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text("Username") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
        modifier = Modifier.fillMaxWidth()
            .semantics { contentType = ContentType.Username }
    )
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
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
                    contentDescription = if (showPassword) "Hide password" else "Show password"
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
            .semantics { contentType = ContentType.Password }
    )
}

@Composable
private fun SignInButton(connecting: Boolean, onClick: () -> Unit) {
    MdButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = !connecting
    ) {
        if (connecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Connecting")
        } else {
            Icon(Icons.Default.Login, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign in")
        }
    }
}

/**
 * The client certificate for servers behind mutual TLS. Choosing goes through the system
 * KeyChain picker, which preselects the current certificate when there is one.
 */
@Composable
private fun ClientCertSection(viewModel: SettingsViewModel) {
    val clientCertAlias by viewModel.clientCertAlias.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val chooseCertificate: (String?) -> Unit = { current ->
        (context as? Activity)?.let { activity ->
            KeyChain.choosePrivateKeyAlias(
                activity,
                { alias -> viewModel.onCertificateSelected(alias, context) },
                null, null, null, -1, current
            )
        }
    }

    SettingsSectionHeader("Client certificate (mTLS)")
    val alias = clientCertAlias
    // Saveable so the question is still open after a rotation. Held outside the branch so
    // it does not reset when the alias flow re-emits.
    var confirmingRemoval by rememberSaveable { mutableStateOf(false) }
    if (alias != null) {
        SettingsRow(
            title = "Certificate",
            icon = Icons.Default.VerifiedUser,
            supporting = alias,
            onClick = { chooseCertificate(alias) }
        )
        SettingsRow(
            title = "Remove certificate",
            icon = Icons.Default.RemoveCircleOutline,
            onClick = { confirmingRemoval = true },
            tone = SettingsTone.DESTRUCTIVE
        )
        if (confirmingRemoval) {
            SettingsConfirmDialog(
                title = "Remove the certificate?",
                text = "A server that requires a client certificate will refuse the connection " +
                    "until you select one again.",
                confirmLabel = "Remove",
                onConfirm = {
                    confirmingRemoval = false
                    viewModel.clearCertificate()
                },
                onDismiss = { confirmingRemoval = false }
            )
        }
    } else {
        SettingsRow(
            title = "Select certificate",
            icon = Icons.Default.VerifiedUser,
            supporting = "No certificate selected",
            onClick = { chooseCertificate(null) }
        )
    }
}

/**
 * What the app learns from listening: the switch, the screen that shows what it learned,
 * and genre enrichment while it runs.
 */
@Composable
private fun LearningSection(viewModel: SettingsViewModel, onOpenInsights: () -> Unit) {
    val smartListeningEnabled by viewModel.smartListeningEnabled.collectAsStateWithLifecycle()

    SettingsSectionHeader("Learning")
    SettingsSwitchRow(
        title = "Smart Listening",
        icon = Icons.Default.Psychology,
        checked = smartListeningEnabled,
        onCheckedChange = { viewModel.toggleSmartListening(it) },
        supporting = "Learns from skip/like/listen actions and improves recommendations"
    )
    SettingsNavigationRow(
        title = "Recommendation insights",
        icon = Icons.Default.Insights,
        supporting = if (smartListeningEnabled) {
            "Score stats, blocked artists, and recommendation DB actions"
        } else {
            "Enable Smart Listening first"
        },
        onClick = onOpenInsights,
        enabled = smartListeningEnabled
    )
    GenreEnrichmentRow(viewModel = viewModel)
}

@Composable
private fun SmartMixTuningSection(viewModel: SettingsViewModel) {
    val variety by viewModel.smartMixVariety.collectAsStateWithLifecycle()
    val discovery by viewModel.smartMixDiscovery.collectAsStateWithLifecycle()
    val length by viewModel.smartMixLength.collectAsStateWithLifecycle()
    val strictness by viewModel.smartMixStrictness.collectAsStateWithLifecycle()

    SettingsSectionHeader("Smart Mix tuning")
    // Sliders are not rows, so they sit without a container, indented to the text of the
    // icon rows around them.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SETTINGS_TEXT_INSET, end = SETTINGS_ROW_INSET, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LabeledSlider(
            title = "Variety",
            // Describes what the knob actually drives: which seed the mix is
            // built around, and how likely it is to leave the kind of music
            // the recent mixes were. It used to promise rotation of "tracks
            // from the same artists", which is a different thing and is not
            // what any of it does.
            description = "How likely each new mix is to move to a different kind of music than the last ones",
            value = variety,
            onValueChangeFinished = viewModel::setSmartMixVariety,
            valueLabel = { v ->
                when {
                    v < 0.33f -> "Low: stays close to your recent mixes"
                    v < 0.66f -> "Medium: balanced"
                    // Not "a different sound each run": this is the chance of
                    // ASKING for a move, and the request is dropped when it
                    // would leave too few seeds to choose from.
                    else -> "High: usually a different sound"
                }
            }
        )
        LabeledSlider(
            title = "Discovery",
            description = "How far the mix ventures from your familiar artists and genres",
            value = discovery,
            onValueChangeFinished = viewModel::setSmartMixDiscovery,
            valueLabel = { v ->
                when {
                    v < 0.33f -> "Low: comfort, closest to your taste"
                    v < 0.66f -> "Medium: some adjacent artists"
                    else -> "High: more new and adjacent artists"
                }
            }
        )
        LabeledSlider(
            title = "Length",
            description = "Roughly how many tracks each Smart Mix aims for",
            value = length,
            onValueChangeFinished = viewModel::setSmartMixLength,
            valueLabel = { v -> "~${smartMixTrackTarget(v)} tracks" }
        )
        LabeledSlider(
            title = "Strictness",
            description = "Which of your tracks are allowed to seed a mix, by how much you like them",
            value = strictness,
            onValueChangeFinished = viewModel::setSmartMixStrictness,
            valueLabel = { v ->
                when {
                    v < 0.33f -> "Low: anything you've recently played"
                    v < 0.66f -> "Medium: tracks you like"
                    else -> "High: only your most-loved tracks"
                }
            }
        )
    }
    // A row like every other action on these screens, and not destructive: it only puts
    // the four sliders back where they started.
    SettingsRow(
        title = "Reset to defaults",
        icon = Icons.Default.RestartAlt,
        supporting = "Puts the four sliders back to their starting values",
        onClick = viewModel::resetSmartMixTuning
    )
}

// Mirrors the DiscoverViewModel length mapping so the label matches the actual
// target (20..60 tracks, 40 at the neutral default).
// Delegates to the engine so the "~N tracks" label cannot drift from what a
// build actually targets.
private fun smartMixTrackTarget(length: Float): Int = smartMixTrackTargetFor(length.toDouble())

/**
 * Progress while genres are being filled in, and nothing at all otherwise.
 *
 * There used to be an API key to paste here. Now that genres come from Music
 * Assistant and MusicBrainz there is nothing to configure, and a row whose only
 * content is a sentence saying so is worse than no row: it takes up a settings
 * slot to tell the reader they have no decision to make.
 */
@Composable
private fun GenreEnrichmentRow(viewModel: SettingsViewModel) {
    val progress by viewModel.enrichmentProgress.collectAsStateWithLifecycle()
    if (!progress.isRunning && progress.total == 0) return

    SettingsRow(
        title = if (progress.isRunning) "Enriching genres" else "Genre enrichment complete",
        icon = Icons.Default.Category,
        supporting = if (progress.isRunning) {
            "${progress.processed}/${progress.total} (${progress.enriched} new)"
        } else {
            "${progress.enriched} artists enriched"
        },
        trailing = if (progress.isRunning) {
            { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
        } else {
            null
        }
    )
}

private fun hasBtConnectPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED

/** Paired BT audio devices as route keys ("bt:NAME"), matching the runtime route key. */
private fun pairedBtAudioRouteKeys(context: Context): List<String> {
    if (!hasBtConnectPermission(context)) return emptyList()
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        ?: return emptyList()
    return runCatching {
        adapter.bondedDevices.orEmpty()
            .filter {
                val cls = it.bluetoothClass
                cls != null && (
                    cls.hasService(BluetoothClass.Service.AUDIO) ||
                        cls.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
                    )
            }
            .mapNotNull { dev -> dev.name?.takeIf { it.isNotBlank() }?.let { "bt:$it" } }
    }.getOrDefault(emptyList())
}

@Composable
private fun SendspinSection(viewModel: SettingsViewModel) {
    val sendspinEnabled by viewModel.sendspinEnabled.collectAsStateWithLifecycle()
    val sendspinState by viewModel.sendspinState.collectAsStateWithLifecycle()

    SettingsSectionHeader("Sendspin")
    SettingsSwitchRow(
        title = "Enable",
        icon = Icons.Default.Speaker,
        checked = sendspinEnabled,
        onCheckedChange = { viewModel.toggleSendspin(it) },
        supporting = when (sendspinState) {
            // The badge says it streams; the line says what that means.
            SendspinState.STREAMING -> "This phone plays as a speaker"
            SendspinState.SYNCING -> "Ready"
            SendspinState.HANDSHAKING -> "Handshaking"
            SendspinState.AUTHENTICATING -> "Authenticating"
            SendspinState.CONNECTING -> "Connecting"
            // The manager exposes the state only, not why it failed.
            SendspinState.ERROR -> "Couldn't connect to the server"
            SendspinState.DISCONNECTED -> if (sendspinEnabled) "Stopped" else "Disabled"
        },
        tone = if (sendspinState == SendspinState.ERROR) SettingsTone.ERROR else SettingsTone.NORMAL,
        badge = SettingsBadge("Streaming").takeIf { sendspinState == SendspinState.STREAMING }
    )
}

/**
 * What the phone plays and how it is processed: the stream format, the compressor and
 * dithering. All three act on this phone's own output only.
 */
@Composable
private fun AudioSection(viewModel: SettingsViewModel) {
    val dither by viewModel.sendspinDither.collectAsStateWithLifecycle()

    SettingsSectionHeader("Audio", caption = "Applies to this phone's output")
    OutputQualityRow(viewModel = viewModel)
    CompressorControl(viewModel = viewModel)
    SettingsSwitchRow(
        title = "Output dithering",
        icon = Icons.Default.Grain,
        checked = dither,
        onCheckedChange = { viewModel.setSendspinDither(it) },
        supporting = "Noise-shaped dither on the 16-bit output for smoother quiet passages, fades and decays"
    )
}

@Composable
private fun OutputQualityRow(viewModel: SettingsViewModel) {
    val formatStr by viewModel.sendspinAudioFormat.collectAsStateWithLifecycle()
    val current = SendspinAudioFormat.fromStored(formatStr)
    SettingsChoiceRow(
        title = "Output quality",
        icon = Icons.Default.HighQuality,
        options = OUTPUT_FORMATS.map {
            QueueConfigOption(value = it.name, title = it.label, description = it.outputDescription())
        },
        selectedValue = current.name,
        onSelect = { value ->
            OUTPUT_FORMATS.firstOrNull { it.name == value }?.let { viewModel.setSendspinAudioFormat(it) }
        }
    )
}

/** The formats offered for the phone's own output, in the order they are listed. */
private val OUTPUT_FORMATS = listOf(
    SendspinAudioFormat.AUTOMATIC,
    SendspinAudioFormat.FLAC,
    SendspinAudioFormat.OPUS,
    SendspinAudioFormat.PCM
)

private fun SendspinAudioFormat.outputDescription(): String = when (this) {
    SendspinAudioFormat.AUTOMATIC -> "Adapts to the network: FLAC on Wi-Fi, Opus on mobile data"
    SendspinAudioFormat.FLAC -> "Lossless. Highest fidelity, higher bandwidth."
    SendspinAudioFormat.OPUS -> "Lossy and efficient. Ideal on mobile data."
    SendspinAudioFormat.PCM -> "Uncompressed, no decode step. Highest bandwidth."
}

/**
 * The compressor level: a row naming the level, with the slider and the level's
 * description under it, indented to the row's text.
 */
@Composable
private fun CompressorControl(viewModel: SettingsViewModel) {
    val compressorLevel by viewModel.sendspinCompressorLevel.collectAsStateWithLifecycle()
    val compNames = listOf("Off", "Soft", "Medium", "Hard")
    val compDescriptions = listOf(
        "No processing. Full original dynamic range.",
        "Light leveling. Evens the volume and lifts quiet detail while keeping most dynamics.",
        "Moderate leveling. A consistent level across quiet and loud passages.",
        "Heavy leveling. A tight, dense level for noisy rooms or low-volume listening."
    )
    // Not a LabeledSlider: the level name sits in an icon row like the neighbouring
    // settings, and both it and the description update live while dragging. The
    // description sits below the slider so its length never shifts the slider.
    var sliderPos by remember(compressorLevel) { mutableFloatStateOf(compressorLevel.toFloat()) }
    val liveLevel = sliderPos.roundToInt().coerceIn(0, 3)
    SettingsRow(
        title = "Sound compressor",
        icon = Icons.Default.Compress,
        trailing = {
            Text(
                compNames[liveLevel],
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SETTINGS_TEXT_INSET, end = SETTINGS_ROW_INSET, bottom = 8.dp)
    ) {
        MdSlider(
            value = sliderPos,
            onValueChange = { sliderPos = it },
            onValueChangeFinished = { viewModel.setSendspinCompressorLevel(sliderPos.roundToInt()) },
            valueRange = 0f..3f,
            steps = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            compDescriptions[liveLevel],
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Per-device "full volume on connect (car audio)", separate from the main Sendspin
 * enable toggle. Only relevant while phone-as-speaker is on, so the section is hidden
 * when Sendspin is disabled. A flagged BT device is pinned to STREAM_MUSIC 100% on
 * connect (its own dial does the attenuation) and left alone.
 */
@Composable
private fun CarAudioSection(viewModel: SettingsViewModel) {
    val knownBtDevices by viewModel.knownBtDevices.collectAsStateWithLifecycle(initialValue = emptySet())
    val carAudioBtDevices by viewModel.carAudioBtDevices.collectAsStateWithLifecycle(initialValue = emptySet())
    val context = LocalContext.current
    var hasBtPerm by remember { mutableStateOf(hasBtConnectPermission(context)) }
    val btPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasBtPerm = granted }
    val pairedKeys = remember(hasBtPerm) { if (hasBtPerm) pairedBtAudioRouteKeys(context) else emptyList() }
    val selectedCount = carAudioBtDevices.size

    SettingsSectionHeader("Bluetooth")
    // Collapsed by default so the (potentially long) paired-device list does
    // not dominate the screen; the row expands it.
    SettingsExpandableRow(
        title = "Full volume on connect (car audio)",
        icon = Icons.Default.DirectionsCar,
        supporting = if (selectedCount > 0) {
            "$selectedCount device${if (selectedCount == 1) "" else "s"} selected"
        } else {
            "Pins the phone output to 100% on devices with their own volume dial"
        }
    ) {
        // Paired BT audio devices (needs BLUETOOTH_CONNECT) unioned with the
        // ones the app has already routed to, plus anything already flagged.
        val displayDevices = (pairedKeys + knownBtDevices + carAudioBtDevices).distinct().sorted()
        displayDevices.forEach { key ->
            DetailSwitchRow(
                title = key.removePrefix("bt:"),
                checked = key in carAudioBtDevices,
                onCheckedChange = { viewModel.setCarAudioBtDevice(key, it) }
            )
        }
        if (hasBtPerm && displayDevices.isEmpty()) {
            Text(
                "No paired Bluetooth audio devices found.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
    // A row of the section rather than a line inside the detail above: a settings row in
    // the detail would add ListItem's inset a second time. Placed here it is also visible
    // without expanding, which is where the reason the list is short belongs.
    if (!hasBtPerm) {
        SettingsRow(
            title = "Show paired Bluetooth devices",
            icon = Icons.Default.Bluetooth,
            supporting = "Allow the Bluetooth permission to pick your car from the paired list",
            onClick = { btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
            tone = SettingsTone.ERROR
        )
    }
}

/**
 * An on/off value inside the detail of a [SettingsExpandableRow]. A full settings row
 * there would add `ListItem`'s inset a second time, so this is the compact form. As in
 * [SettingsSwitchRow], the whole line toggles, the switch only shows the state, and the
 * toggle gives the same haptic tick.
 */
@Composable
private fun DetailSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(it)
            }),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // bodyLarge like the title of a settings row, so a device in the list reads at the
        // same size as the row it expanded from.
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * Debug builds only: records the room and shows where each audible speaker
 * plays relative to the server timestamp (see SyncProbe in :core).
 */
@Composable
private fun SyncProbeSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val state by viewModel.syncProbe.collectAsStateWithLifecycle()
    var permissionRefused by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionRefused = !granted
        if (granted) viewModel.runSyncProbe()
    }
    val running = state == SettingsViewModel.SyncProbeUiState.Running
    val startProbe: () -> Unit = {
        val missing = AppPermissions.missing(context, AppPermissions.acousticCalibrationRequired())
        if (missing.isEmpty()) viewModel.runSyncProbe()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    SettingsSectionHeader("Debug")
    SettingsRow(
        title = "Measure group sync",
        icon = Icons.Default.Mic,
        supporting = when {
            running -> "Listening for 10 seconds. Keep the phone near the speakers."
            permissionRefused -> "Couldn't start. The microphone permission was refused."
            else -> "Records the room and shows where each speaker plays against the server timestamp"
        },
        // No click while listening, but not dimmed: the row is reporting progress.
        onClick = startProbe.takeUnless { running },
        trailing = if (running) {
            { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
        } else {
            null
        }
    )
    (state as? SettingsViewModel.SyncProbeUiState.Finished)?.let { finished ->
        SyncProbeResultDialog(outcome = finished.outcome, onDismiss = viewModel::dismissSyncProbe)
    }
}

@Composable
private fun SyncProbeResultDialog(outcome: SyncProbeOutcome, onDismiss: () -> Unit) {
    fun ms(us: Long): String = "%+.1f ms".format(us / 1000.0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group sync") },
        text = {
            when (outcome) {
                is SyncProbeOutcome.Failure -> Text(outcome.reason)
                is SyncProbeOutcome.Success -> {
                    val result = outcome.result
                    val model = result.model
                    val detail = MaterialTheme.typography.bodySmall
                    val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("This phone should play at ${ms(model.expectedLagUs)}.")
                        if (result.analysis.peaks.isEmpty()) {
                            Text("No speaker matched the stream.")
                        }
                        result.analysis.peaks.forEach { peak ->
                            Text("Speaker at ${ms(peak.lagUs)}, strength ${"%.2f".format(peak.strength)}")
                        }
                        Text(
                            "Clarity ${"%.1f".format(result.analysis.clarity)}; below 5 the peaks are noise.",
                            style = detail, color = detailColor
                        )
                        Text(
                            "Headroom ${model.headroomUs / 1000} ms, sync delay ${model.syncDelayUs / 1000} ms, " +
                                "acoustic correction ${model.acousticCorrectionUs / 1000} ms.",
                            style = detail, color = detailColor
                        )
                        Text(
                            "Output latency ${model.reportedOutputLatencyUs / 1000} ms reported, " +
                                "${model.fullOutputLatencyUs / 1000} ms full. " +
                                "Native drift ${model.nativeDriftUs / 1000.0} ms.",
                            style = detail, color = detailColor
                        )
                        Text(
                            "${model.codec} ${model.sampleRate} Hz, microphone ${result.micSource}, " +
                                "timestamp spread ${result.micTimestampSpreadUs / 1000.0} ms, " +
                                "${result.analysis.overlapUs / 1000} ms analysed.",
                            style = detail, color = detailColor
                        )
                    }
                }
            }
        },
        confirmButton = { MdTextButton(onClick = onDismiss) { Text("Close") } }
    )
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
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version ${updateInfo.version} is available${if (fileSizeMb > 0) " (${fileSizeMb} MB)" else ""}.")
                Text(
                    updateInfo.releaseNotes.take(500).ifBlank { "No release notes." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        // Not a SettingsConfirmDialog, because the release notes do not fit its one
        // sentence, but the same buttons: the action and Cancel.
        confirmButton = {
            MdTextButton(onClick = onConfirm, enabled = !busy) { Text("Install") }
        },
        dismissButton = {
            MdTextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        }
    )
}

// endregion
