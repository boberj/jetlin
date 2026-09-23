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
 * This is a return value, not an exception. A view that throws ends the session and reloads the
 * page, which suits a bug but not an ordinary outcome like "you need to sign in."
 */
public sealed interface Access {
    /** The request may reach the route. */
    public data object Allow : Access

    /**
     * As far as this principal can tell, the route doesn't exist.
     *
     * Use this as the default refusal instead of a "forbidden" response. A `403` on `/admin/users`
     * reveals that an admin panel exists, and revealing that should be a deliberate choice.
     */
    public data object NotFound : Access

    /** The request should go to the URL [to] instead, for example a sign-in page. */
    public data class Redirect(val to: String) : Access
}

/**
 * A requirement a request must meet to reach a route.
 *
 * A guard checks the session's [RequestContext], which holds the application's principal. It's a
 * pure function of the request and the values that `attributes { }` added to it, so it gives the
 * same answer in the HTTP layer, in the composition, and in a test.
 *
 * Guards aren't the security boundary. Record policies are. A guard improves the user experience
 * and avoids rendering a page that would be empty. If a guard is the only thing protecting some
 * data, forgetting the guard leaks the data.
 */
public fun interface Guard {
    /** Returns whether [request] may reach the route, and what happens to it if not. */
    public fun check(request: RequestContext): Access
}

/** Returns a guard that requires both guards. It checks them in order and returns the first refusal. */
public infix fun Guard.and(other: Guard): Guard = Guard { request ->
    when (val first = check(request)) {
        Access.Allow -> other.check(request)
        else -> first
    }
}

/**
 * Creates guards for the application's own principal type.
 *
 * Jetlin has no built-in authentication. The application provides the attribute key that holds its
 * principal, and gets typed guards in return:
 *
 * ```kotlin
 * val PrincipalKey = AttributeKey<User?>("principal")
 * val Principals = Principals(PrincipalKey, signIn = "/login")
 *
 * view("/login", title = "Sign in") { LoginPage() }
 * view("/todos", title = "My todos", requires = Principals.signedIn) { TodoListPage() }
 * view("/admin/users", title = "Users", requires = Principals.where { it.admin }) { AdminUsers() }
 * ```
 *
 * @param key the attribute key that holds the principal, or `null` when nobody is signed in.
 * @param signIn the path of the sign-in page.
 */
public class Principals<P : Any>(
    private val key: AttributeKey<P?>,
    private val signIn: String = "/login",
) {
    /** Returns the principal for [request], or `null` if nobody is signed in. */
    public fun of(request: RequestContext): P? = request[key]

    /**
     * Requires a signed-in principal.
     *
     * Anyone else is redirected to the sign-in page, with the original URL in the `next` query
     * parameter. This guard redirects instead of returning [Access.NotFound], because a page that
     * requires signing in isn't a secret: the user is about to sign in anyway.
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

    /** Returns the sign-in URL, with the requested URL in `next`. */
    private fun signInUrl(request: RequestContext): String = "$signIn?next=${request.url}"
}

/**
 * The guards from the route table, so a link can be checked against the same rule as its route.
 *
 * Whether a link is shown and whether its route is accessible should be one decision. Two separate
 * copies of the rule could drift apart.
 *
 * @param routes each route's pattern and guard, in the order the route table matches them.
 */
public class RouteGuards(private val routes: List<Pair<RoutePattern, Guard?>>) {
    /** Returns the guard of the first route that matches [url], or `null` if it has none. */
    public fun forUrl(url: String): Guard? {
        val path = url.substringBefore('?')
        return routes.firstOrNull { (pattern, _) -> pattern.match(path) != null }?.second
    }

    public companion object {
        /** A route table with no guards. */
        public val None: RouteGuards = RouteGuards(emptyList())
    }
}

/** The route table's guards, which [IfPermitted] checks. The server provides them. */
public val LocalRouteGuards: ProvidableCompositionLocal<RouteGuards> =
    staticCompositionLocalOf { RouteGuards.None }

/**
 * Composes [content] only if [guard] allows this request.
 *
 * The guard runs inside the composition, so it reacts to changes in the state it reads. For
 * example, a principal's role is a cell. Revoking the role invalidates this composable, the guard
 * runs again, and the user is redirected away from the page they're on, with no polling and no
 * logout broadcast.
 *
 * For the same reason, one guard covers all three ways of reaching a route:
 *
 * - A deep link: the HTTP layer checks the guard first, and this check agrees with it.
 * - Navigation within a session: the request changes, and this composable recomposes.
 * - A hibernated session waking up: the attributes are recomputed from the new connection, so a role
 *   revoked while the session was hibernated takes effect on the first recomposition.
 *
 * @param guard the requirement to check, or `null` to allow every request.
 * @param notFound what to show when the guard returns [Access.NotFound].
 * @param content what to show when the guard allows the request.
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
            // Render nothing while the redirect happens. This principal isn't allowed to see the
            // page, and showing it even briefly would disclose its contents.
            val navigator = LocalNavigator.current
            LaunchedEffect(access.to) { navigator.replace(access.to) }
        }
    }
}

/**
 * Resolves the record a route refers to, and composes [content] with it.
 *
 * When the route resolves its own subject, it prevents a common vulnerability.
 * `view("/todo/{id}") { TodoStore.find(pathParam("id")) }` is an insecure direct object reference:
 * the view looks up whatever ID is in the URL. With this composable, the view never sees the raw
 * path parameter, and [resolve] uses the policy-checked lookup, which returns `null` for records
 * that this principal can't read.
 *
 * A `null` subject shows the not-found page before [content] is composed and before the document
 * title is set. The order of the title is easy to overlook: if the title were set first, it could
 * reveal a record that the body refused to show.
 *
 * The lookup is reactive like any other read. If a project stops being shared, anyone viewing one
 * of its todos is moved to the not-found page.
 *
 * @param resolve looks up the record from the request, and returns `null` if it doesn't exist or
 *   the principal can't read it.
 * @param title computes the document title from the record.
 * @param notFound what to show when [resolve] returns `null`.
 * @param content what to show for the record.
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
 * Use it for navigation links. For example,
 * `IfPermitted("/admin/users") { NavLink("/admin/users") { Text("Users") } }` hides the link with the
 * route's own guard, so the link and the route always agree.
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
 * The route table can't always provide the title, because the title can depend on the record the
 * route loaded. Setting it from the composition means it's computed only after the access check. A
 * title computed from a record before that check would leak the record through `<head>`.
 */
@Composable
public fun DocumentTitle(title: String) {
    val holder = LocalDocumentTitle.current
    SideEffect { holder.set(title) }
}

/** Receives the titles that [DocumentTitle] sets. The view that hosts the composition provides it. */
public fun interface TitleSink {
    /** Records [title] as the document title, or clears it if [title] is `null`. */
    public fun set(title: String?)
}

/** Where [DocumentTitle] sends the title. The default discards it. */
public val LocalDocumentTitle: ProvidableCompositionLocal<TitleSink> =
    compositionLocalOf { TitleSink { } }

/** The document title of the not-found page. */
internal const val NOT_FOUND_TITLE: String = "Not found"
