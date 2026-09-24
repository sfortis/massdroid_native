package net.asksakis.massdroidv2.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import net.asksakis.massdroidv2.data.repository.queue.QueueItemsCoordinator
import net.asksakis.massdroidv2.domain.model.QueueItem
import net.asksakis.massdroidv2.domain.model.Track
import net.asksakis.massdroidv2.domain.repository.MusicRepository
import org.junit.Test

/**
 * Reading a queue that is longer than one page.
 *
 * The blocked-artist cleanup reads the queue to decide what to delete. It used to read the
 * shared snapshot, which holds one page of 500, so a blocked track past that point stayed in
 * the queue and would eventually play. That became reachable when a playlist started going to
 * the server as a container, with its blocked tracks still in it, instead of being expanded
 * with them already removed. Four playlists in the reporting library hold 500 tracks each.
 */
class QueuePagedReadTest {

    private val queueId = "q"

    private fun item(index: Int) = QueueItem(
        queueItemId = "item-$index",
        track = Track(itemId = "$index", provider = "library", name = "t$index", uri = "library://track/$index")
    )

    /** A queue of [total] items, served in pages of at most `limit` from `offset`. */
    private fun coordinatorOver(total: Int): QueueItemsCoordinator {
        val music = mockk<MusicRepository>()
        coEvery { music.getQueueItems(queueId, any(), any()) } answers {
            val limit = secondArg<Int>()
            val offset = thirdArg<Int>()
            (offset until minOf(offset + limit, total)).map { item(it) }
        }
        return QueueItemsCoordinator(dagger.Lazy { music })
    }

    @Test
    fun `a queue that fits in one page is read in one go`() = runTest {
        val items = coordinatorOver(total = 120).allItems(queueId)

        assertThat(items).hasSize(120)
    }

    @Test
    fun `a queue longer than one page is read to the end`() = runTest {
        // 1 200 items is three pages. Reading only the first would hide every blocked track
        // from item 500 onwards, which is the defect this covers.
        val items = coordinatorOver(total = 1_200).allItems(queueId)

        assertThat(items).hasSize(1_200)
        assertThat(items.last().queueItemId).isEqualTo("item-1199")
    }

    @Test
    fun `a queue of exactly one page does not lose its last item to the page boundary`() = runTest {
        val items = coordinatorOver(total = 500).allItems(queueId)

        assertThat(items).hasSize(500)
    }

    @Test
    fun `an empty queue reads as empty rather than looping`() = runTest {
        assertThat(coordinatorOver(total = 0).allItems(queueId)).isEmpty()
    }

    @Test
    fun `a known total stops the read without an empty round trip`() = runTest {
        // A queue of exactly one page used to cost a second call that came back empty. Several
        // playlists here hold the 500 tracks the server caps a listing at, so it was routine.
        var calls = 0
        val music = mockk<MusicRepository>()
        coEvery { music.getQueueItems(queueId, any(), any()) } answers {
            calls++
            val limit = secondArg<Int>()
            val offset = thirdArg<Int>()
            (offset until minOf(offset + limit, 500)).map { item(it) }
        }
        val coordinator = QueueItemsCoordinator(dagger.Lazy { music })

        val items = coordinator.allItems(queueId, total = 500)

        assertThat(items).hasSize(500)
        assertThat(calls).isEqualTo(1)
    }
}
