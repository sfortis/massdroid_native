package net.asksakis.massdroidv2.data.websocket

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.asksakis.massdroidv2.domain.repository.CurrentUser

/**
 * Decodes the relevant claims from a Music Assistant JWT.
 *
 * Tokens look like `header.payload.signature` where each segment is base64url.
 * We only need a couple of fields out of the payload (no signature
 * verification — the server enforces validity).
 */
object MaTokenInfo {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(token: String): CurrentUser? {
        if (token.isBlank()) return null
        val parts = token.split('.')
        if (parts.size < 2) return null
        val payloadJson = runCatching {
            val padded = parts[1].padToBase64()
            val bytes = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return null

        val username = payloadJson["username"]?.jsonPrimitive?.contentOrNull
            ?: payloadJson["sub"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val tokenName = payloadJson["token_name"]?.jsonPrimitive?.contentOrNull.orEmpty()

        val authMethod = when {
            tokenName.contains("homeassistant", ignoreCase = true) -> "Home Assistant"
            tokenName.contains("oauth", ignoreCase = true) -> "OAuth"
            tokenName.isBlank() -> null
            else -> tokenName
        }
        return CurrentUser(username = username, authMethod = authMethod)
    }

    private fun String.padToBase64(): String {
        val rem = length % 4
        return if (rem == 0) this else this + "=".repeat(4 - rem)
    }
}
