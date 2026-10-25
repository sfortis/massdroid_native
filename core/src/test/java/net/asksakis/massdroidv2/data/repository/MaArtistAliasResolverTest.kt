package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.data.provider.MusicProvider
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.repository.ArtistAliasLookup
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import org.junit.Test

/**
 * Pins which alias lookups count as answered and which may succeed later.
 *
 * The blocked-artist expansion records that it is done only when every lookup was
 * answered. A uri from another server must therefore not be sent at all: that server's
 * provider instances are unknown here, and MA refuses them with the same "provider
 * unavailable" it gives a provider that is down for now.
 */
class MaArtistAliasResolverTest {

    private val music = mockk<MusicRepository>()
    private val deezer = MusicProvider(instanceId = "deezer--GWnPbDSt", domain = "deezer", name = "Deezer")
    private var providers = listOf(deezer)
    private val resolver = MaArtistAliasResolver(music) { providers }

    private val providerUri = "deezer--GWnPbDSt://artist/6807853"
    private val libraryUri = "library://artist/202"

    private fun failWith(code: Int) {
        coEvery { music.getArtist(any(), any(), any()) } throws MaApiException("refused", code)
    }

    @Test
    fun `the answer lists every other uri the server knows`() = runTest {
        coEvery { music.getArtist("6807853", "deezer--GWnPbDSt", true) } returns Artist(
            itemId = "202",
            provider = "library",
            name = "The Midnight",
            uri = libraryUri,
            providerUris = listOf(providerUri, "tidal--Q9://artist/55"),
        )

        val lookup = resolver.lookup(providerUri)

        assertThat(lookup).isEqualTo(
            ArtistAliasLookup.Resolved(listOf("tidal--Q9://artist/55", libraryUri))
        )
    }

    @Test
    fun `a uri of a provider this server does not have is not sent`() = runTest {
        val lookup = resolver.lookup("deezer--OtherServer://artist/1")

        assertThat(lookup).isEqualTo(ArtistAliasLookup.Unresolvable)
        coVerify(exactly = 0) { music.getArtist(any(), any(), any()) }
    }

    @Test
    fun `library uris and uris that name the provider domain are sent`() = runTest {
        coEvery { music.getArtist(any(), any(), any()) } returns null

        resolver.lookup(libraryUri)
        resolver.lookup("deezer://artist/9")

        coVerify { music.getArtist("202", "library", true) }
        coVerify { music.getArtist("9", "deezer", true) }
    }

    @Test
    fun `every uri is sent while the provider list is not read yet`() = runTest {
        providers = emptyList()
        coEvery { music.getArtist(any(), any(), any()) } returns null

        resolver.lookup("deezer--OtherServer://artist/1")

        coVerify { music.getArtist("1", "deezer--OtherServer", true) }
    }

    @Test
    fun `no answer and a provider that is down may succeed later`() = runTest {
        for (code in listOf(
            MaApiException.TIMEOUT_CODE,
            MaApiException.CONNECTION_LOST_CODE,
            MaApiException.PROVIDER_UNAVAILABLE_CODE,
        )) {
            failWith(code)
            assertThat(resolver.lookup(providerUri)).isEqualTo(ArtistAliasLookup.Failed)
        }
    }

    @Test
    fun `an item the server does not have is an answer`() = runTest {
        // MA's MediaNotFoundError.
        failWith(2)
        assertThat(resolver.lookup(libraryUri)).isEqualTo(ArtistAliasLookup.Unresolvable)

        coEvery { music.getArtist(any(), any(), any()) } returns null
        assertThat(resolver.lookup(libraryUri)).isEqualTo(ArtistAliasLookup.Unresolvable)
    }
}
