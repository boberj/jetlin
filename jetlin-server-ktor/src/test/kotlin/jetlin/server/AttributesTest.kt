package jetlin.server

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import jetlin.html.Div
import jetlin.html.Text
import jetlin.protocol.ServerMessage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * When the application's `attributes` factory runs.
 *
 * It is the hook where a principal, a tenant or a locale enters a session, so it is somewhere an
 * application reasonably does real work — a directory lookup, a database read. How often it runs is
 * therefore part of its contract rather than an implementation detail.
 */
class AttributesTest {

    @Test
    fun `the factory runs for the page and not again for the socket that claims it`(): Unit =
        testApplication {
            val runs = AtomicInteger()
            application {
                jetlin {
                    attributes {
                        runs.incrementAndGet()
                        emptyMap()
                    }
                    view("/") { Div { Text("hello") } }
                }
            }
            val client = createClient { install(WebSockets) }

            val token = client.tokenFromPage()
            assertEquals(1, runs.get(), "the page render is what computes a session's attributes")

            client.webSocket("/jetlin") {
                hello(token)
                awaitMessage<ServerMessage.Reset>()
            }

            // The composition this socket attached to already has its context. Recomputing one to
            // throw away would charge every reconnect for whatever the factory does.
            assertEquals(1, runs.get(), "a socket claiming a live composition inherits its context")
        }
}
