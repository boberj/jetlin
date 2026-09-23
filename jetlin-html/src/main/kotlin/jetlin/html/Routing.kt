package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import jetlin.runtime.rememberSaveableStateHolder

/**
 * A path pattern with named parameters, such as `/todo/{id}`.
 *
 * Jetlin matches patterns itself instead of using the HTTP routing tree, because navigation inside a
 * live session never reaches the HTTP layer. It only changes composition state.
 *
 * @property pattern the pattern. A segment in braces, such as `{id}`, is a parameter that matches
 *   any one segment.
 */
public class RoutePattern(public val pattern: String) {

    private val segments: List<String> = pattern.trim('/').split('/').filter { it.isNotEmpty() }

    /** The number of literal segments. [Router] uses it to prefer `/todo/new` over `/todo/{id}`. */
    internal val specificity: Int = segments.count { !it.isParameter() }

    /**
     * Matches [path] against this pattern.
     *
     * @return the parameter values by name, or `null` if [path] doesn't match. An empty map means a
     *   match with no parameters.
     */
    public fun match(path: String): Map<String, String>? {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.size != segments.size) return null

        val params = mutableMapOf<String, String>()
        for ((index, segment) in segments.withIndex()) {
            val part = parts[index]
            if (segment.isParameter()) {
                params[segment.substring(1, segment.length - 1)] = part
            } else if (segment != part) {
                return null
            }
        }
        return params
    }

    override fun toString(): String = pattern
}

/** Whether this pattern segment is a parameter, such as `{id}`. */
private fun String.isParameter(): Boolean = startsWith('{') && endsWith('}')

/**
 * Resolves a path to one of [routes].
 *
 * The router is generic over what each route holds, so the view layer doesn't need to know what a
 * view is. The server module supplies its own registration type.
 *
 * @param routes each route's pattern and value.
 */
public class Router<T>(routes: List<Pair<RoutePattern, T>>) {

    // Literal segments win over parameters, so /todo/{id} doesn't swallow /todo/new, whatever order
    // the application declared them in.
    private val routes: List<Pair<RoutePattern, T>> = routes.sortedByDescending { it.first.specificity }

    /**
     * A route that matched a path.
     *
     * @property pattern the pattern that matched.
     * @property value what the route holds.
     * @property pathParams the parameter values from the path, by name.
     */
    public class Match<T>(
        public val pattern: RoutePattern,
        public val value: T,
        public val pathParams: Map<String, String>,
    )

    /**
     * Finds the route for [path]. Routes with more literal segments are tried first.
     *
     * @return the match, or `null` if no route matches.
     */
    public fun resolve(path: String): Match<T>? {
        for ((pattern, value) in routes) {
            val params = pattern.match(path)
            if (params != null) return Match(pattern, value, params)
        }
        return null
    }
}

/**
 * Composes [container] once for the session, with the view for the current location inside it.
 *
 * The composition outlives every navigation, because only the state that names the location
 * changes. So [container], above the route, is the one place where an application can keep
 * something across a page change. A `remember` in [container] survives navigation. A `remember` in a
 * view doesn't, because [key] on the matched pattern rebuilds the view instead of reusing it. That's
 * deliberate. Moving between two different routes must not carry one view's state into the other,
 * while moving between two locations of one route, such as `/todo/1` to `/todo/2`, keeps the view
 * and runs it again with new parameters.
 *
 * Views get their saved state back when the user returns to them, through
 * [rememberSaveableStateHolder]. The holder is keyed on the matched pattern, the same key that [key]
 * uses, so a view's registry lives exactly as long as the view. With different keys, restored state
 * would be composed against an empty registry.
 *
 * Both the server and the test harness route through this function, so a test drives exactly what
 * an application runs.
 *
 * @param router the route table.
 * @param request the current request.
 * @param container the composable that wraps every view, or `null` for none. It receives the
 *   current view as `route`.
 * @param onMiss what to show when no route matches. It's composed inside [container].
 * @param content composes the matched route's value.
 */
@Composable
public fun <T> RouteHost(
    router: Router<T>,
    request: RequestContext,
    container: (@Composable (route: @Composable () -> Unit) -> Unit)? = null,
    onMiss: @Composable (path: String) -> Unit,
    content: @Composable (T) -> Unit,
) {
    val holder = rememberSaveableStateHolder()

    val route: @Composable () -> Unit = {
        val match = router.resolve(request.path)
        if (match == null) {
            // Compose it inside the container, because a page that doesn't exist is still a page of
            // this application.
            onMiss(request.path)
        } else {
            CompositionLocalProvider(LocalRequest provides request.withPathParams(match.pathParams)) {
                holder.SaveableStateProvider(match.pattern.pattern) {
                    key(match.pattern.pattern) { content(match.value) }
                }
            }
        }
    }

    if (container == null) route() else container(route)
}

/**
 * Moves the session to another location without a page load.
 *
 * The composition stays alive. Only the state that names the current route changes, so the runtime
 * replaces the matched view and records the resulting DOM changes. The browser is told separately to
 * update its address bar.
 */
public interface Navigator {
    /** Navigates to [url] and adds a browser history entry. */
    public fun push(url: String)

    /** Navigates to [url] and replaces the current browser history entry. */
    public fun replace(url: String)
}

/** The session's [Navigator]. [LiveView] provides it. */
public val LocalNavigator: ProvidableCompositionLocal<Navigator> =
    staticCompositionLocalOf { error("No Navigator in composition; content must be hosted by a LiveView") }
