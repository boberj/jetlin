package jetlin.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * What [CompositionHost.awaitApplied] and [CompositionHost.awaitIdle] promise, and what they must not
 * do on the way.
 *
 * Two ways to get this wrong, and both are tested. Ending a wait early sends a patch or lets a test
 * assert before the tree has caught up — so each test that waits checks the tree afterwards, rather
 * than only that the wait returned. Never ending it deadlocks a session — so every wait here runs
 * under a timeout that turns a hang into a failure naming what hung.
 *
 * runBlocking rather than runTest throughout: the host runs on real dispatchers and real timers, and
 * virtual time would skip exactly the throttles and delays these tests exist to wait through.
 */
class CompositionHostIdleTest {

    @Test
    fun `a throttled frame is waited for rather than skipped`(): Unit = runBlocking {
        val root = TestNode("root")
        var count by mutableIntStateOf(0)
        CompositionHost(TestApplier(root), FramePolicy.Paced(300.milliseconds)).use { host ->
            host.setContent { Leaf("count $count") }
            host.transact { count = 1 } // spends the frame budget, so the next one has to wait

            within("a change held back by the frame throttle") { host.transact { count = 2 } }

            // Recomposition is held in the recomposer while the throttle runs down, and a wait that
            // only looked for queued tasks would find none and return here, before it happened.
            assertEquals("root(count 2)", root.render())
        }
    }

    @Test
    fun `an effect that writes state as it starts has settled by the time awaitIdle returns`(): Unit = runBlocking {
        val root = TestNode("root")
        var label by mutableStateOf("before")
        var loading by mutableStateOf(false)
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent {
                Leaf(label)
                if (loading) {
                    // Written outside any snapshot, from an effect: invisible to the recomposer until
                    // the write is published, then a recomposition of its own.
                    LaunchedEffect(Unit) { label = "loaded" }
                }
            }
            host.transact { loading = true }

            within("an effect launched by the last transaction") { host.awaitIdle() }

            assertEquals("root(loaded)", root.render())
        }
    }

    @Test
    fun `an effect waiting on a timer does not keep the session from settling`(): Unit = runBlocking {
        val root = TestNode("root")
        var ticks by mutableIntStateOf(0)
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent {
                Leaf("ticks $ticks")
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(20)
                        ticks++
                    }
                }
            }
            // A clock read by the page, as a real one would be. Suspended in delay() it has no queued
            // work, so the session settles between ticks instead of never.
            repeat(5) { within("a session with a ticking clock") { host.awaitIdle() } }
        }
    }

    @Test
    fun `an effect that never settles is cut off by the budget, and does not delay a patch`(): Unit = runBlocking {
        val root = TestNode("root")
        var label by mutableStateOf("before")
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent {
                Leaf(label)
                // Suspends constantly and never waits: always queued work on the effects lane.
                LaunchedEffect(Unit) { while (true) yield() }
            }

            within("a patch alongside a busy effect") { host.transact { label = "after" } }
            assertEquals("root(after)", root.render())

            within("a bounded wait for a busy effect") { host.awaitIdle(effectsBudget = 100.milliseconds) }
        }
    }

    @Test
    fun `waiting fails rather than hangs once the composition has died`(): Unit = runBlocking {
        val root = TestNode("root")
        var boom by mutableStateOf(false)
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent {
                if (boom) error("kaboom")
                Leaf("ok")
            }
            runCatching { host.transact { boom = true } }

            // The work that killed it will never be done; a wait for it has to end, and say why.
            within("a wait on a dead composition") {
                assertFailsWith<IllegalStateException> { host.awaitApplied() }
                assertFailsWith<IllegalStateException> { host.awaitIdle() }
            }
        }
    }

    /**
     * An end-to-end guard: transactions and a concurrent writer, with the tree checked after every
     * wait.
     *
     * It is not what establishes that a wait cannot end early. The race that matters sits between two
     * adjacent reads, and this test passed three runs out of three against a version of the check with
     * those reads in the unsafe order. [SessionActivityTest] covers that race by forcing it.
     */
    @Test
    fun `writes from other threads never end a wait early`(): Unit = runBlocking {
        val root = TestNode("root")
        var mine by mutableIntStateOf(0)
        var noise by mutableIntStateOf(0)
        val stop = AtomicBoolean(false)
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent { Leaf("mine $mine noise-seen ${noise >= 0}") }

            // Another session's traffic, as far as this one can tell: state it reads, written
            // continuously from a thread it does not own, arriving through the global snapshot and
            // waking its recomposer at moments it has no control over.
            //
            // Parked briefly between writes. Without any gap a writer can keep the recomposer busy for
            // good, and then no wait for "nothing pending" can end — correctly, and the old wait for
            // Idle had the same property — which would be a test of starvation rather than of the
            // early return this one is looking for.
            val writer = thread(isDaemon = true) {
                while (!stop.get()) {
                    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { noise++ }
                    LockSupport.parkNanos(50_000)
                }
            }
            try {
                repeat(500) { i ->
                    within("transaction $i under concurrent writes") { host.transact { mine = i } }
                    // Read on the session's own thread straight after the wait, while the writer
                    // keeps the recomposer busy. An early return shows up as the previous value.
                    val rendered = host.confined { root.render() }
                    assertEquals("root(mine $i noise-seen true)", rendered, "after transaction $i")
                }
            } finally {
                stop.set(true)
                writer.join()
            }
        }
    }

    /** Runs [block], failing with [what] rather than hanging if it never returns. */
    private suspend fun <T> within(what: String, block: suspend () -> T): T =
        try {
            withTimeout(10.seconds) { block() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Hung waiting on $what", e)
        }
}

@Composable
private fun Leaf(name: String) {
    ComposeNode<TestNode, TestApplier>(
        factory = { TestNode(name) },
        update = { set(name) { this.name = it } },
    )
}

private fun TestNode.render(): String =
    if (children.isEmpty()) name else "$name(${children.joinToString(",") { it.render() }})"
