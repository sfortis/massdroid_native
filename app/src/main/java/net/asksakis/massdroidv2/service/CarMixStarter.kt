package net.asksakis.massdroidv2.service

import android.util.Log
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.discover.MixLauncher
import net.asksakis.massdroidv2.domain.shortcut.ShortcutAction
import net.asksakis.massdroidv2.domain.shortcut.ShortcutActionDispatcher
import net.asksakis.massdroidv2.presentation.discover.failureMessage

private const val TAG = "CarMixStarter"

/**
 * Starts the Smart Mix or Genre Radio the car asked for, and tells the car when one plays
 * nothing. While the phone drives Android Auto there is no screen to show the reason on, so a
 * refused or failed mix is sent to the session as a non-fatal error, which the car shows.
 *
 * Only a mix started here is reported. A mix started on the phone's own screen shows its message
 * there, and the launcher runs one mix at a time, so the two cannot be confused.
 */
internal class CarMixStarter(
    private val scope: CoroutineScope,
    private val mixLauncher: MixLauncher,
    private val shortcutDispatcher: ShortcutActionDispatcher,
    private val sessionEventBus: SessionEventBus,
    private val isAutomotive: Boolean,
    private val session: () -> MediaSession?,
) {
    // The mix the car started and has not had an outcome for yet. Written on the browse thread,
    // read on the main thread where the outcomes are collected.
    @Volatile private var ownMix: MixLauncher.Mix? = null

    /** Collects the outcomes; [scope] must run on the session's application thread. */
    fun start() {
        scope.launch { mixLauncher.outcomes.collect { report(it) } }
        scope.launch { sessionEventBus.resets.collect { ownMix = null } }
    }

    fun startMix(queueId: String, action: ShortcutAction) {
        val mix = when (action) {
            ShortcutAction.SmartMix -> MixLauncher.Mix.SmartMix
            is ShortcutAction.GenreRadio -> MixLauncher.Mix.GenreRadio(action.genre)
            else -> {
                Log.w(TAG, "Not a mix: $action")
                return
            }
        }
        // Claimed before the start, because a refusal is published at once.
        val previous = ownMix
        ownMix = mix
        val accepted = if (isAutomotive) {
            // The headless car keeps its route through the Sendspin coordinator, which turns
            // Sendspin on and starts the mix on the car's own player through the launcher.
            shortcutDispatcher.dispatch(action)
            true
        } else {
            when (mix) {
                MixLauncher.Mix.SmartMix -> mixLauncher.startSmartMix(queueId)
                is MixLauncher.Mix.GenreRadio -> mixLauncher.startGenreRadio(queueId, mix.genre)
            }
        }
        // An ignored press (another mix is building) puts back the mix the car owned.
        if (!accepted) ownMix = previous
    }

    private fun report(outcome: MixLauncher.Outcome) {
        if (outcome.mix != ownMix) return
        ownMix = null
        val message = outcome.failureMessage() ?: return
        Log.d(TAG, "${outcome.mix} played nothing: $message")
        session()?.sendError(SessionError(SessionError.ERROR_UNKNOWN, message))
    }
}
