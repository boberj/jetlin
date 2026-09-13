package jetlin.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

/**
 * Suspending work started from a handler, seen from a composition.
 *
 * The interesting assertions are the two failure-shaped ones: a command that throws must cost a message
 * rather than the session, and a second click on a working button must cost nothing. Both are properties
 * of where the `try` is and of when `invoke` returns early, and both are invisible until something
 * actually fails.
 */
class ActionTest {

    @Test
    fun `a failing action ends at Failed, and the session survives`(): Unit = runTest {
        val root = TestNode("root")
        lateinit var save: Action<String>
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent {
                save = rememberAction { error("rejected: hello") }
                Text(save.state.text())
            }
            assertEquals("root(idle)", root.render())

            host.transact { save() }
            host.awaitIdle()

            assertEquals("root(failed: rejected: hello)", root.render())
            assertIs<Run.Failed>(save.state)
            assertTrue(host.isAlive, "a failed command is a message, not the end of the page")
        }
    }

    @Test
    fun `the end of an attempt is one pass`(): Unit = runTest {
        val root = TestNode("root")
        val arrival = CompletableDeferred<String>()
        lateinit var save: Action<String>
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent {
                save = rememberAction { arrival.await() }
                Text(save.state.text())
            }

            host.transact { save() }
            host.awaitIdle()
            assertEquals("root(running)", root.render())
            val running = host.changeCount

            arrival.complete("saved")
            host.awaitIdle()

            assertEquals("root(done: saved)", root.render())
            // The button coming back and the outcome appearing are one write, so they are one patch.
            assertEquals(1, host.changeCount - running)
        }
    }

    @Test
    fun `a second invoke while running is ignored`(): Unit = runTest {
        val root = TestNode("root")
        val arrival = CompletableDeferred<String>()
        val calls = AtomicInteger()
        lateinit var save: Action<String>
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent {
                save = rememberAction {
                    calls.incrementAndGet()
                    arrival.await()
                }
                Text(save.state.text())
            }

            // What a double click looks like when the button was not disabled quickly enough.
            host.transact { save() }
            host.transact { save() }
            host.awaitIdle()

            assertEquals(1, calls.get(), "a second click must not send a second request")

            arrival.complete("saved")
            host.awaitIdle()
            assertEquals("root(done: saved)", root.render())
        }
    }

    @Test
    fun `a retry replaces the failure it is retrying`(): Unit = runTest {
        val root = TestNode("root")
        val attempts = AtomicInteger()
        val second = CompletableDeferred<String>()
        lateinit var save: Action<String>
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent {
                save = rememberAction {
                    if (attempts.incrementAndGet() == 1) error("rejected") else second.await()
                }
                Text(save.state.text())
            }

            host.transact { save() }
            host.awaitIdle()
            assertEquals("root(failed: rejected)", root.render())

            host.transact { save() }
            host.awaitIdle()

            // The error is gone the moment the retry starts: it cannot be read beside a live attempt.
            assertEquals("root(running)", root.render())

            second.complete("saved")
            host.awaitIdle()
            assertEquals("root(done: saved)", root.render())
        }
    }

    @Test
    fun `reset puts the action back to idle`(): Unit = runTest {
        val root = TestNode("root")
        lateinit var save: Action<String>
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent {
                save = rememberAction { error("rejected") }
                Text(save.state.text())
            }
            host.transact { save() }
            host.awaitIdle()

            host.transact { save.reset() }
            host.awaitIdle()

            assertEquals("root(idle)", root.render())
        }
    }

    @Test
    fun `the action runs the block the current composition built`(): Unit = runTest {
        val root = TestNode("root")
        var draft by mutableStateOf("first")
        lateinit var save: Action<String>
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent {
                // Captures `draft`. Remembered once, but re-read on every pass — otherwise this action
                // would keep saving the draft as it was when the page first composed.
                save = rememberAction { "saved $draft" }
                Text(save.state.text())
            }

            host.transact { draft = "second" }
            host.transact { save() }
            host.awaitIdle()

            assertEquals("root(done: saved second)", root.render())
        }
    }
}

/** The four states as a page would show them. */
private fun Run<String>.text(): String = when (this) {
    is Run.Idle -> "idle"
    is Run.Running -> "running"
    is Run.Failed -> "failed: ${cause.message}"
    is Run.Done -> "done: $value"
}

@Composable
private fun Text(value: String) {
    ComposeNode<TestNode, TestApplier>(
        factory = { TestNode(value) },
        update = { set(value) { this.name = it } },
    )
}

private fun TestNode.render(): String =
    if (children.isEmpty()) name else "$name(${children.joinToString(",") { it.render() }})"
