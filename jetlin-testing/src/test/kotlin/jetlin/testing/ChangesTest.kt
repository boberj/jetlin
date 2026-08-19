package jetlin.testing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.Li
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.Ul
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A row with an identity of its own, so its key does not change when its text does. */
private class Row(val id: Int, text: String) {
    var text: String by mutableStateOf(text)
}

/**
 * Asserting on how much of the page an interaction disturbed.
 *
 * The point of these is that the page looks right either way. A list that rebuilds itself on every
 * change renders exactly the same HTML as one that patches a single row, so nothing asserting on
 * contents can tell them apart — and the difference is the whole cost model of a server-driven UI.
 */
class ChangesTest {

    @Test
    fun `editing one row of a keyed list leaves the others alone`(): Unit = runViewTest {
        setContent { RowList() }

        val update = recordUpdate { onNode(hasAttr("data-test", "edit-2")).click() }

        update.assertOnly(hasText("TWO"))
        update.assertUntouched(hasTag("ul"), hasText("one"), hasText("three"))
    }

    @Test
    fun `keying a list by its content rebuilds the row instead of patching it`(): Unit = runViewTest {
        // The bug this whole facility exists to catch. The page renders identically either way, so
        // every assertion about contents still passes; only the traffic gives it away.
        setContent { RowList(keyByText = true) }

        val update = recordUpdate { onNode(hasAttr("data-test", "edit-2")).click() }

        onNode(hasAttr("data-test", "label-2")).assertText("TWO")
        val error = assertFailsWith<AssertionError> { update.assertUntouched(hasTag("ul")) }
        assertTrue(error.message.orEmpty().contains("<ul>"), error.message)
    }

    @Test
    fun `appending a row is recorded against the list that gained it`(): Unit = runViewTest {
        setContent {
            val items = remember { mutableStateListOf("one") }
            Div {
                Button({ attr("data-test", "add"); onClick { items += "two" } }) { Text("add") }
                Ul({ attr("data-test", "list") }) {
                    items.forEach { key(it) { Li { Text(it) } } }
                }
            }
        }

        val update = recordUpdate { onNode(hasAttr("data-test", "add")).click() }

        // Both the list and the row that arrived: a structural change belongs to the parent, and the
        // new subtree is named too so it can be asserted on directly.
        update.assertOnly(hasAttr("data-test", "list"), hasText("two"))
    }

    @Test
    fun `an interaction that changes nothing is visible as such`(): Unit = runViewTest {
        setContent {
            val items = remember { mutableStateListOf("one") }
            Div {
                // Writes the value it already holds, so the runtime has nothing to send.
                Button({ attr("data-test", "noop"); onClick { items[0] = "one" } }) { Text("noop") }
                Span { Text(items[0]) }
            }
        }

        recordUpdate { onNode(hasAttr("data-test", "noop")).click() }.assertNothingChanged()
    }

    @Test
    fun `assertOnly fails when more of the page moved than expected`(): Unit = runViewTest {
        setContent { RowList() }

        val update = recordUpdate {
            onNode(hasAttr("data-test", "edit-1")).click()
            onNode(hasAttr("data-test", "edit-3")).click()
        }

        val error = assertFailsWith<AssertionError> { update.assertOnly(hasText("ONE")) }
        val message = error.message.orEmpty()
        assertTrue(message.contains("Unexpectedly changed"), message)
        // Naming the surplus node is the whole value of the failure.
        assertTrue(message.contains("<span>"), message)
    }
}

@androidx.compose.runtime.Composable
private fun RowList(keyByText: Boolean = false) {
    val rows = remember { mutableStateListOf(Row(1, "one"), Row(2, "two"), Row(3, "three")) }
    Ul {
        rows.forEach { row ->
            key(if (keyByText) row.text else row.id) {
                Li {
                    Span({ attr("data-test", "label-${row.id}") }) { Text(row.text) }
                    Button({
                        attr("data-test", "edit-${row.id}")
                        onClick { row.text = row.text.uppercase() }
                    }) { Text("edit") }
                }
            }
        }
    }
}
