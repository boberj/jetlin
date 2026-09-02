package jetlin.runtime

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

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
 */
public class CompositionHost(
    applier: Applier<*>,
    framePolicy: FramePolicy = FramePolicy.Immediate,
    @OptIn(ExperimentalCoroutinesApi::class)
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : AutoCloseable {

    /** Context used for all composition-touching work. Excludes the [job] on purpose. */
    private val context: CoroutineContext = dispatcher + framePolicy.toClock()
    private val job = Job()
    private val scope = CoroutineScope(context + job)

    private val recomposer = Recomposer(context)
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
     * has fully settled. The [Applier] has seen the complete initial tree by the time this returns.
     */
    public suspend fun setContent(content: @Composable () -> Unit) {
        GlobalSnapshotManager.ensureStarted()
        runnerJob = scope.launch {
            try {
                recomposer.runRecomposeAndApplyChanges()
            } catch (e: CancellationException) {
                // Normal shutdown via close().
            } catch (t: Throwable) {
                // Recorded rather than rethrown: awaitIdle() reports it to whoever is driving the
                // session, instead of it escaping to the global uncaught-exception handler.
                failure = t
            }
        }
        // Wait for the loop to actually be running before composing, otherwise awaitIdle() below
        // can observe an Inactive recomposer and wait forever.
        recomposer.currentState.first { it == Recomposer.State.Idle || it == Recomposer.State.PendingWork }
        withContext(context) { composition.setContent(content) }
        awaitIdle()
    }

    /**
     * Applies [block] as a single atomic state mutation and returns once the resulting
     * recomposition has settled.
     *
     * The mutable snapshot is the batching unit: however many state objects [block] writes, the
     * runtime sees one apply notification and performs one recomposition pass, so an event handler
     * that touches ten fields still yields exactly one patch message.
     */
    public suspend fun <T> transact(block: () -> T): T {
        val result = withContext(context) { Snapshot.withMutableSnapshot(block) }
        awaitIdle()
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
     * Suspends until every pending invalidation has been recomposed and applied, or until the
     * composition dies — a composable that throws takes the recomposer down, and the caller driving
     * the session needs to hear about it rather than hang.
     */
    public suspend fun awaitIdle() {
        withContext(context) {
            // Publish anything written to the global snapshot from outside a transact block; the
            // recomposer records the invalidation synchronously on this thread.
            Snapshot.sendApplyNotifications()
            yield()
            val runner = runnerJob
            if (runner == null) return@withContext
            coroutineScope {
                val idle = async { recomposer.currentState.first { it == Recomposer.State.Idle } }
                // A failed composition leaves the recomposer Inactive, not ShutDown, so its state
                // alone can never signal the difference between "not started" and "died". The
                // runner job completing is the unambiguous signal.
                select<Unit> {
                    idle.onAwait { }
                    runner.onJoin { }
                }
                idle.cancel()
            }
        }
        failure?.let { throw it }
    }

    override fun close() {
        recomposer.cancel()
        composition.dispose()
        scope.cancel()
    }
}
