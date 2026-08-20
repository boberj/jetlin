package jetlin.testing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.Li
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.Ul
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A row with an identity of its own, so its key survives its text changing. */
private class ScopedRow(val id: Int, text: String) {
    var text: String by mutableStateOf(text)
}

/**
 * Confining a query to part of the page.
 *
 * Without this, "the up button in the third row" has to be written as an index across every button
 * on the page — which is both fragile and says nothing about what was meant.
 */
class ScopeTest {

    @Test
    fun `a scoped query only sees inside its subtree`(): Unit = runViewTest {
        setContent { Rows() }

        within(onAll(hasTestTag("row"))[1]) {
            onNode(hasText("edit")).click()
        }

        onAll(hasClass("label")).assertTexts("one", "TWO", "three")
    }

    @Test
    fun `the same matcher outside the scope would have been ambiguous`(): Unit = runViewTest {
        setContent { Rows() }

        // Every row has an edit button, so this cannot resolve on its own.
        val error = assertFailsWith<AssertionError> { onNode(hasText("edit")).fetch() }
        assertTrue(error.message.orEmpty().contains("found 3"), error.message)

        // Scoped, it is unambiguous.
        within(onAll(hasTestTag("row"))[0]) {
            assertEquals("button", onNode(hasText("edit")).fetch().tag)
        }
    }

    @Test
    fun `something outside the scope is not found`(): Unit = runViewTest {
        setContent {
            Div {
                Span({ testTag("outside") }) { Text("out") }
                Ul { Li({ testTag("row") }) { Span({ classes("label") }) { Text("in") } } }
            }
        }

        within(onNode(hasTestTag("row"))) {
            onNode(hasTestTag("outside")).assertDoesNotExist()
            onNode(hasClass("label")).assertText("in")
        }

        // And the scope is gone again once the block ends.
        onNode(hasTestTag("outside")).assertText("out")
    }

    @Test
    fun `scopes nest`(): Unit = runViewTest {
        setContent {
            Div {
                Div({ testTag("outer") }) {
                    Div({ testTag("inner") }) { Span({ classes("label") }) { Text("deep") } }
                }
                Div({ testTag("other") }) {
                    Div({ testTag("inner") }) { Span({ classes("label") }) { Text("elsewhere") } }
                }
            }
        }

        within(onNode(hasTestTag("outer"))) {
            within(onNode(hasTestTag("inner"))) {
                onNode(hasClass("label")).assertText("deep")
            }
        }
    }

    @Test
    fun `a scope is re-resolved rather than pinned`(): Unit = runViewTest {
        setContent { Rows() }

        val secondRow = onAll(hasTestTag("row"))[1]

        within(secondRow) { onNode(hasText("edit")).click() }
        // The row was rebuilt by that click only if keying is wrong; either way the handle must still
        // address whatever is now in that position.
        within(secondRow) { onNode(hasClass("label")).assertText("TWO") }
    }

    @Test
    fun `assertOnlyWithin passes when everything that moved was inside the row`(): Unit = runViewTest {
        setContent { Rows() }

        val update = recordUpdate {
            within(onAll(hasTestTag("row"))[1]) { onNode(hasText("edit")).click() }
        }

        update.assertOnlyWithin(hasTestTag("row"))
    }

    @Test
    fun `assertOnlyWithin names what escaped the subtree`(): Unit = runViewTest {
        setContent {
            val rows = remember { mutableStateListOf("one", "two") }
            Div {
                Span({ testTag("banner") }) { Text("${rows.size} rows") }
                Ul {
                    rows.forEachIndexed { index, row ->
                        key(index) {
                            Li({ testTag("row") }) {
                                Button({ onClick { rows += "extra $row" } }) { Text("add") }
                            }
                        }
                    }
                }
            }
        }

        val update = recordUpdate {
            within(onAll(hasTestTag("row"))[0]) { onNode(hasText("add")).click() }
        }

        // Adding a row also rewrites the banner and the list, neither of which is inside the row
        // that was clicked.
        val error = assertFailsWith<AssertionError> { update.assertOnlyWithin(hasTestTag("row")) }
        assertTrue(error.message.orEmpty().contains("<span>"), error.message)
    }
}

@Composable
private fun Rows() {
    val rows = remember { mutableStateListOf(ScopedRow(1, "one"), ScopedRow(2, "two"), ScopedRow(3, "three")) }
    Ul {
        rows.forEach { row ->
            key(row.id) {
                Li({ testTag("row") }) {
                    Span({ classes("label") }) { Text(row.text) }
                    Button({ onClick { row.text = row.text.uppercase() } }) { Text("edit") }
                }
            }
        }
    }
}
