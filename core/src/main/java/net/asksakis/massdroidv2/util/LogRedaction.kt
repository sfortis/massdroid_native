package net.asksakis.massdroidv2.util

/**
 * Values that must not appear verbatim in a log the user can share.
 *
 * The Share logs button sends a day of retained history as an attachment for bug
 * reports, so anything identifying in it leaves the device. A Wi-Fi BSSID is the
 * clearest case: it is a precise location, geolocatable from public access-point
 * databases, and it is logged at WARN in the room detector, which ProGuard does
 * not strip from release builds.
 *
 * The point is to keep the diagnostics working, not to blank them. Room detection
 * needs to tell one access point from another across log lines, so a stable short
 * token replaces the address rather than a constant.
 */
object LogRedaction {

    private const val TOKEN_LENGTH = 6

    /**
     * A stable short token for a network identifier (BSSID or SSID). The same
     * input always yields the same token within a process, so log lines can still
     * be correlated, and the token cannot be turned back into the address.
     *
     * Returns "none" for a null or blank input, so a caller does not have to
     * special-case it and no log line reads "null".
     */
    fun networkId(value: String?): String {
        if (value.isNullOrBlank()) return "none"
        val hash = value.hashCode().toLong() and 0xFFFFFFFFL
        return "net-" + hash.toString(RADIX_HEX).padStart(TOKEN_LENGTH, '0').takeLast(TOKEN_LENGTH)
    }

    private const val RADIX_HEX = 16
}
