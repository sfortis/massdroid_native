package net.asksakis.massdroidv2.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.recommendation.MixPlaybackOrchestrator
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import javax.inject.Inject

/**
 * Debug-only entry point for triggering a Smart Mix / Genre Radio from adb, so a
 * test run does not depend on the UI at all.
 *
 * Driving the FAB with `input tap` was the only way to exercise a mix, and it is
 * brittle: it breaks on the lockscreen, on any navigation change, and whenever a
 * `uiautomator dump` lands mid-animation. This runs the exact same
 * [MixPlaybackOrchestrator] entry points the UI uses, so what it measures is the
 * real thing.
 *
 * This file lives in `src/debug`, so it is absent from release builds entirely.
 *
 * ```
 * adb shell am broadcast -a net.asksakis.massdroidv2.debug.SMART_MIX \
 *   -n net.asksakis.massdroidv2.debug/net.asksakis.massdroidv2.debug.DebugMixReceiver
 * adb shell am broadcast -a net.asksakis.massdroidv2.debug.GENRE_RADIO --es genre "indie pop" \
 *   -n net.asksakis.massdroidv2.debug/net.asksakis.massdroidv2.debug.DebugMixReceiver
 * ```
 * Optional `--es player <player_id>` overrides the currently selected player.
 */
@AndroidEntryPoint
class DebugMixReceiver : BroadcastReceiver() {

    @Inject lateinit var orchestrator: MixPlaybackOrchestrator
    @Inject lateinit var playerRepository: PlayerRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val queueId = intent.getStringExtra("player")
            ?: playerRepository.selectedPlayer.value?.playerId
        if (queueId.isNullOrBlank()) {
            Log.w(TAG, "no player selected and no --es player given")
            return
        }
        // Deliberately NOT goAsync(): a broadcast may only stay open ~60s and a
        // cold mix build exceeds that, which ANR'd the app. The process is
        // already alive (this is only ever used while the app is running), so
        // the build just continues on our own scope after delivery returns.
        scope.launch {
            val started = System.currentTimeMillis()
            try {
                val result = when (action) {
                    ACTION_SMART_MIX -> orchestrator.playSmartMix(queueId)
                    ACTION_GENRE_RADIO -> {
                        val genre = intent.getStringExtra("genre")
                        if (genre.isNullOrBlank()) {
                            Log.w(TAG, "GENRE_RADIO needs --es genre <name>")
                            return@launch
                        }
                        orchestrator.playGenreRadio(queueId, genre)
                    }
                    else -> {
                        Log.w(TAG, "unknown action $action")
                        return@launch
                    }
                }
                val ms = System.currentTimeMillis() - started
                Log.i(TAG, "$action on '$queueId' -> $result in ${ms}ms")
            } catch (e: Exception) {
                Log.e(TAG, "$action failed: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "DebugMix"
        const val ACTION_SMART_MIX = "net.asksakis.massdroidv2.debug.SMART_MIX"
        const val ACTION_GENRE_RADIO = "net.asksakis.massdroidv2.debug.GENRE_RADIO"
    }
}
