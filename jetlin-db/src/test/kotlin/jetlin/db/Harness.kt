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
 * Runs a single composition and records the ops it emits.
 *
 * It works like the harness in `:jetlin-html`'s `HtmlApplierTest`. Running a real composition and
 * checking the ops it sends is the most direct way to test that reading a record subscribes the
 * reader. `:jetlin-db` itself doesn't depend on `:jetlin-html`. This is a dependency of the tests
 * only.
 */
internal suspend fun harness(content: @Composable () -> Unit): Harness =
    Harness(content).also { it.start() }

/** A composition over an HTML tree, with helpers for checking what a write changed. See [harness]. */
internal class Harness(private val content: @Composable () -> Unit) : AutoCloseable {
    private val owner = HtmlOwner()
    private val host = CompositionHost(HtmlApplier(owner))

    /**
     * The number of recomposition passes that produced changes. Tests use it to check that writes
     * were batched.
     */
    val changeCount: Long get() = host.changeCount

    suspend fun start() {
        host.setContent {
            CompositionLocalProvider(LocalHtmlOwner provides owner) { content() }
        }
        drain()
    }

    suspend fun drain(): List<Op> = host.confined { owner.drainOps() }

    /** Returns the rendered HTML, for checking what the session shows now. */
    suspend fun html(): String = host.confined { renderToHtml(owner) }

    /**
     * Writes state from outside this composition, as another session or a background job would,
     * then waits for the session to settle.
     *
     * The write uses one mutable snapshot, so the recomposer sees one apply however many fields
     * change. Nothing explicitly subscribes this session to the records: `awaitIdle` publishes the
     * apply, and the session recomposes because it read them.
     */
    suspend fun write(block: () -> Unit) {
        Snapshot.withMutableSnapshot(block)
        settle()
    }

    /**
     * Waits until a change made elsewhere, such as another session's transaction or a background
     * job, has been applied to this composition.
     */
    suspend fun settle(): Unit = host.awaitIdle()

    override fun close(): Unit = host.close()
}
