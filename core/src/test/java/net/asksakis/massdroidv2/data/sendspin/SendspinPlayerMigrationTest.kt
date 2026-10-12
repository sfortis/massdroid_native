package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.data.websocket.MaCommands
import net.asksakis.massdroidv2.data.websocket.MaWebSocketClient
import org.junit.Test

/**
 * The one-time move of the old UUID player's settings onto the Noise player. The JSON shapes are
 * the ones MA 2.10 returns. For a key never stored, `config/players/get_value` answers the entry's
 * default while the old player is still registered, and the sentinel default once it is gone.
 */
class SendspinPlayerMigrationTest {

    private val old = "5046ad54-4f5a-43c0-8278-e0d5a0a51dc9"
    private val new = "wmemhm3-pxGJV_WggWdA_vuaILaJXAhDbr2i5pTsC2I"
    private fun json(text: String) = Json.parseToJsonElement(text)

    private val newPlayerConfig = json(
        """{"player_id":"$new","name":null,"values":{
            "sendspin_static_delay":{"key":"sendspin_static_delay","type":"integer","value":0,"default_value":0},
            "icon":{"key":"icon","type":"icon","value":"mdi-speaker","default_value":"mdi-speaker"},
            "hide_in_ui":{"key":"hide_in_ui","type":"boolean","value":true,"default_value":true},
            "volume_normalization_target":{"key":"volume_normalization_target","type":"float","value":-17,"default_value":-17},
            "protocol_parent_id":{"key":"protocol_parent_id","type":"string","value":null,"default_value":null},
            "airplay||protocol||enabled":{"key":"airplay||protocol||enabled","type":"boolean","value":true,"default_value":true},
            "sync_label":{"key":"sync_label","type":"label","value":null,"default_value":null}
        }}"""
    ).jsonObject

    private val storedOldValues = mapOf(
        "sendspin_static_delay" to JsonPrimitive(21),
        "icon" to JsonPrimitive("smartphone"),
        "hide_in_ui" to JsonPrimitive(false),
    )

    private fun client(oldPlayerKnown: Boolean = true, oldPlayerRegistered: Boolean = false): MaWebSocketClient = mockk(relaxed = true) {
        coEvery { sendCommand(MaCommands.ConfigPlayers.LIST, any(), any(), any(), any()) } returns json(
            if (oldPlayerKnown) """[{"player_id":"$old","name":"MassDroid S25"},{"player_id":"other","name":"x"}]"""
            else """[{"player_id":"other","name":"x"}]"""
        )
        coEvery { sendCommand(MaCommands.ConfigPlayers.GET, any(), any(), any(), any()) } returns newPlayerConfig
        coEvery { sendCommand(MaCommands.ConfigPlayers.GET_VALUE, any(), any(), any(), any()) } answers {
            val args = checkNotNull(secondArg<JsonObject?>())
            val key = args.getValue("key").jsonPrimitive.content
            val entryDefault = newPlayerConfig.getValue("values").jsonObject.getValue(key).jsonObject.getValue("default_value")
            storedOldValues[key] ?: if (oldPlayerRegistered) entryDefault else args.getValue("default")
        }
        coEvery { sendCommand(MaCommands.ConfigPlayerQueues.GET, any(), any(), any(), any()) } returns json(
            """{"queue_id":"$old","values":{
                "crossfade_mode":{"key":"crossfade_mode","type":"string","value":"standard_crossfade","default_value":"disabled"},
                "dont_stop_the_music":{"key":"dont_stop_the_music","type":"boolean","value":true,"default_value":true}
            }}"""
        )
    }

    @Test
    fun `the name, the stored values and the changed queue values move`() = runBlocking {
        assertMoved(oldPlayerRegistered = false)
    }

    @Test
    fun `defaults answered for a still registered old player are not copied`() = runBlocking {
        assertMoved(oldPlayerRegistered = true)
    }

    private suspend fun assertMoved(oldPlayerRegistered: Boolean) {
        val ws = client(oldPlayerRegistered = oldPlayerRegistered)
        val saved = mutableListOf<JsonObject>()
        val queueSaved = mutableListOf<JsonObject>()
        coEvery { ws.sendCommand(MaCommands.ConfigPlayers.SAVE, any(), any(), any(), any()) } answers {
            saved += checkNotNull(secondArg<JsonObject?>())
            null
        }
        coEvery { ws.sendCommand(MaCommands.ConfigPlayerQueues.SAVE, any(), any(), any(), any()) } answers {
            queueSaved += checkNotNull(secondArg<JsonObject?>())
            null
        }

        val outcome = SendspinPlayerMigration(ws).migrate(fromId = old, toId = new)

        assertThat(outcome).isEqualTo(SendspinPlayerMigration.Outcome.MOVED)
        assertThat(saved.single().getValue("player_id").jsonPrimitive.content).isEqualTo(new)
        assertThat(saved.single().getValue("values").jsonObject).isEqualTo(
            JsonObject(mapOf("name" to JsonPrimitive("MassDroid S25")) + storedOldValues)
        )
        assertThat(queueSaved.single().getValue("queue_id").jsonPrimitive.content).isEqualTo(new)
        assertThat(queueSaved.single().getValue("values").jsonObject)
            .isEqualTo(JsonObject(mapOf("crossfade_mode" to JsonPrimitive("standard_crossfade"))))
        // The old player's config stays on the server.
        coVerify(exactly = 0) { ws.sendCommand(CONFIG_PLAYERS_REMOVE, any(), any(), any(), any()) }
    }

    @Test
    fun `identity, protocol mirror and display-only entries are never asked for`() = runBlocking {
        val ws = client()

        SendspinPlayerMigration(ws).migrate(fromId = old, toId = new)

        for (key in listOf("protocol_parent_id", "airplay||protocol||enabled", "sync_label")) {
            coVerify(exactly = 0) {
                ws.sendCommand(MaCommands.ConfigPlayers.GET_VALUE, matchNullable<JsonObject> { it?.get("key")?.jsonPrimitive?.content == key }, any(), any(), any())
            }
        }
    }

    @Test
    fun `a server that never knew the old player is left alone`() = runBlocking {
        val ws = client(oldPlayerKnown = false)

        val outcome = SendspinPlayerMigration(ws).migrate(fromId = old, toId = new)

        assertThat(outcome).isEqualTo(SendspinPlayerMigration.Outcome.NO_OLD_PLAYER)
        coVerify(exactly = 0) { ws.sendCommand(MaCommands.ConfigPlayers.SAVE, any(), any(), any(), any()) }
        coVerify(exactly = 0) { ws.sendCommand(CONFIG_PLAYERS_REMOVE, any(), any(), any(), any()) }
    }
}

/** MA's command for deleting a player config, which the migration must never send. */
private const val CONFIG_PLAYERS_REMOVE = "config/players/remove"
