package net.asksakis.massdroidv2.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import net.asksakis.massdroidv2.ui.screens.home.HomeScreen
import net.asksakis.massdroidv2.ui.screens.home.PlayersScreen
import net.asksakis.massdroidv2.ui.screens.library.AlbumDetailScreen
import net.asksakis.massdroidv2.ui.screens.library.ArtistDetailScreen
import net.asksakis.massdroidv2.ui.screens.library.LibraryScreen
import net.asksakis.massdroidv2.ui.screens.library.PlaylistDetailScreen
import net.asksakis.massdroidv2.ui.screens.nowplaying.NowPlayingScreen
import net.asksakis.massdroidv2.ui.screens.queue.QueueScreen
import net.asksakis.massdroidv2.ui.screens.search.SearchScreen
import net.asksakis.massdroidv2.ui.screens.settings.RecommendationInsightsScreen
import net.asksakis.massdroidv2.ui.screens.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val PLAYERS = "players"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val NOW_PLAYING = "now_playing"
    const val QUEUE = "queue"
    const val SETTINGS = "settings"
    const val RECOMMENDATION_INSIGHTS = "recommendation_insights"
    const val ARTIST_DETAIL = "artist/{itemId}/{provider}?name={name}"
    const val ALBUM_DETAIL = "album/{itemId}/{provider}?name={name}"
    const val PLAYLIST_DETAIL = "playlist/{itemId}/{provider}?name={name}&uri={uri}&favorite={favorite}"

    fun artistDetail(itemId: String, provider: String, name: String = "") =
        "artist/$itemId/$provider?name=${android.net.Uri.encode(name)}"
    fun albumDetail(itemId: String, provider: String, name: String = "") =
        "album/$itemId/$provider?name=${android.net.Uri.encode(name)}"
    fun playlistDetail(itemId: String, provider: String, name: String = "", uri: String = "", favorite: Boolean = false) =
        "playlist/$itemId/$provider?name=${android.net.Uri.encode(name)}&uri=${android.net.Uri.encode(uri)}&favorite=$favorite"
}

@Composable
fun MassDroidNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onArtistClick = { artist ->
                    navController.navigate(Routes.artistDetail(artist.itemId, artist.provider, artist.name))
                },
                onAlbumClick = { album ->
                    navController.navigate(Routes.albumDetail(album.itemId, album.provider, album.name))
                },
                onPlaylistClick = { playlist ->
                    navController.navigate(
                        Routes.playlistDetail(playlist.itemId, playlist.provider, playlist.name, playlist.uri, playlist.favorite)
                    )
                },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.PLAYERS) {
            PlayersScreen(
                onNavigateToNowPlaying = { navController.navigate(Routes.NOW_PLAYING) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onArtistClick = { artist ->
                    navController.navigate(Routes.artistDetail(artist.itemId, artist.provider, artist.name))
                },
                onAlbumClick = { album ->
                    navController.navigate(Routes.albumDetail(album.itemId, album.provider, album.name))
                },
                onPlaylistClick = { playlist ->
                    navController.navigate(Routes.playlistDetail(playlist.itemId, playlist.provider, playlist.name, playlist.uri, playlist.favorite))
                }
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onArtistClick = { artist ->
                    navController.navigate(Routes.artistDetail(artist.itemId, artist.provider, artist.name))
                },
                onAlbumClick = { album ->
                    navController.navigate(Routes.albumDetail(album.itemId, album.provider, album.name))
                },
                onPlaylistClick = { playlist ->
                    navController.navigate(Routes.playlistDetail(playlist.itemId, playlist.provider, playlist.name, playlist.uri, playlist.favorite))
                }
            )
        }

        composable(Routes.NOW_PLAYING) {
            NowPlayingScreen(
                onBack = { navController.popBackStack() },
                onNavigateToQueue = {
                    navController.navigate(Routes.QUEUE) {
                        launchSingleTop = true
                    }
                },
                onNavigateToArtist = { itemId, provider, name ->
                    navController.navigate(Routes.artistDetail(itemId, provider, name))
                },
                onNavigateToAlbum = { itemId, provider, name ->
                    navController.navigate(Routes.albumDetail(itemId, provider, name))
                }
            )
        }

        composable(Routes.QUEUE) {
            QueueScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenRecommendationInsights = { navController.navigate(Routes.RECOMMENDATION_INSIGHTS) }
            )
        }

        composable(Routes.RECOMMENDATION_INSIGHTS) {
            RecommendationInsightsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            Routes.ARTIST_DETAIL,
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("provider") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" }
            )
        ) {
            ArtistDetailScreen(
                onBack = { navController.popBackStack() },
                onAlbumClick = { album ->
                    navController.navigate(Routes.albumDetail(album.itemId, album.provider, album.name))
                },
                onArtistClick = { artist ->
                    navController.navigate(Routes.artistDetail(artist.itemId, artist.provider, artist.name))
                }
            )
        }

        composable(
            Routes.ALBUM_DETAIL,
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("provider") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" }
            )
        ) {
            AlbumDetailScreen(
                onBack = { navController.popBackStack() },
                onArtistClick = { artist ->
                    navController.navigate(Routes.artistDetail(artist.itemId, artist.provider, artist.name))
                }
            )
        }

        composable(
            Routes.PLAYLIST_DETAIL,
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("provider") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                navArgument("favorite") { type = NavType.BoolType; defaultValue = false }
            )
        ) {
            PlaylistDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}
