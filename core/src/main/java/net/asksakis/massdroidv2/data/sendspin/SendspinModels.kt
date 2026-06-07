package net.asksakis.massdroidv2.data.sendspin

import android.util.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Sendspin protocol fields like `track_duration` and `track_progress`
 * are documented as integer milliseconds, but the upstream Music
 * Assistant `sendspin` provider multiplies a sometimes-float
 * `current_media.duration` by 1000 without an int cast, so the
 * resulting JSON can be either `123456` or `123456.0`. The reference
 * `sendspin-js` client survives this because JavaScript collapses
 * both into the same `number` type; Kotlin's strict `Long` serializer
 * rejects the float literal, which used to drop the entire
 * `server/state` payload and trigger reconnect loops on tracks whose
 * duration carries sub-second precision (e.g. local files via ffprobe
 * or Subsonic-style providers).
 *
 * This serializer accepts both shapes and coerces to `Long`. Trailing
 * decimal fractions are truncated toward zero, which matches the
 * existing UI math (everything is integer ms anyway).
 */
private object FlexibleLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value != null) encoder.encodeLong(value)
    }

    override fun deserialize(decoder: Decoder): Long? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonPrimitive) return null
        if (element.isString) return element.content.toLongOrNull()
        return element.longOrNull ?: element.doubleOrNull?.toLong()
    }
}

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
    // FLAC first: it is the canonical Android codec and, crucially, the order
    // here is the server's fallback when a client has no (or a cleared)
    // `preferred_sendspin_format` override. Listing opus first made the server
    // fall back to opus whenever the override was missing/cleared (e.g. after
    // the server rejected a stale 24-bit override as incompatible), which broke
    // grouped sync. Keeping everything at 48 kHz / 16-bit also removes any
    // resample/convert variable from the timing path (AudioTrack is PCM16).
    AudioFormatSpec(codec = "flac", sampleRate = 48000, bitDepth = 16, channels = 2),
    AudioFormatSpec(codec = "opus", sampleRate = 48000, bitDepth = 16, channels = 2),
    AudioFormatSpec(codec = "pcm", sampleRate = 48000, bitDepth = 16, channels = 2)
)

@Serializable
data class PlayerV1Support(
    @SerialName("supported_formats") val supportedFormats: List<AudioFormatSpec> = defaultFormats,
    // Bytes of compressed audio the server may stream ahead (per Sendspin spec).
    // Sized for ~30 s of FLAC (Deezer 44.1k/16 ≈ 110 KB/s -> ~36 s; 96k/24 -> ~13 s)
    // so a cellular/5G throughput dip is ridden out of the buffer instead of
    // underrunning. MAX_ENCODED_BUFFER_BYTES (engine) stays above this as backstop.
    @SerialName("buffer_capacity") val bufferCapacity: Int = 4_000_000,
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
    @SerialName("track_progress")
    @Serializable(with = FlexibleLongSerializer::class)
    val trackProgress: Long? = null,
    @SerialName("track_duration")
    @Serializable(with = FlexibleLongSerializer::class)
    val trackDuration: Long? = null,
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
    // clientReceivedUs (T4) is stamped at the WebSocket onMessage callback (the
    // earliest point, like the JS reference) rather than later in the manager's
    // coroutine handler — coroutine/deserialize dispatch delay on T4 alone
    // biases the NTP offset and makes us play late. 0 = not stamped (fallback).
    data class ServerTime(val payload: ServerTimePayload, val clientReceivedUs: Long = 0L) : SendspinIncoming()
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
