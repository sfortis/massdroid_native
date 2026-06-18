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
 * to move the Sendspin volume — server-pushed events, hardware keys (via the
 * STREAM_MUSIC observer), the in-app slider, sleep-timer fades, the engine's
 * startup re-seed — calls one method here. The coordinator owns:
 *
 *  1. The single `syncEnabled` switch (mirrored from
 *     `SettingsRepository.sendspinSyncSystemVolume`), scoped to the BT route.
 *  2. STREAM_MUSIC <-> MA round-trip dedup.
 *  3. The baseline-drop on first server event (so a stale MA-side cache
 *     doesn't yank STREAM_MUSIC on Sendspin start).
 *
 * ## Dedup model (index-based, single source of truth = MA player volume)
 *
 * The hard problem: Android's `Settings.System` ContentObserver does NOT say
 * who changed STREAM_MUSIC (the user via HW keys, or our own write mirroring
 * the MA volume). The previous implementation matched echoed *percent* values
 * against a multiset of pushes, with multi-second time windows. That was
 * fragile and had a real bug: STREAM_MUSIC has coarse steps (~15 on many
 * phones), so the percent we pushed and the percent re-derived from the actual
 * index differ (70% -> index 11 -> reads back 73%), which the observer saw as a
 * fresh user change and bounced back to MA — a self-sustaining fight.
 *
 * This version tracks the actual STREAM_MUSIC **index** we last set or observed
 * (`lastKnownStreamIndex`). Any observer fire whose current index equals that is
 * either our own mirror write or an unrelated Settings.System URI wake (the
 * observer watches the whole URI) — ignored. A different index is a genuine user
 * change. No percent value-matching, so it is immune to the index<->percent
 * quantization round-trip, and a server echo simply writes STREAM_MUSIC to the
 * same index (a no-op) instead of needing a pending-echo entry.
 */
class SendspinVolumeCoordinator(
    private val audioManager: AudioManager,
    private val serverVolumeEvents: SharedFlow<Int>,
    private val serverMuteEvents: SharedFlow<Boolean>,
    private val syncEnabledFlow: Flow<Boolean>,
    private val sendspinClientIdFlow: Flow<String?>,
    /**
     * Persisted last Sendspin player volume (0..100), survives process death.
     * Used as the startup seed because the live STREAM_MUSIC read is unreliable
     * at launch (per-route storage; our audio route is gone on a fresh start).
     */
    private val lastVolumeFlow: Flow<Int>,
    private val persistLastVolume: suspend (Int) -> Unit,
    private val playerRepository: PlayerRepository,
    /**
     * Returns the current audio output route type as reported by the active
     * Sendspin AudioTrack (`AudioDeviceInfo.TYPE_*`), or `null` if there's no
     * authoritative route yet. Lets the coordinator scope the sync-disable
     * toggle to the Bluetooth route only — on the phone speaker, wired
     * headphones, or USB, STREAM_MUSIC always tracks the MA player volume.
     */
    private val currentOutputDeviceType: () -> Int?,
    /** Current BT output device route key (`bt:NAME`), or null if not on BT. */
    private val currentBtRouteKey: () -> String?,
    /** Route keys flagged as car audio (full volume on connect, no bridge). */
    private val carAudioDevicesFlow: Flow<Set<String>>,
    /** Record a BT route key as seen (for the car-audio picker). */
    private val recordKnownBtDevice: suspend (String) -> Unit,
) {
    companion object {
        private const val TAG = "SsVolCoord"
        // Window during which STREAM_MUSIC observer fires are NOT pushed to MA
        // because they're route-switching artifacts, not user volume changes.
        // Android stores STREAM_MUSIC per output route; a BT connect/disconnect
        // flaps bt->speaker->bt over ~3-4s (each flap fires the observer with
        // the *other* route's stored level). Re-armed on every route change.
        private const val ROUTE_TRANSITION_SUPPRESS_MS = 3_000L
        // After we write STREAM_MUSIC ourselves (mirroring a server/slider value),
        // ignore observer fires for this long. The index-equality check below is
        // the primary self-write filter, but some devices (Samsung) settle the
        // volume curve asynchronously and fire the observer one or more times
        // with an index that differs from the one we set — which would be misread
        // as a user change and pushed back to MA, amplifying into a volume_set
        // flood/oscillation in group mode. This window makes the self-write
        // filter robust to that. Short enough that a genuine user change right
        // after is only missed once (the next change reconciles).
        private const val SELF_WRITE_SETTLE_MS = 500L
        // Upper bound on how long we wait for the server to echo a value we
        // pushed. Sized above real-world WS round-trips (in-car cellular ~2s).
        private const val ECHO_EXPIRY_MS = 6_000L
        private const val MAX_PENDING_ECHOES = 24
    }

    private data class PendingEcho(val pct: Int, val atMs: Long)

    /**
     * Values we pushed to MA whose server echo must NOT be written back to
     * STREAM_MUSIC. This is the MA->STREAM_MUSIC leg: while the user ramps the
     * HW keys (or drags the slider) we push each step to MA; the server echoes
     * each one back, and applying that echo to STREAM_MUSIC would yank the level
     * back to a stale step the user has already moved past (the visible "jump").
     * Value-based (not index) because the server echoes the percent we sent; the
     * other leg (STREAM_MUSIC->MA dedup) uses the index and is quantization-safe.
     * Guarded by its own monitor: pushes come from the observer/UI threads,
     * echoes arrive on the coordinator coroutine.
     */
    private val pendingEchoes = ArrayDeque<PendingEcho>()

    private var jobs: MutableList<Job> = mutableListOf()
    private var scope: CoroutineScope? = null

    @Volatile private var syncEnabled: Boolean = true
    // True once syncEnabledFlow has emitted at least once. Until then we don't
    // know the persisted toggle, so on BT we treat sync as OFF (conservative —
    // never yank a car-pinned STREAM_MUSIC level during the brief DataStore
    // hydration window on cold start).
    @Volatile private var syncHydrated: Boolean = false
    // Route keys the user flagged as car audio: pin STREAM_MUSIC 100% on connect,
    // and disable the STREAM_MUSIC<->MA bridge for them.
    @Volatile private var carAudioDevices: Set<String> = emptySet()
    @Volatile private var awaitingBaseline: Boolean = true
    @Volatile private var lastKnownMaVolume: Int = 100
    @Volatile private var cachedSendspinPlayerId: String? = null
    @Volatile private var suppressPushUntilMs: Long = 0L

    /**
     * The STREAM_MUSIC index we last set ourselves or observed from a genuine
     * user change. Equality against the current index distinguishes our own
     * mirror writes (and unrelated URI wakes) from real user changes, with no
     * percent value-matching. -1 = not yet known.
     */
    @Volatile private var lastKnownStreamIndex: Int = -1
    // Set when we write STREAM_MUSIC ourselves; observer fires before this are
    // our own write settling (see SELF_WRITE_SETTLE_MS), not user changes.
    @Volatile private var selfWriteUntilMs: Long = 0L

    /**
     * The user-facing sync toggle only applies on Bluetooth. On any other route
     * STREAM_MUSIC always tracks the MA player volume. Falls back to
     * getDevices() when the active AudioTrack route is momentarily null.
     */
    private fun effectiveSyncEnabled(): Boolean {
        val routeType = currentOutputDeviceType()
        val routeIsBt = routeType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            routeType == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            routeType == AudioDeviceInfo.TYPE_BLE_SPEAKER
        // Treat "on BT" as: the resolved route is BT, OR any A2DP/BLE sink is
        // connected. During a BT connect/disconnect FLAP the resolved route
        // momentarily reads SPEAKER while A2DP is still settling; keying only off
        // the route would fall into the non-BT "always sync" branch and push a
        // transient STREAM_MUSIC level to MA — resetting a car-pinned volume even
        // with the toggle OFF. The device-presence check keeps the toggle honoured
        // across the whole flap.
        val btConnected = routeIsBt || audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        if (!btConnected) return true
        // Car-audio devices are fully decoupled: STREAM_MUSIC is pinned to 100%
        // on connect and the head unit's dial does the attenuation, so the bridge
        // must never push/pull their volume.
        val key = currentBtRouteKey()
        if (key != null && key in carAudioDevices) return false
        // Other BT: honour the toggle — but until the persisted value has loaded,
        // default to OFF so a stale MA volume can't yank a car-pinned level.
        return syncHydrated && syncEnabled
    }

    /**
     * The output route just settled on a BT device. Remembers it for the
     * car-audio picker, and — if the user flagged it as car audio — pins
     * STREAM_MUSIC to 100% once so the head unit's own dial controls loudness
     * (and the decoupled bridge leaves it there).
     */
    fun onBtRouteConnected() {
        val key = currentBtRouteKey() ?: return
        scope?.launch { recordKnownBtDevice(key) }
        if (key in carAudioDevices) {
            Log.d(TAG, "car-audio device $key connected -> full volume (system + player 100%)")
            writeStreamMusic(100)
            // Also raise the MA player volume to 100 so the in-app slider reflects
            // the full-open phone output (the head unit's dial does the real
            // attenuation). recordLocalPush suppresses the echo so it doesn't loop.
            cachedSendspinPlayerId?.let { id ->
                recordLocalPush(100)
                playerRepository.applyVolumeOptimistic(id, 100)
                launchPushToServer(id, 100, reason = "car-audio-pin")
            }
        }
    }

    /**
     * Lifecycle: bind to a scope and start observing flows. Idempotent.
     * Hydration of the persisted sync setting and client id is async; inputs
     * that need the client id bail while it is still null.
     */
    fun start(coroutineScope: CoroutineScope) {
        if (jobs.isNotEmpty()) return
        scope = coroutineScope
        awaitingBaseline = true
        jobs += coroutineScope.launch {
            syncEnabledFlow.collect { enabled ->
                syncHydrated = true
                if (syncEnabled != enabled) {
                    Log.d(TAG, "syncEnabled=$enabled")
                    syncEnabled = enabled
                }
            }
        }
        jobs += coroutineScope.launch {
            carAudioDevicesFlow.collect { carAudioDevices = it }
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
     * The system observed STREAM_MUSIC possibly changing (ContentObserver fire).
     * The coordinator reads the current index itself: if it equals the index we
     * last set/observed, this is our own mirror write or an unrelated URI wake —
     * ignored. A different index is a genuine user change (HW keys, system bar),
     * mirrored to the MA Sendspin player when sync is effective.
     */
    fun onPhoneStreamVolumeChanged() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val index = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (index == lastKnownStreamIndex) {
            return  // our own write, or an unrelated Settings.System wake
        }
        if (System.currentTimeMillis() < selfWriteUntilMs) {
            // Our own STREAM_MUSIC write is still settling (device fired the
            // observer with a curve-adjusted index). Adopt it, do NOT push back.
            lastKnownStreamIndex = index
            return
        }
        lastKnownStreamIndex = index
        val pct = ((index * 100f / max) + 0.5f).toInt()
        if (!effectiveSyncEnabled()) {
            Log.d(TAG, "stream_music ->$pct% (sync OFF, no MA push)")
            return
        }
        // During a route transition (BT connect/disconnect flap) STREAM_MUSIC
        // reflects the route's own stored level, not a user action.
        if (System.currentTimeMillis() < suppressPushUntilMs) {
            Log.d(TAG, "stream_music ->$pct% (route transition, no MA push)")
            return
        }
        val id = cachedSendspinPlayerId ?: return
        Log.d(TAG, "stream_music ->$pct% (index $index) -> push MA")
        recordLocalPush(pct)
        playerRepository.applyVolumeOptimistic(id, pct)
        launchPushToServer(id, pct, reason = "phone_observer")
    }

    /**
     * The audio output route is changing (BT connect/disconnect, device switch).
     * Opens a short window during which STREAM_MUSIC observer fires are treated
     * as route-switching artifacts and not mirrored to MA. Re-arming on each
     * flap event keeps the whole transient covered.
     */
    fun onOutputRouteChanging() {
        suppressPushUntilMs = System.currentTimeMillis() + ROUTE_TRANSITION_SUPPRESS_MS
        // A route switch loads the new route's own STREAM_MUSIC level; forget the
        // old route's index so the first post-switch observer fire is judged
        // against the new route, not the old one.
        lastKnownStreamIndex = -1
        Log.d(TAG, "route transition: suppressing STREAM_MUSIC->MA pushes for ${ROUTE_TRANSITION_SUPPRESS_MS}ms")
    }

    /**
     * The user moved the in-app volume slider for the Sendspin player. The MA
     * player always receives the new value (the slider IS the MA player volume).
     * When sync is effective we also mirror STREAM_MUSIC.
     */
    fun onUiSliderChanged(pct: Int) {
        val id = cachedSendspinPlayerId ?: return
        val bounded = pct.coerceIn(0, 100)
        recordLocalPush(bounded)
        playerRepository.applyVolumeOptimistic(id, bounded)
        launchPushToServer(id, bounded, reason = "ui_slider")
        if (effectiveSyncEnabled()) writeStreamMusic(bounded)
    }

    /**
     * Sleep-timer fade: pure MA push for the given player, never touches
     * STREAM_MUSIC even if sync is on.
     */
    fun onSleepTimerFade(playerId: String, pct: Int) {
        val bounded = pct.coerceIn(0, 100)
        playerRepository.applyVolumeOptimistic(playerId, bounded)
        launchPushToServer(playerId, bounded, reason = "sleep_timer")
    }

    /**
     * Sendspin engine startup: the volume to seed into the MA player config.
     * Always sourced from the persisted last Sendspin volume — the server
     * resets a re-registering player to 100%, and the live STREAM_MUSIC read is
     * unreliable at launch (Android stores it per output route; the app's audio
     * route is torn down on the previous exit, so STREAM_MUSIC can reflect a
     * different route's level). When STREAM_MUSIC mirrors the MA volume on this
     * route, we also rewrite it to the restored value so the mirror is
     * consistent and the first observer fire isn't misread as a user change.
     */
    suspend fun seedStartupVolume(): Int {
        val persisted = lastVolumeFlow.first().coerceIn(0, 100)
        lastKnownMaVolume = persisted
        if (effectiveSyncEnabled()) writeStreamMusic(persisted)
        return persisted
    }

    // endregion

    // region Observed server events

    private fun onServerVolumeEvent(pct: Int) {
        val bounded = pct.coerceIn(0, 100)
        if (bounded != lastKnownMaVolume) {
            // Persist the authoritative MA player volume so we can restore it on
            // the next launch (the server resets a re-registering player to
            // 100%). Every genuine volume change — slider, HW keys, group — is
            // echoed here, so this is the single persist point.
            scope?.launch { persistLastVolume(bounded) }
        }
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
        // MA->STREAM_MUSIC leg: suppress the echo of a value WE just pushed, so a
        // late echo can't yank STREAM_MUSIC back to a step the user already
        // ramped past (the "jump"). A non-matching value is a genuine server
        // change and is applied; writeStreamMusic stamps lastKnownStreamIndex so
        // the resulting observer fire is recognised as our own (Leg A) write.
        if (consumePendingEcho(bounded)) {
            Log.d(TAG, "echo-suppress server vol $bounded% (matched our push)")
            return
        }
        Log.d(TAG, "apply server vol $bounded% -> STREAM_MUSIC")
        writeStreamMusic(bounded)
    }

    private fun onServerMuteEvent(muted: Boolean) {
        if (!effectiveSyncEnabled()) return
        val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        // Re-stamp the index so the resulting observer fire is treated as our
        // own write rather than a user change.
        lastKnownStreamIndex = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        Log.d(TAG, "server mute=$muted")
    }

    // endregion

    // region Side-effect helpers

    private fun writeStreamMusic(pct: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val targetIndex = (pct * max + 50) / 100
        // Stamp BEFORE the write (and even on a no-op) so the observer fire the
        // setStreamVolume below triggers is recognised as our own and ignored.
        lastKnownStreamIndex = targetIndex
        // Arm the settle window so any curve-adjusted observer fire (Samsung) is
        // also treated as our own write, not a user change.
        selfWriteUntilMs = System.currentTimeMillis() + SELF_WRITE_SETTLE_MS
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current == targetIndex) return
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
        Log.d(TAG, "STREAM_MUSIC -> ${pct}% (index $targetIndex)")
    }

    private fun readPhoneStreamPct(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) ((cur * 100f / max) + 0.5f).toInt() else 0
    }

    /** Remember a value we pushed to MA so its eventual server echo is not
     *  written back to STREAM_MUSIC. Drops expired entries and caps the queue. */
    private fun recordLocalPush(pct: Int) {
        val now = System.currentTimeMillis()
        synchronized(pendingEchoes) {
            pendingEchoes.addLast(PendingEcho(pct, now))
            while (pendingEchoes.isNotEmpty() && now - pendingEchoes.first().atMs > ECHO_EXPIRY_MS) {
                pendingEchoes.removeFirst()
            }
            while (pendingEchoes.size > MAX_PENDING_ECHOES) pendingEchoes.removeFirst()
        }
    }

    /** If [pct] matches a still-pending push, consume it and return true (our own
     *  echo, do not re-apply). A non-match is a genuine server change. */
    private fun consumePendingEcho(pct: Int): Boolean {
        val now = System.currentTimeMillis()
        synchronized(pendingEchoes) {
            while (pendingEchoes.isNotEmpty() && now - pendingEchoes.first().atMs > ECHO_EXPIRY_MS) {
                pendingEchoes.removeFirst()
            }
            val it = pendingEchoes.iterator()
            while (it.hasNext()) {
                if (it.next().pct == pct) { it.remove(); return true }
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
