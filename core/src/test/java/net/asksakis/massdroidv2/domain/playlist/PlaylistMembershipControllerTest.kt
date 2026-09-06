package net.asksakis.massdroidv2.domain.playlist

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.model.acceptsManualTracks
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import org.junit.Test

/**
 * Pins the two faults the add-to-playlist dialog shipped with.
 *
 * The dialog offered playlists the server will not accept a track into, and it
 * blocked on downloading the track list of every playlist in the library before
 * it would draw anything. Both were reported from a real account: 104 playlists,
 * 64 seconds of spinner, and two smart playlists in the list that failed on tap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistMembershipControllerTest {

    private val musicRepository = mockk<MusicRepository>(relaxed = true)

    private fun playlist(
        id: String,
        editable: Boolean = true,
        dynamic: Boolean = false
    ) = Playlist(
        itemId = id,
        provider = "library",
        name = "playlist $id",
        uri = "library://playlist/$id",
        isEditable = editable,
        isDynamic = dynamic
    )

    private fun track(uri: String) = Track(
        itemId = uri.substringAfterLast('/'),
        provider = "library",
        name = "track",
        uri = uri
    )

    /**
     * A smart playlist reports itself as editable because its RULES are editable,
     * which is not the same as accepting a track. Offering one produces a server
     * error on tap, so it must never reach the list.
     */
    @Test
    fun `smart playlists are not offered even though the server calls them editable`() = runTest {
        val ordinary = playlist("1")
        val smart = playlist("2", editable = true, dynamic = true)
        val serverOwned = playlist("3", editable = false)
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } returns
            listOf(ordinary, smart, serverOwned)

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9")
        testScheduler.advanceUntilIdle()

        assertThat(controller.playlists.value.map { it.itemId }).containsExactly("1")
    }

    /** The predicate is what every other surface filters by, so pin it directly. */
    @Test
    fun `only a hand editable playlist accepts a track`() {
        assertThat(playlist("1").acceptsManualTracks).isTrue()
        assertThat(playlist("2", dynamic = true).acceptsManualTracks).isFalse()
        assertThat(playlist("3", editable = false).acceptsManualTracks).isFalse()
        assertThat(playlist("4", editable = false, dynamic = true).acceptsManualTracks).isFalse()
    }

    /**
     * The fault that cost 64 seconds: opening the dialog downloaded the track list
     * of every playlist in the library. Opening must ask the server for the
     * playlists and for nothing else.
     */
    @Test
    fun `opening the dialog does not download any track list`() = runTest {
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } returns
            (1..50).map { playlist(it.toString()) }

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9")
        testScheduler.advanceUntilIdle()

        assertThat(controller.playlists.value).hasSize(50)
        coVerify(exactly = 0) { musicRepository.getPlaylistTracks(any(), any(), any()) }
    }

    /** Only the rows the listener can see are resolved, and each one only once. */
    @Test
    fun `membership is resolved for the visible rows only and never twice`() = runTest {
        val playlists = (1..50).map { playlist(it.toString()) }
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } returns playlists
        coEvery { musicRepository.getPlaylistTracks("1", any(), any()) } returns
            listOf(track("library://track/9"))
        coEvery { musicRepository.getPlaylistTracks("2", any(), any()) } returns
            listOf(track("library://track/other"))

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9")
        testScheduler.advanceUntilIdle()

        val visible = listOf("library://playlist/1", "library://playlist/2")
        controller.onPlaylistsVisible(visible)
        testScheduler.advanceUntilIdle()
        controller.onPlaylistsVisible(visible)
        testScheduler.advanceUntilIdle()

        assertThat(controller.containsTrack.value).containsExactly("library://playlist/1")
        coVerify(exactly = 1) { musicRepository.getPlaylistTracks("1", any(), any()) }
        coVerify(exactly = 1) { musicRepository.getPlaylistTracks("2", any(), any()) }
        coVerify(exactly = 0) { musicRepository.getPlaylistTracks("3", any(), any()) }
    }

    /**
     * The list stays on screen while the track under it changes, in the now
     * playing screen most obviously. A tick resolved for the previous track would
     * claim the new one is already saved.
     */
    @Test
    fun `changing the track clears the ticks`() = runTest {
        val playlists = listOf(playlist("1"))
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } returns playlists
        coEvery { musicRepository.getPlaylistTracks("1", any(), any()) } returns
            listOf(track("library://track/9"))

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9")
        testScheduler.advanceUntilIdle()
        controller.onPlaylistsVisible(listOf("library://playlist/1"))
        testScheduler.advanceUntilIdle()
        assertThat(controller.containsTrack.value).isNotEmpty()

        controller.open("library://track/10")
        testScheduler.advanceUntilIdle()

        assertThat(controller.containsTrack.value).isEmpty()
    }

    /** After the track changes, the same playlist is checked again for the new track. */
    @Test
    fun `a new track re-checks a playlist that was already checked`() = runTest {
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } returns
            listOf(playlist("1"))
        coEvery { musicRepository.getPlaylistTracks("1", any(), any()) } returns
            listOf(track("library://track/9"))

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9")
        testScheduler.advanceUntilIdle()
        controller.onPlaylistsVisible(listOf("library://playlist/1"))
        testScheduler.advanceUntilIdle()

        controller.open("library://track/10")
        testScheduler.advanceUntilIdle()
        controller.onPlaylistsVisible(listOf("library://playlist/1"))
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 2) { musicRepository.getPlaylistTracks("1", any(), any()) }
        assertThat(controller.containsTrack.value).isEmpty()
    }

    /** A check that fails is released, so scrolling back to the row tries again. */
    @Test
    fun `a failed check is retried on the next pass`() = runTest {
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } returns
            listOf(playlist("1"))
        coEvery { musicRepository.getPlaylistTracks("1", any(), any()) } throws
            IllegalStateException("socket closed") andThen listOf(track("library://track/9"))

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9")
        testScheduler.advanceUntilIdle()

        controller.onPlaylistsVisible(listOf("library://playlist/1"))
        testScheduler.advanceUntilIdle()
        assertThat(controller.containsTrack.value).isEmpty()

        controller.onPlaylistsVisible(listOf("library://playlist/1"))
        testScheduler.advanceUntilIdle()

        assertThat(controller.containsTrack.value).containsExactly("library://playlist/1")
    }

    /** Adding ticks the row without another round trip to confirm it. */
    @Test
    fun `adding a track ticks the row`() = runTest {
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } returns
            listOf(playlist("1"))

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9")
        testScheduler.advanceUntilIdle()
        controller.add(playlist("1"))
        testScheduler.advanceUntilIdle()

        assertThat(controller.containsTrack.value).containsExactly("library://playlist/1")
        coVerify(exactly = 1) {
            musicRepository.addTrackToPlaylist(any(), "library://track/9")
        }
    }

    /** Removing clears the tick and takes the track out at the position it sits in. */
    @Test
    fun `removing a track clears the tick`() = runTest {
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } returns
            listOf(playlist("1"))
        coEvery { musicRepository.getPlaylistTracks("1", any(), any()) } returns listOf(
            track("library://track/1"),
            track("library://track/9")
        )

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9")
        testScheduler.advanceUntilIdle()
        controller.onPlaylistsVisible(listOf("library://playlist/1"))
        testScheduler.advanceUntilIdle()
        assertThat(controller.containsTrack.value).isNotEmpty()

        controller.remove(playlist("1"))
        testScheduler.advanceUntilIdle()

        assertThat(controller.containsTrack.value).isEmpty()
        coVerify(exactly = 1) { musicRepository.removeTrackFromPlaylist(any(), 1) }
    }

    /**
     * A track change while the list is still loading used to empty the dialog: the
     * load in flight was discarded because the generation had moved, and the reload
     * it asked for was refused because a load was running. Which track is selected
     * says nothing about which playlists exist.
     */
    @Test
    fun `changing the track while the list loads still leaves the list populated`() = runTest {
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(100)
            listOf(playlist("1"), playlist("2"))
        }

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9", reload = true)
        testScheduler.advanceTimeBy(10)
        controller.open("library://track/10", reload = true)
        testScheduler.advanceUntilIdle()

        assertThat(controller.playlists.value.map { it.itemId }).containsExactly("1", "2")
        assertThat(controller.isLoading.value).isFalse()
    }

    /** An account switch mid-load must not leave the previous account's playlists. */
    @Test
    fun `resetting while the list loads refetches instead of keeping the old list`() = runTest {
        var call = 0
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } coAnswers {
            call++
            kotlinx.coroutines.delay(100)
            if (call == 1) listOf(playlist("old")) else listOf(playlist("new"))
        }

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9", reload = true)
        testScheduler.advanceTimeBy(10)
        controller.reset()
        controller.open("library://track/9", reload = true)
        testScheduler.advanceUntilIdle()

        assertThat(controller.playlists.value.map { it.itemId }).containsExactly("new")
    }

    /**
     * A write that lands after the listener moved to another track is answering a
     * question nobody is asking, and it used to tick a row for the new one.
     */
    @Test
    fun `an add that lands after the track changed does not tick the new track`() = runTest {
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } returns
            listOf(playlist("1"))
        coEvery { musicRepository.addTrackToPlaylist(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(100)
        }

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9")
        testScheduler.advanceUntilIdle()

        var reportedDone = false
        controller.add(playlist("1")) { reportedDone = true }
        testScheduler.advanceTimeBy(10)
        controller.open("library://track/10")
        testScheduler.advanceUntilIdle()

        assertThat(controller.containsTrack.value).isEmpty()
        assertThat(reportedDone).isFalse()
    }

    /**
     * Creating a playlist answers two questions with different lifetimes. The
     * playlist exists whatever the dialog moved on to, so the list refresh must
     * run; the dialog's completion closes it, so it must not fire for a dialog
     * that is now pointed at another track.
     */
    @Test
    fun `a creation that lands after the track changed refreshes the list but does not complete`() = runTest {
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { musicRepository.createPlaylist(any()) } returns playlist("new")
        coEvery { musicRepository.addTrackToPlaylist(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(100)
        }

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9")
        testScheduler.advanceUntilIdle()

        var created: Playlist? = null
        var reportedDone = false
        controller.createAndAdd(
            "New one",
            onCreated = { created = it },
            onDone = { reportedDone = true }
        )
        testScheduler.advanceTimeBy(10)
        controller.open("library://track/10")
        testScheduler.advanceUntilIdle()

        assertThat(created?.itemId).isEqualTo("new")
        assertThat(reportedDone).isFalse()
        assertThat(controller.containsTrack.value).isEmpty()
    }

    /** With the track unchanged, both callbacks run. */
    @Test
    fun `a creation for the current track completes the dialog`() = runTest {
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { musicRepository.createPlaylist(any()) } returns playlist("new")

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9")
        testScheduler.advanceUntilIdle()

        var created: Playlist? = null
        var reportedDone = false
        controller.createAndAdd(
            "New one",
            onCreated = { created = it },
            onDone = { reportedDone = true }
        )
        testScheduler.advanceUntilIdle()

        assertThat(created?.itemId).isEqualTo("new")
        assertThat(reportedDone).isTrue()
        assertThat(controller.containsTrack.value).containsExactly("library://playlist/new")
    }

    /** The spinner covers the playlist fetch only, never a tick check. */
    @Test
    fun `the loading flag is down once the playlists arrive`() = runTest {
        coEvery { musicRepository.getPlaylists(any(), any(), any(), any(), any(), any()) } returns
            listOf(playlist("1"))

        val controller = PlaylistMembershipController(musicRepository, TestScope(testScheduler))
        controller.open("library://track/9")
        testScheduler.advanceUntilIdle()

        assertThat(controller.isLoading.value).isFalse()
        assertThat(controller.playlists.value).hasSize(1)
    }
}
