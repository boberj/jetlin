package jetlin.testing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jetlin.html.ClientComponent
import jetlin.html.Div
import jetlin.html.P
import jetlin.html.Text
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The half of a client component a test without a browser can reach.
 *
 * What it draws is not testable here and is not meant to be. What the server sent it, and what the
 * server does when it reports something, are — and those are the parts an application owns.
 */
class ClientComponentTest {

    @Test
    fun `props sent to the component can be asserted`(): Unit = runViewTest {
        setContent {
            ClientComponent("chart", buildJsonObject { put("series", 3) }, attrs = { testTag("chart") })
        }

        onNode(hasClientComponent("chart")).assertProps(buildJsonObject { put("series", 3) })
        onNode(hasTestTag("chart")).assertMatches(hasClientComponent("chart"))
    }

    @Test
    fun `an event pushed up drives the server the same way the browser would`(): Unit = runViewTest {
        setContent {
            var picked by remember { mutableStateOf("none") }
            Div {
                ClientComponent(
                    name = "chart",
                    attrs = { testTag("chart") },
                    onEvent = { event, payload ->
                        if (event == "picked") picked = "bar ${payload["index"]!!.jsonPrimitive.int}"
                    },
                )
                P({ testTag("picked") }) { Text(picked) }
            }
        }

        onNode(hasTestTag("chart")).pushFromClient("picked", buildJsonObject { put("index", 2) })

        onNode(hasTestTag("picked")).assertText("bar 2")
    }

    @Test
    fun `props change when the state behind them does`(): Unit = runViewTest {
        setContent {
            var series by remember { mutableStateOf(1) }
            Div {
                ClientComponent(
                    name = "chart",
                    props = buildJsonObject { put("series", series) },
                    attrs = { testTag("chart") },
                    onEvent = { _, _ -> series += 1 },
                )
            }
        }

        onNode(hasTestTag("chart")).assertProps(buildJsonObject { put("series", 1) })
        onNode(hasTestTag("chart")).pushFromClient("bump")
        onNode(hasTestTag("chart")).assertProps(buildJsonObject { put("series", 2) })
    }

    @Test
    fun `pushing from something that is not a component says so`(): Unit = runViewTest {
        setContent { Div({ testTag("plain") }) { Text("ordinary") } }

        val error = assertFailsWith<AssertionError> {
            onNode(hasTestTag("plain")).pushFromClient("anything")
        }
        assertTrue(error.message.orEmpty().contains("is not a client component"), error.message)
    }
}
