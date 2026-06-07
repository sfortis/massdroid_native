package net.asksakis.massdroidv2.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class RecommendationFolder(
    val itemId: String,
    val name: String,
    val provider: String,
    val items: RecommendationItems
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
