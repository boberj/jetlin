package jetlin.runtime

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Knows whether a session still has work queued, by counting it rather than by asking the
 * [androidx.compose.runtime.Recomposer] for its state.
 *
 * ## Why not `Recomposer.currentState`
 *
 * `currentState` is not computed when it is read. It is a cached value, rewritten only when the
 * Recomposer calls its private `deriveStateLocked()`, and there are paths that empty one of that
 * function's inputs without calling it again. Two are known, both involving movable content and both
 * present from 1.5 through 1.13.0-alpha02:
 *
 * - `composeInitial` empties `movableContentAwaitingInsert` in `performInitialMovableContentInserts`
 *   after `insertMovableContent` had derived `PendingWork`; the woken loop then leaves through the
 *   early return in `recordComposerModifications`, which skips that function's own derivation.
 * - A frame derives its final state while `movableContentRemoved` is still full, and only afterwards
 *   empties it in `discardUnusedMovableContentState`.
 *
 * Either way the state reads `PendingWork` indefinitely while nothing is pending and the loop is
 * asleep. A host that waits for `Idle` waits until some unrelated change happens to derive the state
 * again — and in a server session, where the inbound loop processes one event at a time and the
 * sender waits before every patch, nothing unrelated may ever come. The session deadlocks.
 *
 * Compose UI never meets this because nothing in it waits for `Idle`: Android reads `currentState`
 * only as a liveness check, and the desktop scene and both test harnesses decide idleness from
 * `hasPendingWork` and from whether the dispatchers the Recomposer runs on still have tasks queued.
 * This class is that second half, shaped for a server rather than a window.
 *
 * ## What is counted
 *
 * Every task dispatched to the session is counted from the moment it is queued until it finishes,
 * on one of two lanes over the same serial dispatcher: [Lane.Recompose] for the recomposition loop and
 * everything the host runs to touch the composition, and [Lane.Effects] for what the Recomposer
 * launches as effects — `LaunchedEffect`, `rememberCoroutineScope`. Two lanes rather than one so that
 * "the patch is ready" and "everything has settled" can be told apart; the same dispatcher underneath
 * so that they still never run at the same time, which is the whole point of confining a session.
 * This is the desktop scene's split between its recompose and effect dispatchers, which likewise
 * share one thread.
 *
 * A `delay()` is not a task. The timer re-dispatches the coroutine when it fires, and only then is it
 * counted, so an effect ticking once a second never makes a session look busy in between — the same
 * distinction the desktop dispatcher draws between immediate and delayed tasks.
 *
 * ## Why waking waiters is event-driven
 *
 * The desktop test harness polls: check, render a frame, sleep, check again. A window already renders
 * at frame rate, so that costs nothing there. A server holds thousands of mostly idle sessions and
 * waits for idleness on every event and before every patch, so polling would cost CPU and latency
 * in every one of them. Instead a lane reaching zero bumps [generation], and anyone waiting re-checks
 * then. When nobody is waiting — the usual case — a drained lane costs one atomic read.
 */
internal class SessionActivity {

    enum class Lane { Recompose, Effects }

    /** Tasks queued or running on [Lane.Recompose]. */
    internal val recompose = AtomicInteger()

    /** Tasks queued or running on either lane. */
    internal val all = AtomicInteger()

    /** Every task ever dispatched on either lane. Only ever increases; see [quietWhile]. */
    internal val dispatched = AtomicLong()

    private val waiting = AtomicInteger()
    private val drains = MutableStateFlow(0L)

    /**
     * Evaluates [quiet] and reports whether it held while no task on [lane] (or on either lane, when
     * null) was queued, running, or dispatched.
     *
     * A task count and a condition cannot be read atomically together, and a naive pair of reads
     * lies. Read the count first and it can say zero just before a task is dispatched; that task can
     * then run and take work out of the Recomposer's collections before the condition is read, so the
     * condition says "nothing pending" in the middle of a frame. Read the condition first and a task
     * can finish between the reads, hiding the work it left behind.
     *
     * So the count is read on both sides, and so is [dispatched], which cannot go back to an earlier
     * value the way a count can. If nothing was queued at the start, nothing is queued at the end,
     * and nothing was dispatched in between, then no task ran while [quiet] was being evaluated — and
     * a condition read while nothing runs is a condition that can be believed.
     */
    internal inline fun quietWhile(lane: Lane?, quiet: () -> Boolean): Boolean {
        val tasks = if (lane == Lane.Recompose) recompose else all
        val before = dispatched.get()
        if (tasks.get() != 0) return false
        if (!quiet()) return false
        return tasks.get() == 0 && dispatched.get() == before
    }

    /**
     * Advances whenever a lane empties while someone is waiting, and when the session ends.
     *
     * A counter rather than an event so that a wake-up cannot be missed: a waiter reads it, checks
     * its condition, and then suspends until it has moved on from the value it read. A drain that
     * lands between the check and the suspension has already moved it.
     */
    val generation: StateFlow<Long> get() = drains

    /** Wraps [dispatcher] so that everything dispatched through it is counted on [lane]. */
    @OptIn(InternalCoroutinesApi::class)
    fun track(dispatcher: CoroutineDispatcher, lane: Lane): CoroutineDispatcher =
        // Delays are forwarded when the wrapped dispatcher provides them, so that a dispatcher with
        // its own notion of time keeps it. Without this the wrapper would silently send every
        // delay() to the default timer. The default session dispatcher, limitedParallelism(1), is
        // itself a Delay that forwards to that timer, so for it the two are the same.
        if (dispatcher is Delay) DelayingTrackedDispatcher(dispatcher, dispatcher, this, lane)
        else TrackedDispatcher(dispatcher, this, lane)

    /**
     * Registers someone about to wait. Must be called before they first read [generation], so that
     * any lane draining after their check is guaranteed to see them and wake them.
     */
    fun beginWaiting() {
        waiting.incrementAndGet()
    }

    fun endWaiting() {
        waiting.decrementAndGet()
    }

    /** Wakes every waiter unconditionally, for when the session itself has ended. */
    fun wakeAll() {
        drains.update { it + 1 }
    }

    internal fun started(lane: Lane) {
        // Counted before the task is handed to the dispatcher: work has to be visible from the moment
        // it exists, or a waiter could read the counter between the hand-over and the task starting.
        if (lane == Lane.Recompose) recompose.incrementAndGet()
        all.incrementAndGet()
        dispatched.incrementAndGet()
    }

    internal fun finished(lane: Lane) {
        var drained = all.decrementAndGet() == 0
        if (lane == Lane.Recompose && recompose.decrementAndGet() == 0) drained = true
        if (drained && waiting.get() > 0) wakeAll()
    }
}

/**
 * A dispatcher that counts what passes through it and otherwise defers entirely to [delegate].
 *
 * Allocates one wrapping [Runnable] per dispatch. A hand-written serial executor could avoid that,
 * at the cost of reimplementing what `limitedParallelism` already does well; worth doing only if a
 * profile ever shows it.
 */
private open class TrackedDispatcher(
    private val delegate: CoroutineDispatcher,
    private val activity: SessionActivity,
    private val lane: SessionActivity.Lane,
) : CoroutineDispatcher() {

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        activity.started(lane)
        val counted = Runnable {
            try {
                block.run()
            } finally {
                activity.finished(lane)
            }
        }
        try {
            delegate.dispatch(context, counted)
        } catch (t: Throwable) {
            // A task that was never queued will never finish, and a count it left behind would make
            // the session look busy forever.
            activity.finished(lane)
            throw t
        }
    }

    override fun toString(): String = "Jetlin[$lane]($delegate)"
}

@OptIn(InternalCoroutinesApi::class)
private class DelayingTrackedDispatcher(
    delegate: CoroutineDispatcher,
    delay: Delay,
    activity: SessionActivity,
    lane: SessionActivity.Lane,
) : TrackedDispatcher(delegate, activity, lane), Delay by delay
