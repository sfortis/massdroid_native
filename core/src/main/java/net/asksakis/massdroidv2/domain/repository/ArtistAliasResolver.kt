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
    /** The other uris for [artistUri], or empty when it cannot be resolved. */
    suspend fun aliasesFor(artistUri: String): List<String>
}
