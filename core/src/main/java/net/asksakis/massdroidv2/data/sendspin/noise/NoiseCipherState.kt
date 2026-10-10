package net.asksakis.massdroidv2.data.sendspin.noise

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Noise CipherState for the AESGCM cipher: a key and a 64-bit message counter used as the nonce.
 *
 * The nonce is four zero bytes followed by the counter in big-endian order, as the Noise
 * specification defines it for AES-GCM. A state without a key passes data through unchanged,
 * which the handshake relies on before the first key is mixed in.
 *
 * One instance serves one direction and is not thread-safe; the transport serialises its use.
 */
internal class NoiseCipherState(key: ByteArray?) {
    private val keySpec: SecretKeySpec? = key?.let {
        require(it.size == SendspinNoise.KEY_SIZE) { "cipher key must be 32 bytes" }
        SecretKeySpec(it, "AES")
    }
    private val cipher: Cipher? = keySpec?.let { Cipher.getInstance(TRANSFORMATION) }
    private var nonce = 0L

    val hasKey: Boolean get() = keySpec != null

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = cipher ?: return plaintext
        c.init(Cipher.ENCRYPT_MODE, keySpec, nextNonceSpec())
        if (ad.isNotEmpty()) c.updateAAD(ad)
        val out = c.doFinal(plaintext)
        nonce++
        return out
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val c = cipher ?: return ciphertext
        val out = try {
            c.init(Cipher.DECRYPT_MODE, keySpec, nextNonceSpec())
            if (ad.isNotEmpty()) c.updateAAD(ad)
            c.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            throw NoiseProtocolException("AEAD authentication failed", e)
        }
        nonce++
        return out
    }

    private fun nextNonceSpec(): GCMParameterSpec {
        // 2^64 - 1 is reserved by Noise; a Long counter never gets near it in practice.
        check(nonce != Long.MAX_VALUE) { "Noise nonce exhausted" }
        val iv = ByteBuffer.allocate(NONCE_SIZE).putInt(0).putLong(nonce).array()
        return GCMParameterSpec(TAG_BITS, iv)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_SIZE = 12
        const val TAG_BITS = 128
    }
}
