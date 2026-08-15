package jetlin.html

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A path pattern with named parameters, e.g. `/todo/{id}`.
 *
 * Matching happens server-side rather than in the HTTP routing tree, because navigation initiated
 * inside a live session never reaches the HTTP layer at all — it changes composition state.
 */
public class RoutePattern(public val pattern: String) {

    private val segments: List<String> = pattern.trim('/').split('/').filter { it.isNotEmpty() }

    /** Number of literal segments, used to prefer `/todo/new` over `/todo/{id}`. */
    internal val specificity: Int = segments.count { !it.isParameter() }

    /** Parameter bindings if [path] matches, or null. An empty map is a match with no parameters. */
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

private fun String.isParameter(): Boolean = startsWith('{') && endsWith('}')

/**
 * Resolves a path to one of [routes].
 *
 * Generic over the payload so that the view layer does not need to know what a "view" is; the server
 * module supplies its own registration type.
 */
public class Router<T>(routes: List<Pair<RoutePattern, T>>) {

    // Literal segments win over parameters, so /todo/new is not swallowed by /todo/{id} regardless
    // of the order the application declared them in.
    private val routes: List<Pair<RoutePattern, T>> = routes.sortedByDescending { it.first.specificity }

    public class Match<T>(
        public val pattern: RoutePattern,
        public val value: T,
        public val pathParams: Map<String, String>,
    )

    public fun resolve(path: String): Match<T>? {
        for ((pattern, value) in routes) {
            val params = pattern.match(path)
            if (params != null) return Match(pattern, value, params)
        }
        return null
    }
}

/**
 * Moves the session to another location without a page load.
 *
 * The composition stays alive: only the state naming the current route changes, so the runtime
 * swaps the matched view and emits the resulting DOM edits. The browser is told separately to
 * update its address bar.
 */
public interface Navigator {
    /** Navigates and adds a browser history entry. */
    public fun push(url: String)

    /** Navigates and replaces the current history entry. */
    public fun replace(url: String)
}

public val LocalNavigator: ProvidableCompositionLocal<Navigator> =
    staticCompositionLocalOf { error("No Navigator in composition; content must be hosted by a LiveView") }
