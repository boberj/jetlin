package jetlin.testing

import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.Input
import jetlin.html.Span
import jetlin.html.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests what the matchers find, and, more usefully, what they don't.
 *
 * Two of these tests exist because HTML is inconsistent about where state lives: `value` and
 * `checked` are DOM properties, while `disabled` is an attribute. A test author should never need to
 * know that, so the cases below check that the right matcher works and that the plausible wrong one
 * doesn't match the same thing without anyone noticing.
 */
class MatcherTest {

    @Test
    fun `a value is found by hasValue and not by the attribute of the same name`(): Unit = runViewTest {
        setContent { Input({ value("typed") }) }

        onNode(hasValue("typed")).assertExists()
        // Setting the attribute instead would change only the control's default, so the framework
        // doesn't write one. A test that looks for it has to fail, not match by accident.
        onNode(hasAttr("value", "typed")).assertDoesNotExist()
    }

    @Test
    fun `disabled is an attribute and checked is a property, and both are matched`(): Unit = runViewTest {
        setContent {
            Div {
                Button({ disabled(true) }) { Text("Off") }
                Input({ type("checkbox"); checked(true) })
            }
        }

        onNode(hasTag("button")).assertDisabled()
        onNode(hasTag("input")).assertChecked()
    }

    @Test
    fun `an enabled control is not matched as disabled`(): Unit = runViewTest {
        setContent { Button({ disabled(false) }) { Text("On") } }

        onNode(hasTag("button")).assertEnabled()
        onNode(hasTag("button") and isDisabled()).assertDoesNotExist()
    }

    @Test
    fun `hasClass matches one class among several`(): Unit = runViewTest {
        setContent { Span({ classes("todo-text done") }) { Text("x") } }

        onNode(hasClass("done")).assertExists()
        onNode(hasClass("todo-text")).assertExists()
        // It isn't a substring match: "don" isn't one of the classes.
        onNode(hasClass("don")).assertDoesNotExist()
    }

    @Test
    fun `hasText matches the whole subtree so a wrapper is addressable`(): Unit = runViewTest {
        setContent { Button({ id("save") }) { Span { Text("Save") } } }

        // The button renders "Save", even though the text node is two levels down.
        assertTrue(hasText("Save").matches(onNode(hasId("save")).fetch()))
    }

    @Test
    fun `combinators compose their descriptions so failures name what was looked for`(): Unit = runViewTest {
        setContent { Div { Text("hi") } }

        val matcher = hasTag("button") and hasText("Save")
        assertEquals("(tag 'button' and text 'Save')", matcher.description)

        val error = assertFailsWith<AssertionError> { onNode(matcher).assertExists() }
        assertTrue(
            error.message.orEmpty().contains("(tag 'button' and text 'Save')"),
            "the failure should name the matcher, but was: ${error.message}",
        )
    }
}
