package jetlin.runtime

import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Turns writes to the global snapshot into apply notifications, for the whole process.
 *
 * Compose state written outside a composition, such as from a background coroutine, a `Flow`
 * collector, or a pub/sub listener, lands in the global snapshot. Every
 * [androidx.compose.runtime.Recomposer] ignores it until something calls
 * [Snapshot.sendApplyNotifications], and nothing calls that automatically. Without this object,
 * state changed by a background job would never trigger a recomposition, server-driven updates
 * would never appear, and nothing would report an error.
 *
 * A global write observer signals that something changed. A coroutine turns that signal into the
 * notification the recomposers wait for.
 *
 * The channel is [Channel.CONFLATED] so that a burst of writes collapses into one notification.
 * This is the first of two stages that coalesce updates. [FramePolicy] is the second.
 */
internal object GlobalSnapshotManager {
    private val started = AtomicBoolean(false)
    private val writes = Channel<Unit>(Channel.CONFLATED)

    /** Starts the pump if it isn't running yet. Safe to call from any thread, any number of times. */
    fun ensureStarted() {
        if (started.compareAndSet(false, true)) {
            CoroutineScope(Dispatchers.Default).launch {
                for (signal in writes) {
                    Snapshot.sendApplyNotifications()
                }
            }
            Snapshot.registerGlobalWriteObserver { writes.trySend(Unit) }
        }
    }
}
