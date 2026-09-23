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
 * Tracks whether a session still has work queued, by counting tasks instead of asking the
 * [androidx.compose.runtime.Recomposer] for its state.
 *
 * ## Why not `Recomposer.currentState`
 *
 * `currentState` isn't computed when you read it. It's a cached value that changes only when the
 * Recomposer calls its private `deriveStateLocked()` function, and some code paths empty one of
 * that function's inputs without calling it again. Two such paths are known. Both involve movable
 * content, and both exist in every version from 1.5 through 1.13.0-alpha02:
 *
 * - `insertMovableContent` derives `PendingWork`. Then `composeInitial` empties
 *   `movableContentAwaitingInsert` in `performInitialMovableContentInserts`. The loop wakes, and
 *   leaves through the early return in `recordComposerModifications`, which skips that function's
 *   own derivation.
 * - A frame derives its final state while `movableContentRemoved` still has entries, and empties it
 *   afterward in `discardUnusedMovableContentState`.
 *
 * In both cases the state reads `PendingWork` indefinitely, while nothing is pending and the loop
 * sleeps. A host that waits for `Idle` then waits until some unrelated change derives the state
 * again. In a server session, that change might never come: the inbound loop handles one event at a
 * time, and the sender waits before every patch. The session deadlocks.
 *
 * Compose UI never hits this, because nothing in it waits for `Idle`. Android reads `currentState`
 * only to check that the loop is alive. The desktop scene and both test harnesses decide idleness
 * from `hasPendingWork` together with whether the Recomposer's dispatchers still have tasks queued.
 * This class provides that second half, adapted for a server instead of a window.
 *
 * ## What it counts
 *
 * Each task dispatched to the session is counted from the moment it's queued until it finishes.
 * There are two lanes over the same serial dispatcher:
 *
 * - [Lane.Recompose] counts the recomposition loop and everything the host runs to touch the
 *   composition.
 * - [Lane.Effects] counts what the Recomposer launches as effects, such as `LaunchedEffect` and
 *   `rememberCoroutineScope`.
 *
 * Two lanes let the host tell "the patch is ready" apart from "everything has settled." Because
 * both lanes share one dispatcher, their tasks still never run at the same time, which is the point
 * of confining a session to one thread. The desktop scene splits its recompose and effect
 * dispatchers the same way, over one shared thread.
 *
 * A `delay()` isn't a task. When the timer fires, it dispatches the coroutine again, and only then
 * is it counted. An effect that ticks once a second therefore never makes the session look busy
 * between ticks. The desktop dispatcher draws the same line between immediate and delayed tasks.
 *
 * ## Why waiters are woken by events
 *
 * The desktop test harness polls: it checks, renders a frame, sleeps, and checks again. A window
 * already renders at frame rate, so polling costs it nothing. A server holds thousands of mostly
 * idle sessions and waits for idleness on every event and before every patch, so polling would add
 * CPU time and latency to all of them. Instead, when a lane reaches zero it advances [generation],
 * and each waiter checks again. When nobody is waiting, which is the usual case, a drained lane
 * costs one atomic read.
 */
internal class SessionActivity {

    /** The two kinds of task the session counts separately. See the class description. */
    enum class Lane { Recompose, Effects }

    /** The number of tasks queued or running on [Lane.Recompose]. */
    internal val recompose = AtomicInteger()

    /** The number of tasks queued or running on either lane. */
    internal val all = AtomicInteger()

    /** The number of tasks ever dispatched on either lane. It never decreases. See [quietWhile]. */
    internal val dispatched = AtomicLong()

    private val waiting = AtomicInteger()
    private val drains = MutableStateFlow(0L)

    /**
     * Evaluates [quiet] and returns whether it held while no task was queued, running, or dispatched.
     *
     * A task count and a condition can't be read together atomically, and reading them one after
     * the other gives wrong answers:
     *
     * - If you read the count first, it can be zero just before a task is dispatched. That task can
     *   then run and remove work from the Recomposer's collections before you read the condition,
     *   so the condition reports nothing pending in the middle of a frame.
     * - If you read the condition first, a task can finish between the two reads and hide the work
     *   it left behind.
     *
     * So this method reads the count before and after [quiet], and does the same with [dispatched],
     * which, unlike a count, can't return to an earlier value. If nothing was queued at the start,
     * nothing is queued at the end, and nothing was dispatched in between, then no task ran while
     * [quiet] was evaluated. A condition read while nothing runs can be trusted.
     *
     * @param lane the lane to check, or `null` to check both lanes.
     */
    internal inline fun quietWhile(lane: Lane?, quiet: () -> Boolean): Boolean {
        val tasks = if (lane == Lane.Recompose) recompose else all
        val before = dispatched.get()
        if (tasks.get() != 0) return false
        if (!quiet()) return false
        return tasks.get() == 0 && dispatched.get() == before
    }

    /**
     * A counter that advances when a lane empties while someone is waiting, and when the session ends.
     *
     * It's a counter instead of an event so that no waiter can miss a wake-up. A waiter reads the
     * counter, checks its condition, and then suspends until the counter differs from the value it
     * read. A drain that lands between the check and the suspension has already advanced it.
     */
    val generation: StateFlow<Long> get() = drains

    /** Wraps [dispatcher] so that everything dispatched through it is counted on [lane]. */
    @OptIn(InternalCoroutinesApi::class)
    fun track(dispatcher: CoroutineDispatcher, lane: Lane): CoroutineDispatcher =
        // Forward delays when the wrapped dispatcher implements Delay, so a dispatcher with its own
        // clock keeps it. Otherwise the wrapper would send every delay() to the default timer
        // without anyone noticing. The default session dispatcher, limitedParallelism(1), is a
        // Delay that forwards to the default timer anyway, so for it the result is the same.
        if (dispatcher is Delay) DelayingTrackedDispatcher(dispatcher, dispatcher, this, lane)
        else TrackedDispatcher(dispatcher, this, lane)

    /**
     * Registers a waiter.
     *
     * Call this before the waiter first reads [generation]. That guarantees that any lane that
     * drains after the waiter's check sees it and wakes it.
     */
    fun beginWaiting() {
        waiting.incrementAndGet()
    }

    /** Unregisters a waiter that [beginWaiting] registered. */
    fun endWaiting() {
        waiting.decrementAndGet()
    }

    /** Wakes every waiter unconditionally. The host calls this when the session ends. */
    fun wakeAll() {
        drains.update { it + 1 }
    }

    /** Counts a task on [lane]. Call it before handing the task to the dispatcher. */
    internal fun started(lane: Lane) {
        // Count the task before the dispatcher has it. Work must be visible from the moment it
        // exists, or a waiter could read the counter between the handover and the task starting.
        if (lane == Lane.Recompose) recompose.incrementAndGet()
        all.incrementAndGet()
        dispatched.incrementAndGet()
    }

    /** Stops counting a task on [lane], and wakes waiters if that drained a lane. */
    internal fun finished(lane: Lane) {
        var drained = all.decrementAndGet() == 0
        if (lane == Lane.Recompose && recompose.decrementAndGet() == 0) drained = true
        if (drained && waiting.get() > 0) wakeAll()
    }
}

/**
 * A dispatcher that counts the tasks passing through it and otherwise defers to [delegate].
 *
 * Each dispatch allocates one wrapping [Runnable]. A hand-written serial executor could avoid that,
 * but it would reimplement what `limitedParallelism` already does well. Write one only if a profile
 * shows the allocation matters.
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
            // A task that was never queued never finishes. If it stayed counted, the session would
            // look busy forever.
            activity.finished(lane)
            throw t
        }
    }

    override fun toString(): String = "Jetlin[$lane]($delegate)"
}

/** A [TrackedDispatcher] that also forwards [Delay] to the dispatcher it wraps. */
@OptIn(InternalCoroutinesApi::class)
private class DelayingTrackedDispatcher(
    delegate: CoroutineDispatcher,
    delay: Delay,
    activity: SessionActivity,
    lane: SessionActivity.Lane,
) : TrackedDispatcher(delegate, activity, lane), Delay by delay
