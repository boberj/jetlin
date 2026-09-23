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

/** Collects the routes that a test makes available. See [setRoutes]. */
public class RoutesBuilder internal constructor() {
    internal val routes: MutableList<Pair<RoutePattern, TestRoute>> = mutableListOf()

    /** The container that [app] set, or `null` for none. */
    internal var container: (@Composable (route: @Composable () -> Unit) -> Unit)? = null

    /**
     * Registers [content] at [pattern], such as `view("/todo/{id}") { TodoDetailPage() }`.
     *
     * @param requires the route's guard, declared the same way as in the application. As on the
     *   real server, the guard runs inside the composition, so you can test revocation and
     *   hibernation without a browser.
     */
    public fun view(pattern: String, requires: Guard? = null, content: @Composable () -> Unit) {
        routes += RoutePattern(pattern) to TestRoute(requires, content)
    }

    /**
     * Registers a view for a single record that the route looks up itself, like the matching
     * `JetlinConfig.view` overload.
     *
     * Check the title with [ViewTest.title]. A title computed from a record that the principal can't
     * read would leak it through `<head>`, and assertions on the body wouldn't catch that.
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
     * Wraps every view in a container that's composed once for the session, as `JetlinConfig.app`
     * does.
     *
     * You need it to test anything that outlives a navigation, because a `remember` in a view doesn't.
     */
    public fun app(content: @Composable (route: @Composable () -> Unit) -> Unit) {
        container = content
    }
}

/**
 * Composes whichever route from [block] matches the session's location, and follows the session as
 * it navigates.
 *
 * The alternative, [ViewTest.setContent], keeps one view in place. That's right for testing a view on
 * its own, but wrong as soon as anything calls `LocalNavigator.push`. The session moves, the view
 * stays composed at a location it wasn't written for, and a page that reads a path parameter that no
 * longer exists fails inside the composition instead of in an assertion.
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
 * The matched route extracts the path parameters, so `pathParam("id")` works exactly as it does
 * when the application is served. Navigating to a path that no route matches fails the composition.
 */
public suspend fun ViewTest.setRoutes(block: RoutesBuilder.() -> Unit) {
    val builder = RoutesBuilder().apply(block)
    val router = Router(builder.routes)
    val guards = RouteGuards(builder.routes.map { (pattern, route) -> pattern to route.guard })
    val patterns = builder.routes.joinToString { it.first.pattern }

    setRoutedContent { request ->
        // Use the same host the server composes, so a test drives what an application runs: the
        // container above, the matched view keyed below it, and saved state restored on return.
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
