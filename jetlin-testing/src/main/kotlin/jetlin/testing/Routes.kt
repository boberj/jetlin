package jetlin.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import jetlin.html.Guard
import jetlin.html.Guarded
import jetlin.html.RequestContext
import jetlin.html.RouteGuards
import jetlin.html.RouteHost
import jetlin.html.RoutePattern
import jetlin.html.Router
import jetlin.html.Subject
import jetlin.html.LocalRouteGuards

/** Collects the routes a test makes available. */
public class RoutesBuilder internal constructor() {
    internal val routes: MutableList<Pair<RoutePattern, TestRoute>> = mutableListOf()

    internal var container: (@Composable (route: @Composable () -> Unit) -> Unit)? = null

    /**
     * Registers [content] at [pattern], e.g. `view("/todo/{id}") { TodoDetailPage() }`.
     *
     * [requires] is the route's guard, declared the same way as in the application. As in the real
     * server, the guard runs inside the composition, so revocation and hibernation can be tested without
     * a browser.
     */
    public fun view(pattern: String, requires: Guard? = null, content: @Composable () -> Unit) {
        routes += RoutePattern(pattern) to TestRoute(requires, content)
    }

    /**
     * Registers a view for a single record that the route looks up itself, like the equivalent
     * `JetlinConfig.view` overload.
     *
     * Check the title with [ViewTest.title]. A title computed from a record the principal may not read
     * would leak it through `<head>`, and assertions on the body wouldn't catch that.
     */
    public fun <T : Any> view(
        pattern: String,
        subject: (RequestContext) -> T?,
        title: (T) -> String,
        requires: Guard? = null,
        content: @Composable (T) -> Unit,
    ) {
        routes += RoutePattern(pattern) to TestRoute(requires) {
            Subject(resolve = subject, title = title, content = content)
        }
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
    val guards = RouteGuards(builder.routes.map { (pattern, route) -> pattern to route.guard })
    val patterns = builder.routes.joinToString { it.first.pattern }

    setRoutedContent { request ->
        // The same host the server composes, so a test drives what an application runs: the
        // container above, the matched view keyed below it, saved state restored on the way back.
        CompositionLocalProvider(LocalRouteGuards provides guards) {
            RouteHost(
                router = router,
                request = request,
                container = builder.container,
                onMiss = { path -> error("No route registered for '$path'. Registered: $patterns") },
            ) { route -> Guarded(route.guard) { route.content() } }
        }
    }
}

/** A registered route: its guard and its content. */
internal class TestRoute(val guard: Guard?, val content: @Composable () -> Unit)
