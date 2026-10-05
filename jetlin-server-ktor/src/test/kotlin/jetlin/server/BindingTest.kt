package jetlin.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.Input
import jetlin.html.LocalNavigator
import jetlin.html.Text
import jetlin.html.bind
import jetlin.html.rememberSavedField
import jetlin.protocol.ClientMessage
import jetlin.protocol.ServerMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests binding session tokens to the browser that the page was rendered for.
 *
 * The browser is identified here by a header, which stands in for the hash of a sign-in cookie's
 * session ID that `jetlin-server-ktor-auth` supplies. What's under test is the registry's side: a
 * token is useless without the browser it belongs to, and ending a browser's sessions reaches every
 * one of them.
 */
class BindingTest {

    private val grace = 150.milliseconds

    @Test
    fun `a token presented from another browser is refused`(): Unit = testApplication {
        application { boundApp() }
        val client = createClient { install(WebSockets) }
        val token = client.tokenFor("alice")

        client.webSocket("/jetlin", request = { header(BROWSER, "mallory") }) {
            hello(token)
            val error = awaitMessage<ServerMessage.Error>()
            assertEquals(ServerMessage.Error("Unknown or already-attached session", fatal = true), error)
        }

        // The refusal didn't use the session up. Its own browser still gets it.
        client.webSocket("/jetlin", request = { header(BROWSER, "alice") }) {
            hello(token)
            awaitMessage<ServerMessage.Reset>()
        }
    }

    @Test
    fun `a token rendered for a signed-out browser is refused once it signs in`(): Unit = testApplication {
        application { boundApp() }
        val client = createClient { install(WebSockets) }
        // No header means no binding, as for a browser without a sign-in cookie.
        val token = client.tokenFromPage()

        client.webSocket("/jetlin", request = { header(BROWSER, "alice") }) {
            hello(token)
            assertTrue(awaitMessage<ServerMessage.Error>().fatal)
        }
    }

    @Test
    fun `a hibernated session wakes only for its own browser`(): Unit = testApplication {
        val store = InMemorySessionStore()
        application {
            boundApp {
                sessionStore = store
                disconnectGrace = grace
            }
        }
        val client = createClient { install(WebSockets) }
        val token = client.tokenFor("alice")

        client.webSocket("/jetlin", request = { header(BROWSER, "alice") }) {
            hello(token)
            awaitMessage<ServerMessage.Reset>()
        }
        waitUntil { store.size == 1 }

        client.webSocket("/jetlin", request = { header(BROWSER, "mallory") }) {
            hello(token)
            assertTrue(awaitMessage<ServerMessage.Error>().fatal)
        }
        // Presenting the token from the wrong browser mustn't destroy the owner's saved state.
        assertEquals(1, store.size, "the snapshot goes back to the store")

        client.webSocket("/jetlin", request = { header(BROWSER, "alice") }) {
            hello(token)
            val reset = awaitMessage<ServerMessage.Reset>()
            assertTrue(reset.children.toString().contains("saved draft"), "expected the saved draft, got $reset")
        }
    }

    @Test
    fun `ending a browser's sessions tells every attached socket and spares other browsers`(): Unit =
        testApplication {
            lateinit var registry: SessionRegistry
            application {
                boundApp()
                registry = jetlinSessions
            }
            val client = createClient { install(WebSockets) }
            val first = client.tokenFor("alice")
            val second = client.tokenFor("alice")
            val bobs = client.tokenFor("bob")

            client.webSocket("/jetlin", request = { header(BROWSER, "bob") }) {
                hello(bobs)
                awaitMessage<ServerMessage.Reset>()

                client.webSocket("/jetlin", request = { header(BROWSER, "alice") }) {
                    hello(first)
                    awaitMessage<ServerMessage.Reset>()

                    // The second tab is still between render and connect. It's ended too.
                    assertEquals(2, registry.endSessions("alice"))
                    assertEquals(ServerMessage.Error("This session has ended.", fatal = true), awaitMessage())
                }

                // The ended session is gone, so the tab that never connected can't claim it later.
                client.webSocket("/jetlin", request = { header(BROWSER, "alice") }) {
                    hello(second)
                    assertTrue(awaitMessage<ServerMessage.Error>().fatal)
                }

                // Bob's session is untouched and still answers.
                send(ClientMessage.Event(node = 3, event = "click", seq = 1))
                awaitMessage<ServerMessage.Load>()
            }
        }

    @Test
    fun `a page load goes out after the patch from the same event`(): Unit = testApplication {
        application { boundApp() }
        val client = createClient { install(WebSockets) }
        val token = client.tokenFor("alice")

        client.webSocket("/jetlin", request = { header(BROWSER, "alice") }) {
            hello(token)
            awaitMessage<ServerMessage.Reset>()
            send(ClientMessage.Event(node = 3, event = "click", seq = 1))
            assertEquals(
                ServerMessage.Load("/elsewhere", post = mapOf("ticket" to "t")),
                awaitMessage<ServerMessage.Load>(),
            )
        }
    }
}

/** The header that stands in for the browser's identity. */
private const val BROWSER = "X-Browser"

/** An application that binds sessions to [BROWSER], with a saved draft and a button that loads a page. */
private fun Application.boundApp(configure: JetlinConfig.() -> Unit = {}) {
    jetlin {
        bindSessions { call -> call.request.headers[BROWSER] }
        configure()
        view("/") {
            val draft = rememberSavedField("saved draft", key = "draft")
            val navigator = LocalNavigator.current
            Div {
                Input({ bind(draft) })
                Button({ onClick { navigator.load("/elsewhere", post = mapOf("ticket" to "t")) } }) { Text("Go") }
            }
        }
    }
}

/** Renders the page as [browser], and returns the page's session token. */
private suspend fun HttpClient.tokenFor(browser: String): String {
    val html = get("/") { header(BROWSER, browser) }.bodyAsText()
    return Regex("""token: "([^"]+)"""").find(html)?.groupValues?.get(1)
        ?: error("no session token in the rendered page")
}
