package jetlin.runtime

import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Process-wide pump that turns global snapshot writes into apply notifications.
 *
 * Compose state written *outside* a composition — from a background coroutine, a Flow collector, a
 * pub/sub listener — lands in the global snapshot and stays invisible to every [androidx.compose.runtime.Recomposer]
 * until someone calls [Snapshot.sendApplyNotifications]. On Android the UI dispatcher does this; off
 * Android nobody does, and the failure mode is silent: server-pushed state updates simply never
 * recompose. Molecule and Mosaic each carry their own version of this class for the same reason.
 *
 * The channel is [Channel.CONFLATED] on purpose: a burst of writes collapses into a single
 * notification, which is the first of the two coalescing stages in Jetlin (the second is
 * [FramePolicy]).
 */
internal object GlobalSnapshotManager {
    private val started = AtomicBoolean(false)
    private val writes = Channel<Unit>(Channel.CONFLATED)

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
