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
        // Upper bound for how long we keep waiting for the server to echo a
        // value we pushed. Sized well above real-world WS round-trips: in-car
        // cellular while moving measured 1.7-2.1s, which exceeded the old
        // fixed 1.5s suppression window and bounced STREAM_MUSIC when the late
        // echo was misread as a fresh server change. Pending entries clear the
        // instant their matching echo arrives, so this only bounds a push
        // whose echo never comes back (e.g. the server coalesced it).
        private const val ECHO_EXPIRY_MS = 6_000L
        private const val MAX_PENDING_ECHOES = 16
        // Window during which STREAM_MUSIC observer fires are NOT pushed to MA
        // because they're route-switching artifacts, not user volume changes.
        // Android stores STREAM_MUSIC per output route; a BT connect/disconnect
        // flaps bt->speaker->bt over ~3-4s (each flap fires the observer with
        // the *other* route's stored level). Measured car connects produce the
        // last spurious push ~4s after the first route event, and the window is
        // re-armed on every route change, so 3s past the final flap covers it.
        private const val ROUTE_TRANSITION_SUPPRESS_MS = 3_000L
    }

    private data class PendingEcho(val pct: Int, val atMs: Long)

    private var jobs: MutableList<Job> = mutableListOf()
    private var scope: CoroutineScope? = null

    @Volatile private var syncEnabled: Boolean = true
    @Volatile private var awaitingBaseline: Boolean = true
    @Volatile private var lastKnownMaVolume: Int = 100
    @Volatile private var cachedSendspinPlayerId: String? = null
    @Volatile private var suppressPushUntilMs: Long = 0L

    /**
     * Volume values we pushed to MA and still expect the server to echo back.
     * Echo suppression is value-based, not purely time-based: the old fixed
     * 1.5s window was shorter than the in-car WS round-trip (1.7-2.1s), so a
     * user's own volume change leaked back through `onServerVolumeEvent` after
     * the window closed and yanked STREAM_MUSIC back down. Matching the echoed
     * value against what we pushed tolerates arbitrary echo latency, and the
     * multiset shape handles rapid multi-step ramps (53→60→73) where the
     * server echoes each intermediate step out of phase. Guarded by its own
     * monitor because pushes come from the observer/UI threads while echoes
     * arrive on the coordinator's coroutine.
     */
    private val pendingEchoes = ArrayDeque<PendingEcho>()
    /**
     * The last STREAM_MUSIC value we know about — either from a write we
     * performed ourselves or from an observer notification. Used to ignore
     * `Settings.System` ContentObserver fires that don't actually change
     * STREAM_MUSIC (the observer subscribes to the whole system-settings URI,
     * so any unrelated system change wakes it up). Without this filter the
     * spurious wakeups would each record a pending echo and bounce an unrelated
     * value to MA, causing legitimate server volume events to be suppressed and
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
        // During a route transition (BT connect/disconnect flap) STREAM_MUSIC
        // reflects the route's own stored level, not a user action. Pushing it
        // would overwrite the Sendspin player volume with the phone-speaker
        // baseline on every car reconnect. The actual playback gain is
        // STREAM_MUSIC itself, so skipping the MA mirror here is purely
        // cosmetic until the next genuine user change reconciles it.
        if (System.currentTimeMillis() < suppressPushUntilMs) {
            Log.d(TAG, "stream_music $previous%→$newPct% (route transition, no MA push)")
            return
        }
        val id = cachedSendspinPlayerId ?: return
        Log.d(TAG, "stream_music $previous%→$newPct% → push MA")
        recordLocalPush(newPct)
        playerRepository.applyVolumeOptimistic(id, newPct)
        launchPushToServer(id, newPct, reason = "phone_observer")
    }

    /**
     * The audio output route is changing (BT connect/disconnect, device
     * switch). Opens a short window during which STREAM_MUSIC observer fires
     * are treated as route-switching artifacts and not mirrored to MA. Called
     * by [SendspinAudioController] from its route-change detector and noisy
     * receiver; re-arming on each flap event keeps the whole transient covered.
     */
    fun onOutputRouteChanging() {
        suppressPushUntilMs = System.currentTimeMillis() + ROUTE_TRANSITION_SUPPRESS_MS
        Log.d(TAG, "route transition: suppressing STREAM_MUSIC->MA pushes for ${ROUTE_TRANSITION_SUPPRESS_MS}ms")
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
        recordLocalPush(bounded)
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
        if (!effectiveSyncEnabled()) {
            Log.d(TAG, "server vol $bounded% (sync OFF)")
            return
        }
        if (awaitingBaseline) {
            awaitingBaseline = false
            Log.d(TAG, "server baseline $bounded% (no STREAM_MUSIC change)")
            return
        }
        if (consumePendingEcho(bounded)) {
            Log.d(TAG, "echo-suppress server vol $bounded% (matched pending push)")
            return
        }
        Log.d(TAG, "apply server vol $bounded% → STREAM_MUSIC")
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

    /**
     * Remember a value we just pushed to MA so its eventual server echo is
     * recognised and suppressed. Drops entries older than [ECHO_EXPIRY_MS]
     * and caps the queue so a burst of un-echoed pushes can't grow unbounded.
     */
    private fun recordLocalPush(pct: Int) {
        val now = System.currentTimeMillis()
        synchronized(pendingEchoes) {
            pendingEchoes.addLast(PendingEcho(pct, now))
            while (pendingEchoes.isNotEmpty() && now - pendingEchoes.first().atMs > ECHO_EXPIRY_MS) {
                pendingEchoes.removeFirst()
            }
            while (pendingEchoes.size > MAX_PENDING_ECHOES) {
                pendingEchoes.removeFirst()
            }
        }
    }

    /**
     * If [pct] matches a still-pending value we pushed, consume that entry and
     * return true (it's our own echo, suppress it). A non-matching value is a
     * genuine server-originated change and must be applied. Expired entries are
     * purged first so a stale push that never echoed can't shadow a real change.
     */
    private fun consumePendingEcho(pct: Int): Boolean {
        val now = System.currentTimeMillis()
        synchronized(pendingEchoes) {
            while (pendingEchoes.isNotEmpty() && now - pendingEchoes.first().atMs > ECHO_EXPIRY_MS) {
                pendingEchoes.removeFirst()
            }
            val iterator = pendingEchoes.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().pct == pct) {
                    iterator.remove()
                    return true
                }
            }
        }
        return false
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
