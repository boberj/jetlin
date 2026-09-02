package jetlin.html

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import jetlin.protocol.COMPONENT_EVENT
import jetlin.protocol.ClientMessage
import jetlin.protocol.EventPayload
import jetlin.protocol.Op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * An element the composition creates and then stops owning.
 *
 * The contract is props down, events up, and a disposable DOM in between. What is pinned here is
 * the server's half of it: that the element goes out empty and named, that changed props travel as
 * one ordinary attribute write, and that a push comes back as an application-level event.
 */
class ClientComponentTest {

    @Test
    fun `renders an empty element naming its implementation and props`(): Unit = runBlocking {
        LiveView { _ ->
            ClientComponent("editor", buildJsonObject { put("content", "hello") })
        }.use { view ->
            view.start()

            assertEquals(
                """<div data-jl="1" data-jl-component="editor" """ +
                    """data-jl-props="{&quot;content&quot;:&quot;hello&quot;}"></div>""",
                view.renderHtml(),
            )
        }
    }

    @Test
    fun `has no children for the composition to patch`(): Unit = runBlocking {
        LiveView { _ -> ClientComponent("chart") }.use { view ->
            view.start()

            val node = view.inspect { it.root.childNodes.single() as ElementNode }
            // Whatever the implementation renders inside is its own; Jetlin never indexes it, so it
            // can never try to insert a sibling at an index that means nothing.
            assertEquals(emptyList(), node.childNodes)
        }
    }

    @Test
    fun `new props travel as one attribute write`(): Unit = runBlocking {
        var series by mutableStateOf(1)
        LiveView { _ ->
            ClientComponent("chart", buildJsonObject { put("series", series) })
        }.use { view ->
            view.start()
            view.owner.startRecording()

            Snapshot.withMutableSnapshot { series = 2 }
            view.awaitIdle()

            // No new protocol concept: props are an attribute, so they are diffed and patched like
            // any other, and reach nodes that arrive after first paint for free.
            assertEquals(
                listOf(Op.SetAttr(1, "data-jl-props", """{"series":2}""")),
                view.inspect { it.drainOps() },
            )
        }
    }

    @Test
    fun `a push arrives as an application event`(): Unit = runBlocking {
        var seen: Pair<String, String>? = null
        LiveView { _ ->
            ClientComponent(
                name = "editor",
                onEvent = { event, payload ->
                    seen = event to payload["html"]!!.jsonPrimitive.content
                },
            )
        }.use { view ->
            view.start()

            view.dispatch(
                ClientMessage.Event(
                    node = 1,
                    event = COMPONENT_EVENT,
                    seq = 1,
                    payload = EventPayload(
                        data = buildJsonObject {
                            put("event", "changed")
                            put("payload", buildJsonObject { put("html", "<p>typed</p>") })
                        },
                    ),
                ),
            )

            assertEquals("changed" to "<p>typed</p>", seen)
        }
    }

    @Test
    fun `the component event is not a DOM listener`(): Unit = runBlocking {
        LiveView { _ -> ClientComponent("editor") }.use { view ->
            view.start()

            // A push is the implementation calling into the runtime, not a DOM event, so there is
            // nothing for the browser to listen for and no listener spec to send it.
            val node = view.inspect { it.root.childNodes.single() as ElementNode }
            assertEquals(emptySet(), node.eventNames)
            assertTrue(view.renderHtml().let { !it.contains("data-jl-on") }, view.renderHtml())
        }
    }

    @Test
    fun `a push naming nothing is ignored rather than throwing`(): Unit = runBlocking {
        var calls = 0
        LiveView { _ -> ClientComponent("editor", onEvent = { _, _ -> calls++ }) }.use { view ->
            view.start()

            // Malformed pushes come from the browser, which is not trusted to be well behaved.
            view.dispatch(ClientMessage.Event(1, COMPONENT_EVENT, 1, EventPayload()))
            view.dispatch(ClientMessage.Event(1, COMPONENT_EVENT, 2, EventPayload(data = JsonObject(emptyMap()))))

            assertEquals(0, calls)
        }
    }

    @Test
    fun `attributes and a test tag can still be declared`(): Unit = runBlocking {
        LiveView { _ ->
            ClientComponent("chart", tag = "section", attrs = { classes("panel"); testTag("chart") })
        }.use { view ->
            view.start()

            val node = view.inspect { it.root.childNodes.single() as ElementNode }
            assertEquals("section", node.tag)
            assertEquals("panel", node.attribute("class"))
            assertEquals("chart", node.testTag)
        }
    }
}
