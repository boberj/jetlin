package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The outcome of checking a route's requirements for a request.
 *
 * This is a return value, not an exception. A view that throws ends the session and reloads the page,
 * which is appropriate for a bug but not for an ordinary outcome like "you need to sign in".
 */
public sealed interface Access {
    public data object Allow : Access

    /**
     * The route does not exist as far as this principal is concerned.
     *
     * Use this as the default refusal instead of a "forbidden" response. A 403 on `/admin/users`
     * reveals that an admin panel exists. Revealing that should be a deliberate choice.
     */
    public data object NotFound : Access

    public data class Redirect(val to: String) : Access
}

/**
 * A requirement a request must meet to reach a route.
 *
 * A guard is evaluated against the session's [RequestContext], which holds the application's
 * principal. It is a pure function of the request and the values `attributes { }` added to it, so it
 * gives the same answer whether it runs in the HTTP layer, inside the composition, or in a test.
 *
 * **Guards are not the security boundary; record policies are.** A guard improves the user experience
 * and avoids rendering a page that would be empty. If a guard is the only thing protecting some data,
 * forgetting it leaks that data.
 */
public fun interface Guard {
    public fun check(request: RequestContext): Access
}

/** Combines two guards. They are checked in order, and the first refusal is returned. */
public infix fun Guard.and(other: Guard): Guard = Guard { request ->
    when (val first = check(request)) {
        Access.Allow -> other.check(request)
        else -> first
    }
}

/**
 * Creates guards for the application's own principal type.
 *
 * Jetlin has no built-in authentication. The application provides the attribute key its principal is
 * stored under, and gets typed guards in return:
 *
 * ```kotlin
 * val PrincipalKey = AttributeKey<User?>("principal")
 * val Principals = Principals(PrincipalKey, signIn = "/login")
 *
 * view("/login", title = "Sign in") { LoginPage() }
 * view("/todos", title = "My todos", requires = Principals.signedIn) { TodoListPage() }
 * view("/admin/users", title = "Users", requires = Principals.where { it.admin }) { AdminUsers() }
 * ```
 */
public class Principals<P : Any>(
    private val key: AttributeKey<P?>,
    private val signIn: String = "/login",
) {
    /** The principal for this request, or null if nobody is signed in. */
    public fun of(request: RequestContext): P? = request[key]

    /**
     * Requires a signed-in principal. Anyone else is redirected to the sign-in page, with the original
     * URL in `next`.
     *
     * This redirects instead of returning not-found because the existence of a page that requires
     * signing in isn't secret; the user is about to authenticate anyway.
     */
    public val signedIn: Guard = Guard { request ->
        if (request[key] != null) Access.Allow else Access.Redirect(signInUrl(request))
    }

    /**
     * Requires a principal that satisfies [predicate].
     *
     * A request with no principal is redirected to sign in. A signed-in principal that fails the
     * predicate gets [Access.NotFound], so the route's existence isn't revealed to them.
     */
    public fun where(predicate: (P) -> Boolean): Guard = Guard { request ->
        val principal = request[key]
        when {
            principal == null -> Access.Redirect(signInUrl(request))
            predicate(principal) -> Access.Allow
            else -> Access.NotFound
        }
    }

    private fun signInUrl(request: RequestContext): String = "$signIn?next=${request.url}"
}

/**
 * The guards from the route table, so a link can be checked against the same rule as its route.
 *
 * Whether a link is shown and whether its route is accessible should be the same decision. Keeping two
 * separate copies of that rule would let them drift apart.
 */
public class RouteGuards(private val routes: List<Pair<RoutePattern, Guard?>>) {
    public fun forUrl(url: String): Guard? {
        val path = url.substringBefore('?')
        return routes.firstOrNull { (pattern, _) -> pattern.match(path) != null }?.second
    }

    public companion object {
        public val None: RouteGuards = RouteGuards(emptyList())
    }
}

public val LocalRouteGuards: ProvidableCompositionLocal<RouteGuards> =
    staticCompositionLocalOf { RouteGuards.None }

/**
 * Composes [content] only if [guard] allows this request.
 *
 * The guard is evaluated inside the composition, so it reacts to changes in the state it reads. A
 * principal's role, for example, is a cell. Revoking the role invalidates this composable, the guard
 * is re-evaluated, and the user is redirected away from the page they're on, without polling or a
 * logout broadcast.
 *
 * For the same reason, one guard covers all three ways of reaching a route:
 *
 * - A deep link: the HTTP layer checks the guard first, and this check agrees with it.
 * - Navigation within a session: the request changes, and this composable recomposes.
 * - A hibernated session waking up: the attributes are recomputed from the new connection, so a role
 *   revoked while the session was hibernated takes effect on the first recomposition.
 */
@Composable
public fun Guarded(
    guard: Guard?,
    notFound: @Composable () -> Unit = { NotFoundPage() },
    content: @Composable () -> Unit,
) {
    val request = LocalRequest.current
    when (val access = guard?.check(request) ?: Access.Allow) {
        Access.Allow -> content()
        Access.NotFound -> {
            DocumentTitle(NOT_FOUND_TITLE)
            notFound()
        }
        is Access.Redirect -> {
            // Render nothing while the redirect happens. This principal isn't allowed to see the page,
            // and even briefly showing it would disclose its contents.
            val navigator = LocalNavigator.current
            LaunchedEffect(access.to) { navigator.replace(access.to) }
        }
    }
}

/**
 * Resolves the record a route refers to, and composes [content] with it.
 *
 * Having the route resolve its own subject prevents a common vulnerability.
 * `view("/todo/{id}") { TodoStore.find(pathParam("id")) }` is an insecure direct object reference:
 * the view looks up whatever id is in the URL. Here, the view never sees the raw path parameter, and
 * [resolve] uses the policy-checked lookup, which returns null for records this principal may not read.
 *
 * A null subject shows the not-found page before [content] is composed and before the document title
 * is set. The title ordering is easy to overlook: if the title were set first, it could reveal a
 * record that the body refused to show.
 *
 * The lookup is reactive like any other read. If a project stops being shared, anyone viewing one of
 * its todos is moved to the not-found page.
 */
@Composable
public fun <T : Any> Subject(
    resolve: (RequestContext) -> T?,
    title: (T) -> String,
    notFound: @Composable () -> Unit = { NotFoundPage() },
    content: @Composable (T) -> Unit,
) {
    val subject = resolve(LocalRequest.current)
    if (subject == null) {
        DocumentTitle(NOT_FOUND_TITLE)
        notFound()
    } else {
        DocumentTitle(title(subject))
        content(subject)
    }
}

/**
 * Composes [content] only if the route at [url] would allow this principal.
 *
 * Use it for navigation links. `IfPermitted("/admin/users") { NavLink("/admin/users") { Text("Users") } }`
 * hides the link using the route's own guard, so the link and the route always agree.
 */
@Composable
public fun IfPermitted(url: String, content: @Composable () -> Unit) {
    val guard = LocalRouteGuards.current.forUrl(url)
    val request = LocalRequest.current
    if (guard == null || guard.check(request) == Access.Allow) content()
}

/** The page shown when a route doesn't exist, or doesn't exist for this principal. */
@Composable
public fun NotFoundPage() {
    Div({ classes("jl-not-found") }) {
        H1 { Text("Not found") }
    }
}

/**
 * Sets the document title from inside the composition.
 *
 * The route table can't always provide the title, because it may depend on the record the route
 * resolved. Setting it from the composition means the title is only computed after the access check.
 * A title computed from a record before that check would leak the record through `<head>`.
 */
@Composable
public fun DocumentTitle(title: String) {
    val holder = LocalDocumentTitle.current
    SideEffect { holder.set(title) }
}

/** Receives titles set by [DocumentTitle]. Provided by the view that hosts the composition. */
public fun interface TitleSink {
    public fun set(title: String?)
}

public val LocalDocumentTitle: ProvidableCompositionLocal<TitleSink> =
    compositionLocalOf { TitleSink { } }

internal const val NOT_FOUND_TITLE: String = "Not found"
