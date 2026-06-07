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
    val lrcLyrics: String? = null,
    val dateAdded: String? = null,
    val mediaType: MediaType = MediaType.TRACK,
    val chapters: List<Chapter> = emptyList(),
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList()
)

/**
 * A single audiobook (or podcast) chapter marker. The whole audiobook is one playable
 * item; chapters are seek targets within it (seek to [start] seconds). Mirrors MA's
 * `MediaItemChapter` (`metadata.chapters`).
 */
@Serializable
data class Chapter(
    val position: Int,
    val name: String,
    val start: Double,
    val end: Double? = null
)

private val CHAPTER_SLUG_REGEX = Regex("""^[\w-]+_(?:ch|chapter)_?\d+$""", RegexOption.IGNORE_CASE)

/**
 * A human chapter title, falling back to "Chapter N" when the provider name is a
 * slug (e.g. `wonderland_ch_01`) or blank, mirroring the MA web UI presentation.
 * Shared by the in-app queue and the Android Auto chapter timeline.
 */
fun Chapter.displayName(index: Int): String {
    val raw = name.trim()
    val isSlug = raw.isEmpty() ||
        CHAPTER_SLUG_REGEX.matches(raw) ||
        (!raw.contains(' ') && raw.contains('_'))
    return if (isSlug) "Chapter ${index + 1}" else raw
}

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

@Serializable
enum class MediaType(val apiValue: String) {
    TRACK("track"),
    ALBUM("album"),
    ARTIST("artist"),
    PLAYLIST("playlist"),
    RADIO("radio"),
    AUDIOBOOK("audiobook");

    companion object {
        fun fromApi(value: String): MediaType? = entries.find { it.apiValue == value }
    }
}
