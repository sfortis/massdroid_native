package net.asksakis.massdroidv2.domain.recommendation

import androidx.annotation.VisibleForTesting
import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.GenreItem
import net.asksakis.massdroidv2.domain.model.Playlist
import net.asksakis.massdroidv2.domain.model.RecommendationFolder
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.GenreScore
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

// @Serializable so the rendered Discover screen can be persisted by DiscoverCache
// (closed-polymorphism sealed hierarchy; kotlinx tags each subclass automatically).
@Serializable
sealed class DiscoverSection {
    @Serializable
    data class ArtistSection(val title: String, val artists: List<Artist>) : DiscoverSection()
    @Serializable
    data class AlbumSection(val title: String, val albums: List<Album>) : DiscoverSection()
    @Serializable
    data class PlaylistSection(val title: String, val playlists: List<Playlist>) : DiscoverSection()
    @Serializable
    data class TrackSection(val title: String, val tracks: List<Track>) : DiscoverSection()
    @Serializable
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

        // 1. Genre Radio (BLL-weighted, top 10)
        val sortedGenres = if (bllGenreScores.isNotEmpty()) {
            val scoreMap = bllGenreScores.associate { normalizeGenre(it.genre) to it.score }
            genreItems.sortedByDescending { scoreMap[normalizeGenre(it.name)] ?: 0.0 }
        } else {
            genreItems
        }.take(10)
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

    @VisibleForTesting
    internal fun folderToSection(
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
            // A mixed folder whose total reaches MIN_SECTION_ITEMS but whose
            // largest single type does NOT cannot form a valid section: the UI
            // only has single-type sections, so emitting one here would show a
            // sub-minimum card (e.g. 2 albums + 2 artists -> a 2-album section).
            // Skip it instead (this was the <MIN_SECTION_ITEMS invariant bug).
            else -> null
        }
    }
}
