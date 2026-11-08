package net.asksakis.massdroidv2.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.asksakis.massdroidv2.data.provider.ProviderManifestCache
import net.asksakis.massdroidv2.data.smartplaylist.SmartPlaylistSeeds
import net.asksakis.massdroidv2.data.websocket.MaApiException
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.SmartPlaylistCreateArgs
import net.asksakis.massdroidv2.data.websocket.SmartPlaylistIdArgs
import net.asksakis.massdroidv2.data.websocket.SmartPlaylistUpdateRulesArgs
import net.asksakis.massdroidv2.data.websocket.sendCommand
import net.asksakis.massdroidv2.domain.model.SeedAddOutcome
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSeed
import net.asksakis.massdroidv2.domain.model.SmartPlaylistSummary
import net.asksakis.massdroidv2.domain.repository.SmartPlaylistRepository

class SmartPlaylistRepositoryImpl(
    private val wsClient: MaWebSocketClient,
    providerManifestCache: ProviderManifestCache
) : SmartPlaylistRepository {

    override val available: Flow<Boolean> = providerManifestCache.loadedDomains
        .map { MaCommands.SmartPlaylists.PLUGIN_DOMAIN in it }
        .distinctUntilChanged()

    override suspend fun seedPlaylists(): List<SmartPlaylistSummary> {
        val result = wsClient.sendCommand(MaCommands.SmartPlaylists.LIST) as? JsonArray ?: return emptyList()
        return result.mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val id = (item["playlist_id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val rules = item["rules"] as? JsonObject ?: return@mapNotNull null
            if (!SmartPlaylistSeeds.isSeedBased(rules)) return@mapNotNull null
            SmartPlaylistSummary(
                playlistId = id,
                name = (item["name"] as? JsonPrimitive)?.content ?: id,
                seedCount = SmartPlaylistSeeds.seedCount(rules)
            )
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun addSeed(playlistId: String, seed: SmartPlaylistSeed): SeedAddOutcome {
        // Read right before the write: update_rules replaces the whole object, so it must
        // carry whatever the server holds now, not what the picker listed.
        val rules = wsClient.sendCommand(MaCommands.SmartPlaylists.GET_RULES, SmartPlaylistIdArgs(playlistId))
            as? JsonObject ?: throw MaApiException("That smart playlist no longer exists", -1)
        return when (val result = SmartPlaylistSeeds.addSeed(rules, seed)) {
            SmartPlaylistSeeds.AddResult.AlreadyPresent -> SeedAddOutcome.ALREADY_PRESENT
            SmartPlaylistSeeds.AddResult.Full -> SeedAddOutcome.FULL
            is SmartPlaylistSeeds.AddResult.Added -> {
                wsClient.sendCommand(
                    MaCommands.SmartPlaylists.UPDATE_RULES,
                    SmartPlaylistUpdateRulesArgs(playlistId, result.rules)
                )
                SeedAddOutcome.ADDED
            }
        }
    }

    override suspend fun createWithSeed(name: String, seed: SmartPlaylistSeed) {
        // Not repeated after a timeout: the first create may have run, and a second one
        // would leave two playlists of the same name.
        wsClient.sendCommand(
            MaCommands.SmartPlaylists.CREATE,
            SmartPlaylistCreateArgs(name = name, rules = SmartPlaylistSeeds.newRules(seed)),
            retryAfterTimeout = false
        )
    }
}
