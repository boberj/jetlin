package jetlin.server

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import jetlin.html.AttributeKey
import jetlin.html.Div
import jetlin.html.Text
import jetlin.protocol.ServerMessage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests when the application's `principal` resolver runs.
 *
 * The resolver is where the principal enters a session, so an application can reasonably do real
 * work there, such as a directory lookup or a database read. How often it runs is part of its
 * contract, not an implementation detail.
 */
class PrincipalTest {

    @Test
    fun `the resolver runs for the page and not again for the socket that claims it`(): Unit =
        testApplication {
            val runs = AtomicInteger()
            application {
                jetlin {
                    principal(AttributeKey<String?>("user")) {
                        runs.incrementAndGet()
                        null
                    }
                    view("/") { Div { Text("hello") } }
                }
            }
            val client = createClient { install(WebSockets) }

            val token = client.tokenFromPage()
            assertEquals(1, runs.get(), "the page render is what computes a session's principal")

            client.webSocket("/jetlin") {
                hello(token)
                awaitMessage<ServerMessage.Reset>()
            }

            // The composition this socket attached to already has its context. Computing a new one only
            // to discard it would charge every reconnect for whatever the resolver does.
            assertEquals(1, runs.get(), "a socket claiming a live composition inherits its context")
        }
}
