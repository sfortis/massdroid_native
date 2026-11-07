package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.Flow
import net.asksakis.massdroidv2.domain.model.PartyInfo
import net.asksakis.massdroidv2.domain.model.PartyJoinLink

/** Music Assistant's Party plugin, which lets guests add songs from a join link. */
interface PartyRepository {
    /** True while the server has the plugin loaded; its commands exist only then. */
    val available: Flow<Boolean>

    /**
     * Emits once for each connection to a server, the current one included, so a screen that
     * shows the party reloads after a reconnect or a switch to another server.
     */
    val connections: Flow<Unit>

    /** The guest join link, or null while guest access is off. */
    suspend fun joinLink(): PartyJoinLink?

    /** The queue that receives the guests' songs, or null when the server has none. */
    suspend fun targetQueueId(): String?

    suspend fun info(): PartyInfo
}
