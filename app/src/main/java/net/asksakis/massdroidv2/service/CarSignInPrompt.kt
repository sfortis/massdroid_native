package net.asksakis.massdroidv2.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaSession

/**
 * The car's Sign in prompt: an error on the session, with a "Sign in" action into the parked
 * car sign-in screen, that stays for as long as there are no saved credentials.
 *
 * It is set on the media notification controller, because only that controller's error reaches
 * the platform session, and the AAOS media center reads the platform session. An error set for
 * every controller is stored for the controllers that connect later, but the platform session
 * does not get it when the notification controller connects after it, and the Media3
 * controllers would lose their commands for nothing. A one-off session error (`sendError`) to a
 * legacy controller is set and cleared again in the same call, so the media center never kept
 * it, and a fresh install showed no Sign in.
 *
 * Calls must come from the session's application thread.
 */
@OptIn(UnstableApi::class)
internal class CarSignInPrompt(private val context: Context) {

    /** Null until the saved settings have been read, so nothing is shown on a guess. */
    @Volatile private var signedOut: Boolean? = null

    /** True once the saved settings are known to hold no credentials; read off the main thread. */
    val isSignedOut: Boolean get() = signedOut == true

    /** The saved credentials changed: show or clear the prompt. */
    fun update(session: MediaSession, signedOut: Boolean) {
        this.signedOut = signedOut
        session.mediaNotificationControllerInfo?.let { apply(session, it) }
    }

    /** A controller connected; the notification controller can connect after [update]. */
    fun onConnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (session.isMediaNotificationController(controller)) apply(session, controller)
    }

    private fun apply(session: MediaSession, controller: MediaSession.ControllerInfo) {
        val out = signedOut ?: return
        session.setPlaybackException(controller, if (out) signInError() else null)
    }

    // Targets CarSignInActivity by class name: it lives only in the automotive flavor source set,
    // so the shared main source set must not reference it. The exported activity can be started
    // by this PendingIntent without a LAUNCHER filter, which is why the car build has no icon.
    private fun signInError(): PlaybackException {
        val intent = Intent().apply {
            setClassName(context, "net.asksakis.massdroidv2.ui.car.CarSignInActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val extras = Bundle().apply {
            putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, "Sign in")
            putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, pendingIntent)
        }
        return PlaybackException(
            "Sign in to Music Assistant",
            null,
            PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
            extras,
        )
    }
}
