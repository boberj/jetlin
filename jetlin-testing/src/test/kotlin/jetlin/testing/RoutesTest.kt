package jetlin.testing

import androidx.compose.runtime.Composable
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.LocalNavigator
import jetlin.html.Text
import jetlin.html.pathParam
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Following a session as it moves between views.
 *
 * Navigation is the case a single pinned view cannot cover: the location changes, and whether the
 * right view is composed at the other end is application behaviour worth asserting on.
 */
class RoutesTest {

    @Test
    fun `pushing a location swaps the view and moves the address bar`(): Unit = runViewTest(url = "/") {
        setRoutes {
            view("/") { ListPage() }
            view("/item/{id}") { DetailPage() }
        }

        onNode(hasTestTag("open-7")).click()

        assertUrl("/item/7")
        onNode(hasTag("h1")).assertText("Item 7")
    }

    @Test
    fun `a path parameter resolves from the route that matched`(): Unit = runViewTest(url = "/item/42") {
        setRoutes {
            view("/") { ListPage() }
            view("/item/{id}") { DetailPage() }
        }

        onNode(hasTag("h1")).assertText("Item 42")
    }

    @Test
    fun `a browser-initiated navigation is followed`(): Unit = runViewTest(url = "/item/1") {
        setRoutes {
            view("/") { ListPage() }
            view("/item/{id}") { DetailPage() }
        }

        // The user pressed back: the address bar has already moved and the server follows.
        navigate("/")

        assertUrl("/")
        onNode(hasTag("h1")).assertText("Items")
    }

    @Test
    fun `a single view resolves its own path parameters from its route`(): Unit =
        runViewTest(url = "/item/42") {
            // No router needed for a view that does not navigate: the pattern is enough to say what
            // the segments of the url mean, and the 42 is written once rather than twice.
            setContent(route = "/item/{id}") { DetailPage() }

            onNode(hasTag("h1")).assertText("Item 42")
        }

    @Test
    fun `a route that does not match the url says which is which`(): Unit = runViewTest(url = "/item/42") {
        val error = assertFailsWith<IllegalStateException> {
            setContent(route = "/other/{id}") { DetailPage() }
        }
        val message = error.message.orEmpty()
        assertTrue(message.contains("/other/{id}"), message)
        assertTrue(message.contains("/item/42"), message)
    }

    @Test
    fun `navigating somewhere unregistered says so`(): Unit = runViewTest(url = "/") {
        setRoutes { view("/") { ListPage() } }

        val error = assertFailsWith<IllegalStateException> { navigate("/nowhere") }
        val message = error.message.orEmpty()
        assertTrue(message.contains("No route registered for '/nowhere'"), message)
        // Listing what was registered turns a puzzling failure into an obvious typo.
        assertTrue(message.contains("Registered: /"), message)
    }
}

@Composable
private fun ListPage() {
    val navigator = LocalNavigator.current
    Div {
        H1 { Text("Items") }
        Button({ testTag("open-7"); onClick { navigator.push("/item/7") } }) { Text("Open 7") }
    }
}

@Composable
private fun DetailPage() {
    H1 { Text("Item ${pathParam("id")}") }
}
