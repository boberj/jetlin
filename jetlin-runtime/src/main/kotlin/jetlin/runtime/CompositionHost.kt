package jetlin.runtime

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one Compose composition headlessly on the JVM, driving an arbitrary [Applier].
 *
 * Compose is usually started by a UI toolkit, which supplies the three pieces this class supplies
 * instead: a [Recomposer] to schedule recomposition, a [Composition] bound to an [Applier] that
 * materializes the tree, and a frame clock to pace the work. None of that requires a UI — the
 * runtime is happy to maintain any tree of nodes, and here that tree is a virtual DOM.
 *
 * One host is one live session. Everything is confined to a single-threaded [dispatcher] so that
 * event handling, recomposition and patch recording cannot interleave, which removes a whole class
 * of races without any locking, and gives natural per-session back-pressure.
 *
 * ## Knowing when a session is done
 *
 * Two questions get asked of a session, and they are deliberately different:
 *
 * - [awaitApplied]: has everything already signalled been recomposed and applied? This is what a
 *   patch needs, it is asked on every client event and before every outgoing message, and so it
 *   waits for nothing else.
 * - [awaitIdle]: has the session settled — recomposition applied, effects that were already queued
 *   run, and writes made outside any snapshot brought into view? This is what a test asserting on the
 *   tree, a first render, or a hibernation needs.
 *
 * Neither consults [Recomposer.currentState]; see [SessionActivity] for why that value cannot be
 * trusted to reach `Idle`. Both are answered from [Recomposer.hasPendingWork], which is computed when
 * it is read, together with a count of the tasks still queued on the session's dispatcher.
 *
 * [dispatcher] must run one task at a time. The host wraps it twice — once for recomposition, once for
 * effects — so the two can be counted apart; both wrappers queue onto it, so they still never run
 * concurrently.
 */
public class CompositionHost(
    applier: Applier<*>,
    framePolicy: FramePolicy = FramePolicy.Immediate,
    @OptIn(ExperimentalCoroutinesApi::class)
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : AutoCloseable {

    private val activity = SessionActivity()
    private val clock = framePolicy.toClock()

    /**
     * Context for everything that touches the composition: the recomposition loop, installing
     * content, transactions and confined reads. Excludes the [job] on purpose.
     */
    private val context: CoroutineContext =
        activity.track(dispatcher, SessionActivity.Lane.Recompose) + clock

    /**
     * Context the [Recomposer] launches effects in. The same thread as [context], counted on its own
     * lane so that waiting for a patch does not also mean waiting for a `LaunchedEffect` to finish
     * whatever it is doing.
     */
    private val effectContext: CoroutineContext =
        activity.track(dispatcher, SessionActivity.Lane.Effects) + clock

    private val job = Job()
    private val scope = CoroutineScope(context + job)

    private val recomposer = Recomposer(effectContext)
    private val composition = Composition(applier, recomposer)

    @Volatile
    private var failure: Throwable? = null

    /** The coroutine running [Recomposer.runRecomposeAndApplyChanges]; completes if composition dies. */
    private var runnerJob: Job? = null

    /** Number of recomposition passes that produced changes. Useful in tests and metrics. */
    public val changeCount: Long get() = recomposer.changeCount

    /**
     * Whether this composition can still do work.
     *
     * False once a composable has thrown: the recomposer stops and nothing will recompose again, so
     * the session it belongs to can only be discarded. Distinct from a handler throwing, which
     * leaves the composition perfectly healthy — the difference decides whether one interaction
     * failed or the whole session is gone, and only the caller can act on it.
     */
    public val isAlive: Boolean get() = failure == null && runnerJob?.isActive != false

    /**
     * Starts the recomposition loop and installs [content], returning once the initial composition
     * has been applied. The [Applier] has seen the complete initial tree by the time this returns.
     */
    public suspend fun setContent(content: @Composable () -> Unit) {
        GlobalSnapshotManager.ensureStarted()
        val runner = scope.launch {
            try {
                recomposer.runRecomposeAndApplyChanges()
            } catch (e: CancellationException) {
                // Normal shutdown via close().
            } catch (t: Throwable) {
                // Recorded rather than rethrown: the next wait reports it to whoever is driving the
                // session, instead of it escaping to the global uncaught-exception handler.
                failure = t
            }
        }
        runnerJob = runner
        // Anyone waiting has to hear that the loop is gone, whether it failed or was closed: its
        // unfinished work will never be done, and waiting for it would be forever.
        runner.invokeOnCompletion { activity.wakeAll() }
        // Wait for the loop to actually be running before composing. A startup check rather than an
        // idleness check — the one use of currentState that stays, because a loop that has not
        // started yet cannot be holding a stale value.
        recomposer.currentState.first { it == Recomposer.State.Idle || it == Recomposer.State.PendingWork }
        withContext(context) { composition.setContent(content) }
        awaitApplied()
    }

    /**
     * Applies [block] as a single atomic state mutation and returns once the resulting
     * recomposition has been applied.
     *
     * The mutable snapshot is the batching unit: however many state objects [block] writes, the
     * runtime sees one apply notification and performs one recomposition pass, so an event handler
     * that touches ten fields still yields exactly one patch message.
     *
     * Effects the handler starts — a coroutine launched to load something, say — are not waited for.
     * Their changes arrive in a following patch rather than holding up this one.
     */
    public suspend fun <T> transact(block: () -> T): T {
        val result = withContext(context) { Snapshot.withMutableSnapshot(block) }
        awaitApplied()
        return result
    }

    /**
     * Runs [block] on the session's confined thread.
     *
     * Anything that touches composition-owned mutable state — draining the patch buffer, reading
     * the node tree — must go through here, or it races the recomposer.
     */
    public suspend fun <T> confined(block: () -> T): T = withContext(context) { block() }

    /**
     * Suspends until every change already signalled to this session has been recomposed and applied,
     * or throws if the composition has died.
     *
     * The cheap question, asked on every client event and before every outgoing message. It does not
     * wait for effects, and it does not flush writes made outside a snapshot — those reach the
     * session through [GlobalSnapshotManager] and produce a patch of their own. When the session is
     * already quiet it returns without dispatching or allocating.
     */
    public suspend fun awaitApplied() {
        awaitUntil { activity.quietWhile(SessionActivity.Lane.Recompose) { !recomposer.hasPendingWork } }
    }

    /**
     * Suspends until the session has settled: recomposition applied, effects that were already
     * queued run, and state written outside any snapshot brought into view. Throws if the composition
     * has died.
     *
     * For anything that needs the tree to reflect everything that has happened so far — a test
     * asserting on what a user would see, a first render, a hibernation. Timers do not count: an
     * effect suspended in `delay()` is not queued work, so a ticking clock does not keep a session
     * from settling.
     *
     * An effect that never stops producing work — a loop that suspends without delaying, a collector
     * on a stream that never pauses — would keep a session from settling at all. [effectsBudget] bounds
     * how long to wait for effects once recomposition has been applied; when it runs out this returns
     * with the recomposition applied and the effects still going. The default waits indefinitely,
     * which is right for tests, where such an effect is a bug worth hanging on.
     */
    public suspend fun awaitIdle(effectsBudget: Duration = Duration.INFINITE) {
        // Writes made outside a snapshot — by a test, or by a store shared across sessions — stay
        // invisible to the recomposer until someone publishes them. Doing it here rather than waiting
        // for GlobalSnapshotManager's pump makes the wait deterministic.
        Snapshot.sendApplyNotifications()
        awaitApplied()
        withTimeoutOrNull(effectsBudget) {
            awaitUntil {
                // Published again on every check: an effect that has just run may itself have written
                // state outside a snapshot, and settling means that recomposition has happened too.
                // Published before the quiet check starts, so any task it dispatches is already
                // counted rather than spoiling the check it belongs to.
                Snapshot.sendApplyNotifications()
                activity.quietWhile(lane = null) { !recomposer.hasPendingWork }
            }
        }
        failure?.let { throw it }
    }

    /**
     * Suspends until [ready] holds, re-checking only when a lane of the session's dispatcher drains.
     *
     * Both callers build [ready] from [SessionActivity.quietWhile] around
     * [Recomposer.hasPendingWork]. That combination is sound because of two facts about the
     * Recomposer: work only leaves the collections `hasPendingWork` reads from inside a task running
     * on the session's dispatcher, and work arriving from another thread — a snapshot apply observer
     * — is added to them before the task that will process it is dispatched. So if no task ran while
     * `hasPendingWork` was read and it said false, there was nothing pending and nothing in transit.
     */
    private suspend inline fun awaitUntil(ready: () -> Boolean) {
        val runner = runnerJob ?: return // Nothing composed yet, so nothing to wait for.
        failure?.let { throw it }
        if (ready()) return // The common case: no dispatch, no allocation.

        activity.beginWaiting()
        try {
            while (true) {
                // Read before checking, so that a drain landing between the check and the suspension
                // below has already moved the generation on and cannot be slept through.
                val seen = activity.generation.value
                failure?.let { throw it }
                // A loop that has stopped will never do the work it was still holding.
                if (!runner.isActive) return
                if (ready()) return
                activity.generation.first { it != seen }
            }
        } finally {
            activity.endWaiting()
        }
    }

    override fun close() {
        recomposer.cancel()
        composition.dispose()
        scope.cancel()
    }
}
