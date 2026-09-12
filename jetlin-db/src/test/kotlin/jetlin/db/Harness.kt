package jetlin.db

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import jetlin.html.HtmlApplier
import jetlin.html.HtmlOwner
import jetlin.html.LocalHtmlOwner
import jetlin.html.renderToHtml
import jetlin.protocol.Op
import jetlin.runtime.CompositionHost

/**
 * One composition, and the ops it emitted.
 *
 * The same shape as `HtmlApplierTest`'s harness in `:jetlin-html`, for the same reason: the only
 * honest way to check that reading a record subscribes a reader is to run a real composition and look
 * at what crossed the wire. `:jetlin-db` itself does not depend on `:jetlin-html` — this is a test
 * dependency, and the boundary is deliberate.
 */
internal suspend fun harness(content: @Composable () -> Unit): Harness =
    Harness(content).also { it.start() }

internal class Harness(private val content: @Composable () -> Unit) : AutoCloseable {
    private val owner = HtmlOwner()
    private val host = CompositionHost(HtmlApplier(owner))

    /** Recomposition passes that produced changes; used to assert writes were batched. */
    val changeCount: Long get() = host.changeCount

    suspend fun start() {
        host.setContent {
            CompositionLocalProvider(LocalHtmlOwner provides owner) { content() }
        }
        drain()
    }

    suspend fun drain(): List<Op> = host.confined { owner.drainOps() }

    /** The rendered markup, for asserting what a session is currently showing. */
    suspend fun html(): String = host.confined { renderToHtml(owner) }

    /**
     * Writes state from outside this composition, as another session or a background job would, and
     * waits for the session to settle.
     *
     * One mutable snapshot, because that is what a write from elsewhere is: however many fields it
     * touches, the recomposer sees one apply. Nothing subscribes this session to the records —
     * `awaitIdle` publishes the apply and the session recomposes because it read them.
     */
    suspend fun write(block: () -> Unit) {
        Snapshot.withMutableSnapshot(block)
        settle()
    }

    /**
     * Waits for a change made elsewhere — another session's transaction, a background job — to have
     * been applied here.
     */
    suspend fun settle(): Unit = host.awaitIdle()

    override fun close(): Unit = host.close()
}
