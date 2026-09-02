package jetlin.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.P
import jetlin.html.Text
import jetlin.protocol.ClientMessage
import jetlin.protocol.ServerMessage
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a client is told when something goes wrong on the server.
 *
 * The distinction these pin down is the whole point: a handler that throws leaves the composition
 * healthy and costs one interaction, while a composable that throws stops the recomposer for good.
 * Treating both the same means either killing sessions that were fine, or leaving a page that looks
 * live and can never change again.
 */
class FailureTest {

    @Test
    fun `a handler that throws costs one interaction, not the session`(): Unit = testApplication {
        val reported = CopyOnWriteArrayList<Throwable>()
        application {
            jetlin {
                onError = { reported += it }
                view("/") {
                    var count by remember { mutableStateOf(0) }
                    Div {
                        Button({ attr("id", "boom"); onClick { error("the handler blew up") } }) { Text("boom") }
                        Button({ attr("id", "ok"); onClick { count++ } }) { Text("ok") }
                        P { Text("count $count") }
                    }
                }
            }
        }
        val client = createClient { install(WebSockets) }
        val token = client.tokenFromPage()

        client.webSocket("/jetlin") {
            hello(token)
            awaitMessage<ServerMessage.Reset>()

            send(ClientMessage.Event(node = 2, event = "click", seq = 1))
            val error = awaitMessage<ServerMessage.Error>()
            assertFalse(error.fatal, "a failed handler does not end the session: $error")
            // Nothing about the exception's own text reaches the browser.
            assertFalse(error.message.contains("blew up"), "the message leaks server detail: $error")

            // And the session carries on: the next click still works.
            send(ClientMessage.Event(node = 4, event = "click", seq = 2))
            val patch = awaitMessage<ServerMessage.Patch>()
            assertTrue(patch.ops.toString().contains("count 1"), "expected the session to still work, got $patch")
        }

        assertEquals(1, reported.size, "the application's error hook should have been given the exception")
        assertTrue(reported.single().message.orEmpty().contains("blew up"))
    }

    @Test
    fun `a composable that throws ends the session and says so`(): Unit = testApplication {
        val reported = CopyOnWriteArrayList<Throwable>()
        application {
            jetlin {
                onError = { reported += it }
                view("/") {
                    var broken by remember { mutableStateOf(false) }
                    Div {
                        Button({ attr("id", "break"); onClick { broken = true } }) { Text("break") }
                        // Fine on the first pass, fatal on the next: the recomposer stops and
                        // nothing this session does afterwards can succeed.
                        if (broken) error("the view blew up")
                    }
                }
            }
        }
        val client = createClient { install(WebSockets) }
        val token = client.tokenFromPage()

        client.webSocket("/jetlin") {
            hello(token)
            awaitMessage<ServerMessage.Reset>()

            send(ClientMessage.Event(node = 2, event = "click", seq = 1))
            val error = awaitMessage<ServerMessage.Error>()
            assertTrue(error.fatal, "a dead composition has to be reported as unrecoverable: $error")
            assertFalse(error.message.contains("blew up"), "the message leaks server detail: $error")
        }

        assertEquals(1, reported.size)
    }

    @Test
    fun `a frame that cannot be read is dropped rather than fatal`(): Unit = testApplication {
        application {
            jetlin {
                view("/") {
                    var count by remember { mutableStateOf(0) }
                    Div {
                        Button({ attr("id", "ok"); onClick { count++ } }) { Text("ok") }
                        P { Text("count $count") }
                    }
                }
            }
        }
        val client = createClient { install(WebSockets) }
        val token = client.tokenFromPage()

        client.webSocket("/jetlin") {
            hello(token)
            awaitMessage<ServerMessage.Reset>()

            // Anyone could send this. Ending the session over it would let a client kill its own
            // session with a typo, and would turn a version skew into an outage.
            send(Frame.Text("{ not json at all"))
            send(Frame.Text("""{"t":"nonsense"}"""))

            send(ClientMessage.Event(node = 2, event = "click", seq = 1))
            val patch = awaitMessage<ServerMessage.Patch>()
            assertTrue(patch.ops.toString().contains("count 1"), "expected the session to survive, got $patch")
        }
    }
}
