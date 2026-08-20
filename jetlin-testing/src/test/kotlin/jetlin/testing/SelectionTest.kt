package jetlin.testing

import jetlin.html.Div
import jetlin.html.Li
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.Ul
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Which node a query resolves to, and what it says when it cannot decide.
 *
 * A query that silently picks one of several matches is worse than one that fails, because the test
 * it produces passes for the wrong reason. These pin the failures down as carefully as the successes.
 */
class SelectionTest {

    @Test
    fun `nesting resolves to the innermost match`(): Unit = runViewTest {
        setContent {
            Ul {
                Li({ testTag("row") }) {
                    Span({ classes("label") }) { Text("Buy milk") }
                }
            }
        }

        // Both the <li> and the <span> render exactly "Buy milk"; the inner one is what was meant.
        assertEquals("span", onNode(hasText("Buy milk")).fetch().tag)
    }

    @Test
    fun `two matches fail rather than picking one`(): Unit = runViewTest {
        setContent {
            Div {
                Span({ testTag("item") }) { Text("a") }
                Span({ testTag("item") }) { Text("b") }
            }
        }

        val error = assertFailsWith<AssertionError> { onNode(hasTestTag("item")).fetch() }
        val message = error.message.orEmpty()
        assertTrue(message.contains("found 2"), message)
        assertTrue(message.contains("onAll"), "the failure should point at the way out: $message")
    }

    @Test
    fun `a query matching nothing prints the tree it searched`(): Unit = runViewTest {
        setContent { Div({ classes("card") }) { Text("hello") } }

        val error = assertFailsWith<AssertionError> { onNode(hasTag("button")).assertExists() }
        val message = error.message.orEmpty()
        assertTrue(message.contains("Found no node matching tag 'button'"), message)
        // Without the tree, working out why a matcher missed means adding print statements.
        assertTrue(message.contains("""<div class="card">"""), message)
    }

    @Test
    fun `a collection counts and indexes in document order`(): Unit = runViewTest {
        setContent {
            Ul {
                listOf("one", "two", "three").forEach { Li({ testTag("row") }) { Text(it) } }
            }
        }

        onAll(hasTestTag("row")).assertCount(3)
        onAll(hasTestTag("row")).assertTexts("one", "two", "three")
        onAll(hasTestTag("row"))[1].assertText("two")
    }

    @Test
    fun `an index past the end names how many there were`(): Unit = runViewTest {
        setContent { Ul { Li({ testTag("row") }) { Text("only") } } }

        val error = assertFailsWith<AssertionError> { onAll(hasTestTag("row"))[3].fetch() }
        assertTrue(error.message.orEmpty().contains("at least 4"), error.message)
        assertTrue(error.message.orEmpty().contains("found 1"), error.message)
    }
}
