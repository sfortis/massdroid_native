package net.asksakis.massdroidv2.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.asksakis.massdroidv2.data.party.opensOnlyAtHome
import net.asksakis.massdroidv2.data.provider.ProviderManifestCache
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.domain.model.PartyInfo
import net.asksakis.massdroidv2.domain.model.PartyJoinLink
import net.asksakis.massdroidv2.domain.model.PartyMode
import net.asksakis.massdroidv2.domain.repository.PartyRepository

class PartyRepositoryImpl(
    private val wsClient: MaWebSocketClient,
    providerManifestCache: ProviderManifestCache
) : PartyRepository {

    override val available: Flow<Boolean> = providerManifestCache.loadedDomains
        .map { MaCommands.Party.PLUGIN_DOMAIN in it }
        .distinctUntilChanged()

    // A new Connected value is a new connection: each one carries its own generation.
    override val connections: Flow<Unit>
        get() = wsClient.connectionState
            .filterIsInstance<ConnectionState.Connected>()
            .map { }

    override suspend fun joinLink(): PartyJoinLink? {
        val url = wsClient.sendCommand(MaCommands.Party.URL).string() ?: return null
        return PartyJoinLink(url, homeNetworkOnly = opensOnlyAtHome(url))
    }

    override suspend fun targetQueueId(): String? = wsClient.sendCommand(MaCommands.Party.PLAYER).string()

    override suspend fun info(): PartyInfo {
        val config = wsClient.sendCommand(MaCommands.Party.CONFIG) as? JsonObject
        return PartyInfo(
            name = config?.get("party_name").string(),
            qrText = config?.get("qr_text").string(),
            mode = when (config?.get("mode").string()) {
                "venue" -> PartyMode.VENUE
                "remote" -> PartyMode.REMOTE
                else -> PartyMode.UNKNOWN
            }
        )
    }

    /** A non-blank string value; a JSON null, a missing value or an empty string is null. */
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
