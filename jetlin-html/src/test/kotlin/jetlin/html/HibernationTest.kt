package jetlin.html

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import jetlin.protocol.ClientMessage
import jetlin.protocol.EventPayload
import jetlin.runtime.rememberSaved
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tests tearing a session down and building it back.
 *
 * Hibernation makes an idle session cheap and a restart survivable, and its whole contract is the
 * line between what comes back and what doesn't. These tests pin that line down.
 */
class HibernationTest {

    @Test
    fun `saved state comes back and remembered state does not`(): Unit = runBlocking {
        val saved = firstSession { draft, scratch ->
            draft.value = "half-typed message"
            scratch.value = "recomputable"
        }

        // A different LiveView, as if on another server after a deployment.
        var restoredDraft = ""
        var restoredScratch = ""
        LiveView(restored = saved) { _ ->
            val draft = rememberSaved<String>("draft") { "" }
            val scratch = remember { mutableStateOf("recomputed from scratch") }
            restoredDraft = draft.value
            restoredScratch = scratch.value
            Span { Text(draft.value) }
        }.use {
            it.start()
            assertEquals("half-typed message", restoredDraft, "saved state must survive")
            assertEquals(
                "recomputed from scratch",
                restoredScratch,
                "remember is scratch space and must not be carried across",
            )
        }
    }

    @Test
    fun `the rendered tree reflects restored state`(): Unit = runBlocking {
        val saved = firstSession { draft, _ -> draft.value = "carried over" }

        LiveView(restored = saved) { _ ->
            val draft = rememberSaved<String>("draft") { "" }
            Input({ value(draft.value) })
        }.use {
            it.start()
            assertTrue(it.renderHtml().contains("""value="carried over""""), it.renderHtml())
        }
    }

    @Test
    fun `state written through the wire survives a hibernate`(): Unit = runBlocking {
        // The full path: an event from a client changes saved state, which then survives the round trip.
        val view = LiveView { _ ->
            val draft = rememberSavedField("", key = "draft")
            Input({ bind(draft) })
        }
        view.start()
        view.dispatch(
            ClientMessage.Event(node = 1, event = "input", seq = 1, payload = EventPayload(value = "typed by a user")),
        )
        val saved = view.hibernate()

        var restored = ""
        LiveView(restored = saved) { _ ->
            val draft = rememberSavedField("", key = "draft")
            restored = draft.value
            Input({ bind(draft) })
        }.use {
            it.start()
            assertEquals("typed by a user", restored)
        }
    }

    @Test
    fun `a session with nothing saveable hibernates to nothing`(): Unit = runBlocking {
        val view = LiveView { _ ->
            val scratch = remember { mutableStateOf("ephemeral") }
            Span { Text(scratch.value) }
        }
        view.start()

        // Worth stating: an empty map is the signal the registry uses to decide that a session isn't
        // worth storing at all.
        assertEquals(emptyMap(), view.hibernate())
    }

    @Test
    fun `state that no longer deserializes falls back instead of failing the session`(): Unit = runBlocking {
        val saved = firstSession { draft, _ -> draft.value = "was a string" }

        // The same key, now holding a different type. That's what a deployment that changed a model
        // looks like to a snapshot written by the previous version.
        var restored = 0
        LiveView(restored = saved) { _ ->
            val count = rememberSaved<Int>("draft") { 42 }
            restored = count.value
            Span { Text("${count.value}") }
        }.use {
            it.start()
            assertEquals(42, restored, "an unreadable value must fall back to the initializer")
        }
    }

    @Test
    fun `explicitly keyed values round trip independently`(): Unit = runBlocking {
        lateinit var first: MutableState<String>
        lateinit var second: MutableState<String>
        val view = LiveView { _ ->
            Div {
                first = rememberSaved(key = "first") { "a" }
                second = rememberSaved(key = "second") { "b" }
                Span { Text(first.value + second.value) }
            }
        }
        view.start()
        view.transactMutate {
            first.value = "first value"
            second.value = "second value"
        }
        val saved = view.hibernate()
        assertEquals(2, saved.size, "each key should hold its own value, got $saved")

        var firstBack = ""
        var secondBack = ""
        LiveView(restored = saved) { _ ->
            Div {
                val restoredFirst = rememberSaved(key = "first") { "a" }
                val restoredSecond = rememberSaved(key = "second") { "b" }
                firstBack = restoredFirst.value
                secondBack = restoredSecond.value
                Span { Text(restoredFirst.value + restoredSecond.value) }
            }
        }.use {
            it.start()
            assertEquals("first value", firstBack)
            assertEquals("second value", secondBack)
        }
    }

    @Test
    fun `two auto-keyed values side by side keep their own state`(): Unit = runBlocking {
        // Compose derives the automatic key from the composable's position, and before 1.12, two
        // calls side by side in one composable got the same position. So this pair used to collide,
        // and the collision had to be reported to stop the second value from overwriting the first
        // without any error. Newer runtimes tell them apart, so the pair survives the round trip, and
        // the report is for the case that can still happen: a position that isn't an identity, in a
        // loop over data that can be reordered.
        val saved = LiveView { _ ->
            Div {
                val first = rememberSaved { "a" }
                val second = rememberSaved { "b" }
                first.value = "first value"
                second.value = "second value"
                Span { Text(first.value + second.value) }
            }
        }.use {
            it.start()
            it.hibernate()
        }

        assertEquals(2, saved.size, "each value should have kept its own key, got $saved")
        assertEquals(
            listOf("first value", "second value"),
            saved.values.map { it.jsonPrimitive.content },
        )
    }
}

/** Builds a session, lets [mutate] change its state, and hibernates it. */
private suspend fun firstSession(
    mutate: (draft: MutableState<String>, scratch: MutableState<String>) -> Unit,
): Map<String, JsonElement> {
    lateinit var draft: MutableState<String>
    lateinit var scratch: MutableState<String>

    val view = LiveView { _ ->
        draft = rememberSaved("draft") { "" }
        scratch = remember { mutableStateOf("original") }
        Span { Text(draft.value) }
    }
    view.start()
    view.transactMutate { mutate(draft, scratch) }
    return view.hibernate()
}

/** Applies a state change as an event handler would, and waits for it to settle. */
private suspend fun LiveView.transactMutate(block: () -> Unit) {
    Snapshot.withMutableSnapshot(block)
    awaitIdle()
}
