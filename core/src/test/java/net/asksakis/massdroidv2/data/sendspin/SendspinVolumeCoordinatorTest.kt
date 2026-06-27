package net.asksakis.massdroidv2.data.sendspin

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.PlayerSelectionLock
import org.junit.Test

/**
 * Behavioural lock for [SendspinVolumeCoordinator], the SOLE gateway for the
 * Sendspin player's STREAM_MUSIC side effects. These tests pin the gating that the
 * "full volume on connect (car audio)" + sync-toggle features rely on, so the
 * decoupling can't silently regress. (The recent "drops on track change" bug was a
 * second, ungated mirror that snapped a car-pinned volume back to a stale MA value;
 * it lived OUTSIDE this gateway, in MainActivity, and was removed. This suite locks
 * the gateway's own decisions.)
 *
 * AudioManager is the only Android dependency (mocked); every other input is an
 * injected flow/lambda. Collectors run on runTest's backgroundScope (auto-cancelled),
 * and the car-exit debounce is driven with virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendspinVolumeCoordinatorTest {

    private companion object {
        const val PLAYER = "ss-player"
        const val MAX_INDEX = 15
        const val CAR_KEY = "bt:MINI45864"
        // Index a STREAM_MUSIC percent maps to, mirroring writeStreamMusic's rounding.
        fun idx(pct: Int) = (pct * MAX_INDEX + 50) / 100
    }

    private class Fixture {
        val audioManager = mockk<AudioManager>(relaxed = true)
        val playerRepository = mockk<PlayerRepository>(relaxed = true)
        val serverVolumeEvents = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val serverMuteEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)
        var syncEnabledFlow: Flow<Boolean> = MutableStateFlow(true)
        val clientIdFlow = MutableStateFlow<String?>(PLAYER)
        val lastVolumeFlow = MutableStateFlow(80)
        val carDevicesFlow = MutableStateFlow<Set<String>>(emptySet())
        val persisted = mutableListOf<Int>()
        val recordedBt = mutableListOf<String>()

        // Mutable route view the injected lambdas read; tests flip these.
        @Volatile var routeType: Int? = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        @Volatile var btRouteKey: String? = null
        // Current STREAM_MUSIC index getStreamVolume returns. Kept distinct from the
        // indices under test so writeStreamMusic actually calls setStreamVolume
        // (it no-ops when current == target).
        @Volatile var streamIndex = 3
        @Volatile var devices: Array<AudioDeviceInfo> = emptyArray()

        init {
            every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns MAX_INDEX
            every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } answers { streamIndex }
            every { audioManager.getDevices(any()) } answers { devices }
            every { playerRepository.selectionLock } returns MutableStateFlow<PlayerSelectionLock?>(null)
        }

        fun build() = SendspinVolumeCoordinator(
            audioManager = audioManager,
            serverVolumeEvents = serverVolumeEvents,
            serverMuteEvents = serverMuteEvents,
            syncEnabledFlow = syncEnabledFlow,
            sendspinClientIdFlow = clientIdFlow,
            lastVolumeFlow = lastVolumeFlow,
            persistLastVolume = { persisted += it },
            playerRepository = playerRepository,
            currentOutputDeviceType = { routeType },
            currentBtRouteKey = { btRouteKey },
            carAudioDevicesFlow = carDevicesFlow,
            recordKnownBtDevice = { recordedBt += it },
        )

        fun btSink(name: String): AudioDeviceInfo = mockk {
            every { type } returns AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            every { productName } returns name
        }

        fun onPhoneRoute() {
            routeType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            btRouteKey = null
            devices = emptyArray()
        }

        fun onBtRoute(name: String = "MINI45864") {
            routeType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            btRouteKey = "bt:$name"
            devices = arrayOf(btSink(name))
        }

        fun assertNoWriteOf(index: Int) =
            verify(exactly = 0) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, any()) }
    }

    // region sync gating (phone / BT / toggle)

    @Test
    fun seed_phoneRouteSyncOn_mirrorsToStreamMusic() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture().apply { onPhoneRoute() }
        val c = f.build()
        c.start(backgroundScope)
        advanceUntilIdle()

        val seeded = c.seedStartupVolume()
        advanceUntilIdle()

        assertThat(seeded).isEqualTo(80)
        verify { f.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx(80), 0) }
    }

    @Test
    fun serverVolume_phoneRouteSyncOn_appliesAfterBaseline() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture().apply { onPhoneRoute() }
        val c = f.build()
        c.start(backgroundScope)
        advanceUntilIdle()

        // First server event only drops the baseline (no STREAM_MUSIC yank on start).
        f.serverVolumeEvents.emit(50)
        advanceUntilIdle()
        f.assertNoWriteOf(idx(50))

        // A subsequent genuine server change is mirrored.
        f.serverVolumeEvents.emit(60)
        advanceUntilIdle()
        verify { f.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx(60), 0) }
    }

    @Test
    fun serverVolume_btRouteSyncOff_doesNotMirror() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture().apply {
            onBtRoute()
            syncEnabledFlow = MutableStateFlow(false)
        }
        val c = f.build()
        c.start(backgroundScope)
        advanceUntilIdle()

        f.serverVolumeEvents.emit(50) // baseline
        f.serverVolumeEvents.emit(60)
        advanceUntilIdle()

        f.assertNoWriteOf(idx(60))
    }

    @Test
    fun serverVolume_btRouteNotHydrated_doesNotMirror() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture().apply {
            onBtRoute()
            // Never emits, so syncHydrated stays false: conservative OFF on BT.
            syncEnabledFlow = MutableSharedFlow()
        }
        val c = f.build()
        c.start(backgroundScope)
        advanceUntilIdle()

        f.serverVolumeEvents.emit(50)
        f.serverVolumeEvents.emit(60)
        advanceUntilIdle()

        f.assertNoWriteOf(idx(60))
    }

    // endregion

    // region car-audio session (pin / decouple / restore)

    @Test
    fun carConnect_flaggedRoute_pinsFullVolumeAndLocksTransport() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture().apply { onBtRoute() }
        val c = f.build()
        c.start(backgroundScope)
        advanceUntilIdle()

        f.carDevicesFlow.emit(setOf(CAR_KEY))
        advanceUntilIdle()

        verify { f.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx(100), 0) }
        verify { f.playerRepository.applyVolumeOptimistic(PLAYER, 100, any(), any()) }
        verify { f.playerRepository.setSelectionLock(PlayerSelectionLock(PLAYER, "car_audio")) }
        coVerify { f.playerRepository.setVolume(PLAYER, 100) }
    }

    @Test
    fun carConnect_nonFlaggedRoute_doesNotPinOrLock() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture().apply { onBtRoute("SomeSpeaker") } // route key bt:SomeSpeaker
        val c = f.build()
        c.start(backgroundScope)
        advanceUntilIdle()

        f.carDevicesFlow.emit(setOf(CAR_KEY)) // flagged set does NOT include this route
        advanceUntilIdle()

        f.assertNoWriteOf(idx(100))
        verify(exactly = 0) { f.playerRepository.setSelectionLock(any()) }
    }

    @Test
    fun carActive_serverVolume_doesNotTouchStreamMusic() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture().apply { onBtRoute() }
        val c = f.build()
        c.start(backgroundScope)
        advanceUntilIdle()
        f.carDevicesFlow.emit(setOf(CAR_KEY)) // enter car session (pins 100%)
        advanceUntilIdle()

        // The car re-asserting its level / any MA echo must NOT move STREAM_MUSIC
        // while decoupled: this is exactly the "drops on track change" guard.
        f.serverVolumeEvents.emit(40)
        f.serverVolumeEvents.emit(40)
        advanceUntilIdle()

        f.assertNoWriteOf(idx(40))
    }

    @Test
    fun carActive_slider_pushesMaButDoesNotTouchStreamMusic() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture().apply { onBtRoute() }
        val c = f.build()
        c.start(backgroundScope)
        advanceUntilIdle()
        f.carDevicesFlow.emit(setOf(CAR_KEY))
        advanceUntilIdle()

        c.onUiSliderChanged(50)
        advanceUntilIdle()

        coVerify { f.playerRepository.setVolume(PLAYER, 50) }
        f.assertNoWriteOf(idx(50))
    }

    @Test
    fun carDisconnect_afterDebounce_restoresPreCarAndReleasesLock() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture().apply { onBtRoute() }
        val c = f.build()
        c.start(backgroundScope)
        advanceUntilIdle()
        f.carDevicesFlow.emit(setOf(CAR_KEY)) // pre-car volume captured = phoneVolume (80)
        advanceUntilIdle()

        c.onBtRouteLost()
        advanceTimeBy(3_500) // past CAR_LOCK_CLEAR_DEBOUNCE_MS
        advanceUntilIdle()

        verify { f.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx(80), 0) }
        verify { f.playerRepository.applyVolumeOptimistic(PLAYER, 80, any(), eq(true)) }
        verify { f.playerRepository.setSelectionLock(null) }
        coVerify { f.playerRepository.setVolume(PLAYER, 80) }
    }

    @Test
    fun carDisconnect_flapReconnectBeforeDebounce_cancelsRestore() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture().apply { onBtRoute() }
        val c = f.build()
        c.start(backgroundScope)
        advanceUntilIdle()
        f.carDevicesFlow.emit(setOf(CAR_KEY))
        advanceUntilIdle()

        c.onBtRouteLost()
        advanceTimeBy(1_000)        // mid-debounce
        c.onBtRouteConnected()      // flap reconnect cancels the pending exit
        advanceTimeBy(3_500)
        advanceUntilIdle()

        // No restore-to-pre-car and no lock release happened.
        f.assertNoWriteOf(idx(80))
        verify(exactly = 0) { f.playerRepository.setSelectionLock(null) }
    }

    // endregion

    // region STREAM_MUSIC observer dedup + echo suppression

    @Test
    fun phoneStreamChanged_genuineUserChange_pushedToMa() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture().apply { onPhoneRoute(); streamIndex = 10 }
        val c = f.build()
        c.start(backgroundScope)
        advanceUntilIdle()

        c.onPhoneStreamVolumeChanged() // index 10, not our write, so genuine
        advanceUntilIdle()

        val pct = (10 * 100 / MAX_INDEX.toFloat() + 0.5f).toInt()
        coVerify { f.playerRepository.setVolume(PLAYER, pct) }
    }

    @Test
    fun phoneStreamChanged_ownMirrorWrite_notBouncedBack() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture().apply { onPhoneRoute() }
        val c = f.build()
        c.start(backgroundScope)
        advanceUntilIdle()

        // A slider change writes STREAM_MUSIC and stamps the index as "ours".
        c.onUiSliderChanged(60)
        advanceUntilIdle()
        // The broadcast that write triggers lands on the same index, recognised as
        // our own and dropped (no second, bounced push).
        f.streamIndex = idx(60)
        c.onPhoneStreamVolumeChanged()
        advanceUntilIdle()

        coVerify(exactly = 1) { f.playerRepository.setVolume(PLAYER, any()) }
    }

    @Test
    fun serverEcho_ofOurPush_notReApplied_genuineChange_applied() = runTest(UnconfinedTestDispatcher()) {
        val f = Fixture().apply { onPhoneRoute(); streamIndex = 12 }
        val c = f.build()
        c.start(backgroundScope)
        advanceUntilIdle()
        f.serverVolumeEvents.emit(70) // drop baseline
        advanceUntilIdle()

        // We push 50 (slider); the server echoes 50 back. It must be consumed, NOT
        // re-written to STREAM_MUSIC (the echo would otherwise yank the level).
        c.onUiSliderChanged(50)
        advanceUntilIdle()
        f.serverVolumeEvents.emit(50)
        advanceUntilIdle()
        verify(exactly = 1) { f.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx(50), 0) }

        // A non-matching server value is a genuine change and IS applied.
        f.serverVolumeEvents.emit(33)
        advanceUntilIdle()
        verify { f.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx(33), 0) }
    }

    // endregion
}
