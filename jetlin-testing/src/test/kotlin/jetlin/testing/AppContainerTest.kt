package jetlin.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.Input
import jetlin.html.Link
import jetlin.html.Nav
import jetlin.html.Text
import jetlin.html.bind
import jetlin.html.pathParam
import jetlin.html.rememberField
import jetlin.html.rememberSavedField
import kotlin.test.Test

/**
 * What outlives a navigation, and what does not.
 *
 * Three lifetimes meet here and an application has to be able to tell them apart. A `remember` in a
 * view lasts until the view is navigated away from. A `rememberSaved` in a view comes back when the
 * view does. A `remember` in the container lasts as long as the session, because the container is
 * composed once above the route and never torn down.
 */
class AppContainerTest {

    @Test
    fun `state in the container survives a navigation`(): Unit = runViewTest(url = "/") {
        setRoutes { app { route -> Chrome(route) }; items() }

        onNode(hasTestTag("search")).type("tug")
        navigate("/item/7")

        onNode(hasTag("h1")).assertText("Item 7")
        // Readable from the other page, not merely restored on returning to this one.
        onNode(hasTestTag("search")).assertValue("tug")

        navigate("/")
        onNode(hasTestTag("search")).assertValue("tug")
    }

    @Test
    fun `state in a view does not survive a navigation`(): Unit = runViewTest(url = "/") {
        setRoutes {
            app { route -> Chrome(route) }
            view("/") {
                val note = rememberField("")
                Div { Input({ testTag("note"); bind(note) }) }
            }
            view("/item/{id}") { H1 { Text("Item ${pathParam("id")}") } }
        }

        onNode(hasTestTag("note")).type("scratch")
        navigate("/item/7")
        navigate("/")

        // The counterpart to the test above: this is what the container exists to change.
        onNode(hasTestTag("note")).assertValue("")
    }

    @Test
    fun `a view's saved state comes back when the view does`(): Unit = runViewTest(url = "/") {
        setRoutes {
            app { route -> Chrome(route) }
            view("/") {
                val draft = rememberSavedField("", key = "draft")
                val scratch = rememberField("")
                Div {
                    Input({ testTag("draft"); bind(draft) })
                    Input({ testTag("scratch"); bind(scratch) })
                }
            }
            view("/item/{id}") { H1 { Text("Item ${pathParam("id")}") } }
        }

        onNode(hasTestTag("draft")).type("half-typed")
        onNode(hasTestTag("scratch")).type("thrown away")

        navigate("/item/7")
        navigate("/")

        onNode(hasTestTag("draft")).assertValue("half-typed")
        onNode(hasTestTag("scratch")).assertValue("")
    }

    @Test
    fun `saved state is captured even when the view tears effects down with it`(): Unit =
        runViewTest(url = "/") {
            setRoutes {
                app { route -> Chrome(route) }
                view("/") {
                    val draft = rememberSavedField("", key = "draft")
                    // A view disposing its own effects is ordinary, and the order those run in
                    // relative to the router's own teardown is the runtime's business. Saving must
                    // not depend on winning that race.
                    DisposableEffect(Unit) { onDispose { } }
                    Div { Input({ testTag("draft"); bind(draft) }) }
                }
                view("/item/{id}") { H1 { Text("Item ${pathParam("id")}") } }
            }

            onNode(hasTestTag("draft")).type("survives teardown")
            navigate("/item/7")
            navigate("/")

            onNode(hasTestTag("draft")).assertValue("survives teardown")
        }

    @Test
    fun `the container's saved state survives hibernation`(): Unit = runViewTest(url = "/") {
        setRoutes {
            app { route ->
                val search = rememberSavedField("", key = "search")
                Div {
                    Input({ testTag("search"); bind(search) })
                    route()
                }
            }
            items()
        }

        onNode(hasTestTag("search")).type("tug")
        hibernateAndRestore()

        // The container is never disposed, so its providers are still registered when the session
        // is asked what it wants to keep.
        onNode(hasTestTag("search")).assertValue("tug")
    }

    @Test
    fun `a view's saved state survives hibernation on the page it was left on`(): Unit =
        runViewTest(url = "/") {
            setRoutes {
                app { route -> Chrome(route) }
                view("/") {
                    val draft = rememberSavedField("", key = "draft")
                    Div { Input({ testTag("draft"); bind(draft) }) }
                }
                view("/item/{id}") { H1 { Text("Item ${pathParam("id")}") } }
            }

            onNode(hasTestTag("draft")).type("still here")
            hibernateAndRestore()

            onNode(hasTestTag("draft")).assertValue("still here")
        }

    @Test
    fun `navigating does not rebuild the chrome`(): Unit = runViewTest(url = "/") {
        setRoutes { app { route -> Chrome(route) }; items() }

        val update = recordUpdate { navigate("/item/7") }

        // Composed once above the route, so a move recomposes it to the same markup and the applier
        // has nothing to record. Composed inside each view instead, all of this would be torn out
        // and re-inserted on every navigation.
        update.assertUntouched(hasTestTag("nav"), hasTestTag("search"), hasTestTag("brand"))
    }

    @Test
    fun `a container belongs to one session rather than to the process`() {
        runViewTest(url = "/") {
            setRoutes { app { route -> Chrome(route) }; items() }
            onNode(hasTestTag("search")).type("tug")
            onNode(hasTestTag("search")).assertValue("tug")
        }

        runViewTest(url = "/") {
            setRoutes { app { route -> Chrome(route) }; items() }
            // A second visitor arrives at an empty search box, not the first one's.
            onNode(hasTestTag("search")).assertValue("")
        }
    }

    @Test
    fun `two locations sharing a route pattern share its saved state`(): Unit = runViewTest(url = "/item/1") {
        setRoutes {
            app { route -> Chrome(route) }
            view("/") { H1 { Text("Items") } }
            view("/item/{id}") {
                val draft = rememberSavedField("", key = "draft")
                Div {
                    H1 { Text("Item ${pathParam("id")}") }
                    Input({ testTag("draft"); bind(draft) })
                }
            }
        }

        onNode(hasTestTag("draft")).type("typed on item 1")
        navigate("/")
        navigate("/item/2")

        // Follows the rule the router already applies while composed: moving between two locations
        // of one pattern re-runs the view rather than rebuilding it, so they share its state. Saved
        // state is keyed the same way, because keying it any other way would restore one location's
        // values into a view that had kept another's.
        onNode(hasTag("h1")).assertText("Item 2")
        onNode(hasTestTag("draft")).assertValue("typed on item 1")
    }
}

/** A container in the shape an application would write: state above the route, then the chrome. */
@Composable
private fun Chrome(route: @Composable () -> Unit) {
    val search = rememberField("")
    Div {
        Nav({ testTag("nav") }) {
            Link("/", { testTag("brand") }) { Text("Items") }
            Input({ testTag("search"); bind(search) })
        }
        route()
    }
}

private fun RoutesBuilder.items() {
    view("/") {
        Div {
            H1 { Text("Items") }
            Button({ testTag("open-7") }) { Text("Open 7") }
        }
    }
    view("/item/{id}") { H1 { Text("Item ${pathParam("id")}") } }
}
