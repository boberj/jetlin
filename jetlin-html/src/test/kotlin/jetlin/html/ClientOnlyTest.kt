package jetlin.html

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import jetlin.protocol.ClientCommand
import jetlin.protocol.ClientTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Tests behavior that the browser is trusted to perform on its own.
 *
 * The contract is deliberately narrow: a fixed set of commands, declared on the element, carried in
 * the listener spec the client already receives. These tests check that the declaration arrives
 * intact, and, the part that saves the round trip, that an element with commands and no handler
 * tells the client not to report the event.
 */
class ClientOnlyTest {

    @Test
    fun `commands travel in the listener spec and the server is not told`(): Unit = runBlocking {
        LiveView { _ ->
            Button({ clientOnly { toggleClass("open", on = closest("card")) } }) { Text("Details") }
        }.use { view ->
            view.start()

            val spec = view.inspect {
                (it.root.childNodes.single() as ElementNode).listenerSpec("click")
            }
            assertEquals(
                listOf(ClientCommand.ToggleClass("open", ClientTarget.Closest("card"))),
                spec?.commands,
            )
            // This is the point: nothing declared a handler, so the event never leaves the browser.
            assertFalse(spec!!.notify)
        }
    }

    @Test
    fun `an ordinary handler still asks to be told`(): Unit = runBlocking {
        LiveView { _ -> Button({ onClick { } }) { Text("Save") } }.use { view ->
            view.start()

            val spec = view.inspect {
                (it.root.childNodes.single() as ElementNode).listenerSpec("click")
            }
            assertTrue(spec!!.notify)
            assertEquals(emptyList(), spec.commands)
        }
    }

    @Test
    fun `an element can act at once and still report`(): Unit = runBlocking {
        LiveView { _ ->
            Button({
                clientOnly { addClass("busy") }
                onClick { }
            }) { Text("Save") }
        }.use { view ->
            view.start()

            val spec = view.inspect {
                (it.root.childNodes.single() as ElementNode).listenerSpec("click")
            }
            // Show the spinner immediately, and still make the round trip for the work itself.
            assertEquals(listOf(ClientCommand.AddClass("busy", ClientTarget.Self)), spec?.commands)
            assertTrue(spec!!.notify)
        }
    }

    @Test
    fun `commands reach the browser in the first paint`(): Unit = runBlocking {
        LiveView { _ ->
            Button({ clientOnly { toggleClass("open") } }) { Text("Details") }
        }.use { view ->
            val html = view.also { it.start() }.renderHtml()

            // The commands travel in data-jl-on, which the client already reads, so a page works before
            // any patch has arrived.
            assertTrue(html.contains("&quot;toggle&quot;"), html)
            assertTrue(html.contains("&quot;notify&quot;:false"), html)
        }
    }

    @Test
    fun `a client-only listener costs nothing extra when the page changes`(): Unit = runBlocking {
        var label by mutableStateOf("Details")
        LiveView { _ ->
            Button({ clientOnly { toggleClass("open") } }) { Text(label) }
        }.use { view ->
            view.start()
            view.owner.startRecording()

            Snapshot.withMutableSnapshot { label = "Hide" }
            view.awaitIdle()

            // The listener is unchanged, so only the text changes. A new spec on every recomposition
            // would put an Op.Listen on the wire for no reason.
            assertEquals(
                listOf(jetlin.protocol.Op.SetText(2, "Hide")),
                view.inspect { it.drainOps() },
            )
        }
    }

    @Test
    fun `declaring commands for one event leaves another alone`(): Unit = runBlocking {
        LiveView { _ ->
            Input({
                clientOnly("focus") { addClass("focused", on = closest("field")) }
                onInput { }
            })
        }.use { view ->
            view.start()

            val node = view.inspect { it.root.childNodes.single() as ElementNode }
            assertFalse(node.listenerSpec("focus")!!.notify)
            assertTrue(node.listenerSpec("input")!!.notify)
            assertEquals(emptyList(), node.listenerSpec("input")!!.commands)
        }
    }
}
