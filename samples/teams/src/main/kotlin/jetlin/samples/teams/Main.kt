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
 * A two-login sample: what the framework's access control looks like in an application.
 *
 * Open it in two windows, sign in as Alice in one and Bob in the other, and share a todo with the team.
 * It appears in the other window as it is shared and leaves as it is unshared — no polling, no
 * subscription, no invalidation code. The same write that stores it is the one that recomposes whoever
 * could see it.
 *
 * Sign-in here is a cookie naming an email address, which is not authentication and is not pretending to
 * be. It exists so that the interesting half — what a principal may see, and what happens when that changes
 * — is the only thing this sample is about.
 */
fun main() {
    val db = openSeeded()
    val port = System.getenv("PORT")?.toInt() ?: 8081
    // The "external system" is this same process, on this same port. A stub, deliberately, because the
    // thing worth showing is what an application does with data it does not own — not that GitHub exists.
    val hub = Hub("http://127.0.0.1:$port")

    embeddedServer(Netty, port = port) {
        hubService()

        routing {
            // Signing in and out are plain HTTP: a cookie goes on, and the browser is sent back to the
            // page. Nothing about this is the framework's business, which is why it is four lines.
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
             * Where the principal enters the session.
             *
             * Runs on the HTTP call, and again when a socket wakes a hibernated session — which is the
             * point: the principal is recomputed from the connection that arrived rather than trusted from a
             * snapshot that may be minutes old. A role revoked while a laptop was asleep is noticed when
             * it opens.
             */
            attributes { call -> mapOf(PrincipalKey to db.signedInUser(call)) }

            onError = { throwable ->
                // An AccessDenied reaching here means application code asked for something a policy
                // refuses — a bug or an attack, and either way nothing was written.
                println("[teams] ${throwable::class.simpleName}: ${throwable.message}")
            }

            app { route -> Shell(route) }

            view("/login", title = "Sign in · Teams") { SignInPage() }

            // `WithPrincipal` is the bridge §4.4 describes: a context parameter is lexical and does not flow
            // through a `@Composable () -> Unit`, so the principal is put back into scope at the root of each
            // page. Everything below it is an ordinary composable with a principal in scope.
            view("/", title = "Todos · Teams", requires = Principals.signedIn) {
                WithPrincipal { TodoListPage(db) }
            }

            view("/notes", title = "Notes · Teams", requires = Principals.signedIn) {
                WithPrincipal { NotesPage(db) }
            }

            // The same session, reading something nobody here owns. No gate, no policy, no transaction:
            // a value that arrives late is snapshot state like any other, so it recomposes what read it.
            view("/hub", title = "Hub · Teams", requires = Principals.signedIn) {
                WithPrincipal { HubPage(hub) }
            }

            // Entity-bound: the route resolves its own subject through the gated lookup, so a todo this
            // principal may not read is not found — and the title comes from what was resolved, so `<head>`
            // cannot disclose a record the body refused to show.
            view(
                "/todo/{id}",
                subject = { request -> db.todoFor(request) },
                title = { todo -> "${todo.title} · Teams" },
                requires = Principals.signedIn,
            ) { todo -> WithPrincipal { TodoDetailPage(db, todo) } }

            // A failed role check renders not-found rather than a forbidden page: a 403 here would
            // confirm there is an admin panel.
            view("/admin/users", title = "Users · Teams", requires = Principals.where { it.admin }) {
                WithPrincipal { AdminUsersPage(db) }
            }
        }
    }.start(wait = true)
}

/** The principal, as the application's own type. Jetlin knows nothing about it beyond this key. */
val PrincipalKey: AttributeKey<User?> = AttributeKey("principal")

/** Typed guards over that key: `Principals.signedIn`, `Principals.where { it.admin }`. */
val Principals: Principals<User> = Principals(PrincipalKey, signIn = "/login")

/** The cookie this sample calls authentication. A real one would verify something. */
const val SESSION_COOKIE: String = "teams_email"

/** One of the seeded accounts, as the sign-in page offers it. */
class Account(val email: String, val label: String, val note: String? = null)

/**
 * Who [openSeeded] creates.
 *
 * A fixture rather than a query: the sign-in page has no principal, and a page that needed one to render
 * itself would need a hole in the gate to exist.
 */
val SEEDED_ACCOUNTS: List<Account> = listOf(
    Account("alice@example.com", "Alice", "Acme"),
    Account("bob@example.com", "Bob", "Acme"),
    Account("carol@example.com", "Carol", "no team"),
    Account("root@example.com", "Root", "admin"),
)

/**
 * Resolves the signed-in user.
 *
 * `authenticate` is the framework's privileged root, and the one read that has to happen before access
 * control can mean anything: there is no principal yet to check this against.
 */
internal fun Db.signedInUser(call: ApplicationCall): User? {
    val email = call.request.cookies[SESSION_COOKIE] ?: return null
    return authenticate(User::class) { user -> user.email == email }
}

/** The route's own lookup, gated: null for a todo this principal may not read. */
internal fun Db.todoFor(request: RequestContext): Todo? {
    val principal = Principals.of(request) ?: return null
    val id = request.pathParams["id"]?.toLongOrNull() ?: return null
    return with(principal) { Todos.find(this@todoFor, Id(id)) }
}

/**
 * Seeds a fresh database with two teammates, someone on no team, and an admin.
 *
 * In a temporary directory, because this is a sample and starting from a known state is the point. A real
 * application would open a file it keeps, and would have run `./gradlew dbMigrate` against it.
 */
internal fun openSeeded(file: Path = createTempDirectory("jetlin-teams").resolve("teams.db")): Db {
    val db = Db.open(file, JetlinSchema.tables)
    if (db.resident.recordCount > 0) return db

    // One `unsafe` block, logged, because seeding has no principal: the first user in an empty database
    // cannot be created by anybody. Everything the application does afterwards goes through the gate.
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
 * Puts the session's principal back into lexical scope.
 *
 * Context parameters are lexical and do not flow through a `@Composable () -> Unit`, so something has to
 * bridge the gap between "the session has a principal" and "this page has one in scope". One function, at the
 * root of each page; everything below it is an ordinary composable.
 *
 * Renders nothing when there is no principal, which the route guards make unreachable: a page that needs one
 * declares `requires = Principals.signedIn`, and the guard has already redirected by the time this runs.
 */
@Composable
fun WithPrincipal(content: @Composable context(User) () -> Unit) {
    val principal = Principals.of(LocalRequest.current) ?: return
    with(principal) { content() }
}

/** Enough CSS to make the sample legible. Not the interesting part. */
internal val STYLES: String = """
    <style>
      :root { color-scheme: light dark; }
      body { margin: 0; font: 15px/1.5 system-ui, sans-serif; }
      .page { max-width: 44rem; margin: 0 auto; padding: 1rem; }
      .nav { display: flex; gap: .75rem; align-items: center; padding: .5rem 0; flex-wrap: wrap; }
      .nav a { color: inherit; text-decoration: none; }
      .brand { font-weight: 600; }
      .who { margin-left: auto; opacity: .7; }
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
