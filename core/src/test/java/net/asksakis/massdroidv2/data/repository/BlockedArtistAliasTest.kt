package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.data.database.BlockedArtistEntity
import net.asksakis.massdroidv2.data.database.BlockedArtistRow
import net.asksakis.massdroidv2.data.database.PlayHistoryDao
import net.asksakis.massdroidv2.data.database.TransactionRunner
import net.asksakis.massdroidv2.domain.repository.ArtistAliasResolver
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import org.junit.Test

/**
 * Pins that blocking an artist blocks the artist, not one of their uris.
 *
 * The failure this guards against was reported from a device and is completely
 * silent: the block is stored, the UI shows the artist as blocked, and the
 * artist keeps playing. One artist reaches the app under several uris - the
 * library row plus one per provider carrying them - and a block placed from a
 * library screen never matched the same artist arriving from a queue event.
 */
class BlockedArtistAliasTest {

    private val transactions = object : TransactionRunner {
        override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
    }
    private val dao = mockk<PlayHistoryDao>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)

    private val libraryUri = "library://artist/202"
    private val providerUri = "acme--Xy1://artist/6807853"

    private fun repo(aliases: ArtistAliasResolver) =
        SmartListeningRepositoryImpl(dao, settings, transactions, aliases)

    @Test
    fun `blocking one uri blocks every uri the server knows`() = runTest {
        val repo = repo { uri -> if (uri == libraryUri) listOf(providerUri) else emptyList() }

        repo.setArtistBlocked(libraryUri, "The Midnight", blocked = true)

        val rows = slot<List<BlockedArtistEntity>>()
        coVerify { dao.upsertBlockedArtists(capture(rows)) }
        assertThat(rows.captured.map { it.artistUri })
            .containsExactly(libraryUri, providerUri)
        assertThat(rows.captured.map { it.artistName }.distinct()).containsExactly("The Midnight")
    }

    @Test
    fun `an artist the server cannot expand is still blocked under its own uri`() = runTest {
        // Offline, or a provider that reports no mappings. Storing the one uri
        // the caller had is no worse than the behaviour this replaced.
        val repo = repo { emptyList() }

        repo.setArtistBlocked(providerUri, "Some Artist", blocked = true)

        val rows = slot<List<BlockedArtistEntity>>()
        coVerify { dao.upsertBlockedArtists(capture(rows)) }
        assertThat(rows.captured.map { it.artistUri }).containsExactly(providerUri)
    }

    @Test
    fun `unblocking clears every uri, and the name as a safety net`() = runTest {
        val repo = repo { uri -> if (uri == libraryUri) listOf(providerUri) else emptyList() }

        repo.setArtistBlocked(libraryUri, "The Midnight", blocked = false)

        coVerify { dao.deleteBlockedArtist(libraryUri) }
        coVerify { dao.deleteBlockedArtist(providerUri) }
        // The aliases come from the server, so an unblock made offline would
        // otherwise leave the artist silenced with no way to release them.
        coVerify { dao.deleteBlockedArtistsByName("The Midnight") }
    }

    // --- one-time catch-up for blocks stored before any of this existed ---

    @Test
    fun `the backfill expands existing blocks and remembers the providers it saw`() = runTest {
        every { settings.blockedArtistAliasProviders } returns flowOf("")
        coEvery { dao.getBlockedArtists() } returns listOf(
            BlockedArtistRow(libraryUri, "The Midnight", 1_700_000_000_000)
        )
        val repo = repo { uri -> if (uri == libraryUri) listOf(providerUri) else emptyList() }

        repo.backfillBlockedArtistAliases("acme,other")

        val rows = slot<List<BlockedArtistEntity>>()
        coVerify { dao.upsertBlockedArtists(capture(rows)) }
        assertThat(rows.captured.map { it.artistUri }).containsExactly(providerUri)
        // The original blocked_at is kept, so the list stays in the order the
        // listener built it.
        assertThat(rows.captured.single().blockedAt).isEqualTo(1_700_000_000_000)
        coVerify { settings.setBlockedArtistAliasProviders("acme,other") }
    }

    @Test
    fun `nothing is redone while the providers are the ones already expanded against`() = runTest {
        every { settings.blockedArtistAliasProviders } returns flowOf("acme,other")

        val expanded = repo { error("must not resolve") }
            .backfillBlockedArtistAliases("acme,other")

        assertThat(expanded).isFalse()
        coVerify(exactly = 0) { dao.getBlockedArtists() }
    }

    @Test
    fun `adding a provider expands the blocks again`() = runTest {
        // The point of remembering the set rather than a done flag: a new
        // provider gives blocked artists a uri nobody has blocked, and they
        // start playing again from it.
        every { settings.blockedArtistAliasProviders } returns flowOf("acme")
        coEvery { dao.getBlockedArtists() } returns listOf(
            BlockedArtistRow(libraryUri, "The Midnight", 1_700_000_000_000)
        )

        val expanded = repo { listOf(providerUri) }.backfillBlockedArtistAliases("acme,newone")

        assertThat(expanded).isTrue()
        coVerify { settings.setBlockedArtistAliasProviders("acme,newone") }
    }

    @Test
    fun `an unread provider list is not recorded as an expansion`() = runTest {
        // Otherwise the app would remember "expanded against nothing" and never
        // run again on this install.
        every { settings.blockedArtistAliasProviders } returns flowOf("")

        assertThat(repo { error("must not resolve") }.backfillBlockedArtistAliases("")).isFalse()
        coVerify(exactly = 0) { settings.setBlockedArtistAliasProviders(any()) }
    }

    @Test
    fun `an install with no blocks still records the providers it checked`() = runTest {
        every { settings.blockedArtistAliasProviders } returns flowOf("")
        coEvery { dao.getBlockedArtists() } returns emptyList()

        repo { error("must not resolve") }.backfillBlockedArtistAliases("acme")

        coVerify { settings.setBlockedArtistAliasProviders("acme") }
    }

    @Test
    fun `the list a person reads shows one row per artist`() = runTest {
        // The table holds one row per uri, so an artist blocked under two
        // providers appeared twice in the blocked list.
        coEvery { dao.getBlockedArtistsForDisplay() } returns listOf(
            BlockedArtistRow(libraryUri, "The Midnight", 2_000)
        )
        coEvery { dao.getUnnamedBlockedArtists() } returns emptyList()

        val shown = repo { emptyList() }.getBlockedArtists()

        assertThat(shown.map { it.artistName }).containsExactly("The Midnight")
    }
}
