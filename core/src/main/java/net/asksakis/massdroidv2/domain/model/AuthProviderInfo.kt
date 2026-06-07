package net.asksakis.massdroidv2.domain.model

/**
 * Describes one of the login providers the Music Assistant server exposes
 * via the unauthenticated `auth/providers` command.
 */
data class AuthProviderInfo(
    val providerId: String,
    val providerType: String,
    val requiresRedirect: Boolean
) {
    val isHomeAssistant: Boolean get() = providerType == "homeassistant"
    val isBuiltin: Boolean get() = providerType == "builtin"
}
