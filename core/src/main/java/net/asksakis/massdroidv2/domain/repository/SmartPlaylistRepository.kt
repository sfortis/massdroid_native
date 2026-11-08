package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.Flow
import net.asksakis.massdroidv2.domain.model.SeedAddOutcome
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSeed
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSummary

/** Seeds for Music Assistant's Smart Playlists plugin. */
interface SmartPlaylistRepository {
    /** True while the server has the plugin loaded; its commands exist only then. */
    val available: Flow<Boolean>

    /** The smart playlists that are built from seeds. Filter-based ones are left out. */
    suspend fun seedPlaylists(): List<SmartPlaylistSummary>

    suspend fun addSeed(playlistId: String, seed: SmartPlaylistSeed): SeedAddOutcome

    /** Creates a dynamic smart playlist with [seed] as its only seed. */
    suspend fun createWithSeed(name: String, seed: SmartPlaylistSeed)
}
