package jetlin.html

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import jetlin.protocol.NodeSpec
import jetlin.protocol.Op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Tests that a test tag names an element for tests, and costs the browser nothing.
 *
 * The tag is stored on the node instead of with the attributes, so it's never serialized or sent.
 * Browser tests are the exception, because Playwright can select only what's in the DOM, so a server
 * can be configured to write tags out too. These tests cover both halves.
 */
class TestTagTest {

    @Test
    fun `a tag is on the node but not in the markup`(): Unit = runBlocking {
        LiveView { _ -> Button({ testTag("save") }) { Text("Save") } }.use { view ->
            view.start()

            assertEquals("save", view.inspect { it.root.childNodes.filterIsInstance<ElementNode>().single().testTag })
            val html = view.renderHtml()
            assertFalse(html.contains("data-test"), "the tag must not reach the browser, but got: $html")
            assertFalse(html.contains("save"), html)
        }
    }

    @Test
    fun `a tag is written out when the server exposes them`(): Unit = runBlocking {
        LiveView(exposeTestTags = true) { _ -> Button({ testTag("save") }) { Text("Save") } }.use { view ->
            view.start()

            assertTrue(view.renderHtml().contains("""data-test="save""""), view.renderHtml())
        }
    }

    @Test
    fun `an exposed tag also reaches nodes that arrive after first paint`(): Unit = runBlocking {
        // This case rules out adding the attribute at serialization time. A row inserted later never
        // goes through the HTML serializer. It travels as a NodeSpec.
        var shown by mutableStateOf(false)
        LiveView(exposeTestTags = true) { _ ->
            Div { if (shown) Span({ testTag("late") }) { Text("here") } }
        }.use { view ->
            view.start()
            view.owner.startRecording()

            Snapshot.withMutableSnapshot { shown = true }
            view.awaitIdle()

            val insert = assertIs<Op.Insert>(view.inspect { it.drainOps() }.single())
            val element = assertIs<NodeSpec.Element>(insert.node)
            assertEquals("late", element.attrs["data-test"])
        }
    }

    @Test
    fun `a tag that changes costs nothing when tags are not exposed`(): Unit = runBlocking {
        var name by mutableStateOf("first")
        LiveView { _ -> Span({ testTag(name) }) { Text("x") } }.use { view ->
            view.start()
            view.owner.startRecording()

            Snapshot.withMutableSnapshot { name = "second" }
            view.awaitIdle()

            // The node knows its new name, and the client is told nothing, because it has no use for it.
            assertEquals(emptyList(), view.inspect { it.drainOps() })
            assertEquals(
                "second",
                view.inspect { it.root.childNodes.filterIsInstance<ElementNode>().single().testTag },
            )
        }
    }

    @Test
    fun `a tag that changes is patched like any attribute when exposed`(): Unit = runBlocking {
        var name by mutableStateOf("first")
        LiveView(exposeTestTags = true) { _ -> Span({ testTag(name) }) { Text("x") } }.use { view ->
            view.start()
            view.owner.startRecording()

            Snapshot.withMutableSnapshot { name = "second" }
            view.awaitIdle()

            assertEquals(
                listOf(Op.SetAttr(1, "data-test", "second")),
                view.inspect { it.drainOps() },
            )
        }
    }

    @Test
    fun `an untagged element has no tag`(): Unit = runBlocking {
        LiveView { _ -> Span { Text("x") } }.use { view ->
            view.start()
            assertNull(view.inspect { it.root.childNodes.filterIsInstance<ElementNode>().single().testTag })
        }
    }
}
