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
 * Tests routes that look up their own record.
 *
 * This design prevents insecure direct object references, such as requesting `/todo/42` when todo
 * 42 belongs to someone else. The route performs the policy-checked lookup itself, and the view
 * never receives the raw path parameter, so it can't look up an arbitrary ID.
 *
 * Every test checks the title separately. `<head>` is rendered before the body, so a title computed
 * from a record would reveal it even if the body refused to show it.
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
                    "the record's title reached <head> before anything checked whether Bob may read it",
                )
            }
        }

    @Test
    fun `a record that exists and one that does not are indistinguishable`(): Unit =
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

                // Alice unshares the project. Nothing notifies Bob's session directly. Looking up the
                // route's subject read `project.shared` through the policy, so the write invalidates it.
                with(alice) { project.update { shared = false } }
                awaitIdle()

                onNode(hasTag("h1")).assertText("Not found")
                assertEquals("Not found", title())
            }
        }
}

private val PrincipalKey = AttributeKey<User?>("principal")

private val Principals = Principals(PrincipalKey, signIn = "/login")

/** Sets up the routes under test, declared as an application would declare them. */
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
 * Looks up the route's record. The lookup is policy-checked, so it returns `null` for a record that
 * this principal can't read.
 *
 * The view only ever receives the result of this lookup, never the path parameter itself.
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
