package jetlin.samples.teams

import io.ktor.server.application.ApplicationCall
import androidx.compose.runtime.Composable
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import jetlin.db.Db
import jetlin.db.authenticate
import jetlin.db.insertUnchecked
import jetlin.db.unsafe
import jetlin.db.Id
import jetlin.html.AttributeKey
import jetlin.html.LocalRequest
import jetlin.html.RequestContext
import jetlin.html.Principals
import jetlin.server.jetlin
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/**
 * A sample with two users, showing the framework's access control in an application.
 *
 * Open it in two windows, sign in as Alice in one and Bob in the other, then share one of Alice's todos
 * with the team. It appears in Bob's window as soon as it is shared and disappears when it is unshared.
 * There is no polling, subscription or invalidation code: the write that stores the change is also
 * what recomposes the sessions that can see the record.
 *
 * Signing in just sets a cookie containing an email address. That is not real authentication, and it
 * isn't meant to be. The sample is about what each principal can see and what happens when that
 * changes, not about how users prove who they are.
 */
fun main() {
    val db = openSeeded()
    val port = System.getenv("PORT")?.toInt() ?: 8081
    // The "external system" is a stub served by this same process on the same port. The sample is about
    // how an application handles data it doesn't own, so a real third-party service would add nothing.
    val hub = Hub("http://127.0.0.1:$port")

    embeddedServer(Netty, port = port) {
        hubService()

        routing {
            // Signing in and out are ordinary HTTP routes that set or clear a cookie and redirect. The
            // framework isn't involved.
            get("/signin/{email}") {
                call.response.cookies.append(SESSION_COOKIE, call.parameters["email"].orEmpty(), path = "/")
                call.respondRedirect("/")
            }
            get("/signout") {
                call.response.cookies.append(SESSION_COOKIE, "", path = "/", maxAge = 0)
                call.respondRedirect("/login")
            }
        }

        jetlin {
            exposeTestTags = true
            head = STYLES

            /**
             * Supplies the session's principal.
             *
             * This runs for the initial HTTP request, and again when a WebSocket wakes a hibernated
             * session. The principal is therefore recomputed from the new connection instead of being
             * restored from a snapshot that may be out of date. If a role was revoked while a laptop was
             * asleep, the change takes effect when the laptop wakes.
             */
            attributes { call -> mapOf(PrincipalKey to db.signedInUser(call)) }

            onError = { throwable ->
                // An AccessDenied here means application code attempted something a policy refuses,
                // either because of a bug or an attack. In both cases nothing was written.
                println("[teams] ${throwable::class.simpleName}: ${throwable.message}")
            }

            app { route -> Shell(hub, route) }

            view("/login", title = "Sign in · Teams") { SignInPage() }

            // Context parameters are lexically scoped and aren't passed through a `@Composable () -> Unit`,
            // so `WithPrincipal` puts the principal back in scope at the root of each page (see §4.4 of the
            // design plan). Everything inside it has a principal in scope.
            view("/", title = "Todos · Teams", requires = Principals.signedIn) {
                WithPrincipal { TodoListPage(db) }
            }

            view("/notes", title = "Notes · Teams", requires = Principals.signedIn) {
                WithPrincipal { NotesPage(db) }
            }

            // Shows data from the external system. There is no gate, policy or transaction involved: a
            // fetched value is ordinary snapshot state, so its arrival recomposes whatever read it.
            view("/hub", title = "Hub · Teams", requires = Principals.signedIn) {
                WithPrincipal { HubPage(hub) }
            }

            // An entity-bound route. It resolves the todo through the policy-checked lookup, so a todo this
            // principal may not read shows as not found. The title is computed from the resolved todo, so
            // `<head>` can't reveal a record the body refused to show.
            view(
                "/todo/{id}",
                subject = { request -> db.todoFor(request) },
                title = { todo -> "${todo.title} · Teams" },
                requires = Principals.signedIn,
            ) { todo -> WithPrincipal { TodoDetailPage(db, todo) } }

            // A failed role check shows the not-found page instead of a forbidden page, because a 403
            // would confirm that an admin panel exists.
            view("/admin/users", title = "Users · Teams", requires = Principals.where { it.admin }) {
                WithPrincipal { AdminUsersPage(db) }
            }
        }
    }.start(wait = true)
}

/** The attribute key holding the principal, as this application's `User` type. Jetlin only sees the key. */
val PrincipalKey: AttributeKey<User?> = AttributeKey("principal")

/** Typed guards for the principal, such as `Principals.signedIn` and `Principals.where { it.admin }`. */
val Principals: Principals<User> = Principals(PrincipalKey, signIn = "/login")

/** The cookie this sample uses as a stand-in for authentication. It verifies nothing. */
const val SESSION_COOKIE: String = "teams_email"

/** A seeded account, as listed on the sign-in page. */
class Account(val email: String, val label: String, val note: String? = null)

/**
 * The accounts that [openSeeded] creates.
 *
 * The sign-in page lists these from a constant instead of querying the database. It has no principal,
 * so a query would need a way around the policy checks.
 */
val SEEDED_ACCOUNTS: List<Account> = listOf(
    Account("alice@example.com", "Alice", "Acme"),
    Account("bob@example.com", "Bob", "Acme"),
    Account("carol@example.com", "Carol", "no team"),
    Account("root@example.com", "Root", "admin"),
)

/**
 * Looks up the signed-in user from the session cookie.
 *
 * This uses `authenticate`, the framework's one unchecked lookup. It is needed here because there is
 * no principal yet to check the lookup against.
 */
internal fun Db.signedInUser(call: ApplicationCall): User? {
    val email = call.request.cookies[SESSION_COOKIE] ?: return null
    return authenticate(User::class) { user -> user.email == email }
}

/** The `/todo/{id}` route's lookup. Returns null for a todo this principal may not read. */
internal fun Db.todoFor(request: RequestContext): Todo? {
    val principal = Principals.of(request) ?: return null
    val id = request.pathParams["id"]?.toLongOrNull() ?: return null
    return with(principal) { Todos.find(this@todoFor, Id(id)) }
}

/**
 * Creates a new database seeded with two teammates, a user with no team, and an admin.
 *
 * The database goes in a temporary directory so the sample always starts from the same state. A real
 * application would open a persistent file that `./gradlew dbMigrate` has been run against.
 */
internal fun openSeeded(file: Path = createTempDirectory("jetlin-teams").resolve("teams.db")): Db {
    val db = Db.open(file, JetlinSchema.tables)
    if (db.resident.recordCount > 0) return db

    // Seeding needs `unsafe` (which logs) because there is no principal yet: no one can be allowed to
    // create the first user of an empty database. Everything after this goes through the policy checks.
    unsafe("seeding the sample database") {
        db.transact {
            val acme = db.insertUnchecked(Team("Acme"))
            val alice = db.insertUnchecked(User("Alice", "alice@example.com").also { it.team = acme })
            val bob = db.insertUnchecked(User("Bob", "bob@example.com").also { it.team = acme })
            val carol = db.insertUnchecked(User("Carol", "carol@example.com"))
            db.insertUnchecked(User("Root", "root@example.com", admin = true))

            db.insertUnchecked(Todo(alice, "Write the team sample").also { it.team = acme })
            db.insertUnchecked(Todo(alice, "Rehearse the demo"))
            db.insertUnchecked(Todo(bob, "Review the sample").also { it.team = acme })
            db.insertUnchecked(Todo(carol, "Nothing to do with Acme"))
            db.insertUnchecked(Note(alice, "Only Alice can read this"))
            db.insertUnchecked(Note(carol, "Only Carol can read this"))
        }
    }
    return db
}

/**
 * Makes the session's principal available as a context parameter to [content].
 *
 * Context parameters are lexically scoped and are not passed through a `@Composable () -> Unit`, so the
 * principal stored in the session has to be brought back into scope explicitly. Call this once at the
 * root of each page.
 *
 * If there is no principal, nothing is rendered. In practice that doesn't happen: pages that need a
 * principal declare `requires = Principals.signedIn`, so the guard has already redirected.
 */
@Composable
fun WithPrincipal(content: @Composable context(User) () -> Unit) {
    val principal = Principals.of(LocalRequest.current) ?: return
    with(principal) { content() }
}

/** Minimal styling for the sample. */
internal val STYLES: String = """
    <style>
      :root { color-scheme: light dark; }
      body { margin: 0; font: 15px/1.5 system-ui, sans-serif; }
      .page { max-width: 44rem; margin: 0 auto; padding: 1rem; }
      .nav { display: flex; gap: .75rem; align-items: center; padding: .5rem 0; flex-wrap: wrap; }
      .nav a { color: inherit; text-decoration: none; }
      .brand { font-weight: 600; }
      .who { margin-left: auto; opacity: .7; }
      .banner { border: 1px solid color-mix(in srgb, currentColor 15%, transparent); border-radius: .5rem;
              padding: .4rem .75rem; margin: .25rem 0; font-size: .9em; opacity: .85; }
      .card { border: 1px solid color-mix(in srgb, currentColor 15%, transparent); border-radius: .5rem;
              padding: 1rem; margin: .75rem 0; }
      .row { display: flex; gap: .5rem; align-items: center; margin: .5rem 0; }
      .input { flex: 1; padding: .4rem .5rem; }
      .todos, .notes, .people { list-style: none; margin: 0; padding: 0; }
      .todo, .note, .person { display: flex; gap: .5rem; align-items: center; padding: .35rem 0;
              border-bottom: 1px solid color-mix(in srgb, currentColor 8%, transparent); }
      .todo-text { color: inherit; }
      .muted { opacity: .6; font-size: .9em; }
      .badge { font-size: .75em; padding: .1rem .4rem; border-radius: .6rem;
              background: color-mix(in srgb, currentColor 12%, transparent); }
      .link { background: none; border: 0; color: inherit; text-decoration: underline; cursor: pointer; }
      .btn { padding: .4rem .7rem; }
    </style>
""".trimIndent()
