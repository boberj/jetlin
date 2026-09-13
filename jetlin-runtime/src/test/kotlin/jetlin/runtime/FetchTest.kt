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
 * A value that arrives late, seen from a composition.
 *
 * Every assertion here is about what a session does rather than about the state machine: whether the
 * placeholder reached the tree, whether the arrival cost one pass, whether a second reader cost a second
 * request. The state machine is only interesting because of those.
 *
 * Two of these tests would hang rather than fail if the design were wrong, and that is deliberate: a read
 * that wrote snapshot state would invalidate its own reader every pass, so [CompositionHost.awaitIdle]
 * would never return. A test that hangs on a recomposition loop is a truer report than one that counts
 * passes and happens to stop.
 */
class FetchTest {

    /** Where fetches run: never the session's dispatcher, which is the point. */
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

            // Nothing further should happen on its own. If the read had written state, this would be
            // where the loop showed up — as a hang, not as a wrong number.
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
            // Two readers in one pass, which is what a page showing the same value twice looks like.
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
            host.transact { page.tick++ } // A recomposition, which is what re-reads the value.
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
            // Waiting for the revalidation to be under way rather than assuming it: scheduling a fetch
            // hands a coroutine to a dispatcher, and asserting on the count before that coroutine has
            // run is a race the test would lose on a loaded machine.
            revalidating.await()

            // The revalidation is outstanding and the page still shows something true. Flipping back to
            // the placeholder here is the flicker this design exists to avoid.
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
            // One pass, which re-reads the value and so schedules the revalidation. Counting from after
            // it means the only thing left to count is what the arrival did.
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

            // Three turns of the loop, which is three requests nobody asked for by hand.
            awaitCalls(calls, atLeast = 3)
            host.close()

            // And then it stops: nobody is looking, so nobody is paying.
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

        // Six turns of a 30ms loop in 200ms, give or take. Two loops would be a dozen, and the gap is
        // the point: sessions showing the same thing are one request between them, not one each.
        assertTrue(both in 2..9, "expected one loop's worth of requests, got $both")

        // One of them leaves. The value is still on somebody's page, so it is still being kept fresh.
        first.close()
        awaitCalls(calls, atLeast = both + 2)

        // The last one leaves, and only then does it stop.
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

        // A page that wants it fresher joins, and gets what it asked for rather than what was there.
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

            // What a retry button does. The failure is cleared first, because there is no value on screen
            // to flicker away from.
            host.transact { fetch.invalidate() }
            fetches.settle()
            host.awaitIdle()

            assertEquals(2, calls.get())
            assertEquals("root(at last)", root.render())
        }
    }
}


/** Waits, in real time, for a poll loop to have run at least [atLeast] times. */
private suspend fun awaitCalls(calls: AtomicInteger, atLeast: Int) {
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) {
            while (calls.get() < atLeast) delay(5)
        }
    }
}

/** A real wait, since a poll loop runs on a real clock and `runTest` does not. */
private suspend fun realDelay(duration: kotlin.time.Duration) {
    withContext(Dispatchers.Default) { delay(duration) }
}

/**
 * A page with something of its own to recompose for.
 *
 * A value is only re-read when something recomposes, so a test about staleness needs a reason to
 * recompose that is not the value itself — otherwise it asserts on a pass that never happened.
 */
private class Page(private val fetch: Fetch<String>) {
    var tick: Int by mutableStateOf(0)

    @Composable
    fun Content() {
        Text("tick $tick")
        Text(fetch.value.text())
    }
}

/** The three states as a page would show them. */
private fun Fetched<String>.text(): String = when (this) {
    is Fetched.Loading -> "…"
    is Fetched.Failed -> "unavailable"
    is Fetched.Ready -> value
}

/**
 * Waits for every fetch this scope has started.
 *
 * Joining the scope's children rather than polling: a fetch is an ordinary coroutine, so the test can
 * wait for exactly the thing it is about instead of sleeping and hoping.
 */
private suspend fun CoroutineScope.settle() {
    coroutineContext.job.children.toList().forEach { it.join() }
}

/** A clock the test moves by hand, so nothing here waits for a TTL in real time. */
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
