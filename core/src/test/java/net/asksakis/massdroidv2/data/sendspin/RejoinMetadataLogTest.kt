package net.asksakis.massdroidv2.data.sendspin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins which server reports a rejoin is checked with: those of the connection that carried the
 * stream, against the latest one of the connection that carries the new stream/start. The
 * boundary is the new connection's session, in order with the reports.
 */
class RejoinMetadataLogTest {

    private fun report(title: String) = ServerMetadataPayload(title = title)

    @Test
    fun `a start without a new connection carries no evidence`() {
        val log = RejoinMetadataLog()
        log.onMetadata(report("a"))
        assertThat(log.takeAtStreamStart()).isNull()
    }

    @Test
    fun `a new connection mid-stream takes the earlier reports as before and the newest later one as after`() {
        val log = RejoinMetadataLog()
        log.onMetadata(report("a"))
        log.onMetadata(report("b"))
        log.onNewConnection(streamPlaying = true)
        log.onMetadata(report("c"))
        log.onMetadata(report("d"))

        val evidence = log.takeAtStreamStart()

        assertThat(evidence?.before).containsExactly(report("a"), report("b")).inOrder()
        assertThat(evidence?.after).isEqualTo(report("d"))
    }

    @Test
    fun `a report the old connection sent before it was lost counts as before, not after`() {
        // The loss can be seen before the old connection's last report is handled; the boundary
        // is the new session, so that report still belongs to before.
        val log = RejoinMetadataLog()
        log.onMetadata(report("a"))
        log.onMetadata(report("old, handled late"))
        log.onNewConnection(streamPlaying = true)

        val evidence = log.takeAtStreamStart()

        assertThat(evidence?.before).containsExactly(report("a"), report("old, handled late")).inOrder()
        assertThat(evidence?.after).isNull()
    }

    @Test
    fun `a new connection with no stream playing starts over`() {
        val log = RejoinMetadataLog()
        log.onMetadata(report("a"))
        log.onNewConnection(streamPlaying = false)
        log.onMetadata(report("b"))
        assertThat(log.takeAtStreamStart()).isNull()
        log.onNewConnection(streamPlaying = true)
        assertThat(log.takeAtStreamStart()?.before).containsExactly(report("b"))
    }

    @Test
    fun `a second connection before the rejoin keeps the reports from before the first`() {
        val log = RejoinMetadataLog()
        log.onMetadata(report("a"))
        log.onNewConnection(streamPlaying = true)
        // A connection that came up, reported and dropped again without a stream/start.
        log.onMetadata(report("between"))
        log.onNewConnection(streamPlaying = true)
        log.onMetadata(report("after"))

        val evidence = log.takeAtStreamStart()

        assertThat(evidence?.before).containsExactly(report("a"))
        assertThat(evidence?.after).isEqualTo(report("after"))
    }

    @Test
    fun `a report from a connection that dropped again does not answer for the next one`() {
        val log = RejoinMetadataLog()
        log.onMetadata(report("a"))
        log.onNewConnection(streamPlaying = true)
        log.onMetadata(report("between"))
        log.onNewConnection(streamPlaying = true)

        assertThat(log.takeAtStreamStart()?.after).isNull()
    }

    @Test
    fun `the evidence is taken once and the log follows the new connection after it`() {
        val log = RejoinMetadataLog()
        log.onMetadata(report("a"))
        log.onNewConnection(streamPlaying = true)
        log.onMetadata(report("b"))
        log.takeAtStreamStart()

        assertThat(log.takeAtStreamStart()).isNull()
        log.onNewConnection(streamPlaying = true)
        assertThat(log.takeAtStreamStart()?.before).containsExactly(report("b"))
    }

    @Test
    fun `only the last few live reports are kept`() {
        val log = RejoinMetadataLog()
        (1..6).forEach { log.onMetadata(report("r$it")) }
        log.onNewConnection(streamPlaying = true)
        assertThat(log.takeAtStreamStart()?.before?.map { it.title }).containsExactly("r3", "r4", "r5", "r6").inOrder()
    }

    @Test
    fun `clear forgets a pending rejoin`() {
        val log = RejoinMetadataLog()
        log.onMetadata(report("a"))
        log.onNewConnection(streamPlaying = true)
        log.clear()
        assertThat(log.takeAtStreamStart()).isNull()
    }
}
