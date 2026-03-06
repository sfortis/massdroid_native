package net.asksakis.massdroidv2.domain.recommendation

import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.GenreScore
import net.asksakis.massdroidv2.ui.screens.home.GenreItem
import javax.inject.Inject
import javax.inject.Singleton

sealed class DiscoverSection {
    data class ArtistSection(val title: String, val artists: List<Artist>) : DiscoverSection()
    data class AlbumSection(val title: String, val albums: List<Album>) : DiscoverSection()
    data class PlaylistSection(val title: String, val playlists: List<Playlist>) : DiscoverSection()
    data class TrackSection(val title: String, val tracks: List<Track>) : DiscoverSection()
    data class GenreRadioSection(val title: String, val genres: List<GenreItem>) : DiscoverSection()
}

private const val MIN_SECTION_ITEMS = 3
private val IGNORED_FOLDERS = setOf("in_progress", "favorite_radio", "favorite_playlists")

@Singleton
class DiscoverSectionBuilder @Inject constructor() {

    fun buildSections(
        serverFolders: List<RecommendationFolder>,
        suggestedArtists: List<Artist>,
        suggestedAlbums: List<Album>,
        genreItems: List<GenreItem>,
        bllGenreScores: List<GenreScore>
    ): List<DiscoverSection> {
        val sections = mutableListOf<DiscoverSection>()

        val filtered = serverFolders.filter { it.itemId !in IGNORED_FOLDERS }
        val libraryFolders = filtered.filter { it.provider == "library" }
            .associateBy { it.itemId }

        // 1. Genre Radio (BLL-weighted)
        val sortedGenres = if (bllGenreScores.isNotEmpty()) {
            val scoreMap = bllGenreScores.associate { it.genre to it.score }
            genreItems.sortedByDescending { scoreMap[it.name] ?: 0.0 }
        } else {
            genreItems
        }
        if (sortedGenres.size >= MIN_SECTION_ITEMS) {
            sections.add(DiscoverSection.GenreRadioSection("Genre Radio", sortedGenres))
        }

        // 2. Albums You Might Like (BLL+MMR)
        if (suggestedAlbums.size >= MIN_SECTION_ITEMS) {
            sections.add(DiscoverSection.AlbumSection("Albums You Might Like", suggestedAlbums))
        }

        // 3. Artists You Might Like (BLL+MMR)
        if (suggestedArtists.size >= MIN_SECTION_ITEMS) {
            sections.add(DiscoverSection.ArtistSection("Artists You Might Like", suggestedArtists))
        }

        // 4. Recent Favorite Albums (from server)
        libraryFolders["recent_favorite_albums"]?.let { folder ->
            folderToSection(folder, titleOverride = "Recent Favorite Albums")?.let { sections.add(it) }
        }

        // 5. Recent Favorite Tracks
        libraryFolders["recent_favorite_tracks"]?.let { folder ->
            if (folder.items.tracks.size >= MIN_SECTION_ITEMS) {
                sections.add(DiscoverSection.TrackSection("Recent Favorite Tracks", folder.items.tracks))
            }
        }

        // 6. Random fallbacks (if few sections so far)
        if (sections.size < MIN_SECTION_ITEMS) {
            libraryFolders["random_albums"]?.let { folder ->
                if (folder.items.albums.size >= MIN_SECTION_ITEMS) {
                    sections.add(DiscoverSection.AlbumSection("Explore Albums", folder.items.albums))
                }
            }
            libraryFolders["random_artists"]?.let { folder ->
                if (folder.items.artists.size >= MIN_SECTION_ITEMS) {
                    sections.add(DiscoverSection.ArtistSection("Explore Artists", folder.items.artists))
                }
            }
        }

        return sections
    }

    private fun folderToSection(
        folder: RecommendationFolder,
        titleOverride: String? = null
    ): DiscoverSection? {
        val title = titleOverride ?: folder.name
        val items = folder.items

        // Pick the dominant media type
        return when {
            items.playlists.size >= MIN_SECTION_ITEMS ->
                DiscoverSection.PlaylistSection(title, items.playlists)
            items.albums.size >= MIN_SECTION_ITEMS ->
                DiscoverSection.AlbumSection(title, items.albums)
            items.artists.size >= MIN_SECTION_ITEMS ->
                DiscoverSection.ArtistSection(title, items.artists)
            items.tracks.size >= MIN_SECTION_ITEMS ->
                DiscoverSection.TrackSection(title, items.tracks)
            // Mixed content: try combining if total is enough
            items.totalCount >= MIN_SECTION_ITEMS -> {
                // For mixed folders, prefer albums if any, otherwise tracks
                when {
                    items.albums.isNotEmpty() -> DiscoverSection.AlbumSection(title, items.albums)
                    items.playlists.isNotEmpty() -> DiscoverSection.PlaylistSection(title, items.playlists)
                    items.tracks.isNotEmpty() -> DiscoverSection.TrackSection(title, items.tracks)
                    items.artists.isNotEmpty() -> DiscoverSection.ArtistSection(title, items.artists)
                    else -> null
                }
            }
            else -> null
        }
    }
}
