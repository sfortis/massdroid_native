package net.asksakis.massdroidv2.data.proximity

/**
 * The lowest weight calibration gives an anchor, the floor for one that barely tells
 * rooms apart. Calibration clamps every weight to at least this value.
 */
const val BEACON_WEIGHT_FLOOR = 0.15

/** Slack for the floor comparison, since a stored weight is a double written by arithmetic. */
private const val WEIGHT_FLOOR_SLACK = 0.01

/**
 * The anchors the persistent BLE scan filters on: MAC addresses and advertised names from the
 * rooms detected by Bluetooth. Wi-Fi rooms are left out, because the detector never scores
 * their beacons.
 *
 * An anchor at [BEACON_WEIGHT_FLOOR] in every room that lists it is left out as well. Each
 * filter takes one of the controller's hardware slots (64 on the S25, about 40 of them held
 * by system services when measured), and when the set does not fit, the platform refuses to
 * offload it and filters on the application processor instead. Such an anchor is a faint,
 * shared beacon (measured around -91 to -95 dBm), and the scorer charges a missing anchor
 * `weight * (calibrated + 100)^2`, about 5 for these against thousands for a strong one, so
 * never hearing it costs the decision next to nothing.
 *
 * A room whose anchors are all at the floor keeps them, because without them it could never
 * be heard at all.
 */
data class ScanAnchors(val macs: Set<String>, val names: Set<String>) {
    companion object {
        fun from(config: ProximityConfig): ScanAnchors {
            val bleRooms = config.rooms.filter { it.wifiMatchMode == null }
            val informative = bleRooms
                .flatMap { it.beaconProfiles }
                .filter { it.isAboveFloor() }
                .map { it.key() }
                .toSet()
            val kept = bleRooms.flatMap { room ->
                val roomKeys = room.beaconProfiles.map { it.key() }
                if (roomKeys.none { it in informative }) roomKeys else roomKeys.filter { it in informative }
            }.toSet()
            return ScanAnchors(
                macs = kept.filter { it.type == AnchorType.MAC && !it.id.startsWith("wifi:") }.map { it.id }.toSet(),
                names = kept.filter { it.type == AnchorType.NAME && it.id.isNotBlank() }.map { it.id }.toSet()
            )
        }

        private data class Key(val type: AnchorType, val id: String)

        private fun BeaconProfile.key() = Key(anchorType, if (anchorType == AnchorType.NAME) name else address)

        private fun BeaconProfile.isAboveFloor() = weight > BEACON_WEIGHT_FLOOR + WEIGHT_FLOOR_SLACK
    }
}
