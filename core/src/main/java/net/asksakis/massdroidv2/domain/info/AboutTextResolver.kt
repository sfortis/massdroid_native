package net.asksakis.massdroidv2.domain.info

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import net.asksakis.massdroidv2.data.musicbrainz.AlbumInfoResolver
import net.asksakis.massdroidv2.data.musicbrainz.ArtistBioResolver
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A few words to show beside a playing track: about its album when anything describes the
 * album, otherwise about its artist.
 *
 * Each subject is asked of Music Assistant first and of the MusicBrainz and Wikipedia chain
 * second, the same order the phone's artist page uses for the bio:
 *  1. the album's Music Assistant description,
 *  2. the album's Wikipedia summary ([AlbumInfoResolver]),
 *  3. the artist's Music Assistant description,
 *  4. the artist's Wikipedia summary ([ArtistBioResolver]).
 *
 * Small releases that exist only on a streaming provider usually have none of these, and
 * the answer is then null. A Music Assistant description that carries a phone number or an
 * email address is skipped ([PromotionalText]): it is an artist's contact line, not an about.
 */
@Singleton
class AboutTextResolver @Inject constructor(
    private val musicRepository: MusicRepository,
    private val albumInfoResolver: AlbumInfoResolver,
    private val artistBioResolver: ArtistBioResolver,
) {

    suspend fun forTrack(track: Track): AboutText.Shown? {
        val album = lookup("album") {
            val id = track.albumItemId
            val provider = track.albumProvider
            if (id != null && provider != null) musicRepository.getAlbum(id, provider) else null
        }
        val albumName = album?.name ?: track.albumName
        album?.description.usable()?.let { return AboutText.Shown(it, AboutSubject.ALBUM, albumName) }

        val artist = lookup("artist") {
            val id = track.artistItemId
            val provider = track.artistProvider
            if (id != null && provider != null) musicRepository.getArtist(id, provider) else null
        }
        // The track's own artist line joins several names with ", "; the first is the
        // primary artist, the one an album is filed under.
        val artistName = artist?.name ?: track.artistNames.substringBefore(", ").trim()
        if (artistName.isEmpty()) return null

        if (albumName.isNotBlank()) {
            lookup("album info") {
                albumInfoResolver.resolve(artistName, albumName, album?.albumType, album?.year ?: track.year)
            }?.let { return AboutText.Shown(it, AboutSubject.ALBUM, albumName) }
        }
        artist?.description.usable()?.let { return AboutText.Shown(it, AboutSubject.ARTIST, artistName) }
        return lookup("artist bio") { artistBioResolver.resolve(artistName, artist?.mbid) }
            ?.let { AboutText.Shown(it, AboutSubject.ARTIST, artistName) }
    }

    /**
     * The about text of whatever [tracks] is playing, for a player screen. Looked up again
     * only when the album or the artist changes, not for every track of the same album.
     * [AboutText.Loading] while the next one is looked up, so the text of the album before is
     * never shown under a new cover, and so a screen can keep its layout until the answer is in.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun follow(tracks: Flow<Track?>): Flow<AboutText> = tracks
        .distinctUntilChanged(::sameAboutSubject)
        .transformLatest { track ->
            emit(AboutText.Loading)
            emit(track?.let { forTrack(it) } ?: AboutText.Absent)
        }

    private fun String?.usable(): String? =
        this?.trim()?.takeIf { it.isNotEmpty() && !PromotionalText.hasContactDetails(it) }

    /** Runs one step of the chain; a failed step is skipped rather than ending the chain. */
    private suspend fun <T> lookup(step: String, block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$step lookup failed: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "AboutText"
    }
}

/** What a player screen shows of the about text of the playing track. */
sealed interface AboutText {
    /** Being looked up; a screen keeps its current layout. */
    data object Loading : AboutText

    /** [text] describes the [subject] called [name]. */
    data class Shown(val text: String, val subject: AboutSubject, val name: String) : AboutText

    /** Nothing describes the album or the artist. */
    data object Absent : AboutText
}

enum class AboutSubject { ALBUM, ARTIST }

/** Whether two tracks share the album and the artist the about text describes. */
private fun sameAboutSubject(a: Track?, b: Track?): Boolean =
    a?.albumItemId == b?.albumItemId && a?.albumProvider == b?.albumProvider &&
        a?.artistItemId == b?.artistItemId && a?.artistProvider == b?.artistProvider &&
        a?.albumName == b?.albumName && a?.artistNames == b?.artistNames
