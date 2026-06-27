package net.asksakis.massdroidv2.domain.recommendation

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import net.asksakis.massdroidv2.data.genre.GenreRepository
import net.asksakis.massdroidv2.data.lastfm.LastFmGenreResolver
import net.asksakis.massdroidv2.data.lastfm.LastFmLibraryEnricher
import net.asksakis.massdroidv2.data.lastfm.LastFmSimilarResolver
import net.asksakis.massdroidv2.data.util.ProviderHealthReporter
import net.asksakis.massdroidv2.domain.model.Artist
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import net.asksakis.massdroidv2.domain.repository.PlayHistoryRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import net.asksakis.massdroidv2.domain.repository.SmartListeningRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DiscoverFeed"
private const val BLL_GENRE_SCORE_DAYS = 90
private const val BLL_GENRE_SCORE_LIMIT = 20
private const val SUPPRESSED_ARTIST_LOOKBACK_DAYS = 120

/**
 * Builds the full Discover feed (the same engine-driven sections the phone shows):
 * library load -> Last.fm/BLL discovery (artists + albums) -> genre + recommendation
 * sections. Lifted out of DiscoverViewModel so the headless AAOS car renders the
 * identical feed. @Singleton + stateless per call (the caller supplies the scope);
 * the phone VM keeps the caching / WS-event incremental refresh / disk cache around it.
 */
@Singleton
class DiscoverFeedOrchestrator @Inject constructor(
    musicRepository: MusicRepository,
    playHistoryRepository: PlayHistoryRepository,
    private val genreRepository: GenreRepository,
    private val settingsRepository: SettingsRepository,
    private val smartListeningRepository: SmartListeningRepository,
    recommendationEngine: RecommendationEngine,
    lastFmSimilarResolver: LastFmSimilarResolver,
    lastFmGenreResolver: LastFmGenreResolver,
    private val lastFmLibraryEnricher: LastFmLibraryEnricher,
    providerHealthReporter: ProviderHealthReporter,
    private val sectionBuilder: DiscoverSectionBuilder,
) {

    private val contentLoader = DiscoverContentLoader(musicRepository, genreRepository)
    private val recommendationOrchestrator = DiscoverRecommendationOrchestrator(
        musicRepository = musicRepository,
        playHistoryRepository = playHistoryRepository,
        genreRepository = genreRepository,
        recommendationEngine = recommendationEngine,
        lastFmSimilarResolver = lastFmSimilarResolver,
        lastFmGenreResolver = lastFmGenreResolver,
        providerHealthReporter = providerHealthReporter,
    )

    /**
     * The built feed plus the library maps the mix paths reuse (so the phone VM does
     * not reload them). Genre maps are already exclusion-filtered.
     */
    data class DiscoverFeed(
        val sections: List<DiscoverSection>,
        val topArtists: List<Artist>,
        val artistByUri: Map<String, Artist>,
        val genreArtists: Map<String, List<String>>,
        val strictGenreArtists: Map<String, List<String>>,
    )

    @Suppress("TooGenericExceptionCaught")
    suspend fun buildFeed(): DiscoverFeed {
        // Exclusions first (blocked + suppressed) so the library load and the genre
        // maps are filtered consistently. Only when Smart Listening is enabled.
        var excludedArtistUris = emptySet<String>()
        if (settingsRepository.smartListeningEnabled.first()) {
            val blocked = smartListeningRepository.getBlockedArtistUris()
            val suppressed = smartListeningRepository.getSuppressedArtistUris(days = SUPPRESSED_ARTIST_LOOKBACK_DAYS)
            excludedArtistUris = blocked + suppressed
        }

        val content = contentLoader.load(excludedArtistUris = excludedArtistUris)
        val merged = content.mergedArtists
        // Fire-and-forget background enrichment (manages its own scope).
        lastFmLibraryEnricher.enrichInBackground(merged)

        val artistByUri = merged.mapNotNull { artist ->
            artist.canonicalKey()?.let { it to artist }
        }.toMap()

        val strictGenreArtists = content.strictGenreArtists.mapValues { (_, uris) ->
            uris.filterNot { uri ->
                val key = MediaIdentity.artistKeyFromUri(uri)
                key != null && key in excludedArtistUris
            }
        }
        val genreArtists = content.genreArtists.mapValues { (_, uris) ->
            uris.filterNot { uri ->
                val key = MediaIdentity.artistKeyFromUri(uri)
                key != null && key in excludedArtistUris
            }
        }
        val genreItems = content.genreItems.filter { it.name in genreArtists.keys }

        val discovery = try {
            recommendationOrchestrator.buildDiscovery(
                libraryArtists = merged,
                serverFolders = content.enrichedFolders,
                excludedArtistUris = excludedArtistUris,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build discovery", e)
            DiscoveryResult(emptyList(), emptyList())
        }

        val bllGenreScores = try {
            genreRepository.scoredGenres(days = BLL_GENRE_SCORE_DAYS, limit = BLL_GENRE_SCORE_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        val sections = sectionBuilder.buildSections(
            serverFolders = content.enrichedFolders,
            suggestedArtists = discovery.artists,
            suggestedAlbums = discovery.albums,
            genreItems = genreItems,
            bllGenreScores = bllGenreScores,
        )
        Log.d(TAG, "Built ${sections.size} sections (${merged.size} artists)")

        return DiscoverFeed(
            sections = sections,
            topArtists = merged,
            artistByUri = artistByUri,
            genreArtists = genreArtists,
            strictGenreArtists = strictGenreArtists,
        )
    }
}
