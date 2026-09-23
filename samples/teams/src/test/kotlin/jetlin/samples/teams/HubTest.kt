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
 * Tests the sample against its stub of an external system, over real HTTP.
 *
 * The stub runs in this process on a port that the operating system chooses, and the tests call it
 * through a real HTTP client. That's as close to a real external system as a reliable test can get.
 * The tests check two caching properties from the page's point of view: each principal's data is
 * fetched with that principal's own credential, and data that's the same for everyone is fetched
 * only once.
 *
 * Both properties are about requests that weren't made, which is why the stub records every call.
 */
class HubTest {

    @Test
    fun `each principal sees their own status, fetched as themselves`(): Unit = withHub { hub ->
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

        // One request per principal, each with that principal's own token. There's no shared
        // profile object that the wrong principal could reach.
        assertEquals(
            listOf("me:alice@example.com", "me:bob@example.com"),
            data.calls.filter { it.startsWith("me:") },
        )
    }

    @Test
    fun `the announcement is fetched once and shared`(): Unit = withHub { hub ->
        data.announcement = "Deploy freeze on Friday"

        withSample { db ->
            repeat(2) { session ->
                runViewTest(url = "/hub") {
                    signedInToHub(db, hub, if (session == 0) "alice@example.com" else "bob@example.com")

                    eventually { onNode(hasTestTag("announcement")).assertText("Deploy freeze on Friday") }
                    // The chrome shows it too, so it's a second reader of the same value.
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
    fun `the chrome shows external data on a page that knows nothing about it`(): Unit = withHub { hub ->
        data.announcement = "Deploy freeze on Friday"

        withSample { db ->
            runViewTest {
                signedInToHub(db, hub, "alice@example.com")

                // The todo list, showing stored records, with external data in the chrome above it.
                onNode(hasTestTag("todos")).assertExists()
                eventually { onNode(hasTestTag("banner")).assertText("Deploy freeze on Friday") }
            }
        }
    }

    @Test
    fun `the banner follows the announcement while the page just sits there`(): Unit =
        withHub(refreshEvery = 40.milliseconds) { hub ->
            data.announcement = "Deploy freeze on Friday"

            withSample { db ->
                runViewTest {
                    signedInToHub(db, hub, "alice@example.com")
                    eventually { onNode(hasTestTag("banner")).assertText("Deploy freeze on Friday") }

                    // The announcement changes on the external system. Nothing notifies the application,
                    // and nobody interacts with the page. The watch picks up the change, and its write
                    // redraws the banner.
                    data.announcement = "All clear"

                    eventually { onNode(hasTestTag("banner")).assertText("All clear") }
                }
            }
        }

    @Test
    fun `saving a status shows the new one`(): Unit = withHub { hub ->
        withSample { db ->
            runViewTest(url = "/hub") {
                signedInToHub(db, hub, "alice@example.com")
                eventually { onNode(hasTestTag("status")).assertText("no status · 0 updates") }

                onNode(hasTestTag("status-draft")).type("writing the hub sample")
                onNode(hasTestTag("save-status")).click()

                // After the command succeeds, it refreshes the profile, and the new status appears
                // once that fetch completes.
                eventually {
                    onNode(hasTestTag("status")).assertText("writing the hub sample · 1 updates")
                }
            }
        }
    }

    @Test
    fun `a refused command shows on the page and leaves the session alive`(): Unit = withHub { hub ->
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
                // Nothing was saved, and the session still works. The only effect is an error message.
                onNode(hasTestTag("status")).assertText("no status · 0 updates")
                onNode(hasTestTag("save-status")).assertExists()
            }
        }
    }
}

/** Signs in as [email] and composes the hub route inside the application's real chrome. */
private suspend fun ViewTest.signedInToHub(db: Db, hub: Hub, email: String) {
    setAttribute(PrincipalKey, db.user(email))
    setRoutes {
        view("/", requires = Principals.signedIn) { WithPrincipal { TodoListPage(db) } }
        view("/hub", requires = Principals.signedIn) { WithPrincipal { HubPage(hub) } }
        app { route -> Shell(hub, route) }
    }
}

/**
 * The test setup: the stub, a hub client pointed at it, and a way to wait for the page to react.
 *
 * Tests wait with [eventually] instead of joining coroutines. An earlier version joined the fetch
 * scope's children, which worked until watched values added a polling loop to that scope. The loop
 * never finishes, so joining it would never return.
 */
private class HubFixture(val data: HubData) {
    /**
     * Retries [assertion] until it passes, for work that the session started and `awaitIdle` can't
     * see.
     *
     * A command waits on a network socket instead of queuing work on the session's dispatcher, so a
     * session with a request in progress really is idle, and can handle other events. A test that
     * needs the outcome has to wait for the outcome itself. This is the only real-time wait in these
     * tests, so it has a time limit, and it reports the last failure it saw.
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
