package jetlin.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import jetlin.html.LocalRequest
import jetlin.html.RoutePattern
import jetlin.html.Router

/** Collects the routes a test makes available. */
public class RoutesBuilder internal constructor() {
    internal val routes: MutableList<Pair<RoutePattern, @Composable () -> Unit>> = mutableListOf()

    /** Registers [content] at [pattern], e.g. `view("/todo/{id}") { TodoDetailPage() }`. */
    public fun view(pattern: String, content: @Composable () -> Unit) {
        routes += RoutePattern(pattern) to content
    }
}

/**
 * Composes whichever of [block]'s routes matches the session's current location, and follows it as
 * the session navigates.
 *
 * The alternative, [ViewTest.setContent], pins one view in place. That is right for testing a view
 * on its own and wrong the moment anything calls `LocalNavigator.push`: the session moves, the
 * pinned view stays composed at a location it was never written for, and a page reading a path
 * parameter that no longer exists fails inside the composition rather than in the assertion.
 *
 * ```kotlin
 * runViewTest(url = "/todo/1") {
 *     setRoutes {
 *         view("/") { TodoListPage() }
 *         view("/todo/{id}") { TodoDetailPage() }
 *     }
 *
 *     onNode(hasAttr("data-test", "save")).click()
 *     assertUrl("/")
 *     onAll(hasAttr("data-test", "todo")).assertCount(3)
 * }
 * ```
 *
 * Path parameters are resolved by the route that matched, so `pathParam("id")` works exactly as it
 * does when the application is served.
 */
public suspend fun ViewTest.setRoutes(block: RoutesBuilder.() -> Unit) {
    val builder = RoutesBuilder().apply(block)
    val router = Router(builder.routes)
    val patterns = builder.routes.joinToString { it.first.pattern }

    setRoutedContent { request ->
        val match = router.resolve(request.path)
            ?: error("No route registered for '${request.path}'. Registered: $patterns")
        CompositionLocalProvider(LocalRequest provides request.withPathParams(match.pathParams)) {
            // Keyed by pattern so moving between routes builds the new view fresh rather than
            // carrying the previous one's state into it.
            key(match.pattern.pattern) { match.value() }
        }
    }
}
