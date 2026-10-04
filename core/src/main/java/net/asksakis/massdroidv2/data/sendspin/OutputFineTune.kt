package net.asksakis.massdroidv2.data.sendspin

/** The kind of output the phone's Sendspin audio plays on. */
enum class OutputKind { SPEAKER, BLUETOOTH, WIRED, USB }

/**
 * The output the phone plays on now, as the timing settings show it.
 *
 * [routeKey] is the key the output's calibration and fine-tune are stored
 * under. It is null only for Bluetooth while several sinks are connected and
 * none carries the Sendspin output: the device cannot be named then, so it has
 * no fine-tune, and a calibration is stored for whichever device the test
 * sound turns out to play on.
 */
data class OutputInfo(
    val routeKey: String?,
    val name: String,
    val kind: OutputKind,
    val canCalibrate: Boolean,
)

/** Storage keys of the outputs that are not named by a device. */
object OutputRouteKeys {
    const val SPEAKER = "speaker"
    const val WIRED = "wired"
    const val USB = "usb"
    const val BLUETOOTH_PREFIX = "bt:"
}

/** A short name for a stored route key, for the list of other calibrated outputs. */
fun outputNameForRouteKey(routeKey: String): String = when {
    routeKey == OutputRouteKeys.SPEAKER -> "Phone speaker"
    routeKey == OutputRouteKeys.WIRED -> "Wired headphones"
    routeKey == OutputRouteKeys.USB -> "USB audio"
    routeKey.startsWith(OutputRouteKeys.BLUETOOTH_PREFIX) ->
        routeKey.removePrefix(OutputRouteKeys.BLUETOOTH_PREFIX).ifBlank { "Bluetooth device" }
    else -> routeKey
}

/** The fine-tune of one output: [ms] stored under [routeKey], positive plays later. */
data class OutputFineTune(val routeKey: String, val ms: Int)
