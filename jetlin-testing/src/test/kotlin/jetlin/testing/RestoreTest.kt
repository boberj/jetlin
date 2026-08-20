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
 * What survives a session being torn down and rebuilt.
 *
 * Which values are declared saveable is an application decision, not a framework one — saving too
 * little throws away the user's typing, saving too much makes idle sessions expensive. This is how
 * an author checks they drew the line where they meant to.
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
        // remember is scratch space; recomputing it is the point, and it is what keeps a hibernated
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
