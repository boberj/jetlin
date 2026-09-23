package jetlin.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest

/**
 * Tests [Fetch] from the point of view of a composition.
 *
 * The assertions check observable session behaviour rather than the internal state machine: whether the
 * placeholder was rendered, whether the arrival caused exactly one recomposition, and whether a second
 * reader caused a second request.
 *
 * Two of these tests hang instead of failing if reading ever writes snapshot state. Such a write would
 * invalidate the reader on every pass, so [CompositionHost.awaitIdle] would never return. That is
 * intentional: a hang points directly at a recomposition loop, whereas counting passes up to some limit
 * could stop early and hide it.
 */
class FetchTest {

    /** The scope fetches run on. It is deliberately separate from the session's dispatcher. */
    private val fetches = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun stopFetching() {
        fetches.cancel()
    }

    @Test
    fun `an unfetched value renders a placeholder, and its arrival is one pass`(): Unit = runTest {
        val arrival = CompletableDeferred<String>()
        val calls = AtomicInteger()
        val fetch = Fetch(fetches) {
            calls.incrementAndGet()
            arrival.await()
        }

        val root = TestNode("root")
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent { Text(fetch.value.text()) }
            assertEquals("root(…)", root.render(), "the session composed without waiting for the fetch")

            // Nothing else should happen now. If the read had written state, the recomposition loop
            // would show up here as a hang.
            host.awaitIdle()
            val settled = host.changeCount

            arrival.complete("hello")
            fetches.settle()
            host.awaitIdle()

            assertEquals("root(hello)", root.render())
            assertEquals(1, host.changeCount - settled, "one arrival should be one recomposition pass")
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `two readers cause one fetch`(): Unit = runTest {
        val calls = AtomicInteger()
        val arrival = CompletableDeferred<String>()
        val fetch = Fetch(fetches) {
            calls.incrementAndGet()
            arrival.await()
        }

        val root = TestNode("root")
        CompositionHost(TestApplier(root)).use { host ->
            // Two reads in one pass, as on a page that shows the same value twice.
            host.setContent {
                Text(fetch.value.text())
                Text(fetch.value.text())
            }
            arrival.complete("hello")
            fetches.settle()
            host.awaitIdle()

            assertEquals("root(hello,hello)", root.render())
            assertEquals(1, calls.get(), "the second read should have joined the outstanding fetch")
        }
    }

    @Test
    fun `a failed fetch renders as failed and leaves the session alive`(): Unit = runTest {
        val fetch = Fetch<String>(fetches) { error("no route to host") }

        val root = TestNode("root")
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent { Text(fetch.value.text()) }
            fetches.settle()
            host.awaitIdle()

            assertEquals("root(unavailable)", root.render())
            assertTrue(host.isAlive, "a failed fetch is a message, not the end of the session")
        }
    }

    @Test
    fun `a value inside its ttl is not fetched again`(): Unit = runTest {
        val calls = AtomicInteger()
        val clock = TestClock()
        val fetch = Fetch(fetches, ttl = 30.seconds, now = clock::now) {
            "call ${calls.incrementAndGet()}"
        }

        val root = TestNode("root")
        val page = Page(fetch)
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent { page.Content() }
            fetches.settle()
            host.awaitIdle()
            assertEquals("root(tick 0,call 1)", root.render())

            clock.advance(29.seconds)
            host.transact { page.tick++ } // Forces a recomposition, which reads the value again.
            fetches.settle()
            host.awaitIdle()

            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `past its ttl the old value is served while the new one is fetched`(): Unit = runTest {
        val clock = TestClock()
        val second = CompletableDeferred<String>()
        val revalidating = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val fetch = Fetch(fetches, ttl = 30.seconds, now = clock::now) {
            if (calls.incrementAndGet() == 1) {
                "first"
            } else {
                revalidating.complete(Unit)
                second.await()
            }
        }

        val root = TestNode("root")
        val page = Page(fetch)
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent { page.Content() }
            fetches.settle()
            host.awaitIdle()
            assertEquals("root(tick 0,first)", root.render())

            clock.advance(31.seconds)
            host.transact { page.tick++ }
            host.awaitIdle()
            // Wait until the revalidation has actually started. Scheduling a fetch only hands a coroutine
            // to a dispatcher, so checking the count immediately would be a race that could fail on a
            // busy machine.
            revalidating.await()

            // While the revalidation is running, the page still shows the previous value. Showing the
            // placeholder again here would be the flicker this design avoids.
            assertEquals("root(tick 1,first)", root.render())
            assertEquals(2, calls.get())

            second.complete("second")
            fetches.settle()
            host.awaitIdle()
            assertEquals("root(tick 1,second)", root.render())
        }
    }

    @Test
    fun `a revalidation that finds the same value recomposes nothing`(): Unit = runTest {
        val clock = TestClock()
        val calls = AtomicInteger()
        val fetch = Fetch(fetches, ttl = 30.seconds, now = clock::now) {
            calls.incrementAndGet()
            "unchanged"
        }

        val root = TestNode("root")
        val page = Page(fetch)
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent { page.Content() }
            fetches.settle()
            host.awaitIdle()

            clock.advance(31.seconds)
            // Recompose once, which re-reads the value and schedules the revalidation. Counting from
            // after this pass isolates the effect of the arrival.
            host.transact { page.tick++ }
            host.awaitIdle()
            val revalidating = host.changeCount

            fetches.settle()
            host.awaitIdle()

            assertEquals(2, calls.get(), "the copy was stale, so it should have been re-fetched")
            assertEquals(
                0,
                host.changeCount - revalidating,
                "an unchanged value must not recompose anyone: that is what serving stale is worth",
            )
        }
    }

    @Test
    fun `a failed revalidation keeps the value it had`(): Unit = runTest {
        val clock = TestClock()
        val calls = AtomicInteger()
        val fetch = Fetch(fetches, ttl = 30.seconds, now = clock::now) {
            if (calls.incrementAndGet() == 1) "first" else error("gone away")
        }

        val root = TestNode("root")
        val page = Page(fetch)
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent { page.Content() }
            fetches.settle()
            host.awaitIdle()

            clock.advance(31.seconds)
            host.transact { page.tick++ }
            fetches.settle()
            host.awaitIdle()

            assertEquals(2, calls.get())
            assertEquals(
                "root(tick 1,first)",
                root.render(),
                "a failed refresh is no reason to lose good data",
            )
        }
    }


    @Test
    fun `a watched value keeps refreshing while a page shows it, and stops when the page goes`(): Unit =
        runTest {
            val calls = AtomicInteger()
            val fetch = Fetch(fetches) { "call ${calls.incrementAndGet()}" }

            val root = TestNode("root")
            val host = CompositionHost(TestApplier(root))
            host.setContent { Text(fetch.fresh(every = 30.milliseconds).text()) }

            // Three iterations of the loop, so three requests that nothing else triggered.
            awaitCalls(calls, atLeast = 3)
            host.close()

            // After the watch stops, no more requests are made.
            val whenClosed = calls.get()
            realDelay(150.milliseconds)
            assertEquals(whenClosed, calls.get(), "a page that is gone should not still be polling")
        }

    @Test
    fun `two sessions watching one value share one loop`(): Unit = runTest {
        val calls = AtomicInteger()
        val fetch = Fetch(fetches) { "call ${calls.incrementAndGet()}" }

        val first = CompositionHost(TestApplier(TestNode("root")))
        val second = CompositionHost(TestApplier(TestNode("root")))
        first.setContent { Text(fetch.fresh(every = 30.milliseconds).text()) }
        second.setContent { Text(fetch.fresh(every = 30.milliseconds).text()) }

        realDelay(200.milliseconds)
        val both = calls.get()

        // A 30 ms loop runs about six times in 200 ms. Two separate loops would make about a dozen
        // requests; one shared loop means the sessions share each request.
        assertTrue(both in 2..9, "expected one loop's worth of requests, got $both")

        // One watcher stops. The other is still watching, so polling continues.
        first.close()
        awaitCalls(calls, atLeast = both + 2)

        // Polling stops only when the last watcher stops.
        second.close()
        val whenBothClosed = calls.get()
        realDelay(150.milliseconds)
        assertEquals(whenBothClosed, calls.get())
    }

    @Test
    fun `the shorter of two watch intervals wins`(): Unit = runTest {
        val calls = AtomicInteger()
        val fetch = Fetch(fetches) { "call ${calls.incrementAndGet()}" }

        val slow = fetch.watch(every = 10.seconds)
        realDelay(60.milliseconds)
        assertEquals(0, calls.get(), "a ten second watch should not have fired yet")

        // A watcher with a shorter interval joins, and the loop switches to the shorter interval.
        val quick = fetch.watch(every = 25.milliseconds)
        awaitCalls(calls, atLeast = 2)

        quick.stop()
        slow.stop()
    }

    @Test
    fun `invalidate clears a failure and the next read tries again`(): Unit = runTest {
        val calls = AtomicInteger()
        val fetch = Fetch(fetches) {
            if (calls.incrementAndGet() == 1) error("no route to host") else "at last"
        }

        val root = TestNode("root")
        CompositionHost(TestApplier(root)).use { host ->
            host.setContent { Text(fetch.value.text()) }
            fetches.settle()
            host.awaitIdle()
            assertEquals("root(unavailable)", root.render())

            // This is what a retry button does. The failed state is reset first, since there is no good
            // value on screen that the reset could hide.
            host.transact { fetch.invalidate() }
            fetches.settle()
            host.awaitIdle()

            assertEquals(2, calls.get())
            assertEquals("root(at last)", root.render())
        }
    }
}


/** Waits in real time until the poll loop has made at least [atLeast] requests. */
private suspend fun awaitCalls(calls: AtomicInteger, atLeast: Int) {
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) {
            while (calls.get() < atLeast) delay(5)
        }
    }
}

/** A real-time wait. The poll loop runs on the real clock, not on `runTest`'s virtual one. */
private suspend fun realDelay(duration: kotlin.time.Duration) {
    withContext(Dispatchers.Default) { delay(duration) }
}

/**
 * A page with its own state, so a test can trigger a recomposition that doesn't involve the fetched value.
 *
 * A value is only read again when something recomposes. Staleness tests need a way to cause that
 * recomposition independently of the value; otherwise they would be asserting on a pass that never ran.
 */
private class Page(private val fetch: Fetch<String>) {
    var tick: Int by mutableStateOf(0)

    @Composable
    fun Content() {
        Text("tick $tick")
        Text(fetch.value.text())
    }
}

/** Formats the three [Fetched] states as display text. */
private fun Fetched<String>.text(): String = when (this) {
    is Fetched.Loading -> "…"
    is Fetched.Failed -> "unavailable"
    is Fetched.Ready -> value
}

/**
 * Waits for every fetch started on this scope to finish.
 *
 * Each fetch is an ordinary coroutine, so the test can join the scope's children directly instead of
 * sleeping for a guessed amount of time.
 */
private suspend fun CoroutineScope.settle() {
    coroutineContext.job.children.toList().forEach { it.join() }
}

/** A manually advanced clock, so tests don't wait for TTLs to expire in real time. */
private class TestClock {
    private var nanos = 0L

    fun now(): Long = nanos

    fun advance(by: kotlin.time.Duration) {
        nanos += by.inWholeNanoseconds
    }
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
