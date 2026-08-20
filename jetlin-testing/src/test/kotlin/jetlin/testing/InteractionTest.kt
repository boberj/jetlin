package jetlin.testing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.Form
import jetlin.html.Input
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.bind
import jetlin.html.rememberField
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Sending events the way a browser would, and what happens when there is nothing to send them to.
 */
class InteractionTest {

    @Test
    fun `a click reaches a handler on an ancestor of the matched node`(): Unit = runViewTest {
        setContent {
            var clicks by remember { mutableStateOf(0) }
            Div {
                // The text lives in a span, so a text query resolves to the span rather than to the
                // button holding the handler. A browser would bubble; so does this.
                Button({ onClick { clicks++ } }) { Span { Text("Save") } }
                P({ testTag("count") }) { Text("$clicks") }
            }
        }

        onNode(hasText("Save")).click()
        onNode(hasTestTag("count")).assertText("1")
    }

    @Test
    fun `clicking something nobody wired up fails`(): Unit = runViewTest {
        setContent { Button({ testTag("dead") }) { Text("Does nothing") } }

        val error = assertFailsWith<AssertionError> { onNode(hasTestTag("dead")).click() }
        val message = error.message.orEmpty()
        assertTrue(message.contains("Nothing listens for 'click'"), message)
        assertTrue(message.contains("no listeners"), message)
    }

    @Test
    fun `typing sends the field's new value and the server sees it`(): Unit = runViewTest {
        setContent {
            val field = rememberField("") { if (it.isBlank()) "Required" else null }
            Div {
                Input({ testTag("name"); bind(field) })
                field.error?.let { P({ testTag("error") }) { Text(it) } }
            }
        }

        onNode(hasTestTag("name")).type("Ada")
        onNode(hasTestTag("error")).assertDoesNotExist()
        onNode(hasTestTag("name")).assertValue("Ada")

        onNode(hasTestTag("name")).type("")
        onNode(hasTestTag("error")).assertText("Required")
    }

    @Test
    fun `checking a box reports the new state`(): Unit = runViewTest {
        setContent {
            var done by remember { mutableStateOf(false) }
            Div {
                Input({ type("checkbox"); checked(done); onChecked { done = it } })
                Span({ testTag("state") }) { Text(if (done) "done" else "todo") }
            }
        }

        onNode(hasTag("input")).check()
        onNode(hasTestTag("state")).assertText("done")

        onNode(hasTag("input")).check(false)
        onNode(hasTestTag("state")).assertText("todo")
    }

    @Test
    fun `submitting carries the form's fields`(): Unit = runViewTest {
        setContent {
            var submitted by remember { mutableStateOf("") }
            Div {
                Form({ onSubmit { submitted = it["email"].orEmpty() } }) {
                    Input({ name("email") })
                }
                P({ testTag("submitted") }) { Text(submitted) }
            }
        }

        onNode(hasTag("form")).submit(mapOf("email" to "ada@example.com"))
        onNode(hasTestTag("submitted")).assertText("ada@example.com")
    }

    @Test
    fun `a key press reaches its handler`(): Unit = runViewTest {
        setContent {
            var lastKey by remember { mutableStateOf("none") }
            Div {
                Input({ onKeyDown { lastKey = it } })
                Span({ testTag("key") }) { Text(lastKey) }
            }
        }

        onNode(hasTag("input")).pressKey("Enter")
        onNode(hasTestTag("key")).assertText("Enter")
    }
}
