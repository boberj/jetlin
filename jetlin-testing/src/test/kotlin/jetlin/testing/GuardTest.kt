package jetlin.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jetlin.html.AttributeKey
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.IfPermitted
import jetlin.html.Text
import jetlin.html.Viewers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a route does about a viewer it does not want.
 *
 * Three ways into a route and they have to agree: a deep link, an in-session navigation, and a
 * hibernated session waking up. The third is the one most likely to be missed — a session resumes on
 * whatever URL it was on, so the guard has to be re-evaluated on wake and not only on entry.
 *
 * These use a plain state-holding object as the principal rather than a database, because nothing here
 * is about storage: what makes eviction work is that a guard reads live state.
 */
class GuardTest {

    @Test
    fun `a route with no guard composes its view`(): Unit = runViewTest(url = "/") {
        setRoutes { view("/") { Page("Home") } }

        onNode(hasTag("h1")).assertText("Home")
    }

    @Test
    fun `a signed-out visitor is sent to sign in, carrying where they were going`(): Unit =
        runViewTest(url = "/todos") {
            setRoutes {
                view("/login") { Page("Sign in") }
                view("/todos", requires = Viewers.signedIn) { Page("Todos") }
            }

            assertUrl("/login?next=/todos")
            onNode(hasTag("h1")).assertText("Sign in")
        }

    @Test
    fun `a signed-in visitor reaches the page`(): Unit = runViewTest(url = "/todos") {
        setAttribute(PersonKey, Person("Alice"))
        setRoutes {
            view("/login") { Page("Sign in") }
            view("/todos", requires = Viewers.signedIn) { Page("Todos") }
        }

        assertUrl("/todos")
        onNode(hasTag("h1")).assertText("Todos")
    }

    @Test
    fun `a failed role check renders not found rather than admitting the route exists`(): Unit =
        runViewTest(url = "/admin/users") {
            setAttribute(PersonKey, Person("Alice"))
            setRoutes {
                view("/") { Page("Home") }
                view("/admin/users", requires = Viewers.where { it.admin }) { Page("Users") }
            }

            // Still on the URL: not found is a page, not a redirect, and a 403 would confirm there is an
            // admin panel here.
            assertUrl("/admin/users")
            onNode(hasTag("h1")).assertText("Not found")
            assertEquals("Not found", title(), "the title must not say what the page would have been")
        }

    @Test
    fun `an admin reaches the admin page`(): Unit = runViewTest(url = "/admin/users") {
        setAttribute(PersonKey, Person("Root", admin = true))
        setRoutes {
            view("/") { Page("Home") }
            view("/admin/users", requires = Viewers.where { it.admin }) { Page("Users") }
        }

        onNode(hasTag("h1")).assertText("Users")
    }

    @Test
    fun `navigating into a route the viewer may not reach lands on not found`(): Unit =
        runViewTest(url = "/") {
            setAttribute(PersonKey, Person("Alice"))
            setRoutes {
                view("/") { Page("Home") }
                view("/admin/users", requires = Viewers.where { it.admin }) { Page("Users") }
            }

            navigate("/admin/users")

            onNode(hasTag("h1")).assertText("Not found")
        }

    @Test
    fun `losing a role moves the viewer off the page they are sitting on`(): Unit =
        runViewTest(url = "/admin/users") {
            val root = Person("Root", admin = true)
            setAttribute(PersonKey, root)
            setRoutes {
                view("/") { Page("Home") }
                view("/login") { Page("Sign in") }
                view("/admin/users", requires = Viewers.where { it.admin }) { Page("Users") }
            }
            onNode(hasTestTag("page")).assertExists()

            // An admin being demoted by someone else, with nothing told about this session. The guard
            // read `admin`, which is snapshot state, so this invalidates the guard and nothing else.
            root.admin = false
            awaitIdle()

            onNode(hasTag("h1")).assertText("Not found")
        }

    @Test
    fun `a guard is re-evaluated when a hibernated session wakes`(): Unit =
        runViewTest(url = "/admin/users") {
            setAttribute(PersonKey, Person("Root", admin = true))
            setRoutes {
                view("/") { Page("Home") }
                view("/login") { Page("Sign in") }
                view("/admin/users", requires = Viewers.where { it.admin }) { Page("Users") }
            }
            onNode(hasTag("h1")).assertText("Users")

            // The role is revoked while the session is asleep: the viewer is recomputed from the
            // connection that wakes it, not restored from a snapshot that may be minutes old.
            setAttribute(PersonKey, Person("Root", admin = false))
            hibernateAndRestore()

            assertUrl("/admin/users")
            onNode(hasTag("h1")).assertText("Not found")
        }

    @Test
    fun `a session that wakes with no viewer at all is sent to sign in`(): Unit =
        runViewTest(url = "/todos") {
            setAttribute(PersonKey, Person("Alice"))
            setRoutes {
                view("/login") { Page("Sign in") }
                view("/todos", requires = Viewers.signedIn) { Page("Todos") }
            }
            onNode(hasTag("h1")).assertText("Todos")

            setAttribute(PersonKey, null)
            hibernateAndRestore()

            assertUrl("/login?next=/todos")
        }

    @Test
    fun `a link is hidden by the same guard that blocks the route`(): Unit = runViewTest(url = "/") {
        setAttribute(PersonKey, Person("Alice"))
        setRoutes {
            view("/") {
                Div {
                    IfPermitted("/admin/users") { Div({ testTag("admin-link") }) { Text("Users") } }
                }
            }
            view("/admin/users", requires = Viewers.where { it.admin }) { Page("Users") }
        }

        onAll(hasTestTag("admin-link")).assertCount(0)
    }

    @Test
    fun `an admin sees the link`(): Unit = runViewTest(url = "/") {
        setAttribute(PersonKey, Person("Root", admin = true))
        setRoutes {
            view("/") {
                Div {
                    IfPermitted("/admin/users") { Div({ testTag("admin-link") }) { Text("Users") } }
                }
            }
            view("/admin/users", requires = Viewers.where { it.admin }) { Page("Users") }
        }

        onNode(hasTestTag("admin-link")).assertExists()
    }

    @Test
    fun `an entity-bound route composes its subject and titles the page from it`(): Unit =
        runViewTest(url = "/note/7") {
            setAttribute(PersonKey, Person("Alice"))
            setRoutes {
                view("/") { Page("Home") }
                view(
                    "/note/{id}",
                    subject = { request -> notes[request.pathParams["id"]] },
                    title = { note -> note.title },
                ) { note -> Page(note.title) }
            }

            onNode(hasTag("h1")).assertText("Seven")
            assertEquals("Seven", title())
        }

    @Test
    fun `an entity-bound route whose subject is absent says nothing about it`(): Unit =
        runViewTest(url = "/note/9") {
            setAttribute(PersonKey, Person("Alice"))
            setRoutes {
                view("/") { Page("Home") }
                view(
                    "/note/{id}",
                    // Absent because this viewer may not read it, which is the same answer as "no such
                    // note" on purpose: a distinguishable refusal tells whoever is probing that it exists.
                    subject = { request -> notes[request.pathParams["id"]] },
                    title = { note -> note.title },
                ) { note -> Page(note.title) }
            }

            onNode(hasTag("h1")).assertText("Not found")
            assertEquals("Not found", title(), "the title is rendered before the body and must not leak")
        }
}

/** A principal that holds state, which is all a guard needs it to be. */
private class Person(val name: String, admin: Boolean = false) {
    var admin: Boolean by mutableStateOf(admin)

    override fun toString(): String = name
}

private val PersonKey = AttributeKey<Person?>("person")

private val Viewers = Viewers(PersonKey, signIn = "/login")

private class Note(val title: String)

private val notes = mapOf("7" to Note("Seven"))

@Composable
private fun Page(heading: String) {
    Div({ testTag("page") }) { H1 { Text(heading) } }
}
