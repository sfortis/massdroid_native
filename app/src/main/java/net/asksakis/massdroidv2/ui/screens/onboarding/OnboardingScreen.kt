package net.asksakis.massdroidv2.ui.screens.onboarding

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import net.asksakis.massdroidv2.R
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.domain.AppLinks
import net.asksakis.massdroidv2.domain.connection.ChosenServer
import net.asksakis.massdroidv2.domain.connection.ServerRow
import net.asksakis.massdroidv2.domain.connection.ServerRowGroup
import net.asksakis.massdroidv2.ui.components.MdButton
import net.asksakis.massdroidv2.ui.components.MdIconButton
import net.asksakis.massdroidv2.ui.components.MdTextButton
import net.asksakis.massdroidv2.ui.components.OtherServerSheet
import net.asksakis.massdroidv2.ui.components.NO_CERTIFICATE_CHOSEN
import net.asksakis.massdroidv2.ui.components.NoServerFound
import net.asksakis.massdroidv2.ui.components.SearchingForServers
import net.asksakis.massdroidv2.ui.components.SignInStep
import net.asksakis.massdroidv2.ui.components.chooseClientCertificate
import net.asksakis.massdroidv2.ui.components.launchCustomTab
import net.asksakis.massdroidv2.ui.components.rememberSignInDraft

/**
 * The phone's first launch, shown while no server is saved: a welcome, the server list, and
 * the sign-in. It asks for no permission, because none is needed to connect and play.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val started by viewModel.started.collectAsStateWithLifecycle()
    val chosen by viewModel.serverPicker.chosen.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    // The onboarding leaves by itself once a sign-in connects. Watching here rather than from
    // MainActivity keeps that true after the activity is recreated with this route on top.
    LaunchedEffect(Unit) {
        viewModel.connectionState.first { it is ConnectionState.Connected }
        onFinished()
    }

    // Back walks the steps backwards, and on the welcome it closes the app, as Back on Home does.
    BackHandler {
        when {
            chosen != null -> viewModel.changeServer()
            started -> viewModel.backToWelcome()
            else -> activity?.finish()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            val server = chosen
            when {
                !started -> WelcomeStep(onStart = viewModel::start)
                server == null -> ServerStep(viewModel)
                else -> {
                    StepHeader(step = 2, onBack = viewModel::changeServer)
                    StepTitle("Sign in", "Use the account you have on this server.")
                    SignInBody(viewModel, server)
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Image(
            painter = painterResource(R.drawable.logo_md),
            contentDescription = null,
            modifier = Modifier.size(LOGO_SIZE)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Your music, in every room",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Feature(
                Icons.Default.SpeakerGroup,
                "Play it anywhere",
                "Pick a speaker, or group a few, and move the music between rooms."
            )
            Feature(
                Icons.Default.LibraryMusic,
                "Find something to play",
                "Browse your library, search, or start a mix built from what you love."
            )
            Feature(
                Icons.Default.PhoneAndroid,
                "Listen on this phone",
                "Your phone joins in as a speaker, in sync with the others."
            )
        }
        Spacer(Modifier.height(48.dp))
        MdButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "MassDroid works with your own Music Assistant server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        MdTextButton(onClick = { launchCustomTab(context, AppLinks.MUSIC_ASSISTANT_URL) }) {
            Text("What's Music Assistant?")
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, line: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconBadge(icon)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** The back arrow and the bar that says which of the two sign-in steps this is. */
@Composable
private fun StepHeader(step: Int, onBack: () -> Unit) {
    MdIconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        repeat(STEP_COUNT) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(
                        color = if (index < step) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = CircleShape
                    )
            )
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun StepTitle(title: String, line: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold))
    Spacer(Modifier.height(4.dp))
    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ServerStep(viewModel: OnboardingViewModel) {
    val picker = viewModel.serverPicker
    val rows by picker.rows.collectAsStateWithLifecycle()
    val searching by picker.searching.collectAsStateWithLifecycle()
    val typedCheck by picker.typedCheck.collectAsStateWithLifecycle()
    var choosingOther by rememberSaveable { mutableStateOf(false) }
    val pick: (ServerRow) -> Unit = { row ->
        viewModel.signIn.clearError()
        picker.choose(row.url, row.name)
    }

    val lastUsed = rows.filter { it.group == ServerRowGroup.LAST_USED }
    val found = rows.filter { it.group == ServerRowGroup.ON_THIS_NETWORK }
    StepHeader(step = 1, onBack = viewModel::backToWelcome)
    StepTitle(
        "Choose your server",
        when {
            found.isNotEmpty() -> "Found on your network. Tap one to go on."
            searching -> "Looking for Music Assistant on your network."
            lastUsed.isNotEmpty() -> "Pick a server you used before, or type its address."
            else -> "Type your server's address under Other server."
        }
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (lastUsed.isNotEmpty()) {
            GroupLabel("Recently used")
            lastUsed.forEach { row -> ServerTile(row, onClick = { pick(row) }) }
        }
        GroupLabel("On this network")
        when {
            found.isNotEmpty() -> found.forEach { row -> ServerTile(row, onClick = { pick(row) }) }
            searching -> SearchingForServers()
            else -> NoServerFound(onSearchAgain = picker::searchAgain)
        }
        Tile(
            icon = Icons.Default.Add,
            title = "Other server",
            line = "Type an address",
            onClick = { choosingOther = true },
            emphasised = false
        )
    }
    Spacer(Modifier.height(24.dp))
    ClientCertificateLink(viewModel)

    if (choosingOther) {
        OtherServerSheet(
            check = typedCheck,
            onCheck = picker::checkTyped,
            onUse = {
                if (picker.useTyped()) {
                    viewModel.signIn.clearError()
                    choosingOther = false
                }
            },
            onDismiss = {
                picker.clearTyped()
                choosingOther = false
            }
        )
    }
}

/**
 * For a server behind mutual TLS, which does not answer without a client certificate. Few
 * users need it, so it is a link under the list rather than a step.
 */
@Composable
private fun ClientCertificateLink(viewModel: OnboardingViewModel) {
    val alias by viewModel.signIn.clientCertAlias.collectAsStateWithLifecycle()
    val noneChosen by viewModel.noCertificateChosen.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        MdTextButton(
            onClick = {
                (context as? Activity)?.let { activity ->
                    chooseClientCertificate(activity, alias) { picked ->
                        viewModel.onCertificatePicked(picked, context)
                    }
                }
            }
        ) {
            Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(alias?.let { "Client certificate: $it" } ?: "Use a client certificate")
        }
    }
    // The system picker closes at once, with nothing chosen, when the phone holds no usable
    // certificate (Android 10 shows no message of its own), so the app says what to do.
    if (noneChosen && alias == null) {
        Text(
            text = NO_CERTIFICATE_CHOSEN,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ServerTile(row: ServerRow, onClick: () -> Unit) {
    Tile(
        icon = when {
            row.group == ServerRowGroup.LAST_USED -> Icons.Default.History
            row.isHomeAssistantAddon -> Icons.Default.Home
            else -> Icons.Default.Dns
        },
        title = row.name,
        line = row.detail,
        onClick = onClick,
        emphasised = true
    )
}

/** A server, or Other server, as a tile the whole width of the column. */
@Composable
private fun Tile(icon: ImageVector, title: String, line: String?, onClick: () -> Unit, emphasised: Boolean) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (emphasised) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
        border = if (emphasised) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            IconBadge(icon)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                line?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SignInBody(viewModel: OnboardingViewModel, server: ChosenServer) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val attemptedUrl by viewModel.signIn.attemptedUrl.collectAsStateWithLifecycle()
    val draft = rememberSignInDraft()

    SignInStep(
        signIn = viewModel.signIn,
        server = server,
        connecting = connectionState is ConnectionState.Connecting,
        draft = draft,
        onChangeServer = viewModel::changeServer,
        onCheckAgain = viewModel.serverPicker::checkAgain,
        label = null
    )
    // Only the error of a sign-in sent to this server, not one left from another server.
    (connectionState as? ConnectionState.Error)?.takeIf { attemptedUrl == server.url }?.let { error ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = error.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Start
        )
    }
}

private val CONTENT_MAX_WIDTH = 480.dp
private val LOGO_SIZE = 96.dp
private const val STEP_COUNT = 2
