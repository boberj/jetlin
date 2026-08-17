package jetlin.html

import androidx.compose.runtime.mutableStateOf
import jetlin.protocol.ClientMessage
import jetlin.protocol.EventPayload
import jetlin.protocol.Op
import jetlin.protocol.PropValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class FormsTest {

    @Test
    fun `an untouched field reports no error even when invalid`(): Unit = runBlocking {
        lateinit var field: Field<String>
        val view = LiveView { _ ->
            field = rememberField("") { if (it.isBlank()) "Required" else null }
            Input({ bind(field) })
        }
        view.use {
            view.start()
            // A form should not open covered in red.
            assertNull(field.error)
            assertFalse(field.isValid)
        }
    }

    @Test
    fun `editing marks the field touched and surfaces the error`(): Unit = runBlocking {
        lateinit var field: Field<String>
        val view = LiveView { _ ->
            field = rememberField("ok") { if (it.isBlank()) "Required" else null }
            Input({ bind(field) })
        }
        view.use {
            view.start()
            view.typeInto(node = 1, value = "")

            assertTrue(field.touched)
            assertEquals("Required", field.error)
        }
    }

    @Test
    fun `reset returns the field to a pristine state`(): Unit = runBlocking {
        lateinit var field: Field<String>
        val view = LiveView { _ ->
            field = rememberField("") { if (it.isBlank()) "Required" else null }
            Input({ bind(field) })
        }
        view.use {
            view.start()
            view.typeInto(node = 1, value = "typed")
            assertTrue(field.touched)

            field.reset("")
            assertFalse(field.touched)
            assertNull(field.error)
        }
    }

    @Test
    fun `bind renders the value as a DOM property and debounces input`(): Unit = runBlocking {
        val view = LiveView { _ ->
            val field = rememberField("hello")
            Input({ bind(field, debounceMs = 250) })
        }
        view.use {
            view.start()
            val html = view.renderHtml()
            assertTrue(html.contains("""value="hello""""), html)
            // The debounce has to reach the client, or every keystroke is a round trip. The spec
            // travels as JSON inside an attribute, so it is attribute-escaped on the way out.
            assertTrue(html.contains("""&quot;debounceMs&quot;:250"""), html)
        }
    }

    @Test
    fun `a server-side value change reaches the input as a property write`(): Unit = runBlocking {
        lateinit var field: Field<String>
        val view = LiveView { _ ->
            field = rememberField("before")
            Input({ bind(field) })
        }
        view.use {
            view.start()
            view.owner.drainOps()

            view.typeInto(node = 1, value = "after")
            assertEquals(
                listOf(Op.SetProp(1, "value", PropValue.Str("after"))),
                view.owner.drainOps(),
            )
        }
    }

    @Test
    fun `allValid reports the weakest field`() {
        val good = field("x") { null }
        val bad = field("") { "Required" }
        assertTrue(allValid(good))
        assertFalse(allValid(good, bad))
    }
}

/** Builds a field outside a composition, for the parts that need no session. */
private fun field(initial: String, validate: (String) -> String?): Field<String> =
    Field(mutableStateOf(initial), mutableStateOf(false), validate)

/** Routes an input event through the session, the way the transport would. */
private suspend fun LiveView.typeInto(node: Int, value: String) {
    dispatch(
        ClientMessage.Event(
            node = node,
            event = "input",
            seq = 1,
            payload = EventPayload(value = value),
        ),
    )
}
