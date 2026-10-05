package jetlin.samples.teams

import androidx.compose.runtime.Composable
import io.ktor.server.auth.SessionAuthenticationScheme
import io.ktor.server.auth.SessionTransportType
import io.ktor.server.auth.install
import io.ktor.server.auth.session
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.sessions.SameSite
import io.ktor.server.sessions.SessionStorageMemory
import io.ktor.server.sessions.sameSite
import io.ktor.utils.io.ExperimentalKtorApi
import jetlin.db.Db
import jetlin.db.authenticate
import jetlin.db.insertUnchecked
import jetlin.db.unsafe
import jetlin.db.Id
import jetlin.html.AttributeKey
import jetlin.html.LocalRequest
import jetlin.html.RequestContext
import jetlin.html.Principals
import jetlin.server.auth.authentication
import jetlin.server.jetlin
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.Serializable

/**
 * Starts a sample with several users that shows the framework's access control in an application.
 *
 * Open it in two windows, sign in as Alice in one and Bob in the other, and share one of Alice's
 * todos with the team. It appears in Bob's window as soon as it's shared, and disappears when it's
 * unshared. There's no polling, subscription, or invalidation code: the write that stores the change
 * is also what recomposes the sessions that can see the record.
 *
 * Signing in is real session handling: the cookie holds an opaque session ID, the session stays on
 * the server, and signing out ends the user's other tabs. What isn't real is proving who you are.
 * The sign-in page lets anyone pick any seeded account without a password, because the sample is
 * about what each principal can see and what happens when that changes.
 *
 * The server listens on the port in the `PORT` environment variable, or on port 8081.
 */
@OptIn(ExperimentalKtorApi::class)
fun main() {
    val db = openSeeded()
    val port = System.getenv("PORT")?.toInt() ?: 8081
    // The external system is a stub that this process serves on the same port. The sample is about how
    // an application handles data it doesn't own, so a real third-party service would add nothing.
    val hub = Hub("http://127.0.0.1:$port")

    embeddedServer(Netty, port = port) {
        hubService()

        val sessionAuth = db.sessionAuth()
        install(sessionAuth)

        jetlin {
            exposeTestTags = true
            head = STYLES

            // Supply the session's principal from the sign-in cookie. It's looked up for the initial
            // HTTP request, and again when a WebSocket wakes a hibernated session. So the principal is
            // recomputed from the new connection instead of restored from a snapshot that might be out
            // of date. If a role was revoked while a laptop was asleep, the change takes effect when
            // the laptop wakes.
            val auth = authentication(sessionAuth, principal = PrincipalKey)

            onError = { throwable ->
                // An AccessDenied here means application code tried something that a policy refuses,
                // because of a bug or an attack. Either way, nothing was written.
                println("[teams] ${throwable::class.simpleName}: ${throwable.message}")
            }

            app { route -> Shell(hub, auth.rememberControls(), route) }

            view("/login", title = "Sign in · Teams") { SignInPage(auth.rememberControls()) }

            // Context parameters are lexically scoped, and they aren't passed through a
            // `@Composable () -> Unit`. So WithPrincipal puts the principal back in scope at the
            // root of each page, and everything inside it has a principal in scope. See §4.4 of the
            // design plan.
            view("/", title = "Todos · Teams", requires = Principals.signedIn) {
                WithPrincipal { TodoListPage(db) }
            }

            view("/notes", title = "Notes · Teams", requires = Principals.signedIn) {
                WithPrincipal { NotesPage(db) }
            }

            // This page shows data from the external system. No gate, policy, or transaction is
            // involved: a fetched value is ordinary snapshot state, so its arrival recomposes
            // whatever read it.
            view("/hub", title = "Hub · Teams", requires = Principals.signedIn) {
                WithPrincipal { HubPage(hub) }
            }

            // A route for one record. It looks up the todo with the policy-checked lookup, so a
            // todo that this principal can't read shows as not found. The title is computed from
            // the todo it found, so <head> can't reveal a record that the body refused to show.
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

/**
 * The attribute key that holds the principal, as this application's `User` type. Jetlin sees only
 * the key.
 */
val PrincipalKey: AttributeKey<User?> = AttributeKey("principal")

/** Typed guards for the principal, such as `Principals.signedIn` and `Principals.where { it.admin }`. */
val Principals: Principals<User> = Principals(PrincipalKey, signIn = "/login")

/** The name of the sign-in cookie. It holds a session ID, and the [TeamsSession] stays on the server. */
const val SESSION_COOKIE: String = "teams_session"

/**
 * What the sign-in cookie refers to.
 *
 * @property email the signed-in user's email address.
 */
@Serializable
data class TeamsSession(val email: String)

/**
 * A seeded account, as the sign-in page lists it.
 *
 * @property email the email address to sign in with.
 * @property label the name to show.
 * @property note a short description, such as the account's team.
 */
class Account(val email: String, val label: String, val note: String? = null)

/**
 * The accounts that [openSeeded] creates.
 *
 * The sign-in page lists these from a constant instead of querying the database. It has no
 * principal, so a query would need a way around the policy checks.
 */
val SEEDED_ACCOUNTS: List<Account> = listOf(
    Account("alice@example.com", "Alice", "Acme"),
    Account("bob@example.com", "Bob", "Acme"),
    Account("carol@example.com", "Carol", "no team"),
    Account("root@example.com", "Root", "admin"),
)

/**
 * The sign-in scheme: a cookie that holds a session ID, and the user that the session names.
 *
 * `validate` uses `authenticate`, the framework's one unchecked lookup. It needs it because there's
 * no principal yet to check the lookup against. A session whose user no longer exists doesn't
 * validate, and the browser is signed out.
 *
 * The sessions are kept in memory, so a restart signs everyone out. A real application would use
 * `directorySessionStorage` or a shared store.
 */
@OptIn(ExperimentalKtorApi::class)
internal fun Db.sessionAuth(): SessionAuthenticationScheme<TeamsSession, User> =
    session<TeamsSession, User>(SESSION_COOKIE) {
        transport = SessionTransportType.CookieId(SessionStorageMemory()) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.sameSite = SameSite.Lax
        }
        validate { session -> authenticate(User::class) { user -> user.email == session.email } }
    }

/**
 * Looks up the todo for the `/todo/{id}` route. Returns `null` for a todo that this principal can't
 * read.
 */
internal fun Db.todoFor(request: RequestContext): Todo? {
    val principal = Principals.of(request) ?: return null
    val id = request.pathParams["id"]?.toLongOrNull() ?: return null
    return with(principal) { Todos.find(this@todoFor, Id(id)) }
}

/**
 * Creates a new database seeded with two teammates, a user with no team, and an admin.
 *
 * By default, the database goes in a temporary directory, so the sample always starts from the
 * same state. A real application would open a persistent file that `./gradlew dbMigrate` has
 * migrated.
 *
 * @param file the database file. If it already has records, nothing is seeded.
 */
internal fun openSeeded(file: Path = createTempDirectory("jetlin-teams").resolve("teams.db")): Db {
    val db = Db.open(file, JetlinSchema.tables)
    if (db.resident.recordCount > 0) return db

    // Seeding needs `unsafe`, because there's no principal yet: nobody can be allowed to create the
    // first user of an empty database. Everything after this goes through the policy checks.
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
 * Context parameters are lexically scoped, and they aren't passed through a
 * `@Composable () -> Unit`, so the principal stored in the session has to be brought back into scope
 * explicitly. Call this once at the root of each page.
 *
 * If there's no principal, nothing is rendered. In practice that doesn't happen: pages that need a
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
