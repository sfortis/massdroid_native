package net.asksakis.massdroidv2.ui.components

import android.app.Activity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import android.content.Context
import android.security.KeyChain
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.signin.SavedSignIn
import net.asksakis.massdroidv2.data.signin.SignInController
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.asksakis.massdroidv2.domain.connection.ChosenServer
import net.asksakis.massdroidv2.domain.connection.PortChoice
import net.asksakis.massdroidv2.domain.connection.ServerAddressInput
import net.asksakis.massdroidv2.domain.connection.ServerCheck
import net.asksakis.massdroidv2.domain.connection.ServerRow
import net.asksakis.massdroidv2.domain.connection.ServerRowGroup
import net.asksakis.massdroidv2.domain.connection.displayAddressOf

/** Saves what is typed under Other server across a rotation. */
val ServerAddressInputSaver = listSaver<ServerAddressInput, String>(
    save = { listOf(it.address, it.port, it.portEdited.toString(), it.https.toString()) },
    restore = { ServerAddressInput(it[0], it[1], it[2].toBoolean(), it[3].toBoolean()) }
)

/**
 * Step 1 of the sign-in: the server used last, the servers found on this network, and Other
 * server for an address of the user's own. Rows, so they line up with the rows around them.
 */
@Composable
fun ServerList(
    rows: List<ServerRow>,
    searching: Boolean,
    onPick: (ServerRow) -> Unit,
    onSearchAgain: () -> Unit,
    onOther: () -> Unit,
    onForget: ((ServerRow) -> Unit)? = null,
    largeTargets: Boolean = false
) {
    val lastUsed = rows.filter { it.group == ServerRowGroup.LAST_USED }
    val found = rows.filter { it.group == ServerRowGroup.ON_THIS_NETWORK }
    // A long press on a server used before offers to forget it. Saved by address, so the
    // question survives a rotation.
    var forgettingUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val forgetAction: (ServerRow) -> (() -> Unit)? = { row ->
        { forgettingUrl = row.url }.takeIf { onForget != null && row.canForget }
    }
    if (lastUsed.isNotEmpty()) {
        ServerListHeader("Recently used")
        lastUsed.forEach { row -> ServerListRow(row, onPick, onLongPress = forgetAction(row)) }
    }
    rows.firstOrNull { it.url == forgettingUrl }?.let { row ->
        ForgetServerDialog(
            name = row.name,
            onConfirm = {
                onForget?.invoke(row)
                forgettingUrl = null
            },
            onDismiss = { forgettingUrl = null }
        )
    }
    ServerListHeader("On this network")
    when {
        found.isNotEmpty() -> found.forEach { row -> ServerListRow(row, onPick, onLongPress = forgetAction(row)) }
        searching -> SearchingForServers(Modifier.padding(horizontal = SETTINGS_ROW_INSET, vertical = 8.dp))
        else -> NoServerFound(
            onSearchAgain,
            Modifier.padding(horizontal = SETTINGS_ROW_INSET, vertical = 8.dp),
            largeTarget = largeTargets
        )
    }
    SettingsRow(title = "Other server", icon = Icons.Default.Add, onClick = onOther)
}

/** The first seconds of a search of the network, before an empty list means anything. */
@Composable
fun SearchingForServers(modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Looking for Music Assistant on this network",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A search that found nothing, and why that is expected away from the server's network. The
 * search goes on in the background, and a server it finds later replaces this.
 */
@Composable
fun NoServerFound(onSearchAgain: () -> Unit, modifier: Modifier = Modifier, largeTarget: Boolean = false) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("No server found on this network", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = NO_SERVER_FOUND_HINT,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MdOutlinedButton(
            onClick = onSearchAgain,
            // A car screen needs a target that is easy to hit while parked.
            modifier = if (largeTarget) {
                Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = CAR_TOUCH_TARGET)
            } else {
                Modifier.padding(top = 4.dp)
            }
        ) {
            Text("Search again")
        }
    }
}

private val CAR_TOUCH_TARGET = 64.dp

/** Why the network search can find nothing, and what to do instead. */
const val NO_SERVER_FOUND_HINT =
    "Servers are found only on the same Wi-Fi as Music Assistant. Away from home or on a VPN, use Other server."

@Composable
private fun ServerListHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = SETTINGS_ROW_INSET, end = SETTINGS_ROW_INSET, top = 12.dp)
    )
}

@Composable
private fun ServerListRow(row: ServerRow, onPick: (ServerRow) -> Unit, onLongPress: (() -> Unit)? = null) {
    SettingsRow(
        title = row.name,
        icon = when {
            row.group == ServerRowGroup.LAST_USED -> Icons.Default.History
            row.isHomeAssistantAddon -> Icons.Default.Home
            else -> Icons.Default.Dns
        },
        supporting = row.detail,
        modifier = if (onLongPress != null) Modifier.longPressable(onLongPress) { onPick(row) } else Modifier,
        onClick = { onPick(row) }.takeIf { onLongPress == null }
    )
}

/** A tap picks the row, and a long press, with a vibration only then, offers to forget it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.longPressable(onLongPress: () -> Unit, onClick: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    return combinedClickable(
        onClick = onClick,
        onLongClickLabel = "Forget this server",
        onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongPress()
        }
    )
}

@Composable
private fun ForgetServerDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forget this server?") },
        text = {
            Text(
                "MassDroid forgets $name and the sign-in it saved for it. " +
                    "To use it again, sign in with your username and password."
            )
        },
        confirmButton = { MdTextButton(onClick = onConfirm) { Text("Forget") } },
        dismissButton = { MdTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The server step 2 signs in to: its name, and under it whether it answered. [onChange] goes
 * back to step 1, and [onCheckAgain] asks a server that did not answer once more.
 */
@Composable
fun ChosenServerCard(
    server: ChosenServer,
    onChange: () -> Unit,
    onCheckAgain: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)
        ) {
            Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                ServerCheckLine(server.check)
            }
            Column(horizontalAlignment = Alignment.End) {
                MdTextButton(onClick = onChange) { Text("Change server") }
                if (server.check is ServerCheck.Problem) {
                    MdTextButton(onClick = onCheckAgain) { Text("Try again") }
                }
            }
        }
    }
}

/** One line that says what a server check found, in the colour of its outcome. */
@Composable
fun ServerCheckLine(check: ServerCheck, idleText: String? = null) {
    when (check) {
        ServerCheck.Idle -> idleText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ServerCheck.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Looking for Music Assistant",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is ServerCheck.Found -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Music Assistant ${check.version}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        is ServerCheck.Problem -> Text(
            check.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

/**
 * Other server: one address field, checked while the user types, and three connection chips
 * under it. [onCheck] receives every change, and the checker waits for the typing to pause.
 * [onUse] is enabled once Music Assistant answered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherServerSheet(
    check: ServerCheck,
    onCheck: (ServerAddressInput) -> Unit,
    onUse: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        sheetMaxWidth = SheetDefaults.maxWidth(),
        onDismissRequest = onDismiss,
        sheetState = SheetDefaults.sheetState(),
        containerColor = SheetDefaults.containerColor()
    ) {
        OtherServerForm(
            check = check,
            onCheck = onCheck,
            onUse = onUse,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        )
    }
}

/** The contents of [OtherServerSheet]. */
@Composable
fun OtherServerForm(
    check: ServerCheck,
    onCheck: (ServerAddressInput) -> Unit,
    onUse: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by rememberSaveable(stateSaver = ServerAddressInputSaver) { mutableStateOf(ServerAddressInput()) }
    // Custom stays chosen while its port field is open, even when the port typed there is 8095.
    var customPicked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(input) { onCheck(input) }
    val choice = if (customPicked) PortChoice.CUSTOM else input.portChoice

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SheetDefaults.HeaderTitle("Other server")
        OutlinedTextField(
            value = input.address,
            onValueChange = { input = input.withAddress(it) },
            label = { Text("Server address") },
            placeholder = { Text("homeassistant.local") },
            singleLine = true,
            isError = check is ServerCheck.Problem,
            supportingText = { ServerCheckLine(check, idleText = "For example homeassistant.local or 192.168.1.20") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )
        (check as? ServerCheck.Problem)?.suggestion?.let { url ->
            val address = displayAddressOf(url)
            MdOutlinedButton(
                onClick = {
                    customPicked = false
                    input = ServerAddressInput.fromUrl(url)
                }
            ) {
                Text("Use ${address.substringBeforeLast(':')}, port ${address.substringAfterLast(':')}")
            }
        }
        Text("Connection", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PortChip("MA port", choice == PortChoice.MUSIC_ASSISTANT) {
                customPicked = false
                input = input.withPortChoice(PortChoice.MUSIC_ASSISTANT)
            }
            PortChip("HTTP", choice == PortChoice.HTTP) {
                customPicked = false
                input = input.withPortChoice(PortChoice.HTTP)
            }
            PortChip("HTTPS", choice == PortChoice.HTTPS) {
                customPicked = false
                input = input.withPortChoice(PortChoice.HTTPS)
            }
            PortChip("Custom", choice == PortChoice.CUSTOM) { customPicked = true }
        }
        if (choice == PortChoice.CUSTOM) {
            OutlinedTextField(
                value = input.effectivePort,
                onValueChange = { typed ->
                    // Typing here keeps Custom chosen, so the field does not vanish when the
                    // port passes through 80, 8095 or 443 on the way to another number.
                    customPicked = true
                    input = input.withPort(typed.filter(Char::isDigit).take(PORT_DIGITS))
                },
                label = { Text("Port") },
                singleLine = true,
                supportingText = { Text("Music Assistant itself uses 8095") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
        MdButton(
            onClick = onUse,
            enabled = check is ServerCheck.Found,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Use this server") }
    }
}

@Composable
private fun RowScope.PortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
        modifier = Modifier.weight(1f)
    )
}

private const val PORT_DIGITS = 5

/**
 * What has been typed into the sign-in form. Each value is null until its field is edited,
 * and an untouched field shows the saved value instead. Seeding the fields from the saved
 * values at the first composition would capture the empty values the flows start with.
 */
@Stable
class SignInDraft(
    username: MutableState<String?>,
    password: MutableState<String?>
) {
    var username by username
    var password by password
}

/**
 * A [SignInDraft] for the composition it is called in. The username also survives a rotation
 * and process recreation; the password does not, because saved instance state is a Bundle the
 * system may write to disk, and a typed password must not end up there.
 */
@Composable
fun rememberSignInDraft(): SignInDraft {
    val username = rememberSaveable { mutableStateOf<String?>(null) }
    val password = remember { mutableStateOf<String?>(null) }
    return remember(username, password) { SignInDraft(username, password) }
}

/**
 * Step 2 of the sign-in: the chosen server on top, then only the ways to sign in to it.
 * Settings shows it under the server status, and the onboarding shows it under its own
 * heading, which is why [label] can be left out.
 */
@Composable
fun SignInStep(
    signIn: SignInController,
    server: ChosenServer,
    connecting: Boolean,
    draft: SignInDraft,
    onChangeServer: () -> Unit,
    onCheckAgain: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = "Sign in"
) {
    val error by signIn.error.collectAsStateWithLifecycle()
    val saved by remember(signIn, server) { signIn.savedSignIn(server) }
        .collectAsStateWithLifecycle(SavedSignIn.NONE)
    val providers by signIn.providers.collectAsStateWithLifecycle()
    val oauthInProgress by signIn.oauthInProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // An untouched field shows the saved value, which also covers the saved values arriving
    // a frame after the first composition.
    val username = draft.username ?: saved.username
    val password = draft.password ?: saved.password
    // The providers belong to the server checked last, which is this one once it answered.
    val homeAssistant = server.check is ServerCheck.Found && providers.any { it.isHomeAssistant }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChosenServerCard(
            server = server,
            onChange = {
                signIn.clearError()
                onChangeServer()
            },
            onCheckAgain = onCheckAgain
        )

        label?.let { Text(it, style = MaterialTheme.typography.titleSmall) }

        saved.continueAs?.let { name ->
            ContinueAsButton(
                name = name,
                connecting = connecting,
                onClick = { signIn.continueAs(server, saved) }
            )
            Text(
                text = "Or sign in with another account:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (homeAssistant) {
            HomeAssistantSignIn(
                inProgress = oauthInProgress,
                onClick = {
                    coroutineScope.launch {
                        val authUrl = signIn.startHomeAssistantOAuth(server.url)
                        if (authUrl != null) launchCustomTab(context, authUrl)
                    }
                }
            )
        }

        CredentialFields(
            username = username,
            onUsernameChange = {
                draft.username = it
                signIn.clearError()
            },
            password = password,
            onPasswordChange = {
                draft.password = it
                signIn.clearError()
            }
        )

        // Only the form's own errors (missing fields, Home Assistant sign-in). A connection
        // error is shown by the screen around the step.
        error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        SignInButton(
            connecting = connecting,
            onClick = { signIn.login(server, username, password) }
        )
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
        text = "or with your Music Assistant account",
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
            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign in")
        }
    }
}

/** Signs in with the account this phone saved for the server, before the fields for another one. */
@Composable
private fun ContinueAsButton(name: String, connecting: Boolean, onClick: () -> Unit) {
    MdButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = !connecting
    ) {
        Icon(Icons.Default.Person, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Continue as $name")
    }
}

/** Opens [url] in a browser tab, which returns to the app when it closes. */
fun launchCustomTab(context: Context, url: String) {
    CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
        .launchUrl(context, android.net.Uri.parse(url))
}

/**
 * Opens the system picker for a client certificate and reports the chosen alias, or null, on
 * the main thread. The picker closes at once with nothing chosen when the phone holds no
 * usable certificate, and Android 10 shows no message of its own then, so callers explain it
 * with [NO_CERTIFICATE_CHOSEN].
 */
fun chooseClientCertificate(activity: Activity, current: String?, onResult: (String?) -> Unit) {
    KeyChain.choosePrivateKeyAlias(
        activity,
        { picked -> activity.runOnUiThread { onResult(picked) } },
        null, null, null, -1, current
    )
}

const val NO_CERTIFICATE_CHOSEN = "No certificate found on this phone."
