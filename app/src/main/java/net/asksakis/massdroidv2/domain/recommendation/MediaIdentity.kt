package net.asksakis.massdroidv2.domain.recommendation

import net.asksakis.massdroidv2.domain.model.Album
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.ArtistScore
import net.asksakis.massdroidv2.domain.repository.GenreScore

fun Artist.canonicalKey(): String? = MediaIdentity.canonicalArtistKey(itemId = itemId, uri = uri)
fun Album.canonicalKey(): String? = MediaIdentity.canonicalAlbumKey(itemId = itemId, uri = uri)
fun Track.canonicalKey(): String? = MediaIdentity.canonicalTrackKey(itemId = itemId, uri = uri)

@JvmName("artistScoresToMap")
fun List<ArtistScore>.toScoreMap(): Map<String, Double> = associate { it.artistUri to it.score }

@JvmName("genreScoresToMap")
fun List<GenreScore>.toScoreMap(): Map<String, Double> = associate { it.genre.lowercase() to it.score }

object MediaIdentity {

    fun canonicalArtistKey(itemId: String? = null, uri: String? = null): String? =
        canonicalMediaKey(itemId = itemId, uri = uri)

    fun canonicalAlbumKey(itemId: String? = null, uri: String? = null): String? =
        canonicalMediaKey(itemId = itemId, uri = uri)

    fun canonicalTrackKey(itemId: String? = null, uri: String? = null): String? =
        canonicalMediaKey(itemId = itemId, uri = uri)

    fun artistKeyFromUri(uri: String?): String? = canonicalArtistKey(uri = uri)

    fun canonicalMediaKey(itemId: String? = null, uri: String? = null): String? {
        val raw = uri?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val normalizedUri = normalizeUri(raw)
            if (normalizedUri.isNotEmpty()) return normalizedUri
        }

        val direct = itemId?.trim().orEmpty()
        return direct.ifEmpty { null }
    }

    private fun normalizeUri(raw: String): String {
        return raw
            .substringBefore('#')
            .substringBefore('?')
            .trim()
    }
}
