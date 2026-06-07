package net.asksakis.massdroidv2.data.proximity

private const val DETECTOR_DISCRIMINATION_THRESHOLD = 5.0
private const val LOCAL_STRONG_RSSI_DBM = -65
private const val LOCAL_GOOD_RSSI_DBM = -72
private const val HIGH_DISCRIMINATION_DB = 20.0
private const val MEDIUM_DISCRIMINATION_DB = 10.0

fun detectorLocalAnchorBoost(profile: BeaconProfile): Double {
    val signalFactor = when {
        profile.meanRssi >= LOCAL_STRONG_RSSI_DBM -> 1.0
        profile.meanRssi >= LOCAL_GOOD_RSSI_DBM -> 0.6
        else -> 0.0
    }
    val separationFactor = when {
        profile.discriminationScore >= HIGH_DISCRIMINATION_DB -> 1.0
        profile.discriminationScore >= MEDIUM_DISCRIMINATION_DB -> 0.6
        else -> 0.0
    }
    val visibilityFactor = profile.visibilityRate.coerceIn(0.3, 1.0)
    return 1.0 + (signalFactor * separationFactor * visibilityFactor)
}

fun detectorEffectiveWeight(profile: BeaconProfile): Double =
    profile.weight * detectorLocalAnchorBoost(profile)

fun isDetectorPrimaryLocalAnchor(profile: BeaconProfile): Boolean =
    profile.meanRssi >= LOCAL_GOOD_RSSI_DBM && profile.discriminationScore >= MEDIUM_DISCRIMINATION_DB

fun detectorPriorityScore(profile: BeaconProfile): Double {
    val localSignalBonus = when {
        profile.meanRssi >= LOCAL_STRONG_RSSI_DBM -> 0.8
        profile.meanRssi >= LOCAL_GOOD_RSSI_DBM -> 0.4
        else -> 0.0
    }
    val positiveDiscrimination = profile.discriminationScore.coerceAtLeast(0.0) / 20.0
    return detectorEffectiveWeight(profile) + positiveDiscrimination + localSignalBonus
}

fun rankBeaconProfilesForDetection(profiles: List<BeaconProfile>): List<BeaconProfile> =
    profiles.sortedWith(
        compareByDescending<BeaconProfile> { detectorPriorityScore(it) }
            .thenByDescending { it.discriminationScore > DETECTOR_DISCRIMINATION_THRESHOLD }
            .thenByDescending { detectorEffectiveWeight(it) }
            .thenByDescending { it.discriminationScore }
    )
