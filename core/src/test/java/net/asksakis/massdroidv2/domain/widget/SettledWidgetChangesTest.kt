package net.asksakis.massdroidv2.domain.widget

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The widget publisher's pacing. Each draw opens a Glance session of at least 45 s, so a burst of
 * changes has to cost one draw, and the selected player's constant updates must not hold a real
 * change back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettledWidgetChangesTest {

    /** [card] is what the widget would show; [tick] stands for elapsed time, volume and the like. */
    private data class Input(val card: String, val tick: Int = 0)

    private suspend fun drawn(vararg steps: Pair<Long, Input>, into: MutableList<Pair<Long, Input>>, now: () -> Long) {
        flow {
            steps.forEach { (wait, input) ->
                delay(wait)
                emit(input)
            }
            // The real inputs never complete, and a completing flow would hand its last value
            // through the debounce at once instead of after the wait.
            delay(STAY_OPEN_MS)
        }.settledWidgetChanges(shown = Input::card)
            .onEach { into += now() to it }
            .collect()
    }

    @Test
    fun `a burst of play and pause flips is drawn once, as its last state`() = runTest {
        val drawn = mutableListOf<Pair<Long, Input>>()
        drawn(
            0L to Input("playing"),
            500L to Input("paused"),
            500L to Input("playing"),
            500L to Input("paused"),
            into = drawn,
            now = { currentTime },
        )
        assertThat(drawn).containsExactly(1_500L + WIDGET_SETTLE_MS to Input("paused"))
    }

    @Test
    fun `updates that show the same card do not hold a change back`() = runTest {
        val drawn = mutableListOf<Pair<Long, Input>>()
        val ticks = (1..10).map { 500L to Input("track B", tick = it) }.toTypedArray()
        drawn(
            0L to Input("track B"),
            *ticks,
            into = drawn,
            now = { currentTime },
        )
        assertThat(drawn).containsExactly(WIDGET_SETTLE_MS to Input("track B"))
    }

    @Test
    fun `changes further apart than the wait are each drawn`() = runTest {
        val drawn = mutableListOf<Pair<Long, Input>>()
        drawn(
            0L to Input("track A"),
            5_000L to Input("track B"),
            into = drawn,
            now = { currentTime },
        )
        assertThat(drawn).containsExactly(
            WIDGET_SETTLE_MS to Input("track A"),
            5_000L + WIDGET_SETTLE_MS to Input("track B"),
        ).inOrder()
    }

    private companion object {
        const val STAY_OPEN_MS = 60_000L
    }
}
