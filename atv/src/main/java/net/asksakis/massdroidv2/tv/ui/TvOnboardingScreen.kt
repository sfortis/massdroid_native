package net.asksakis.massdroidv2.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import net.asksakis.massdroidv2.data.discovery.ServerPicker
import net.asksakis.massdroidv2.data.signin.SavedSignIn
import net.asksakis.massdroidv2.data.signin.SignInController
import net.asksakis.massdroidv2.data.websocket.OAUTH_CALLBACK_SCHEME
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.domain.connection.ChosenServer
import net.asksakis.massdroidv2.domain.connection.PortChoice
import net.asksakis.massdroidv2.domain.connection.ServerAddressInput
import net.asksakis.massdroidv2.domain.connection.ServerCheck
import net.asksakis.massdroidv2.domain.connection.ServerRow
import net.asksakis.massdroidv2.domain.connection.ServerRowGroup
import net.asksakis.massdroidv2.domain.connection.displayAddressOf
import net.asksakis.massdroidv2.domain.connection.sameServer
import net.asksakis.massdroidv2.tv.R
import net.asksakis.massdroidv2.ui.shared.HomeAssistantSignInPage
import net.asksakis.massdroidv2.ui.shared.isWebViewAvailable

private val SCREEN_EDGE = 64.dp

private val ServerAddressInputSaver = listSaver<ServerAddressInput, String>(
    save = { listOf(it.address, it.port, it.portEdited.toString(), it.https.toString()) },
    restore = { ServerAddressInput(it[0], it[1], it[2].toBoolean(), it[3].toBoolean()) }
)
private val COLUMN_GAP = 56.dp
private val FORM_WIDTH = 480.dp
private val LOGO_SIZE = 96.dp

/**
 * The TV sign-in, in the same two steps as the phone: choose a server, then sign in to it.
 * Other server is a third view in place of the list, because a sheet that grows under a field
 * is hard to reach with a remote. BACK goes one step back. The Home Assistant sign-in page
 * covers the whole screen while it is open. [onSignedIn] runs once a sign-in sent from this
 * screen saved its server.
 */
@Composable
fun TvOnboardingScreen(onSignedIn: () -> Unit, viewModel: TvOnboardingViewModel = hiltViewModel()) {
    val picker = viewModel.serverPicker
    val signIn = viewModel.signIn
    val chosen by picker.chosen.collectAsStateWithLifecycle()
    val homeAssistantPage by signIn.homeAssistantPage.collectAsStateWithLifecycle()
    var choosingOther by rememberSaveable { mutableStateOf(false) }
    // The server this screen last sent a sign-in to. Only its success closes the screen:
    // Connected alone is not enough, because a failed switch connects to the previous server.
    var sentUrl by rememberSaveable { mutableStateOf<String?>(null) }
    // Leaving the chosen server abandons its sign-in, which then can no longer close the screen.
    val leaveServer: () -> Unit = {
        signIn.cancelAttempt()
        sentUrl = null
    }

    val succeeded by signIn.succeeded.collectAsStateWithLifecycle()
    LaunchedEffect(succeeded, sentUrl) {
        val done = succeeded ?: return@LaunchedEffect
        val sent = sentUrl ?: return@LaunchedEffect
        if (sameServer(done, sent)) {
            signIn.consumeSuccess()
            onSignedIn()
        }
    }

    BackHandler(enabled = choosingOther || chosen != null) {
        if (choosingOther) {
            picker.clearTyped()
            choosingOther = false
        } else {
            leaveServer()
            picker.changeServer()
        }
    }

    // What was typed for the chosen server, kept here so it survives the Home Assistant page,
    // which replaces the form while it is open; null shows what is saved for that server. The
    // password stays out of the saved state, which the system may write to disk.
    var typedUsername by rememberSaveable(chosen?.url) { mutableStateOf<String?>(null) }
    var typedPassword by remember(chosen?.url) { mutableStateOf<String?>(null) }
    // The form puts the cursor back on the Home Assistant button after its page closes.
    var pageClosed by remember { mutableStateOf(false) }

    homeAssistantPage?.let { url ->
        LaunchedEffect(Unit) { pageClosed = true }
        TvHomeAssistantPage(url, signIn)
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        // The introduction sits on the left and the form on the right, as in the platform's
        // own TV forms, so the fields start near the top of the screen and most of the form
        // stays visible above the on-screen keyboard. imePadding shrinks the screen above the
        // keyboard where the system does not already resize the window (edge-to-edge).
        Row(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = SCREEN_EDGE),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OnboardingIntro(Modifier.weight(1f))
            Spacer(Modifier.width(COLUMN_GAP))
            // Centered while the form fits, scrollable once the keyboard leaves less room. The
            // scroll container also brings the focused field or button into view.
            BoxWithConstraints(modifier = Modifier.width(FORM_WIDTH).fillMaxHeight()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(vertical = 32.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    val server = chosen
                    when {
                        choosingOther -> TvOtherServer(
                            picker = picker,
                            onUsed = {
                                signIn.clearError()
                                choosingOther = false
                            },
                            onBack = {
                                picker.clearTyped()
                                choosingOther = false
                            }
                        )
                        server == null -> TvServerList(
                            picker = picker,
                            onPick = { row ->
                                leaveServer()
                                picker.choose(row.url, row.name)
                            },
                            onOther = { choosingOther = true }
                        )
                        else -> TvSignIn(
                            viewModel = viewModel,
                            server = server,
                            typed = TypedSignIn(typedUsername, typedPassword),
                            onUsername = { typedUsername = it },
                            onPassword = { typedPassword = it },
                            focusHomeAssistant = pageClosed,
                            onFocused = { pageClosed = false },
                            onSent = { sentUrl = server.url },
                            onChangeServer = {
                                leaveServer()
                                picker.changeServer()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Step 1: one large button per server, the first one focused, and Other server last. */
@Composable
private fun ColumnScope.TvServerList(
    picker: ServerPicker,
    onPick: (ServerRow) -> Unit,
    onOther: () -> Unit
) {
    val rows by picker.rows.collectAsStateWithLifecycle()
    val searching by picker.searching.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    val lastUsed = rows.filter { it.group == ServerRowGroup.LAST_USED }
    val found = rows.filter { it.group == ServerRowGroup.ON_THIS_NETWORK }
    // The first button takes the focus, so one press of OK picks it. It changes when the
    // first server appears, or when the last used server is found on the network and moves to
    // that group, which is why the key is the first row with its group.
    // The search state is part of the key: Search again leaves the composition when it is
    // pressed, and the focus then moves to the first button left instead of being lost.
    LaunchedEffect(rows.firstOrNull()?.let { it.url to it.group }, searching) {
        runCatching { firstFocus.requestFocus() }
    }

    Text("Choose your server", style = MaterialTheme.typography.titleLarge)
    var first = true
    fun focusModifier(): Modifier = if (first) {
        first = false
        Modifier.focusRequester(firstFocus)
    } else {
        Modifier
    }
    if (lastUsed.isNotEmpty()) {
        TvGroupLabel("Recently used")
        lastUsed.forEach { row -> TvServerButton(row, focusModifier()) { onPick(row) } }
    }
    TvGroupLabel("On this network")
    when {
        found.isNotEmpty() -> found.forEach { row -> TvServerButton(row, focusModifier()) { onPick(row) } }
        searching -> {
            Text(
                "Looking for Music Assistant on this network",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        else -> {
            // The search goes on in the background, and a server it finds replaces this.
            Text("No server found on this network", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Servers are found only on the same network as Music Assistant. " +
                    "Away from home or on a VPN, use Other server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = picker::searchAgain, modifier = focusModifier().pointerClick(onClick = picker::searchAgain)) {
                Text("Search again")
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onOther, modifier = focusModifier().pointerClick(onClick = onOther)) {
        Text("Other server")
    }
}

@Composable
private fun TvGroupLabel(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun TvServerButton(row: ServerRow, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.fillMaxWidth().pointerClick(onClick = onClick)) {
        Column {
            Text(row.name, style = MaterialTheme.typography.titleSmall)
            row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Step 2: the chosen server, then "Continue as" when the server has a saved account, Home
 * Assistant when the server offers it, and the username and password.
 */
@Composable
private fun ColumnScope.TvSignIn(
    viewModel: TvOnboardingViewModel,
    server: ChosenServer,
    typed: TypedSignIn,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    focusHomeAssistant: Boolean,
    onFocused: () -> Unit,
    onSent: () -> Unit,
    onChangeServer: () -> Unit
) {
    val signIn = viewModel.signIn
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val error by signIn.error.collectAsStateWithLifecycle()
    val attemptedUrl by signIn.attemptedUrl.collectAsStateWithLifecycle()
    val providers by signIn.providers.collectAsStateWithLifecycle()
    val oauthInProgress by signIn.oauthInProgress.collectAsStateWithLifecycle()
    val saved by remember(server) { signIn.savedSignIn(server) }.collectAsStateWithLifecycle(SavedSignIn.NONE)

    var showPassword by rememberSaveable { mutableStateOf(false) }
    val shownUsername = typed.username ?: saved.username
    val shownPassword = typed.password ?: saved.password

    val connecting = connection is ConnectionState.Connecting
    // Only the error of a sign-in sent to this server, not one left from another server.
    val connectionError = (connection as? ConnectionState.Error)?.takeIf { attemptedUrl == server.url }
    val statusMessage = error
        ?: connectionError?.message
        ?: if (connecting) "Connecting..." else null
    val isError = error != null || connectionError != null
    // The providers belong to the server checked last, which is this one once it answered.
    val webView = remember { isWebViewAvailable() }
    val homeAssistant = webView && server.check is ServerCheck.Found && providers.any { it.isHomeAssistant }

    val continueFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val connectFocus = remember { FocusRequester() }
    val changeFocus = remember { FocusRequester() }
    val tryAgainFocus = remember { FocusRequester() }
    val homeAssistantFocus = remember { FocusRequester() }
    val continueName = saved.continueAs
    // One press of OK continues with the saved account when there is one.
    val firstFocus = if (continueName != null) continueFocus else usernameFocus
    LaunchedEffect(server.url, continueName != null) { runCatching { firstFocus.requestFocus() } }
    // Launched after the one above, so back from the Home Assistant page its button wins.
    LaunchedEffect(focusHomeAssistant, homeAssistant) {
        if (focusHomeAssistant && homeAssistant) {
            runCatching { homeAssistantFocus.requestFocus() }
            onFocused()
        }
    }
    // Try again leaves the screen while the server is checked again, taking the focus with it,
    // so the focus waits on Change server and then goes where the result leads.
    var retried by remember(server.url) { mutableStateOf(false) }
    LaunchedEffect(server.check, retried) {
        if (!retried) return@LaunchedEffect
        runCatching {
            when (server.check) {
                is ServerCheck.Problem -> tryAgainFocus.requestFocus()
                is ServerCheck.Found -> firstFocus.requestFocus()
                else -> changeFocus.requestFocus()
            }
        }
    }
    val focusManager = LocalFocusManager.current
    val nextField: () -> Unit = { focusManager.moveFocus(FocusDirection.Down) }
    // Guarded here as well as by the button's enabled flag, because the keyboard's Done
    // reaches it without going through the button.
    val connect: () -> Unit = {
        if (!connecting && signIn.login(server, shownUsername, shownPassword)) onSent()
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(server.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            TvServerCheckLine(server.check)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onChangeServer,
            modifier = Modifier.focusRequester(changeFocus).pointerClick(onClick = onChangeServer)
        ) { Text("Change server") }
        if (server.check is ServerCheck.Problem) {
            val again = {
                retried = true
                viewModel.serverPicker.checkAgain()
            }
            Button(
                onClick = again,
                modifier = Modifier.focusRequester(tryAgainFocus).pointerClick(onClick = again)
            ) { Text("Try again") }
        }
    }

    Spacer(Modifier.height(20.dp))
    continueName?.let { name ->
        val continueAs = {
            if (!connecting) {
                signIn.continueAs(server, saved)
                onSent()
            }
        }
        Button(
            onClick = continueAs,
            enabled = !connecting,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(continueFocus)
                .pointerClick(enabled = !connecting, onClick = continueAs)
        ) { Text("Continue as $name") }
        Spacer(Modifier.height(12.dp))
        Text(
            "Or sign in with another account:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
    }
    if (homeAssistant) {
        val open = {
            if (!oauthInProgress) {
                signIn.openHomeAssistantSignIn(server.url)
                onSent()
            }
        }
        Button(
            onClick = open,
            enabled = !oauthInProgress,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(homeAssistantFocus)
                .pointerClick(enabled = !oauthInProgress, onClick = open)
        ) { Text(if (oauthInProgress) "Waiting for sign in" else "Sign in with Home Assistant") }
        Spacer(Modifier.height(6.dp))
        Text(
            "or with your Music Assistant account",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
    }
    TvTextField(
        "Username", shownUsername, KeyboardType.Text, ImeAction.Next, onImeAction = nextField,
        focusRequester = usernameFocus,
        onValueChange = onUsername
    )
    Spacer(Modifier.height(12.dp))
    // Showing the password changes only how it is drawn. The keyboard is always told this is
    // a password field, so it never learns the password. Done moves the cursor to Connect,
    // which closes the keyboard so the status line is visible, and starts the login.
    TvTextField(
        "Password", shownPassword, KeyboardType.Password, ImeAction.Done,
        onImeAction = {
            runCatching { connectFocus.requestFocus() }
            connect()
        },
        masked = !showPassword,
        onValueChange = onPassword
    )
    Spacer(Modifier.height(8.dp))
    ShowPasswordToggle(showPassword) { showPassword = !showPassword }

    Spacer(Modifier.height(20.dp))
    Button(
        onClick = connect,
        enabled = !connecting,
        modifier = Modifier
            .focusRequester(connectFocus)
            .pointerClick(enabled = !connecting, onClick = connect)
    ) {
        Text(if (connecting) "Connecting..." else "Sign in")
    }

    if (statusMessage != null) {
        Spacer(Modifier.height(12.dp))
        Text(
            statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** What was typed into the sign-in form; null shows what is saved for the server. */
private class TypedSignIn(val username: String?, val password: String?)

/**
 * The Home Assistant sign-in page over the whole screen. BACK and Cancel return to the form; a
 * page that failed to load offers Retry.
 */
@Composable
private fun TvHomeAssistantPage(url: String, signIn: SignInController) {
    HomeAssistantSignInPage(
        url = url,
        callbackScheme = OAUTH_CALLBACK_SCHEME,
        onCallback = signIn::deliverHomeAssistantCallback,
        onCancel = signIn::cancelHomeAssistantSignIn,
        modifier = Modifier.fillMaxSize()
    ) { message, retry ->
        val firstButton = remember { FocusRequester() }
        LaunchedEffect(message) { runCatching { firstButton.requestFocus() } }
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = SCREEN_EDGE),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(message, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val cancel = signIn::cancelHomeAssistantSignIn
                    retry?.let { again ->
                        Button(
                            onClick = again,
                            modifier = Modifier.focusRequester(firstButton).pointerClick(onClick = again)
                        ) { Text("Retry") }
                    }
                    Button(
                        onClick = cancel,
                        modifier = (if (retry == null) Modifier.focusRequester(firstButton) else Modifier)
                            .pointerClick(onClick = cancel)
                    ) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * Other server: the address field, checked while it is typed, the connection choice (Music
 * Assistant's port, HTTPS, or another port), and Use this server once Music Assistant answered.
 */
@Composable
private fun ColumnScope.TvOtherServer(picker: ServerPicker, onUsed: () -> Unit, onBack: () -> Unit) {
    val check by picker.typedCheck.collectAsStateWithLifecycle()
    var input by rememberSaveable(stateSaver = ServerAddressInputSaver) { mutableStateOf(ServerAddressInput()) }
    // Custom stays chosen while its port field is open, even when the port typed there is 8095.
    var customPicked by rememberSaveable { mutableStateOf(false) }
    val choice = if (customPicked) PortChoice.CUSTOM else input.portChoice
    val fieldFocus = remember { FocusRequester() }
    // Done closes nothing on its own on a TV keyboard, so it moves on to the next control.
    val focusManager = LocalFocusManager.current
    val moveDown: () -> Unit = { focusManager.moveFocus(FocusDirection.Down) }
    LaunchedEffect(input) { picker.checkTyped(input) }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    Text("Other server", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    TvTextField(
        "Server address", input.address, KeyboardType.Uri, ImeAction.Done, onImeAction = moveDown,
        focusRequester = fieldFocus
    ) { input = input.withAddress(it) }
    Spacer(Modifier.height(6.dp))
    TvServerCheckLine(check, idleText = "For example homeassistant.local or 192.168.1.20")
    (check as? ServerCheck.Problem)?.suggestion?.let { url ->
        Spacer(Modifier.height(8.dp))
        val address = displayAddressOf(url)
        val use = {
            customPicked = false
            input = ServerAddressInput.fromUrl(url)
            // The suggestion disappears with the new check, so the focus goes back to the field.
            runCatching { fieldFocus.requestFocus() }
            Unit
        }
        Button(onClick = use, modifier = Modifier.pointerClick(onClick = use)) {
            Text("Use ${address.substringBeforeLast(':')}, port ${address.substringAfterLast(':')}")
        }
    }
    Spacer(Modifier.height(16.dp))
    Text("Connection", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvPortChip("MA port", choice == PortChoice.MUSIC_ASSISTANT) {
            customPicked = false
            input = input.withPortChoice(PortChoice.MUSIC_ASSISTANT)
        }
        TvPortChip("HTTP", choice == PortChoice.HTTP) {
            customPicked = false
            input = input.withPortChoice(PortChoice.HTTP)
        }
        TvPortChip("HTTPS", choice == PortChoice.HTTPS) {
            customPicked = false
            input = input.withPortChoice(PortChoice.HTTPS)
        }
        TvPortChip("Custom", choice == PortChoice.CUSTOM) { customPicked = true }
    }
    if (choice == PortChoice.CUSTOM) {
        Spacer(Modifier.height(12.dp))
        TvTextField("Port", input.effectivePort, KeyboardType.Number, ImeAction.Done, onImeAction = moveDown) {
            // Typing here keeps Custom chosen, so the field does not vanish when the port
            // passes through 80, 8095 or 443 on the way to another number.
            customPicked = true
            input = input.withPort(it.filter(Char::isDigit).take(PORT_DIGITS))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Music Assistant itself uses 8095",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val found = check is ServerCheck.Found
        val use = { if (picker.useTyped()) onUsed() }
        Button(onClick = use, enabled = found, modifier = Modifier.pointerClick(enabled = found, onClick = use)) {
            Text("Use this server")
        }
        Button(onClick = onBack, modifier = Modifier.pointerClick(onClick = onBack)) { Text("Back") }
    }
}

/** What a server check found, in one line for the remote. */
@Composable
private fun TvServerCheckLine(check: ServerCheck, idleText: String? = null) {
    val (text, isError) = when (check) {
        ServerCheck.Idle -> (idleText ?: return) to false
        ServerCheck.Checking -> "Looking for Music Assistant" to false
        is ServerCheck.Found -> "\u2713  Music Assistant ${check.version}" to false
        is ServerCheck.Problem -> check.message to true
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun OnboardingIntro(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        TvLogo(Modifier.size(LOGO_SIZE))
        Spacer(Modifier.height(20.dp))
        Text("Connect to Music Assistant", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose your Music Assistant server, then sign in.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The MassDroid "MD" mark, shared by the onboarding and the startup screen. */
@Composable
internal fun TvLogo(modifier: Modifier = Modifier) {
    // The vector is drawn in two mid grays that fade into the dark surface. One light
    // tint matches the TV banner.
    Image(
        painter = painterResource(R.drawable.logo_md),
        contentDescription = null,
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
        modifier = modifier
    )
}

@Composable
private fun ShowPasswordToggle(shown: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.small),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.pointerClick(onClick = onToggle)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (shown) "Hide password" else "Show password",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/** One of the three connection choices, as a chip the remote can focus. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvPortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.pointerClick(onClick = onClick)
    ) { Text(label) }
}

private const val PORT_DIGITS = 5
