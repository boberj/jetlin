package jetlin.html

import java.util.Collections
import jetlin.protocol.ClientMessage
import jetlin.protocol.Op
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
 * Navigation seen from the outside: what a connected browser would actually receive.
 *
 * These use `runBlocking` rather than `runTest` because a LiveView runs on real dispatchers, and
 * virtual time would skip straight past the waiting these need to do.
 */
class LiveViewTest {

    @Test
    fun `navigating swaps the view and tells the browser where it went`(): Unit = withNavigatingView { view, received ->
        // Node 1 is the button; clicking it calls navigator.push("/b").
        view.dispatch(ClientMessage.Event(node = 1, event = "click", seq = 1))
        received.awaitAtLeast(2)

        val patch = assertIs<ServerMessage.Patch>(received[0])
        assertEquals(listOf(Op.SetText(2, "at /b")), patch.ops)

        val navigate = assertIs<ServerMessage.Navigate>(received[1])
        assertEquals("/b", navigate.url)
        assertEquals(false, navigate.replace)
        assertEquals("/b", view.currentUrl)
    }

    @Test
    fun `the patch arrives before the address bar changes`(): Unit = withNavigatingView { view, received ->
        view.dispatch(ClientMessage.Event(node = 1, event = "click", seq = 1))
        received.awaitAtLeast(2)

        // Otherwise the browser would briefly show the new URL alongside the old content.
        assertTrue(received[0] is ServerMessage.Patch, "expected the patch first, got $received")
        assertTrue(received[1] is ServerMessage.Navigate)
    }

    @Test
    fun `a browser-initiated navigation is followed, not echoed back`(): Unit = withNavigatingView { view, received ->
        // The user pressed back, so the address bar has already moved.
        view.dispatch(ClientMessage.Navigate("/b"))
        received.awaitAtLeast(1)
        delay(150) // give a spurious echo a chance to show up

        assertEquals("/b", view.currentUrl)
        assertTrue(
            received.none { it is ServerMessage.Navigate },
            "the server must not tell the browser to go where it already is, got $received",
        )
    }

    @Test
    fun `navigating to the current location does nothing`(): Unit = withNavigatingView(start = "/b") { view, received ->
        view.dispatch(ClientMessage.Event(node = 1, event = "click", seq = 1))
        delay(200)

        assertTrue(received.isEmpty(), "expected no traffic for a no-op navigation, got $received")
    }

    @Test
    fun `the request is readable from any depth of the composition`(): Unit = runBlocking {
        val view = LiveView(RequestContext(path = "/todo/7", pathParams = mapOf("id" to "7"))) { _ ->
            Div {
                Div {
                    Span { Text("id=${pathParam("id")} path=${LocalRequest.current.path}") }
                }
            }
        }
        view.use {
            view.start()
            val html = view.renderHtml()
            assertTrue(html.contains("id=7 path=/todo/7"), html)
        }
    }
}

/**
 * Runs [block] against a view holding a button that navigates to `/b`, with a collector attached to
 * its outgoing messages.
 */
private fun withNavigatingView(
    start: String = "/a",
    block: suspend (LiveView, List<ServerMessage>) -> Unit,
) = runBlocking {
    val view = LiveView(RequestContext(path = start)) { request ->
        val navigator = LocalNavigator.current
        Button({ onClick { navigator.push("/b") } }) {
            Text("at ${request.path}")
        }
    }
    view.start()

    val received = Collections.synchronizedList(mutableListOf<ServerMessage>())
    val collector = launch { view.messages.collect { received += it } }
    try {
        block(view, received)
    } finally {
        collector.cancel()
        view.close()
    }
}

private suspend fun List<ServerMessage>.awaitAtLeast(count: Int) {
    val arrived = withTimeoutOrNull(5_000) {
        while (size < count) delay(10)
        true
    }
    checkNotNull(arrived) { "timed out waiting for $count messages, saw $this" }
}
