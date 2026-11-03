package net.asksakis.massdroidv2.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.discovery.ServerPicker
import net.asksakis.massdroidv2.data.signin.SignInController
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import javax.inject.Inject

/**
 * The TV sign-in: the server picked in step 1, then the phone's [SignInController] for it, so
 * the TV signs in and saves the way the phone and the car do. It offers a username and
 * password, the account saved for the server, and Home Assistant, whose page the TV shows
 * inside the app because it has no browser.
 */
@HiltViewModel
class TvOnboardingViewModel @Inject constructor(
    wsClient: MaWebSocketClient,
    serverPickerFactory: ServerPicker.Factory,
    signInFactory: SignInController.Factory,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = wsClient.connectionState

    /** Step 1 and step 2 of the sign-in, shared with the phone and the car. */
    val serverPicker: ServerPicker = serverPickerFactory.create(viewModelScope)

    /** The sign-in form of the chosen server, shared with the phone and the car. */
    val signIn: SignInController = signInFactory.create(viewModelScope)

    init {
        // A Home Assistant start that fails says why on the form, as on the phone.
        viewModelScope.launch { signIn.oauthErrors.collect(signIn::setError) }
    }
}
