package jetlin.html

import androidx.compose.runtime.Composable
import jetlin.protocol.ClientMessage
import jetlin.protocol.EventPayload
import jetlin.protocol.Extract
import jetlin.protocol.ListenerSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What an element listens for, and what happens when two declarations want the same event.
 *
 * Listeners are keyed by event name from here to the browser's table, so a second handler for one
 * event has nowhere to go. These pin down that it is refused where it was written rather than
 * quietly winning or quietly losing.
 */
class EventsTest {

    @Test
    fun `a select reports the option that was chosen`(): Unit = runBlocking {
        var chosen = "alpha"
        val view = LiveView { _ ->
            Select({ onChange { chosen = it } }) {
                Option({ value("alpha") }) { Text("Alpha") }
                Option({ value("bravo") }) { Text("Bravo") }
            }
        }
        view.use {
            view.start()
            view.change(node = 1, value = "bravo")
            assertEquals("bravo", chosen)
        }
    }

    @Test
    fun `onChange asks the browser for the value and nothing else`(): Unit = runBlocking {
        val view = LiveView { _ -> Select({ onChange { } }) }
        view.use {
            view.start()
            val select = view.owner.root.childNodes.single() as ElementNode
            assertEquals(setOf("change"), select.eventNames)
            assertEquals(
                ListenerSpec(extract = listOf(Extract.VALUE)),
                select.listenerSpec("change"),
            )
        }
    }

    @Test
    fun `onChange and onChecked cannot both be declared on one element`(): Unit = assertRejects(
        "<input> declares two handlers for 'change'",
        "onChecked and onChange",
    ) {
        Input({ onChange { }; onChecked { } })
    }

    @Test
    fun `two handlers for the same event are refused whichever they are`(): Unit = assertRejects(
        "<button> declares two handlers for 'click'",
    ) {
        Button({ onClick { }; on("click") { } })
    }

    @Test
    fun `a link runs the click a caller declared and still navigates`(): Unit = runBlocking {
        var clicks = 0
        val view = LiveView { _ -> Link("/next", { onClick { clicks++ } }) { Text("Next") } }
        view.use {
            view.start()
            view.dispatch(ClientMessage.Event(node = 1, event = "click", seq = 1, payload = EventPayload()))
            // Both parties had a claim on this click: the caller wanted to hear about it, and the
            // link still has to move the session.
            assertEquals(1, clicks)
            assertEquals("/next", view.currentUrl)
        }
    }
}

/** Sends a committed value the way a `<select>` would, through the transport's own message type. */
private suspend fun LiveView.change(node: Int, value: String) {
    dispatch(ClientMessage.Event(node = node, event = "change", seq = 1, payload = EventPayload(value = value)))
}

/** Composes [content] and asserts it was refused, with a message mentioning each of [fragments]. */
private fun assertRejects(vararg fragments: String, content: @Composable () -> Unit): Unit = runBlocking {
    val failure = runCatching {
        val view = LiveView { _ -> content() }
        view.use { view.start() }
    }.exceptionOrNull()

    val reported = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
    for (fragment in fragments) {
        assertTrue(fragment in reported, "expected a failure mentioning \"$fragment\", got: $reported")
    }
}
