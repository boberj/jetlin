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
 * pub/sub listener — lands in the global snapshot, where it stays invisible to every
 * [androidx.compose.runtime.Recomposer] until someone calls [Snapshot.sendApplyNotifications].
 * Nothing calls it automatically, so without this class the failure is silent: state changed by a
 * background job would never cause a recomposition, and server-driven updates would never appear.
 *
 * Registering a global write observer gives us the "something changed" signal; the coroutine turns
 * it into the notification the recomposers are waiting for.
 *
 * The channel is [Channel.CONFLATED] on purpose: a burst of writes collapses into a single
 * notification, which is the first of the two coalescing stages (the second is [FramePolicy]).
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
