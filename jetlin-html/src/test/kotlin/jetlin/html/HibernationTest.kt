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

/**
 * Tearing a session down and building it back.
 *
 * Hibernation is what makes an idle session cheap and a restart survivable, and its whole contract
 * is the line between what comes back and what does not. These pin that line down.
 */
class HibernationTest {

    @Test
    fun `saved state comes back and remembered state does not`(): Unit = runBlocking {
        val saved = firstSession { draft, scratch ->
            draft.value = "half-typed message"
            scratch.value = "recomputable"
        }

        // A different LiveView entirely, as if on another server after a deploy.
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
        // The full path: an event from a client mutates saved state, which then round-trips.
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

        // Worth being explicit about: an empty map is the signal the registry uses to decide a
        // session is not worth storing at all.
        assertEquals(emptyMap(), view.hibernate())
    }

    @Test
    fun `state that no longer deserializes falls back instead of failing the session`(): Unit = runBlocking {
        val saved = firstSession { draft, _ -> draft.value = "was a string" }

        // The same key, now holding a different type — what a deploy that changed a model looks
        // like to a snapshot written by the previous version.
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
    fun `colliding keys are reported instead of losing a value`(): Unit = runBlocking {
        // Two auto-keyed values side by side in one composable. Their positions are
        // indistinguishable on this Compose runtime, so without this check the second would
        // silently overwrite the first and the user would lose whatever was in it.
        val view = LiveView { _ ->
            Div {
                val first = rememberSaved { "a" }
                val second = rememberSaved { "b" }
                Span { Text(first.value + second.value) }
            }
        }
        view.start()

        val failure = runCatching { view.hibernate() }.exceptionOrNull()
        assertTrue(
            failure?.message?.contains("share a key") == true,
            "expected a collision to be reported, got $failure",
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

/** Applies a state change the way an event handler would, and waits for it to settle. */
private suspend fun LiveView.transactMutate(block: () -> Unit) {
    Snapshot.withMutableSnapshot(block)
    awaitIdle()
}
