package net.asksakis.massdroidv2.data.sendspin

import net.asksakis.massdroidv2.data.sendspin.noise.PskCategory

/**
 * What a `server/activate` asks of the client on the Noise transport, following aiosendspin's
 * `_handle_server_activate` and `_apply_activation`.
 *
 * The player role is active while `active_roles` names one. That set is sticky: a message without
 * it keeps the previous one. The `playback` activity only says whether the group is playing right
 * now, so it starts nothing by itself; a paired phone that is idle gets `activities: []` together
 * with its roles and must still answer volume commands and stream starts.
 */
sealed class NoiseActivationStep {
    /** Send `client/pair-finalize` with a new long-term PSK. */
    data object FinalizePairing : NoiseActivationStep()

    /** Refuse a pairing the client did not offer or cannot run on this PSK. */
    data class AbortPairing(val reason: String) : NoiseActivationStep()

    /** The player role became active: start state reports, the heartbeat and the clock sync. */
    data object StartPlayer : NoiseActivationStep()

    /** The player was already active; report the full state again (after a re-handshake). */
    data object ResendState : NoiseActivationStep()

    /** The server withdrew the player role. */
    data object StopPlayer : NoiseActivationStep()

    /** Nothing to do: the player stays as it was. Unpaired and not approved, it waits for pairing. */
    data object None : NoiseActivationStep()

    /**
     * The server asked for more than this PSK admits (a player on the pairing PSK, pairing mixed
     * with other activities). The client says goodbye with [reason] and starts nothing.
     */
    data class Reject(val reason: String) : NoiseActivationStep()

    companion object {
        /**
         * @param effectiveRoles the active roles after applying this message's sticky set.
         * @param playerActive whether the player role was active before this message.
         * @param resync whether a re-handshake preceded this message.
         */
        fun decide(
            payload: ServerActivatePayload,
            pskCategory: PskCategory,
            effectiveRoles: List<String>,
            playerActive: Boolean,
            resync: Boolean,
        ): NoiseActivationStep {
            val activities = payload.activities.toSet()
            if (!admissible(pskCategory, activities, hasRoles = effectiveRoles.isNotEmpty())) {
                return Reject(GOODBYE_REASON_UNAUTHORIZED)
            }
            if (ACTIVITY_PAIRING in activities) {
                // A pairing PSK admits only pairing_psk; the PIN methods are not offered.
                val runnable = payload.pairing?.method == PAIR_METHOD_PAIRING_PSK &&
                    pskCategory == PskCategory.PAIRING
                return if (runnable) FinalizePairing else AbortPairing(PAIR_ABORT_METHOD_NOT_SUPPORTED)
            }
            val playerRole = hasPlayerRole(effectiveRoles)
            return when {
                playerRole && !playerActive -> StartPlayer
                playerRole && resync -> ResendState
                !playerRole && playerActive -> StopPlayer
                else -> None
            }
        }

        /**
         * aiosendspin's `_admissible`: the activities, and any active role, must be ones the
         * matched PSK allows. The phone always advertises unpaired access, so a sentinel
         * connection may carry playback.
         */
        private fun admissible(category: PskCategory, activities: Set<String>, hasRoles: Boolean): Boolean =
            activitiesAllowed(category, activities) &&
                (!hasRoles || activitiesAllowed(category, activities + ACTIVITY_PLAYBACK))

        private fun activitiesAllowed(category: PskCategory, activities: Set<String>): Boolean = when {
            ACTIVITY_PAIRING in activities -> activities == setOf(ACTIVITY_PAIRING)
            category == PskCategory.LONG_TERM -> activities.all { it == ACTIVITY_PLAYBACK || it == ACTIVITY_MANAGEMENT }
            category == PskCategory.SENTINEL -> activities.isEmpty() || activities == setOf(ACTIVITY_PLAYBACK)
            // The pairing PSK admits only the pairing activity, handled above.
            else -> false
        }

        /** Whether [roles] (as `family@version`) include a player role. */
        fun hasPlayerRole(roles: List<String>): Boolean = roles.any { it.substringBefore('@') == PLAYER_ROLE_FAMILY }

        private const val PLAYER_ROLE_FAMILY = "player"
    }
}
