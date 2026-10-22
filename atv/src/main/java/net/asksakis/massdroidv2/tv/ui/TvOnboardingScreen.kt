package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.tv.R

private val SCREEN_EDGE = 64.dp
private val COLUMN_GAP = 56.dp
private val FORM_WIDTH = 480.dp
private val LOGO_SIZE = 96.dp

@Composable
fun TvOnboardingScreen(viewModel: TvOnboardingViewModel = hiltViewModel()) {
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val attempting by viewModel.attempting.collectAsStateWithLifecycle()

    // Saveable, so a configuration change the activity does not handle (a keyboard plugged in,
    // an HDMI density change) keeps the form. The password stays out of the saved state, as on
    // the phone's sign-in form.
    var url by rememberSaveable { mutableStateOf("https://") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    val statusMessage = error
        ?: (connection as? ConnectionState.Error)?.message
        ?: if (attempting) "Connecting..." else null
    val isError = error != null || connection is ConnectionState.Error

    // First field grabs focus on entry so the D-pad has an anchor immediately;
    // without this the leanback remote has nothing to move from and the screen
    // reads as "frozen".
    val firstFieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFieldFocus.requestFocus() } }
    val connectFocus = remember { FocusRequester() }

    val focusManager = LocalFocusManager.current
    val nextField: () -> Unit = { focusManager.moveFocus(FocusDirection.Down) }
    // Guarded here as well as by the button's enabled flag, because the keyboard's Done
    // reaches it without going through the button.
    val connect: () -> Unit = { if (!attempting) viewModel.login(url, username, password) }

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
                    TvTextField(
                        "Server URL", url, KeyboardType.Uri, ImeAction.Next, onImeAction = nextField,
                        focusRequester = firstFieldFocus
                    ) { url = it }
                    Spacer(Modifier.height(12.dp))
                    TvTextField("Username", username, KeyboardType.Text, ImeAction.Next, onImeAction = nextField) {
                        username = it
                    }
                    Spacer(Modifier.height(12.dp))
                    // Showing the password changes only how it is drawn. The keyboard is always
                    // told this is a password field, so it never learns the password. Done moves
                    // the cursor to Connect, which closes the keyboard so the status line is
                    // visible, and starts the login.
                    TvTextField(
                        "Password", password, KeyboardType.Password, ImeAction.Done,
                        onImeAction = {
                            runCatching { connectFocus.requestFocus() }
                            connect()
                        },
                        masked = !showPassword
                    ) { password = it }
                    Spacer(Modifier.height(8.dp))
                    ShowPasswordToggle(showPassword) { showPassword = !showPassword }

                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = connect,
                        enabled = !attempting,
                        modifier = Modifier
                            .focusRequester(connectFocus)
                            .pointerClick(enabled = !attempting, onClick = connect)
                    ) {
                        Text(if (attempting) "Connecting..." else "Connect")
                    }

                    if (statusMessage != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingIntro(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        TvLogo(Modifier.size(LOGO_SIZE))
        Spacer(Modifier.height(20.dp))
        Text("Connect to Music Assistant", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter your Music Assistant server and credentials.",
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
