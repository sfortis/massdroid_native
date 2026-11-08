package net.asksakis.massdroidv2.playback

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test

/**
 * The resume an interruption owed is kept for a short time after the focus gain, while the
 * output still reads the phone speaker and the car is connected (issue #68), and given up
 * when the output does not come back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SinkReturnWindowTest {

    private val scope = TestScope()
    private var sinkBack = false
    private var resumed = 0
    private var expired = 0
    private val window = SinkReturnWindow(
        scope,
        sinkIsBack = { sinkBack },
        onResume = { resumed++ },
        onExpired = { expired++ },
    )

    private fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    @Test
    fun `the sink returning inside the window resumes once`() {
        window.open()
        advance(400L)

        assertThat(window.onSinkReturned()).isTrue()
        advance(SinkReturnWindow.WINDOW_MS * 2)

        assertThat(resumed).isEqualTo(1)
        assertThat(expired).isEqualTo(0)
    }

    /** A pause pressed while the window waits: the later return of the car starts nothing. */
    @Test
    fun `a pause inside the window closes it without a resume`() {
        window.open()
        advance(400L)

        window.cancel()
        assertThat(window.onSinkReturned()).isFalse()
        advance(SinkReturnWindow.WINDOW_MS * 2)

        assertThat(resumed).isEqualTo(0)
        assertThat(expired).isEqualTo(0)
    }

    /** The phone left the car: the output never comes back, so the resume is given up. */
    @Test
    fun `the window expires when the sink does not return`() {
        window.open()

        advance(SinkReturnWindow.WINDOW_MS - 1L)
        assertThat(expired).isEqualTo(0)

        advance(2L)
        assertThat(expired).isEqualTo(1)
        assertThat(resumed).isEqualTo(0)
        assertThat(window.isOpen).isFalse()
    }

    @Test
    fun `a return after the window expired starts nothing`() {
        window.open()
        advance(SinkReturnWindow.WINDOW_MS + 1L)

        assertThat(window.onSinkReturned()).isFalse()

        assertThat(resumed).isEqualTo(0)
        assertThat(expired).isEqualTo(1)
    }

    /** The output reached the car but no route callback reported it before the deadline. */
    @Test
    fun `a sink found back at the deadline resumes instead of expiring`() {
        window.open()
        sinkBack = true

        advance(SinkReturnWindow.WINDOW_MS + 1L)

        assertThat(resumed).isEqualTo(1)
        assertThat(expired).isEqualTo(0)
    }

    /** A route callback with no gain waiting must not start playback. */
    @Test
    fun `a sink return with no window open starts nothing`() {
        assertThat(window.onSinkReturned()).isFalse()
        assertThat(resumed).isEqualTo(0)
    }
}
