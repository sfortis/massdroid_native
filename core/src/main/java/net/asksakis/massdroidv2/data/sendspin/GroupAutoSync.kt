package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lines up every member of a sync group on the server timestamp from one tap.
 *
 * Each member is measured on its own: the others are muted through Music
 * Assistant, the sync probe records the room, and the one remaining peak is
 * that member's lag after the timestamp. [SyncDelayPlanner] then picks every
 * member's delay, the delays are written the same way the Sync speakers
 * sliders write them, and the mute states are put back as they were.
 *
 * The recording happens where the phone is, so the result lines the speakers
 * up for that listening position, the distance to each speaker included.
 */
@Singleton
class GroupAutoSync @Inject constructor(
    private val sendspinManager: SendspinManager,
    private val playerRepository: PlayerRepository,
    private val settingsRepository: SettingsRepository,
) {
    sealed interface State {
        data object Idle : State
        data class Measuring(val playerName: String, val index: Int, val total: Int) : State
        data class Done(val plan: SyncDelayPlan, val names: Map<String, String>) : State
        data class Failed(val reason: String) : State
    }

    companion object {
        private const val TAG = "GroupAutoSync"
        // How long MA may take to confirm the mute states before measuring anyway.
        private const val MUTE_CONFIRM_TIMEOUT_MS = 3_000L
        // A speaker keeps playing what is already in its output pipeline after it
        // is muted, about 200 ms on the Pi and more on a Cast device; this lets it
        // drain so the recording holds only the member being measured.
        private const val MUTE_SETTLE_MS = 1_000L
        // Below this the analysis did not clearly match the stream.
        private const val MIN_CLARITY = 8.0
        // A second peak this strong means another speaker was still audible.
        private const val MAX_SECOND_PEAK = 0.5
        // Peaks this close to the strongest are the same speaker: a reflection,
        // or a second driver in the same box.
        private const val SAME_SPEAKER_US = 10_000L
        // sendspin-cli with hardware volume applies mute and volume through async
        // PulseAudio calls, and a second command to the same player inside this
        // window left its sink muted (2026-10-02). setVolume updates the player
        // list optimistically, so the list cannot confirm a volume change; this
        // spacing is what keeps the two apart.
        private const val COMMAND_SPACING_MS = 500L
        // A member quieter than this is raised to it while it is measured, so it
        // stands well above the room noise, and put back afterwards.
        private const val MIN_MEASURE_VOLUME = 40
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The caller has already been granted RECORD_AUDIO. */
    fun start(memberIds: List<String>, ourPlayerId: String) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = runCatching { run(memberIds, ourPlayerId) }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "auto sync failed", e)
                    State.Failed("Couldn't sync the speakers. ${e.message ?: "Something went wrong."}")
                }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = State.Idle
    }

    fun dismiss() {
        if (_state.value is State.Done || _state.value is State.Failed) _state.value = State.Idle
    }

    private class Member(val id: String, val name: String, val control: DelayControl, val key: String?)

    private suspend fun run(memberIds: List<String>, ourPlayerId: String): State {
        sendspinManager.syncProbeBlocker()?.let { return State.Failed(it) }
        val players = playerRepository.players.value.filter { it.playerId in memberIds && it.available }
        if (players.none { it.playerId == ourPlayerId }) {
            return State.Failed("Couldn't sync the speakers. This phone is not in the group.")
        }
        val members = players.map { player ->
            if (player.playerId == ourPlayerId) {
                Member(
                    player.playerId, player.displayName,
                    DelayControl.Signed(settingsRepository.sendspinSyncDelayMs.first()), key = null
                )
            } else {
                val config = playerRepository.getPlayerConfig(player.playerId)
                val syncKey = config?.sendspinSyncDelayKey
                val staticKey = config?.sendspinStaticDelayKey
                when {
                    syncKey != null ->
                        Member(player.playerId, player.displayName, DelayControl.Signed(config.sendspinSyncDelayMs ?: 0), syncKey)
                    staticKey != null ->
                        Member(player.playerId, player.displayName, DelayControl.Static(config.sendspinStaticDelayMs ?: 0), staticKey)
                    else -> Member(player.playerId, player.displayName, DelayControl.None, key = null)
                }
            }
        }
        val originalMutes = players.associate { it.playerId to it.volumeMuted }
        val originalVolumes = players.associate { it.playerId to it.volumeLevel }

        // Members whose volume this run raised; only these are put back.
        val raised = mutableSetOf<String>()
        val lags = try {
            sendspinManager.syncProbeSession { session ->
                members.mapIndexed { index, member ->
                    _state.value = State.Measuring(member.name, index + 1, members.size)
                    solo(member.id, members.map { it.id })
                    // Raised only after the mutes are confirmed, so a mute and a volume
                    // change never reach the same player together (see restore).
                    val volume = originalVolumes[member.id] ?: MIN_MEASURE_VOLUME
                    if (volume < MIN_MEASURE_VOLUME) {
                        delay(COMMAND_SPACING_MS)
                        playerRepository.setVolume(member.id, MIN_MEASURE_VOLUME)
                        raised += member.id
                    }
                    delay(MUTE_SETTLE_MS)
                    member.id to lagOf(session.measure(windowFor(member.control)), member.name)
                }.toMap()
            }
        } finally {
            withContext(NonCancellable) { restore(originalMutes, originalVolumes, raised) }
        }

        val plan = SyncDelayPlanner.plan(members.map { MemberMeasurement(it.id, lags[it.id], it.control) })
        apply(plan, members)
        Log.i(TAG, "target=${plan.targetUs / 1000.0}ms " + plan.members.joinToString { p ->
            "${p.playerId}: lag=${p.lagUs?.div(1000.0)}ms new=${p.newValueMs} residual=${p.residualUs?.div(1000.0)}ms"
        })
        return State.Done(plan, members.associate { it.id to it.name })
    }

    /** Unmutes [soloId], mutes every other member, and waits for MA to confirm it. */
    private suspend fun solo(soloId: String, allIds: List<String>) {
        val wanted = allIds.associateWith { it != soloId }
        setMutes(wanted)
        awaitPlayers { list -> wanted.all { (id, muted) -> list.mutedOf(id) == muted } }
    }

    private suspend fun setMutes(wanted: Map<String, Boolean>) {
        val current = playerRepository.players.value.associate { it.playerId to it.volumeMuted }
        wanted.forEach { (id, muted) ->
            if (current[id] != muted) runCatching { playerRepository.toggleMute(id, muted) }
        }
    }

    /**
     * Puts back the raised volumes, then the mute states, also after a cancel.
     *
     * The order matters. sendspin-cli with hardware volume left its sink muted
     * when an unmute and a volume change arrived 20 ms apart (2026-10-02), so
     * the volumes go first, [COMMAND_SPACING_MS] apart from the mutes, and the
     * unmute is the last command each player gets. A mute state MA still
     * reports wrong is sent once more.
     */
    private suspend fun restore(mutes: Map<String, Boolean>, volumes: Map<String, Int>, raised: Set<String>) {
        raised.forEach { id -> volumes[id]?.let { runCatching { playerRepository.setVolume(id, it) } } }
        if (raised.isNotEmpty()) delay(COMMAND_SPACING_MS)
        setMutes(mutes)
        val confirmed = awaitPlayers { list -> mutes.all { (id, muted) -> list.mutedOf(id) == muted } }
        if (!confirmed) setMutes(mutes)
    }

    /** Waits until [condition] holds for the player list; false if MA did not confirm in time. */
    private suspend fun awaitPlayers(condition: (List<Player>) -> Boolean): Boolean =
        withTimeoutOrNull(MUTE_CONFIRM_TIMEOUT_MS) { playerRepository.players.first(condition) } != null

    private fun List<Player>.mutedOf(id: String): Boolean? = firstOrNull { it.playerId == id }?.volumeMuted

    /**
     * Where to look for a member: the default window, moved by the shift its
     * current delay applies. A static delay of 1595 ms puts a speaker about
     * 1.6 s early, and the window follows it there without widening, which
     * would let loop-based music match itself a beat or a bar away.
     */
    private fun windowFor(control: DelayControl): LongRange {
        val centreUs = when (control) {
            is DelayControl.Static -> -control.currentMs * 1000L
            is DelayControl.Signed -> control.currentMs * 1000L
            DelayControl.None -> 0L
        }
        val window = SyncProbeAnalyzer.DEFAULT_WINDOW_US
        return (window.first + centreUs)..(window.last + centreUs)
    }

    /** The member's lag, or null when it was not heard clearly and alone. */
    private fun lagOf(outcome: SyncProbeOutcome, name: String): Long? {
        val analysis = (outcome as? SyncProbeOutcome.Success)?.result?.analysis
        if (analysis == null) {
            Log.w(TAG, "$name: ${(outcome as SyncProbeOutcome.Failure).reason}")
            return null
        }
        val peaks = analysis.peaks
        val strongest = peaks.firstOrNull()
        val second = peaks.drop(1)
            .filter { strongest != null && kotlin.math.abs(it.lagUs - strongest.lagUs) > SAME_SPEAKER_US }
            .maxOfOrNull { it.strength } ?: 0.0
        if (strongest == null || analysis.clarity < MIN_CLARITY || second >= MAX_SECOND_PEAK) {
            Log.w(TAG, "$name: not measured, clarity=${analysis.clarity} peaks=$peaks")
            return null
        }
        return strongest.lagUs
    }

    private suspend fun apply(plan: SyncDelayPlan, members: List<Member>) {
        val byId = members.associateBy { it.id }
        plan.members.forEach { p ->
            val member = byId[p.playerId] ?: return@forEach
            val value = p.newValueMs ?: return@forEach
            val current = when (val c = member.control) {
                is DelayControl.Static -> c.currentMs
                is DelayControl.Signed -> c.currentMs
                DelayControl.None -> return@forEach
            }
            if (value == current) return@forEach
            if (member.key == null) {
                settingsRepository.setSendspinSyncDelayMs(value)
            } else {
                playerRepository.savePlayerConfig(member.id, mapOf(member.key to value))
            }
        }
    }
}
