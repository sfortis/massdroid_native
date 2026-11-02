package net.asksakis.massdroidv2.domain.connection

/** What the sign-in screens know about a server address after asking it who it is. */
sealed interface ServerCheck {
    /** Nothing has been asked yet, or the address is still being typed. */
    data object Idle : ServerCheck

    data object Checking : ServerCheck

    /**
     * Music Assistant answered at [url]. [serverId] is the `server_id` its greeting carried,
     * which names the account saved for it.
     */
    data class Found(val url: String, val version: String, val serverId: String? = null) : ServerCheck

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
    val isHomeAssistantAddon: Boolean = false,
    // A server used before that is not the current one, found on the network or not, which the
    // list can forget. The current server is left by Sign out instead.
    val canForget: Boolean = false
)

/** A server announced on the local network, as the server list needs it. */
data class AnnouncedServer(
    val url: String,
    val name: String,
    val version: String?,
    val isHomeAssistantAddon: Boolean
)

/**
 * [url] as one address: trimmed, without a trailing slash, and without the default port of its
 * scheme, so "https://host:443/" and "https://host" are the same row. Compare results without
 * regard to case.
 */
fun normalizedAddress(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    val match = DEFAULT_PORT.matchEntire(trimmed) ?: return trimmed
    val (scheme, host, port, rest) = match.destructured
    val isDefault = (scheme.equals("https", true) && port == "443") || (scheme.equals("http", true) && port == "80")
    return if (isDefault) "$scheme://$host$rest" else trimmed
}

/** Whether two saved or chosen server URLs are the same address; see [normalizedAddress]. */
fun sameServer(a: String, b: String): Boolean =
    normalizedAddress(a).equals(normalizedAddress(b), ignoreCase = true)

private val DEFAULT_PORT = Regex("""^(https?)://([^/:]+):(\d+)(.*)$""", RegexOption.IGNORE_CASE)

/** The address of [url] without its scheme, which is how the sign-in screens show it. */
fun displayAddressOf(url: String): String = url.substringAfter("://").trimEnd('/')

/**
 * The rows of the server list: the server used last and the other servers signed in to before
 * ([usedBefore], most recent first), then the servers announced on this network. A used
 * server gets a row of its own only when it was not announced, and an announced server that was
 * used says so in its detail line.
 */
fun serverRows(
    savedUrl: String,
    announced: List<AnnouncedServer>,
    usedBefore: List<String> = emptyList()
): List<ServerRow> {
    val saved = normalizedAddress(savedUrl)
    val earlier = usedBefore.map(::normalizedAddress)
        .filter { it.isNotEmpty() && !it.equals(saved, ignoreCase = true) }
        .distinctBy { it.lowercase() }
    fun usedLabel(url: String): String? = when {
        saved.isNotEmpty() && url.equals(saved, ignoreCase = true) -> "Last used"
        earlier.any { it.equals(url, ignoreCase = true) } -> "Used before"
        else -> null
    }
    val foundAddresses = announced.map { normalizedAddress(it.url) }
    val found = announced.mapIndexed { i, server ->
        val address = foundAddresses[i]
        ServerRow(
            url = server.url,
            name = server.name,
            detail = listOfNotNull(
                usedLabel(address),
                displayAddressOf(server.url),
                server.version?.let { "v$it" }
            ).joinToString(" · "),
            group = ServerRowGroup.ON_THIS_NETWORK,
            isHomeAssistantAddon = server.isHomeAssistantAddon,
            canForget = earlier.any { it.equals(address, ignoreCase = true) }
        )
    }
    val used = (listOf(saved).filter { it.isNotEmpty() } + earlier)
        .filter { url -> foundAddresses.none { it.equals(url, ignoreCase = true) } }
        .map { url ->
            ServerRow(
                url = url,
                name = displayAddressOf(url),
                // With one used server the group heading says it all.
                detail = usedLabel(url).takeIf { earlier.isNotEmpty() },
                group = ServerRowGroup.LAST_USED,
                canForget = !url.equals(saved, ignoreCase = true)
            )
        }
    return used + found
}
