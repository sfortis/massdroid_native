package net.asksakis.massdroidv2.data.repository

import android.util.Log
import net.asksakis.massdroidv2.domain.repository.ArtistAliasResolver
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks Music Assistant which uris belong to the same artist.
 *
 * Works off whatever the server reports, so it is provider-agnostic: a uri is
 * parsed as `<provider>://artist/<id>` whatever the provider is called, and the
 * answer comes from that item's `provider_mappings` plus the library uri the
 * server resolved it to. Nothing here assumes a particular music provider, a
 * particular server version, or that the artist is in the library at all.
 *
 * Returns empty rather than throwing when the server cannot be reached, so a
 * block placed offline still stores the one uri the caller had.
 */
@Singleton
class MaArtistAliasResolver @Inject constructor(
    private val musicRepository: MusicRepository,
) : ArtistAliasResolver {

    override suspend fun aliasesFor(artistUri: String): List<String> {
        val ref = parse(artistUri) ?: return emptyList()
        return try {
            val artist = musicRepository.getArtist(ref.itemId, ref.provider, lazy = true)
                ?: return emptyList()
            // The server's own uri counts: asking about a provider item usually
            // resolves to the library one, which is the uri a block placed from
            // a library screen would have used.
            (artist.providerUris + artist.uri)
                .filter { it.isNotBlank() && it != artistUri }
                .distinct()
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve aliases for $artistUri: ${e.message}")
            emptyList()
        }
    }

    private data class Ref(val provider: String, val itemId: String)

    private fun parse(uri: String): Ref? {
        val provider = uri.substringBefore("://", "").trim()
        if (provider.isEmpty()) return null
        val itemId = uri.substringAfter("://", "")
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
            .substringAfterLast('/')
            .trim()
        if (itemId.isEmpty()) return null
        return Ref(provider, itemId)
    }

    private companion object {
        const val TAG = "ArtistAliases"
    }
}
