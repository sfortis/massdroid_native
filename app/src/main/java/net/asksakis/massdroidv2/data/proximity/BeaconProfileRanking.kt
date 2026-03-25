package net.asksakis.massdroidv2.data.proximity

private const val DETECTOR_DISCRIMINATION_PRIORITY = 5.0

fun rankBeaconProfilesForDetection(profiles: List<BeaconProfile>): List<BeaconProfile> =
    profiles.sortedWith(
        compareByDescending<BeaconProfile> { it.discriminationScore > DETECTOR_DISCRIMINATION_PRIORITY }
            .thenByDescending { it.weight }
            .thenByDescending { it.discriminationScore }
    )
