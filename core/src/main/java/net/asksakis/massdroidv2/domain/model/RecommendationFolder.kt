package net.asksakis.massdroidv2.domain.model

import kotlinx.serialization.Serializable

/**
 * One recommendation row of Music Assistant. [uri] names the row in the user's home row
 * settings, and [enabledByDefault] says whether the row shows before the user has chosen.
 */
@Serializable
data class RecommendationFolder(
    val itemId: String,
    val name: String,
    val provider: String,
    val items: RecommendationItems,
    val uri: String? = null,
    val enabledByDefault: Boolean = true
)

@Serializable
data class RecommendationItems(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList()
) {
    val totalCount: Int get() = artists.size + albums.size + tracks.size + playlists.size
}
