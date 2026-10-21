package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.recommendation.trackIdentityKey
import org.junit.Test

/**
 * After a reconnect a queue keeps its mix mode only while it still plays the list the app sent.
 *
 * The match has to accept another copy of a sent track, because the server may play a different
 * version of a requested uri, and has to refuse anything else, because another client may have
 * replaced the queue while the app was away.
 */
class GeneratedQueueContentTest {

    private val content = GeneratedQueueContent.of(
        listOf(
            Track(itemId = "1", provider = "deezer", name = "The Curse", uri = "deezer://track/1", artistNames = "Diary of Dreams"),
            Track(itemId = "2", provider = "deezer", name = "Sleep", uri = "deezer://track/2", artistNames = "Second Still, Guest"),
        )
    )

    @Test
    fun `a track the app sent is recognised by its uri`() {
        assertThat(content.holds("deezer://track/2", "")).isTrue()
    }

    @Test
    fun `another copy of a sent track is recognised by its recording`() {
        assertThat(content.holds("library://track/77", trackIdentityKey("Diary of Dreams", "The Curse"))).isTrue()
    }

    @Test
    fun `a track the app did not send means the queue was replaced`() {
        assertThat(content.holds("library://track/9", trackIdentityKey("Someone Else", "Own Album Track"))).isFalse()
    }

    @Test
    fun `a track with no identity is judged on its uri alone`() {
        assertThat(content.holds("library://track/9", "")).isFalse()
    }
}
