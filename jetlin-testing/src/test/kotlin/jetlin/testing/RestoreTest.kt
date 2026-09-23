package jetlin.testing

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import jetlin.html.Div
import jetlin.html.Input
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.bind
import jetlin.html.rememberSavedField
import kotlin.test.Test

/**
 * Tests what survives a session being torn down and rebuilt.
 *
 * The application decides which values are saved, not the framework. Saving too little throws away
 * what the user typed, and saving too much makes idle sessions expensive. These assertions are how an
 * author checks that they drew the line where they meant to.
 */
class RestoreTest {

    @Test
    fun `a saved field comes back and a remembered one does not`(): Unit = runViewTest {
        setContent {
            val draft = rememberSavedField("", key = "draft")
            val scratch = remember { mutableStateOf("recomputed") }
            Div {
                Input({ testTag("draft"); bind(draft) })
                Span({ testTag("scratch") }) { Text(scratch.value) }
            }
        }

        onNode(hasTestTag("draft")).type("half-typed message")

        hibernateAndRestore()

        onNode(hasTestTag("draft")).assertValue("half-typed message")
        // `remember` is scratch space. Recomputing it is the point, and it's what keeps a hibernated
        // session small.
        onNode(hasTestTag("scratch")).assertText("recomputed")
    }

    @Test
    fun `queries and interactions keep working against the restored view`(): Unit = runViewTest {
        setContent {
            val draft = rememberSavedField("", key = "draft")
            Div { Input({ testTag("draft"); bind(draft) }) }
        }

        hibernateAndRestore()

        onNode(hasTestTag("draft")).type("typed after waking")
        onNode(hasTestTag("draft")).assertValue("typed after waking")
    }
}
