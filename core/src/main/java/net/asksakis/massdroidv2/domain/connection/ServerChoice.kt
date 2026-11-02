package net.asksakis.massdroidv2.domain.connection

/** What the sign-in screens know about a server address after asking it who it is. */
sealed interface ServerCheck {
    /** Nothing has been asked yet, or the address is still being typed. */
    data object Idle : ServerCheck

    data object Checking : ServerCheck

    /** Music Assistant answered at [url]. */
    data class Found(val url: String, val version: String) : ServerCheck

    /**
     * The address cannot be used, and [message] says why in words the user can act on.
     * [suggestion] is a Music Assistant address to offer instead, when one answered.
     */
    data class Problem(val message: String, val suggestion: String? = null) : ServerCheck
}

/** The server picked in step 1 of the sign-in, which step 2 signs in to. */
data class ChosenServer(val url: String, val name: String, val check: ServerCheck)

/** Which group of the server list a row belongs to. */
enum class ServerRowGroup { LAST_USED, ON_THIS_NETWORK }

/** One row of the server list in step 1 of the sign-in. */
data class ServerRow(
    val url: String,
    val name: String,
    val detail: String?,
    val group: ServerRowGroup,
    val isHomeAssistantAddon: Boolean = false
)

/** A server announced on the local network, as the server list needs it. */
data class AnnouncedServer(
    val url: String,
    val name: String,
    val version: String?,
    val isHomeAssistantAddon: Boolean
)

/** The address of [url] without its scheme, which is how the sign-in screens show it. */
fun displayAddressOf(url: String): String = url.substringAfter("://").trimEnd('/')

/**
 * The rows of the server list: the server used last, then the servers announced on this
 * network. The last used server gets a row of its own only when it was not announced, and an
 * announced server that was used last says so in its detail line.
 */
fun serverRows(savedUrl: String, announced: List<AnnouncedServer>): List<ServerRow> {
    val saved = savedUrl.trim().trimEnd('/')
    val found = announced.map { server ->
        val wasUsed = saved.isNotEmpty() && server.url.trimEnd('/') == saved
        ServerRow(
            url = server.url,
            name = server.name,
            detail = listOfNotNull(
                "Last used".takeIf { wasUsed },
                displayAddressOf(server.url),
                server.version?.let { "v$it" }
            ).joinToString(" · "),
            group = ServerRowGroup.ON_THIS_NETWORK,
            isHomeAssistantAddon = server.isHomeAssistantAddon
        )
    }
    val lastUsed = if (saved.isNotEmpty() && found.none { it.url.trimEnd('/') == saved }) {
        ServerRow(url = saved, name = displayAddressOf(saved), detail = null, group = ServerRowGroup.LAST_USED)
    } else {
        null
    }
    return listOfNotNull(lastUsed) + found
}
