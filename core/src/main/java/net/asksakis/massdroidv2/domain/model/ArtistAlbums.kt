package net.asksakis.massdroidv2.domain.model

/**
 * The albums of an artist's "Discography" section: [discography] with each uri once, and
 * without the albums the library section above it already shows ([library]).
 *
 * Both sections sit in one list keyed by uri, so a uri in both crashes the artist page (#87).
 * Music Assistant before 2.9 answers artist_albums for a library artist with the library albums
 * merged with every provider's albums, and it gives the same answer when asked with the
 * artist's provider id, so on those servers the two sections hold the same library albums.
 * Since 2.9 the two answers do not overlap and nothing is removed.
 */
fun discographyBeyondLibrary(library: List<Album>, discography: List<Album>): List<Album> {
    val shown = library.mapTo(HashSet()) { it.uri }
    return discography.distinctBy { it.uri }.filterNot { it.uri in shown }
}
