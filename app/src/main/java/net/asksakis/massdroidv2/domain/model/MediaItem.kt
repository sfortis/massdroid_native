package net.asksakis.massdroidv2.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val itemId: String,
    val provider: String,
    val name: String,
    val uri: String,
    val duration: Double? = null,
    val artistNames: String = "",
    val albumName: String = "",
    val imageUrl: String? = null,
    val favorite: Boolean = false,
    val position: Int? = null,
    val artistItemId: String? = null,
    val artistProvider: String? = null,
    val albumItemId: String? = null,
    val albumProvider: String? = null,
    val artistUri: String? = null,
    val artistUris: List<String> = emptyList(),
    val albumUri: String? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val providerDomains: List<String> = emptyList(),
    val lyrics: String? = null,
    val lrcLyrics: String? = null
)

@Serializable
data class Album(
    val itemId: String,
    val provider: String,
    val name: String,
    val uri: String,
    val artistNames: String = "",
    val imageUrl: String? = null,
    val favorite: Boolean = false,
    val version: String? = null,
    val year: Int? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val label: String? = null,
    val artists: List<Artist> = emptyList(),
    val albumType: String? = null,
    val providerDomains: List<String> = emptyList()
)

@Serializable
data class Artist(
    val itemId: String,
    val provider: String,
    val name: String,
    val uri: String,
    val imageUrl: String? = null,
    val favorite: Boolean = false,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val providerDomains: List<String> = emptyList()
)

@Serializable
data class Playlist(
    val itemId: String,
    val provider: String,
    val name: String,
    val uri: String,
    val imageUrl: String? = null,
    val favorite: Boolean = false,
    val isEditable: Boolean = true,
    val providerDomains: List<String> = emptyList()
)

@Serializable
data class Radio(
    val itemId: String,
    val provider: String,
    val name: String,
    val uri: String,
    val imageUrl: String? = null,
    val favorite: Boolean = false,
    val inLibrary: Boolean = true,
    val providerDomains: List<String> = emptyList()
)

@Serializable
data class BrowseItem(
    val itemId: String,
    val provider: String,
    val name: String,
    val uri: String,
    val path: String? = null,
    val imageUrl: String? = null,
    val isFolder: Boolean = false,
    val mediaType: String = "",
    val isPlayable: Boolean = false
)

enum class MediaType(val apiValue: String) {
    TRACK("track"),
    ALBUM("album"),
    ARTIST("artist"),
    PLAYLIST("playlist"),
    RADIO("radio");

    companion object {
        fun fromApi(value: String): MediaType? = entries.find { it.apiValue == value }
    }
}
