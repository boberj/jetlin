package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What a route decided about this request.
 *
 * A value rather than an exception, deliberately: a view that throws ends the session and restarts the
 * page, which is right for a bug and wrong for "you are not signed in".
 */
public sealed interface Access {
    public data object Allow : Access

    /**
     * The route is not here for this principal.
     *
     * The default refusal, in preference to anything that says "forbidden": a 403 on `/admin/users`
     * confirms there is an admin panel. Disclosure should be the explicit choice, not the default one.
     */
    public data object NotFound : Access

    public data class Redirect(val to: String) : Access
}

/**
 * What a route requires.
 *
 * Evaluated against the session's [RequestContext], which is where the application's principal lives —
 * so a guard is a pure function of the request and whatever `attributes { }` attached to it, and can be
 * evaluated at the HTTP layer, inside the composition, or in a test, with the same answer.
 *
 * **A guard is not the security boundary.** The record's policy is. A guard is UX plus a cheap early exit: it
 * stops a page rendering that would have been empty. If a guard is ever the only thing protecting data,
 * one forgotten guard is a leak.
 */
public fun interface Guard {
    public fun check(request: RequestContext): Access
}

/** Both, in order: the first refusal wins. */
public infix fun Guard.and(other: Guard): Guard = Guard { request ->
    when (val first = check(request)) {
        Access.Allow -> other.check(request)
        else -> first
    }
}

/**
 * Guards for an application's own principal type.
 *
 * Jetlin knows nothing about authentication, so the application says where its principal lives and gets
 * typed guards back:
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
    /** The principal this request belongs to, if any. */
    public fun of(request: RequestContext): P? = request[key]

    /**
     * Requires a principal, and sends anyone else to the sign-in page with where they were going.
     *
     * A redirect rather than a not-found, because a page that exists and needs signing in is not a
     * secret: the principal is about to prove who they are anyway.
     */
    public val signedIn: Guard = Guard { request ->
        if (request[key] != null) Access.Allow else Access.Redirect(signInUrl(request))
    }

    /**
     * Requires a principal [predicate] accepts.
     *
     * Signed out redirects to sign in; signed in and refused is [Access.NotFound], because whether the
     * route exists at all is not this principal's business.
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
 * The guards of the route table, so that a link can ask the same question the route will.
 *
 * Hiding a link and blocking a route are one fact, and two copies of it drift.
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
 * Evaluated inside the composition, which is what makes eviction free: a guard reads live state — a
 * principal's role is a cell — so revoking that role invalidates this composable, which re-evaluates to a
 * redirect and moves the principal off the page they are sitting on. No polling, no logout broadcast.
 *
 * It is also why the same guard covers all three ways into a route: a deep link (the HTTP layer answers
 * first, and this agrees), an in-session navigation (the request changes, this recomposes), and a
 * hibernated session waking up (the attributes are recomputed from the arriving connection, so a role
 * revoked while the session slept is noticed on the first recomposition rather than never).
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
            // Nothing is rendered while leaving: the page being left is not this principal's to see, and a
            // flash of it is a disclosure however brief.
            val navigator = LocalNavigator.current
            LaunchedEffect(access.to) { navigator.replace(access.to) }
        }
    }
}

/**
 * Resolves what this route is about, and composes [content] with it.
 *
 * The point of letting a route resolve its own subject is that the insecure shape stops being
 * expressible. `view("/todo/{id}") { TodoStore.find(pathParam("id")) }` is a textbook insecure direct
 * object reference; here there is no path parameter left to look up by hand, and [resolve] goes through
 * the gated lookup, which returns null for a record this principal may not read.
 *
 * Null resolves to not-found *before* [content] composes, and — this is the part that is easy to miss —
 * before the title is set, so the document title cannot disclose a record the body refused to show.
 *
 * Reactive, like every other read: unshare the project and whoever is holding one of its todos open
 * lands on not-found.
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
 * Composes [content] only if the route at [url] would admit this principal.
 *
 * For navigation: `IfPermitted("/admin/users") { NavLink("/admin/users") { Text("Users") } }` hides the
 * link by the same rule that blocks the route, so the two cannot disagree.
 */
@Composable
public fun IfPermitted(url: String, content: @Composable () -> Unit) {
    val guard = LocalRouteGuards.current.forUrl(url)
    val request = LocalRequest.current
    if (guard == null || guard.check(request) == Access.Allow) content()
}

/** The page shown for a route that is not here, or not here for this principal. */
@Composable
public fun NotFoundPage() {
    Div({ classes("jl-not-found") }) {
        H1 { Text("Not found") }
    }
}

/**
 * Sets the document title for whatever is currently composed.
 *
 * A title has to be able to come from the composition rather than from the route table, because a
 * route's title can depend on what the route resolved — and a title computed from a record before anything
 * checked whether the principal may read it is a disclosure in `<head>`.
 */
@Composable
public fun DocumentTitle(title: String) {
    val holder = LocalDocumentTitle.current
    SideEffect { holder.set(title) }
}

/** Where [DocumentTitle] puts what it is told. Supplied by the view hosting the composition. */
public fun interface TitleSink {
    public fun set(title: String?)
}

public val LocalDocumentTitle: ProvidableCompositionLocal<TitleSink> =
    compositionLocalOf { TitleSink { } }

internal const val NOT_FOUND_TITLE: String = "Not found"
