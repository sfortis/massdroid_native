package net.asksakis.massdroidv2.data.sendspin.noise

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Noise SymmetricState with SHA-256 and AES-GCM: the chaining key, the handshake hash and the
 * current handshake cipher.
 */
internal class NoiseSymmetricState(protocolName: String) {
    private var h: ByteArray
    private var ck: ByteArray
    private var cipher = NoiseCipherState(null)

    init {
        val name = protocolName.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= HASH_LEN) name.copyOf(HASH_LEN) else SendspinNoise.sha256(name)
        ck = h.copyOf()
    }

    val handshakeHash: ByteArray get() = h.copyOf()

    fun mixHash(data: ByteArray) {
        h = SendspinNoise.sha256(h + data)
    }

    fun mixKey(inputKeyMaterial: ByteArray) {
        val (newCk, tempK) = hkdf(ck, inputKeyMaterial, 2)
        ck = newCk
        cipher = NoiseCipherState(tempK)
    }

    fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
        val (newCk, tempH, tempK) = hkdf(ck, inputKeyMaterial, 3)
        ck = newCk
        mixHash(tempH)
        cipher = NoiseCipherState(tempK)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipher.encryptWithAd(h, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    /** Returns the initiator-to-responder cipher first and the responder-to-initiator one second. */
    fun split(): Pair<NoiseCipherState, NoiseCipherState> {
        val (k1, k2) = hkdf(ck, ByteArray(0), 2)
        return NoiseCipherState(k1) to NoiseCipherState(k2)
    }

    private fun hkdf(chainingKey: ByteArray, inputKeyMaterial: ByteArray, outputs: Int): List<ByteArray> {
        val tempKey = hmac(chainingKey, inputKeyMaterial)
        val result = ArrayList<ByteArray>(outputs)
        var previous = ByteArray(0)
        for (i in 1..outputs) {
            previous = hmac(tempKey, previous + i.toByte())
            result += previous
        }
        return result
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private companion object {
        const val HASH_LEN = 32
    }
}

/**
 * The client side of the Sendspin Noise handshake: the responder of pattern KKpsk2.
 *
 * ```
 * KKpsk2:
 *   -> s
 *   <- s
 *   ...
 *   -> e, es, ss          (message 1, server to client, carries {"psk_id": ...})
 *   <- e, ee, se, psk     (message 2, client to server, carries {})
 * ```
 *
 * The server is the initiator. Both static keys are known in advance, so the pre-messages mix the
 * server's key and then the client's key into the handshake hash. The PSK is chosen only after
 * message 1 names it, which works because the `psk` token is processed in message 2.
 *
 * @param prologue for the first handshake, the exact UTF-8 text of `client/init` followed by that
 *   of `server/init`; for a re-handshake, the previous handshake hash.
 * @param ephemeral a fixed ephemeral key pair, for tests only.
 */
class NoiseResponderHandshake(
    private val localStatic: NoiseKeyPair,
    private val remoteStaticPublic: ByteArray,
    prologue: ByteArray,
    private val ephemeral: NoiseKeyPair = NoiseKeyPair.generate(),
) {
    private val symmetric = NoiseSymmetricState(SendspinNoise.PROTOCOL_NAME)
    private var remoteEphemeral: ByteArray? = null
    private var step = Step.AWAIT_MESSAGE_1

    init {
        require(remoteStaticPublic.size == SendspinNoise.KEY_SIZE) { "remote static key must be 32 bytes" }
        symmetric.mixHash(prologue)
        symmetric.mixHash(remoteStaticPublic)
        symmetric.mixHash(localStatic.publicKey)
    }

    /** Consumes handshake message 1 and returns its decrypted payload. */
    fun readMessage1(message: ByteArray): ByteArray {
        check(step == Step.AWAIT_MESSAGE_1) { "message 1 already read" }
        if (message.size < SendspinNoise.KEY_SIZE + TAG_SIZE) {
            throw NoiseProtocolException("handshake message 1 is too short: ${message.size} bytes")
        }
        val re = message.copyOfRange(0, SendspinNoise.KEY_SIZE)
        remoteEphemeral = re
        symmetric.mixHash(re)
        symmetric.mixKey(re)
        symmetric.mixKey(NoiseDh.agree(localStatic.privateKey, re))
        symmetric.mixKey(NoiseDh.agree(localStatic.privateKey, remoteStaticPublic))
        val payload = symmetric.decryptAndHash(message.copyOfRange(SendspinNoise.KEY_SIZE, message.size))
        step = Step.AWAIT_PSK
        return payload
    }

    /**
     * Mixes in [psk], writes handshake message 2 carrying [payload], and returns it with the
     * transport ciphers and the final handshake hash.
     */
    fun writeMessage2(psk: ByteArray, payload: ByteArray): Result {
        check(step == Step.AWAIT_PSK) { "message 1 has not been read" }
        require(psk.size == SendspinNoise.PSK_SIZE) { "PSK must be 32 bytes" }
        val re = checkNotNull(remoteEphemeral)
        symmetric.mixHash(ephemeral.publicKey)
        symmetric.mixKey(ephemeral.publicKey)
        symmetric.mixKey(NoiseDh.agree(ephemeral.privateKey, re))
        symmetric.mixKey(NoiseDh.agree(ephemeral.privateKey, remoteStaticPublic))
        symmetric.mixKeyAndHash(psk)
        val message = ephemeral.publicKey + symmetric.encryptAndHash(payload)
        val (initiatorToResponder, responderToInitiator) = symmetric.split()
        step = Step.DONE
        return Result(
            message = message,
            transport = NoiseTransport(send = responderToInitiator, receive = initiatorToResponder),
            handshakeHash = symmetric.handshakeHash,
        )
    }

    class Result(
        val message: ByteArray,
        val transport: NoiseTransport,
        val handshakeHash: ByteArray,
    )

    private enum class Step { AWAIT_MESSAGE_1, AWAIT_PSK, DONE }

    private companion object {
        const val TAG_SIZE = 16
    }
}
