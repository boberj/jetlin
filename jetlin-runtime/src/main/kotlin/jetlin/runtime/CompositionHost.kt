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
 * Runs one Compose composition on the JVM without a UI toolkit, driving any [Applier].
 *
 * A UI toolkit normally provides three things to start Compose: a [Recomposer] that schedules
 * recomposition, a [Composition] bound to an [Applier] that builds the node tree, and a frame clock
 * that paces the work. This class provides all three. Compose doesn't need a screen to maintain a
 * tree of nodes, and in Jetlin that tree is a virtual DOM.
 *
 * Each host is one live session. All work runs on a single-threaded [dispatcher], so event handling,
 * recomposition, and patch recording never interleave. That rules out a class of races without any
 * locks, and it gives each session back-pressure for free.
 *
 * ## Waiting for a session
 *
 * Callers wait for one of two conditions, and the difference matters:
 *
 * - [awaitApplied] returns when every change already signaled has been recomposed and applied. A
 *   patch needs exactly this. The server calls it on every client event and before every outgoing
 *   message, so it waits for nothing else.
 * - [awaitIdle] returns when the session has settled: recomposition is applied, queued effects have
 *   run, and writes made outside a snapshot are visible. Tests that assert on the tree, the first
 *   render, and hibernation need this.
 *
 * Neither method reads [Recomposer.currentState], because that value can't be trusted to reach
 * `Idle`; [SessionActivity] explains why. Both combine [Recomposer.hasPendingWork], which is computed
 * when read, with a count of the tasks still queued on the session's dispatcher.
 *
 * @param applier the applier that receives the composition's tree changes.
 * @param framePolicy how often recomposition may run. See [FramePolicy].
 * @param dispatcher the session's dispatcher. It must run one task at a time. The host wraps it
 *   twice, once for recomposition and once for effects, so it can count the two separately. Both
 *   wrappers queue onto the same dispatcher, so their tasks still never run concurrently.
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
     * The context for everything that touches the composition: the recomposition loop, installing
     * content, transactions, and confined reads. It deliberately leaves out [job].
     */
    private val context: CoroutineContext =
        activity.track(dispatcher, SessionActivity.Lane.Recompose) + clock

    /**
     * The context the [Recomposer] launches effects in. It runs on the same thread as [context] but
     * is counted on its own lane, so waiting for a patch doesn't also wait for a `LaunchedEffect` to
     * finish its work.
     */
    private val effectContext: CoroutineContext =
        activity.track(dispatcher, SessionActivity.Lane.Effects) + clock

    private val job = Job()
    private val scope = CoroutineScope(context + job)

    private val recomposer = Recomposer(effectContext)
    private val composition = Composition(applier, recomposer)

    @Volatile
    private var failure: Throwable? = null

    /**
     * The coroutine that runs [Recomposer.runRecomposeAndApplyChanges]. It completes if the
     * composition dies.
     */
    private var runnerJob: Job? = null

    /** The number of recomposition passes that produced changes. Tests and metrics read it. */
    public val changeCount: Long get() = recomposer.changeCount

    /**
     * Whether this composition can still do work.
     *
     * This becomes `false` once a composable throws. The recomposer then stops for good, and the
     * only thing left to do with the session is discard it. A throwing event handler is different:
     * it leaves the composition healthy. The caller uses this property to tell whether one
     * interaction failed or the whole session is gone.
     */
    public val isAlive: Boolean get() = failure == null && runnerJob?.isActive != false

    /**
     * Starts the recomposition loop and installs [content].
     *
     * Returns once the initial composition has been applied, so the [Applier] has seen the complete
     * initial tree.
     */
    public suspend fun setContent(content: @Composable () -> Unit) {
        GlobalSnapshotManager.ensureStarted()
        val runner = scope.launch {
            try {
                recomposer.runRecomposeAndApplyChanges()
            } catch (e: CancellationException) {
                // close() cancelled the loop. This is a normal shutdown.
            } catch (t: Throwable) {
                // Record the failure instead of rethrowing it. The next wait then reports it to
                // whoever drives the session, rather than the global uncaught-exception handler.
                failure = t
            }
        }
        runnerJob = runner
        // Wake every waiter when the loop ends, whether it failed or was closed. Its unfinished
        // work will never be done, so waiting for it would never end.
        runner.invokeOnCompletion { activity.wakeAll() }
        // Wait until the loop is running before composing. This is the one place that reads
        // currentState. It's safe here because it checks startup, not idleness, and a loop that
        // hasn't started can't be holding a stale value.
        recomposer.currentState.first { it == Recomposer.State.Idle || it == Recomposer.State.PendingWork }
        withContext(context) { composition.setContent(content) }
        awaitApplied()
    }

    /**
     * Runs [block] as one atomic state change and returns once the resulting recomposition is applied.
     *
     * [block] runs in a mutable snapshot, which is the unit of batching. However many state objects
     * it writes, the runtime sees one apply notification and runs one recomposition pass. An event
     * handler that writes ten fields still produces exactly one patch message.
     *
     * This method doesn't wait for effects that [block] starts, such as a coroutine launched to load
     * data. Their changes arrive in a later patch instead of delaying this one.
     *
     * @return the value [block] returns.
     */
    public suspend fun <T> transact(block: () -> T): T {
        val result = withContext(context) { Snapshot.withMutableSnapshot(block) }
        awaitApplied()
        return result
    }

    /**
     * Runs [block] on the session's thread.
     *
     * Use this for anything that touches mutable state the composition owns, such as draining the
     * patch buffer or reading the node tree. Code that skips it races the recomposer.
     */
    public suspend fun <T> confined(block: () -> T): T = withContext(context) { block() }

    /**
     * Suspends until every change already signaled to this session has been recomposed and applied.
     *
     * This is the cheap wait, and the server calls it on every client event and before every
     * outgoing message. It doesn't wait for effects. It also doesn't flush writes made outside a
     * snapshot: those reach the session through [GlobalSnapshotManager] and produce their own patch.
     * If the session is already quiet, this returns without dispatching or allocating.
     *
     * @throws Throwable the failure that killed the composition, if a composable threw.
     */
    public suspend fun awaitApplied() {
        awaitUntil { activity.quietWhile(SessionActivity.Lane.Recompose) { !recomposer.hasPendingWork } }
    }

    /**
     * Suspends until the session has settled.
     *
     * A settled session has applied its recomposition, run the effects that were already queued,
     * and made state written outside any snapshot visible. Use this when the tree must reflect
     * everything that has happened so far: in a test that asserts on what a user sees, for the first
     * render, and before hibernating. Timers don't count as work. An effect suspended in `delay()`
     * isn't queued, so a ticking clock doesn't keep a session from settling.
     *
     * An effect that never stops producing work, such as a loop that suspends without delaying or a
     * collector on a stream that never pauses, keeps a session from ever settling.
     *
     * @param effectsBudget how long to wait for effects after recomposition is applied. When the
     *   budget runs out, this returns with recomposition applied and effects still running. The
     *   default waits forever, which suits tests: there, an effect that never settles is a bug, and
     *   hanging exposes it.
     * @throws Throwable the failure that killed the composition, if a composable threw.
     */
    public suspend fun awaitIdle(effectsBudget: Duration = Duration.INFINITE) {
        // Writes made outside a snapshot, by a test or by a store that several sessions share, stay
        // invisible to the recomposer until something publishes them. Publishing here, instead of
        // waiting for GlobalSnapshotManager to do it, makes this wait deterministic.
        Snapshot.sendApplyNotifications()
        awaitApplied()
        withTimeoutOrNull(effectsBudget) {
            awaitUntil {
                // Publish again on every check. An effect that just ran might have written state
                // outside a snapshot, and the session hasn't settled until that recomposition has
                // happened too. Publishing before the quiet check starts means any task it
                // dispatches is already counted and can't spoil the check.
                Snapshot.sendApplyNotifications()
                activity.quietWhile(lane = null) { !recomposer.hasPendingWork }
            }
        }
        failure?.let { throw it }
    }

    /**
     * Suspends until [ready] returns `true`, checking again only when a lane of the session's
     * dispatcher drains.
     *
     * Both callers build [ready] by wrapping [Recomposer.hasPendingWork] in
     * [SessionActivity.quietWhile]. That combination is sound because of two facts about the
     * Recomposer:
     *
     * - Work leaves the collections that `hasPendingWork` reads only inside a task running on the
     *   session's dispatcher.
     * - Work arriving from another thread, such as from a snapshot apply observer, is added to those
     *   collections before the task that processes it is dispatched.
     *
     * So if no task ran while `hasPendingWork` was read, and it returned `false`, then nothing was
     * pending and nothing was in transit.
     */
    private suspend inline fun awaitUntil(ready: () -> Boolean) {
        val runner = runnerJob ?: return // Nothing has been composed, so there's nothing to wait for.
        failure?.let { throw it }
        if (ready()) return // The common case, with no dispatch and no allocation.

        activity.beginWaiting()
        try {
            while (true) {
                // Read the generation before checking. A drain that lands between the check and the
                // suspension below then shows up as a new generation, so it can't be missed.
                val seen = activity.generation.value
                failure?.let { throw it }
                // A stopped loop never does the work it was still holding, so stop waiting for it.
                if (!runner.isActive) return
                if (ready()) return
                activity.generation.first { it != seen }
            }
        } finally {
            activity.endWaiting()
        }
    }

    /** Stops the recomposition loop and disposes of the composition. */
    override fun close() {
        recomposer.cancel()
        composition.dispose()
        scope.cancel()
    }
}
