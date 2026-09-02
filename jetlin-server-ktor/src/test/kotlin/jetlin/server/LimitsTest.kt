package jetlin.server

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.P
import jetlin.html.Text
import jetlin.protocol.ClientMessage
import jetlin.protocol.ServerMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two ceilings, over a real server.
 *
 * Both exist for the same reason: without them a stream of requests from one source grows memory or
 * takes a share of the machine until neither is left for anybody else. Both are deliberately
 * degradations rather than defences — a refused page render and a dropped event are a bad day for
 * whoever hit the limit, and a normal one for everybody else.
 */
class LimitsTest {

    @Test
    fun `page renders are refused once the session limit is reached`(): Unit = testApplication {
        application {
            jetlin {
                maxSessions = 2
                view("/") { Div { Text("a page") } }
            }
        }
        val client = createClient { }

        repeat(2) {
            assertEquals(HttpStatusCode.OK, client.get("/").status, "session ${it + 1} should fit")
        }

        val refused = client.get("/")
        assertEquals(HttpStatusCode.ServiceUnavailable, refused.status)
        // Told when to come back, rather than left to guess.
        assertEquals("5", refused.headers[HttpHeaders.RetryAfter])
        assertTrue(refused.bodyAsText().contains("try again"), refused.bodyAsText())
    }

    @Test
    fun `a reconnect is not refused when the limit is reached`(): Unit = testApplication {
        application {
            jetlin {
                maxSessions = 1
                view("/") { Div { Text("a page") } }
            }
        }
        val client = createClient { install(WebSockets) }
        val token = client.tokenFromPage()

        // The one slot is taken, and new visitors are turned away.
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/").status)

        // Somebody who already had a session is not: turning them away to make room for new
        // visitors would be the wrong trade, and they are not the ones creating the pressure.
        client.webSocket("/jetlin") {
            hello(token)
            awaitMessage<ServerMessage.Reset>()
        }
    }

    @Test
    fun `a connection sending too fast is throttled and told once`(): Unit = testApplication {
        application {
            jetlin {
                // One event a second, no burst beyond the first: enough to let the hello through
                // and stop everything after it.
                eventsPerSecond = 1.0
                eventBurst = 1
                view("/") {
                    var count by remember { mutableStateOf(0) }
                    Div {
                        Button({ attr("id", "go"); onClick { count++ } }) { Text("go") }
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

            repeat(20) { send(ClientMessage.Event(node = 2, event = "click", seq = it + 1L)) }

            val error = awaitMessage<ServerMessage.Error>()
            assertFalse(error.fatal, "being told to slow down does not end the session: $error")
            assertTrue(error.message.contains("Too many"), error.message)
        }
    }

    @Test
    fun `a burst within the budget is not throttled`(): Unit = testApplication {
        application {
            jetlin {
                eventsPerSecond = 50.0
                eventBurst = 100
                view("/") {
                    var count by remember { mutableStateOf(0) }
                    Div {
                        Button({ attr("id", "go"); onClick { count++ } }) { Text("go") }
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

            // Filling in a form is bursty. A limit that could not absorb this would have to be set
            // so high it stopped protecting anything.
            repeat(30) { send(ClientMessage.Event(node = 2, event = "click", seq = it + 1L)) }

            // Every one arrived: the count reaches thirty rather than stopping at a limit.
            var last = ""
            while (!last.contains("count 30")) {
                last = awaitMessage<ServerMessage.Patch>().ops.toString()
            }
        }
    }
}
