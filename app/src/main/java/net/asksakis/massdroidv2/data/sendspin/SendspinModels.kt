package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Auth (proxy mode)

@Serializable
data class SendspinAuthMessage(
    val type: String = "auth",
    val token: String,
    @SerialName("client_id") val clientId: String
)

// Outgoing messages

@Serializable
data class AudioFormatSpec(
    val codec: String = "opus",
    @SerialName("sample_rate") val sampleRate: Int = 48000,
    val channels: Int = 2,
    @SerialName("bit_depth") val bitDepth: Int = 16
)

private val defaultFormats = listOf(
    AudioFormatSpec(codec = "opus", sampleRate = 48000, bitDepth = 16, channels = 2),
    AudioFormatSpec(codec = "flac", sampleRate = 48000, bitDepth = 24, channels = 2),
    AudioFormatSpec(codec = "pcm", sampleRate = 48000, bitDepth = 16, channels = 2)
)

@Serializable
data class PlayerV1Support(
    @SerialName("supported_formats") val supportedFormats: List<AudioFormatSpec> = defaultFormats,
    @SerialName("buffer_capacity") val bufferCapacity: Int = 8000000,
    @SerialName("supported_commands") val supportedCommands: List<String> = listOf("volume", "mute")
)

@Serializable
data class DeviceInfo(
    @SerialName("product_name") val productName: String = "Mobile Application",
    val manufacturer: String = "asksakis.net",
    @SerialName("software_version") val softwareVersion: String = "1.0.0"
)

@Serializable
data class ClientHelloPayload(
    @SerialName("client_id") val clientId: String,
    val name: String,
    val version: Int = 1,
    @SerialName("supported_roles") val supportedRoles: List<String> = listOf("player@v1", "metadata@v1"),
    @SerialName("device_info") val deviceInfo: DeviceInfo = DeviceInfo(),
    @SerialName("player@v1_support") val playerV1Support: PlayerV1Support = PlayerV1Support()
)

@Serializable
data class SendspinClientHello(
    val type: String = "client/hello",
    val payload: ClientHelloPayload
)

@Serializable
data class PlayerStateInfo(
    val volume: Int = 100,
    val muted: Boolean = false,
    @SerialName("static_delay_ms")
    val staticDelayMs: Int = 0
)

@Serializable
data class ClientStatePayload(
    val state: String = "synchronized",
    val player: PlayerStateInfo = PlayerStateInfo()
)

@Serializable
data class SendspinClientState(
    val type: String = "client/state",
    val payload: ClientStatePayload = ClientStatePayload()
)

// Time sync

@Serializable
data class ClientTimePayload(
    @SerialName("client_transmitted") val clientTransmitted: Long
)

@Serializable
data class SendspinClientTime(
    val type: String = "client/time",
    val payload: ClientTimePayload
)

@Serializable
data class ServerTimePayload(
    @SerialName("client_transmitted") val clientTransmitted: Long,
    @SerialName("server_received") val serverReceived: Long,
    @SerialName("server_transmitted") val serverTransmitted: Long = 0
)

// Incoming message payloads

@Serializable
data class StreamStartPlayerInfo(
    val codec: String = "opus",
    @SerialName("sample_rate") val sampleRate: Int = 48000,
    val channels: Int = 2,
    @SerialName("bit_depth") val bitDepth: Int = 16,
    @SerialName("codec_header") val codecHeader: String? = null
)

@Serializable
data class StreamStartPayload(
    val player: StreamStartPlayerInfo = StreamStartPlayerInfo()
)

@Serializable
data class PlayerCommandPayload(
    val command: String,
    val volume: Int? = null,
    val mute: Boolean? = null
)

@Serializable
data class ServerCommandPayload(
    val player: PlayerCommandPayload? = null
)

@Serializable
data class MetadataProgressPayload(
    @SerialName("track_progress") val trackProgress: Long? = null,
    @SerialName("track_duration") val trackDuration: Long? = null,
    @SerialName("playback_speed") val playbackSpeed: Int? = null
)

@Serializable
data class ServerMetadataPayload(
    val timestamp: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    @SerialName("album_artist") val albumArtist: String? = null,
    val album: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    val year: Int? = null,
    val track: Int? = null,
    val progress: MetadataProgressPayload? = null,
    val repeat: String? = null,
    val shuffle: Boolean? = null
)

@Serializable
data class ServerStatePayload(
    val metadata: ServerMetadataPayload? = null
)

// Runtime format change

@Serializable
data class RequestFormatPlayerPayload(
    val codec: String,
    @SerialName("sample_rate") val sampleRate: Int = 48000,
    @SerialName("bit_depth") val bitDepth: Int = 16,
    val channels: Int = 2
)

@Serializable
data class RequestFormatPayload(
    val player: RequestFormatPlayerPayload
)

@Serializable
data class SendspinRequestFormat(
    val type: String = "stream/request-format",
    val payload: RequestFormatPayload
)

// Goodbye

@Serializable
data class GoodbyePayload(
    val reason: String = "user_request"
)

@Serializable
data class SendspinGoodbye(
    val type: String = "client/goodbye",
    val payload: GoodbyePayload = GoodbyePayload()
)

// Incoming message dispatch

sealed class SendspinIncoming {
    data object AuthOk : SendspinIncoming()
    data class AuthError(val message: String) : SendspinIncoming()
    data class ServerHello(val raw: JsonObject) : SendspinIncoming()
    data class ServerTime(val payload: ServerTimePayload) : SendspinIncoming()
    data object GroupUpdate : SendspinIncoming()
    data class StreamStart(val payload: StreamStartPayload) : SendspinIncoming()
    data object StreamEnd : SendspinIncoming()
    data object StreamClear : SendspinIncoming()
    data class ServerState(val payload: ServerStatePayload) : SendspinIncoming()
    data class ServerCommand(val payload: ServerCommandPayload) : SendspinIncoming()
    data class Unknown(val type: String) : SendspinIncoming()

    companion object {
        fun parse(text: String, json: Json): SendspinIncoming {
            val obj = json.parseToJsonElement(text).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: return Unknown("no_type")

            return when (type) {
                "auth_ok" -> AuthOk
                "auth_error" -> AuthError(
                    obj["message"]?.jsonPrimitive?.content ?: "Authentication failed"
                )
                "server/hello" -> ServerHello(obj)
                "server/time" -> ServerTime(
                    json.decodeFromJsonElement(
                        ServerTimePayload.serializer(),
                        obj["payload"]!!
                    )
                )
                "group/update" -> GroupUpdate
                "stream/start" -> StreamStart(
                    json.decodeFromJsonElement(
                        StreamStartPayload.serializer(),
                        obj["payload"]!!
                    )
                )
                "stream/end" -> StreamEnd
                "stream/clear" -> StreamClear
                "server/state" -> ServerState(
                    json.decodeFromJsonElement(
                        ServerStatePayload.serializer(),
                        obj["payload"]!!
                    )
                )
                "server/command" -> ServerCommand(
                    json.decodeFromJsonElement(
                        ServerCommandPayload.serializer(),
                        obj["payload"]!!
                    )
                )
                else -> {
                    Log.d("SendspinParse", "Unknown type=$type payload=${obj["payload"]}")
                    Unknown(type)
                }
            }
        }
    }
}
