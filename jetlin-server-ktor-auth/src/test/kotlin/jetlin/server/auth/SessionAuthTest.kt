package jetlin.server.auth

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.cookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.auth.SessionTransportType
import io.ktor.server.auth.install
import io.ktor.server.auth.session
import io.ktor.server.sessions.SessionStorageMemory
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ExperimentalKtorApi
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import java.util.concurrent.ConcurrentHashMap
import jetlin.html.AttributeKey
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.LocalRequest
import jetlin.html.Principals
import jetlin.html.Text
import jetlin.html.queryParam
import jetlin.protocol.ClientMessage
import jetlin.protocol.JetlinJson
import jetlin.protocol.NodeId
import jetlin.protocol.NodeSpec
import jetlin.protocol.ServerMessage
import jetlin.server.jetlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

/**
 * Tests signing in and out through Jetlin over real HTTP and WebSocket traffic.
 *
 * Each [HttpClient] with its own cookie jar is one browser. The flow under test is the one a user
 * goes through: a handler asks to sign in, the socket receives a page load, the browser posts the
 * ticket, and the response sets the cookie that every later request authenticates with.
 */
@OptIn(ExperimentalKtorApi::class)
class SessionAuthTest {

    @Test
    fun `a guarded page sends a browser without a session to sign in`(): Unit = authTest {
        val response = browser().get("/")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/login?next=/", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `signing in from a handler sets a session cookie and goes to next`(): Unit = authTest {
        val browser = browser()
        val redeemed = browser.signIn("alice", next = "/")

        assertEquals(HttpStatusCode.SeeOther, redeemed.status, redeemed.bodyAsText())
        assertEquals("/", redeemed.headers[HttpHeaders.Location])
        val home = browser.get("/")
        assertEquals(HttpStatusCode.OK, home.status)
        assertTrue(home.bodyAsText().contains("Signed in as alice"), home.bodyAsText())
    }

    @Test
    fun `the cookie holds an opaque ID and not the session`(): Unit = authTest {
        val browser = browser()
        browser.signIn("alice")
        val cookie = assertNotNull(browser.sessionCookie())
        assertTrue("alice" !in cookie.value, "the cookie should be an ID, got ${cookie.value}")
        assertTrue(cookie.httpOnly, "a session cookie must be out of reach of scripts")
    }

    @Test
    fun `signing in again issues a new session ID and retires the old one`(): Unit = authTest {
        val browser = browser()
        browser.signIn("alice")
        val before = assertNotNull(browser.sessionCookie()).value

        browser.signIn("bob")
        val after = assertNotNull(browser.sessionCookie()).value

        assertNotEquals(before, after, "keeping the ID across sign-in allows session fixation")
        assertTrue(browser.get("/").bodyAsText().contains("Signed in as bob"))
        // Whoever held the old ID has nothing now.
        val stale = browser().get("/") { header(HttpHeaders.Cookie, "$COOKIE=$before") }
        assertNotEquals(HttpStatusCode.OK, stale.status)
    }

    @Test
    fun `a ticket can be redeemed only once`(): Unit = authTest {
        val browser = browser()
        val ticket = browser.ticketFor("alice")
        assertNotNull(browser.redeem(ticket).headers[HttpHeaders.SetCookie])

        val replay = browser().redeem(ticket)
        assertEquals(HttpStatusCode.SeeOther, replay.status)
        assertEquals("/", replay.headers[HttpHeaders.Location])
        assertNull(replay.headers[HttpHeaders.SetCookie], "a spent ticket must not sign anyone in")
    }

    @Test
    fun `a ticket posted from another site is refused`(): Unit = authTest {
        val browser = browser()
        val ticket = browser.ticketFor("alice")

        val crossOrigin = browser.redeem(ticket, origin = "https://evil.example")
        assertEquals(HttpStatusCode.Forbidden, crossOrigin.status)
        assertNull(crossOrigin.headers[HttpHeaders.SetCookie])

        val crossSite = browser.redeem(browser.ticketFor("alice"), fetchSite = "cross-site")
        assertEquals(HttpStatusCode.Forbidden, crossSite.status)
    }

    @Test
    fun `next can only point at this site`(): Unit = authTest {
        val redeemed = browser().signIn("alice", next = "//evil.example/phish")
        assertEquals("/", redeemed.headers[HttpHeaders.Location])
    }

    @Test
    fun `a token is useless in a browser without its cookie`(): Unit = authTest {
        val alice = browser()
        alice.signIn("alice")
        val token = alice.tokenOf("/")

        browser().webSocket("/jetlin") {
            hello(token)
            assertTrue(awaitMessage<ServerMessage.Error>().fatal, "another browser must not get alice's session")
        }
    }

    @Test
    fun `signing out in one tab ends the others`(): Unit = authTest {
        val browser = browser()
        browser.signIn("alice")
        val first = browser.tokenOf("/")
        val second = browser.tokenOf("/")

        browser.webSocket("/jetlin") {
            hello(second)
            awaitMessage<ServerMessage.Reset>()

            val ticket = browser.ticketFrom(first, button = "sign-out")
            assertEquals("/login", browser.redeem(ticket).headers[HttpHeaders.Location])

            assertEquals(ServerMessage.Error("This session has ended.", fatal = true), awaitMessage())
        }
        assertEquals(HttpStatusCode.Found, browser.get("/").status, "the browser is signed out")
    }

    @Test
    fun `a session that no longer validates is cleared instead of refused`(): Unit = authTest {
        val browser = browser()
        browser.signIn("carol")
        users.remove("carol")

        // The first request clears the cookie and comes back as nobody, instead of a 401 on every
        // request until the cookie expires.
        val cleared = browser.get("/")
        assertEquals(HttpStatusCode.Found, cleared.status)
        assertEquals("/", cleared.headers[HttpHeaders.Location])
        assertEquals("/login?next=/", browser.get("/").headers[HttpHeaders.Location])
    }

    @Test
    fun `local paths pass and everything else becomes the root`() {
        assertEquals("/todos?filter=open", localPath("/todos?filter=open"))
        assertEquals("/", localPath("https://evil.example"))
        assertEquals("/", localPath("//evil.example"))
        assertEquals("/", localPath("/\\evil.example"))
        assertEquals("/", localPath("/ok\r\nSet-Cookie: x=y"))
    }

    // ------------------------------------------------------------------ fixture

    private val users = ConcurrentHashMap(
        mapOf("alice" to User("alice"), "bob" to User("bob"), "carol" to User("carol")),
    )

    private fun authTest(block: suspend ApplicationTestBuilder.() -> Unit): Unit = testApplication {
        application {
            val sessionAuth = session<TestSession, User>(COOKIE) {
                transport = SessionTransportType.CookieId(SessionStorageMemory()) {
                    cookie.path = "/"
                    cookie.httpOnly = true
                }
                validate { session -> users[session.name] }
            }
            install(sessionAuth)

            jetlin {
                val auth = authentication(sessionAuth, principal = PrincipalKey)
                view("/login") {
                    val controls = auth.rememberControls()
                    val next = queryParam("next") ?: "/"
                    Div {
                        for (name in listOf("alice", "bob", "carol")) {
                            Button({ attr("name", name); onClick { controls.signIn(TestSession(name), next) } }) {
                                Text(name)
                            }
                        }
                    }
                }
                view("/", requires = Principals.signedIn) {
                    val controls = auth.rememberControls()
                    val user = Principals.of(LocalRequest.current)
                    Div {
                        Text("Signed in as ${user?.name}")
                        Button({ attr("name", "sign-out"); onClick { controls.signOut("/login") } }) {
                            Text("Sign out")
                        }
                    }
                }
            }
        }
        block()
    }

    /** Returns a new browser: a client with its own cookie jar that doesn't follow redirects. */
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient {
        install(HttpCookies)
        install(WebSockets)
        followRedirects = false
    }

    /** Signs this browser in as [name] through the sign-in page, and returns the redeeming response. */
    private suspend fun HttpClient.signIn(name: String, next: String = "/"): HttpResponse =
        redeem(ticketFor(name, next))

    /** Clicks [name]'s button on the sign-in page, and returns the ticket that the page load carries. */
    private suspend fun HttpClient.ticketFor(name: String, next: String = "/"): String =
        ticketFrom(tokenOf("/login?next=$next"), button = name)

    /** Clicks [button] in the session [token], and returns the ticket that the page load carries. */
    private suspend fun HttpClient.ticketFrom(token: String, button: String): String {
        var ticket: String? = null
        webSocket("/jetlin") {
            hello(token)
            val reset = awaitMessage<ServerMessage.Reset>()
            send(ClientMessage.Event(node = reset.nodeNamed(button), event = "click", seq = 1))
            val load = awaitMessage<ServerMessage.Load>()
            assertEquals("/jetlin/auth", load.url)
            ticket = load.post?.get("ticket")
        }
        return assertNotNull(ticket, "the page load should carry a ticket")
    }

    /** Posts [ticket] as the browser's hidden form would. */
    private suspend fun HttpClient.redeem(
        ticket: String,
        origin: String = "http://localhost",
        fetchSite: String = "same-origin",
    ): HttpResponse = submitForm("/jetlin/auth", parameters { append("ticket", ticket) }) {
        // The test engine sends no Host header, which a browser always does.
        header(HttpHeaders.Host, "localhost")
        header(HttpHeaders.Origin, origin)
        header("Sec-Fetch-Site", fetchSite)
    }

    /** Renders [url], and returns the page's session token. */
    private suspend fun HttpClient.tokenOf(url: String): String {
        val html = get(url).bodyAsText()
        return Regex("""token: "([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: error("no session token in $url: $html")
    }

    private suspend fun HttpClient.sessionCookie() = cookies("http://localhost/").firstOrNull { it.name == COOKIE }
}

@Serializable
private data class TestSession(val name: String)

private data class User(val name: String)

private const val COOKIE = "test_session"

private val PrincipalKey = AttributeKey<User?>("user")

private val Principals = Principals(PrincipalKey, signIn = "/login")

/** Returns the ID of the button whose `name` attribute is [name]. */
private fun ServerMessage.Reset.nodeNamed(name: String): NodeId {
    fun find(nodes: List<NodeSpec>): NodeId? = nodes.firstNotNullOfOrNull { node ->
        when {
            node !is NodeSpec.Element -> null
            node.tag == "button" && node.attrs["name"] == name -> node.id
            else -> find(node.children)
        }
    }
    return find(children) ?: error("no button named $name in $children")
}

private suspend fun WebSocketSession.hello(token: String) {
    send(ClientMessage.Hello(token, null))
}

private suspend fun WebSocketSession.send(message: ClientMessage) {
    send(Frame.Text(JetlinJson.encodeToString(ClientMessage.serializer(), message)))
}

/** Reads frames until one of the requested type arrives, so unrelated traffic can't fail a test. */
private suspend inline fun <reified T : ServerMessage> WebSocketSession.awaitMessage(): T =
    withTimeout(5_000) {
        while (true) {
            val frame = incoming.receive() as? Frame.Text ?: continue
            val message = JetlinJson.decodeFromString(ServerMessage.serializer(), frame.readText())
            if (message is T) return@withTimeout message
        }
        error("unreachable")
    }
