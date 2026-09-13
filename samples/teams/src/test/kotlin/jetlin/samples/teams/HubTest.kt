package jetlin.samples.teams

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import jetlin.db.Db
import jetlin.testing.ViewTest
import jetlin.testing.assertNotDisclosed
import jetlin.testing.click
import jetlin.testing.hasTestTag
import jetlin.testing.runViewTest
import jetlin.testing.setRoutes
import jetlin.testing.type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The sample against its own external system, over real HTTP.
 *
 * The stub runs in this process — on a port the operating system picks, through a real client and a real
 * request — which is as close to an external system as a test can honestly get. What is being checked is
 * not that HTTP works but that the two claims about caching hold where a principal can see them: each
 * principal's own data is fetched with each principal's own credential, and the thing that is the same
 * for everybody is fetched once.
 *
 * Both claims are about requests that did *not* happen, which is why the stub counts what it was asked.
 */
class HubTest {

    @Test
    fun `each principal sees their own status, fetched as themselves`() = withHub { hub ->
        data.setStatus("alice@example.com", "reviewing the sample")
        data.setStatus("bob@example.com", "on holiday")

        withSample { db ->
            runViewTest(url = "/hub") {
                signedInToHub(db, hub, "alice@example.com")

                eventually { onNode(hasTestTag("status")).assertText("reviewing the sample · 1 updates") }
                assertNotDisclosed("on holiday")
            }

            runViewTest(url = "/hub") {
                signedInToHub(db, hub, "bob@example.com")

                eventually { onNode(hasTestTag("status")).assertText("on holiday · 1 updates") }
                assertNotDisclosed("reviewing the sample")
            }
        }

        // One request per principal, each carrying that principal's own token. There is no single object
        // for `the profile`, so there is nothing for the wrong principal to reach.
        assertEquals(
            listOf("me:alice@example.com", "me:bob@example.com"),
            data.calls.filter { it.startsWith("me:") },
        )
    }

    @Test
    fun `the announcement is fetched once and shared`() = withHub { hub ->
        data.announcement = "Deploy freeze on Friday"

        withSample { db ->
            repeat(2) { session ->
                runViewTest(url = "/hub") {
                    signedInToHub(db, hub, if (session == 0) "alice@example.com" else "bob@example.com")

                    eventually { onNode(hasTestTag("announcement")).assertText("Deploy freeze on Friday") }
                    // And again in the chrome, which is a second reader of the same value.
                    onNode(hasTestTag("banner")).assertText("Deploy freeze on Friday")
                }
            }
        }

        assertEquals(
            1,
            data.calls.count { it == "announcement" },
            "two sessions read one value that is the same for both: that should be one request",
        )
    }

    @Test
    fun `the chrome shows external data on a page that knows nothing about it`() = withHub { hub ->
        data.announcement = "Deploy freeze on Friday"

        withSample { db ->
            runViewTest {
                signedInToHub(db, hub, "alice@example.com")

                // The todo list, rendering stored records, with something nobody here owns above it.
                onNode(hasTestTag("todos")).assertExists()
                eventually { onNode(hasTestTag("banner")).assertText("Deploy freeze on Friday") }
            }
        }
    }

    @Test
    fun `the banner follows the announcement while the page just sits there`() =
        withHub(refreshEvery = 40.milliseconds) { hub ->
            data.announcement = "Deploy freeze on Friday"

            withSample { db ->
                runViewTest {
                    signedInToHub(db, hub, "alice@example.com")
                    eventually { onNode(hasTestTag("banner")).assertText("Deploy freeze on Friday") }

                    // Changed at the far end, with nothing telling the application and nobody touching
                    // the page. The watch is what notices, and the write is what redraws it.
                    data.announcement = "All clear"

                    eventually { onNode(hasTestTag("banner")).assertText("All clear") }
                }
            }
        }

    @Test
    fun `saving a status shows the new one`() = withHub { hub ->
        withSample { db ->
            runViewTest(url = "/hub") {
                signedInToHub(db, hub, "alice@example.com")
                eventually { onNode(hasTestTag("status")).assertText("no status · 0 updates") }

                onNode(hasTestTag("status-draft")).type("writing the hub sample")
                onNode(hasTestTag("save-status")).click()

                // The command invalidated the profile rather than refetching it; the read that follows
                // the command finishing is what paid for the new copy.
                eventually {
                    onNode(hasTestTag("status")).assertText("writing the hub sample · 1 updates")
                }
            }
        }
    }

    @Test
    fun `a refused command shows on the page and leaves the session alive`() = withHub { hub ->
        withSample { db ->
            runViewTest(url = "/hub") {
                signedInToHub(db, hub, "alice@example.com")
                eventually { onNode(hasTestTag("status")).assertText("no status · 0 updates") }

                onNode(hasTestTag("status-draft")).type("x".repeat(STATUS_LIMIT + 1))
                onNode(hasTestTag("save-status")).click()

                eventually {
                    onNode(hasTestTag("status-error"))
                        .assertText("a status has to fit in $STATUS_LIMIT characters")
                }
                // Nothing was written, and the page is still a page: the refusal cost a line of text.
                onNode(hasTestTag("status")).assertText("no status · 0 updates")
                onNode(hasTestTag("save-status")).assertExists()
            }
        }
    }
}

/** Signs in as [email] and composes the hub route, with the application's real chrome around it. */
private suspend fun ViewTest.signedInToHub(db: Db, hub: Hub, email: String) {
    setAttribute(PrincipalKey, db.user(email))
    setRoutes {
        view("/", requires = Principals.signedIn) { WithPrincipal { TodoListPage(db) } }
        view("/hub", requires = Principals.signedIn) { WithPrincipal { HubPage(hub) } }
        app { route -> Shell(hub, route) }
    }
}

/**
 * The stub, a client pointed at it, and a way to wait for what the page does about it.
 *
 * Everything here waits through [eventually] rather than by joining coroutines. An earlier version joined
 * the fetch scope's children, which was exact until a watched value put a polling loop in that scope: a
 * loop that never finishes is not something a test can wait for.
 */
private class HubFixture(val data: HubData) {
    /**
     * Retries [assertion] until it holds, for work the session started that `awaitIdle` cannot see.
     *
     * A command suspends on a socket rather than queueing work on the session's dispatcher, so a session
     * with a request outstanding is genuinely idle — it is free to do anything else, which is the point.
     * A test that wants the outcome therefore has to wait for the outcome. This is the one place here
     * that waits in real time, which is why it is bounded and why it reports the last failure it saw.
     */
    suspend fun ViewTest.eventually(assertion: suspend () -> Unit) {
        var last: AssertionError? = null
        val held = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5.seconds) {
                while (true) {
                    awaitIdle()
                    try {
                        assertion()
                        return@withTimeoutOrNull true
                    } catch (failed: AssertionError) {
                        last = failed
                        delay(10)
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
        }
        if (held != true) throw AssertionError("still not true after 5s", last)
    }
}

private fun withHub(refreshEvery: Duration = 15.seconds, block: HubFixture.(Hub) -> Unit) {
    val data = HubData()
    val server = embeddedServer(Netty, port = 0) { hubService(data) }
    server.start(wait = false)
    try {
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Hub("http://127.0.0.1:$port", HttpClient(CIO), refreshEvery, scope).use { hub ->
            HubFixture(data).block(hub)
        }
    } finally {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
    }
}
