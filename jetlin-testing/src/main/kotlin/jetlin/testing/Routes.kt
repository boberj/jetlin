package jetlin.testing

import androidx.compose.runtime.Composable
import jetlin.html.RouteHost
import jetlin.html.RoutePattern
import jetlin.html.Router

/** Collects the routes a test makes available. */
public class RoutesBuilder internal constructor() {
    internal val routes: MutableList<Pair<RoutePattern, @Composable () -> Unit>> = mutableListOf()

    internal var container: (@Composable (route: @Composable () -> Unit) -> Unit)? = null

    /** Registers [content] at [pattern], e.g. `view("/todo/{id}") { TodoDetailPage() }`. */
    public fun view(pattern: String, content: @Composable () -> Unit) {
        routes += RoutePattern(pattern) to content
    }

    /**
     * Wraps every view in a container composed once for the session, as `JetlinConfig.app` does.
     *
     * Needed to test anything that outlives a navigation, since a `remember` in a view does not.
     */
    public fun app(content: @Composable (route: @Composable () -> Unit) -> Unit) {
        container = content
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
 *     onNode(hasTestTag("save")).click()
 *     assertUrl("/")
 *     onAll(hasTestTag("todo")).assertCount(3)
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
        // The same host the server composes, so a test drives what an application runs: the
        // container above, the matched view keyed below it, saved state restored on the way back.
        RouteHost(
            router = router,
            request = request,
            container = builder.container,
            onMiss = { path -> error("No route registered for '$path'. Registered: $patterns") },
        ) { it() }
    }
}
