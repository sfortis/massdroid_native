package net.asksakis.massdroidv2.data.sendspin

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.repository.PlayerRepository

/**
 * Sole gateway for Sendspin player volume side effects. Every input that wants
 * to move the Sendspin volume — server-pushed events, hardware keys, the AA
 * volume tap, the in-app slider, sleep-timer fades, the engine's startup
 * re-seed, the system-side STREAM_MUSIC observer — calls one method here. The
 * coordinator owns:
 *
 *  1. The single `syncEnabled` switch (mirrored from
 *     `SettingsRepository.sendspinSyncSystemVolume`). No other caller checks
 *     this setting directly — they ask the coordinator to act and it decides
 *     what STREAM_MUSIC and MA player volume should do.
 *  2. Echo suppression for STREAM_MUSIC ↔ MA round-trips.
 *  3. The baseline-drop on first server event (so a stale MA-side cache
 *     doesn't yank STREAM_MUSIC on Sendspin start).
 *
 * Why one class: previously the bridge gated only the server→STREAM_MUSIC
 * leg and the SendspinCoordinator ContentObserver gated the STREAM_MUSIC→MA
 * leg. The hardware-key path in MainActivity and the engine startup seed in
 * SendspinAudioController each carried their own ungated logic. The switch
 * appeared to "still sync" because three independent call sites had to
 * remember to check it. Funnelling everything through this coordinator
 * removes that class of bug entirely.
 */
class SendspinVolumeCoordinator(
    private val audioManager: AudioManager,
    private val serverVolumeEvents: SharedFlow<Int>,
    private val serverMuteEvents: SharedFlow<Boolean>,
    private val syncEnabledFlow: Flow<Boolean>,
    private val sendspinClientIdFlow: Flow<String?>,
    private val playerRepository: PlayerRepository,
    /**
     * Returns the current audio output route type as reported by the active
     * Sendspin AudioTrack (`AudioDeviceInfo.TYPE_*`), or `null` if there's no
     * authoritative route yet. Lets the coordinator scope the sync-disable
     * toggle to the Bluetooth route only — on the phone speaker, wired
     * headphones, or USB, STREAM_MUSIC always tracks the MA player volume so
     * the in-app slider, hardware keys, and MA-side group fan-out stay
     * coherent. Only on BT (where the car or headphones perform additional
     * attenuation) does the toggle apply.
     */
    private val currentOutputDeviceType: () -> Int?,
) {
    companion object {
        private const val TAG = "SsVolCoord"
        private const val ECHO_WINDOW_MS = 1_500L
    }

    private var jobs: MutableList<Job> = mutableListOf()
    private var scope: CoroutineScope? = null

    @Volatile private var syncEnabled: Boolean = true
    @Volatile private var awaitingBaseline: Boolean = true
    @Volatile private var lastLocalPushAtMs: Long = 0L
    @Volatile private var lastKnownMaVolume: Int = 100
    @Volatile private var cachedSendspinPlayerId: String? = null
    /**
     * The last STREAM_MUSIC value we know about — either from a write we
     * performed ourselves or from an observer notification. Used to ignore
     * `Settings.System` ContentObserver fires that don't actually change
     * STREAM_MUSIC (the observer subscribes to the whole system-settings URI,
     * so any unrelated system change wakes it up). Without this filter the
     * spurious wakeups would each call recordLocalPush() and open the echo
     * window, causing legitimate server volume events to be suppressed and
     * leaving STREAM_MUSIC out of sync with the MA player.
     */
    @Volatile private var lastKnownStreamMusicPct: Int = -1

    /**
     * The user-facing sync toggle only applies when audio is routed to
     * Bluetooth. On any other route (phone speaker, wired headphones, USB
     * audio) STREAM_MUSIC must always track the MA player volume so the
     * in-app slider, hardware keys, and group fan-out stay consistent.
     *
     * The primary source is the active AudioTrack's reported device type,
     * but that can return null during transitions (track change, sync
     * relock, brief idle periods between codec reinit). A null at that
     * moment used to fall through to "always sync" — which on BT applied
     * the server's volume to STREAM_MUSIC and yanked the user's pinned
     * level down to whatever MA had stored, every few minutes when a new
     * track started. We now fall back to AudioManager.getDevices() so the
     * BT route stays recognised across those gaps.
     */
    private fun effectiveSyncEnabled(): Boolean {
        val routeType = currentOutputDeviceType()
        val isBt = if (routeType != null) {
            routeType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                routeType == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                routeType == AudioDeviceInfo.TYPE_BLE_SPEAKER
        } else {
            // Fallback: query the connected output devices directly. If a BT
            // sink is available the user is almost certainly on BT even when
            // the active AudioTrack is momentarily idle.
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
        }
        return if (isBt) syncEnabled else true
    }

    /**
     * Lifecycle: bind to a scope and start observing flows. Idempotent.
     *
     * Hydration of the persisted sync setting and Sendspin client id is
     * asynchronous — the collectors below write into [syncEnabled] and
     * [cachedSendspinPlayerId] on first emission. Input methods that
     * depend on the client id already bail when it is still null, so a
     * volume key press in the first few milliseconds after start() is
     * naturally a no-op until the Flow emits. The previous runBlocking
     * here was a foreground-service-startup blocker (up to 250 ms when
     * DataStore was contended) for a race window that resolves itself.
     */
    fun start(coroutineScope: CoroutineScope) {
        if (jobs.isNotEmpty()) return
        scope = coroutineScope
        awaitingBaseline = true
        jobs += coroutineScope.launch {
            syncEnabledFlow.collect { enabled ->
                if (syncEnabled != enabled) {
                    Log.d(TAG, "syncEnabled=$enabled")
                    syncEnabled = enabled
                }
            }
        }
        jobs += coroutineScope.launch {
            sendspinClientIdFlow.collect { id -> cachedSendspinPlayerId = id }
        }
        jobs += coroutineScope.launch {
            serverVolumeEvents.collect { pct -> onServerVolumeEvent(pct) }
        }
        jobs += coroutineScope.launch {
            serverMuteEvents.collect { muted -> onServerMuteEvent(muted) }
        }
        Log.d(TAG, "started (async hydration)")
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = mutableListOf()
        scope = null
        Log.d(TAG, "stopped")
    }

    // region Input intents

    /**
     * The system observed STREAM_MUSIC changing (ContentObserver fire). When
     * sync is ON we push the new value to the MA Sendspin player so server
     * state mirrors the phone. When sync is OFF the MA volume stays
     * independent — this is exactly what the car-BT case wants.
     */
    fun onPhoneStreamVolumeChanged(newPct: Int) {
        // The ContentObserver in SendspinCoordinator watches the entire
        // Settings.System URI and fires for any change (display brightness,
        // notification volume, anything). Filter to events that actually
        // moved STREAM_MUSIC, otherwise we'd open the echo window for
        // unrelated wakeups and starve legitimate MA volume echoes.
        val previous = lastKnownStreamMusicPct
        lastKnownStreamMusicPct = newPct
        if (previous == newPct) {
            Log.d(TAG, "stream_music wake $newPct% (unchanged, ignored)")
            return
        }
        if (!effectiveSyncEnabled()) {
            Log.d(TAG, "stream_music $previous%→$newPct% (sync OFF, no MA push)")
            return
        }
        val id = cachedSendspinPlayerId ?: return
        Log.d(TAG, "stream_music $previous%→$newPct% → push MA")
        recordLocalPush()
        playerRepository.applyVolumeOptimistic(id, newPct)
        launchPushToServer(id, newPct, reason = "phone_observer")
    }

    /**
     * The user moved the in-app volume slider for the Sendspin player. The
     * MA player always receives the new value (the slider IS the MA player
     * volume). When sync is ON we also write STREAM_MUSIC so the system bar
     * mirrors it; when sync is OFF, STREAM_MUSIC stays where the user set it.
     */
    fun onUiSliderChanged(pct: Int) {
        val id = cachedSendspinPlayerId ?: return
        val bounded = pct.coerceIn(0, 100)
        playerRepository.applyVolumeOptimistic(id, bounded)
        launchPushToServer(id, bounded, reason = "ui_slider")
        if (!effectiveSyncEnabled()) return
        writeStreamMusic(bounded)
        recordLocalPush()
    }

    /**
     * Sleep-timer fade: the timer wants a specific MA volume for this player
     * (could be Sendspin or any other player — but only Sendspin goes through
     * the coordinator). Pure MA push, never touches STREAM_MUSIC even if sync
     * is on (a system-volume change here would be a confusing surprise).
     */
    fun onSleepTimerFade(playerId: String, pct: Int) {
        val bounded = pct.coerceIn(0, 100)
        playerRepository.applyVolumeOptimistic(playerId, bounded)
        launchPushToServer(playerId, bounded, reason = "sleep_timer")
    }

    /**
     * Sendspin engine startup. Returns the volume that should be seeded into
     * the MA player config before the connection completes. Sync effective:
     * derive from current STREAM_MUSIC (phone speaker / wired / USB always
     * sync, BT only when the toggle is on). Sync disabled (BT + toggle off):
     * keep the MA player's last-known volume so a high STREAM_MUSIC pinned
     * for car BT doesn't get echoed into MA.
     */
    fun seedStartupVolume(): Int {
        return if (effectiveSyncEnabled()) {
            readPhoneStreamPct()
        } else {
            lastKnownMaVolume
        }
    }

    // endregion

    // region Observed server events

    private fun onServerVolumeEvent(pct: Int) {
        val bounded = pct.coerceIn(0, 100)
        lastKnownMaVolume = bounded
        val sinceLocal = System.currentTimeMillis() - lastLocalPushAtMs
        if (!effectiveSyncEnabled()) {
            Log.d(TAG, "server vol $bounded% (sync OFF, sinceLocal=${sinceLocal}ms)")
            return
        }
        if (awaitingBaseline) {
            awaitingBaseline = false
            Log.d(TAG, "server baseline $bounded% (no STREAM_MUSIC change)")
            return
        }
        if (sinceLocal < ECHO_WINDOW_MS) {
            Log.d(TAG, "echo-suppress server vol $bounded% (sinceLocal=${sinceLocal}ms)")
            return
        }
        Log.d(TAG, "apply server vol $bounded% → STREAM_MUSIC (sinceLocal=${sinceLocal}ms)")
        writeStreamMusic(bounded)
    }

    private fun onServerMuteEvent(muted: Boolean) {
        if (!effectiveSyncEnabled()) return
        val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        Log.d(TAG, "server mute=$muted")
    }

    // endregion

    // region Side-effect helpers

    private fun writeStreamMusic(pct: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val targetIndex = (pct * max + 50) / 100
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current == targetIndex) return
        // Pre-record the value the observer will see. The setStreamVolume
        // call below will fire the ContentObserver; onPhoneStreamVolumeChanged
        // would then read pct as "new", treat it as a user push, and bounce
        // it back to MA. Stamping lastKnownStreamMusicPct here makes the
        // observer see it as unchanged and short-circuit out cleanly.
        lastKnownStreamMusicPct = pct
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
        Log.d(TAG, "STREAM_MUSIC -> ${pct}% (index $targetIndex)")
    }

    private fun readPhoneStreamPct(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) ((cur * 100f / max) + 0.5f).toInt() else 0
    }

    private fun recordLocalPush() {
        lastLocalPushAtMs = System.currentTimeMillis()
    }

    private fun launchPushToServer(playerId: String, pct: Int, reason: String) {
        val s = scope ?: return
        s.launch {
            try {
                playerRepository.setVolume(playerId, pct)
            } catch (e: Exception) {
                Log.w(TAG, "push to MA failed ($reason): ${e.message}")
            }
        }
    }

    // endregion
}
