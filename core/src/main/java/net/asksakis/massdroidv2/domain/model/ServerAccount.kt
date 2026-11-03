package net.asksakis.massdroidv2.domain.model

/** The token and username this phone holds for one Music Assistant server. */
data class ServerAccount(val token: String, val username: String) {
    val hasToken: Boolean get() = token.isNotBlank()

    // The token is a secret, so it never reaches a log.
    override fun toString(): String = "ServerAccount(username=$username, hasToken=$hasToken)"
}
