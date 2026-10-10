package net.asksakis.massdroidv2.data.sendspin.noise

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom

/**
 * The app's Sendspin Noise identity and its pairings, kept in one small JSON file.
 *
 * The identity is a single X25519 key pair for the whole app. Its public key is the player id on
 * every MA server that speaks Noise (2.10.0 and later). Each completed pairing adds a long-term PSK
 * bound to that server's id.
 *
 * The file belongs in `noBackupFilesDir`, so Android backup and device transfer never copy it. A
 * restored private key would make two phones claim the same player, and a long-term PSK is of no
 * use on a phone the server never paired with.
 *
 * The file is read on first use and rewritten atomically on every change. All methods are
 * synchronized. The first call can touch the disk, so it belongs on a background thread.
 */
class SendspinIdentityStore(
    private val file: File,
    private val random: SecureRandom = SecureRandom(),
) : PskResolver {

    private class State(val identity: NoiseKeyPair, val longTermPsks: Map<String, ByteArray>)

    private var state: State? = null

    /**
     * The identity, created and saved on first use.
     *
     * @throws IOException when a new identity cannot be saved. Using an unsaved one would give the
     *   phone a different player id on the next start.
     */
    @Synchronized
    fun identity(): NoiseKeyPair = current().identity

    @Synchronized
    fun hasLongTermPsk(serverId: String): Boolean = serverId in current().longTermPsks

    @Synchronized
    fun saveLongTermPsk(serverId: String, psk: ByteArray) {
        require(psk.size == SendspinNoise.PSK_SIZE) { "PSK must be ${SendspinNoise.PSK_SIZE} bytes" }
        val old = current()
        replace(State(old.identity, old.longTermPsks + (serverId to psk.copyOf())))
    }

    @Synchronized
    fun removeLongTermPsk(serverId: String) {
        val old = current()
        if (serverId !in old.longTermPsks) return
        replace(State(old.identity, old.longTermPsks - serverId))
    }

    @Synchronized
    override fun resolve(pskId: String): ResolvedPsk? =
        current().longTermPsks.entries
            .firstOrNull { (_, psk) -> SendspinNoise.pskId(psk) == pskId }
            ?.let { (serverId, psk) -> ResolvedPsk(pskId, psk.copyOf(), PskCategory.LONG_TERM, serverId) }

    private fun current(): State = state ?: load().also { state = it }

    private fun replace(next: State) {
        write(next)
        state = next
    }

    private fun load(): State {
        if (file.exists()) {
            // A read error propagates: the file may be fine, and replacing it would lose the identity.
            val text = file.readText()
            val decoded = runCatching { decode(text) }
            decoded.getOrNull()?.let { return it }
            // Malformed content. Keep the bad file for inspection; it cannot be used.
            val aside = File(file.parentFile, "${file.name}.corrupt")
            Log.e(TAG, "Unreadable Sendspin identity, moved to ${aside.name}; creating a new one", decoded.exceptionOrNull())
            file.renameTo(aside)
        }
        val fresh = State(NoiseKeyPair.generate(random), emptyMap())
        write(fresh)
        Log.i(TAG, "Created Sendspin identity ${fresh.identity.peerId}")
        return fresh
    }

    private fun write(state: State) {
        val text = buildJsonObject {
            put("version", FORMAT_VERSION)
            put("private_key", SendspinNoise.b64urlEncode(state.identity.privateKey))
            putJsonObject("long_term_psks") {
                state.longTermPsks.forEach { (serverId, psk) -> put(serverId, SendspinNoise.b64urlEncode(psk)) }
            }
        }.toString()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("cannot replace ${file.name}")
    }

    private fun decode(text: String): State {
        val obj = Json.parseToJsonElement(text).jsonObject
        val version = obj.getValue("version").jsonPrimitive.int
        require(version == FORMAT_VERSION) { "unknown identity file version $version" }
        val identity = NoiseKeyPair.fromPrivateKey(
            SendspinNoise.b64urlDecode(obj.getValue("private_key").jsonPrimitive.content)
        )
        val psks = obj["long_term_psks"]?.jsonObject.orEmpty().mapValues { (_, value) ->
            SendspinNoise.b64urlDecode(value.jsonPrimitive.content).also {
                require(it.size == SendspinNoise.PSK_SIZE) { "stored PSK has ${it.size} bytes" }
            }
        }
        return State(identity, psks)
    }

    private companion object {
        const val TAG = "SendspinIdentity"
        const val FORMAT_VERSION = 1
    }
}
