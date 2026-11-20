package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.Flow
import net.asksakis.massdroidv2.domain.model.PartyInfo
import net.asksakis.massdroidv2.domain.model.PartyJoinLink
import net.asksakis.massdroidv2.domain.model.PartyPlayerSetting

/** Music Assistant's Party plugin, which lets guests add songs from a join link. */
interface PartyRepository {
    /**
     * True while the server has the plugin loaded; its commands exist only then. It also stays
     * true while [setPlayer] waits for the reload its save causes, so a screen showing the party
     * does not close or empty itself for that moment.
     */
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

    /**
     * The plugin's player setting, or null when this account may not change it: only an admin
     * can save a plugin's settings.
     */
    suspend fun playerSetting(): PartyPlayerSetting?

    /**
     * Saves the plugin's player setting, a player id or [PartyPlayerSetting.AUTO], and returns
     * once the plugin answers again: the server reloads it after the save. When the server
     * already stores [value] nothing is saved, because the server would not reload the plugin.
     * Throws when the server refuses the save.
     */
    suspend fun setPlayer(value: String)
}
