package jetlin.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import jetlin.html.AttributeKey
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.Text
import jetlin.html.Principals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests guarded routes reached by a deep link, where the guard runs before any composition exists.
 *
 * `GuardTest` in `:jetlin-testing` covers in-session navigation and waking from hibernation. All three
 * paths must agree, and this one has to express the result in HTTP: a redirect is a 302 response, not
 * a page that redirects itself, and a refused route is a 404 that doesn't reveal the route exists.
 */
class GuardedRouteTest {

    @Test
    fun `a signed-out deep link is a redirect, and no session is rendered`(): Unit = testApplication {
        application { guardedApp(principal = null) }
        val client = createClient { followRedirects = false }

        val response = client.get("/todos")

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/login?next=/todos", response.headers[HttpHeaders.Location])
        // No composition was created. Rendering a page for a request that is about to be redirected
        // would cost a whole session.
        assertFalse("jl-session" in response.bodyAsText())
    }

    @Test
    fun `a signed-in deep link renders the page`(): Unit = testApplication {
        application { guardedApp(principal = Person("Alice")) }

        val response = client.get("/todos")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("Todos" in response.bodyAsText(), response.bodyAsText())
    }

    @Test
    fun `a role the principal does not have is a 404, with nothing in it about the route`(): Unit =
        testApplication {
            application { guardedApp(principal = Person("Alice")) }

            val response = client.get("/admin/users")
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue("<title>Not found</title>" in body, body)
            assertFalse("Users" in body, "the page said what it was refusing to show")
        }

    @Test
    fun `an admin gets the admin page`(): Unit = testApplication {
        application { guardedApp(principal = Person("Root", admin = true)) }

        val response = client.get("/admin/users")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("Users" in response.bodyAsText())
    }

    @Test
    fun `an entity-bound route titles the page from the subject it resolved`(): Unit = testApplication {
        application { guardedApp(principal = Person("Alice")) }

        val response = client.get("/note/7")

        assertTrue("<title>Seven</title>" in response.bodyAsText(), response.bodyAsText())
    }

    @Test
    fun `an entity-bound route with no subject says nothing about it, in the title either`(): Unit =
        testApplication {
            application { guardedApp(principal = Person("Alice")) }

            val response = client.get("/note/9")
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue("<title>Not found</title>" in body, body)
            assertFalse("Nine" in body, "the record reached the page despite not being readable")
        }
}

private class Person(val name: String, val admin: Boolean = false)

private val PersonKey = AttributeKey<Person?>("person")

private val Principals = Principals(PersonKey, signIn = "/login")

private class Note(val title: String)

/** Note 9 exists but this principal may not read it, so the policy-checked lookup returns null. */
private val readableNotes = mapOf("7" to Note("Seven"))

private fun io.ktor.server.application.Application.guardedApp(principal: Person?) {
    jetlin {
        attributes { mapOf(PersonKey to principal) }
        view("/login", title = "Sign in") { Page("Sign in") }
        view("/todos", title = "Todos", requires = Principals.signedIn) { Page("Todos") }
        view("/admin/users", title = "Users", requires = Principals.where { it.admin }) { Page("Users") }
        view(
            "/note/{id}",
            subject = { request -> readableNotes[request.pathParams["id"]] },
            title = { note -> note.title },
            requires = Principals.signedIn,
        ) { note -> Page(note.title) }
    }
}

@androidx.compose.runtime.Composable
private fun Page(heading: String) {
    Div { H1 { Text(heading) } }
}
