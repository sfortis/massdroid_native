package net.asksakis.massdroidv2.domain.playlist

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.domain.model.SeedAddOutcome
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSeed
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSeedKind
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSummary
import net.asksakis.massdroidv2.domain.repository.SmartPlaylistRepository
import org.junit.Test

/**
 * The smart playlist picker writes once per tap, and an answer that arrives late never
 * closes a picker that was reopened for another item meanwhile.
 */
class SmartPlaylistSeedControllerTest {

    private class GatedRepository : SmartPlaylistRepository {
        val gate = CompletableDeferred<SeedAddOutcome>()
        var adds = 0
        override val available: Flow<Boolean> = flowOf(true)
        override suspend fun seedPlaylists(): List<SmartPlaylistSummary> = emptyList()
        override suspend fun addSeed(playlistId: String, seed: SmartPlaylistSeed): SeedAddOutcome {
            adds++
            return gate.await()
        }
        override suspend fun createWithSeed(name: String, seed: SmartPlaylistSeed) = Unit
    }

    private val playlist = SmartPlaylistSummary("uuid-1", "Night drive", seedCount = 3)
    private val track = SmartPlaylistSeed(SmartPlaylistSeedKind.TRACK, "library://track/7", "Miracle")
    private val artist = SmartPlaylistSeed(SmartPlaylistSeedKind.ARTIST, "library://artist/59", "WhoMadeWho")

    /** The guard must hold on a dispatcher that does not run the launch at once. */
    @Test
    fun `a second tap before the first write starts is refused`() = runTest {
        val repository = GatedRepository()
        val controller = SmartPlaylistSeedController(repository, TestScope(testScheduler))
        controller.open(track)

        controller.add(playlist)
        controller.add(playlist)
        repository.gate.complete(SeedAddOutcome.ADDED)
        advanceUntilIdle()

        assertThat(repository.adds).isEqualTo(1)
    }

    @Test
    fun `a late answer does not close a picker reopened for another item`() = runTest {
        val repository = GatedRepository()
        val controller = SmartPlaylistSeedController(repository, TestScope(testScheduler))
        controller.open(track)
        controller.add(playlist)
        advanceUntilIdle()

        controller.close()
        controller.open(artist)
        repository.gate.complete(SeedAddOutcome.ADDED)
        advanceUntilIdle()

        assertThat(controller.seed.value).isEqualTo(artist)
    }

    @Test
    fun `the picker closes once its own write is answered`() = runTest {
        val repository = GatedRepository()
        val controller = SmartPlaylistSeedController(repository, TestScope(testScheduler))
        controller.open(track)
        controller.add(playlist)
        repository.gate.complete(SeedAddOutcome.ADDED)
        advanceUntilIdle()

        assertThat(controller.seed.value).isNull()
    }
}
