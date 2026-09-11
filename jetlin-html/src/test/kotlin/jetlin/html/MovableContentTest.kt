package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import jetlin.protocol.ClientMessage
import jetlin.protocol.Op
import jetlin.protocol.ServerMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Views that use `movableContentOf`, which used to be able to stop a session for good.
 *
 * The Recomposer publishes its state as a cached value, and two paths involving movable content
 * leave that value at `PendingWork` after the work is done: composing movable content in the first
 * frame, and removing it. A host that waited for `Idle` waited until something unrelated happened.
 * In a live session nothing unrelated need ever happen — the inbound loop handles one event at a
 * time and the sender waits before every patch — so the page stopped responding. The first two tests
 * reproduce exactly those two paths; see `SessionActivity` for the mechanism and the fix.
 *
 * Every wait runs under a timeout, so a regression fails with a message instead of hanging the build.
 */
class MovableContentTest {

    @Test
    fun `movable content in the first frame does not stop a view from starting`(): Unit = runBlocking {
        val view = LiveView { _ ->
            Div {
                val content = remember { movableContentOf { Span { Text("movable") } } }
                content()
            }
        }
        view.use {
            within("starting a view with movable content in its first frame") { it.start() }
            assertTrue("movable" in it.renderHtml())
        }
    }

    @Test
    fun `removing movable content in response to a click completes and sends the removal`(): Unit = runBlocking {
        val items = mutableStateListOf<Int>()
        val view = LiveView { _ -> MovableList(items) { items.remove(2) } }
        view.use {
            // Rows added after the first frame rather than present in it, so that this test reaches
            // the removal path on its own instead of stopping at the first-frame one above.
            within("starting a view with no rows yet") { it.start() }
            Snapshot.withMutableSnapshot { items.addAll(listOf(1, 2, 3)) }
            within("adding movable rows") { it.awaitIdle() }
            it.inspect { owner -> owner.drainOps() } // the additions went out already; only the removal is of interest
            val button = it.inspect { owner -> owner.root.find("button").id }

            // The removal path: the frame derives its final state before the removed content is
            // discarded, which is what used to leave the recomposer reporting work it did not have.
            within("the click that removes a movable row") {
                it.dispatch(ClientMessage.Event(node = button, event = "click", seq = 1))
            }

            // And the session is still sending. The sender waits before every message, so a stuck
            // wait would stop this too — and a page that does not update is the symptom a user sees.
            val patch = within("the patch for the removal") { it.messages.first() }
            val removal = assertIs<Op.Remove>(assertIs<ServerMessage.Patch>(patch).ops.single())
            assertEquals(1, removal.count)
            assertEquals(listOf("row 1", "row 3"), it.inspect { owner -> owner.root.all("span").map { s -> s.text() } })
        }
    }

    @Test
    fun `an effect that writes as the page starts is part of the first render, not a patch after it`(): Unit = runBlocking {
        var label by mutableStateOf("loading")
        val view = LiveView { _ ->
            Div { Text(label) }
            LaunchedEffect(Unit) { label = "loaded" }
        }
        view.use {
            within("starting a view whose effect writes immediately") { it.start() }

            // The page is rendered after start returns, and a socket adopting it keeps every op
            // recorded after start's drain. Had this effect's recomposition landed after the drain it
            // would sit in the buffer while also being in the markup, and adoption would apply it twice.
            assertTrue("loaded" in it.renderHtml(), "the render should include what the effect wrote")
            assertFalse(it.owner.hasPendingOps, "nothing the effect caused should be left to send again")
        }
    }

    private suspend fun <T> within(what: String, block: suspend () -> T): T =
        try {
            withTimeout(10.seconds) { block() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Hung waiting on $what", e)
        }
}

/** A button, then one row per item, each row movable content keyed by its item. */
@Composable
private fun MovableList(items: List<Int>, onRemove: () -> Unit) {
    val rows = remember { HashMap<Int, @Composable () -> Unit>() }
    Div {
        Button({ onClick(onRemove) }) { Text("remove") }
        items.forEach { item ->
            val row = rows.getOrPut(item) { movableContentOf { Span { Text("row $item") } } }
            key(item) { row() }
        }
    }
}

private fun ElementNode.all(tag: String): List<ElementNode> =
    childNodes.filterIsInstance<ElementNode>().flatMap { child -> listOfNotNull(child.takeIf { it.tag == tag }) + child.all(tag) }

private fun ElementNode.find(tag: String): ElementNode = all(tag).single()

private fun ElementNode.text(): String = childNodes.joinToString("") { node ->
    when (node) {
        is TextNode -> node.text
        is ElementNode -> node.text()
    }
}
