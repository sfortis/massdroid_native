package net.asksakis.massdroidv2.data.sendspin.noise

import java.security.MessageDigest
import java.util.Base64

/**
 * Constants and encodings of the Sendspin Noise transport (aiosendspin 9.1.1, MA 2.10.0 and later).
 *
 * The client always picks the AES-GCM suite. The platform's AES-GCM is hardware accelerated on
 * ARMv8 and exists at minSdk 26, while ChaCha20-Poly1305 would need a library below API 28.
 */
object SendspinNoise {
    const val PROTOCOL_VERSION = 1
    const val SUITE = "25519_AESGCM_SHA256"
    const val PROTOCOL_NAME = "Noise_KKpsk2_$SUITE"

    const val KEY_SIZE = 32
    const val PSK_SIZE = 32

    /** Length of a base64url peer id: a 32-byte X25519 public key without padding. */
    const val PEER_ID_LENGTH = 43

    /** Plaintext type byte of a JSON control message inside a transport frame. */
    const val TYPE_JSON: Byte = 0
    const val TYPE_FRAGMENT_MORE: Byte = 2
    const val TYPE_FRAGMENT_END: Byte = 3

    /** Noise's limit on one message, the AEAD tag included. */
    const val MAX_NOISE_MESSAGE = 65535

    /** Noise's 65535-byte transport message limit minus the 16-byte AEAD tag. */
    const val MAX_TRANSPORT_PLAINTEXT = MAX_NOISE_MESSAGE - 16

    private val PSK_ID_LABEL = "sendspin-psk-id-v1".toByteArray(Charsets.US_ASCII)

    /**
     * The published sentinel PSK. A connection admitted with it is encrypted but not
     * authenticated; the server uses it for clients it has no pairing with.
     */
    val SENTINEL_PSK: ByteArray = sha256("sendspin-sentinel-psk-v1".toByteArray(Charsets.US_ASCII))

    /** The id the server names a PSK by in handshake message 1. */
    fun pskId(psk: ByteArray): String {
        require(psk.size == PSK_SIZE) { "PSK must be $PSK_SIZE bytes, got ${psk.size}" }
        return b64urlEncode(sha256(PSK_ID_LABEL + psk))
    }

    fun b64urlEncode(data: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** Decodes base64url with or without padding; throws [IllegalArgumentException] when malformed. */
    fun b64urlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}

/** A Noise handshake or transport message failed to parse or to authenticate. */
class NoiseProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
