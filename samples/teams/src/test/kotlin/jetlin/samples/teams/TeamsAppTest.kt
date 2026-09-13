package jetlin.samples.teams

import jetlin.db.Db
import jetlin.db.Id
import jetlin.db.unsafe
import jetlin.testing.ViewTest
import jetlin.testing.assertNotDisclosed
import jetlin.testing.click
import jetlin.testing.hasTestTag
import jetlin.testing.hasText
import jetlin.testing.type
import jetlin.testing.recordUpdate
import jetlin.testing.runViewTest
import jetlin.testing.setRoutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The sample as an application: two principals, one database, and what each of them can see.
 *
 * Every test here is a statement about access rather than about markup, which is the only kind of statement
 * worth making twice in a framework and an application. A single-principal test cannot fail the interesting
 * way, so almost every one of these has two.
 */
class TeamsAppTest {

    @Test
    fun `a principal sees their own todos and their team's`(): Unit = withSample { db ->
        runViewTest {
            signedInAs(db, "bob@example.com")

            // Bob's own, plus the one Alice shared with Acme. Not Alice's unshared one, and nothing of
            // Carol's, who is on no team.
            onNode(hasTestTag("todo") and hasText("Review the sample", substring = true)).assertExists()
            onNode(hasTestTag("todo") and hasText("Write the team sample", substring = true)).assertExists()
            assertNotDisclosed("Rehearse the demo", "Nothing to do with Acme")
        }
    }

    @Test
    fun `someone on no team sees only their own`(): Unit = withSample { db ->
        runViewTest {
            signedInAs(db, "carol@example.com")

            onAll(hasTestTag("todo")).assertCount(1)
            onNode(hasTestTag("todo") and hasText("Nothing to do with Acme", substring = true)).assertExists()
            assertNotDisclosed("Write the team sample", "Review the sample", "Rehearse the demo")
        }
    }

    @Test
    fun `sharing a todo puts it on a teammate's open page, and unsharing takes it away`(): Unit =
        withSample { db ->
            val alice = db.user("alice@example.com")
            val rehearse = with(alice) { Todos.all(db).single { it.title == "Rehearse the demo" } }

            runViewTest {
                signedInAs(db, "bob@example.com")
                onAll(hasTestTag("todo")).assertCount(2)

                // Alice shares it from her own session, somewhere else entirely.
                val arrival = recordUpdate { with(alice) { rehearse.update { team = alice.team } } }

                onAll(hasTestTag("todo")).assertCount(3)
                onNode(hasTestTag("todo") and hasText("Rehearse the demo", substring = true)).assertExists()
                // A share arriving from another session redraws the list and nothing else: the chrome
                // above it recomposes to the same markup and emits nothing.
                arrival.assertUntouched(hasTestTag("principal"), hasTestTag("draft"))

                with(alice) { rehearse.update { team = null } }
                awaitIdle()

                onAll(hasTestTag("todo")).assertCount(2)
                assertNotDisclosed("Rehearse the demo")
            }
        }

    @Test
    fun `a teammate can see a shared todo and cannot change it`(): Unit = withSample { db ->
        runViewTest {
            signedInAs(db, "bob@example.com")

            // Alice's, shared with Acme: visible, and the checkbox says what the policy would say.
            within(onNode(hasTestTag("todo") and hasText("Write the team sample", substring = true))) {
                onNode(hasTestTag("done")).assertDisabled()
                onAll(hasTestTag("share")).assertCount(0)
            }
        }
    }

    @Test
    fun `a private note is private`(): Unit = withSample { db ->
        runViewTest(url = "/notes") {
            signedInAs(db, "alice@example.com")

            onNode(hasTestTag("note")).assertText("Only Alice can read this")
            assertNotDisclosed("Only Carol can read this")
        }
    }

    @Test
    fun `the admin page is not there for anyone else, and does not say so`(): Unit = withSample { db ->
        runViewTest(url = "/admin/users") {
            signedInAs(db, "alice@example.com")

            assertEquals("Not found", title())
            assertNotDisclosed("bob@example.com", "carol@example.com")
        }
    }

    @Test
    fun `an admin reaches the admin page`(): Unit = withSample { db ->
        runViewTest(url = "/admin/users") {
            signedInAs(db, "root@example.com")

            onAll(hasTestTag("user")).assertCount(4)
        }
    }

    @Test
    fun `losing admin moves the principal off the admin page`(): Unit = withSample { db ->
        val root = db.user("root@example.com")

        runViewTest(url = "/admin/users") {
            signedInAs(db, "root@example.com")
            onAll(hasTestTag("user")).assertCount(4)

            // Someone else demotes them. Nothing tells this session anything; the guard read `admin`.
            with(root) { root.update { admin = false } }
            awaitIdle()

            assertEquals("Not found", title())
            onAll(hasTestTag("user")).assertCount(0)
        }
    }

    @Test
    fun `another principal's todo is not found, and its title stays out of the head`(): Unit =
        withSample { db ->
            val alice = db.user("alice@example.com")
            val secret = with(alice) { Todos.all(db).single { it.title == "Rehearse the demo" } }

            runViewTest(url = "/todo/${secret.id}") {
                signedInAs(db, "carol@example.com")

                assertEquals("Not found", title())
                assertNotDisclosed("Rehearse the demo")
            }
        }

    @Test
    fun `a principal's own todo opens, titled after it`(): Unit = withSample { db ->
        val alice = db.user("alice@example.com")
        val own = with(alice) { Todos.all(db).single { it.title == "Rehearse the demo" } }

        runViewTest(url = "/todo/${own.id}") {
            signedInAs(db, "alice@example.com")

            onNode(hasTestTag("title")).assertText("Rehearse the demo")
            assertEquals("Rehearse the demo · Teams", title())
        }
    }

    @Test
    fun `archiving is refused for anyone who is not an admin`(): Unit = withSample { db ->
        val alice = db.user("alice@example.com")
        val own = with(alice) { Todos.all(db).single { it.title == "Rehearse the demo" } }

        runViewTest(url = "/todo/${own.id}") {
            signedInAs(db, "alice@example.com")

            // The control says so...
            onNode(hasTestTag("archived")).assertDisabled()
            // ...and the write is refused even if something sends it anyway.
            assertFailsWith<jetlin.db.AccessDenied> { with(alice) { own.update { archived = true } } }
            assertTrue(!own.archived)
        }
    }

    @Test
    fun `adding a todo stores it, for the principal who added it`(): Unit = withSample { db ->
        runViewTest {
            signedInAs(db, "carol@example.com")

            onNode(hasTestTag("draft")).type("Water the plants")
            onNode(hasTestTag("add")).click()

            onNode(hasTestTag("todo") and hasText("Water the plants", substring = true)).assertExists()
            val carol = db.user("carol@example.com")
            assertEquals(
                listOf("Nothing to do with Acme", "Water the plants"),
                with(carol) { Todos.all(db).map { it.title }.sorted() },
            )
        }
    }
}

/** Signs in as [email] and composes the application's real route table. */
private suspend fun ViewTest.signedInAs(db: Db, email: String) {
    setAttribute(PrincipalKey, db.user(email))
    setRoutes {
        view("/login") { SignInPage() }
        view("/", requires = Principals.signedIn) { WithPrincipal { TodoListPage(db) } }
        view("/notes", requires = Principals.signedIn) { WithPrincipal { NotesPage(db) } }
        view(
            "/todo/{id}",
            subject = { request -> db.todoFor(request) },
            title = { todo -> "${todo.title} · Teams" },
            requires = Principals.signedIn,
        ) { todo -> WithPrincipal { TodoDetailPage(db, todo) } }
        view("/admin/users", requires = Principals.where { it.admin }) { WithPrincipal { AdminUsersPage(db) } }
        app { route -> Shell(route) }
    }
}
