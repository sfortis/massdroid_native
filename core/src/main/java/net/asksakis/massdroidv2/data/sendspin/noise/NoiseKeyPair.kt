package net.asksakis.massdroidv2.data.sendspin.noise

import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.SecureRandom

/**
 * A long-term or ephemeral X25519 key pair.
 *
 * The public key, base64url encoded, is the Sendspin peer id: the client's `client_id` and the
 * server's `server_id`.
 */
class NoiseKeyPair private constructor(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
) {
    val peerId: String get() = SendspinNoise.b64urlEncode(publicKey)

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): NoiseKeyPair {
            val private = ByteArray(X25519.SCALAR_SIZE)
            X25519.generatePrivateKey(random, private)
            return fromPrivateKey(private)
        }

        fun fromPrivateKey(privateKey: ByteArray): NoiseKeyPair {
            require(privateKey.size == X25519.SCALAR_SIZE) { "X25519 private key must be 32 bytes" }
            val public = ByteArray(X25519.POINT_SIZE)
            X25519.generatePublicKey(privateKey, 0, public, 0)
            return NoiseKeyPair(privateKey.copyOf(), public)
        }
    }
}

internal object NoiseDh {
    /**
     * X25519 between [privateKey] and [publicKey]. An all-zero result means the peer sent a
     * low-order point, which Noise must reject.
     */
    fun agree(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        if (publicKey.size != X25519.POINT_SIZE) {
            throw NoiseProtocolException("X25519 public key must be 32 bytes, got ${publicKey.size}")
        }
        val shared = ByteArray(X25519.POINT_SIZE)
        if (!X25519.calculateAgreement(privateKey, 0, publicKey, 0, shared, 0)) {
            throw NoiseProtocolException("X25519 agreement produced the identity point")
        }
        return shared
    }
}
