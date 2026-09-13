package jetlin.db

import androidx.compose.runtime.Composable
import jetlin.html.AttributeKey
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.Text
import jetlin.html.Principals
import jetlin.html.RequestContext
import jetlin.testing.ViewTest
import jetlin.testing.hasTag
import jetlin.testing.runViewTest
import jetlin.testing.setRoutes
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A route that resolves its own record.
 *
 * The bug this deletes is the textbook insecure direct object reference: `/todo/42` where 42 is someone
 * else's. Under this API the insecure version is not expressible, because the lookup the route performs
 * is the gated one and there is no path parameter left to look up by hand.
 *
 * The title is asserted separately in every case, because `<head>` is rendered before the body: a title
 * computed from a row discloses it even when the body refused to show it.
 */
class EntityRouteTest {

    @Test
    fun `a principal reaches their own record, and the title comes from it`(): Unit = withRoutedDb { db, alice, _ ->
        val task = db.transact { db.insert(Task(alice, "Read the plan")) }

        runViewTest(url = "/task/${task.id}") {
            setAttribute(PrincipalKey, alice)
            setTaskRoutes(db)

            onNode(hasTag("h1")).assertText("Read the plan")
            assertEquals("Read the plan", title())
        }
    }

    @Test
    fun `someone else's record is not found, and the title does not disclose it`(): Unit =
        withRoutedDb { db, alice, bob ->
            val task = db.transact { db.insert(Task(alice, "Alice's secret plan")) }

            runViewTest(url = "/task/${task.id}") {
                setAttribute(PrincipalKey, bob)
                setTaskRoutes(db)

                onNode(hasTag("h1")).assertText("Not found")
                assertEquals(
                    "Not found",
                    title(),
                    "the row's title reached <head> before anything checked whether Bob may read it",
                )
            }
        }

    @Test
    fun `a row that exists and one that does not are indistinguishable`(): Unit =
        withRoutedDb { db, _, bob ->
            runViewTest(url = "/task/9999") {
                setAttribute(PrincipalKey, bob)
                setTaskRoutes(db)

                onNode(hasTag("h1")).assertText("Not found")
                assertEquals("Not found", title())
            }
        }

    @Test
    fun `unsharing a project evicts whoever is holding one of its tasks open`(): Unit =
        withRoutedDb { db, alice, bob ->
            val project = db.transact { db.insert(Project(alice, "Inbox", shared = true)) }
            val task = db.transact { db.insert(Task(alice, "shared plan").also { it.project = project }) }

            runViewTest(url = "/task/${task.id}") {
                setAttribute(PrincipalKey, bob)
                setTaskRoutes(db)
                onNode(hasTag("h1")).assertText("shared plan")

                // Alice unshares. Nothing tells Bob's session anything: the route's subject resolution
                // read `project.shared` through the policy, so writing it invalidates exactly that.
                with(alice) { project.update { shared = false } }
                awaitIdle()

                onNode(hasTag("h1")).assertText("Not found")
                assertEquals("Not found", title())
            }
        }
}

private val PrincipalKey = AttributeKey<User?>("principal")

private val Principals = Principals(PrincipalKey, signIn = "/login")

/** The route table under test, declared the way an application declares it. */
private suspend fun ViewTest.setTaskRoutes(db: Db) {
    setRoutes {
        view("/login") { Page("Sign in") }
        view(
            "/task/{id}",
            subject = { request -> taskOf(db, request) },
            title = { task -> task.title },
            requires = Principals.signedIn,
        ) { task -> Page(task.title) }
    }
}

/**
 * The route's own lookup: gated, so it is null for a row this principal may not read.
 *
 * The whole point of the shape — the route cannot be written any other way, because the path parameter
 * never reaches the view.
 */
private fun taskOf(db: Db, request: RequestContext): Task? {
    val principal = Principals.of(request) ?: return null
    val id = request.pathParams["id"]?.toLongOrNull() ?: return null
    return with(principal) { Tasks.find(db, Id(id)) }
}

@Composable
private fun Page(heading: String) {
    Div { H1 { Text(heading) } }
}

@OptIn(ExperimentalPathApi::class)
private fun withRoutedDb(block: (Db, User, User) -> Unit) {
    val directory = createTempDirectory("jetlin-db")
    try {
        Db.open(directory.resolve("test.db"), schema()).use { db ->
            val alice = db.transact { db.insert(User("Alice")) }
            val bob = db.transact { db.insert(User("Bob")) }
            block(db, alice, bob)
        }
    } finally {
        directory.deleteRecursively()
    }
}
