package net.asksakis.massdroidv2.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import net.asksakis.massdroidv2.data.party.PartyPlayerSettingParser
import net.asksakis.massdroidv2.data.party.opensOnlyAtHome
import net.asksakis.massdroidv2.data.provider.ProviderManifestCache
import net.asksakis.massdroidv2.data.websocket.ConfigProviderGetArgs
import net.asksakis.massdroidv2.data.websocket.ConfigProviderSaveArgs
import net.asksakis.massdroidv2.data.websocket.ConnectionState
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import net.asksakis.massdroidv2.data.websocket.sendCommand
import net.asksakis.massdroidv2.domain.model.PartyInfo
import net.asksakis.massdroidv2.domain.model.PartyJoinLink
import net.asksakis.massdroidv2.domain.model.PartyMode
import net.asksakis.massdroidv2.domain.model.PartyPlayerSetting
import net.asksakis.massdroidv2.domain.repository.PartyRepository

class PartyRepositoryImpl(
    private val wsClient: MaWebSocketClient,
    private val providerManifestCache: ProviderManifestCache
) : PartyRepository {

    // The calls of setPlayer waiting for the plugin's reload, during which a read of the plugin
    // list can miss the plugin. A count, so the end of one wait does not end another.
    private val reloadHolds = MutableStateFlow(0)

    override val available: Flow<Boolean> = combine(
        providerManifestCache.loadedDomains.map { MaCommands.Party.PLUGIN_DOMAIN in it },
        reloadHolds
    ) { loaded, holds -> loaded || holds > 0 }
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

    override suspend fun playerSetting(): PartyPlayerSetting? {
        val me = wsClient.sendCommand(MaCommands.Auth.ME) as? JsonObject
        if (me?.get("role").string() != ROLE_ADMIN) return null
        val instanceId = providerManifestCache.instanceIdOf(MaCommands.Party.PLUGIN_DOMAIN) ?: return null
        return storedSetting(instanceId)
    }

    override suspend fun setPlayer(value: String) {
        val instanceId = checkNotNull(providerManifestCache.instanceIdOf(MaCommands.Party.PLUGIN_DOMAIN)) {
            "The Party plugin is not loaded"
        }
        // The server reloads the plugin only when a value changes, so a save of the stored value
        // would leave nothing to wait for.
        if (storedSetting(instanceId)?.value == value) return
        reloadHolds.update { it + 1 }
        try {
            val saved = wsClient.sendCommand(
                MaCommands.ConfigProviders.SAVE,
                ConfigProviderSaveArgs(
                    providerDomain = MaCommands.Party.PLUGIN_DOMAIN,
                    instanceId = instanceId,
                    values = buildJsonObject { put(MaCommands.Party.PLAYER_KEY, value) }
                )
            )
            checkNotNull(saved) { "The server did not save the party player" }
            // The server reloads the plugin a second after the save, and the plugin list is read
            // again a second after the reload's events. The plugin's commands answer again once
            // a read started after the save lists it; a read already running when the save was
            // answered may have seen the plugin before the reload. Bounded, so a missed event
            // cannot hold the screen; past the bound the plugin list says what is true.
            val readsBefore = providerManifestCache.instanceReadsStarted
            withTimeoutOrNull(RELOAD_SETTLE_TIMEOUT_MS) {
                providerManifestCache.instancesRead.first {
                    it > readsBefore && MaCommands.Party.PLUGIN_DOMAIN in providerManifestCache.loadedDomains.value
                }
            }
        } finally {
            reloadHolds.update { it - 1 }
        }
    }

    private suspend fun storedSetting(instanceId: String): PartyPlayerSetting? {
        val config = wsClient.sendCommand(MaCommands.ConfigProviders.GET, ConfigProviderGetArgs(instanceId))
        return PartyPlayerSettingParser.parse(config as? JsonObject)
    }

    /** A non-blank string value; a JSON null, a missing value or an empty string is null. */
    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private companion object {
        const val ROLE_ADMIN = "admin"
        const val RELOAD_SETTLE_TIMEOUT_MS = 10_000L
    }
}
