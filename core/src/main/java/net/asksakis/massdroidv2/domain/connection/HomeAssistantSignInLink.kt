package net.asksakis.massdroidv2.domain.connection

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The addresses a "Sign in with Home Assistant" link sends the browser to, other than the one
 * the app is connected to: the Home Assistant login page, and the Music Assistant address the
 * browser returns to (the link's `redirect_uri`).
 *
 * Music Assistant builds both on the server. The return goes to its External URL only when one
 * is set and the sign-in started from it, and otherwise to its own LAN address (MA 2.10.9,
 * `_get_oauth_callback_base_url`). Away from home that address does not load, and the app
 * cannot change it: the server checks the return address when the sign-in finishes.
 */
fun signInOriginsToCheck(authorizationUrl: String, connectedUrl: String): List<String> {
    val link = authorizationUrl.toHttpUrlOrNull() ?: return emptyList()
    val connected = connectedUrl.toHttpUrlOrNull()?.origin()
    val returnTo = link.queryParameter("redirect_uri")?.toHttpUrlOrNull()
    return listOfNotNull(link, returnTo).map { it.origin() }.distinct().filter { it != connected }
}

/** The message when the sign-in link needs an address this phone cannot reach. */
fun unreachableSignInMessage(origin: String): String =
    "Sign in with Home Assistant has to open $origin, which this phone can't reach right now. " +
        "Sign in once at home, and the saved sign-in keeps working away from home. Or set an " +
        "External URL in Music Assistant and Home Assistant that this phone can reach. Or sign in " +
        "with your Music Assistant username and password."

/** The message when the user comes back from a sign-in that needed another address. */
fun unfinishedSignInMessage(origin: String): String =
    "Sign-in did not finish. It opens $origin in the browser, so this phone must reach that " +
        "address. If the page did not load, sign in once at home, or with your Music Assistant " +
        "username and password."

private fun HttpUrl.origin(): String =
    newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
