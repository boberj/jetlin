package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import jetlin.protocol.ClientMessage
import jetlin.protocol.ServerMessage
import jetlin.runtime.CompositionHost
import jetlin.runtime.FramePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One live server-side view: a composition, its virtual DOM, and the patch stream it produces.
 *
 * Transport-agnostic on purpose — this class knows nothing about WebSockets or Ktor, so it can be
 * driven directly from a test without a browser or a server in the loop.
 */
public class LiveView(
    framePolicy: FramePolicy = FramePolicy.Immediate,
    private val content: @Composable () -> Unit,
) : AutoCloseable {

    public val owner: HtmlOwner = HtmlOwner()
    private val host = CompositionHost(HtmlApplier(owner), framePolicy)

    private var rev = 0L

    /** Highest client event sequence whose effects are folded into the next patch. */
    @Volatile
    private var ack = 0L

    /** Composes the initial tree. Ops from the first pass are dropped: first paint ships as HTML. */
    public suspend fun start() {
        host.setContent {
            CompositionLocalProvider(LocalHtmlOwner provides owner) {
                content()
            }
        }
        host.confined { owner.drainOps() }
    }

    /** Server-rendered HTML for the initial page load. */
    public suspend fun renderHtml(): String = host.confined { renderToHtml(owner) }

    /**
     * The whole tree as a single message, for a client attaching or rejoining.
     *
     * Buffered ops are discarded first. A composition keeps running while no client is attached —
     * the clock in the sample keeps ticking — so by reconnect time the buffer holds mutations
     * describing a tree the arriving client has never seen. The full snapshot subsumes them, and
     * replaying them on top of it would apply indices twice.
     */
    public suspend fun reset(): ServerMessage.Reset = host.confined {
        owner.drainOps()
        ServerMessage.Reset(++rev, owner.snapshotChildren())
    }

    /**
     * Applies one client event and waits for the resulting recomposition to settle. Any ops it
     * produced leave through [patches]; keeping a single writer avoids two coroutines splitting
     * one logical patch between two messages.
     */
    public suspend fun dispatch(event: ClientMessage.Event) {
        ack = event.seq
        host.transact { owner.dispatch(event.node, event.event, event.payload) }
    }

    /**
     * Patches produced by this view, whatever caused them — a client event, a `LaunchedEffect`, or
     * a background coroutine writing shared state.
     *
     * Sending updates to the client needs no separate API: when state a composable read changes,
     * that composable recomposes, the applier records ops, and they arrive here.
     */
    public val patches: Flow<ServerMessage.Patch> = flow {
        for (signal in owner.dirtySignals) {
            host.awaitIdle()
            val patch = host.confined {
                val ops = owner.drainOps()
                if (ops.isEmpty()) null else ServerMessage.Patch(++rev, ack, ops)
            }
            if (patch != null) emit(patch)
        }
    }

    override fun close(): Unit = host.close()
}
