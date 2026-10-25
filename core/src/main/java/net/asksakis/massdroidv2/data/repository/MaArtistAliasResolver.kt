package net.asksakis.massdroidv2.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import net.asksakis.massdroidv2.data.provider.MusicProvider
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.domain.repository.ArtistAliasLookup
import net.asksakis.massdroidv2.domain.repository.ArtistAliasResolver
import net.asksakis.massdroidv2.domain.repository.MusicRepository

/**
 * Asks Music Assistant which uris belong to the same artist.
 *
 * Works off whatever the server reports, so it is provider-agnostic: a uri is
 * parsed as `<provider>://artist/<id>` whatever the provider is called, and the
 * answer comes from that item's `provider_mappings` plus the library uri the
 * server resolved it to. Nothing here assumes a particular music provider, a
 * particular server version, or that the artist is in the library at all.
 *
 * Never throws: a lookup the server could not answer comes back as
 * [ArtistAliasLookup.Failed], so a block placed offline still stores the one uri
 * the caller had.
 *
 * A uri whose provider this server does not have is not sent at all. The local
 * database outlives a switch to another server, and its uris name the provider
 * instances of the server they came from (2026-10-24: 94 lookups against a test
 * server, every one refused as provider unavailable). [musicProviders] is the
 * server's current list; while it is still empty every uri is asked.
 */
class MaArtistAliasResolver(
    private val musicRepository: MusicRepository,
    private val musicProviders: () -> List<MusicProvider>,
) : ArtistAliasResolver {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun lookup(artistUri: String): ArtistAliasLookup {
        val ref = parse(artistUri) ?: return ArtistAliasLookup.Unresolvable
        if (!isServedHere(ref.provider)) return ArtistAliasLookup.Unresolvable
        return try {
            val artist = musicRepository.getArtist(ref.itemId, ref.provider, lazy = true)
                ?: return ArtistAliasLookup.Unresolvable
            // The server's own uri counts: asking about a provider item usually
            // resolves to the library one, which is the uri a block placed from
            // a library screen would have used.
            ArtistAliasLookup.Resolved(
                (artist.providerUris + artist.uri)
                    .filter { it.isNotBlank() && it != artistUri }
                    .distinct()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: MaApiException) {
            Log.w(TAG, "Could not resolve aliases for $artistUri: ${e.message}")
            // Any other refusal is the server's answer about this uri, and asking
            // again gets the same one.
            if (e.mayHaveTakenEffect || e.isProviderUnavailable) {
                ArtistAliasLookup.Failed
            } else {
                ArtistAliasLookup.Unresolvable
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve aliases for $artistUri: ${e.message}")
            ArtistAliasLookup.Failed
        }
    }

    /**
     * Library and builtin uris belong to every server. A provider uri carries the
     * instance id, and the domain is accepted as well for a provider whose uris use it.
     */
    private fun isServedHere(provider: String): Boolean {
        if (provider in SERVER_WIDE_PROVIDERS) return true
        val providers = musicProviders()
        return providers.isEmpty() ||
            providers.any { it.instanceId == provider || it.domain == provider }
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
        val SERVER_WIDE_PROVIDERS = setOf("library", "builtin")
    }
}
