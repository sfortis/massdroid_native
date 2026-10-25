package net.asksakis.massdroidv2.domain.repository

/**
 * Every uri Music Assistant knows one artist by.
 *
 * Blocking an artist has to survive the fact that the same person reaches the
 * app under different uris depending on where they came from. A block placed
 * from the library screen stores `library://artist/202`, while the queue
 * delivers the very same artist as `deezer--GWnPbDSt://artist/6807853`, so a
 * filter comparing uris finds nothing and the artist keeps playing - which is
 * exactly what a listener reported for The Midnight.
 *
 * Matching on the name would paper over it, but names are not unique: the same
 * one covers unrelated acts in unrelated genres. The server's own
 * `provider_mappings` is an authoritative statement of identity, so that is
 * what this resolves.
 */
fun interface ArtistAliasResolver {
    /** The other uris for [artistUri], or why there are none. */
    suspend fun lookup(artistUri: String): ArtistAliasLookup
}

/**
 * The answer to one alias lookup.
 *
 * [Unresolvable] and [Failed] both leave the caller with no aliases, but they mean different
 * things to a caller that remembers it has done the work: asking again after [Unresolvable]
 * gets the same answer, while [Failed] may succeed on the next connection.
 */
sealed interface ArtistAliasLookup {
    /** The server answered. The list is empty when it knows the artist under this uri only. */
    data class Resolved(val aliases: List<String>) : ArtistAliasLookup

    /**
     * Asking again will not help: the server does not know the uri, or the uri names a
     * provider this server does not have, such as one from another server.
     */
    data object Unresolvable : ArtistAliasLookup

    /** No answer: a timeout, a lost connection, or a provider that is down for now. */
    data object Failed : ArtistAliasLookup
}

fun ArtistAliasLookup.aliasesOrEmpty(): List<String> =
    (this as? ArtistAliasLookup.Resolved)?.aliases.orEmpty()
