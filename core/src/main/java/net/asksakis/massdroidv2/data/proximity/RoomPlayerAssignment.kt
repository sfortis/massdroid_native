package net.asksakis.massdroidv2.data.proximity

/**
 * Point the room with [roomId] at the player named by [playerId] and [playerName], leaving every
 * other room as it was.
 *
 * Rooms are independent entries and the room setup screen already allows two of them to name the
 * same player, so an assignment here does not take the player out of a room it was in before. A
 * [roomId] that is not in the config yields the same config, which is what an assignment racing a
 * room deletion looks like.
 */
fun ProximityConfig.withRoomPlayer(
    roomId: String,
    playerId: String,
    playerName: String
): ProximityConfig = copy(
    rooms = rooms.map { room ->
        if (room.id == roomId) room.copy(playerId = playerId, playerName = playerName) else room
    }
)
