package jetlin.samples.teams

import jetlin.db.Db
import jetlin.db.Id
import jetlin.db.unsafe
import jetlin.testing.ViewTest
import jetlin.testing.assertNotDisclosed
import jetlin.testing.check
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
 * Tests the sample as an application: two principals, one database, and what each of them can see.
 *
 * These tests are about access, not markup. The framework's own tests cover the same properties, but
 * checking them again at the application level is worthwhile. Most tests use two principals,
 * because access bugs usually can't show up with only one.
 */
class TeamsAppTest {

    @Test
    fun `a principal sees their own todos and their team's`(): Unit = withSample { db ->
        runViewTest {
            signedInAs(db, "bob@example.com")

            // Bob's own todo, and the one Alice shared with Acme. Not Alice's unshared todo, and
            // nothing of Carol's, because she's on no team.
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

                // Alice shares the todo from her own, separate session.
                val arrival = recordUpdate { with(alice) { rehearse.update { team = alice.team } } }

                onAll(hasTestTag("todo")).assertCount(3)
                onNode(hasTestTag("todo") and hasText("Rehearse the demo", substring = true)).assertExists()
                // A share from another session updates the list and nothing else. The chrome
                // recomposes, but it produces the same markup, so it emits no ops.
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

            // Alice's todo, shared with Acme: visible, with the checkbox disabled, as the policy
            // requires.
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

            // Someone else removes their admin role. Nothing notifies this session. The guard read
            // `admin`.
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

            // The control is disabled...
            onNode(hasTestTag("archived")).assertDisabled()
            // ...and the write is refused even if the event is sent anyway.
            assertFailsWith<jetlin.db.AccessDenied> { with(alice) { own.update { archived = true } } }
            assertTrue(!own.archived)
        }
    }

    @Test
    fun `an admin sees every todo and can change one they do not own`(): Unit = withSample { db ->
        val carol = db.user("carol@example.com")
        val carols = with(carol) { Todos.all(db).single() }

        runViewTest {
            signedInAs(db, "root@example.com")

            // Root is on no team, so this is the policy admitting admins, not sharing.
            onAll(hasTestTag("todo")).assertCount(4)
            within(onNode(hasTestTag("todo") and hasText("Nothing to do with Acme", substring = true))) {
                onNode(hasTestTag("done")).assertEnabled().check()
            }
            assertTrue(carols.done)
        }
    }

    @Test
    fun `an admin can archive a todo they do not own`(): Unit = withSample { db ->
        val carol = db.user("carol@example.com")
        val carols = with(carol) { Todos.all(db).single() }

        runViewTest(url = "/todo/${carols.id}") {
            signedInAs(db, "root@example.com")

            onNode(hasTestTag("archived")).assertEnabled().check()
            assertTrue(carols.archived)
        }
    }

    @Test
    fun `losing admin disables archiving on a page that is already open`(): Unit = withSample { db ->
        val root = db.user("root@example.com")
        val bob = db.user("bob@example.com")
        val shared = with(bob) { Todos.all(db).single { it.title == "Write the team sample" } }
        // Bob can read Alice's todo through the team, so he can still see it after losing admin.
        // An admin reading through the admin rule alone would see the page become "Not found".
        with(root) { bob.update { admin = true } }

        runViewTest(url = "/todo/${shared.id}") {
            signedInAs(db, "bob@example.com")
            onNode(hasTestTag("archived")).assertEnabled()

            // The page asked `canUpdate`, which ran the policy, which read `admin`. So this write
            // recomposes the checkbox with nothing notifying the page.
            with(root) { bob.update { admin = false } }

            onNode(hasTestTag("archived")).assertDisabled()
        }
    }

    @Test
    fun `a todo can't be shared with a team its owner isn't on`(): Unit = withSample { db ->
        val root = db.user("root@example.com")
        val alice = db.user("alice@example.com")
        val globex = with(root) { Teams.all(db).add(Team("Globex")) }
        val rehearse = with(alice) { Todos.all(db).single { it.title == "Rehearse the demo" } }

        // Alice can change her todo, and sharing only sets a column, so every check before the write
        // passes. The todo as it would end up is one she couldn't have created, so it's refused.
        assertFailsWith<jetlin.db.AccessDenied> { with(alice) { rehearse.update { team = globex } } }
        assertEquals(null, rehearse.team)

        // Her own team is fine.
        with(alice) { rehearse.update { team = alice.team } }
        assertEquals(alice.team, rehearse.team)
    }

    @Test
    fun `a user can't make themselves an admin`(): Unit = withSample { db ->
        val bob = db.user("bob@example.com")

        assertFailsWith<jetlin.db.AccessDenied> { with(bob) { bob.update { admin = true } } }

        assertTrue(!bob.admin)
        assertEquals(2, with(bob) { Todos.all(db).size }, "still only his own todo and the one shared with Acme")
    }

    @Test
    fun `a user can't join a team by reaching its record through someone else`(): Unit = withSample { db ->
        val alice = db.user("alice@example.com")
        val carol = db.user("carol@example.com")

        // Every user is readable, and a reference isn't checked when it's followed, so Carol can get
        // hold of Acme through Alice. Holding it doesn't let her join it.
        val acme = with(carol) { Users.all(db).single { it == alice }.team }
        assertFailsWith<jetlin.db.AccessDenied> { with(carol) { carol.update { team = acme } } }

        assertEquals(null, carol.team)
        assertEquals(listOf("Nothing to do with Acme"), with(carol) { Todos.all(db).map { it.title } })
    }

    @Test
    fun `a user can rename themselves, and an admin can change what they may do`(): Unit = withSample { db ->
        val root = db.user("root@example.com")
        val carol = db.user("carol@example.com")
        val acme = db.user("alice@example.com").team

        with(carol) { carol.update { name = "Caroline" } }
        with(root) { carol.update { team = acme } }

        assertEquals("Caroline", carol.name)
        assertEquals(acme, carol.team)
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
        // These tests don't use the external system. They're about stored data and who can see it.
        app { route -> Shell(hub = null, content = route) }
    }
}
