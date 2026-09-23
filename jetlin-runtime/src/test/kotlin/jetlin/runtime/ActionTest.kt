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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Tests [Action] from the point of view of a composition.
 *
 * The most important tests cover failure cases: a command that throws must produce an error message
 * instead of ending the session, and clicking a busy button again must do nothing. These depend on where
 * the `try` is placed and on when `invoke` returns early, and neither would be noticed until something
 * actually failed.
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
            host.awaitRun(save)

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
            host.awaitRun(save)

            assertEquals("root(done: saved)", root.render())
            // Re-enabling the button and showing the outcome is a single state write, so it is one patch.
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

            // Simulates a double click that arrives before the button has been disabled.
            host.transact { save() }
            host.transact { save() }
            host.awaitIdle()

            assertEquals(1, calls.get(), "a second click must not send a second request")

            arrival.complete("saved")
            host.awaitRun(save)
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
            host.awaitRun(save)
            assertEquals("root(failed: rejected)", root.render())

            host.transact { save() }
            host.awaitIdle()

            // The error is cleared as soon as the retry starts, so it is never shown during an attempt.
            assertEquals("root(running)", root.render())

            second.complete("saved")
            host.awaitRun(save)
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
            host.awaitRun(save)

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
                // Captures `draft`. The action is remembered once, but the block is updated on every
                // pass; otherwise it would keep saving the draft from the first composition.
                save = rememberAction { "saved $draft" }
                Text(save.state.text())
            }

            host.transact { draft = "second" }
            host.transact { save() }
            host.awaitRun(save)

            assertEquals("root(done: saved second)", root.render())
        }
    }
}

/**
 * Waits for an attempt to finish, then for the page to update.
 *
 * `awaitIdle` alone isn't enough. While the action is suspended waiting on something outside the
 * session, the session has no queued work and really is idle, which is exactly what an action is for.
 * So this waits for the outcome itself, with a time limit.
 */
private suspend fun CompositionHost.awaitRun(action: Action<*>) {
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) {
            while (action.state is Run.Running) delay(2)
        }
    }
    awaitIdle()
}

/** Formats the four [Run] states as display text. */
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
