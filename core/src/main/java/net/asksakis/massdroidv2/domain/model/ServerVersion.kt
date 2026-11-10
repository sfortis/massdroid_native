package net.asksakis.massdroidv2.domain.model

private val SERVER_VERSION = Regex("""^(\d+)\.(\d+)\.(\d+)""")

/**
 * Whether the Music Assistant [serverVersion] reads as [major].[minor].[patch] or later.
 *
 * The parts are compared as numbers, so 2.10.10 is later than 2.10.7, and a suffix such as
 * the `b4` of 2.11.0b4 is ignored. A version that does not start with X.Y.Z counts as older,
 * and so does the 0.0.0 a dev build reports, so a feature is only used where it is known.
 */
fun isServerVersionAtLeast(serverVersion: String?, major: Int, minor: Int, patch: Int = 0): Boolean {
    val match = serverVersion?.let { SERVER_VERSION.find(it) } ?: return false
    val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
    val difference = parts.zip(listOf(major, minor, patch)).firstOrNull { (part, min) -> part != min }
    return difference == null || difference.first > difference.second
}
