package jetlin.html

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.util.Collections
import jetlin.protocol.ServerMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a session costs when nobody is reading it.
 *
 * A composition outlives its socket — that is what lets a reconnecting user find their session where
 * they left it — so a session with a running timer keeps producing updates whether or not anyone is
 * listening. These tests pin down that those updates cannot accumulate without limit.
 */
class BackPressureTest {

    @Test
    fun `edits are not recorded while no client is attached`(): Unit = runBlocking {
        val ticker = Ticker()
        val view = LiveView { _ -> Span { Text("tick ${ticker.count}") } }
        view.use {
            view.start()
            view.clientDetached()

            repeat(50) { ticker.advance(view) }

            assertTrue(
                view.owner.drainOps().isEmpty(),
                "a detached session must not buffer updates nobody will receive",
            )
        }
    }

    @Test
    fun `reattaching resumes recording from a full snapshot`(): Unit = runBlocking {
        val ticker = Ticker()
        val view = LiveView { _ -> Span { Text("tick ${ticker.count}") } }
        view.use {
            view.start()
            view.clientDetached()
            repeat(10) { ticker.advance(view) }

            // A rejoining client is given the tree as it stands, not the edits it missed.
            val reset = view.reset()
            assertTrue(
                reset.children.toString().contains("tick 10"),
                "the snapshot must reflect what happened while detached: $reset",
            )

            ticker.advance(view)
            assertEquals(1, view.owner.drainOps().size, "recording should resume after reset")
        }
    }

    @Test
    fun `an overflowing buffer degrades to a full tree instead of growing`(): Unit = runBlocking {
        val ticker = Ticker()
        val view = LiveView { _ -> Span { Text("tick ${ticker.count}") } }
        view.owner.maxBufferedOps = 8

        val received = Collections.synchronizedList(mutableListOf<ServerMessage>())
        view.use {
            view.start()

            // Nothing is collecting yet, so these pile up in the buffer and trip the ceiling.
            repeat(20) { ticker.advance(view) }
            assertTrue(view.owner.hasOverflowed, "expected the buffer to overflow")

            val collector = launch { view.messages.collect { received += it } }
            try {
                received.awaitAtLeast(1)
                // A Patch here would be a lie: the dropped edits are not recoverable.
                val message = assertIs<ServerMessage.Reset>(received.first())
                assertTrue(message.children.toString().contains("tick 20"), "$message")
            } finally {
                collector.cancel()
            }
        }
    }

    @Test
    fun `recording resumes normally after an overflow`(): Unit = runBlocking {
        val ticker = Ticker()
        val view = LiveView { _ -> Span { Text("tick ${ticker.count}") } }
        view.owner.maxBufferedOps = 8

        val received = Collections.synchronizedList(mutableListOf<ServerMessage>())
        view.use {
            view.start()
            repeat(20) { ticker.advance(view) }

            val collector = launch { view.messages.collect { received += it } }
            try {
                received.awaitAtLeast(1)
                ticker.advance(view)
                received.awaitAtLeast(2)

                assertIs<ServerMessage.Patch>(received[1], "expected incremental updates to resume")
            } finally {
                collector.cancel()
            }
        }
    }
}

/** State the view reads, so advancing it forces exactly one text edit. */
private class Ticker {
    var count: Int by mutableStateOf(0)

    /** Writes from outside the composition, the way a background job or shared store would. */
    suspend fun advance(view: LiveView) {
        Snapshot.withMutableSnapshot { count++ }
        view.awaitIdle()
    }
}

private suspend fun List<ServerMessage>.awaitAtLeast(count: Int) {
    val arrived = withTimeoutOrNull(5_000) {
        while (size < count) delay(10)
        true
    }
    checkNotNull(arrived) { "timed out waiting for $count messages, saw $this" }
}
