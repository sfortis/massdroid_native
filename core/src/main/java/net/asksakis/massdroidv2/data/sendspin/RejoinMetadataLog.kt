package net.asksakis.massdroidv2.data.sendspin

/**
 * The server metadata a rejoin is checked against; see [RejoinSplicePolicy.serverStateContinues].
 *
 * It keeps the last few reports of the live connection. The boundary between connections is the
 * Noise session coming up, which reaches the manager in the same ordered message stream as the
 * reports: everything the old connection sent is handled before it, and everything after it
 * comes from the new connection. The socket loss itself is seen on another collector, which can
 * run before reports of the old connection still waiting in the stream, so it does not decide
 * which reports belong to which side.
 *
 * When a connection comes up while a stream is playing, the reports of the connection that
 * carried the stream become the reports from before the drop, and from then on only the latest
 * report counts. A connection that comes up and drops again before its stream/start keeps the
 * reports from before the first drop and forgets its own: only the connection that carries the
 * stream/start may answer for it. The stream/start takes both, once.
 *
 * Every call comes from the manager's message collector; the lock only keeps the class safe on
 * its own.
 */
internal class RejoinMetadataLog {

    /** What a rejoin's stream/start is checked with. */
    data class Evidence(val before: List<ServerMetadataPayload>, val after: ServerMetadataPayload?)

    private val live = ArrayDeque<ServerMetadataPayload>()
    private var beforeLoss: List<ServerMetadataPayload>? = null
    private var afterLoss: ServerMetadataPayload? = null

    @Synchronized
    fun onMetadata(metadata: ServerMetadataPayload) {
        if (beforeLoss != null) {
            afterLoss = metadata
            return
        }
        live.addLast(metadata)
        while (live.size > KEPT_REPORTS) live.removeFirst()
    }

    /**
     * A new connection's session came up. With a stream playing, the earlier connection was lost
     * or replaced in the middle of it, and what follows is a rejoin; without one there is nothing
     * to rejoin and the log starts over.
     */
    @Synchronized
    fun onNewConnection(streamPlaying: Boolean) {
        if (!streamPlaying) {
            clear()
            return
        }
        if (beforeLoss == null) beforeLoss = live.toList()
        afterLoss = null
    }

    /**
     * Called at every stream/start. Returns the evidence when this start follows a new connection
     * in the middle of a stream, else null. Either way the log goes back to following the live
     * connection.
     */
    @Synchronized
    fun takeAtStreamStart(): Evidence? {
        val before = beforeLoss ?: return null
        val evidence = Evidence(before, afterLoss)
        beforeLoss = null
        live.clear()
        afterLoss?.let(live::addLast)
        afterLoss = null
        return evidence
    }

    /** Forgets everything, for a manager that stops or a stream that ended without a rejoin. */
    @Synchronized
    fun clear() {
        live.clear()
        beforeLoss = null
        afterLoss = null
    }

    private companion object {
        // Enough for the current track's report and the next track's, sent ahead of time.
        const val KEPT_REPORTS = 4
    }
}
