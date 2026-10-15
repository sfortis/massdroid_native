package net.asksakis.massdroidv2.domain.discover

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.asksakis.massdroidv2.data.websocket.SessionEventBus
import net.asksakis.massdroidv2.domain.discover.MixLauncher.Mix
import net.asksakis.massdroidv2.domain.recommendation.MixPlaybackOrchestrator
import net.asksakis.massdroidv2.domain.recommendation.MixPlaybackOrchestrator.MixResult
import net.asksakis.massdroidv2.presentation.discover.userMessage
import org.junit.Test

/**
 * One mix at a time for every caller, the genre check against a loaded feed, the double press
 * guard, and the outcome each start publishes.
 */
class MixLauncherTest {

    private val orchestrator = mockk<MixPlaybackOrchestrator>()
    private val feedStore = mockk<DiscoverFeedStore> {
        every { libraryContext() } returns null
    }
    private val sessionEventBus = SessionEventBus()
    // A clock that does not move, so the double press window can never run out mid-test.
    private val launcher = MixLauncher(orchestrator, feedStore, sessionEventBus).apply { clock = { 1_000L } }

    @Test
    fun `a Smart Mix is built on the queue and its outcome is published`() = runBlocking {
        coEvery { orchestrator.playSmartMix("queue", null) } returns MixResult.Played(25, null)
        val outcome = async { launcher.outcomes.first() }
        delay(SETTLE_MS)

        launcher.startSmartMix("queue")

        assertThat(withTimeout(WAIT_MS) { outcome.await() })
            .isEqualTo(MixLauncher.Outcome(Mix.SmartMix, MixResult.Played(25, null)))
        withTimeout(WAIT_MS) { launcher.activity.first { it == null } }
        Unit
    }

    @Test
    fun `a start while another mix is building is ignored`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        coEvery { orchestrator.playSmartMix(any(), any()) } coAnswers {
            release.await()
            MixResult.Played(25, null)
        }
        launcher.startSmartMix("queue")
        withTimeout(WAIT_MS) { launcher.activity.first { it == Mix.SmartMix } }

        launcher.startGenreRadio("queue", "rock")
        release.complete(Unit)
        withTimeout(WAIT_MS) { launcher.activity.first { it == null } }

        coVerify(exactly = 0) { orchestrator.playGenreRadio(any(), any(), any()) }
    }

    @Test
    fun `a genre with no artists in the loaded feed is refused before anything is built`() = runBlocking {
        every { feedStore.libraryContext() } returns MixPlaybackOrchestrator.LibraryContext(
            artistByUri = emptyMap(),
            genreArtists = mapOf("rock" to listOf("library://artist/1")),
            strictGenreArtists = emptyMap(),
        )
        val outcome = async { launcher.outcomes.first() }
        delay(SETTLE_MS)

        launcher.startGenreRadio("queue", "polka")

        assertThat(withTimeout(WAIT_MS) { outcome.await() })
            .isEqualTo(MixLauncher.Outcome(Mix.GenreRadio("polka"), MixResult.NoMatchingArtists))
        coVerify(exactly = 0) { orchestrator.playGenreRadio(any(), any(), any()) }
    }

    @Test
    fun `without a loaded feed the genre is left to the orchestrator`() = runBlocking {
        coEvery { orchestrator.playGenreRadio("queue", "polka", null) } returns MixResult.Started
        launcher.startGenreRadio("queue", "polka")
        coVerify(timeout = WAIT_MS) { orchestrator.playGenreRadio("queue", "polka", null) }
    }

    @Test
    fun `a second press on the same genre right after the first is ignored`() = runBlocking {
        coEvery { orchestrator.playGenreRadio(any(), any(), any()) } returns MixResult.Started
        launcher.startGenreRadio("queue", "rock")
        coVerify(timeout = WAIT_MS) { orchestrator.playGenreRadio("queue", "rock", null) }
        withTimeout(WAIT_MS) { launcher.activity.first { it == null } }

        launcher.startGenreRadio("queue", "rock")
        delay(SETTLE_MS)

        coVerify(exactly = 1) { orchestrator.playGenreRadio(any(), any(), any()) }
    }

    @Test
    fun `a press ignored while another mix builds does not block the same genre later`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        coEvery { orchestrator.playSmartMix(any(), any()) } coAnswers {
            release.await()
            MixResult.Played(25, null)
        }
        coEvery { orchestrator.playGenreRadio(any(), any(), any()) } returns MixResult.Started
        launcher.startSmartMix("queue")
        assertThat(launcher.startGenreRadio("queue", "rock")).isFalse()
        release.complete(Unit)
        withTimeout(WAIT_MS) { launcher.activity.first { it == null } }

        assertThat(launcher.startGenreRadio("queue", "rock")).isTrue()
        coVerify(timeout = WAIT_MS) { orchestrator.playGenreRadio("queue", "rock", null) }
    }

    @Test
    fun `a build cancelled by an account switch neither publishes nor clears a newer build`() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { orchestrator.playSmartMix(any(), any()) } coAnswers {
            // The first build already has its answer when the switch lands.
            if (++calls == 1) withContext(NonCancellable) { first.await() } else second.await()
            MixResult.Played(25, null)
        }
        val outcomes = mutableListOf<MixLauncher.Outcome>()
        val collector = launch(Dispatchers.Default) { launcher.outcomes.collect { outcomes += it } }
        delay(SETTLE_MS)
        launcher.startSmartMix("old")
        withTimeout(WAIT_MS) { launcher.activity.first { it == Mix.SmartMix } }

        sessionEventBus.emitReset()
        withTimeout(WAIT_MS) { launcher.activity.first { it == null } }
        assertThat(launcher.startSmartMix("new")).isTrue()
        first.complete(Unit)
        delay(SETTLE_MS)

        assertThat(launcher.activity.value).isEqualTo(Mix.SmartMix)
        assertThat(outcomes).isEmpty()
        second.complete(Unit)
        withTimeout(WAIT_MS) { launcher.activity.first { it == null } }
        delay(SETTLE_MS)
        assertThat(outcomes).hasSize(1)
        collector.cancel()
    }

    @Test
    fun `a start without a player says so`() = runBlocking {
        val outcome = async { launcher.outcomes.first() }
        delay(SETTLE_MS)

        launcher.startSmartMix("")

        assertThat(withTimeout(WAIT_MS) { outcome.await() })
            .isEqualTo(MixLauncher.Outcome(Mix.SmartMix, MixResult.NoPlayer))
        assertThat(withTimeout(WAIT_MS) { outcome.await() }.userMessage()).isEqualTo("Select a player first")
    }

    private companion object {
        const val WAIT_MS = 5_000L
        const val SETTLE_MS = 150L
    }
}
