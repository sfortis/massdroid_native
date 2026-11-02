package net.asksakis.massdroidv2.ui.screens.onboarding

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.discovery.ServerPicker
import net.asksakis.massdroidv2.data.signin.SignInController
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import javax.inject.Inject

/**
 * The phone's first launch: a welcome, then the two sign-in steps the Settings screen uses.
 * The app leaves the onboarding for Home once the first sign-in connects, which MainActivity
 * watches, so this ViewModel only holds where the user is.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    wsClient: MaWebSocketClient,
    serverPickerFactory: ServerPicker.Factory,
    signInFactory: SignInController.Factory
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = wsClient.connectionState

    /** Step 1 and step 2 of the sign-in, the same as in Settings. */
    val serverPicker: ServerPicker = serverPickerFactory.create(viewModelScope)

    val signIn: SignInController = signInFactory.create(viewModelScope)

    /** False while the welcome shows. Saved, so a rotation does not return to the welcome. */
    val started: StateFlow<Boolean> = savedState.getStateFlow(KEY_STARTED, false)

    private val _noCertificateChosen = MutableStateFlow(false)

    /** The certificate picker closed with nothing chosen, which needs an explanation. */
    val noCertificateChosen: StateFlow<Boolean> = _noCertificateChosen.asStateFlow()

    init {
        // Collected here rather than on the sign-in step, so an error that arrives while step 1
        // shows (after process death the step is not restored) still reaches the form.
        viewModelScope.launch {
            signIn.oauthErrors.collect { message -> signIn.setError(message) }
        }
    }

    fun start() {
        savedState[KEY_STARTED] = true
    }

    /** Back to the server list, abandoning a sign-in that is still running. */
    fun changeServer() {
        signIn.cancelAttempt()
        serverPicker.changeServer()
    }

    fun backToWelcome() {
        changeServer()
        savedState[KEY_STARTED] = false
    }

    fun onCertificatePicked(alias: String?, context: Context) {
        _noCertificateChosen.value = alias == null && signIn.clientCertAlias.value == null
        signIn.selectCertificate(alias, context)
    }

    private companion object {
        const val KEY_STARTED = "started"
    }
}
