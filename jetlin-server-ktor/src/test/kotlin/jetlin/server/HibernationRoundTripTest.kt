package jetlin.server

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import jetlin.html.Div
import jetlin.html.Input
import jetlin.html.Text
import jetlin.html.bind
import jetlin.html.rememberSavedField
import jetlin.protocol.ClientMessage
import jetlin.protocol.EventPayload
import jetlin.protocol.JetlinJson
import jetlin.protocol.ServerMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * A session going all the way down and coming back, over a real socket.
 *
 * The unit tests cover capture and restore in isolation; this covers the part only the transport
 * knows — that a socket going away eventually hibernates the session, and that a later socket
 * quoting the same token gets the user's state back rather than a blank page.
 */
class HibernationRoundTripTest {

    private val grace = 150.milliseconds

    @Test
    fun `a draft typed before a disconnect survives hibernation`(): Unit = testApplication {
        val store = InMemorySessionStore()
        application {
            jetlin {
                sessionStore = store
                disconnectGrace = grace
                view("/") {
                    val draft = rememberSavedField("", key = "draft")
                    Div { Input({ attr("id", "draft"); bind(draft) }) }
                }
            }
        }
        val client = createClient { install(WebSockets) }
        val token = client.tokenFromPage()

        client.webSocket("/jetlin") {
            hello(token)
            awaitMessage<ServerMessage.Reset>()
            send(
                ClientMessage.Event(
                    node = 2,
                    event = "input",
                    seq = 1,
                    payload = EventPayload(value = "typed before the disconnect"),
                ),
            )
            awaitMessage<ServerMessage.Patch>()
        }

        // Past the grace period the composition is destroyed and only the snapshot remains.
        waitUntil { store.size == 1 }

        client.webSocket("/jetlin") {
            hello(token)
            val reset = awaitMessage<ServerMessage.Reset>()
            assertTrue(
                reset.children.toString().contains("typed before the disconnect"),
                "the woken session should render what the user had typed, got $reset",
            )
        }
    }

    @Test
    fun `a session with nothing saveable is not stored`(): Unit = testApplication {
        val store = InMemorySessionStore()
        application {
            jetlin {
                sessionStore = store
                disconnectGrace = grace
                view("/") { Div { Text("nothing worth keeping") } }
            }
        }
        val client = createClient { install(WebSockets) }
        val token = client.tokenFromPage()

        client.webSocket("/jetlin") {
            hello(token)
            awaitMessage<ServerMessage.Reset>()
        }

        delay(grace * 4)
        assertEquals(0, store.size, "a session with no saved state is not worth a store entry")

        // And a client quoting that token is told plainly, so it can start over.
        client.webSocket("/jetlin") {
            hello(token)
            val error = awaitMessage<ServerMessage.Error>()
            assertTrue(error.fatal, "an unrecoverable session must say so: $error")
        }
    }

    @Test
    fun `a woken session resumes at the location the browser reports`(): Unit = testApplication {
        val store = InMemorySessionStore()
        application {
            jetlin {
                sessionStore = store
                disconnectGrace = grace
                view("/") {
                    val draft = rememberSavedField("home", key = "draft")
                    Div { Input({ bind(draft) }) }
                }
                view("/elsewhere") { Div { Text("the other page") } }
            }
        }
        val client = createClient { install(WebSockets) }
        val token = client.tokenFromPage()

        client.webSocket("/jetlin") {
            hello(token)
            awaitMessage<ServerMessage.Reset>()
        }
        waitUntil { store.size == 1 }

        // The user pressed back while disconnected: the address bar wins over the stored location.
        client.webSocket("/jetlin") {
            hello(token, url = "/elsewhere")
            val reset = awaitMessage<ServerMessage.Reset>()
            assertTrue(
                reset.children.toString().contains("the other page"),
                "expected the browser's location to win, got $reset",
            )
        }
    }
}

private suspend fun io.ktor.client.HttpClient.tokenFromPage(): String {
    val html = get("/").bodyAsText()
    return Regex("""token: "([^"]+)"""").find(html)?.groupValues?.get(1)
        ?: error("no session token in the rendered page")
}

private suspend fun io.ktor.websocket.WebSocketSession.hello(token: String, url: String? = null) {
    send(ClientMessage.Hello(token, url))
}

private suspend fun io.ktor.websocket.WebSocketSession.send(message: ClientMessage) {
    send(Frame.Text(JetlinJson.encodeToString(ClientMessage.serializer(), message)))
}

/** Reads frames until one of the requested type arrives, so unrelated traffic cannot fail a test. */
private suspend inline fun <reified T : ServerMessage> io.ktor.websocket.WebSocketSession.awaitMessage(): T =
    withTimeout(5_000) {
        while (true) {
            val frame = incoming.receive() as? Frame.Text ?: continue
            val message = JetlinJson.decodeFromString(ServerMessage.serializer(), frame.readText())
            if (message is T) return@withTimeout message
        }
        error("unreachable")
    }

private suspend fun waitUntil(condition: () -> Boolean) {
    withTimeout(5_000) {
        while (!condition()) delay(20)
    }
}
