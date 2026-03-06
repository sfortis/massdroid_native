package net.asksakis.massdroidv2.ui.screens.home

import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.DiscoverSection

private const val MIN_SECTION_ITEMS = 3

class DiscoverSectionCoordinator(
    private val recentFavoriteAlbumsTitle: String,
    private val recentFavoriteTracksTitle: String
) {

    fun updateRecentFavoriteTracks(
        current: List<DiscoverSection>,
        tracks: List<Track>
    ): List<DiscoverSection> {
        return reorder(
            upsertTrackSection(
                current = current,
                title = recentFavoriteTracksTitle,
                tracks = tracks
            )
        )
    }

    fun updateRecentFavoriteAlbums(
        current: List<DiscoverSection>,
        albums: List<Album>
    ): List<DiscoverSection> {
        return reorder(
            upsertAlbumSection(
                current = current,
                title = recentFavoriteAlbumsTitle,
                albums = albums
            )
        )
    }

    fun reorder(sections: List<DiscoverSection>): List<DiscoverSection> {
        return sections
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<DiscoverSection>>(
                    { sectionPriority(it.value) },
                    { it.index }
                )
            )
            .map { it.value }
    }

    private fun upsertTrackSection(
        current: List<DiscoverSection>,
        title: String,
        tracks: List<Track>
    ): List<DiscoverSection> {
        val currentIndex = current.indexOfFirst {
            it is DiscoverSection.TrackSection && it.title == title
        }
        val hasEnoughItems = tracks.size >= MIN_SECTION_ITEMS

        return when {
            currentIndex >= 0 && hasEnoughItems -> {
                current.toMutableList().apply {
                    this[currentIndex] = DiscoverSection.TrackSection(title = title, tracks = tracks)
                }
            }
            currentIndex >= 0 && !hasEnoughItems -> {
                current.toMutableList().apply { removeAt(currentIndex) }
            }
            currentIndex < 0 && hasEnoughItems -> {
                current.toMutableList().apply {
                    add(DiscoverSection.TrackSection(title = title, tracks = tracks))
                }
            }
            else -> current
        }
    }

    private fun upsertAlbumSection(
        current: List<DiscoverSection>,
        title: String,
        albums: List<Album>
    ): List<DiscoverSection> {
        val currentIndex = current.indexOfFirst {
            it is DiscoverSection.AlbumSection && it.title == title
        }
        val hasEnoughItems = albums.size >= MIN_SECTION_ITEMS

        return when {
            currentIndex >= 0 && hasEnoughItems -> {
                current.toMutableList().apply {
                    this[currentIndex] = DiscoverSection.AlbumSection(title = title, albums = albums)
                }
            }
            currentIndex >= 0 && !hasEnoughItems -> {
                current.toMutableList().apply { removeAt(currentIndex) }
            }
            currentIndex < 0 && hasEnoughItems -> {
                current.toMutableList().apply {
                    add(DiscoverSection.AlbumSection(title = title, albums = albums))
                }
            }
            else -> current
        }
    }

    private fun sectionPriority(section: DiscoverSection): Int {
        return when (section) {
            is DiscoverSection.GenreRadioSection -> 0
            is DiscoverSection.AlbumSection ->
                when (section.title) {
                    "Albums You Might Like" -> 1
                    recentFavoriteAlbumsTitle -> 3
                    else -> 100
                }
            is DiscoverSection.ArtistSection ->
                if (section.title == "Artists You Might Like") 2 else 100
            is DiscoverSection.TrackSection ->
                if (section.title == recentFavoriteTracksTitle) 4 else 100
            is DiscoverSection.PlaylistSection -> 100
        }
    }
}
