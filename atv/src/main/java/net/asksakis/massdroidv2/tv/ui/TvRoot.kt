package net.asksakis.massdroidv2.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.asksakis.massdroidv2.data.websocket.ConnectionState

/**
 * Top-level TV destination switch: the connected home once the shared WS client
 * reports Connected, otherwise the onboarding/login screen.
 */
@Composable
fun TvRoot(viewModel: TvRootViewModel = hiltViewModel()) {
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    if (connection is ConnectionState.Connected) {
        TvHomeScreen()
    } else {
        TvOnboardingScreen()
    }
}
