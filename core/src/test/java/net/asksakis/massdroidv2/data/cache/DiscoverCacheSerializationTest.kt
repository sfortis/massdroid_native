package net.asksakis.massdroidv2.data.cache

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.GenreItem
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSection
import org.junit.Test

/**
 * Locks the DiscoverCache serialization contract. The cache now persists the
 * rendered DiscoverSection list (closed-polymorphism sealed hierarchy), so a
 * round-trip through the app's Json must preserve every section subtype. A break
 * here only degrades to "empty cache -> rebuilt on connect" at runtime (load()
 * swallows the exception), which is why a compile-time guard is worthwhile.
 */
class DiscoverCacheSerializationTest {

    // Mirrors the AppModule-provided Json configuration.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private fun artist(id: String) = Artist(itemId = id, provider = "library", name = "Artist $id", uri = "library://artist/$id")
    private fun album(id: String, image: String?) =
        Album(itemId = id, provider = "library", name = "Album $id", uri = "library://album/$id", imageUrl = image)
    private fun track(id: String) = Track(itemId = id, provider = "library", name = "Track $id", uri = "library://track/$id")

    @Test
    fun `round-trips every DiscoverSection subtype`() {
        val data = DiscoverCache.CacheData(
            sections = listOf(
                DiscoverSection.ArtistSection("Artists You Might Like", listOf(artist("1"), artist("2"))),
                DiscoverSection.AlbumSection("Recent Favorite Albums", listOf(album("10", "http://img/10"))),
                DiscoverSection.TrackSection("Recent Favorite Tracks", listOf(track("20"))),
                DiscoverSection.GenreRadioSection("Genre Radio", listOf(GenreItem("rock", 42, null)))
            ),
            topArtists = listOf(artist("1")),
            lastRefreshed = 123L
        )

        val decoded = json.decodeFromString(
            DiscoverCache.CacheData.serializer(),
            json.encodeToString(DiscoverCache.CacheData.serializer(), data)
        )

        assertThat(decoded).isEqualTo(data)
        assertThat(decoded.sections).hasSize(4)
        assertThat(decoded.sections[0]).isInstanceOf(DiscoverSection.ArtistSection::class.java)
        assertThat(decoded.sections[3]).isInstanceOf(DiscoverSection.GenreRadioSection::class.java)
    }

    @Test
    fun `surfaces albums with missing images for the stale-refresh gate`() {
        val data = DiscoverCache.CacheData(
            sections = listOf(
                DiscoverSection.AlbumSection("Recent Favorite Albums", listOf(album("1", null), album("2", "http://img/2")))
            )
        )

        val decoded = json.decodeFromString(
            DiscoverCache.CacheData.serializer(),
            json.encodeToString(DiscoverCache.CacheData.serializer(), data)
        )

        val hasMissingImages = decoded.sections.any { section ->
            section is DiscoverSection.AlbumSection && section.albums.any { it.imageUrl == null }
        }
        assertThat(hasMissingImages).isTrue()
    }
}
