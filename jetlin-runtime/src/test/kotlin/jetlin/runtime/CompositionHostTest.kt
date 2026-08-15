package jetlin.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CompositionHostTest {

    @Test
    fun `initial composition reaches the applier before setContent returns`() = runTest {
        val root = TestNode("root")
        val applier = TestApplier(root)
        CompositionHost(applier).use { host ->
            host.setContent {
                Node("a") {
                    Node("b")
                }
            }
            assertEquals("root(a(b))", root.render())
        }
    }

    @Test
    fun `state change recomposes and mutates the tree`() = runTest {
        val root = TestNode("root")
        var showSecond by mutableStateOf(false)
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent {
                Node("a")
                if (showSecond) Node("b")
            }
            assertEquals("root(a)", root.render())

            host.transact { showSecond = true }
            assertEquals("root(a,b)", root.render())

            host.transact { showSecond = false }
            assertEquals("root(a)", root.render())
        }
    }

    @Test
    fun `writes batched into one snapshot produce one recomposition pass`() = runTest {
        val root = TestNode("root")
        var first by mutableStateOf(0)
        var second by mutableStateOf(0)
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent { Node("n$first-$second") }
            val before = host.changeCount

            host.transact {
                first = 1
                second = 1
            }

            assertEquals("root(n1-1)", root.render())
            assertEquals(1, host.changeCount - before, "expected a single recomposition pass")
        }
    }

    @Test
    fun `state written outside the composition still recomposes`() = runTest {
        val root = TestNode("root")
        val label = mutableStateOf("before")
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent { Node(label.value) }
            assertEquals("root(before)", root.render())

            // No transact: simulate a background coroutine or pub/sub listener writing to the
            // global snapshot. This only works because GlobalSnapshotManager is pumping.
            Snapshot.withMutableSnapshot { label.value = "after" }
            host.awaitIdle()

            assertEquals("root(after)", root.render())
        }
    }

    @Test
    fun `composition failure surfaces to the caller`() = runTest {
        val root = TestNode("root")
        var boom by mutableStateOf(false)
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent {
                if (boom) error("kaboom")
                Node("ok")
            }
            val thrown = runCatching { host.transact { boom = true } }.exceptionOrNull()
            assertTrue(
                thrown?.let { generateSequence(it) { e -> e.cause }.any { e -> e.message == "kaboom" } } == true,
                "expected the composition error to propagate, got $thrown",
            )
        }
    }
}

@Composable
private fun Node(name: String, content: @Composable () -> Unit = {}) {
    ComposeNode<TestNode, TestApplier>(
        factory = { TestNode(name) },
        update = { set(name) { this.name = it } },
        content = content,
    )
}

private fun TestNode.render(): String =
    if (children.isEmpty()) name else "$name(${children.joinToString(",") { it.render() }})"
